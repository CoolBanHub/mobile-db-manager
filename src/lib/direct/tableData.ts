import type { ColumnInfo, MobileConnectionSummary, MobileTableDataResponse, MobileTableTransactionChange, MobileTableTransactionResult } from "../mobileTypes";
import { listDirectConnections } from "./connections";
import { id } from "./localStore";
import { loadDirectMetadata } from "./metadata";
import { DirectApiError, DirectDatabase, requireNative } from "./native";
import { executeDirectQuery } from "./query";

function quoteIdentifier(value: unknown, dbType: string): string {
  const text = String(value ?? "");
  if (!text || text.length > 256 || text.includes("\0")) throw new DirectApiError("数据库对象名称无效");
  if (dbType === "mysql") return `\`${text.replaceAll("`", "``")}\``;
  if (dbType === "sqlserver") return `[${text.replaceAll("]", "]]")}]`;
  return `"${text.replaceAll('"', '""')}"`;
}

function sqlLiteral(value: unknown): string {
  return `'${String(value ?? "").replaceAll("'", "''")}'`;
}

async function directTableContext(payload: Record<string, unknown>) {
  requireNative();
  const connections = await listDirectConnections();
  const connection = connections.find((item) => item.id === payload.connectionId);
  if (!connection) throw new DirectApiError("连接不存在");
  const columns = await loadDirectMetadata<ColumnInfo[]>("columns", {
    connectionId: connection.id,
    database: String(payload.database ?? ""),
    schema: String(payload.schema ?? ""),
    table: String(payload.table ?? ""),
  });
  if (!columns.length) throw new DirectApiError("表没有可见字段或已不存在");
  return { connection, columns };
}

function assertDirectTableWrite(payload: Record<string, unknown>, connection: MobileConnectionSummary) {
  if (connection.readOnly) throw new DirectApiError("此连接已设置为只读");
  if (connection.isProduction && String(payload.productionConfirmation ?? "") !== connection.name) {
    throw new DirectApiError("生产连接写入前必须输入完整连接名称");
  }
}

export async function buildDirectTableTemplate(payload: Record<string, unknown>): Promise<string> {
  return tableSelectSql(payload, false);
}

export async function loadDirectTableData(payload: Record<string, unknown>): Promise<MobileTableDataResponse> {
  const limit = Math.min(100, Math.max(1, Number(payload.limit ?? 30)));
  const offset = Math.max(0, Number(payload.offset ?? 0));
  const context = await directTableContext(payload);
  const sql = await tableSelectSql(payload, true, limit + 1, offset, context.connection);
  const result = await executeDirectQuery(
    {
      ...payload,
      sql,
      executionId: id(),
      offset: 0,
      pageSize: limit + 1,
    },
    true,
    false,
  );
  const hasMore = result.rows.length > limit;
  result.rows = result.rows.slice(0, limit);
  result.has_more = hasMore;
  result.truncated = hasMore;
  const hasPrimaryKey = context.columns.some((column) => column.is_primary_key);
  return {
    result,
    offset,
    limit,
    hasMore,
    selectTemplate: await tableSelectSql(payload, false, 30, 0, context.connection),
    columnMeta: context.columns,
    editable: !context.connection.readOnly && hasPrimaryKey,
    editBlockReason: context.connection.readOnly
      ? "此连接已设置为只读"
      : hasPrimaryKey
        ? null
        : "表没有主键，无法安全定位要修改的行",
    connectionName: context.connection.name,
    isProduction: context.connection.isProduction,
  };
}

export async function commitDirectTableTransaction(payload: {
  connectionId: string;
  database: string;
  schema: string | null;
  table: string;
  changes: MobileTableTransactionChange[];
  productionConfirmation?: string;
}): Promise<MobileTableTransactionResult> {
  const { connection } = await directTableContext(payload);
  assertDirectTableWrite(payload, connection);
  if (!payload.changes.length) throw new DirectApiError("没有待提交的表数据变更");
  if (payload.changes.length > 100) throw new DirectApiError("一次最多提交 100 项表数据变更");
  // The native side reloads columns and primary keys and builds parameterized SQL;
  // the WebView never supplies executable transaction statements.
  return (
    await DirectDatabase.tableTransaction({
      ...payload,
      confirmedWrite: true,
    })
  ).value as MobileTableTransactionResult;
}

async function tableSelectSql(
  payload: Record<string, unknown>,
  includeControls: boolean,
  limit = 30,
  offset = 0,
  resolvedConnection?: MobileConnectionSummary,
): Promise<string> {
  requireNative();
  const connection = resolvedConnection ?? (await listDirectConnections()).find((item) => item.id === payload.connectionId);
  if (!connection) throw new DirectApiError("连接不存在");
  const dbType = connection.dbType;
  const table = [payload.schema, payload.table]
    .filter(Boolean)
    .map((item) => quoteIdentifier(item, dbType))
    .join(".");
  let sql = `SELECT * FROM ${table}`;
  if (includeControls) {
    const filters = Array.isArray(payload.filters) ? (payload.filters as Array<Record<string, unknown>>) : [];
    const clauses = filters.map((filter) => {
      const column = quoteIdentifier(filter.column, dbType);
      const value = sqlLiteral(filter.value);
      switch (filter.operator) {
        case "notEquals":
          return `${column} <> ${value}`;
        case "contains":
          return `${column} LIKE ${sqlLiteral(`%${String(filter.value ?? "")}%`)}`;
        case "startsWith":
          return `${column} LIKE ${sqlLiteral(`${String(filter.value ?? "")}%`)}`;
        case "endsWith":
          return `${column} LIKE ${sqlLiteral(`%${String(filter.value ?? "")}`)}`;
        case "greaterThan":
          return `${column} > ${value}`;
        case "greaterThanOrEqual":
          return `${column} >= ${value}`;
        case "lessThan":
          return `${column} < ${value}`;
        case "lessThanOrEqual":
          return `${column} <= ${value}`;
        case "isNull":
          return `${column} IS NULL`;
        case "isNotNull":
          return `${column} IS NOT NULL`;
        default:
          return `${column} = ${value}`;
      }
    });
    if (clauses.length) sql += ` WHERE ${clauses.join(" AND ")}`;
    const sort = payload.sort as Record<string, unknown> | null;
    if (sort?.column) {
      sql += ` ORDER BY ${quoteIdentifier(sort.column, dbType)} ${sort.direction === "desc" ? "DESC" : "ASC"}`;
    } else if (dbType === "sqlserver") {
      sql += " ORDER BY (SELECT NULL)";
    }
    sql += dbType === "sqlserver" ? ` OFFSET ${offset} ROWS FETCH NEXT ${limit} ROWS ONLY` : ` LIMIT ${limit} OFFSET ${offset}`;
  }
  return `${sql};`;
}
