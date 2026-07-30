import { DIRECT_DATABASE_URL, directFetch, directJsonRequest } from "./directDatabase";

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
  sslMode: "required" | "verify-ca" | "verify-full";
  readOnly: boolean;
  isProduction: boolean;
  connectTimeoutSecs: number;
  queryTimeoutSecs: number;
  hasProxy: boolean;
  hasCaCertificate: boolean;
}

export type MobileDatabaseType =
  | "mysql"
  | "postgres"
  | "sqlite"
  | "rqlite"
  | "turso"
  | "cloudflare-d1"
  | "redis"
  | "duckdb"
  | "clickhouse"
  | "sqlserver"
  | "mongodb"
  | "oracle"
  | "elasticsearch"
  | "hbase"
  | "qdrant"
  | "milvus"
  | "weaviate"
  | "chromadb"
  | "doris"
  | "starrocks"
  | "manticoresearch"
  | "databend"
  | "redshift"
  | "dameng"
  | "gaussdb"
  | "kingbase"
  | "highgo"
  | "uxdb"
  | "vastbase"
  | "goldendb"
  | "kwdb"
  | "yashandb"
  | "databricks"
  | "saphana"
  | "teradata"
  | "vertica"
  | "firebird"
  | "exasol"
  | "opengauss"
  | "oceanbase-oracle"
  | "questdb"
  | "gbase"
  | "access"
  | "h2"
  | "snowflake"
  | "trino"
  | "prestosql"
  | "hive"
  | "spark"
  | "db2"
  | "informix"
  | "neo4j"
  | "cassandra"
  | "bigquery"
  | "kylin"
  | "sundb"
  | "oscar"
  | "tdengine"
  | "xugu"
  | "iotdb"
  | "etcd"
  | "zookeeper"
  | "iris"
  | "influxdb"
  | "jdbc"
  | "mq"
  | "nacos";

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
  sslMode: "required" | "verify-ca" | "verify-full";
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
  sshEnabled: boolean;
  sshHost: string;
  sshPort: number;
  sshUsername: string;
  sshHostKeyFingerprint: string;
  sshPassword: string;
  sshAuthMethod: "password" | "private-key";
  sshPrivateKey: string;
  sshPrivateKeyPassphrase: string;
  connectionString: string;
  oracleConnectionType: "service_name" | "sid" | "tns";
  sysdba: boolean;
  urlParams: string;
  initScript: string;
  visibleDatabases: string[];
  visibleSchemas: Record<string, string[]>;
  productionDatabases: string[];
  redisConnectionMode: "standalone" | "sentinel" | "cluster";
  redisSentinelMaster: string;
  redisSentinelNodes: string;
  redisSentinelUsername: string;
  redisSentinelPassword: string;
  redisSentinelTls: boolean;
  redisClusterNodes: string;
  jdbcDriverClass: string;
  jdbcDriverPaths: string[];
  driverProfile: string;
  driverLabel: string;
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
  sshEnabled: boolean;
  sshHost: string;
  sshPort: number;
  sshUsername: string;
  sshHostKeyFingerprint: string;
  hasSshPassword: boolean;
  sshAuthMethod: "password" | "private-key";
  hasSshPrivateKey: boolean;
  hasSshPrivateKeyPassphrase: boolean;
  connectionString: string;
  hasConnectionString: boolean;
  oracleConnectionType: "service_name" | "sid" | "tns";
  sysdba: boolean;
  urlParams: string;
  initScript: string;
  visibleDatabases: string[];
  visibleSchemas: Record<string, string[]>;
  productionDatabases: string[];
  redisConnectionMode: "standalone" | "sentinel" | "cluster";
  redisSentinelMaster: string;
  redisSentinelNodes: string;
  redisSentinelUsername: string;
  hasRedisSentinelPassword: boolean;
  redisSentinelTls: boolean;
  redisClusterNodes: string;
  jdbcDriverClass: string;
  jdbcDriverPaths: string[];
  driverProfile: string;
  driverLabel: string;
  tunnelLayerCount: number;
  tunnelProfileCount: number;
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

export interface PartitionInfo {
  name: string;
  position: number;
  value: string;
  partition_type: string;
  partition_key: string;
  online?: boolean | null;
  auto_partition_type?: string | null;
  auto_partition_span?: number | null;
}

export interface SubpartitionInfo {
  name: string;
  position: number;
  value: string;
  partition_type: string;
  partition_key: string;
}

export interface SequenceInfo {
  name: string;
  data_type: string;
  start_value: string;
  min_value: string;
  max_value: string;
  increment: string;
  cycle: boolean;
  last_value?: string | null;
}

export interface RuleInfo {
  name: string;
  table_name: string;
  definition: string;
}

export interface ExtensionInfo {
  name: string;
  version: string;
  comment?: string | null;
  schema?: string | null;
}

export interface OwnerInfo {
  object_name: string;
  object_type: string;
  owner: string;
}

export interface ObjectStatistics {
  name: string;
  schema?: string | null;
  estimated_rows?: number | null;
  total_bytes?: number | null;
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

export interface MobileMultiQueryResult extends QueryResult {
  execution_error?: boolean;
  statement_index?: number;
}

export interface MobileTransactionResponse {
  transactionId: string;
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
  columnMeta: ColumnInfo[];
  editable: boolean;
  editBlockReason: string | null;
  connectionName: string;
  isProduction: boolean;
}

export interface MobileTableTemplateResponse {
  sql: string;
}

export interface MobileTableCellUpdateResponse {
  affectedRows: number;
}

export interface MobileTableRowMutationResponse {
  affectedRows: number;
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
  executionMode?: "safe" | "advanced" | "script" | "transaction";
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

function requireDirectMode(baseUrl: string) {
  if (baseUrl !== DIRECT_DATABASE_URL) {
    throw new ApiError("Android 客户端仅支持本机数据库直连", 501);
  }
}

export function apiFetch(baseUrl: string, path: string, _token: string | null, init: RequestInit = {}): Promise<Response> {
  requireDirectMode(baseUrl);
  return directFetch(path, init);
}

export async function apiGetJson<T>(baseUrl: string, path: string, _token: string | null, params: Record<string, string | number | undefined>): Promise<T> {
  requireDirectMode(baseUrl);
  return directJsonRequest<T>({ method: "GET", path, params });
}

export async function apiPostJson<T>(baseUrl: string, path: string, _token: string | null, body: unknown, _options: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<T> {
  requireDirectMode(baseUrl);
  return directJsonRequest<T>({ method: "POST", path, body });
}

export async function apiDeleteJson<T>(baseUrl: string, path: string, _token: string | null): Promise<T> {
  requireDirectMode(baseUrl);
  return directJsonRequest<T>({ method: "DELETE", path });
}
