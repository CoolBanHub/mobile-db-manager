<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import {
  ApiError,
  apiGetJson,
  apiPostJson,
  type DatabaseInfo,
  type MobileConnectionSummary,
  type MobileQueryDraft,
  type QueryResult,
  type SavedSqlFile,
} from "../lib/mobileApi";

const props = defineProps<{
  baseUrl: string;
  token: string | null;
  connections: MobileConnectionSummary[];
  draft?: MobileQueryDraft | null;
}>();
const emit = defineEmits<{ authExpired: []; draftConsumed: [] }>();
const connectionId = ref("");
const database = ref("");
const schema = ref("");
const databases = ref<DatabaseInfo[]>([]);
const schemas = ref<string[]>([]);
const sql = ref("SELECT 1;");
const result = ref<QueryResult | null>(null);
const error = ref("");
const loadingContext = ref(false);
const executing = ref(false);
const page = ref(0);
const saveName = ref("");
const saving = ref(false);
const saveStatus = ref("");
const PAGE_SIZE = 20;
let connectionRequestId = 0;
let schemaRequestId = 0;
let queryRequestId = 0;
let queryController: AbortController | null = null;

const selectedConnection = computed(() => props.connections.find((item) => item.id === connectionId.value));
const secureQuerySupported = computed(() => selectedConnection.value?.dbType === "postgres");
const pageRows = computed(() => result.value?.rows.slice(page.value * PAGE_SIZE, (page.value + 1) * PAGE_SIZE) ?? []);
const pageCount = computed(() => Math.max(1, Math.ceil((result.value?.rows.length ?? 0) / PAGE_SIZE)));

function fail(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit("authExpired");
  else error.value = reason instanceof Error ? reason.message : "请求失败";
}

function invalidateQuery() {
  queryRequestId++;
  queryController?.abort();
  queryController = null;
  executing.value = false;
  result.value = null;
  page.value = 0;
}

async function selectConnection(preferredDatabase?: string, preferredSchema?: string | null) {
  invalidateQuery();
  const requestId = ++connectionRequestId;
  schemaRequestId++;
  const requestedConnectionId = connectionId.value;
  database.value = "";
  schema.value = "";
  databases.value = [];
  schemas.value = [];
  result.value = null;
  if (!requestedConnectionId) return;
  loadingContext.value = true;
  error.value = "";
  try {
    const response = await apiGetJson<DatabaseInfo[]>(props.baseUrl, "/api/schema/databases", props.token, {
      connection_id: requestedConnectionId,
    });
    if (requestId !== connectionRequestId || connectionId.value !== requestedConnectionId) return;
    databases.value = response;
    database.value =
      (preferredDatabase && response.some((item) => item.name === preferredDatabase) ? preferredDatabase : "") ||
      selectedConnection.value?.database ||
      response[0]?.name ||
      "";
    if (database.value) await selectDatabase(preferredSchema);
  } catch (reason) {
    fail(reason);
  } finally {
    if (requestId === connectionRequestId) loadingContext.value = false;
  }
}

async function selectDatabase(preferredSchema?: string | null) {
  invalidateQuery();
  const requestId = ++schemaRequestId;
  const requestedConnectionId = connectionId.value;
  const requestedDatabase = database.value;
  schema.value = "";
  schemas.value = [];
  result.value = null;
  if (!requestedConnectionId || !requestedDatabase) return;
  try {
    const response = await apiGetJson<string[]>(props.baseUrl, "/api/schema/schemas", props.token, {
      connection_id: requestedConnectionId,
      database: requestedDatabase,
      apply_visible_filter: "true",
    });
    if (
      requestId !== schemaRequestId ||
      connectionId.value !== requestedConnectionId ||
      database.value !== requestedDatabase
    ) return;
    schemas.value = response;
    schema.value =
      preferredSchema !== undefined
        ? preferredSchema && response.includes(preferredSchema)
          ? preferredSchema
          : ""
        : response[0] || "";
  } catch (reason) {
    fail(reason);
  }
}

async function saveCurrentSql() {
  if (!connectionId.value || !database.value || !sql.value.trim() || !saveName.value.trim()) return;
  saving.value = true;
  error.value = "";
  saveStatus.value = "";
  try {
    await apiPostJson<SavedSqlFile>(
      props.baseUrl,
      "/api/mobile/saved-sql",
      props.token,
      {
        connectionId: connectionId.value,
        database: database.value,
        schema: schema.value || null,
        name: saveName.value.trim(),
        sql: sql.value,
      },
      { timeoutMs: 8_000 },
    );
    saveName.value = "";
    saveStatus.value = "已同步到 DBX Server";
  } catch (reason) {
    fail(reason);
  } finally {
    saving.value = false;
  }
}

watch(
  () => props.draft,
  async (draft) => {
    if (!draft) return;
    sql.value = draft.sql;
    connectionId.value = draft.connectionId;
    emit("draftConsumed");
    await selectConnection(draft.database, draft.schema);
  },
  { immediate: true },
);

async function execute() {
  if (!connectionId.value || !database.value || !sql.value.trim()) return;
  const requestId = ++queryRequestId;
  queryController?.abort();
  const controller = new AbortController();
  queryController = controller;
  executing.value = true;
  error.value = "";
  result.value = null;
  page.value = 0;
  try {
    const response = await apiPostJson<QueryResult>(
      props.baseUrl,
      "/api/mobile/query",
      props.token,
      {
        connectionId: connectionId.value,
        database: database.value,
        schema: schema.value || null,
        sql: sql.value,
      },
      { signal: controller.signal, timeoutMs: 45_000 },
    );
    if (requestId === queryRequestId) result.value = response;
  } catch (reason) {
    if (requestId === queryRequestId) {
      if (reason instanceof DOMException && reason.name === "AbortError") error.value = "查询已取消或网络请求超时";
      else fail(reason);
    }
  } finally {
    if (requestId === queryRequestId) {
      executing.value = false;
      queryController = null;
    }
  }
}

onBeforeUnmount(() => {
  connectionRequestId++;
  schemaRequestId++;
  invalidateQuery();
});

function displayValue(value: unknown) {
  if (value === null) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}
</script>

<template>
  <div class="query-workbench">
    <div class="context-grid">
      <label><span>连接</span><select v-model="connectionId" @change="selectConnection()"><option value="">选择连接</option><option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <label><span>数据库</span><select v-model="database" :disabled="!connectionId || loadingContext" @change="selectDatabase()"><option value="">选择数据库</option><option v-for="item in databases" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
      <label><span>Schema</span><select v-model="schema" :disabled="schemas.length === 0" @change="invalidateQuery"><option value="">默认</option><option v-for="item in schemas" :key="item" :value="item">{{ item }}</option></select></label>
    </div>
    <div class="editor">
      <div><span>DB-ENFORCED READ ONLY</span><small>单条 SQL · 200 行 · 2 MiB · 30 秒</small></div>
      <textarea v-model="sql" spellcheck="false" autocapitalize="none" placeholder="SELECT * FROM table_name;"></textarea>
      <p v-if="selectedConnection && !secureQuerySupported" class="support-note">该数据库暂不支持数据库层只读事务，移动查询已禁用。</p>
      <button :disabled="executing || !secureQuerySupported || !database || !sql.trim()" type="button" @click="execute">{{ executing ? "正在执行…" : "执行查询  ▶" }}</button>
      <form class="save-sql" @submit.prevent="saveCurrentSql">
        <input v-model="saveName" maxlength="120" placeholder="给当前 SQL 命名" />
        <button :disabled="saving || !database || !sql.trim() || !saveName.trim()" type="submit">{{ saving ? "保存中" : "保存 SQL" }}</button>
      </form>
      <p v-if="saveStatus" class="save-status">{{ saveStatus }}</p>
    </div>
    <div v-if="error" class="query-error"><b>!</b><span>{{ error }}</span></div>
    <section v-if="result" class="result-panel">
      <header><span>{{ result.rows.length }} ROWS · {{ result.execution_time_ms }} MS</span><em v-if="result.truncated">TRUNCATED</em></header>
      <div v-if="result.columns.length" class="result-scroll"><table><thead><tr><th v-for="column in result.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in pageRows" :key="rowIndex"><td v-for="(value, index) in row" :key="index" :class="{ null: value === null }">{{ displayValue(value) }}</td></tr></tbody></table></div>
      <p v-else>执行成功，影响 {{ result.affected_rows }} 行。</p>
      <footer v-if="pageCount > 1"><button :disabled="page === 0" @click="page--">←</button><span>{{ page + 1 }} / {{ pageCount }}</span><button :disabled="page + 1 >= pageCount" @click="page++">→</button></footer>
    </section>
  </div>
</template>

<style scoped>
.query-workbench { display: grid; gap: 12px; margin-top: 16px; }
.context-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.context-grid label:last-child { grid-column: 1 / -1; }
label span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 8px; letter-spacing: .12em; }
select { width: 100%; height: 42px; border: 1px solid var(--line); border-radius: 0; background: var(--panel); padding: 0 9px; color: var(--ink); font: inherit; font-size: 10px; }
.editor { border: 1px solid var(--line); border-top: 2px solid var(--acid); background: #080a09; }
.editor > div { display: flex; justify-content: space-between; padding: 10px 12px; color: var(--acid); font-size: 8px; letter-spacing: .12em; }
.editor small { color: var(--muted); }
textarea { display: block; width: 100%; min-height: 180px; resize: vertical; border: 0; border-top: 1px solid var(--line); outline: none; background: transparent; padding: 15px; color: #e7f5d1; font: 12px/1.65 "Azeret Mono Variable", monospace; }
.support-note { margin: 0; border-top: 1px solid var(--line); padding: 10px 12px; color: var(--amber); font-size: 9px; line-height: 1.5; }
.editor button { width: 100%; min-height: 46px; border: 0; border-top: 1px solid var(--line); background: var(--acid); color: #10130c; font-weight: 760; }
.save-sql { display: grid; grid-template-columns: 1fr auto; border-top: 1px solid var(--line); }
.save-sql input { min-width: 0; border: 0; background: #101310; padding: 0 12px; color: var(--ink); font: inherit; font-size: 10px; outline: none; }
.save-sql button { width: auto; min-width: 92px; border-top: 0; border-left: 1px solid var(--line); background: transparent; color: var(--acid); }
.save-status { margin: 0; border-top: 1px solid var(--line); padding: 8px 12px; color: var(--acid); font-size: 8px; }
button:disabled { opacity: .45; }
.query-error { display: flex; gap: 10px; border: 1px solid rgba(255,101,95,.35); padding: 13px; color: var(--danger); font-size: 10px; line-height: 1.5; }
.result-panel { border: 1px solid var(--line); background: var(--panel); }
.result-panel header { display: flex; justify-content: space-between; padding: 11px; color: var(--acid); font-size: 8px; letter-spacing: .1em; }
.result-panel em { color: var(--amber); font-style: normal; }
.result-scroll { overflow: auto; max-height: 48vh; border-top: 1px solid var(--line); }
table { border-collapse: collapse; min-width: 100%; font-size: 9px; white-space: nowrap; }
th, td { max-width: 240px; overflow: hidden; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 9px; text-align: left; text-overflow: ellipsis; }
th { position: sticky; top: 0; background: #171b18; color: var(--acid); }
td.null { color: var(--faint); font-style: italic; }
.result-panel > p { padding: 20px; color: var(--muted); font-size: 10px; }
.result-panel footer { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 9px; }
.result-panel footer button { width: 34px; height: 30px; border: 1px solid var(--line); background: transparent; color: var(--acid); }
.result-panel footer span { font-size: 9px; }
</style>
