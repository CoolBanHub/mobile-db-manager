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
}

export type MobileDatabaseType =
  | "mysql"
  | "postgres"
  | "redis"
  | "sqlserver"
  | "mongodb";

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
  hasPassword: boolean;
  username: string;
  keepaliveIntervalSecs: number;
  proxyEnabled: boolean;
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
  tunnelLayerCount: number;
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
