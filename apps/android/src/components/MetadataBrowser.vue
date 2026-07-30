<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import TableDataBrowser from "./TableDataBrowser.vue";
import { buildDirectTableTemplate, loadDirectMetadata } from "../lib/directDatabase";
import {
  type ColumnInfo,
  type DatabaseInfo,
  type DatabaseObjectInfo,
  type ForeignKeyInfo,
  type IndexInfo,
  type MobileConnectionSummary,
  type MobileQueryDraft,
  type MobileTableTarget,
  type TableInfo,
} from "../lib/mobileTypes";

type BrowseLevel = "connections" | "databases" | "schemas" | "tables" | "details" | "data";
type SchemaSection = "relations" | "routines";
type DetailTab = "columns" | "indexes" | "foreignKeys";

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
let retryAction: (() => Promise<void>) | null = null;
let tableActionRequestId = 0;
let detailRequestId = 0;
const TABLE_BROWSING_DATABASE_TYPES = new Set(["postgres", "mysql", "sqlserver"]);

const supportsTableBrowsing = computed(() => TABLE_BROWSING_DATABASE_TYPES.has(selectedConnection.value?.dbType ?? ""));

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
  level.value = "schemas";

  const connectionId = selectedConnection.value.id;
  await runLoad(async () => {
    schemas.value = await loadDirectMetadata<string[]>("schemas", {
      connectionId,
      database: database.name,
    });
    if (schemas.value.length === 0) {
      level.value = "tables";
      await fetchTables(connectionId, database.name, "", 0);
    }
  });
}

async function openSchema(schema: string) {
  if (!selectedConnection.value) return;
  invalidateTableAction();
  selectedSchema.value = schema;
  selectedTable.value = null;
  tables.value = [];
  routines.value = [];
  routinesLoaded.value = false;
  columns.value = [];
  schemaSection.value = "relations";
  level.value = "tables";
  const connectionId = selectedConnection.value.id;
  await runLoad(() => fetchTables(connectionId, selectedDatabase.value, schema, 0));
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
  return selectedSchema.value
    ? `${quoteIdentifier(selectedSchema.value)}.${quoteIdentifier(name)}`
    : quoteIdentifier(name);
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

function createRelationSql(kind: "table" | "view") {
  const name = window.prompt(kind === "table" ? "新表名称" : "新视图名称");
  if (!name?.trim()) return;
  const target = qualifiedObject(name.trim());
  openManagementSql(
    kind === "table"
      ? `CREATE TABLE ${target} (\n  id INTEGER PRIMARY KEY\n);`
      : `CREATE VIEW ${target} AS\nSELECT 1 AS value;`,
  );
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
  openManagementSql(
    `ALTER TABLE ${qualifiedObject(table.name)}\n  ADD CONSTRAINT ${quoteIdentifier(constraintName.trim())} FOREIGN KEY (${quoteIdentifier(column.trim())})\n  REFERENCES ${reference.trim()};`,
  );
}

function dropColumnSql(column: ColumnInfo) {
  if (!selectedTable.value) return;
  openManagementSql(
    `ALTER TABLE ${qualifiedObject(selectedTable.value.name)} DROP COLUMN ${quoteIdentifier(column.name)};`,
  );
}

function dropIndexSql(index: IndexInfo) {
  if (!selectedTable.value) return;
  const dbType = selectedConnection.value?.dbType;
  openManagementSql(
    dbType === "mysql"
      ? `DROP INDEX ${quoteIdentifier(index.name)} ON ${qualifiedObject(selectedTable.value.name)};`
      : `DROP INDEX ${selectedSchema.value ? `${quoteIdentifier(selectedSchema.value)}.` : ""}${quoteIdentifier(index.name)};`,
  );
}

function dropConstraintSql(name: string) {
  if (!selectedTable.value) return;
  openManagementSql(
    `ALTER TABLE ${qualifiedObject(selectedTable.value.name)} DROP CONSTRAINT ${quoteIdentifier(name)};`,
  );
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
    level.value = "tables";
    tableTarget.value = null;
    selectedTable.value = null;
  } else if (level.value === "details") {
    level.value = "tables";
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

function retry() {
  if (retryAction) void runLoad(retryAction);
}

onBeforeUnmount(() => {
  invalidateTableAction();
  detailRequestId++;
});
</script>

<template>
  <div class="metadata-browser">
    <TableDataBrowser v-if="level === 'data' && tableTarget" :target="tableTarget" @back="goBack" @open-query="openGeneratedQuery" />
    <template v-else>
      <div v-if="level !== 'connections'" class="browser-toolbar">
        <button type="button" aria-label="返回上一级" @click="goBack">←</button>
        <div>
          <span>{{ title }}</span>
          <p>{{ contextLabel }}</p>
        </div>
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

        <div v-else-if="level === 'databases'" class="browser-list">
          <button class="management-action" type="button" @click="createDatabaseSql">＋ 创建数据库 SQL</button>
          <article v-for="database in databases" :key="database.name" class="table-row">
            <button class="browser-row" type="button" @click="openDatabase(database)">
              <span class="object-icon">DB</span
              ><span><small>DATABASE</small><strong>{{ database.name }}</strong></span
              ><b>›</b>
            </button>
            <div class="table-actions"><button class="danger" type="button" @click="dropDatabaseSql(database)">生成删除 SQL</button></div>
          </article>
          <div v-if="databases.length === 0" class="browser-state"><strong>没有可见数据库</strong></div>
        </div>

        <div v-else-if="level === 'schemas'" class="browser-list">
          <button class="management-action" type="button" @click="createSchemaSql">＋ 创建 Schema SQL</button>
          <button v-for="schema in schemas" :key="schema" class="browser-row" type="button" @click="openSchema(schema)">
            <span class="object-icon">SC</span
            ><span
              ><small>SCHEMA</small><strong>{{ schema }}</strong></span
            ><b>›</b>
          </button>
          <div v-if="schemas.length === 0" class="browser-state"><strong>没有可见 Schema</strong></div>
        </div>

        <div v-else-if="level === 'tables'" class="schema-objects">
          <div class="schema-management-actions">
            <button type="button" @click="createRelationSql('table')">＋ TABLE</button>
            <button type="button" @click="createRelationSql('view')">＋ VIEW</button>
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
            <article v-for="table in tables" :key="`${table.parent_schema || ''}:${table.name}`" class="table-row">
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
            <button v-for="routine in routines" :key="`${routine.object_type}:${routine.name}:${routine.signature || ''}`" class="browser-row" type="button" @click="executeRoutineSql(routine)">
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
  margin-top: 16px;
}
.browser-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 10px;
}
.browser-toolbar button {
  width: 38px;
  height: 38px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--acid);
}
.browser-toolbar span {
  color: var(--acid);
  font-size: 8px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
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
.browser-list,
.column-list {
  display: grid;
  gap: 8px;
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
  background: #0b0d0c;
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
  background: rgba(199, 255, 61, 0.07);
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
  min-height: 78px;
  grid-template-columns: 38px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 11px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 12px;
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
  border: 1px solid var(--line);
  background: var(--panel);
}
.table-main {
  display: grid;
  width: 100%;
  min-height: 76px;
  grid-template-columns: 38px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 11px;
  border: 0;
  background: transparent;
  padding: 12px;
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
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgba(199, 255, 61, 0.3);
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
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
