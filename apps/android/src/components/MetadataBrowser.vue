<script setup lang="ts">
import { computed, ref } from "vue";
import {
  ApiError,
  apiGetJson,
  type ColumnInfo,
  type DatabaseInfo,
  type MobileConnectionSummary,
  type TableInfo,
} from "../lib/mobileApi";

type BrowseLevel = "connections" | "databases" | "schemas" | "tables" | "columns";

const props = defineProps<{
  baseUrl: string;
  token: string | null;
  connections: MobileConnectionSummary[];
}>();

const emit = defineEmits<{
  authExpired: [];
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
const columns = ref<ColumnInfo[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const errorMessage = ref("");
const hasMoreTables = ref(false);
let retryAction: (() => Promise<void>) | null = null;

const title = computed(() => {
  if (level.value === "connections") return "连接";
  if (level.value === "databases") return "数据库";
  if (level.value === "schemas") return "Schema";
  if (level.value === "tables") return "表与视图";
  return "字段";
});

const contextLabel = computed(() => {
  const parts = [
    selectedConnection.value?.name,
    selectedDatabase.value,
    selectedSchema.value,
    selectedTable.value?.name,
  ].filter(Boolean);
  return parts.join(" / ");
});

function handleError(error: unknown, retry: () => Promise<void>) {
  if (error instanceof ApiError && error.status === 401) {
    emit("authExpired");
    return;
  }
  errorMessage.value = error instanceof Error ? error.message : "元数据加载失败";
  retryAction = retry;
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
  selectedConnection.value = connection;
  selectedDatabase.value = "";
  selectedSchema.value = "";
  selectedTable.value = null;
  databases.value = [];
  schemas.value = [];
  tables.value = [];
  columns.value = [];
  level.value = "databases";
  await runLoad(async () => {
    databases.value = await apiGetJson<DatabaseInfo[]>(
      props.baseUrl,
      "/api/schema/databases",
      props.token,
      { connection_id: connection.id },
    );
  });
}

async function openDatabase(database: DatabaseInfo) {
  if (!selectedConnection.value) return;
  selectedDatabase.value = database.name;
  selectedSchema.value = "";
  selectedTable.value = null;
  schemas.value = [];
  tables.value = [];
  columns.value = [];
  level.value = "schemas";

  const connectionId = selectedConnection.value.id;
  await runLoad(async () => {
    schemas.value = await apiGetJson<string[]>(props.baseUrl, "/api/schema/schemas", props.token, {
      connection_id: connectionId,
      database: database.name,
      apply_visible_filter: "true",
    });
    if (schemas.value.length === 0) {
      level.value = "tables";
      await fetchTables(connectionId, database.name, "", 0);
    }
  });
}

async function openSchema(schema: string) {
  if (!selectedConnection.value) return;
  selectedSchema.value = schema;
  selectedTable.value = null;
  tables.value = [];
  columns.value = [];
  level.value = "tables";
  const connectionId = selectedConnection.value.id;
  await runLoad(() => fetchTables(connectionId, selectedDatabase.value, schema, 0));
}

async function fetchTables(connectionId: string, database: string, schema: string, offset: number) {
  const page = await apiGetJson<TableInfo[]>(props.baseUrl, "/api/schema/tables", props.token, {
    connection_id: connectionId,
    database,
    schema,
    limit: TABLE_PAGE_SIZE,
    offset,
  });
  tables.value = offset === 0 ? page : [...tables.value, ...page];
  hasMoreTables.value = page.length === TABLE_PAGE_SIZE;
}

async function loadMoreTables() {
  if (!selectedConnection.value || loadingMore.value) return;
  loadingMore.value = true;
  errorMessage.value = "";
  try {
    await fetchTables(
      selectedConnection.value.id,
      selectedDatabase.value,
      selectedSchema.value,
      tables.value.length,
    );
  } catch (error) {
    handleError(error, loadMoreTables);
  } finally {
    loadingMore.value = false;
  }
}

async function openTable(table: TableInfo) {
  if (!selectedConnection.value) return;
  selectedTable.value = table;
  columns.value = [];
  level.value = "columns";
  const connectionId = selectedConnection.value.id;
  await runLoad(async () => {
    columns.value = await apiGetJson<ColumnInfo[]>(props.baseUrl, "/api/schema/columns", props.token, {
      connection_id: connectionId,
      database: selectedDatabase.value,
      schema: selectedSchema.value,
      table: table.name,
    });
  });
}

function goBack() {
  errorMessage.value = "";
  if (level.value === "columns") {
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
</script>

<template>
  <div class="metadata-browser">
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
      <p>请求由 DBX Server 执行，数据库凭据不会进入手机。</p>
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
        <button v-for="database in databases" :key="database.name" class="browser-row" type="button" @click="openDatabase(database)">
          <span class="object-icon">DB</span><span><small>DATABASE</small><strong>{{ database.name }}</strong></span><b>›</b>
        </button>
        <div v-if="databases.length === 0" class="browser-state"><strong>没有可见数据库</strong></div>
      </div>

      <div v-else-if="level === 'schemas'" class="browser-list">
        <button v-for="schema in schemas" :key="schema" class="browser-row" type="button" @click="openSchema(schema)">
          <span class="object-icon">SC</span><span><small>SCHEMA</small><strong>{{ schema }}</strong></span><b>›</b>
        </button>
        <div v-if="schemas.length === 0" class="browser-state"><strong>没有可见 Schema</strong></div>
      </div>

      <div v-else-if="level === 'tables'" class="browser-list">
        <button v-for="table in tables" :key="`${table.parent_schema || ''}:${table.name}`" class="browser-row" type="button" @click="openTable(table)">
          <span class="object-icon">{{ table.table_type.toUpperCase().includes("VIEW") ? "VW" : "TB" }}</span>
          <span><small>{{ table.table_type }}</small><strong>{{ table.name }}</strong><p v-if="table.comment">{{ table.comment }}</p></span><b>›</b>
        </button>
        <div v-if="tables.length === 0" class="browser-state"><strong>没有可见表或视图</strong></div>
        <button v-if="hasMoreTables" class="load-more" :disabled="loadingMore" type="button" @click="loadMoreTables">
          {{ loadingMore ? "正在加载" : `继续加载（已显示 ${tables.length}）` }}
        </button>
      </div>

      <div v-else class="column-list">
        <article v-for="column in columns" :key="column.name" class="column-row">
          <div><strong>{{ column.name }}</strong><em v-if="column.is_primary_key">PK</em></div>
          <span>{{ column.data_type }}</span>
          <p>
            {{ column.is_nullable ? "可为空" : "非空" }}
            <template v-if="column.column_default"> · 默认 {{ column.column_default }}</template>
          </p>
          <small v-if="column.comment">{{ column.comment }}</small>
        </article>
        <div v-if="columns.length === 0" class="browser-state"><strong>没有可见字段</strong></div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.metadata-browser { margin-top: 16px; }
.browser-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; border: 1px solid var(--line); background: var(--panel); padding: 10px; }
.browser-toolbar button { width: 38px; height: 38px; border: 1px solid var(--line); background: transparent; color: var(--acid); }
.browser-toolbar span { color: var(--acid); font-size: 8px; letter-spacing: .14em; text-transform: uppercase; }
.browser-toolbar p { overflow: hidden; max-width: 70vw; margin: 4px 0 0; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.browser-list, .column-list { display: grid; gap: 8px; }
.browser-row { position: relative; display: grid; width: 100%; min-height: 78px; grid-template-columns: 38px minmax(0, 1fr) 16px; align-items: center; gap: 11px; border: 1px solid var(--line); background: var(--panel); padding: 12px; text-align: left; }
.browser-row.connection { grid-template-columns: 3px minmax(0, 1fr) 16px; gap: 14px; }
.browser-row.connection > i { width: 3px; height: 48px; }
.browser-row > span:not(.object-icon) { min-width: 0; }
.browser-row small { display: flex; align-items: center; gap: 6px; color: var(--acid); font-size: 7px; letter-spacing: .12em; text-transform: uppercase; }
.browser-row small em { border: 1px solid rgba(255,187,61,.4); padding: 2px 4px; color: var(--amber); font-style: normal; }
.browser-row strong { display: block; overflow: hidden; margin-top: 6px; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.browser-row p { overflow: hidden; margin: 5px 0 0; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.browser-row > b { color: var(--faint); font-size: 20px; font-weight: 400; }
.object-icon { display: grid; width: 38px; height: 38px; place-items: center; border: 1px solid rgba(199,255,61,.3); color: var(--acid); font-size: 9px; }
.browser-state { min-height: 190px; border: 1px dashed rgba(235,242,232,.16); padding: 30px 22px; }
.browser-state strong { display: block; margin-top: 18px; font-size: 14px; }
.browser-state p { margin: 8px 0 0; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 11px; line-height: 1.6; }
.browser-state > b { color: var(--danger); font-size: 28px; }
.browser-state button, .load-more { margin-top: 18px; border: 1px solid var(--line); background: transparent; padding: 10px 13px; color: var(--acid); font-size: 9px; }
.loader { display: block; width: 24px; height: 24px; border: 2px solid var(--line); border-top-color: var(--acid); border-radius: 50%; animation: spin .8s linear infinite; }
.load-more { width: 100%; margin-top: 2px; }
.column-row { border: 1px solid var(--line); background: var(--panel); padding: 14px; }
.column-row div { display: flex; align-items: center; gap: 7px; }
.column-row strong { overflow-wrap: anywhere; font-size: 12px; }
.column-row em { border: 1px solid rgba(199,255,61,.35); padding: 2px 4px; color: var(--acid); font-size: 7px; font-style: normal; }
.column-row > span { display: block; margin-top: 7px; color: var(--acid); font-size: 9px; }
.column-row p, .column-row small { display: block; margin: 7px 0 0; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
