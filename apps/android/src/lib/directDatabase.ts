import { Capacitor, registerPlugin } from "@capacitor/core";
import type { ColumnInfo, MobileConnectionDraft, MobileConnectionEditor, MobileConnectionSummary, MobileHistoryEntry, MobileHistoryPage, QueryResult, SavedSqlFile, SavedSqlFolder, SavedSqlLibrary } from "./mobileApi";

export const DIRECT_DATABASE_URL = "dbx-direct://local";

interface NativeResult<T> {
  value: T;
}

interface DirectDatabasePlugin {
  listConnections(): Promise<NativeResult<MobileConnectionSummary[]>>;
  getConnection(options: { id: string }): Promise<NativeResult<MobileConnectionEditor>>;
  saveConnection(options: { connection: MobileConnectionDraft }): Promise<NativeResult<MobileConnectionSummary>>;
  deleteConnection(options: { id: string }): Promise<NativeResult<{ ok: boolean }>>;
  testConnection(options: { connection: MobileConnectionDraft }): Promise<NativeResult<{ message: string }>>;
  listDatabases(options: { connection: MobileConnectionDraft }): Promise<NativeResult<Array<{ name: string }>>>;
  metadata(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  query(options: Record<string, unknown>): Promise<NativeResult<QueryResult>>;
  cancel(options: { executionId: string }): Promise<NativeResult<{ cancelled: boolean }>>;
}

const DirectDatabase = registerPlugin<DirectDatabasePlugin>("DirectDatabase");
const HISTORY_KEY = "dbx-mobile.direct.history.v1";
const SAVED_SQL_KEY = "dbx-mobile.direct.saved-sql.v1";

interface DirectRequest {
  method: string;
  path: string;
  params?: Record<string, string | number | undefined>;
  body?: unknown;
}

function requireNative() {
  if (!Capacitor.isNativePlatform()) {
    throw new DirectApiError("数据库直连只能在 Android App 中运行；浏览器开发模式没有本机数据库驱动", 501);
  }
}

export class DirectApiError extends Error {
  constructor(
    message: string,
    public readonly status = 400,
  ) {
    super(message);
    this.name = "DirectApiError";
  }
}

export async function listDirectDatabases(connection: MobileConnectionDraft): Promise<string[]> {
  requireNative();
  const result = (await DirectDatabase.listDatabases({ connection })).value;
  return [...new Set(result.map((item) => item.name).filter(Boolean))].sort((left, right) => left.localeCompare(right));
}

function id(): string {
  return globalThis.crypto?.randomUUID?.() ?? `local-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function readJson<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(key) ?? "") as T;
  } catch {
    return fallback;
  }
}

function writeJson(key: string, value: unknown) {
  localStorage.setItem(key, JSON.stringify(value));
}

function pathParts(path: string) {
  return path.split("?")[0].split("/").filter(Boolean).map(decodeURIComponent);
}

function stringParam(params: Record<string, string | number | undefined>, name: string): string {
  const value = params[name];
  return value === undefined ? "" : String(value);
}

async function metadata(kind: string, params: Record<string, string | number | undefined>) {
  requireNative();
  return (
    await DirectDatabase.metadata({
      kind,
      connectionId: stringParam(params, "connection_id"),
      database: stringParam(params, "database"),
      schema: stringParam(params, "schema"),
      table: stringParam(params, "table"),
      filter: stringParam(params, "filter"),
      limit: Number(params.limit ?? 100),
      offset: Number(params.offset ?? 0),
    })
  ).value;
}

function historyEntries(): MobileHistoryEntry[] {
  return readJson<MobileHistoryEntry[]>(HISTORY_KEY, []);
}

function saveHistory(body: Record<string, unknown>, result: QueryResult | null, error: unknown, started: number) {
  if (Number(body.offset ?? 0) !== 0) return;
  const entries = historyEntries();
  const entry: MobileHistoryEntry = {
    id: id(),
    connectionId: String(body.connectionId ?? ""),
    connectionName: String(body.connectionName ?? ""),
    database: String(body.database ?? ""),
    schema: body.schema ? String(body.schema) : null,
    sql: String(body.sql ?? ""),
    executedAt: new Date().toISOString(),
    executionTimeMs: result?.execution_time_ms ?? Date.now() - started,
    success: !error,
    error: error instanceof Error ? error.message : error ? String(error) : null,
  };
  writeJson(HISTORY_KEY, [entry, ...entries].slice(0, 1_000));
}

async function executeQuery(body: Record<string, unknown>, readOnly: boolean): Promise<QueryResult> {
  requireNative();
  const started = Date.now();
  try {
    const result = (
      await DirectDatabase.query({
        connectionId: body.connectionId,
        database: body.database,
        schema: body.schema,
        sql: body.sql,
        executionId: body.executionId,
        offset: Number(body.offset ?? 0),
        pageSize: Number(body.pageSize ?? 50),
        readOnly,
        confirmedWrite: body.confirmedWrite,
        productionConfirmation: body.productionConfirmation,
      })
    ).value;
    saveHistory(body, result, null, started);
    return result;
  } catch (error) {
    saveHistory(body, null, error, started);
    throw error;
  }
}

function library(): SavedSqlLibrary {
  return readJson<SavedSqlLibrary>(SAVED_SQL_KEY, { folders: [], files: [] });
}

function saveLibrary(value: SavedSqlLibrary) {
  writeJson(SAVED_SQL_KEY, value);
}

function savedSql(body: Record<string, unknown>): SavedSqlFile {
  const current = library();
  const now = new Date().toISOString();
  const existing = current.files.find((item) => item.id === body.id);
  const file: SavedSqlFile = {
    id: existing?.id ?? id(),
    connectionId: String(body.connectionId ?? existing?.connectionId ?? ""),
    folderId: body.folderId === undefined ? (existing?.folderId ?? null) : body.folderId ? String(body.folderId) : null,
    name: String(body.name ?? existing?.name ?? "query.sql").replace(/\.sql$/i, "") + ".sql",
    database: String(body.database ?? existing?.database ?? ""),
    schema: body.schema === undefined ? (existing?.schema ?? null) : body.schema ? String(body.schema) : null,
    sql: String(body.sql ?? existing?.sql ?? ""),
    sqlLoaded: true,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };
  current.files = [file, ...current.files.filter((item) => item.id !== file.id)];
  saveLibrary(current);
  return file;
}

function response(value: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(error: unknown): Response {
  const status = error instanceof DirectApiError ? error.status : 400;
  const message = error instanceof Error ? error.message : String(error);
  return response({ error: message }, status);
}

export async function directJsonRequest<T>({ method, path, params = {}, body }: DirectRequest): Promise<T> {
  const parts = pathParts(path);
  const payload = (body ?? {}) as Record<string, unknown>;

  try {
    if (parts[2] === "connections") {
      requireNative();
      if (method === "GET" && parts.length === 3) return (await DirectDatabase.listConnections()).value as T;
      if (method === "GET" && parts[3]) return (await DirectDatabase.getConnection({ id: parts[3] })).value as T;
      if (method === "POST" && parts[3] === "test") return (await DirectDatabase.testConnection({ connection: payload as unknown as MobileConnectionDraft })).value as T;
      if (method === "POST" && parts[3] === "save") return (await DirectDatabase.saveConnection({ connection: payload as unknown as MobileConnectionDraft })).value as T;
      if (method === "DELETE" && parts[3]) return (await DirectDatabase.deleteConnection({ id: parts[3] })).value as T;
    }

    if (parts[2] === "schema" && method === "GET") {
      const kind = parts[3] === "foreign-keys" ? "foreign-keys" : parts[3];
      return (await metadata(kind, params)) as T;
    }

    if (parts[2] === "query" && method === "POST" && parts.length === 3) {
      return (await executeQuery(payload, true)) as T;
    }
    if (parts[2] === "query" && method === "POST" && parts[3] === "advanced") {
      return [await executeQuery({ ...payload, offset: 0, pageSize: 500 }, false)] as T;
    }
    if (parts[2] === "query" && method === "POST" && parts[3] === "explain") {
      const result = await executeQuery({ ...payload, sql: `EXPLAIN ${String(payload.sql ?? "")}`, offset: 0, pageSize: 200 }, true);
      return result.rows.map((row) => row.join(" | ")).join("\n") as T;
    }
    if (parts[2] === "query" && method === "DELETE" && parts[3]) {
      requireNative();
      return (await DirectDatabase.cancel({ executionId: parts[3] })).value as T;
    }
    if (parts[2] === "query" && parts[3] === "ai") {
      throw new DirectApiError("直连模式未配置 AI 服务；请先使用本地 SQL 编辑与补全", 501);
    }

    if (parts[2] === "table-template" && method === "POST") {
      return { sql: await tableSelectSql(payload, false) } as T;
    }
    if (parts[2] === "table-data" && method === "POST") {
      if (parts[3] === "update") {
        return (await updateDirectTableCell(payload)) as T;
      }
      if (parts[3] === "insert") {
        return (await insertDirectTableRow(payload)) as T;
      }
      if (parts[3] === "delete") {
        return (await deleteDirectTableRow(payload)) as T;
      }
      const limit = Math.min(100, Math.max(1, Number(payload.limit ?? 30)));
      const offset = Math.max(0, Number(payload.offset ?? 0));
      const context = await directTableContext(payload);
      const sql = await tableSelectSql(payload, true, limit + 1, offset);
      const result = await executeQuery(
        {
          ...payload,
          sql,
          executionId: id(),
          offset: 0,
          pageSize: limit + 1,
        },
        true,
      );
      const hasMore = result.rows.length > limit;
      result.rows = result.rows.slice(0, limit);
      result.has_more = hasMore;
      result.truncated = hasMore;
      return {
        result,
        offset,
        limit,
        hasMore,
        selectTemplate: await tableSelectSql(payload, false),
        columnMeta: context.columns,
        editable: !context.connection.readOnly && context.columns.some((column) => column.is_primary_key),
        editBlockReason: context.connection.readOnly ? "此连接已设置为只读" : context.columns.some((column) => column.is_primary_key) ? null : "表没有主键，无法安全定位要修改的行",
        connectionName: context.connection.name,
        isProduction: context.connection.isProduction,
      } as T;
    }

    if (parts[2] === "history") {
      let entries = historyEntries();
      if (method === "DELETE" && parts.length === 3) {
        writeJson(HISTORY_KEY, []);
        return { ok: true } as T;
      }
      if (method === "DELETE" && parts[3]) {
        writeJson(
          HISTORY_KEY,
          entries.filter((item) => item.id !== parts[3]),
        );
        return { ok: true } as T;
      }
      if (method === "POST" && parts[3] === "search") {
        const query = String(payload.searchText ?? "").toLocaleLowerCase();
        const connectionId = String(payload.connectionId ?? "");
        entries = entries.filter((item) => (!query || `${item.sql} ${item.database} ${item.connectionName}`.toLocaleLowerCase().includes(query)) && (!connectionId || item.connectionId === connectionId));
        if (typeof payload.success === "boolean") entries = entries.filter((item) => item.success === payload.success);
        if (payload.startedAt) entries = entries.filter((item) => item.executedAt >= String(payload.startedAt));
        if (payload.endedAt) entries = entries.filter((item) => item.executedAt <= String(payload.endedAt));
        const page: MobileHistoryPage = { entries: entries.slice(0, 50), nextCursor: null, total: entries.length };
        return page as T;
      }
    }

    if (parts[2] === "saved-sql") {
      const current = library();
      if (method === "GET" && parts[3]) {
        return (current.files.find((item) => item.id === parts[3]) ?? null) as T;
      }
      if (method === "GET") return current as T;
      if (method === "POST" && parts.length === 3) return savedSql(payload) as T;
      if (method === "DELETE" && parts[3] && parts[3] !== "folders") {
        current.files = current.files.filter((item) => item.id !== parts[3]);
        saveLibrary(current);
        return { ok: true } as T;
      }
      if (method === "POST" && parts[3] === "folders") {
        const now = new Date().toISOString();
        const existing = current.folders.find((item) => item.id === payload.id);
        const folder: SavedSqlFolder = {
          id: existing?.id ?? id(),
          connectionId: String(payload.connectionId ?? existing?.connectionId ?? ""),
          parentFolderId: payload.parentFolderId ? String(payload.parentFolderId) : null,
          name: String(payload.name ?? existing?.name ?? "新建文件夹"),
          updatedAt: now,
        };
        current.folders = [folder, ...current.folders.filter((item) => item.id !== folder.id)];
        saveLibrary(current);
        return folder as T;
      }
      if (method === "DELETE" && parts[3] === "folders" && parts[4]) {
        current.folders = current.folders.filter((item) => item.id !== parts[4] && item.parentFolderId !== parts[4]);
        current.files = current.files.filter((item) => item.folderId !== parts[4]);
        saveLibrary(current);
        return { ok: true } as T;
      }
    }

    throw new DirectApiError(`Android 直连尚未实现：${method} ${path}`, 501);
  } catch (error) {
    if (error instanceof DirectApiError) throw error;
    throw new DirectApiError(error instanceof Error ? error.message : String(error));
  }
}

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
  const connections = (await DirectDatabase.listConnections()).value;
  const connection = connections.find((item) => item.id === payload.connectionId);
  if (!connection) throw new DirectApiError("连接不存在", 404);
  const columns = (await metadata("columns", {
    connection_id: connection.id,
    database: String(payload.database ?? ""),
    schema: String(payload.schema ?? ""),
    table: String(payload.table ?? ""),
  })) as ColumnInfo[];
  if (!columns.length) throw new DirectApiError("表没有可见字段或已不存在", 404);
  return { connection, columns };
}

function directUpdateLiteral(value: unknown, dbType: string): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return value ? "TRUE" : "FALSE";
  const text = typeof value === "object" ? JSON.stringify(value) : String(value);
  const escaped = text.replaceAll("'", "''");
  return `'${dbType === "mysql" ? escaped.replaceAll("\\", "\\\\") : escaped}'`;
}

async function updateDirectTableCell(payload: Record<string, unknown>) {
  const { connection, columns } = await directTableContext(payload);
  assertDirectTableWrite(payload, connection);
  const columnIndex = columns.findIndex((column) => column.name === payload.column);
  if (columnIndex < 0) throw new DirectApiError("要修改的字段已不存在");
  const primaryKeys = columns.filter((column) => column.is_primary_key);
  if (!primaryKeys.length) throw new DirectApiError("表没有主键，无法安全定位要修改的行");
  const originalRow = Array.isArray(payload.originalRow) ? payload.originalRow : [];
  if (originalRow.length !== columns.length) throw new DirectApiError("当前行与表字段不匹配，请刷新后重试");
  const primaryPredicates = primaryKeys.map((primaryKey) => {
    const index = columns.findIndex((column) => column.name === primaryKey.name);
    const value = originalRow[index];
    const identifier = quoteIdentifier(primaryKey.name, connection.dbType);
    return value === null || value === undefined ? `${identifier} IS NULL` : `${identifier} = ${directUpdateLiteral(value, connection.dbType)}`;
  });
  const table = [payload.schema, payload.table]
    .filter(Boolean)
    .map((item) => quoteIdentifier(item, connection.dbType))
    .join(".");
  const column = quoteIdentifier(columns[columnIndex].name, connection.dbType);
  const sql = `UPDATE ${table} SET ${column} = ${directUpdateLiteral(payload.value, connection.dbType)} WHERE ${primaryPredicates.join(" AND ")};`;
  const result = await executeQuery(
    {
      ...payload,
      sql,
      executionId: id(),
      offset: 0,
      pageSize: 1,
      connectionName: connection.name,
      confirmedWrite: true,
    },
    false,
  );
  return { affectedRows: result.affected_rows };
}

function assertDirectTableWrite(payload: Record<string, unknown>, connection: MobileConnectionSummary) {
  if (connection.readOnly) throw new DirectApiError("此连接已设置为只读");
  if (connection.isProduction && String(payload.productionConfirmation ?? "") !== connection.name) {
    throw new DirectApiError("生产连接写入前必须输入完整连接名称");
  }
}

function directQualifiedTable(payload: Record<string, unknown>, dbType: string) {
  return [payload.schema, payload.table]
    .filter(Boolean)
    .map((item) => quoteIdentifier(item, dbType))
    .join(".");
}

async function insertDirectTableRow(payload: Record<string, unknown>) {
  const { connection, columns } = await directTableContext(payload);
  assertDirectTableWrite(payload, connection);
  const row = Array.isArray(payload.row) ? payload.row : [];
  const providedColumns = Array.isArray(payload.providedColumns) ? payload.providedColumns : row.map(() => true);
  if (row.length !== columns.length || providedColumns.length !== columns.length) {
    throw new DirectApiError("新增行与表字段不匹配，请刷新后重试");
  }
  const included = columns.map((column, index) => ({ column, index })).filter(({ index }) => providedColumns[index]);
  const table = directQualifiedTable(payload, connection.dbType);
  const sql = included.length
    ? `INSERT INTO ${table} (${included.map(({ column }) => quoteIdentifier(column.name, connection.dbType)).join(", ")}) VALUES (${included.map(({ index }) => directUpdateLiteral(row[index], connection.dbType)).join(", ")});`
    : connection.dbType === "mysql"
      ? `INSERT INTO ${table} () VALUES ();`
      : `INSERT INTO ${table} DEFAULT VALUES;`;
  const result = await executeQuery(
    {
      ...payload,
      sql,
      executionId: id(),
      offset: 0,
      pageSize: 1,
      connectionName: connection.name,
      confirmedWrite: true,
    },
    false,
  );
  return { affectedRows: result.affected_rows };
}

async function deleteDirectTableRow(payload: Record<string, unknown>) {
  const { connection, columns } = await directTableContext(payload);
  assertDirectTableWrite(payload, connection);
  const originalRow = Array.isArray(payload.originalRow) ? payload.originalRow : [];
  if (originalRow.length !== columns.length) {
    throw new DirectApiError("当前行与表字段不匹配，请刷新后重试");
  }
  const primaryKeys = columns.filter((column) => column.is_primary_key);
  if (!primaryKeys.length) throw new DirectApiError("表没有主键，无法安全删除数据");
  const predicates = primaryKeys.map((primaryKey) => {
    const index = columns.findIndex((column) => column.name === primaryKey.name);
    const value = originalRow[index];
    const identifier = quoteIdentifier(primaryKey.name, connection.dbType);
    return value === null || value === undefined ? `${identifier} IS NULL` : `${identifier} = ${directUpdateLiteral(value, connection.dbType)}`;
  });
  const sql = `DELETE FROM ${directQualifiedTable(payload, connection.dbType)} WHERE ${predicates.join(" AND ")};`;
  const result = await executeQuery(
    {
      ...payload,
      sql,
      executionId: id(),
      offset: 0,
      pageSize: 1,
      connectionName: connection.name,
      confirmedWrite: true,
    },
    false,
  );
  return { affectedRows: result.affected_rows };
}

async function tableSelectSql(payload: Record<string, unknown>, includeControls: boolean, limit = 30, offset = 0): Promise<string> {
  requireNative();
  const connections = (await DirectDatabase.listConnections()).value;
  const connection = connections.find((item) => item.id === payload.connectionId);
  if (!connection) throw new DirectApiError("连接不存在", 404);
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

export async function directFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const url = new URL(path, "https://local.invalid");
  let body: unknown;
  if (typeof init.body === "string" && init.body) body = JSON.parse(init.body);
  try {
    const value = await directJsonRequest({
      method: (init.method ?? "GET").toUpperCase(),
      path: url.pathname,
      params: Object.fromEntries(url.searchParams.entries()),
      body,
    });
    return response(value);
  } catch (error) {
    return failure(error);
  }
}
