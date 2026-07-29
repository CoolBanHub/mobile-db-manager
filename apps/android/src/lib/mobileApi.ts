import { nativeAwareFetch } from "./nativeHttp";

export interface MobileLoginResponse {
  ok: boolean;
  token: string | null;
  expiresAt: number | null;
}

export interface MobileConnectionSummary {
  id: string;
  name: string;
  note: string;
  dbType: string;
  host: string;
  port: number;
  database: string | null;
  color: string | null;
  ssl: boolean;
  readOnly: boolean;
  isProduction: boolean;
  connectTimeoutSecs: number;
  queryTimeoutSecs: number;
  hasProxy: boolean;
  hasCaCertificate: boolean;
}

export type MobileDatabaseType = "mysql" | "postgres" | "sqlserver" | "mongodb" | "redis" | "clickhouse" | "sqlite" | "oracle";

export interface MobileConnectionDraft {
  id?: string;
  name: string;
  note: string;
  dbType: MobileDatabaseType;
  host: string;
  port: number;
  username: string;
  password: string;
  database: string | null;
  color: string | null;
  ssl: boolean;
  readOnly: boolean;
  isProduction: boolean;
  connectTimeoutSecs: number;
  queryTimeoutSecs: number;
  idleTimeoutSecs: number;
  keepaliveIntervalSecs: number;
  caCertPath: string;
  clientCertPath: string;
  clientKeyPath: string;
  proxyEnabled: boolean;
  proxyType: "socks5" | "http";
  proxyHost: string;
  proxyPort: number;
  proxyUsername: string;
  proxyPassword: string;
}

export interface MobileConnectionEditor extends MobileConnectionSummary {
  hasPassword: boolean;
  username: string;
  idleTimeoutSecs: number;
  keepaliveIntervalSecs: number;
  caCertPath: string;
  clientCertPath: string;
  clientKeyPath: string;
  proxyEnabled: boolean;
  proxyType: "socks5" | "http";
  proxyHost: string;
  proxyPort: number;
  proxyUsername: string;
  hasProxyPassword: boolean;
}

export interface DatabaseInfo {
  name: string;
}

export interface TableInfo {
  name: string;
  table_type: string;
  comment: string | null;
  parent_schema: string | null;
  parent_name: string | null;
}

export interface ColumnInfo {
  name: string;
  data_type: string;
  is_nullable: boolean;
  column_default: string | null;
  is_primary_key: boolean;
  extra: string | null;
  comment: string | null;
}

export interface IndexInfo {
  name: string;
  columns: string[];
  is_unique: boolean;
  is_primary: boolean;
  filter?: string | null;
  index_type?: string | null;
  included_columns?: string[] | null;
  comment?: string | null;
}

export interface ForeignKeyInfo {
  name: string;
  column: string;
  ref_schema?: string | null;
  ref_table: string;
  ref_column: string;
  on_update?: string | null;
  on_delete?: string | null;
}

export interface ConstraintInfo {
  name: string;
  constraint_type: string;
  definition: string;
  columns: string[];
  ref_schema?: string | null;
  ref_table?: string | null;
  ref_columns: string[];
  match_type?: string | null;
  on_update?: string | null;
  on_delete?: string | null;
  deferrable: boolean;
  initially_deferred: boolean;
  enabled: boolean;
  valid: boolean;
}

export interface TriggerInfo {
  name: string;
  event: string;
  timing: string;
  statement?: string | null;
}

export interface DatabaseObjectInfo {
  name: string;
  object_type: string;
  schema?: string | null;
  valid?: boolean | null;
  signature?: string | null;
  comment?: string | null;
}

export interface ObjectSource {
  name: string;
  object_type: string;
  schema?: string | null;
  source: string;
  editable?: boolean;
}

export interface QueryResult {
  columns: string[];
  rows: unknown[][];
  affected_rows: number;
  execution_time_ms: number;
  truncated: boolean;
  has_more?: boolean;
}

export interface MongoCollectionInfo {
  name: string;
  id: string;
  kind?: "collection" | "view" | "timeseries" | "bucket";
  bucketName?: string;
}

export interface MongoDocumentResult {
  documents: unknown[];
  raw_documents?: string[];
  extended_documents?: unknown[];
  total: number;
  total_is_exact?: boolean;
}

export interface RedisDatabaseInfo {
  db: number;
  keys: number;
}

export interface RedisKeyInfo {
  key_display: string;
  key_raw: string;
  key_type?: string;
  ttl?: number;
  size?: number;
  value_preview?: string;
}

export interface RedisScanResult {
  cursor: number;
  keys: RedisKeyInfo[];
  total_keys: number;
}

export interface RedisBlob {
  raw_base64: string;
  encoding: "utf8" | "binary";
}

export type RedisValueData =
  | { kind: "string"; content: RedisBlob }
  | { kind: "json"; value: string }
  | { kind: "list"; items: { index: number; value: RedisBlob }[]; total: number }
  | { kind: "set"; items: { member: RedisBlob }[]; total: number }
  | { kind: "hash"; items: { field: RedisBlob; value: RedisBlob }[]; total: number }
  | { kind: "zset"; items: { score: string; member: RedisBlob }[]; total: number }
  | { kind: "stream"; entries: { id: string; fields: { field: string; value: string }[] }[] }
  | { kind: "unknown" };

export interface RedisValue {
  key_display: string;
  key_raw: string;
  ttl: number;
  redis_type: string;
  data: RedisValueData;
}

export interface RedisCommandResult {
  command: string;
  safety: "allowed" | "write" | "confirm" | "blocked";
  value: unknown;
}

export interface MobileTableTarget {
  connectionId: string;
  database: string;
  schema: string | null;
  table: string;
}

export type MobileTableFilterOperator = "equals" | "notEquals" | "contains" | "startsWith" | "endsWith" | "greaterThan" | "greaterThanOrEqual" | "lessThan" | "lessThanOrEqual" | "isNull" | "isNotNull";

export interface MobileTableFilter {
  column: string;
  operator: MobileTableFilterOperator;
  value: string;
}

export interface MobileTableSort {
  column: string;
  direction: "asc" | "desc";
}

export interface MobileTableDataResponse {
  result: QueryResult;
  offset: number;
  limit: number;
  hasMore: boolean;
  selectTemplate: string;
}

export interface MobileTableTemplateResponse {
  sql: string;
}

export interface MobileQueryDraft {
  nonce: number;
  connectionId: string;
  database: string;
  schema: string | null;
  sql: string;
  savedSqlId?: string;
  savedSqlName?: string;
  savedSqlFolderId?: string | null;
}

export interface MobileHistoryEntry {
  id: string;
  connectionId: string;
  connectionName: string;
  database: string;
  schema: string | null;
  sql: string;
  executedAt: string;
  executionTimeMs: number;
  success: boolean;
  error: string | null;
}

export interface MobileHistoryCursor {
  executedAt: string;
  id: string;
}

export interface MobileHistoryPage {
  entries: MobileHistoryEntry[];
  nextCursor: MobileHistoryCursor | null;
  total: number;
}

export interface SavedSqlFolder {
  id: string;
  connectionId: string;
  parentFolderId: string | null;
  name: string;
  updatedAt: string;
}

export interface SavedSqlFile {
  id: string;
  connectionId: string;
  folderId: string | null;
  name: string;
  database: string;
  schema: string | null;
  sql: string;
  sqlLoaded: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SavedSqlLibrary {
  folders: SavedSqlFolder[];
  files: SavedSqlFile[];
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export const API_REQUEST_TIMEOUT_MS = 8_000;

export class ApiTimeoutError extends Error {
  constructor() {
    super("请求超时，请检查网络后重试");
    this.name = "ApiTimeoutError";
  }
}

export async function withApiTimeout<T>(operation: (signal: AbortSignal) => Promise<T>, timeoutMs = API_REQUEST_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController();
  const timeout = globalThis.setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await operation(controller.signal);
  } catch (error) {
    if (controller.signal.aborted) throw new ApiTimeoutError();
    throw error;
  } finally {
    globalThis.clearTimeout(timeout);
  }
}

export function buildApiHeaders(token: string | null, initial?: HeadersInit): Headers {
  const headers = new Headers(initial);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return headers;
}

export function apiFetch(baseUrl: string, path: string, token: string | null, init: RequestInit = {}): Promise<Response> {
  return nativeAwareFetch(baseUrl, `${baseUrl}${path}`, {
    ...init,
    headers: buildApiHeaders(token, init.headers),
  });
}

export function buildApiPath(path: string, params: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) query.set(key, String(value));
  }
  const encoded = query.toString();
  return encoded ? `${path}?${encoded}` : path;
}

export async function apiGetJson<T>(baseUrl: string, path: string, token: string | null, params: Record<string, string | number | undefined>): Promise<T> {
  return withApiTimeout(async (signal) => {
    const response = await apiFetch(baseUrl, buildApiPath(path, params), token, {
      headers: { Accept: "application/json" },
      signal,
    });
    if (!response.ok) throw new ApiError(await apiErrorMessage(response), response.status);
    return response.json() as Promise<T>;
  });
}

async function apiErrorMessage(response: Response): Promise<string> {
  const fallback = `服务器返回 ${response.status}`;
  const text = await response.text().catch(() => "");
  if (!text) return fallback;
  try {
    const payload = JSON.parse(text) as { message?: string; error?: string };
    return payload.message ?? payload.error ?? fallback;
  } catch {
    return text;
  }
}

export async function apiPostJson<T>(baseUrl: string, path: string, token: string | null, body: unknown, options: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal?.addEventListener("abort", abort, { once: true });
  const timeout = window.setTimeout(abort, options.timeoutMs ?? 35_000);
  try {
    const response = await apiFetch(baseUrl, path, token, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new ApiError(await apiErrorMessage(response), response.status);
    }
    return response.json() as Promise<T>;
  } finally {
    window.clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abort);
  }
}

export async function apiDeleteJson<T>(baseUrl: string, path: string, token: string | null): Promise<T> {
  return withApiTimeout(async (signal) => {
    const response = await apiFetch(baseUrl, path, token, {
      method: "DELETE",
      headers: { Accept: "application/json" },
      signal,
    });
    if (!response.ok) throw new ApiError(await apiErrorMessage(response), response.status);
    return response.json() as Promise<T>;
  });
}
