import { Capacitor, registerPlugin } from "@capacitor/core";
import type {
  ColumnInfo,
  MobileConnectionDraft,
  MobileConnectionEditor,
  MobileConnectionSummary,
  MobileHistoryEntry,
  MobileHistoryPage,
  MobileEtcdEntry,
  MobileEtcdOverview,
  MobileEtcdPage,
  MobileMongoDocumentPage,
  MobileRedisKeyDetail,
  MobileRedisOverview,
  MobileRedisScanPage,
  MobileTableDataResponse,
  QueryResult,
  SavedSqlFile,
  SavedSqlFolder,
  SavedSqlLibrary,
} from "./mobileTypes";

interface NativeResult<T> {
  value: T;
}

interface DirectDatabasePlugin {
  listConnections(): Promise<NativeResult<MobileConnectionSummary[]>>;
  getConnection(options: { id: string }): Promise<NativeResult<MobileConnectionEditor>>;
  saveConnection(options: { connection: MobileConnectionDraft }): Promise<NativeResult<MobileConnectionSummary>>;
  deleteConnection(options: { id: string }): Promise<NativeResult<{ ok: boolean }>>;
  testConnection(options: { connection: MobileConnectionDraft }): Promise<NativeResult<{ message: string }>>;
  metadata(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  query(options: Record<string, unknown>): Promise<NativeResult<QueryResult>>;
  redis(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  mongo(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  etcd(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  cancel(options: { executionId: string }): Promise<NativeResult<{ cancelled: boolean }>>;
}

const DirectDatabase = registerPlugin<DirectDatabasePlugin>("DirectDatabase");
export const DIRECT_HISTORY_KEY = "mobile-db-manager.direct.history.v1";
const SAVED_SQL_KEY = "mobile-db-manager.direct.saved-sql.v1";

/**
 * 原生直连能力只允许在 Android 容器中调用。
 * 浏览器开发环境没有凭据保险箱和原生驱动，提前失败可避免误把敏感参数交给 Web 实现。
 */
function requireNative() {
  if (!Capacitor.isNativePlatform()) {
    throw new DirectApiError("数据库直连只能在 Android App 中运行");
  }
}

export class DirectApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DirectApiError";
  }
}

export async function listDirectConnections(): Promise<MobileConnectionSummary[]> {
  requireNative();
  return (await DirectDatabase.listConnections()).value;
}

export async function getDirectConnection(id: string): Promise<MobileConnectionEditor> {
  requireNative();
  return (await DirectDatabase.getConnection({ id })).value;
}

export async function saveDirectConnection(connection: MobileConnectionDraft): Promise<MobileConnectionSummary> {
  requireNative();
  return (await DirectDatabase.saveConnection({ connection })).value;
}

export async function deleteDirectConnection(id: string): Promise<void> {
  requireNative();
  await DirectDatabase.deleteConnection({ id });
}

export async function testDirectConnection(connection: MobileConnectionDraft): Promise<string> {
  requireNative();
  return (await DirectDatabase.testConnection({ connection })).value.message;
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

export type DirectMetadataKind =
  | "databases"
  | "schemas"
  | "tables"
  | "columns"
  | "indexes"
  | "foreign-keys"
  | "objects";

export interface DirectMetadataRequest {
  connectionId: string;
  database?: string;
  schema?: string;
  table?: string;
  filter?: string;
  limit?: number;
  offset?: number;
}

export async function loadDirectMetadata<T>(
  kind: DirectMetadataKind,
  request: DirectMetadataRequest,
): Promise<T> {
  requireNative();
  return (
    await DirectDatabase.metadata({
      kind,
      connectionId: request.connectionId,
      database: request.database ?? "",
      schema: request.schema ?? "",
      table: request.table ?? "",
      filter: request.filter ?? "",
      limit: request.limit ?? 100,
      offset: request.offset ?? 0,
    })
  ).value as T;
}

function historyEntries(): MobileHistoryEntry[] {
  return readJson<MobileHistoryEntry[]>(DIRECT_HISTORY_KEY, []);
}

function saveHistory(body: Record<string, unknown>, result: QueryResult | null, error: unknown, started: number) {
  // 翻页查询沿用同一次逻辑执行，只记录第一页，避免历史列表被分页操作淹没。
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
  writeJson(DIRECT_HISTORY_KEY, [entry, ...entries].slice(0, 1_000));
}

export async function executeDirectQuery(
  body: Record<string, unknown>,
  readOnly = true,
  recordHistory = true,
): Promise<QueryResult> {
  requireNative();
  const started = Date.now();
  try {
    // WebView 只传连接 ID 和本次查询参数；账号、密码始终由原生保险箱读取。
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
    if (recordHistory) saveHistory(body, result, null, started);
    return result;
  } catch (error) {
    if (recordHistory) saveHistory(body, null, error, started);
    throw error;
  }
}

export function loadDirectSavedSqlLibrary(): SavedSqlLibrary {
  return readJson<SavedSqlLibrary>(SAVED_SQL_KEY, { folders: [], files: [] });
}

function saveLibrary(value: SavedSqlLibrary) {
  writeJson(SAVED_SQL_KEY, value);
}

export function saveDirectSavedSql(body: Record<string, unknown>): SavedSqlFile {
  const current = loadDirectSavedSqlLibrary();
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

export async function cancelDirectQuery(executionId: string): Promise<void> {
  requireNative();
  await DirectDatabase.cancel({ executionId });
}

async function directRedis<T>(options: Record<string, unknown>): Promise<T> {
  requireNative();
  return (await DirectDatabase.redis(options)).value as T;
}

// 三类非关系型数据库共用“动作 + 参数”的窄桥接，具体命令白名单由原生层校验。
export function loadDirectRedisOverview(connectionId: string, database: number): Promise<MobileRedisOverview> {
  return directRedis<MobileRedisOverview>({
    connectionId,
    database: String(database),
    action: "overview",
  });
}

export function scanDirectRedisKeys(
  connectionId: string,
  database: number,
  cursor: string,
  pattern: string,
): Promise<MobileRedisScanPage> {
  return directRedis<MobileRedisScanPage>({
    connectionId,
    database: String(database),
    action: "scan",
    cursor,
    pattern,
    count: 100,
  });
}

export function loadDirectRedisKey(
  connectionId: string,
  database: number,
  key: string,
): Promise<MobileRedisKeyDetail> {
  return directRedis<MobileRedisKeyDetail>({
    connectionId,
    database: String(database),
    action: "detail",
    key,
  });
}

export function mutateDirectRedis(
  connectionId: string,
  database: number,
  action: string,
  payload: Record<string, unknown>,
  productionConfirmation?: string,
): Promise<{ result: unknown }> {
  // confirmedWrite 只是用户界面的显式确认；只读和生产环境校验仍在原生层强制执行。
  return directRedis<{ result: unknown }>({
    connectionId,
    database: String(database),
    action,
    ...payload,
    confirmedWrite: true,
    productionConfirmation: productionConfirmation ?? "",
  });
}

async function directMongo<T>(options: Record<string, unknown>): Promise<T> {
  requireNative();
  return (await DirectDatabase.mongo(options)).value as T;
}

export function loadDirectMongoDatabases(connectionId: string): Promise<string[]> {
  return directMongo<string[]>({ connectionId, action: "databases" });
}

export function loadDirectMongoCollections(
  connectionId: string,
  database: string,
): Promise<string[]> {
  return directMongo<string[]>({ connectionId, database, action: "collections" });
}

export function loadDirectMongoDocuments(
  connectionId: string,
  database: string,
  collection: string,
  filter: string,
  offset: number,
  limit = 25,
): Promise<MobileMongoDocumentPage> {
  return directMongo<MobileMongoDocumentPage>({
    connectionId,
    database,
    collection,
    filter,
    offset,
    limit,
    action: "documents",
  });
}

export function mutateDirectMongo(
  connectionId: string,
  database: string,
  collection: string,
  action: "insert" | "replace" | "delete",
  payload: Record<string, unknown>,
  productionConfirmation?: string,
): Promise<Record<string, unknown>> {
  // payload 仅包含当前操作需要的文档，连接凭据不会进入 JavaScript 运行时。
  return directMongo<Record<string, unknown>>({
    connectionId,
    database,
    collection,
    action,
    ...payload,
    confirmedWrite: true,
    productionConfirmation: productionConfirmation ?? "",
  });
}

async function directEtcd<T>(options: Record<string, unknown>): Promise<T> {
  requireNative();
  return (await DirectDatabase.etcd(options)).value as T;
}

export function loadDirectEtcdOverview(connectionId: string): Promise<MobileEtcdOverview> {
  return directEtcd<MobileEtcdOverview>({ connectionId, action: "overview" });
}

export function loadDirectEtcdEntries(
  connectionId: string,
  prefix: string,
  limit = 200,
): Promise<MobileEtcdPage> {
  return directEtcd<MobileEtcdPage>({ connectionId, action: "list", prefix, limit });
}

export function loadDirectEtcdEntry(connectionId: string, key: string): Promise<MobileEtcdEntry> {
  return directEtcd<MobileEtcdEntry>({ connectionId, action: "detail", key });
}

export function mutateDirectEtcd(
  connectionId: string,
  action: "put" | "delete",
  key: string,
  value: string,
  productionConfirmation?: string,
  lease = "0",
): Promise<Record<string, unknown>> {
  // lease 使用字符串传递，避免 JavaScript number 丢失 etcd 的 64 位租约精度。
  return directEtcd<Record<string, unknown>>({
    connectionId,
    action,
    key,
    value,
    lease,
    confirmedWrite: true,
    productionConfirmation: productionConfirmation ?? "",
  });
}

export async function explainDirectQuery(body: Record<string, unknown>): Promise<string> {
  const connection = (await listDirectConnections()).find((item) => item.id === body.connectionId);
  if (!connection) throw new DirectApiError("连接不存在");
  if (connection.dbType === "sqlserver") {
    throw new DirectApiError("SQL Server 执行计划需要专用 SHOWPLAN 会话，当前安卓版本暂未开放");
  }
  if (connection.dbType !== "postgres" && connection.dbType !== "mysql") {
    throw new DirectApiError("当前数据库类型不支持 SQL 执行计划");
  }
  const result = await executeDirectQuery(
    { ...body, sql: `EXPLAIN ${String(body.sql ?? "")}`, offset: 0, pageSize: 200 },
    true,
    false,
  );
  return result.rows.map((row) => row.join(" | ")).join("\n");
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

export function searchDirectHistory(payload: Record<string, unknown>): MobileHistoryPage {
  let entries = historyEntries();
  const query = String(payload.searchText ?? "").toLocaleLowerCase();
  const connectionId = String(payload.connectionId ?? "");
  entries = entries.filter(
    (item) =>
      (!query || `${item.sql} ${item.database} ${item.connectionName}`.toLocaleLowerCase().includes(query)) &&
      (!connectionId || item.connectionId === connectionId),
  );
  if (typeof payload.success === "boolean") entries = entries.filter((item) => item.success === payload.success);
  if (payload.startedAt) entries = entries.filter((item) => item.executedAt >= String(payload.startedAt));
  if (payload.endedAt) entries = entries.filter((item) => item.executedAt <= String(payload.endedAt));
  const total = entries.length;
  const cursor = payload.cursor as { executedAt?: string; id?: string } | undefined;
  if (cursor?.id) {
    const cursorIndex = entries.findIndex(
      (item) => item.id === cursor.id && (!cursor.executedAt || item.executedAt === cursor.executedAt),
    );
    if (cursorIndex >= 0) {
      entries = entries.slice(cursorIndex + 1);
    } else if (cursor.executedAt) {
      entries = entries.filter((item) => item.executedAt < cursor.executedAt!);
    }
  }
  const requestedLimit = Number(payload.limit ?? 20);
  const limit = Number.isFinite(requestedLimit)
    ? Math.min(100, Math.max(1, Math.trunc(requestedLimit)))
    : 20;
  const page = entries.slice(0, limit);
  const last = page.at(-1);
  return {
    entries: page,
    nextCursor: entries.length > page.length && last ? { executedAt: last.executedAt, id: last.id } : null,
    total,
  };
}

export function clearDirectHistory(): void {
  writeJson(DIRECT_HISTORY_KEY, []);
}

export function deleteDirectHistoryEntry(historyId: string): void {
  writeJson(
    DIRECT_HISTORY_KEY,
    historyEntries().filter((item) => item.id !== historyId),
  );
}

export function loadDirectSavedSql(savedSqlId: string): SavedSqlFile | null {
  return loadDirectSavedSqlLibrary().files.find((item) => item.id === savedSqlId) ?? null;
}

export function deleteDirectSavedSql(savedSqlId: string): void {
  const current = loadDirectSavedSqlLibrary();
  current.files = current.files.filter((item) => item.id !== savedSqlId);
  saveLibrary(current);
}

export function saveDirectSavedSqlFolder(payload: Record<string, unknown>): SavedSqlFolder {
  const current = loadDirectSavedSqlLibrary();
  const now = new Date().toISOString();
  const existing = current.folders.find((item) => item.id === payload.id);
  const folder: SavedSqlFolder = {
    id: existing?.id ?? id(),
    connectionId: String(payload.connectionId ?? existing?.connectionId ?? ""),
    parentFolderId:
      payload.parentFolderId === undefined
        ? (existing?.parentFolderId ?? null)
        : payload.parentFolderId
          ? String(payload.parentFolderId)
          : null,
    name: String(payload.name ?? existing?.name ?? "新建文件夹"),
    updatedAt: now,
  };
  current.folders = [folder, ...current.folders.filter((item) => item.id !== folder.id)];
  saveLibrary(current);
  return folder;
}

export function deleteDirectSavedSqlFolder(folderId: string): void {
  const current = loadDirectSavedSqlLibrary();
  const children = new Map<string, string[]>();
  for (const folder of current.folders) {
    if (!folder.parentFolderId) continue;
    const siblings = children.get(folder.parentFolderId) ?? [];
    siblings.push(folder.id);
    children.set(folder.parentFolderId, siblings);
  }
  const removed = new Set([folderId]);
  const pending = [folderId];
  while (pending.length) {
    for (const childId of children.get(pending.shift()!) ?? []) {
      if (!removed.has(childId)) {
        removed.add(childId);
        pending.push(childId);
      }
    }
  }
  current.folders = current.folders.filter((item) => !removed.has(item.id));
  current.files = current.files.filter((item) => !item.folderId || !removed.has(item.folderId));
  saveLibrary(current);
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

function directUpdateLiteral(value: unknown, dbType: string): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return value ? "TRUE" : "FALSE";
  const text = typeof value === "object" ? JSON.stringify(value) : String(value);
  const escaped = text.replaceAll("'", "''");
  return `'${dbType === "mysql" ? escaped.replaceAll("\\", "\\\\") : escaped}'`;
}

export async function updateDirectTableCell(payload: Record<string, unknown>) {
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
  const result = await executeDirectQuery(
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

export async function insertDirectTableRow(payload: Record<string, unknown>) {
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
  const result = await executeDirectQuery(
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

export async function deleteDirectTableRow(payload: Record<string, unknown>) {
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
  const result = await executeDirectQuery(
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

async function tableSelectSql(
  payload: Record<string, unknown>,
  includeControls: boolean,
  limit = 30,
  offset = 0,
  resolvedConnection?: MobileConnectionSummary,
): Promise<string> {
  requireNative();
  const connection =
    resolvedConnection ??
    (await DirectDatabase.listConnections()).value.find((item) => item.id === payload.connectionId);
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
