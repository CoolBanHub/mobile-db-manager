<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import CreateTableEditor from "./CreateTableEditor.vue";
import TableDataBrowser from "./TableDataBrowser.vue";
import { loadDirectMetadata } from "@/lib/direct/metadata";
import { buildDirectTableTemplate } from "@/lib/direct/tableData";
import { type ColumnInfo, type DatabaseInfo, type DatabaseObjectInfo, type ForeignKeyInfo, type IndexInfo, type MobileConnectionSummary, type MobileQueryDraft, type MobileTableTarget, type TableInfo } from "@/lib/mobileTypes";

type BrowseLevel = "connections" | "databases" | "schemas" | "tables" | "details" | "data";
type SchemaSection = "relations" | "routines";
type DetailTab = "columns" | "indexes" | "foreignKeys";
type InspectorTab = "structure" | "data" | "query";

const props = defineProps<{
  connections: MobileConnectionSummary[];
}>();

const emit = defineEmits<{
  openQuery: [draft: Omit<MobileQueryDraft, "nonce">];
}>();

const TABLE_PAGE_SIZE = 100;
const level = ref<BrowseLevel>("connections");
const selectedConnection = ref<MobileConnectionSummary | null>(null);
const selectedDatabase = ref("");
const selectedSchema = ref("");
const selectedTable = ref<TableInfo | null>(null);
const databases = ref<DatabaseInfo[]>([]);
const schemas = ref<string[]>([]);
const tables = ref<TableInfo[]>([]);
const routines = ref<DatabaseObjectInfo[]>([]);
const routinesLoaded = ref(false);
const columns = ref<ColumnInfo[]>([]);
const indexes = ref<IndexInfo[]>([]);
const foreignKeys = ref<ForeignKeyInfo[]>([]);
const schemaSection = ref<SchemaSection>("relations");
const inspectorTab = ref<InspectorTab>("structure");
const expandedObjectGroups = ref(new Set(["tables"]));
const detailTab = ref<DetailTab>("columns");
const detailLoaded = ref<Partial<Record<DetailTab, boolean>>>({});
const detailLoading = ref(false);
const detailError = ref("");
const loading = ref(false);
const loadingMore = ref(false);
const errorMessage = ref("");
const hasMoreTables = ref(false);
const tableTarget = ref<MobileTableTarget | null>(null);
const actionTable = ref("");
const objectSearch = ref("");
const tableDataBrowser = ref<{ handleBack: () => boolean } | null>(null);
const createTableOpen = ref(false);
let retryAction: (() => Promise<void>) | null = null;
// 详情和“打开查询”各自维护请求版本，返回上级后迟到的响应不会重新打开旧对象。
let tableActionRequestId = 0;
let detailRequestId = 0;
const TABLE_BROWSING_DATABASE_TYPES = new Set(["postgres", "mysql", "sqlserver"]);

const supportsTableBrowsing = computed(() => TABLE_BROWSING_DATABASE_TYPES.has(selectedConnection.value?.dbType ?? ""));
const searchNeedle = computed(() => objectSearch.value.trim().toLocaleLowerCase());
const visibleDatabases = computed(() => databases.value.filter((item) => !searchNeedle.value || item.name === selectedDatabase.value || item.name.toLocaleLowerCase().includes(searchNeedle.value)));
const visibleSchemas = computed(() => schemas.value.filter((item) => !searchNeedle.value || item.toLocaleLowerCase().includes(searchNeedle.value)));
const visibleTables = computed(() => tables.value.filter((item) => !searchNeedle.value || `${item.name} ${item.comment ?? ""}`.toLocaleLowerCase().includes(searchNeedle.value)));
const visibleRoutines = computed(() => routines.value.filter((item) => !searchNeedle.value || `${item.name} ${item.signature ?? ""}`.toLocaleLowerCase().includes(searchNeedle.value)));
const visibleRelationTables = computed(() => visibleTables.value.filter((item) => !item.table_type.toUpperCase().includes("VIEW")));
const visibleViews = computed(() => visibleTables.value.filter((item) => item.table_type.toUpperCase().includes("VIEW")));
const businessSchemas = computed(() =>
  schemas.value.filter((schema) => {
    const normalized = schema.toLocaleLowerCase();
    return normalized !== "information_schema" && normalized !== "sys" && !normalized.startsWith("pg_");
  }),
);
const showSchemaSwitcher = computed(() => businessSchemas.value.length > 1);
const routineGroups = computed(() => [
  { key: "functions", label: "函数", icon: "function", items: visibleRoutines.value.filter((item) => item.object_type.toUpperCase().includes("FUNCTION")) },
  { key: "procedures", label: "存储过程", icon: "procedure", items: visibleRoutines.value.filter((item) => item.object_type.toUpperCase().includes("PROCEDURE")) },
  { key: "sequences", label: "序列", icon: "sequence", items: visibleRoutines.value.filter((item) => item.object_type.toUpperCase().includes("SEQUENCE")) },
]);

const title = computed(() => {
  if (level.value === "connections") return "连接";
  if (level.value === "databases") return "数据库";
  if (level.value === "schemas") return "Schema";
  if (level.value === "tables") return "表与视图";
  if (level.value === "details") return "对象详情";
  if (level.value === "data") return "表数据";
  return "元数据";
});

const contextLabel = computed(() => {
  const parts = [selectedConnection.value?.name, selectedDatabase.value, selectedSchema.value, selectedTable.value?.name].filter(Boolean);
  return parts.join(" / ");
});

function handleError(error: unknown, retry: () => Promise<void>) {
  errorMessage.value = error instanceof Error ? error.message : "元数据加载失败";
  retryAction = retry;
}

function invalidateTableAction() {
  tableActionRequestId++;
  actionTable.value = "";
}

async function runLoad(action: () => Promise<void>) {
  loading.value = true;
  errorMessage.value = "";
  retryAction = action;
  try {
    await action();
  } catch (error) {
    handleError(error, action);
  } finally {
    loading.value = false;
  }
}

async function openConnection(connection: MobileConnectionSummary) {
  invalidateTableAction();
  selectedConnection.value = connection;
  selectedDatabase.value = "";
  selectedSchema.value = "";
  selectedTable.value = null;
  databases.value = [];
  schemas.value = [];
  tables.value = [];
  routines.value = [];
  routinesLoaded.value = false;
  columns.value = [];
  level.value = "databases";
  await runLoad(async () => {
    databases.value = await loadDirectMetadata<DatabaseInfo[]>("databases", { connectionId: connection.id });
  });
}

async function openDatabase(database: DatabaseInfo) {
  if (!selectedConnection.value) return;
  if (selectedDatabase.value === database.name) {
    selectedDatabase.value = "";
    selectedSchema.value = "";
    schemas.value = [];
    tables.value = [];
    routines.value = [];
    return;
  }
  invalidateTableAction();
  selectedDatabase.value = database.name;
  selectedSchema.value = "";
  selectedTable.value = null;
  schemas.value = [];
  tables.value = [];
  routines.value = [];
  routinesLoaded.value = false;
  columns.value = [];
  schemaSection.value = "relations";
  level.value = "databases";

  const connectionId = selectedConnection.value.id;
  await runLoad(async () => {
    schemas.value = await loadDirectMetadata<string[]>("schemas", {
      connectionId,
      database: database.name,
    });
    // 表与例程互不依赖，并行读取能缩短进入数据库后的首屏等待时间。
    const preferredSchema = businessSchemas.value.find((schema) => ["public", "dbo"].includes(schema.toLocaleLowerCase())) ?? businessSchemas.value[0] ?? schemas.value[0] ?? "";
    selectedSchema.value = preferredSchema;
    await Promise.all([fetchTables(connectionId, database.name, preferredSchema, 0), fetchRoutines()]);
  });
}

async function openSchema(schema: string) {
  if (!selectedConnection.value) return;
  if (selectedSchema.value === schema) return;
  invalidateTableAction();
  selectedSchema.value = schema;
  selectedTable.value = null;
  tables.value = [];
  routines.value = [];
  routinesLoaded.value = false;
  columns.value = [];
  schemaSection.value = "relations";
  level.value = "databases";
  const connectionId = selectedConnection.value.id;
  await runLoad(async () => {
    await Promise.all([fetchTables(connectionId, selectedDatabase.value, schema, 0), fetchRoutines()]);
  });
}

function toggleObjectGroup(key: string) {
  const next = new Set(expandedObjectGroups.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  expandedObjectGroups.value = next;
}

function openTreeTable(table: TableInfo) {
  if (supportsTableBrowsing.value) openTableData(table);
  else void openTable(table);
}

function selectInspectorTab(tab: InspectorTab) {
  inspectorTab.value = tab;
  if (tab === "query") {
    openManagementSql(`-- ${selectedDatabase.value}${selectedSchema.value ? ` / ${selectedSchema.value}` : ""}\nSELECT 1;`);
  }
}

async function fetchTables(connectionId: string, database: string, schema: string, offset: number) {
  const page = await loadDirectMetadata<TableInfo[]>("tables", {
    connectionId,
    database,
    schema,
    limit: TABLE_PAGE_SIZE,
    offset,
  });
  tables.value = offset === 0 ? page : [...tables.value, ...page];
  hasMoreTables.value = page.length === TABLE_PAGE_SIZE;
}

async function fetchRoutines() {
  if (!selectedConnection.value || routinesLoaded.value) return;
  routines.value = await loadDirectMetadata<DatabaseObjectInfo[]>("objects", {
    connectionId: selectedConnection.value.id,
    database: selectedDatabase.value,
    schema: selectedSchema.value,
    limit: 200,
    offset: 0,
  });
  routinesLoaded.value = true;
}

async function selectSchemaSection(section: SchemaSection) {
  schemaSection.value = section;
  errorMessage.value = "";
  if (section === "routines" && !routinesLoaded.value) {
    await runLoad(fetchRoutines);
  }
}

async function loadMoreTables() {
  if (!selectedConnection.value || loadingMore.value) return;
  loadingMore.value = true;
  errorMessage.value = "";
  try {
    await fetchTables(selectedConnection.value.id, selectedDatabase.value, selectedSchema.value, tables.value.length);
  } catch (error) {
    handleError(error, loadMoreTables);
  } finally {
    loadingMore.value = false;
  }
}

async function openTable(table: TableInfo) {
  if (!selectedConnection.value) return;
  invalidateTableAction();
  detailRequestId++;
  selectedTable.value = table;
  columns.value = [];
  indexes.value = [];
  foreignKeys.value = [];
  detailLoaded.value = {};
  detailTab.value = "columns";
  level.value = "details";
  await loadDetail("columns");
}

function detailParams() {
  return {
    connectionId: selectedConnection.value?.id ?? "",
    database: selectedDatabase.value,
    schema: selectedSchema.value,
    table: selectedTable.value?.name ?? "",
  };
}

async function loadDetail(tab: DetailTab) {
  if (!selectedConnection.value || !selectedTable.value) return;
  if (detailLoaded.value[tab]) {
    detailRequestId++;
    detailTab.value = tab;
    detailLoading.value = false;
    detailError.value = "";
    return;
  }
  detailTab.value = tab;
  const requestId = ++detailRequestId;
  detailLoading.value = true;
  detailError.value = "";
  try {
    const params = detailParams();
    let payload: ColumnInfo[] | IndexInfo[] | ForeignKeyInfo[];
    if (tab === "columns") {
      payload = await loadDirectMetadata<ColumnInfo[]>("columns", params);
    } else if (tab === "indexes") {
      payload = await loadDirectMetadata<IndexInfo[]>("indexes", params);
    } else {
      payload = await loadDirectMetadata<ForeignKeyInfo[]>("foreign-keys", params);
    }
    if (requestId !== detailRequestId || detailTab.value !== tab) return;
    if (tab === "columns") columns.value = payload as ColumnInfo[];
    else if (tab === "indexes") indexes.value = payload as IndexInfo[];
    else foreignKeys.value = payload as ForeignKeyInfo[];
    detailLoaded.value = { ...detailLoaded.value, [tab]: true };
  } catch (error) {
    if (requestId === detailRequestId) {
      detailError.value = error instanceof Error ? error.message : "对象元数据加载失败";
    }
  } finally {
    if (requestId === detailRequestId) detailLoading.value = false;
  }
}

function mobileTarget(table: TableInfo): MobileTableTarget | null {
  if (!selectedConnection.value) return null;
  return {
    connectionId: selectedConnection.value.id,
    database: selectedDatabase.value,
    schema: selectedSchema.value || null,
    table: table.name,
  };
}

function quoteIdentifier(value: string) {
  const dbType = selectedConnection.value?.dbType;
  if (["mysql", "clickhouse", "doris", "starrocks"].includes(dbType ?? "")) {
    return `\`${value.replaceAll("`", "``")}\``;
  }
  if (dbType === "sqlserver") return `[${value.replaceAll("]", "]]")}]`;
  return `"${value.replaceAll('"', '""')}"`;
}

function qualifiedObject(name: string) {
  return selectedSchema.value ? `${quoteIdentifier(selectedSchema.value)}.${quoteIdentifier(name)}` : quoteIdentifier(name);
}

function openManagementSql(sql: string) {
  if (!selectedConnection.value) return;
  emit("openQuery", {
    connectionId: selectedConnection.value.id,
    database: selectedDatabase.value || selectedConnection.value.database || databases.value[0]?.name || "",
    schema: selectedSchema.value || null,
    sql,
    executionMode: "advanced",
  });
}

function createDatabaseSql() {
  const name = window.prompt("新数据库名称");
  if (name?.trim()) openManagementSql(`CREATE DATABASE ${quoteIdentifier(name.trim())};`);
}

function dropDatabaseSql(database: DatabaseInfo) {
  openManagementSql(`DROP DATABASE ${quoteIdentifier(database.name)};`);
}

function createSchemaSql() {
  const name = window.prompt("新 Schema 名称");
  if (name?.trim()) openManagementSql(`CREATE SCHEMA ${quoteIdentifier(name.trim())};`);
}

function renameSchemaSql() {
  const nextName = window.prompt("Schema 重命名为", selectedSchema.value);
  if (nextName?.trim() && nextName.trim() !== selectedSchema.value) {
    openManagementSql(`ALTER SCHEMA ${quoteIdentifier(selectedSchema.value)} RENAME TO ${quoteIdentifier(nextName.trim())};`);
  }
}

function dropSchemaSql() {
  if (selectedSchema.value) openManagementSql(`DROP SCHEMA ${quoteIdentifier(selectedSchema.value)};`);
}

function openCreateTable() {
  if (!selectedConnection.value || !selectedDatabase.value) return;
  createTableOpen.value = true;
}

function openCreatedTableSql(sql: string) {
  // 设计器不绕过查询工作台直接执行 DDL，让现有写入确认和生产保护继续作为唯一安全入口。
  createTableOpen.value = false;
  openManagementSql(sql);
}

function createViewSql() {
  const name = window.prompt("新视图名称");
  if (!name?.trim()) return;
  const target = qualifiedObject(name.trim());
  openManagementSql(`CREATE VIEW ${target} AS\nSELECT 1 AS value;`);
}

function renameRelationSql(table: TableInfo) {
  const nextName = window.prompt("重命名为", table.name);
  if (!nextName?.trim() || nextName.trim() === table.name) return;
  openManagementSql(`ALTER ${table.table_type.toUpperCase().includes("VIEW") ? "VIEW" : "TABLE"} ${qualifiedObject(table.name)} RENAME TO ${quoteIdentifier(nextName.trim())};`);
}

function dropRelationSql(table: TableInfo) {
  const objectType = table.table_type.toUpperCase().includes("VIEW") ? "VIEW" : "TABLE";
  openManagementSql(`DROP ${objectType} ${qualifiedObject(table.name)};`);
}

function alterTableSql(table: TableInfo) {
  openManagementSql(`-- 在执行前补全表结构变更\nALTER TABLE ${qualifiedObject(table.name)}\n  ADD COLUMN new_column VARCHAR(255);`);
}

function addIndexSql(table: TableInfo) {
  const column = window.prompt("索引字段（多个字段用逗号分隔）");
  if (!column?.trim()) return;
  const indexName = window.prompt("索引名称", `idx_${table.name}_${column.split(",")[0].trim()}`);
  if (!indexName?.trim()) return;
  const columnsSql = column
    .split(",")
    .map((item) => quoteIdentifier(item.trim()))
    .filter(Boolean)
    .join(", ");
  openManagementSql(`CREATE INDEX ${quoteIdentifier(indexName.trim())} ON ${qualifiedObject(table.name)} (${columnsSql});`);
}

function addForeignKeySql(table: TableInfo) {
  const column = window.prompt("本表外键字段");
  const reference = window.prompt("引用对象，格式 schema.table(column)");
  if (!column?.trim() || !reference?.trim()) return;
  const constraintName = window.prompt("外键约束名称", `fk_${table.name}_${column.trim()}`);
  if (!constraintName?.trim()) return;
  openManagementSql(`ALTER TABLE ${qualifiedObject(table.name)}\n  ADD CONSTRAINT ${quoteIdentifier(constraintName.trim())} FOREIGN KEY (${quoteIdentifier(column.trim())})\n  REFERENCES ${reference.trim()};`);
}

function dropColumnSql(column: ColumnInfo) {
  if (!selectedTable.value) return;
  openManagementSql(`ALTER TABLE ${qualifiedObject(selectedTable.value.name)} DROP COLUMN ${quoteIdentifier(column.name)};`);
}

function dropIndexSql(index: IndexInfo) {
  if (!selectedTable.value) return;
  const dbType = selectedConnection.value?.dbType;
  openManagementSql(dbType === "mysql" ? `DROP INDEX ${quoteIdentifier(index.name)} ON ${qualifiedObject(selectedTable.value.name)};` : `DROP INDEX ${selectedSchema.value ? `${quoteIdentifier(selectedSchema.value)}.` : ""}${quoteIdentifier(index.name)};`);
}

function dropConstraintSql(name: string) {
  if (!selectedTable.value) return;
  openManagementSql(`ALTER TABLE ${qualifiedObject(selectedTable.value.name)} DROP CONSTRAINT ${quoteIdentifier(name)};`);
}

function openAdministrationSql(kind: "users" | "sessions" | "monitor") {
  const dbType = selectedConnection.value?.dbType;
  const sqlByDatabase: Record<string, Record<typeof kind, string>> = {
    postgres: {
      users: "SELECT rolname, rolsuper, rolcanlogin, rolconnlimit FROM pg_roles ORDER BY rolname;",
      sessions: "SELECT pid, usename, datname, client_addr, state, query_start, query FROM pg_stat_activity ORDER BY query_start DESC;",
      monitor: "SELECT datname, numbackends, xact_commit, xact_rollback, blks_read, blks_hit, deadlocks FROM pg_stat_database ORDER BY datname;",
    },
    mysql: {
      users: "SELECT User, Host, account_locked, password_expired FROM mysql.user ORDER BY User, Host;",
      sessions: "SHOW FULL PROCESSLIST;",
      monitor: "SHOW GLOBAL STATUS;",
    },
    sqlserver: {
      users: "SELECT name, type_desc, is_disabled, create_date FROM sys.server_principals ORDER BY name;",
      sessions: "SELECT session_id, login_name, host_name, status, cpu_time, memory_usage, reads, writes FROM sys.dm_exec_sessions WHERE is_user_process = 1;",
      monitor: "SELECT * FROM sys.dm_os_performance_counters;",
    },
    oracle: {
      users: "SELECT username, account_status, created, profile FROM all_users ORDER BY username;",
      sessions: "SELECT sid, serial#, username, status, machine, program, sql_id FROM v$session WHERE type = 'USER';",
      monitor: "SELECT name, value FROM v$sysstat ORDER BY name;",
    },
  };
  const sql = sqlByDatabase[dbType ?? ""]?.[kind];
  if (sql) openManagementSql(sql);
  else errorMessage.value = "当前数据库类型暂无内置运维查询模板";
}

function executeRoutineSql(routine: DatabaseObjectInfo) {
  const target = qualifiedObject(routine.name);
  openManagementSql(routine.object_type === "PROCEDURE" ? `CALL ${target}();` : `SELECT ${target}();`);
}

function openTableData(table: TableInfo) {
  const target = mobileTarget(table);
  if (!target || !supportsTableBrowsing.value) return;
  invalidateTableAction();
  selectedTable.value = table;
  tableTarget.value = target;
  errorMessage.value = "";
  level.value = "data";
}

async function openTableQuery(table: TableInfo) {
  const target = mobileTarget(table);
  if (!target || !supportsTableBrowsing.value || actionTable.value) return;
  const requestId = ++tableActionRequestId;
  actionTable.value = table.name;
  errorMessage.value = "";
  try {
    const sql = await buildDirectTableTemplate({ ...target, offset: 0, limit: 30 });
    if (requestId === tableActionRequestId) emit("openQuery", { ...target, sql });
  } catch (error) {
    if (requestId === tableActionRequestId) handleError(error, () => openTableQuery(table));
  } finally {
    if (requestId === tableActionRequestId) actionTable.value = "";
  }
}

function openGeneratedQuery(draft: Omit<MobileQueryDraft, "nonce">) {
  emit("openQuery", draft);
}

function goBack() {
  invalidateTableAction();
  detailRequestId++;
  errorMessage.value = "";
  if (level.value === "data") {
    level.value = "databases";
    tableTarget.value = null;
    selectedTable.value = null;
  } else if (level.value === "details") {
    level.value = "databases";
    selectedTable.value = null;
  } else if (level.value === "tables") {
    if (schemas.value.length > 0) {
      level.value = "schemas";
      selectedSchema.value = "";
    } else {
      level.value = "databases";
      selectedDatabase.value = "";
    }
  } else if (level.value === "schemas") {
    level.value = "databases";
    selectedDatabase.value = "";
  } else if (level.value === "databases") {
    level.value = "connections";
    selectedConnection.value = null;
  }
}

function handleBack() {
  if (createTableOpen.value) {
    createTableOpen.value = false;
    return true;
  }
  if (tableDataBrowser.value?.handleBack()) return true;
  if (level.value === "connections") return false;
  goBack();
  return true;
}

function getQueryContext() {
  const connection = selectedConnection.value ?? props.connections[0];
  if (!connection) return null;
  return {
    connectionId: connection.id,
    database: selectedDatabase.value || connection.database || databases.value[0]?.name || "",
    schema: selectedSchema.value || null,
  };
}

defineExpose({ getQueryContext, handleBack });

function retry() {
  if (retryAction) void runLoad(retryAction);
}

onMounted(() => {
  if (props.connections.length === 1) void openConnection(props.connections[0]);
});

onBeforeUnmount(() => {
  invalidateTableAction();
  detailRequestId++;
});
</script>

<template>
  <div class="metadata-browser">
    <CreateTableEditor
      v-if="createTableOpen && selectedConnection"
      :connection="selectedConnection"
      :database="selectedDatabase"
      :schema="selectedSchema"
      @close="createTableOpen = false"
      @open-query="openCreatedTableSql"
    />
    <TableDataBrowser v-if="level === 'data' && tableTarget" ref="tableDataBrowser" :target="tableTarget" @back="goBack" @open-query="openGeneratedQuery" />
    <template v-else>
      <div v-if="level === 'details'" class="browser-toolbar">
        <button type="button" aria-label="返回上一级" @click="goBack">←</button>
        <div>
          <span>{{ title }}</span>
          <p>{{ contextLabel }}</p>
        </div>
      </div>

      <div v-if="level !== 'connections' && level !== 'details'" class="browser-search">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6" />
          <path d="m16 16 4 4" />
        </svg>
        <input v-model="objectSearch" type="search" placeholder="搜索数据库、模式、表或列" />
        <button type="button" aria-label="筛选">≡</button>
      </div>

      <div v-if="loading" class="browser-state">
        <i class="loader" aria-hidden="true"></i>
        <strong>正在读取 {{ title }}</strong>
        <p>请求由 Android 原生驱动直接执行，数据库凭据只保存在本机加密仓库。</p>
      </div>

      <div v-else-if="errorMessage" class="browser-state error">
        <b aria-hidden="true">!</b>
        <strong>加载失败</strong>
        <p>{{ errorMessage }}</p>
        <button type="button" @click="retry">重新加载</button>
      </div>

      <template v-else>
        <div v-if="level === 'connections'" class="browser-list">
          <button v-for="connection in connections" :key="connection.id" class="browser-row connection" type="button" @click="openConnection(connection)">
            <i :style="{ background: connection.color || 'var(--acid)' }"></i>
            <span>
              <small>{{ connection.dbType }}<em v-if="connection.isProduction">PROD</em></small>
              <strong>{{ connection.name }}</strong>
              <p>{{ connection.host }}:{{ connection.port }}</p>
            </span>
            <b>›</b>
          </button>
          <div v-if="connections.length === 0" class="browser-state"><strong>暂无数据库连接</strong></div>
        </div>

        <div v-else-if="level === 'databases'" class="database-tree-view">
          <div class="tree-root-label">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m7 9 5 5 5-5" />
              <ellipse cx="12" cy="5" rx="6" ry="3" />
              <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
            </svg>
            <span>数据库 ({{ databases.length }})</span>
          </div>

          <div class="metadata-tree" role="tree" aria-label="数据库对象树">
            <template v-for="database in visibleDatabases" :key="database.name">
              <button class="tree-node database-node" :class="{ expanded: selectedDatabase === database.name }" type="button" role="treeitem" @click="openDatabase(database)">
                <span class="tree-chevron">›</span>
                <svg class="tree-icon" viewBox="0 0 24 24" aria-hidden="true">
                  <ellipse cx="12" cy="5" rx="6" ry="3" />
                  <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
                </svg>
                <span>{{ database.name }}</span>
              </button>

              <template v-if="selectedDatabase === database.name">
                <div v-if="showSchemaSwitcher" class="schema-switcher" aria-label="切换 Schema">
                  <span>模式</span>
                  <button v-for="schema in businessSchemas" :key="schema" :class="{ active: selectedSchema === schema }" type="button" @click="openSchema(schema)">{{ schema }}</button>
                </div>

                <button class="tree-node group-node" :class="{ expanded: expandedObjectGroups.has('tables') }" type="button" @click="toggleObjectGroup('tables')">
                  <span class="tree-chevron">›</span>
                  <svg class="tree-icon" viewBox="0 0 24 24" aria-hidden="true">
                    <rect x="3.5" y="4" width="17" height="16" rx="1.5" />
                    <path d="M3.5 9h17M9 4v16" />
                  </svg>
                  <span>表 ({{ visibleRelationTables.length }})</span>
                </button>
                <template v-if="expandedObjectGroups.has('tables')">
                  <button class="tree-node leaf-node create-table-node" :disabled="selectedConnection?.readOnly" type="button" @click="openCreateTable">
                    <span class="tree-symbol">＋</span><span>新建表</span><small>{{ selectedConnection?.readOnly ? "只读连接" : "可视化设计" }}</small>
                  </button>
                  <button v-for="table in visibleRelationTables" :key="`table:${table.name}`" class="tree-node leaf-node" type="button" @click="openTreeTable(table)">
                    <svg class="tree-icon" viewBox="0 0 24 24" aria-hidden="true">
                      <rect x="3.5" y="4" width="17" height="16" rx="1.5" />
                      <path d="M3.5 9h17M9 4v16M15 4v16" />
                    </svg>
                    <span>{{ table.name }}</span>
                    <small>{{ table.comment || table.table_type }}</small>
                  </button>
                  <button v-if="hasMoreTables" class="tree-node leaf-node load-node" :disabled="loadingMore" type="button" @click="loadMoreTables">
                    <span>…</span><small>{{ loadingMore ? "正在加载" : "加载更多" }}</small>
                  </button>
                </template>

                <button class="tree-node group-node" :class="{ expanded: expandedObjectGroups.has('views') }" type="button" @click="toggleObjectGroup('views')">
                  <span class="tree-chevron">›</span><span class="tree-symbol">⌑</span><span>视图 ({{ visibleViews.length }})</span>
                </button>
                <template v-if="expandedObjectGroups.has('views')">
                  <button v-for="view in visibleViews" :key="`view:${view.name}`" class="tree-node leaf-node" type="button" @click="openTreeTable(view)">
                    <span class="tree-symbol">⌑</span><span>{{ view.name }}</span
                    ><small>{{ view.comment || "VIEW" }}</small>
                  </button>
                </template>

                <template v-for="group in routineGroups" :key="group.key">
                  <button class="tree-node group-node" :class="{ expanded: expandedObjectGroups.has(group.key) }" type="button" @click="toggleObjectGroup(group.key)">
                    <span class="tree-chevron">›</span><span class="tree-symbol">{{ group.icon === "function" ? "ƒx" : group.icon === "procedure" ? "ƒ" : "≋" }}</span
                    ><span>{{ group.label }} ({{ group.items.length }})</span>
                  </button>
                  <template v-if="expandedObjectGroups.has(group.key)">
                    <button v-for="item in group.items" :key="`${group.key}:${item.name}:${item.signature || ''}`" class="tree-node leaf-node" type="button" @click="executeRoutineSql(item)">
                      <span class="tree-symbol">{{ group.icon === "sequence" ? "≋" : "ƒ" }}</span
                      ><span>{{ item.name }}</span
                      ><small>{{ item.signature || item.object_type }}</small>
                    </button>
                  </template>
                </template>
              </template>
            </template>
            <div v-if="databases.length === 0" class="browser-state compact"><strong>没有可见数据库</strong></div>
          </div>

          <section v-if="selectedDatabase" class="schema-inspector">
            <nav aria-label="数据库工具">
              <button :class="{ active: inspectorTab === 'structure' }" type="button" @click="selectInspectorTab('structure')">结构</button>
              <button :class="{ active: inspectorTab === 'data' }" type="button" @click="selectInspectorTab('data')">数据</button>
              <button :class="{ active: inspectorTab === 'query' }" type="button" @click="selectInspectorTab('query')">查询</button>
            </nav>
            <div v-if="inspectorTab === 'structure'" class="schema-summary">
              <div class="schema-summary-title">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <rect x="3.5" y="4" width="17" height="16" rx="1.5" />
                  <path d="M3.5 9h17M9 4v16" /></svg
                ><strong :title="selectedDatabase">{{ selectedDatabase }}</strong>
              </div>
              <dl>
                <dt>类型</dt>
                <dd>{{ selectedConnection?.dbType || "—" }}</dd>
                <dt v-if="showSchemaSwitcher">模式</dt>
                <dd v-if="showSchemaSwitcher">{{ selectedSchema }}</dd>
                <dt>字符集</dt>
                <dd>UTF8</dd>
              </dl>
              <dl>
                <dt>表</dt>
                <dd>{{ visibleRelationTables.length }}</dd>
                <dt>视图</dt>
                <dd>{{ visibleViews.length }}</dd>
                <dt>函数</dt>
                <dd>{{ routineGroups[0]?.items.length || 0 }}</dd>
                <dt>序列</dt>
                <dd>{{ routineGroups[2]?.items.length || 0 }}</dd>
              </dl>
            </div>
            <div v-else class="inspector-hint">{{ inspectorTab === "data" ? "选择上方表即可浏览数据" : "正在打开查询工作台…" }}</div>
          </section>
        </div>

        <div v-else-if="level === 'schemas'" class="browser-list">
          <button class="management-action" type="button" @click="createSchemaSql">＋ 创建 Schema SQL</button>
          <button v-for="schema in visibleSchemas" :key="schema" class="browser-row" type="button" @click="openSchema(schema)">
            <span class="object-icon">SC</span
            ><span
              ><small>SCHEMA</small><strong>{{ schema }}</strong></span
            ><b>›</b>
          </button>
          <div v-if="schemas.length === 0" class="browser-state"><strong>没有可见 Schema</strong></div>
        </div>

        <div v-else-if="level === 'tables'" class="schema-objects">
          <div class="schema-management-actions">
            <button :disabled="selectedConnection?.readOnly" type="button" @click="openCreateTable">＋ TABLE</button>
            <button type="button" @click="createViewSql">＋ VIEW</button>
            <button type="button" @click="renameSchemaSql">RENAME SCHEMA</button>
            <button class="danger" type="button" @click="dropSchemaSql">DROP SCHEMA SQL</button>
          </div>
          <div class="administration-actions">
            <span>数据库运维</span>
            <button type="button" @click="openAdministrationSql('users')">用户</button>
            <button type="button" @click="openAdministrationSql('sessions')">会话 / 进程</button>
            <button type="button" @click="openAdministrationSql('monitor')">监控指标</button>
          </div>
          <div class="object-tabs">
            <button :class="{ active: schemaSection === 'relations' }" type="button" @click="selectSchemaSection('relations')">
              表 / 视图 <b>{{ tables.length }}</b>
            </button>
            <button :class="{ active: schemaSection === 'routines' }" type="button" @click="selectSchemaSection('routines')">
              函数 / 过程 <b>{{ routinesLoaded ? routines.length : "—" }}</b>
            </button>
          </div>
          <div v-if="schemaSection === 'relations'" class="browser-list">
            <article v-for="table in visibleTables" :key="`${table.parent_schema || ''}:${table.name}`" class="table-row">
              <button class="table-main" type="button" @click="openTable(table)">
                <span class="object-icon">{{ table.table_type.toUpperCase().includes("VIEW") ? "VW" : "TB" }}</span>
                <span
                  ><small>{{ table.table_type }}</small
                  ><strong>{{ table.name }}</strong>
                  <p v-if="table.comment">{{ table.comment }}</p></span
                ><b>›</b>
              </button>
              <div class="table-actions">
                <button :disabled="!supportsTableBrowsing" type="button" @click="openTableData(table)">数据</button>
                <button :disabled="!supportsTableBrowsing || !!actionTable" type="button" @click="openTableQuery(table)">
                  {{ actionTable === table.name ? "生成中" : "SELECT ↗" }}
                </button>
                <button type="button" @click="renameRelationSql(table)">重命名</button>
                <button class="danger" type="button" @click="dropRelationSql(table)">删除 SQL</button>
              </div>
            </article>
            <div v-if="tables.length === 0" class="browser-state"><strong>没有可见表或视图</strong></div>
            <p v-if="!supportsTableBrowsing && tables.length" class="preview-note">数据预览当前支持 PostgreSQL、MySQL 和 SQL Server；元数据浏览仍可使用。</p>
            <button v-if="hasMoreTables" class="load-more" :disabled="loadingMore" type="button" @click="loadMoreTables">
              {{ loadingMore ? "正在加载" : `继续加载（已显示 ${tables.length}）` }}
            </button>
          </div>
          <div v-else-if="schemaSection === 'routines'" class="browser-list">
            <button v-for="routine in visibleRoutines" :key="`${routine.object_type}:${routine.name}:${routine.signature || ''}`" class="browser-row" type="button" @click="executeRoutineSql(routine)">
              <span class="object-icon">{{ routine.object_type === "PROCEDURE" ? "PR" : "FN" }}</span>
              <span>
                <small>{{ routine.object_type }}</small>
                <strong>{{ routine.name }}</strong>
                <p>{{ routine.signature || routine.comment || "生成执行 SQL" }}</p>
              </span>
              <b>›</b>
            </button>
            <div v-if="routines.length === 0" class="browser-state"><strong>没有可见函数或存储过程</strong></div>
          </div>
        </div>

        <div v-else-if="level === 'details'" class="column-list">
          <div v-if="selectedTable" class="column-actions">
            <button :disabled="!supportsTableBrowsing" type="button" @click="openTableData(selectedTable)">预览表数据</button>
            <button :disabled="!supportsTableBrowsing || !!actionTable" type="button" @click="openTableQuery(selectedTable)">
              {{ actionTable ? "正在生成" : "在查询工作台打开 ↗" }}
            </button>
            <button v-if="!selectedTable.table_type.toUpperCase().includes('VIEW')" type="button" @click="alterTableSql(selectedTable)">结构设计 SQL</button>
            <button v-if="!selectedTable.table_type.toUpperCase().includes('VIEW')" type="button" @click="addIndexSql(selectedTable)">新增索引 SQL</button>
            <button v-if="!selectedTable.table_type.toUpperCase().includes('VIEW')" type="button" @click="addForeignKeySql(selectedTable)">新增外键 SQL</button>
          </div>
          <div class="detail-tabs">
            <button
              v-for="tab in [
                ['columns', '字段'],
                ['indexes', '索引'],
                ['foreignKeys', '外键'],
              ] as [DetailTab, string][]"
              :key="tab[0]"
              :class="{ active: detailTab === tab[0] }"
              type="button"
              @click="loadDetail(tab[0])"
            >
              {{ tab[1] }}
            </button>
          </div>
          <div v-if="detailLoading" class="browser-state compact"><i class="loader"></i><strong>正在读取对象元数据</strong></div>
          <div v-else-if="detailError" class="browser-state error compact">
            <b>!</b><strong>加载失败</strong>
            <p>{{ detailError }}</p>
            <button type="button" @click="loadDetail(detailTab)">重试</button>
          </div>
          <template v-else-if="detailTab === 'columns'">
            <article v-for="column in columns" :key="column.name" class="column-row">
              <div>
                <strong>{{ column.name }}</strong
                ><em v-if="column.is_primary_key">PK</em>
              </div>
              <span>{{ column.data_type }}</span>
              <p>
                {{ column.is_nullable ? "可为空" : "非空" }}<template v-if="column.column_default"> · 默认 {{ column.column_default }}</template>
              </p>
              <small v-if="column.comment">{{ column.comment }}</small>
              <button v-if="selectedTable && !selectedTable.table_type.toUpperCase().includes('VIEW')" class="inline-danger" type="button" @click="dropColumnSql(column)">删除字段 SQL</button>
            </article>
            <div v-if="columns.length === 0" class="browser-state"><strong>没有可见字段</strong></div>
          </template>
          <template v-else-if="detailTab === 'indexes'">
            <article v-for="index in indexes" :key="index.name" class="metadata-card">
              <header>
                <strong>{{ index.name }}</strong
                ><span><em v-if="index.is_primary">PK</em><em v-if="index.is_unique">UNIQUE</em></span>
              </header>
              <code>{{ index.columns.join(", ") }}</code>
              <p v-if="index.index_type">{{ index.index_type }}</p>
              <p v-if="index.included_columns?.length">INCLUDE {{ index.included_columns.join(", ") }}</p>
              <pre v-if="index.filter">{{ index.filter }}</pre>
              <button v-if="!index.is_primary" class="inline-danger" type="button" @click="dropIndexSql(index)">删除索引 SQL</button>
            </article>
            <div v-if="indexes.length === 0" class="browser-state"><strong>没有可见索引</strong></div>
          </template>
          <template v-else-if="detailTab === 'foreignKeys'">
            <article v-for="foreignKey in foreignKeys" :key="`${foreignKey.name}:${foreignKey.column}`" class="metadata-card">
              <header>
                <strong>{{ foreignKey.name }}</strong
                ><span><em>FK</em></span>
              </header>
              <code>{{ foreignKey.column }} → {{ foreignKey.ref_schema ? `${foreignKey.ref_schema}.` : "" }}{{ foreignKey.ref_table }}.{{ foreignKey.ref_column }}</code>
              <p v-if="foreignKey.on_update || foreignKey.on_delete">ON UPDATE {{ foreignKey.on_update || "—" }} · ON DELETE {{ foreignKey.on_delete || "—" }}</p>
              <button class="inline-danger" type="button" @click="dropConstraintSql(foreignKey.name)">删除外键 SQL</button>
            </article>
            <div v-if="foreignKeys.length === 0" class="browser-state"><strong>没有可见外键</strong></div>
          </template>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.metadata-browser {
  margin-top: 0;
}
.browser-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: -10px 0 8px;
  border: 0;
  background: transparent;
  padding: 0;
}
.browser-toolbar button {
  width: 30px;
  height: 34px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-size: 18px;
}
.browser-toolbar span {
  color: var(--ink);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0;
}
.browser-toolbar p {
  overflow: hidden;
  max-width: 70vw;
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.browser-search {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 38px;
  align-items: center;
  min-height: 39px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--field);
}
.browser-search svg {
  width: 17px;
  height: 17px;
  margin-left: 11px;
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.7;
  stroke-linecap: round;
}
.browser-search input {
  width: 100%;
  min-width: 0;
  height: 37px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 10px;
}
.browser-search button {
  height: 27px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font-size: 18px;
  transform: rotate(180deg);
}
.browser-list,
.column-list {
  display: grid;
  gap: 3px;
}
.schema-objects {
  display: grid;
  gap: 8px;
}
.schema-management-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 1px solid var(--line);
}
.schema-management-actions button,
.management-action {
  min-height: 40px;
  border: 0;
  border-right: 1px solid var(--line);
  background: rgba(199, 255, 61, 0.055);
  color: var(--acid);
  font: inherit;
  font-size: 8px;
}
.management-action {
  border: 1px solid var(--line);
}
.object-tabs,
.detail-tabs {
  display: flex;
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  scrollbar-width: none;
}
.object-tabs button,
.detail-tabs button {
  flex: 1 0 auto;
  min-height: 40px;
  border: 0;
  background: transparent;
  padding: 0 11px;
  color: var(--muted);
  font: inherit;
  font-size: 8px;
  white-space: nowrap;
}
.object-tabs button + button,
.detail-tabs button + button {
  border-left: 1px solid var(--line);
}
.object-tabs button.active,
.detail-tabs button.active {
  background: var(--accent-soft);
  color: var(--acid);
  box-shadow: inset 0 -2px var(--acid);
}
.object-tabs b {
  margin-left: 5px;
  color: var(--faint);
  font-size: 7px;
}
.browser-row {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 46px;
  grid-template-columns: 30px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 11px;
  border: 0;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 70%, transparent);
  border-radius: 0;
  background: transparent;
  padding: 6px 8px;
  text-align: left;
}
.browser-row.connection {
  grid-template-columns: 3px minmax(0, 1fr) 16px;
  gap: 14px;
}
.browser-row.connection > i {
  width: 3px;
  height: 48px;
}
.browser-row > span:not(.object-icon) {
  min-width: 0;
}
.browser-row small {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.browser-row small em {
  border: 1px solid rgba(255, 187, 61, 0.4);
  padding: 2px 4px;
  color: var(--amber);
  font-style: normal;
}
.browser-row strong {
  display: block;
  overflow: hidden;
  margin-top: 6px;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.browser-row p {
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.browser-row > b {
  color: var(--faint);
  font-size: 20px;
  font-weight: 400;
}
.table-row {
  overflow: hidden;
  border: 0;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 75%, transparent);
  border-radius: 0;
  background: transparent;
}
.table-main {
  display: grid;
  width: 100%;
  min-height: 46px;
  grid-template-columns: 30px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 11px;
  border: 0;
  background: transparent;
  padding: 6px 8px;
  color: var(--ink);
  text-align: left;
}
.table-main > span:not(.object-icon) {
  min-width: 0;
}
.table-main small {
  display: flex;
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.table-main strong {
  display: block;
  overflow: hidden;
  margin-top: 6px;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-main p {
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-main > b {
  color: var(--faint);
  font-size: 20px;
  font-weight: 400;
}
.table-actions,
.column-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border-top: 1px solid var(--line);
}
.table-actions button,
.column-actions button {
  min-height: 36px;
  border: 0;
  background: rgba(199, 255, 61, 0.025);
  color: var(--acid);
  font: inherit;
  font-size: 8px;
  letter-spacing: 0.06em;
}
.table-actions button + button,
.column-actions button + button {
  border-left: 1px solid var(--line);
}
.table-actions button:disabled,
.column-actions button:disabled {
  color: var(--faint);
  opacity: 0.65;
}
.table-actions button.danger {
  color: var(--danger);
}
.column-actions {
  border: 1px solid var(--line);
}
.column-actions button {
  min-height: 42px;
}
.preview-note {
  margin: 0;
  border: 1px dashed rgba(255, 187, 61, 0.25);
  padding: 10px 12px;
  color: var(--amber);
  font-family: "PingFang SC", sans-serif;
  font-size: 8px;
  line-height: 1.6;
}
.object-icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--acid) 30%, var(--line));
  border-radius: 7px;
  background: var(--accent-soft);
  color: var(--acid);
  font-size: 9px;
}
.browser-state {
  min-height: 190px;
  border: 1px dashed rgba(235, 242, 232, 0.16);
  padding: 30px 22px;
}
.browser-state.compact {
  min-height: 130px;
}
.browser-state strong {
  display: block;
  margin-top: 18px;
  font-size: 14px;
}
.browser-state p {
  margin: 8px 0 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 11px;
  line-height: 1.6;
}
.browser-state > b {
  color: var(--danger);
  font-size: 28px;
}
.browser-state button,
.load-more {
  margin-top: 18px;
  border: 1px solid var(--line);
  background: transparent;
  padding: 10px 13px;
  color: var(--acid);
  font-size: 9px;
}
.loader {
  display: block;
  width: 24px;
  height: 24px;
  border: 2px solid var(--line);
  border-top-color: var(--acid);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.load-more {
  width: 100%;
  margin-top: 2px;
}
.column-row {
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 14px;
}
.column-row div {
  display: flex;
  align-items: center;
  gap: 7px;
}
.column-row strong {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.column-row em {
  border: 1px solid rgba(199, 255, 61, 0.35);
  padding: 2px 4px;
  color: var(--acid);
  font-size: 7px;
  font-style: normal;
}
.column-row > span {
  display: block;
  margin-top: 7px;
  color: var(--acid);
  font-size: 9px;
}
.column-row p,
.column-row small {
  display: block;
  margin: 7px 0 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.metadata-card {
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 13px;
}
.metadata-card header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.metadata-card header strong {
  overflow-wrap: anywhere;
  font-size: 11px;
}
.metadata-card header span {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 4px;
}
.metadata-card em {
  border: 1px solid rgba(199, 255, 61, 0.3);
  padding: 2px 4px;
  color: var(--acid);
  font-size: 7px;
  font-style: normal;
}
.metadata-card code {
  display: block;
  margin-top: 9px;
  color: var(--acid);
  font-size: 9px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.metadata-card p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 8px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.metadata-card pre {
  overflow: auto;
  max-height: 58dvh;
  margin: 9px 0 0;
  border: 1px solid var(--line);
  background: #080a09;
  padding: 11px;
  color: #cbd4cb;
  font:
    9px/1.65 "Azeret Mono Variable",
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.administration-actions button,
.inline-danger {
  border: 0;
  background: transparent;
  padding: 10px;
  color: var(--acid);
  font: inherit;
  font-size: 8px;
}
.administration-actions {
  display: grid;
  grid-template-columns: 1fr repeat(3, auto);
  align-items: center;
  border: 1px solid var(--line);
  border-top: 0;
}
.administration-actions span {
  padding: 10px;
  color: var(--muted);
  font-size: 8px;
}
.administration-actions button {
  border-left: 1px solid var(--line);
}
.inline-danger {
  margin-top: 9px;
  border: 1px solid rgba(255, 90, 90, 0.25);
  color: var(--danger);
}
.database-tree-view {
  display: grid;
  height: calc(100dvh - 205px);
  min-height: 380px;
  grid-template-rows: auto minmax(0, 1fr) auto;
  margin: 0 -2px;
}
.tree-root-label,
.tree-node {
  display: flex;
  align-items: center;
  color: var(--ink);
  font-family: "PingFang SC", system-ui, sans-serif;
}
.tree-root-label {
  min-height: 29px;
  gap: 7px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 550;
}
.tree-root-label svg,
.tree-icon {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.55;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.metadata-tree {
  overflow-y: auto;
  min-height: 160px;
  padding-bottom: 8px;
  scrollbar-width: none;
}
.metadata-tree::-webkit-scrollbar {
  display: none;
}
.tree-node {
  width: 100%;
  min-height: 28px;
  gap: 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  padding: 2px 6px;
  text-align: left;
  font-size: 10px;
  line-height: 1.2;
}
.tree-node:active {
  background: color-mix(in srgb, var(--accent-soft) 68%, transparent);
}
.database-node {
  padding-left: 18px;
}
.database-node > span:last-child,
.schema-node > span:last-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.schema-node {
  padding-left: 35px;
}
.group-node {
  padding-left: 35px;
}
.leaf-node {
  padding-left: 58px;
}
.create-table-node {
  color: var(--acid);
}
.create-table-node .tree-symbol {
  border: 1px solid color-mix(in srgb, var(--acid) 30%, var(--line));
  border-radius: 4px;
  background: var(--accent-soft);
  color: var(--acid);
}
.tree-node:disabled,
.schema-management-actions button:disabled {
  opacity: 0.45;
}
.schema-node.selected {
  background: var(--accent-soft);
  color: var(--acid);
}
.tree-chevron {
  width: 10px;
  flex: 0 0 10px;
  color: var(--muted);
  font-size: 16px;
  line-height: 1;
  transform: rotate(0deg);
  transition: transform 120ms ease;
}
.tree-node.expanded > .tree-chevron {
  transform: rotate(90deg);
}
.tree-symbol {
  width: 15px;
  flex: 0 0 15px;
  color: var(--muted);
  text-align: center;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10px;
}
.tree-menu {
  margin-left: auto;
  color: var(--acid);
  font-size: 17px;
  line-height: 1;
}
.schema-switcher {
  display: flex;
  overflow-x: auto;
  align-items: center;
  gap: 5px;
  min-height: 32px;
  margin-left: 35px;
  padding: 2px 6px 3px 0;
  scrollbar-width: none;
}
.schema-switcher::-webkit-scrollbar {
  display: none;
}
.schema-switcher > span {
  margin-right: 2px;
  color: var(--muted);
  font-size: 8px;
}
.schema-switcher button {
  flex: 0 0 auto;
  min-height: 23px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  padding: 0 9px;
  color: var(--muted);
  font: inherit;
  font-size: 8px;
}
.schema-switcher button.active {
  border-color: var(--acid);
  background: var(--accent-soft);
  color: var(--acid);
}
.leaf-node > small {
  overflow: hidden;
  margin-left: auto;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.leaf-node > span:not(.tree-symbol) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.leaf-node.load-node {
  color: var(--acid);
}
.schema-inspector {
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: 12px 12px 0 0;
  background: color-mix(in srgb, var(--panel) 96%, transparent);
  box-shadow: 0 -7px 22px rgba(0, 0, 0, 0.04);
}
.schema-inspector nav {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-bottom: 1px solid var(--line);
}
.schema-inspector nav button {
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 10px;
}
.schema-inspector nav button.active {
  color: var(--acid);
  box-shadow: inset 0 -2px var(--acid);
}
.schema-summary {
  display: grid;
  min-height: 104px;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.95fr) minmax(64px, 0.7fr);
  gap: 10px;
  padding: 13px 12px 15px;
}
.schema-summary-title {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 8px;
  padding-top: 2px;
  font-size: 11px;
}
.schema-summary-title svg {
  flex: 0 0 20px;
  width: 20px;
  height: 20px;
  fill: none;
  stroke: var(--ink);
  stroke-width: 1.5;
}
.schema-summary-title strong {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.schema-summary dl {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr);
  align-content: start;
  gap: 7px 9px;
  margin: 0;
  padding-left: 12px;
  border-left: 1px solid var(--line);
  font-size: 8px;
}
.schema-summary dt {
  color: var(--muted);
}
.schema-summary dd {
  overflow: hidden;
  margin: 0;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspector-hint {
  min-height: 90px;
  padding: 24px 14px;
  color: var(--muted);
  text-align: center;
  font-size: 10px;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
