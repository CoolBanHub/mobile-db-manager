export interface MobileConnectionSummary {
  // 列表与工作台只接收非敏感摘要；用户名和凭据不会出现在该类型中。
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
}

export type MobileDatabaseType =
  | "mysql"
  | "postgres"
  | "redis"
  | "sqlserver"
  | "mongodb"
  | "etcd";

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
  keepaliveIntervalSecs: number;
  proxyEnabled: boolean;
  proxyHost: string;
  proxyPort: number;
  proxyUsername: string;
  proxyPassword: string;
  sshEnabled: boolean;
  sshProfileId: string;
  sshHost: string;
  sshPort: number;
  sshUsername: string;
  sshHostKeyFingerprint: string;
  sshPassword: string;
  sshAuthMethod: "password" | "private-key";
  sshPrivateKey: string;
  sshPrivateKeyPassphrase: string;
  connectionString: string;
}

export interface MobileConnectionEditor extends MobileConnectionSummary {
  // has* 仅表示原生保险箱中已有密文，让编辑页支持“留空即保留”，不会回传秘密本身。
  hasPassword: boolean;
  username: string;
  keepaliveIntervalSecs: number;
  proxyEnabled: boolean;
  proxyHost: string;
  proxyPort: number;
  proxyUsername: string;
  hasProxyPassword: boolean;
  sshEnabled: boolean;
  sshProfileId: string;
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
  tunnelLayerCount: number;
}

export interface MobileSshProfileSummary {
  // 跳板机只引用独立密钥 ID；已保存的密码和私钥本身不得进入 WebView。
  id: string;
  name: string;
  host: string;
  port: number;
  username: string;
  hostKeyFingerprint: string;
  authMethod: "password" | "private-key";
  hasPassword: boolean;
  keyId: string;
  keyName: string;
}

export interface MobileSshProfileDraft {
  // password 仅承载用户本次输入；编辑已有配置时传空字符串表示保留原密文。
  id?: string;
  name: string;
  host: string;
  port: number;
  username: string;
  hostKeyFingerprint: string;
  authMethod: "password" | "private-key";
  password: string;
  keyId: string;
}

export interface MobileSshKeySummary {
  // 摘要只包含可公开展示的公钥元数据和秘密存在状态。
  id: string;
  name: string;
  keyType: string;
  fingerprint: string;
  hasPrivateKey: boolean;
  hasPassphrase: boolean;
  usageCount: number;
}

export interface MobileSshKeyDraft {
  // 文件导入在原生层完成；该 privateKey 字段仅供用户主动粘贴时使用。
  id?: string;
  name: string;
  privateKey: string;
  privateKeyPassphrase: string;
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

export interface DatabaseObjectInfo {
  name: string;
  object_type: string;
  schema?: string | null;
  valid?: boolean | null;
  signature?: string | null;
  comment?: string | null;
}

export interface QueryResult {
  columns: string[];
  rows: unknown[][];
  affected_rows: number;
  execution_time_ms: number;
  truncated: boolean;
  has_more?: boolean;
}

export interface MobileDatabaseSession {
  sessionId: string;
  user: string;
  database: string;
  client: string;
  state: string;
  command: string;
  query: string;
  queryStartedAt: string;
  transactionStartedAt: string;
  durationMs: number;
  transactionDurationMs: number;
  waitType: string;
  waitEvent: string;
}

export interface MobileDatabaseLock {
  waitingSessionId: string;
  blockingSessionId: string;
  database: string;
  objectName: string;
  waitType: string;
  durationMs: number;
  waitingQuery: string;
  blockingQuery: string;
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

export type MobileTableTransactionChange =
  | { kind: "insert"; values: Record<string, unknown> }
  | { kind: "update"; values: Record<string, unknown>; primaryKey: Record<string, unknown> }
  | { kind: "delete"; primaryKey: Record<string, unknown> };

export interface MobileTableTransactionResult {
  committed: boolean;
  operationCount: number;
  affectedRows: number;
}

export type RedisKeyType = "string" | "hash" | "list" | "set" | "zset" | "stream" | "none" | string;

export interface MobileRedisOverview {
  keyCount: number;
  keyspace: string;
}

export interface MobileRedisScanPage {
  cursor: string;
  keys: string[];
}

export interface MobileRedisKeyDetail {
  key: string;
  type: RedisKeyType;
  ttlMs: number;
  memoryBytes: number | null;
  length?: number;
  value: unknown;
}

export interface MobileMongoDocumentPage {
  // 使用 Extended JSON 字符串保留 ObjectId、日期和高精度数值等 BSON 类型。
  documents: string[];
  offset: number;
  limit: number;
  hasMore: boolean;
}

export interface MobileEtcdOverview {
  version: string;
  dbSize: string;
  keyCount: string;
}

export interface MobileEtcdEntry {
  // etcd revision/lease 均可能超过 JS 安全整数范围，因此统一以字符串跨桥接传输。
  key: string;
  value: string;
  createRevision: string;
  modRevision: string;
  version: string;
  lease: string;
}

export interface MobileEtcdPage {
  entries: MobileEtcdEntry[];
  count: string;
  more: boolean;
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
  executionMode?: "safe" | "advanced";
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
