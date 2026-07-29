<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import {
  ApiError,
  apiFetch,
  apiGetJson,
  apiPostJson,
  type ColumnInfo,
  type DatabaseInfo,
  type MobileConnectionSummary,
  type MobileQueryDraft,
  type QueryResult,
  type SavedSqlFile,
  type TableInfo,
} from "../lib/mobileApi";
import { exportQueryResult, type QueryExportFormat } from "../lib/queryExport";
import {
  applySqlSuggestion,
  buildColumnCondition,
  buildTableSelect,
  formatSql,
  mergeTableMetadata,
  sqlSuggestions,
  type SqlSuggestion,
} from "../lib/sqlEditor";

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
const browsedTables = ref<TableInfo[]>([]);
const tableSearchResults = ref<TableInfo[]>([]);
const tableSearch = ref("");
const columns = ref<ColumnInfo[]>([]);
const selectedTable = ref<TableInfo | null>(null);
const sql = ref("SELECT 1;");
const editorElement = ref<HTMLTextAreaElement | null>(null);
const suggestions = ref<SqlSuggestion[]>([]);
const result = ref<QueryResult | null>(null);
const error = ref("");
const loadingContext = ref(false);
const executing = ref(false);
const cancelling = ref(false);
const loadingMetadata = ref(false);
const loadingMoreTables = ref(false);
const searchingTables = ref(false);
const browsedTablesHaveMore = ref(false);
const searchResultsHaveMore = ref(false);
const resultOffset = ref(0);
const saveName = ref("");
const savedSqlId = ref("");
const savedSqlFolderId = ref<string | null>(null);
const saving = ref(false);
const saveStatus = ref("");
const exporting = ref(false);
const exportStatus = ref("");
const exportFormat = ref<QueryExportFormat>("csv");
const columnWidths = ref<Record<number, number>>({});
const selectedCell = ref<{
  rowIndex: number;
  pageRowIndex: number;
  columnIndex: number;
  value: unknown;
} | null>(null);
const SERVER_PAGE_SIZE = 50;
const TABLE_PAGE_SIZE = 100;
let connectionRequestId = 0;
let schemaRequestId = 0;
let tableRequestId = 0;
let queryRequestId = 0;
let exportRequestId = 0;
let queryController: AbortController | null = null;
let activeExecutionId: string | null = null;
let tableSearchTimer: ReturnType<typeof setTimeout> | null = null;

const selectedConnection = computed(() => props.connections.find((item) => item.id === connectionId.value));
const resultPage = computed(() => Math.floor(resultOffset.value / SERVER_PAGE_SIZE) + 1);
const tables = computed(() => mergeTableMetadata(browsedTables.value, tableSearchResults.value));
const visibleTables = computed(() =>
  tableSearch.value.trim() ? tableSearchResults.value : browsedTables.value,
);
const hasMoreTables = computed(() =>
  tableSearch.value.trim() ? searchResultsHaveMore.value : browsedTablesHaveMore.value,
);

function fail(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit("authExpired");
  else error.value = reason instanceof Error ? reason.message : "请求失败";
}

function createExecutionId() {
  return globalThis.crypto?.randomUUID?.() ?? `mobile-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function requestServerCancellation(executionId: string, keepalive = false) {
  return apiFetch(props.baseUrl, `/api/mobile/query/${encodeURIComponent(executionId)}`, props.token, {
    method: "DELETE",
    headers: { Accept: "application/json" },
    keepalive,
  });
}

function invalidateQuery() {
  queryRequestId++;
  exportRequestId++;
  if (activeExecutionId) void requestServerCancellation(activeExecutionId, true).catch(() => undefined);
  queryController?.abort();
  queryController = null;
  activeExecutionId = null;
  executing.value = false;
  cancelling.value = false;
  exporting.value = false;
  result.value = null;
  exportStatus.value = "";
  resultOffset.value = 0;
  columnWidths.value = {};
  selectedCell.value = null;
}

async function selectConnection(preferredDatabase?: string, preferredSchema?: string | null) {
  invalidateQuery();
  const requestId = ++connectionRequestId;
  schemaRequestId++;
  tableRequestId++;
  const requestedConnectionId = connectionId.value;
  database.value = "";
  schema.value = "";
  databases.value = [];
  schemas.value = [];
  browsedTables.value = [];
  tableSearchResults.value = [];
  tableSearch.value = "";
  loadingMetadata.value = false;
  loadingMoreTables.value = false;
  searchingTables.value = false;
  columns.value = [];
  selectedTable.value = null;
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
    await loadTables();
  } catch (reason) {
    fail(reason);
  }
}

async function selectSchema() {
  invalidateQuery();
  await loadTables();
}

async function loadTables() {
  const requestId = ++tableRequestId;
  if (tableSearchTimer) clearTimeout(tableSearchTimer);
  tableSearchTimer = null;
  browsedTables.value = [];
  tableSearchResults.value = [];
  tableSearch.value = "";
  browsedTablesHaveMore.value = false;
  searchResultsHaveMore.value = false;
  loadingMoreTables.value = false;
  searchingTables.value = false;
  columns.value = [];
  selectedTable.value = null;
  if (!connectionId.value || !database.value) {
    loadingMetadata.value = false;
    return;
  }
  loadingMetadata.value = true;
  try {
    const page = await fetchTablePage("", 0);
    if (requestId !== tableRequestId) return;
    browsedTables.value = page;
    browsedTablesHaveMore.value = page.length === TABLE_PAGE_SIZE;
  } catch (reason) {
    if (requestId === tableRequestId) fail(reason);
  } finally {
    if (requestId === tableRequestId) loadingMetadata.value = false;
  }
}

function fetchTablePage(filter: string, offset: number) {
  return apiGetJson<TableInfo[]>(props.baseUrl, "/api/schema/tables", props.token, {
    connection_id: connectionId.value,
    database: database.value,
    schema: schema.value,
    filter: filter || undefined,
    limit: TABLE_PAGE_SIZE,
    offset,
  });
}

async function searchTables() {
  const requestId = ++tableRequestId;
  const filter = tableSearch.value.trim();
  loadingMetadata.value = false;
  loadingMoreTables.value = false;
  tableSearchResults.value = [];
  searchResultsHaveMore.value = false;
  if (!filter) {
    searchingTables.value = false;
    return;
  }
  searchingTables.value = true;
  try {
    const page = await fetchTablePage(filter, 0);
    if (requestId !== tableRequestId || filter !== tableSearch.value.trim()) return;
    tableSearchResults.value = page;
    searchResultsHaveMore.value = page.length === TABLE_PAGE_SIZE;
  } catch (reason) {
    if (requestId === tableRequestId) fail(reason);
  } finally {
    if (requestId === tableRequestId) searchingTables.value = false;
  }
}

function scheduleTableSearch() {
  if (tableSearchTimer) clearTimeout(tableSearchTimer);
  tableSearchTimer = setTimeout(() => {
    tableSearchTimer = null;
    void searchTables();
  }, 250);
}

function clearTableSearch() {
  if (tableSearchTimer) clearTimeout(tableSearchTimer);
  tableSearchTimer = null;
  tableSearch.value = "";
  void searchTables();
}

async function loadMoreTables() {
  if (loadingMoreTables.value || !hasMoreTables.value) return;
  const requestId = ++tableRequestId;
  const filter = tableSearch.value.trim();
  const current = filter ? tableSearchResults.value : browsedTables.value;
  loadingMoreTables.value = true;
  try {
    const page = await fetchTablePage(filter, current.length);
    if (requestId !== tableRequestId || filter !== tableSearch.value.trim()) return;
    if (filter) tableSearchResults.value = mergeTableMetadata(tableSearchResults.value, page);
    else browsedTables.value = mergeTableMetadata(browsedTables.value, page);
    if (filter) searchResultsHaveMore.value = page.length === TABLE_PAGE_SIZE;
    else browsedTablesHaveMore.value = page.length === TABLE_PAGE_SIZE;
  } catch (reason) {
    if (requestId === tableRequestId) fail(reason);
  } finally {
    if (requestId === tableRequestId) loadingMoreTables.value = false;
  }
}

async function openTable(table: TableInfo) {
  selectedTable.value = table;
  columns.value = [];
  loadingMetadata.value = true;
  try {
    columns.value = await apiGetJson<ColumnInfo[]>(props.baseUrl, "/api/schema/columns", props.token, {
      connection_id: connectionId.value,
      database: database.value,
      schema: schema.value,
      table: table.name,
    });
  } catch (reason) {
    fail(reason);
  } finally {
    loadingMetadata.value = false;
  }
}

function generateTableQuery(table: TableInfo) {
  sql.value = buildTableSelect(table.name, schema.value || null, selectedConnection.value?.dbType ?? "postgres");
  void openTable(table);
  suggestions.value = [];
}

function addColumnCondition(column: ColumnInfo) {
  sql.value = buildColumnCondition(sql.value, column.name, selectedConnection.value?.dbType ?? "postgres");
  suggestions.value = [];
  requestAnimationFrame(() => {
    const editor = editorElement.value;
    if (!editor) return;
    const caret = Math.max(0, sql.value.lastIndexOf(";"));
    editor.focus();
    editor.setSelectionRange(caret, caret);
  });
}

function updateSuggestions() {
  const editor = editorElement.value;
  if (!editor) return;
  suggestions.value = sqlSuggestions(sql.value, editor.selectionStart, tables.value, columns.value);
}

function acceptSuggestion(suggestion: SqlSuggestion) {
  const editor = editorElement.value;
  if (!editor) return;
  const next = applySqlSuggestion(sql.value, editor.selectionStart, suggestion.label);
  sql.value = next.sql;
  suggestions.value = [];
  requestAnimationFrame(() => {
    editor.focus();
    editor.setSelectionRange(next.caret, next.caret);
  });
}

function formatCurrentSql() {
  sql.value = formatSql(sql.value);
  suggestions.value = [];
}

async function saveCurrentSql() {
  if (!connectionId.value || !database.value || !sql.value.trim() || !saveName.value.trim()) return;
  saving.value = true;
  error.value = "";
  saveStatus.value = "";
  const wasUpdate = Boolean(savedSqlId.value);
  try {
    const saved = await apiPostJson<SavedSqlFile>(
      props.baseUrl,
      "/api/mobile/saved-sql",
      props.token,
      {
        id: savedSqlId.value || undefined,
        connectionId: connectionId.value,
        folderId: savedSqlFolderId.value,
        database: database.value,
        schema: schema.value || null,
        name: saveName.value.trim(),
        sql: sql.value,
      },
      { timeoutMs: 8_000 },
    );
    savedSqlId.value = saved.id;
    savedSqlFolderId.value = saved.folderId;
    saveName.value = saved.name.replace(/\.sql$/i, "");
    saveStatus.value = wasUpdate ? "已覆盖更新并同步到 DBX Server" : "已同步到 DBX Server";
  } catch (reason) {
    fail(reason);
  } finally {
    saving.value = false;
  }
}

function saveAsCopy() {
  savedSqlId.value = "";
  savedSqlFolderId.value = null;
  saveName.value = saveName.value.replace(/\.sql$/i, "");
  saveStatus.value = "已切换为另存新文件";
}

watch(
  () => props.draft,
  async (draft) => {
    if (!draft) return;
    sql.value = draft.sql;
    connectionId.value = draft.connectionId;
    savedSqlId.value = draft.savedSqlId ?? "";
    savedSqlFolderId.value = draft.savedSqlFolderId ?? null;
    saveName.value = draft.savedSqlName?.replace(/\.sql$/i, "") ?? "";
    saveStatus.value = "";
    emit("draftConsumed");
    await selectConnection(draft.database, draft.schema);
  },
  { immediate: true },
);

async function executePage(offset: number) {
  if (!connectionId.value || !database.value || !sql.value.trim()) return;
  const requestId = ++queryRequestId;
  queryController?.abort();
  const controller = new AbortController();
  const executionId = createExecutionId();
  queryController = controller;
  activeExecutionId = executionId;
  executing.value = true;
  error.value = "";
  if (offset === 0) result.value = null;
  exportStatus.value = "";
  selectedCell.value = null;
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
        executionId,
        offset,
        pageSize: SERVER_PAGE_SIZE,
      },
      { signal: controller.signal, timeoutMs: 45_000 },
    );
    if (requestId === queryRequestId) {
      result.value = response;
      resultOffset.value = offset;
      if (offset === 0) columnWidths.value = {};
    }
  } catch (reason) {
    if (requestId === queryRequestId) {
      if (reason instanceof DOMException && reason.name === "AbortError") error.value = "查询已取消或网络请求超时";
      else fail(reason);
      void requestServerCancellation(executionId, true).catch(() => undefined);
    }
  } finally {
    if (requestId === queryRequestId) {
      executing.value = false;
      queryController = null;
      activeExecutionId = null;
    }
  }
}

function execute() {
  return executePage(0);
}

async function cancelQuery() {
  const executionId = activeExecutionId;
  if (!executionId || cancelling.value) return;
  cancelling.value = true;
  error.value = "";
  try {
    await requestServerCancellation(executionId);
    error.value = "查询已取消，服务端正在清理数据库任务";
  } catch (reason) {
    error.value = reason instanceof Error ? `取消请求未确认：${reason.message}` : "取消请求未确认";
  } finally {
    queryController?.abort();
    cancelling.value = false;
  }
}

async function shareResult() {
  if (!result.value || result.value.columns.length === 0 || exporting.value) return;
  const requestId = ++exportRequestId;
  const requestedResult = result.value;
  const requestedDatabase = database.value;
  const requestedSchema = schema.value || null;
  exporting.value = true;
  exportStatus.value = "";
  error.value = "";
  try {
    const receipt = await exportQueryResult(
      {
        result: requestedResult,
        database: requestedDatabase,
        schema: requestedSchema,
      },
      exportFormat.value,
    );
    if (requestId === exportRequestId) {
      exportStatus.value =
        receipt.delivery === "share"
          ? `已打开分享面板 · ${receipt.filename}`
          : `${receipt.format.toUpperCase()} 已下载 · ${receipt.filename}`;
    }
  } catch (reason) {
    if (requestId === exportRequestId) {
      error.value = reason instanceof Error ? `导出失败：${reason.message}` : "导出失败，请重试";
    }
  } finally {
    if (requestId === exportRequestId) exporting.value = false;
  }
}

onBeforeUnmount(() => {
  connectionRequestId++;
  schemaRequestId++;
  tableRequestId++;
  if (tableSearchTimer) clearTimeout(tableSearchTimer);
  invalidateQuery();
});

function displayValue(value: unknown) {
  if (value === null) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function cellText(value: unknown) {
  if (value === null) return "NULL";
  if (value === undefined) return "";
  return typeof value === "object" ? JSON.stringify(value, null, 2) : String(value);
}

async function copyText(value: string, success: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
    } else {
      const input = document.createElement("textarea");
      input.value = value;
      input.style.position = "fixed";
      input.style.opacity = "0";
      document.body.append(input);
      input.select();
      if (!document.execCommand("copy")) throw new Error("系统拒绝了复制操作");
      input.remove();
    }
    exportStatus.value = success;
  } catch (reason) {
    error.value = reason instanceof Error ? `复制失败：${reason.message}` : "复制失败";
  }
}

function openCell(rowIndex: number, columnIndex: number, value: unknown) {
  selectedCell.value = {
    rowIndex: resultOffset.value + rowIndex,
    pageRowIndex: rowIndex,
    columnIndex,
    value,
  };
}

function copySelectedCell() {
  if (!selectedCell.value) return;
  return copyText(cellText(selectedCell.value.value), "单元格已复制");
}

function copySelectedRow() {
  if (!selectedCell.value || !result.value) return;
  const row = result.value.rows[selectedCell.value.pageRowIndex] ?? [];
  return copyText(row.map((value) => cellText(value).replace(/\r?\n/g, " ")).join("\t"), "当前行已复制");
}

function adjustColumn(index: number, delta: number) {
  const current = columnWidths.value[index] ?? 160;
  columnWidths.value = { ...columnWidths.value, [index]: Math.min(480, Math.max(88, current + delta)) };
}

function autoFitColumns() {
  if (!result.value) return;
  columnWidths.value = Object.fromEntries(
    result.value.columns.map((column, index) => {
      const widest = Math.max(
        column.length,
        ...result.value!.rows.map((row) => displayValue(row[index]).length),
      );
      return [index, Math.min(360, Math.max(88, widest * 7 + 28))];
    }),
  );
}
</script>

<template>
  <div class="query-workbench">
    <div class="context-grid">
      <label><span>连接</span><select v-model="connectionId" @change="selectConnection()"><option value="">选择连接</option><option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <label><span>数据库</span><select v-model="database" :disabled="!connectionId || loadingContext" @change="selectDatabase()"><option value="">选择数据库</option><option v-for="item in databases" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
      <label><span>Schema</span><select v-model="schema" :disabled="schemas.length === 0" @change="selectSchema"><option value="">默认</option><option v-for="item in schemas" :key="item" :value="item">{{ item }}</option></select></label>
    </div>
    <div class="editor">
      <div class="editor-heading">
        <span>SERVER-ENFORCED READ ONLY</span>
        <small>单条 SQL · 服务端 50 行/页 · 2 MiB · 30 秒</small>
        <button type="button" aria-label="格式化 SQL" @click="formatCurrentSql">FORMAT</button>
      </div>
      <div class="editor-input">
        <textarea
          ref="editorElement"
          v-model="sql"
          spellcheck="false"
          autocapitalize="none"
          placeholder="SELECT * FROM table_name;"
          @click="updateSuggestions"
          @input="updateSuggestions"
          @keyup="updateSuggestions"
        ></textarea>
        <div v-if="suggestions.length" class="suggestion-list" role="listbox" aria-label="SQL 自动补全">
          <button
            v-for="suggestion in suggestions"
            :key="`${suggestion.kind}:${suggestion.label}`"
            type="button"
            role="option"
            @mousedown.prevent="acceptSuggestion(suggestion)"
          >
            <i :data-kind="suggestion.kind">{{ suggestion.kind.slice(0, 2).toUpperCase() }}</i>
            <strong>{{ suggestion.label }}</strong>
            <small>{{ suggestion.detail }}</small>
          </button>
        </div>
      </div>
      <div class="query-actions">
        <button :disabled="executing || !database || !sql.trim()" type="button" @click="execute">
          {{ executing ? "正在执行…" : "执行查询  ▶" }}
        </button>
        <button
          v-if="executing"
          class="cancel-action"
          :disabled="cancelling"
          type="button"
          @click="cancelQuery"
        >
          {{ cancelling ? "正在取消…" : "取消查询  ■" }}
        </button>
      </div>
      <form class="save-sql" @submit.prevent="saveCurrentSql">
        <input v-model="saveName" maxlength="120" :placeholder="savedSqlId ? '更新已保存 SQL 的名称' : '给当前 SQL 命名'" />
        <button v-if="savedSqlId" :disabled="saving" type="button" @click="saveAsCopy">另存</button>
        <button :disabled="saving || !database || !sql.trim() || !saveName.trim()" type="submit">
          {{ saving ? "保存中" : savedSqlId ? "覆盖更新" : "保存 SQL" }}
        </button>
      </form>
      <p v-if="saveStatus" class="save-status">{{ saveStatus }}</p>
    </div>
    <section v-if="database" class="query-builder">
      <header>
        <div><span>SCHEMA ASSIST</span><strong>从元数据生成查询</strong></div>
        <small>{{ loadingMetadata || searchingTables ? "SYNCING…" : `${visibleTables.length}${hasMoreTables ? "+" : ""} TABLES` }}</small>
      </header>
      <div class="table-search">
        <input
          v-model="tableSearch"
          type="search"
          autocomplete="off"
          placeholder="搜索全部表与视图"
          aria-label="搜索表与视图"
          @input="scheduleTableSearch"
        />
        <button v-if="tableSearch" type="button" aria-label="清除表搜索" @click="clearTableSearch">×</button>
      </div>
      <div v-if="visibleTables.length" class="table-strip">
        <article v-for="table in visibleTables" :key="`${table.parent_schema ?? ''}:${table.name}`" :class="{ active: selectedTable?.name === table.name }">
          <button type="button" @click="openTable(table)">
            <small>{{ table.table_type }}</small><strong>{{ table.name }}</strong>
          </button>
          <button type="button" aria-label="生成只读查询" @click="generateTableQuery(table)">SELECT ↗</button>
        </article>
      </div>
      <p v-else-if="!loadingMetadata && !searchingTables">
        {{ tableSearch.trim() ? "没有匹配的表或视图。" : "当前范围没有可见表或视图。" }}
      </p>
      <button
        v-if="hasMoreTables"
        class="load-more-tables"
        :disabled="loadingMoreTables"
        type="button"
        @click="loadMoreTables"
      >{{ loadingMoreTables ? "正在加载…" : `继续加载（已显示 ${visibleTables.length}）` }}</button>
      <div v-if="selectedTable" class="field-builder">
        <div><span>FIELDS / {{ selectedTable.name }}</span><small>点击字段追加 WHERE / AND 条件</small></div>
        <button
          v-for="column in columns"
          :key="column.name"
          type="button"
          :title="`${column.name} · ${column.data_type}`"
          @click="addColumnCondition(column)"
        >
          <strong>{{ column.name }}</strong><small>{{ column.data_type }}</small><b>＋</b>
        </button>
      </div>
    </section>
    <div v-if="error" class="query-error"><b>!</b><span>{{ error }}</span></div>
    <section v-if="result" class="result-panel">
      <header>
        <div class="result-metrics">
          <span>
            {{ result.rows.length ? resultOffset + 1 : 0 }}–{{ resultOffset + result.rows.length }} ROWS
            · {{ result.execution_time_ms }} MS
          </span>
          <em v-if="result.has_more">MORE</em>
        </div>
        <div v-if="result.columns.length" class="result-actions">
          <button type="button" :disabled="exporting" @click="autoFitColumns">AUTO WIDTH</button>
          <select v-model="exportFormat" aria-label="查询结果导出格式">
            <option value="csv">CSV</option>
            <option value="json">JSON</option>
            <option value="markdown">MARKDOWN</option>
            <option value="xlsx">EXCEL XLSX</option>
          </select>
          <button
            class="export-action"
            :disabled="exporting"
            type="button"
            :aria-label="`导出并分享 ${exportFormat.toUpperCase()} 查询结果`"
            @click="shareResult"
          >
            {{ exporting ? "PREPARING…" : "EXPORT ↗" }}
          </button>
        </div>
      </header>
      <p v-if="exportStatus" class="export-status" aria-live="polite">{{ exportStatus }}</p>
      <p v-if="result.columns.length" class="result-hint">点击单元格查看完整内容并复制；表头 − / + 可调整列宽。导出范围为当前服务端页。</p>
      <div v-if="result.columns.length" class="result-scroll">
        <table>
          <colgroup>
            <col
              v-for="(_, index) in result.columns"
              :key="index"
              :style="{ width: `${columnWidths[index] ?? 160}px` }"
            />
          </colgroup>
          <thead>
            <tr>
              <th v-for="(column, index) in result.columns" :key="`${column}:${index}`">
                <div>
                  <span>{{ column }}</span>
                  <span class="width-controls">
                    <button type="button" :aria-label="`缩小 ${column} 列`" @click="adjustColumn(index, -32)">−</button>
                    <button type="button" :aria-label="`加宽 ${column} 列`" @click="adjustColumn(index, 32)">＋</button>
                  </span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in result.rows" :key="resultOffset + rowIndex">
              <td
                v-for="(_, index) in result.columns"
                :key="index"
                :class="{ null: row[index] === null }"
              >
                <button type="button" @click="openCell(rowIndex, index, row[index])">
                  {{ displayValue(row[index]) }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else>执行成功，影响 {{ result.affected_rows }} 行。</p>
      <footer v-if="result.columns.length && (resultOffset > 0 || result.has_more)">
        <button :disabled="executing || resultOffset === 0" @click="executePage(Math.max(0, resultOffset - SERVER_PAGE_SIZE))">←</button>
        <span>SERVER PAGE {{ resultPage }}</span>
        <button :disabled="executing || !result.has_more" @click="executePage(resultOffset + SERVER_PAGE_SIZE)">→</button>
      </footer>
    </section>
    <div
      v-if="selectedCell && result"
      class="cell-detail-backdrop"
      role="presentation"
      @click.self="selectedCell = null"
    >
      <section class="cell-detail" role="dialog" aria-modal="true" aria-labelledby="cell-detail-title">
        <header>
          <div>
            <span>CELL {{ selectedCell.rowIndex + 1 }} / {{ selectedCell.columnIndex + 1 }}</span>
            <strong id="cell-detail-title">{{ result.columns[selectedCell.columnIndex] }}</strong>
          </div>
          <button type="button" aria-label="关闭单元格详情" @click="selectedCell = null">×</button>
        </header>
        <pre :class="{ null: selectedCell.value === null }">{{ cellText(selectedCell.value) }}</pre>
        <footer>
          <button type="button" @click="copySelectedCell">复制单元格</button>
          <button type="button" @click="copySelectedRow">复制整行 TSV</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.query-workbench { display: grid; gap: 12px; margin-top: 16px; }
.context-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.context-grid label:last-child { grid-column: 1 / -1; }
label span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 8px; letter-spacing: .12em; }
select { width: 100%; height: 42px; border: 1px solid var(--line); border-radius: 0; background: var(--panel); padding: 0 9px; color: var(--ink); font: inherit; font-size: 10px; }
.editor { border: 1px solid var(--line); border-top: 2px solid var(--acid); background: #080a09; }
.editor-heading { display: grid; min-height: 38px; grid-template-columns: 1fr auto auto; align-items: center; gap: 9px; padding-left: 12px; color: var(--acid); font-size: 8px; letter-spacing: .12em; }
.editor-heading small { color: var(--muted); font-size: 7px; letter-spacing: 0; }
.editor-heading button { align-self: stretch; border: 0; border-left: 1px solid var(--line); background: rgba(199,255,61,.08); padding: 0 10px; color: var(--acid); font: inherit; font-size: 7px; letter-spacing: .1em; }
.editor-input { position: relative; border-top: 1px solid var(--line); }
textarea { display: block; width: 100%; min-height: 180px; resize: vertical; border: 0; outline: none; background: transparent; padding: 15px; color: #e7f5d1; font: 12px/1.65 "Azeret Mono Variable", monospace; }
.suggestion-list { position: absolute; z-index: 4; right: 8px; bottom: 8px; left: 8px; overflow: auto; max-height: 190px; border: 1px solid rgba(199,255,61,.45); background: rgba(12,15,12,.98); box-shadow: 0 -16px 35px rgba(0,0,0,.55); }
.suggestion-list button { display: grid; width: 100%; min-height: 40px; grid-template-columns: 25px minmax(0,1fr) auto; align-items: center; gap: 8px; border: 0; border-bottom: 1px solid var(--line); background: transparent; padding: 6px 9px; color: var(--ink); text-align: left; }
.suggestion-list i { display: grid; width: 24px; height: 24px; place-items: center; border: 1px solid var(--line); color: var(--muted); font-size: 6px; font-style: normal; }
.suggestion-list i[data-kind="column"] { border-color: rgba(199,255,61,.4); color: var(--acid); }
.suggestion-list strong { overflow: hidden; font-size: 10px; text-overflow: ellipsis; }
.suggestion-list small { color: var(--muted); font-size: 7px; }
.query-actions { display: grid; grid-template-columns: 1fr; border-top: 1px solid var(--line); }
.query-actions:has(.cancel-action) { grid-template-columns: 1fr 42%; }
.query-actions button { min-height: 48px; border: 0; background: var(--acid); color: #10130c; font: inherit; font-weight: 760; }
.query-actions .cancel-action { border-left: 1px solid rgba(255,101,95,.45); background: rgba(255,101,95,.13); color: var(--danger); }
.save-sql { display: grid; grid-template-columns: 1fr auto auto; border-top: 1px solid var(--line); }
.save-sql input { min-width: 0; border: 0; background: #101310; padding: 0 12px; color: var(--ink); font: inherit; font-size: 10px; outline: none; }
.save-sql button { width: auto; min-width: 92px; min-height: 46px; border: 0; border-left: 1px solid var(--line); background: transparent; color: var(--acid); }
.save-status { margin: 0; border-top: 1px solid var(--line); padding: 8px 12px; color: var(--acid); font-size: 8px; }
button:disabled { opacity: .45; }
.query-builder { overflow: hidden; border: 1px solid var(--line); background: linear-gradient(145deg, rgba(199,255,61,.045), transparent 52%), var(--panel); }
.query-builder > header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--line); padding: 11px 12px; }
.query-builder > header span { display: block; color: var(--acid); font-size: 7px; letter-spacing: .14em; }
.query-builder > header strong { display: block; margin-top: 4px; font-size: 11px; }
.query-builder > header small { color: var(--muted); font-size: 7px; }
.query-builder > p { margin: 0; padding: 18px 12px; color: var(--muted); font-size: 9px; }
.table-search { display: grid; grid-template-columns: 1fr auto; border-bottom: 1px solid var(--line); background: #0c0f0d; }
.table-search input { min-width: 0; min-height: 40px; border: 0; outline: none; background: transparent; padding: 0 11px; color: var(--ink); font: inherit; font-size: 9px; }
.table-search button { width: 40px; border: 0; border-left: 1px solid var(--line); background: transparent; color: var(--muted); font: inherit; font-size: 16px; }
.table-strip { display: flex; overflow-x: auto; gap: 7px; padding: 10px; scroll-snap-type: x proximity; }
.table-strip article { flex: 0 0 156px; scroll-snap-align: start; border: 1px solid var(--line); background: #0c0f0d; }
.table-strip article.active { border-color: rgba(199,255,61,.52); }
.table-strip button { display: block; width: 100%; border: 0; background: transparent; color: var(--ink); text-align: left; }
.table-strip article > button:first-child { min-height: 61px; padding: 10px; }
.table-strip article > button:last-child { min-height: 31px; border-top: 1px solid var(--line); padding: 0 10px; color: var(--acid); font-size: 7px; letter-spacing: .1em; }
.table-strip small { display: block; overflow: hidden; color: var(--muted); font-size: 6px; text-overflow: ellipsis; white-space: nowrap; }
.table-strip strong { display: block; overflow: hidden; margin-top: 7px; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.load-more-tables { width: 100%; min-height: 40px; border: 0; border-top: 1px solid var(--line); background: rgba(199,255,61,.04); color: var(--acid); font: inherit; font-size: 8px; letter-spacing: .08em; }
.field-builder { display: grid; max-height: 230px; grid-template-columns: 1fr 1fr; overflow-y: auto; border-top: 1px solid var(--line); }
.field-builder > div { grid-column: 1 / -1; padding: 10px 12px; }
.field-builder > div span { display: block; color: var(--acid); font-size: 7px; letter-spacing: .12em; }
.field-builder > div small { display: block; margin-top: 4px; color: var(--muted); font-size: 7px; }
.field-builder > button { position: relative; min-width: 0; min-height: 52px; border: 0; border-top: 1px solid var(--line); border-right: 1px solid var(--line); background: rgba(0,0,0,.12); padding: 8px 28px 8px 10px; color: var(--ink); text-align: left; }
.field-builder > button strong, .field-builder > button small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.field-builder > button strong { font-size: 9px; }
.field-builder > button small { margin-top: 5px; color: var(--muted); font-size: 7px; }
.field-builder > button b { position: absolute; top: 17px; right: 10px; color: var(--acid); font-size: 14px; font-weight: 400; }
.query-error { display: flex; gap: 10px; border: 1px solid rgba(255,101,95,.35); padding: 13px; color: var(--danger); font-size: 10px; line-height: 1.5; }
.result-panel { border: 1px solid var(--line); background: var(--panel); }
.result-panel > header { display: grid; min-height: 46px; grid-template-columns: minmax(140px, 1fr) auto; border-bottom: 1px solid var(--line); color: var(--acid); font-size: 8px; letter-spacing: .1em; }
.result-metrics { display: flex; min-width: 0; align-items: center; gap: 8px; padding: 11px; }
.result-panel em { color: var(--amber); font-style: normal; }
.result-actions { display: flex; min-width: 0; align-items: stretch; }
.result-actions > button, .result-actions > select { min-height: 44px; border: 0; border-left: 1px solid var(--line); background: transparent; padding: 0 9px; color: var(--acid); font: inherit; font-size: 7px; letter-spacing: .06em; }
.result-actions > select { width: 86px; border-radius: 0; }
.result-actions > button:first-child { color: var(--muted); }
.result-actions .export-action { background: linear-gradient(135deg, rgba(199,255,61,.16), rgba(199,255,61,.04)); font-weight: 720; }
.export-action:active { background: var(--acid); color: #10130c; }
.export-status { margin: 0; border-bottom: 1px solid var(--line); padding: 9px 11px; color: var(--muted); font-size: 8px; line-height: 1.5; overflow-wrap: anywhere; }
.result-panel .result-hint { margin: 0; border-bottom: 1px solid var(--line); padding: 8px 11px; color: var(--faint); font-size: 7px; line-height: 1.55; }
.result-scroll { overflow: auto; max-height: 48vh; }
table { table-layout: fixed; border-collapse: collapse; min-width: 100%; width: max-content; font-size: 9px; white-space: nowrap; }
th, td { overflow: hidden; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 0; text-align: left; text-overflow: ellipsis; }
th { position: sticky; z-index: 2; top: 0; background: #171b18; color: var(--acid); }
th > div { display: flex; min-height: 39px; align-items: center; justify-content: space-between; gap: 8px; padding-left: 9px; }
th > div > span:first-child { overflow: hidden; text-overflow: ellipsis; }
.width-controls { display: flex; align-self: stretch; }
.width-controls button { width: 27px; border: 0; border-left: 1px solid var(--line); background: rgba(255,255,255,.025); color: var(--muted); font: inherit; }
td > button { display: block; overflow: hidden; width: 100%; min-height: 38px; border: 0; background: transparent; padding: 9px; color: var(--ink); font: inherit; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
td > button:active { background: rgba(199,255,61,.1); color: var(--acid); }
td.null { color: var(--faint); font-style: italic; }
.result-panel > p { padding: 20px; color: var(--muted); font-size: 10px; }
.result-panel footer { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 9px; }
.result-panel footer button { width: 34px; height: 30px; border: 1px solid var(--line); background: transparent; color: var(--acid); }
.result-panel footer span { font-size: 9px; }
.cell-detail-backdrop { position: fixed; z-index: 30; inset: 0; display: grid; align-items: end; background: rgba(0,0,0,.72); backdrop-filter: blur(4px); }
.cell-detail { max-height: min(72vh, 560px); overflow: hidden; border: 1px solid rgba(199,255,61,.45); border-bottom: 0; background: #0b0e0c; box-shadow: 0 -24px 70px rgba(0,0,0,.68); }
.cell-detail > header { display: flex; min-height: 58px; align-items: stretch; justify-content: space-between; border-bottom: 1px solid var(--line); padding-left: 14px; }
.cell-detail > header div { display: grid; align-content: center; gap: 5px; min-width: 0; }
.cell-detail > header span { color: var(--muted); font-size: 7px; letter-spacing: .14em; }
.cell-detail > header strong { overflow: hidden; color: var(--acid); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.cell-detail > header button { width: 58px; border: 0; border-left: 1px solid var(--line); background: transparent; color: var(--muted); font-size: 24px; }
.cell-detail pre { overflow: auto; max-height: calc(min(72vh, 560px) - 116px); min-height: 120px; margin: 0; padding: 16px; color: var(--ink); font: 11px/1.65 "Azeret Mono Variable", monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.cell-detail pre.null { color: var(--faint); font-style: italic; }
.cell-detail > footer { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--line); }
.cell-detail > footer button { min-height: 48px; border: 0; border-right: 1px solid var(--line); background: rgba(199,255,61,.07); color: var(--acid); font: inherit; font-size: 9px; }
@media (max-width: 560px) {
  .result-panel > header { grid-template-columns: 1fr; }
  .result-actions { border-top: 1px solid var(--line); }
  .result-actions > button, .result-actions > select { flex: 1 1 auto; border-left: 0; border-right: 1px solid var(--line); }
}
</style>
