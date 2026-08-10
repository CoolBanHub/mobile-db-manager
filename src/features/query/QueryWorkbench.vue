<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import QueryResultPanel from "./QueryResultPanel.vue";
import { isMobileSqlDatabase } from "@/lib/databaseCapabilities";
import { loadDirectMetadata } from "@/lib/direct/metadata";
import { loadDirectMongoCollections, loadDirectMongoDatabases } from "@/lib/direct/mongo";
import { cancelDirectQuery, executeDirectQuery, explainDirectQuery } from "@/lib/direct/query";
import { saveDirectSavedSql } from "@/lib/direct/savedSql";
import type { ColumnInfo, DatabaseInfo, MobileConnectionSummary, MobileQueryDraft, QueryResult, SavedSqlFile, TableInfo } from "@/lib/mobileTypes";
import { exportQueryResult, type QueryExportFormat } from "@/lib/queryExport";
import { applySqlSuggestion, buildColumnCondition, buildTableSelect, editorKeywords, formatSql, mergeTableMetadata, sqlSuggestions, type SqlSuggestion } from "@/lib/sqlEditor";
import { parseSqlParameterJson, resolveSqlParameters } from "@/lib/sqlParameters";

type ExecutionMode = "safe" | "advanced";

const props = defineProps<{
  connections: MobileConnectionSummary[];
  draft?: MobileQueryDraft | null;
}>();
const emit = defineEmits<{ back: []; draftConsumed: []; more: [] }>();
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
const queryTabs = ref([{ id: 1, title: "查询 1", sql: "SELECT 1 AS result;" }]);
const activeQueryTabId = ref(1);
let nextQueryTabId = 2;
const sql = computed({
  get: () => queryTabs.value.find((tab) => tab.id === activeQueryTabId.value)?.sql ?? "",
  set: (value: string) => {
    const tab = queryTabs.value.find((item) => item.id === activeQueryTabId.value);
    if (tab) tab.sql = value;
  },
});
const executionMode = ref<ExecutionMode>("safe");
const parameterJson = ref("");
const showParameters = ref(false);
const confirmedWrite = ref(false);
const productionConfirmation = ref("");
const explainResult = ref("");
const explaining = ref(false);
const showChart = ref(false);
const cellEditValue = ref("");
const pendingCellEdits = ref<Array<{ rowIndex: number; columnIndex: number; value: string }>>([]);
const editorElement = ref<HTMLTextAreaElement | null>(null);
const suggestions = ref<SqlSuggestion[]>([]);
const selectedSuggestionIndex = ref(0);
const suggestionPosition = ref({ top: "58px", left: "48px" });
const columnsByTable = ref<Record<string, ColumnInfo[]>>({});
const loadingSuggestionTables = new Set<string>();
const runMenuOpen = ref(false);
const showSavePanel = ref(false);
const activeResultTab = ref<"result" | "messages" | "plan" | "schema">("result");
// 默认给结果区预留一行表头与数据的空间；仍可通过中间拖拽条扩大编辑器。
const editorHeight = ref(150);
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
const QUERY_PAGE_SIZE = 100;
const TABLE_PAGE_SIZE = 100;
// 每类异步请求使用递增版本号，快速切换连接/Schema 时丢弃过期响应，避免界面串数据。
let connectionRequestId = 0;
let schemaRequestId = 0;
let tableRequestId = 0;
let queryRequestId = 0;
let exportRequestId = 0;
let activeExecutionId: string | null = null;
let lastExecutedSql = "";
let tableSearchTimer: ReturnType<typeof setTimeout> | null = null;
let stopEditorResize: (() => void) | null = null;

const selectedConnection = computed(() => props.connections.find((item) => item.id === connectionId.value));
const selectedDatabaseType = computed(() => selectedConnection.value?.dbType ?? "postgres");
const queryExecutionSupported = computed(() => Boolean(selectedConnection.value && isMobileSqlDatabase(selectedConnection.value.dbType)));
const schemaContextLabel = computed(() => (selectedDatabaseType.value === "redis" ? "Redis 命令" : selectedDatabaseType.value === "mongodb" ? "MongoDB 集合" : "默认 Schema"));
const editorPlaceholder = computed(() => {
  if (selectedDatabaseType.value === "redis") return "GET user:1001";
  if (selectedDatabaseType.value === "mongodb") return "db.users.find({ status: 'active' })";
  return "SELECT * FROM table_name;";
});
const resultPage = computed(() => Math.floor(resultOffset.value / QUERY_PAGE_SIZE) + 1);
const advancedMode = computed(() => executionMode.value !== "safe");
const writeAllowed = computed(() => selectedConnection.value && !selectedConnection.value.readOnly);
const productionConfirmed = computed(() => !selectedConnection.value?.isProduction || productionConfirmation.value.trim() === selectedConnection.value.name);
const advancedExecutionReady = computed(() => !advancedMode.value || (!!writeAllowed.value && confirmedWrite.value && productionConfirmed.value));
const chartRows = computed(() => {
  if (!result.value || result.value.columns.length < 2) return [];
  const numericColumn = result.value.columns.findIndex((_, index) => result.value!.rows.some((row) => typeof row[index] === "number"));
  if (numericColumn < 0) return [];
  const values = result.value.rows
    .slice(0, 20)
    .map((row) => ({ label: displayValue(row[0]), value: Number(row[numericColumn]) }))
    .filter((item) => Number.isFinite(item.value));
  const maximum = Math.max(1, ...values.map((item) => Math.abs(item.value)));
  return values.map((item) => ({ ...item, width: (Math.abs(item.value) / maximum) * 100 }));
});
const tables = computed(() => mergeTableMetadata(browsedTables.value, tableSearchResults.value));
const visibleTables = computed(() => (tableSearch.value.trim() ? tableSearchResults.value : browsedTables.value));
const hasMoreTables = computed(() => (tableSearch.value.trim() ? searchResultsHaveMore.value : browsedTablesHaveMore.value));
const usesTableContextPicker = computed(() => selectedDatabaseType.value === "mysql" || selectedDatabaseType.value === "mongodb");
const resultStatusText = computed(() => {
  if (!result.value) return "";
  if (result.value.columns.length) return `返回 ${result.value.rows.length}${result.value.has_more ? "+" : ""} 行`;
  return `影响 ${result.value.affected_rows} 行`;
});
const suggestionColumns = computed(() => {
  const merged = [...columns.value, ...Object.values(columnsByTable.value).flat()];
  return merged.filter((column, index) => merged.findIndex((item) => item.name === column.name && item.data_type === column.data_type) === index);
});
const editorLineCount = computed(() => Math.max(1, sql.value.split("\n").length));
const highlightedSql = computed(() => highlightSql(sql.value));

function fail(reason: unknown) {
  error.value = reason instanceof Error ? reason.message : "请求失败";
}

function createExecutionId() {
  return globalThis.crypto?.randomUUID?.() ?? `mobile-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function invalidateQuery() {
  // 上下文变化时同时作废查询、导出和原生 Statement，旧结果不得覆盖新页面。
  queryRequestId++;
  exportRequestId++;
  if (activeExecutionId) void cancelDirectQuery(activeExecutionId).catch(() => undefined);
  activeExecutionId = null;
  executing.value = false;
  cancelling.value = false;
  exporting.value = false;
  result.value = null;
  explainResult.value = "";
  exportStatus.value = "";
  resultOffset.value = 0;
  columnWidths.value = {};
  selectedCell.value = null;
}

function executableSql(source = sql.value) {
  return resolveSqlParameters(source, parseSqlParameterJson(parameterJson.value));
}

async function executeAdvanced(source = sql.value) {
  if (!selectedConnection.value || selectedConnection.value.readOnly) {
    error.value = "只读连接禁止高级 SQL，请切换到安全模式执行查询";
    return;
  }
  if (!confirmedWrite.value) {
    error.value = "高级 SQL 可能产生副作用，请先确认本次执行";
    return;
  }
  if (!productionConfirmed.value) {
    error.value = "生产连接名称不匹配，高级 SQL 未执行";
    return;
  }
  const requestId = ++queryRequestId;
  const executionId = createExecutionId();
  activeExecutionId = executionId;
  executing.value = true;
  error.value = "";
  result.value = null;
  try {
    // 参数先在前端解析；原生侧把所有高级 SQL 都按可写请求重新执行安全校验。
    const resolvedSql = executableSql(source);
    const response = await executeDirectQuery(
      {
        connectionId: connectionId.value,
        database: database.value,
        schema: schema.value || null,
        sql: resolvedSql,
        executionId,
        confirmedWrite: confirmedWrite.value,
        productionConfirmation: productionConfirmation.value || null,
      },
      false,
    );
    if (requestId !== queryRequestId) return;
    result.value = response;
    resultOffset.value = 0;
  } catch (reason) {
    if (requestId === queryRequestId) fail(reason);
  } finally {
    if (requestId === queryRequestId) {
      executing.value = false;
      activeExecutionId = null;
      confirmedWrite.value = false;
      productionConfirmation.value = "";
    }
  }
}

function toggleExecutionMode() {
  executionMode.value = advancedMode.value ? "safe" : "advanced";
  confirmedWrite.value = false;
  productionConfirmation.value = "";
  error.value = "";
}

async function explainQuery() {
  explaining.value = true;
  error.value = "";
  explainResult.value = "";
  try {
    explainResult.value = await explainDirectQuery({
      connectionId: connectionId.value,
      database: database.value,
      schema: schema.value || null,
      sql: executableSql(),
      mode: "explain",
    });
    activeResultTab.value = "plan";
  } catch (reason) {
    fail(reason);
  } finally {
    explaining.value = false;
  }
}

async function openSqlFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (file.size > 1024 * 1024) {
    error.value = "移动端直接打开的 SQL 文件不能超过 1 MiB";
    input.value = "";
    return;
  }
  try {
    sql.value = await file.text();
    executionMode.value = "advanced";
  } catch (reason) {
    error.value = reason instanceof Error ? `读取 SQL 文件失败：${reason.message}` : "读取 SQL 文件失败";
  } finally {
    input.value = "";
  }
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
  columnsByTable.value = {};
  loadingMetadata.value = false;
  loadingMoreTables.value = false;
  searchingTables.value = false;
  columns.value = [];
  selectedTable.value = null;
  result.value = null;
  if (!requestedConnectionId) return;
  if (sql.value === "SELECT 1;" || sql.value === "SELECT 1 AS result;") {
    sql.value = selectedDatabaseType.value === "redis" ? "" : selectedDatabaseType.value === "mongodb" ? "db." : sql.value;
  }
  loadingContext.value = true;
  error.value = "";
  try {
    // Redis 没有关系型元数据；MongoDB 将 database/collection 映射到工作台上下文。
    if (selectedDatabaseType.value === "redis") {
      const redisDatabase = selectedConnection.value?.database || "0";
      databases.value = [{ name: redisDatabase }];
      database.value = redisDatabase;
      return;
    }
    if (selectedDatabaseType.value === "mongodb") {
      const names = await loadDirectMongoDatabases(requestedConnectionId);
      if (requestId !== connectionRequestId || connectionId.value !== requestedConnectionId) return;
      databases.value = names.map((name) => ({ name }));
      database.value = (preferredDatabase && names.includes(preferredDatabase) ? preferredDatabase : "") || selectedConnection.value?.database || names[0] || "";
      if (database.value) await selectDatabase();
      return;
    }
    const response = await loadDirectMetadata<DatabaseInfo[]>("databases", {
      connectionId: requestedConnectionId,
    });
    if (requestId !== connectionRequestId || connectionId.value !== requestedConnectionId) return;
    databases.value = response;
    database.value = (preferredDatabase && response.some((item) => item.name === preferredDatabase) ? preferredDatabase : "") || selectedConnection.value?.database || response[0]?.name || "";
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
    if (selectedDatabaseType.value === "redis") return;
    if (selectedDatabaseType.value === "mongodb") {
      const collections = await loadDirectMongoCollections(requestedConnectionId, requestedDatabase);
      if (requestId !== schemaRequestId || connectionId.value !== requestedConnectionId || database.value !== requestedDatabase) return;
      browsedTables.value = collections.map((name) => ({
        name,
        table_type: "COLLECTION",
        comment: null,
        parent_schema: requestedDatabase,
        parent_name: null,
      }));
      browsedTablesHaveMore.value = false;
      return;
    }
    const response = await loadDirectMetadata<string[]>("schemas", {
      connectionId: requestedConnectionId,
      database: requestedDatabase,
    });
    if (requestId !== schemaRequestId || connectionId.value !== requestedConnectionId || database.value !== requestedDatabase) return;
    schemas.value = response;
    schema.value = preferredSchema !== undefined ? (preferredSchema && response.includes(preferredSchema) ? preferredSchema : "") : response[0] || "";
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
  columnsByTable.value = {};
  if (!connectionId.value || !database.value) {
    loadingMetadata.value = false;
    return;
  }
  if (!queryExecutionSupported.value) {
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
  return loadDirectMetadata<TableInfo[]>("tables", {
    connectionId: connectionId.value,
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
  if (!queryExecutionSupported.value) {
    tableSearchResults.value = browsedTables.value.filter((table) => table.name.toLocaleLowerCase().includes(filter.toLocaleLowerCase()));
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
  // 移动键盘输入采用短防抖，减少远程元数据查询，同时保持搜索反馈及时。
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
  if (!queryExecutionSupported.value) return;
  loadingMetadata.value = true;
  try {
    columns.value = await loadDirectMetadata<ColumnInfo[]>("columns", {
      connectionId: connectionId.value,
      database: database.value,
      schema: schema.value,
      table: table.name,
    });
    columnsByTable.value = { ...columnsByTable.value, [table.name.toLocaleLowerCase()]: columns.value };
  } catch (reason) {
    fail(reason);
  } finally {
    loadingMetadata.value = false;
  }
}

function selectContextTable(event: Event) {
  const name = (event.target as HTMLSelectElement).value;
  if (!name) {
    selectedTable.value = null;
    columns.value = [];
    return;
  }
  const table = tables.value.find((item) => item.name === name);
  if (table) void openTable(table);
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
  suggestions.value = sqlSuggestions(sql.value, editor.selectionStart, tables.value, suggestionColumns.value, selectedDatabaseType.value);
  selectedSuggestionIndex.value = 0;
  const beforeCaret = sql.value.slice(0, editor.selectionStart);
  const lines = beforeCaret.split("\n");
  const popupWidth = Math.min(230, Math.max(180, editor.clientWidth - 16));
  const maxPopupLeft = Math.max(8, editor.clientWidth - popupWidth - 8);
  suggestionPosition.value = {
    top: `${Math.min(20 + lines.length * 20, 210)}px`,
    left: `${Math.min(42 + (lines.at(-1)?.length ?? 0) * 7.2, maxPopupLeft)}px`,
  };
  void hydrateSuggestionContext(beforeCaret);
}

async function hydrateSuggestionContext(beforeCaret: string) {
  if (!queryExecutionSupported.value) return;
  const referenced = [...beforeCaret.matchAll(/\b(?:from|join)\s+([\w.[\]"`]+)/gi)].map(
    (match) =>
      match[1]
        .split(".")
        .at(-1)
        ?.replace(/\[|\]|"|`/g, "") ?? "",
  );
  const qualifier = beforeCaret.match(/([A-Za-z_][A-Za-z0-9_$]*)\.[A-Za-z0-9_$]*$/)?.[1];
  if (qualifier) referenced.push(qualifier);
  const targets = tables.value.filter((table) => referenced.some((name) => name.toLocaleLowerCase() === table.name.toLocaleLowerCase())).slice(0, 3);
  // 只预取 SQL 光标附近最多三张表的列，防止大型 schema 的补全请求失控。
  await Promise.all(
    targets.map(async (table) => {
      const key = table.name.toLocaleLowerCase();
      if (columnsByTable.value[key] || loadingSuggestionTables.has(key)) return;
      loadingSuggestionTables.add(key);
      try {
        const nextColumns = await loadDirectMetadata<ColumnInfo[]>("columns", {
          connectionId: connectionId.value,
          database: database.value,
          schema: schema.value,
          table: table.name,
        });
        columnsByTable.value = { ...columnsByTable.value, [key]: nextColumns };
        const editor = editorElement.value;
        if (editor) suggestions.value = sqlSuggestions(sql.value, editor.selectionStart, tables.value, suggestionColumns.value, selectedDatabaseType.value);
      } catch {
        // 联想元数据加载失败不应干扰查询编辑和执行。
      } finally {
        loadingSuggestionTables.delete(key);
      }
    }),
  );
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

function handleEditorKeydown(event: KeyboardEvent) {
  if (!suggestions.value.length) return;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    selectedSuggestionIndex.value = (selectedSuggestionIndex.value + 1) % suggestions.value.length;
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    selectedSuggestionIndex.value = (selectedSuggestionIndex.value - 1 + suggestions.value.length) % suggestions.value.length;
  } else if (event.key === "Enter" || event.key === "Tab") {
    event.preventDefault();
    acceptSuggestion(suggestions.value[selectedSuggestionIndex.value]);
  } else if (event.key === "Escape") {
    event.preventDefault();
    suggestions.value = [];
  }
}

function selectedSql() {
  const editor = editorElement.value;
  if (!editor || editor.selectionStart === editor.selectionEnd) return sql.value;
  return sql.value.slice(editor.selectionStart, editor.selectionEnd);
}

function runSelected() {
  runMenuOpen.value = false;
  void execute(selectedSql());
}

function runAll() {
  runMenuOpen.value = false;
  void execute(sql.value);
}

function resetQueryOutput() {
  result.value = null;
  explainResult.value = "";
  error.value = "";
  exportStatus.value = "";
  activeResultTab.value = "result";
  resultOffset.value = 0;
}

function createQueryTab() {
  const id = nextQueryTabId++;
  queryTabs.value.push({ id, title: `查询 ${id}`, sql: "" });
  activeQueryTabId.value = id;
  suggestions.value = [];
  resetQueryOutput();
  requestAnimationFrame(() => editorElement.value?.focus());
}

function selectQueryTab(id: number) {
  if (id === activeQueryTabId.value) return;
  activeQueryTabId.value = id;
  suggestions.value = [];
  resetQueryOutput();
}

function closeQueryTab(id: number) {
  const index = queryTabs.value.findIndex((tab) => tab.id === id);
  if (index < 0) return;
  if (queryTabs.value.length === 1) {
    queryTabs.value[0].sql = "";
    resetQueryOutput();
    return;
  }
  queryTabs.value.splice(index, 1);
  if (activeQueryTabId.value === id) {
    activeQueryTabId.value = queryTabs.value[Math.min(index, queryTabs.value.length - 1)].id;
    resetQueryOutput();
  }
}

function startEditorResize(event: PointerEvent) {
  if (event.button !== 0) return;
  event.preventDefault();
  const startY = event.clientY;
  const startHeight = editorHeight.value;
  const move = (moveEvent: PointerEvent) => {
    editorHeight.value = Math.min(520, Math.max(150, startHeight + moveEvent.clientY - startY));
  };
  const stop = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", stop);
    window.removeEventListener("pointercancel", stop);
    stopEditorResize = null;
  };
  stopEditorResize?.();
  stopEditorResize = stop;
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", stop);
  window.addEventListener("pointercancel", stop);
}

function handleResizeKeydown(event: KeyboardEvent) {
  if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
  event.preventDefault();
  editorHeight.value = Math.min(520, Math.max(150, editorHeight.value + (event.key === "ArrowDown" ? 24 : -24)));
}

function highlightSql(source: string) {
  const keywords = editorKeywords(selectedDatabaseType.value)
    .map((keyword) => keyword.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
    .sort((left, right) => right.length - left.length)
    .join("|");
  const pattern = new RegExp(`('(?:''|[^'])*'|--[^\\n]*|\\b(?:${keywords})\\b|\\b\\d+(?:\\.\\d+)?\\b)`, "gi");
  const escape = (value: string) => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
  return (
    source
      .split(pattern)
      .map((token) => {
        if (token.startsWith("'")) return `<span class="sql-string">${escape(token)}</span>`;
        if (token.startsWith("--")) return `<span class="sql-comment">${escape(token)}</span>`;
        if (/^\d/.test(token)) return `<span class="sql-number">${escape(token)}</span>`;
        if (new RegExp(`^(?:${keywords})$`, "i").test(token)) return `<span class="sql-keyword">${escape(token)}</span>`;
        return escape(token);
      })
      .join("") + "\n"
  );
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
    const saved = saveDirectSavedSql({
      id: savedSqlId.value || undefined,
      connectionId: connectionId.value,
      folderId: savedSqlFolderId.value,
      database: database.value,
      schema: schema.value || null,
      name: saveName.value.trim(),
      sql: sql.value,
    });
    savedSqlId.value = saved.id;
    savedSqlFolderId.value = saved.folderId;
    saveName.value = saved.name.replace(/\.sql$/i, "");
    const target = "本机 SQL 库";
    saveStatus.value = wasUpdate ? `已覆盖更新到${target}` : `已保存到${target}`;
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
    executionMode.value = draft.executionMode === "advanced" ? "advanced" : "safe";
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

async function executePage(offset: number, source?: string) {
  if (!connectionId.value || !database.value || !sql.value.trim()) return;
  const requestId = ++queryRequestId;
  const executionId = createExecutionId();
  activeExecutionId = executionId;
  executing.value = true;
  error.value = "";
  if (offset === 0) result.value = null;
  exportStatus.value = "";
  selectedCell.value = null;
  try {
    const querySql = source ?? (offset === 0 ? sql.value : lastExecutedSql || sql.value);
    if (offset === 0) lastExecutedSql = querySql;
    const response = await executeDirectQuery({
      connectionId: connectionId.value,
      database: database.value,
      schema: schema.value || null,
      sql: querySql,
      executionId,
      offset,
      pageSize: QUERY_PAGE_SIZE,
    });
    if (requestId === queryRequestId) {
      result.value = response;
      resultOffset.value = offset;
      if (offset === 0) columnWidths.value = {};
    }
  } catch (reason) {
    if (requestId === queryRequestId) {
      fail(reason);
      void cancelDirectQuery(executionId).catch(() => undefined);
    }
  } finally {
    if (requestId === queryRequestId) {
      executing.value = false;
      activeExecutionId = null;
    }
  }
}

function execute(source = sql.value) {
  lastExecutedSql = source;
  activeResultTab.value = "result";
  return executionMode.value === "safe" ? executePage(0, source) : executeAdvanced(source);
}

async function cancelQuery() {
  const executionId = activeExecutionId;
  if (!executionId || cancelling.value) return;
  cancelling.value = true;
  error.value = "";
  try {
    await cancelDirectQuery(executionId);
    error.value = "查询已取消，手机正在释放数据库语句";
  } catch (reason) {
    error.value = reason instanceof Error ? `取消请求未确认：${reason.message}` : "取消请求未确认";
  } finally {
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
      exportStatus.value = receipt.delivery === "share" ? `已打开分享面板 · ${receipt.filename}` : `${receipt.format.toUpperCase()} 已下载 · ${receipt.filename}`;
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
  stopEditorResize?.();
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

function copyResultTable() {
  if (!result.value?.columns.length) return;
  const lines = [result.value.columns, ...result.value.rows].map((row) => row.map((value) => cellText(value).replace(/\r?\n/g, " ")).join("\t"));
  void copyText(lines.join("\n"), `已复制 ${result.value.rows.length} 行查询结果`);
}

function refreshResult() {
  if (!result.value || executing.value) return;
  void executePage(resultOffset.value, lastExecutedSql || sql.value);
}

function openCell(rowIndex: number, columnIndex: number, value: unknown) {
  selectedCell.value = {
    rowIndex: resultOffset.value + rowIndex,
    pageRowIndex: rowIndex,
    columnIndex,
    value,
  };
  cellEditValue.value = value === null ? "NULL" : cellText(value);
}

function queueCellEdit() {
  if (!selectedCell.value) return;
  const key = `${selectedCell.value.pageRowIndex}:${selectedCell.value.columnIndex}`;
  pendingCellEdits.value = [
    ...pendingCellEdits.value.filter((item) => `${item.rowIndex}:${item.columnIndex}` !== key),
    {
      rowIndex: selectedCell.value.pageRowIndex,
      columnIndex: selectedCell.value.columnIndex,
      value: cellEditValue.value,
    },
  ];
  exportStatus.value = `已暂存 ${pendingCellEdits.value.length} 个单元格修改`;
  selectedCell.value = null;
}

function quotedIdentifier(value: string) {
  const dbType = selectedConnection.value?.dbType;
  if (["mysql", "clickhouse", "doris", "starrocks"].includes(dbType ?? "")) return `\`${value.replaceAll("`", "``")}\``;
  if (dbType === "sqlserver") return `[${value.replaceAll("]", "]]")}]`;
  return `"${value.replaceAll('"', '""')}"`;
}

function editedSqlLiteral(value: string, original: unknown) {
  if (value.trim().toUpperCase() === "NULL") return "NULL";
  if (typeof original === "number" && Number.isFinite(Number(value))) return String(Number(value));
  if (typeof original === "boolean" && /^(true|false)$/i.test(value.trim())) return value.trim().toUpperCase();
  return `'${value.replaceAll("'", "''")}'`;
}

function buildPendingUpdateSql() {
  if (!selectedTable.value || !result.value || pendingCellEdits.value.length === 0) return;
  const primaryKeys = columns.value.filter((column) => column.is_primary_key);
  const keyIndexes = primaryKeys.map((column) => result.value!.columns.indexOf(column.name));
  if (!primaryKeys.length || keyIndexes.some((index) => index < 0)) {
    error.value = "可编辑结果需要所选表的主键字段出现在结果集中";
    return;
  }
  const tableName = schema.value ? `${quotedIdentifier(schema.value)}.${quotedIdentifier(selectedTable.value.name)}` : quotedIdentifier(selectedTable.value.name);
  const statements = pendingCellEdits.value.map((edit) => {
    const row = result.value!.rows[edit.rowIndex];
    const column = result.value!.columns[edit.columnIndex];
    const predicates = primaryKeys.map((key, index) => {
      const original = row[keyIndexes[index]];
      return original === null ? `${quotedIdentifier(key.name)} IS NULL` : `${quotedIdentifier(key.name)} = ${editedSqlLiteral(cellText(original), original)}`;
    });
    return `UPDATE ${tableName} SET ${quotedIdentifier(column)} = ${editedSqlLiteral(edit.value, row[edit.columnIndex])} WHERE ${predicates.join(" AND ")};`;
  });
  sql.value = statements.join("\n");
  executionMode.value = "advanced";
  confirmedWrite.value = false;
  pendingCellEdits.value = [];
  result.value = null;
  exportStatus.value = "批量改单元格 SQL 已生成；请审阅并显式确认写操作";
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
      const widest = Math.max(column.length, ...result.value!.rows.map((row) => displayValue(row[index]).length));
      return [index, Math.min(360, Math.max(88, widest * 7 + 28))];
    }),
  );
}

function handleBack() {
  if (!selectedCell.value) return false;
  selectedCell.value = null;
  return true;
}

defineExpose({ handleBack });
</script>

<template>
  <div class="query-workbench">
    <section class="query-connection-bar">
      <button class="query-back" type="button" aria-label="返回" @click="emit('back')">‹</button>
      <label
        ><select v-model="connectionId" aria-label="查询连接" @change="selectConnection()">
          <option value="">选择连接</option>
          <option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select></label
      >
      <div>
        <strong>{{ selectedConnection?.name || "选择数据库连接" }}</strong>
        <small v-if="selectedConnection">{{ selectedConnection.host }}:{{ selectedConnection.port }} · <i></i> 已连接</small>
        <small v-else>连接后加载数据库结构与输入联想</small>
      </div>
      <button type="button" aria-label="新建查询" @click="createQueryTab">＋</button>
      <label class="query-file-button" aria-label="打开 SQL 文件">▱<input type="file" accept=".sql,.txt,text/plain,application/sql" @change="openSqlFile" /></label>
      <button type="button" aria-label="保存 SQL" @click="showSavePanel = !showSavePanel">▣</button>
      <button type="button" aria-label="更多与主题设置" @click="emit('more')">⋮</button>
    </section>
    <div class="context-path">
      <span class="context-database-icon" aria-hidden="true">◉</span>
      <select v-model="database" :disabled="!connectionId || loadingContext" aria-label="数据库" @change="selectDatabase()">
        <option value="">选择数据库</option>
        <option v-for="item in databases" :key="item.name" :value="item.name">{{ item.name }}</option>
      </select>
      <b>/</b>
      <select v-if="usesTableContextPicker" :value="selectedTable?.name || ''" :disabled="!database || loadingMetadata" aria-label="表或集合" @change="selectContextTable">
        <option value="">{{ selectedDatabaseType === "mongodb" ? "选择集合" : "选择表" }}</option>
        <option v-for="item in tables" :key="`${item.parent_schema ?? ''}:${item.name}`" :value="item.name">{{ item.name }}</option>
      </select>
      <select v-else v-model="schema" :disabled="schemas.length === 0" aria-label="Schema" @change="selectSchema">
        <option value="">{{ schemaContextLabel }}</option>
        <option v-for="item in schemas" :key="item" :value="item">{{ item }}</option>
      </select>
      <button type="button" aria-label="刷新元数据" @click="selectDatabase(schema)">↻</button>
    </div>
    <nav class="query-tabs" aria-label="查询标签">
      <button v-for="tab in queryTabs" :key="tab.id" :class="{ active: activeQueryTabId === tab.id }" type="button" @click="selectQueryTab(tab.id)">
        {{ tab.title }} <span role="button" tabindex="0" :aria-label="`关闭${tab.title}`" @click.stop="closeQueryTab(tab.id)" @keydown.enter.stop="closeQueryTab(tab.id)">×</span>
      </button>
      <button type="button" aria-label="新建查询" @click="createQueryTab">＋</button>
    </nav>
    <div class="editor">
      <div class="query-toolbar">
        <div class="run-control">
          <button :disabled="executing || !queryExecutionSupported || !database || !sql.trim() || !advancedExecutionReady" type="button" @click="runSelected">
            ▶ <span>{{ executing ? "运行中" : "运行" }}</span>
          </button>
          <button :disabled="executing || !queryExecutionSupported" type="button" aria-label="运行选项" @click="runMenuOpen = !runMenuOpen">⌄</button>
          <div v-if="runMenuOpen" class="run-menu">
            <button :disabled="!advancedExecutionReady" type="button" @click="runSelected">运行所选</button>
            <button :disabled="!advancedExecutionReady" type="button" @click="runAll">运行全部</button>
          </div>
        </div>
        <button :disabled="!executing || cancelling" type="button" @click="cancelQuery">■ <span>停止</span></button>
        <i></i>
        <button :disabled="explaining || !queryExecutionSupported || !database || !sql.trim()" type="button" @click="explainQuery">≋ <span>解释</span></button>
        <i></i>
        <button type="button" @click="formatCurrentSql">〈〉 <span>格式化</span></button>
        <button type="button" aria-label="切换执行模式" @click="toggleExecutionMode">⋮</button>
      </div>
      <div v-if="advancedMode" class="advanced-guard">
        <label>
          <input v-model="confirmedWrite" type="checkbox" :disabled="!writeAllowed" />
          <span>{{ writeAllowed ? "我确认高级模式中的任何语句都按可写操作执行" : "只读连接禁止高级模式，请切换安全模式" }}</span>
        </label>
        <input v-if="selectedConnection?.isProduction" v-model="productionConfirmation" :placeholder="`生产库确认：输入 ${selectedConnection.name}`" autocomplete="off" />
      </div>
      <div class="code-editor" :style="{ height: `${editorHeight}px` }">
        <div class="line-numbers" aria-hidden="true">
          <span v-for="line in editorLineCount" :key="line">{{ line }}</span>
        </div>
        <div class="editor-input">
          <pre class="sql-highlight" aria-hidden="true" v-html="highlightedSql"></pre>
          <textarea ref="editorElement" v-model="sql" spellcheck="false" autocapitalize="none" autocomplete="off" :placeholder="editorPlaceholder" aria-label="数据库查询编辑器" @click="updateSuggestions" @input="updateSuggestions" @keydown="handleEditorKeydown"></textarea>
          <div v-if="suggestions.length" class="suggestion-list" :style="suggestionPosition" role="listbox" aria-label="SQL 自动补全">
            <button v-for="(suggestion, index) in suggestions" :key="`${suggestion.kind}:${suggestion.label}`" :class="{ selected: selectedSuggestionIndex === index }" type="button" role="option" :aria-selected="selectedSuggestionIndex === index" @mousedown.prevent="acceptSuggestion(suggestion)">
              <i :data-kind="suggestion.kind">{{ suggestion.kind.slice(0, 2).toUpperCase() }}</i>
              <strong>{{ suggestion.label }}</strong>
              <small>{{ suggestion.detail }}</small>
            </button>
          </div>
        </div>
      </div>
      <div class="editor-tools">
        <button type="button" @click="showParameters = !showParameters">参数</button>
        <button :class="{ active: activeResultTab === 'schema' }" type="button" @click="activeResultTab = 'schema'">Schema 联想</button>
        <small>Ln {{ editorLineCount }} · UTF-8 · {{ selectedConnection?.dbType || "SQL" }}</small>
      </div>
      <div v-if="showParameters" class="parameter-editor">
        <span>JSON PARAMETERS · 支持 :name、${name}、&#123;&#123;name&#125;&#125;</span>
        <textarea v-model="parameterJson" spellcheck="false" placeholder='{"userId": 42, "active": true}'></textarea>
      </div>
      <form v-if="showSavePanel" class="save-sql" @submit.prevent="saveCurrentSql">
        <input v-model="saveName" maxlength="120" :placeholder="savedSqlId ? '更新已保存 SQL 的名称' : '给当前 SQL 命名'" />
        <button v-if="savedSqlId" :disabled="saving" type="button" @click="saveAsCopy">另存</button>
        <button :disabled="saving || !database || !sql.trim() || !saveName.trim()" type="submit">
          {{ saving ? "保存中" : savedSqlId ? "覆盖更新" : "保存 SQL" }}
        </button>
      </form>
      <p v-if="saveStatus" class="save-status">{{ saveStatus }}</p>
    </div>
    <div class="editor-result-resizer" role="separator" aria-label="调整编辑器与结果区高度" aria-orientation="horizontal" :aria-valuenow="editorHeight" tabindex="0" @pointerdown="startEditorResize" @keydown="handleResizeKeydown"><i></i></div>
    <nav class="result-tabs" aria-label="查询输出">
      <button :class="{ active: activeResultTab === 'result' }" type="button" @click="activeResultTab = 'result'">结果 1</button>
      <button :class="{ active: activeResultTab === 'messages' }" type="button" @click="activeResultTab = 'messages'">消息</button>
      <button :class="{ active: activeResultTab === 'plan' }" type="button" @click="activeResultTab = 'plan'">执行计划</button>
      <button :class="{ active: activeResultTab === 'schema' }" type="button" @click="activeResultTab = 'schema'">
        Schema Assist <i>{{ visibleTables.length }}</i>
      </button>
    </nav>
    <div v-if="error" class="query-error">
      <b>!</b><span>{{ error }}</span>
    </div>
    <section v-if="activeResultTab === 'messages'" class="message-panel">
      <strong>{{ result ? "执行成功" : executing ? "正在执行查询" : "等待执行" }}</strong>
      <span v-if="result">{{ resultStatusText }} · {{ result.execution_time_ms }} ms</span>
      <span v-else>{{ saveStatus || exportStatus || "查询消息将在这里显示" }}</span>
    </section>
    <section v-if="activeResultTab === 'plan' && explainResult" class="explain-panel">
      <header><span>EXECUTION PLAN</span><button type="button" @click="explainResult = ''">×</button></header>
      <pre>{{ explainResult }}</pre>
    </section>
    <section v-if="activeResultTab === 'schema'" class="query-builder schema-result-panel">
      <header>
        <div><span>SCHEMA ASSIST</span><strong>从元数据生成查询</strong></div>
        <small>{{ loadingMetadata || searchingTables ? "SYNCING…" : `${visibleTables.length}${hasMoreTables ? "+" : ""} OBJECTS` }}</small>
      </header>
      <div class="table-search">
        <input v-model="tableSearch" type="search" autocomplete="off" placeholder="搜索表、视图或集合" aria-label="搜索表、视图或集合" @input="scheduleTableSearch" />
        <button v-if="tableSearch" type="button" aria-label="清除搜索" @click="clearTableSearch">×</button>
      </div>
      <div v-if="visibleTables.length" class="table-strip">
        <article v-for="table in visibleTables" :key="`${table.parent_schema ?? ''}:${table.name}`" :class="{ active: selectedTable?.name === table.name }">
          <button type="button" @click="openTable(table)">
            <small>{{ table.table_type }}</small
            ><strong>{{ table.name }}</strong>
          </button>
          <button v-if="queryExecutionSupported" type="button" aria-label="生成只读查询" @click="generateTableQuery(table)">SELECT ↗</button>
        </article>
      </div>
      <p v-else-if="!database">请先选择数据库连接和数据库。</p>
      <p v-else-if="!loadingMetadata && !searchingTables">{{ tableSearch.trim() ? "没有匹配的对象。" : "当前范围没有可见对象。" }}</p>
      <button v-if="hasMoreTables" class="load-more-tables" :disabled="loadingMoreTables" type="button" @click="loadMoreTables">{{ loadingMoreTables ? "正在加载…" : `继续加载（已显示 ${visibleTables.length}）` }}</button>
      <div v-if="selectedTable" class="field-builder">
        <div>
          <span>FIELDS / {{ selectedTable.name }}</span
          ><small>点击字段追加 WHERE / AND 条件</small>
        </div>
        <button v-for="column in columns" :key="column.name" type="button" :title="`${column.name} · ${column.data_type}`" @click="addColumnCondition(column)">
          <strong>{{ column.name }}</strong
          ><small>{{ column.data_type }}</small
          ><b>＋</b>
        </button>
      </div>
    </section>
    <QueryResultPanel
      v-if="activeResultTab === 'result' && result"
      :result="result"
      :status-text="resultStatusText"
      :executing="executing"
      :exporting="exporting"
      :export-status="exportStatus"
      v-model:export-format="exportFormat"
      :pending-edit-count="pendingCellEdits.length"
      :chart-rows="chartRows"
      :show-chart="showChart"
      :column-widths="columnWidths"
      :result-offset="resultOffset"
      :result-page="resultPage"
      :page-size="QUERY_PAGE_SIZE"
      @refresh="refreshResult"
      @copy="copyResultTable"
      @export="shareResult"
      @build-update-sql="buildPendingUpdateSql"
      @toggle-chart="showChart = !showChart"
      @auto-fit="autoFitColumns"
      @open-cell="openCell"
      @page="executePage"
    />
    <div v-if="selectedCell && result" class="cell-detail-backdrop" role="presentation" @click.self="selectedCell = null">
      <section class="cell-detail" role="dialog" aria-modal="true" aria-labelledby="cell-detail-title">
        <header>
          <div>
            <span>CELL {{ selectedCell.rowIndex + 1 }} / {{ selectedCell.columnIndex + 1 }}</span>
            <strong id="cell-detail-title">{{ result.columns[selectedCell.columnIndex] }}</strong>
          </div>
          <button type="button" aria-label="关闭单元格详情" @click="selectedCell = null">×</button>
        </header>
        <pre :class="{ null: selectedCell.value === null }">{{ cellText(selectedCell.value) }}</pre>
        <textarea v-if="selectedTable && columns.some((column) => column.is_primary_key)" v-model="cellEditValue" class="cell-edit-input" aria-label="编辑单元格值"></textarea>
        <footer>
          <button type="button" @click="copySelectedCell">复制单元格</button>
          <button type="button" @click="copySelectedRow">复制整行 TSV</button>
          <button v-if="selectedTable && columns.some((column) => column.is_primary_key)" type="button" @click="queueCellEdit">暂存修改</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.query-workbench {
  display: grid;
  gap: 12px;
  margin-top: 16px;
}
.context-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.context-grid label:last-child {
  grid-column: 1 / -1;
}
label span {
  display: block;
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 8px;
  letter-spacing: 0.12em;
}
select {
  width: 100%;
  height: 42px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--panel);
  padding: 0 9px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.editor {
  border: 1px solid var(--line);
  border-top: 2px solid var(--acid);
  background: #080a09;
}
.editor-heading {
  display: grid;
  min-height: 38px;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 9px;
  padding-left: 12px;
  color: var(--acid);
  font-size: 8px;
  letter-spacing: 0.12em;
}
.editor-heading small {
  color: var(--muted);
  font-size: 7px;
  letter-spacing: 0;
}
.editor-heading button {
  align-self: stretch;
  border: 0;
  border-left: 1px solid var(--line);
  background: rgba(199, 255, 61, 0.08);
  padding: 0 10px;
  color: var(--acid);
  font: inherit;
  font-size: 7px;
  letter-spacing: 0.1em;
}
.execution-modes {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  border-top: 1px solid var(--line);
}
.execution-modes button {
  min-height: 39px;
  border: 0;
  border-right: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 7px;
}
.execution-modes button.active {
  background: rgba(199, 255, 61, 0.12);
  color: var(--acid);
}
.advanced-guard {
  display: grid;
  gap: 9px;
  border-top: 1px solid rgba(255, 184, 77, 0.28);
  background: rgba(255, 184, 77, 0.055);
  padding: 11px;
}
.advanced-guard label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--amber);
  font-size: 8px;
  line-height: 1.45;
}
.advanced-guard label span {
  display: inline;
  margin: 0;
  color: inherit;
  font-size: inherit;
  letter-spacing: 0;
}
.advanced-guard > input {
  min-height: 38px;
  border: 1px solid rgba(255, 184, 77, 0.35);
  background: #0d0e0c;
  padding: 0 9px;
  color: var(--ink);
  font: inherit;
  font-size: 9px;
}
.editor-input {
  position: relative;
  border-top: 1px solid var(--line);
}
textarea {
  display: block;
  width: 100%;
  min-height: 180px;
  resize: vertical;
  border: 0;
  outline: none;
  background: transparent;
  padding: 15px;
  color: #e7f5d1;
  font:
    12px/1.65 "Azeret Mono Variable",
    monospace;
}
.editor-tools {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid var(--line);
}
.editor-tools button,
.file-action {
  display: grid;
  min-height: 38px;
  place-items: center;
  border: 0;
  border-right: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.02);
  color: var(--muted);
  font: inherit;
  font-size: 7px;
  letter-spacing: 0.08em;
}
.file-action {
  position: relative;
  margin: 0;
}
.file-action input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.parameter-editor {
  border-top: 1px solid var(--line);
}
.parameter-editor > span {
  display: block;
  padding: 8px 11px;
  color: var(--muted);
  font-size: 7px;
  letter-spacing: 0.08em;
}
.parameter-editor textarea {
  min-height: 84px;
  border-top: 1px solid var(--line);
  font-size: 10px;
}
.suggestion-list {
  position: absolute;
  z-index: 4;
  right: 8px;
  bottom: 8px;
  left: 8px;
  overflow: auto;
  max-height: 190px;
  border: 1px solid rgba(199, 255, 61, 0.45);
  background: rgba(12, 15, 12, 0.98);
  box-shadow: 0 -16px 35px rgba(0, 0, 0, 0.55);
}
.suggestion-list button {
  display: grid;
  width: 100%;
  min-height: 40px;
  grid-template-columns: 25px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 6px 9px;
  color: var(--ink);
  text-align: left;
}
.suggestion-list i {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: 6px;
  font-style: normal;
}
.suggestion-list i[data-kind="column"] {
  border-color: rgba(199, 255, 61, 0.4);
  color: var(--acid);
}
.suggestion-list strong {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
}
.suggestion-list small {
  color: var(--muted);
  font-size: 7px;
}
.query-actions {
  display: grid;
  grid-template-columns: 1fr;
  border-top: 1px solid var(--line);
}
.query-actions:has(.cancel-action) {
  grid-template-columns: 1fr 42%;
}
.query-actions button {
  min-height: 48px;
  border: 0;
  background: var(--acid);
  color: #10130c;
  font: inherit;
  font-weight: 760;
}
.query-actions .cancel-action {
  border-left: 1px solid rgba(255, 101, 95, 0.45);
  background: rgba(255, 101, 95, 0.13);
  color: var(--danger);
}
.save-sql {
  display: grid;
  grid-template-columns: 1fr auto auto;
  border-top: 1px solid var(--line);
}
.save-sql input {
  min-width: 0;
  border: 0;
  background: #101310;
  padding: 0 12px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
  outline: none;
}
.save-sql button {
  width: auto;
  min-width: 92px;
  min-height: 46px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--acid);
}
.save-status {
  margin: 0;
  border-top: 1px solid var(--line);
  padding: 8px 12px;
  color: var(--acid);
  font-size: 8px;
}
button:disabled {
  opacity: 0.45;
}
.query-builder {
  overflow: hidden;
  border: 1px solid var(--line);
  background: linear-gradient(145deg, rgba(199, 255, 61, 0.045), transparent 52%), var(--panel);
}
.query-builder > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding: 11px 12px;
}
.query-builder > header span {
  display: block;
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.14em;
}
.query-builder > header strong {
  display: block;
  margin-top: 4px;
  font-size: 11px;
}
.query-builder > header small {
  color: var(--muted);
  font-size: 7px;
}
.query-builder > p {
  margin: 0;
  padding: 18px 12px;
  color: var(--muted);
  font-size: 9px;
}
.table-search {
  display: grid;
  grid-template-columns: 1fr auto;
  border-bottom: 1px solid var(--line);
  background: #0c0f0d;
}
.table-search input {
  min-width: 0;
  min-height: 40px;
  border: 0;
  outline: none;
  background: transparent;
  padding: 0 11px;
  color: var(--ink);
  font: inherit;
  font-size: 9px;
}
.table-search button {
  width: 40px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 16px;
}
.table-strip {
  display: flex;
  overflow-x: auto;
  gap: 7px;
  padding: 10px;
  scroll-snap-type: x proximity;
}
.table-strip article {
  flex: 0 0 156px;
  scroll-snap-align: start;
  border: 1px solid var(--line);
  background: #0c0f0d;
}
.table-strip article.active {
  border-color: rgba(199, 255, 61, 0.52);
}
.table-strip button {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  color: var(--ink);
  text-align: left;
}
.table-strip article > button:first-child {
  min-height: 61px;
  padding: 10px;
}
.table-strip article > button:last-child {
  min-height: 31px;
  border-top: 1px solid var(--line);
  padding: 0 10px;
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.1em;
}
.table-strip small {
  display: block;
  overflow: hidden;
  color: var(--muted);
  font-size: 6px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-strip strong {
  display: block;
  overflow: hidden;
  margin-top: 7px;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.load-more-tables {
  width: 100%;
  min-height: 40px;
  border: 0;
  border-top: 1px solid var(--line);
  background: rgba(199, 255, 61, 0.04);
  color: var(--acid);
  font: inherit;
  font-size: 8px;
  letter-spacing: 0.08em;
}
.field-builder {
  display: grid;
  max-height: 230px;
  grid-template-columns: 1fr 1fr;
  overflow-y: auto;
  border-top: 1px solid var(--line);
}
.field-builder > div {
  grid-column: 1 / -1;
  padding: 10px 12px;
}
.field-builder > div span {
  display: block;
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.12em;
}
.field-builder > div small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 7px;
}
.field-builder > button {
  position: relative;
  min-width: 0;
  min-height: 52px;
  border: 0;
  border-top: 1px solid var(--line);
  border-right: 1px solid var(--line);
  background: rgba(0, 0, 0, 0.12);
  padding: 8px 28px 8px 10px;
  color: var(--ink);
  text-align: left;
}
.field-builder > button strong,
.field-builder > button small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.field-builder > button strong {
  font-size: 9px;
}
.field-builder > button small {
  margin-top: 5px;
  color: var(--muted);
  font-size: 7px;
}
.field-builder > button b {
  position: absolute;
  top: 17px;
  right: 10px;
  color: var(--acid);
  font-size: 14px;
  font-weight: 400;
}
.query-error {
  display: flex;
  gap: 10px;
  border: 1px solid rgba(255, 101, 95, 0.35);
  padding: 13px;
  color: var(--danger);
  font-size: 10px;
  line-height: 1.5;
}
.explain-panel {
  overflow: hidden;
  border: 1px solid rgba(96, 76, 255, 0.4);
  background: #0b0b10;
}
.explain-panel header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding-left: 11px;
  color: #b9aeff;
  font-size: 8px;
  letter-spacing: 0.12em;
}
.explain-panel header button {
  width: 42px;
  height: 38px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
}
.explain-panel pre {
  overflow: auto;
  max-height: 44vh;
  margin: 0;
  padding: 13px;
  color: var(--ink);
  font:
    9px/1.6 "Azeret Mono Variable",
    monospace;
  white-space: pre-wrap;
}
.result-panel {
  border: 1px solid var(--line);
  background: var(--panel);
}
.result-panel > header {
  display: grid;
  min-height: 46px;
  grid-template-columns: minmax(140px, 1fr) auto;
  border-bottom: 1px solid var(--line);
  color: var(--acid);
  font-size: 8px;
  letter-spacing: 0.1em;
}
.result-metrics {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  padding: 11px;
}
.result-panel em {
  color: var(--amber);
  font-style: normal;
}
.result-actions {
  display: flex;
  min-width: 0;
  align-items: stretch;
}
.result-actions > button,
.result-actions > select {
  min-height: 44px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  padding: 0 9px;
  color: var(--acid);
  font: inherit;
  font-size: 7px;
  letter-spacing: 0.06em;
}
.result-actions > select {
  width: 86px;
  border-radius: 0;
}
.result-actions > button:first-child {
  color: var(--muted);
}
.result-actions .export-action {
  background: linear-gradient(135deg, rgba(199, 255, 61, 0.16), rgba(199, 255, 61, 0.04));
  font-weight: 720;
}
.export-action:active {
  background: var(--acid);
  color: #10130c;
}
.export-status {
  margin: 0;
  border-bottom: 1px solid var(--line);
  padding: 9px 11px;
  color: var(--muted);
  font-size: 8px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.query-chart {
  display: grid;
  gap: 7px;
  padding: 12px;
}
.query-chart article {
  display: grid;
  grid-template-columns: minmax(70px, 28%) 1fr auto;
  align-items: center;
  gap: 8px;
}
.query-chart article > span {
  overflow: hidden;
  color: var(--muted);
  font-size: 7px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.query-chart i {
  overflow: hidden;
  height: 12px;
  background: rgba(255, 255, 255, 0.05);
}
.query-chart i b {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--acid), #62d8a8);
}
.query-chart strong {
  color: var(--ink);
  font-size: 8px;
}
.result-panel .result-hint {
  margin: 0;
  border-bottom: 1px solid var(--line);
  padding: 8px 11px;
  color: var(--faint);
  font-size: 7px;
  line-height: 1.55;
}
.result-scroll {
  overflow: auto;
  max-height: 48vh;
}
table {
  table-layout: fixed;
  border-collapse: collapse;
  min-width: 100%;
  width: max-content;
  font-size: 9px;
  white-space: nowrap;
}
th,
td {
  overflow: hidden;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  padding: 0;
  text-align: left;
  text-overflow: ellipsis;
}
th {
  position: sticky;
  z-index: 2;
  top: 0;
  background: #171b18;
  color: var(--acid);
}
th > div {
  display: flex;
  min-height: 39px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-left: 9px;
}
th > div > span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
}
.width-controls {
  display: flex;
  align-self: stretch;
}
.width-controls button {
  width: 27px;
  border: 0;
  border-left: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.025);
  color: var(--muted);
  font: inherit;
}
td > button {
  display: block;
  overflow: hidden;
  width: 100%;
  min-height: 38px;
  border: 0;
  background: transparent;
  padding: 9px;
  color: var(--ink);
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
td > button:active {
  background: rgba(199, 255, 61, 0.1);
  color: var(--acid);
}
td.null {
  color: var(--faint);
  font-style: italic;
}
.result-panel > p {
  padding: 20px;
  color: var(--muted);
  font-size: 10px;
}
.result-panel footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 9px;
}
.result-panel footer button {
  width: 34px;
  height: 30px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--acid);
}
.result-panel footer span {
  font-size: 9px;
}
.cell-detail-backdrop {
  position: fixed;
  z-index: 30;
  inset: 0;
  display: grid;
  align-items: end;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(4px);
}
.cell-detail {
  max-height: min(72vh, 560px);
  overflow: hidden;
  border: 1px solid rgba(199, 255, 61, 0.45);
  border-bottom: 0;
  background: #0b0e0c;
  box-shadow: 0 -24px 70px rgba(0, 0, 0, 0.68);
}
.cell-detail > header {
  display: flex;
  min-height: 58px;
  align-items: stretch;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding-left: 14px;
}
.cell-detail > header div {
  display: grid;
  align-content: center;
  gap: 5px;
  min-width: 0;
}
.cell-detail > header span {
  color: var(--muted);
  font-size: 7px;
  letter-spacing: 0.14em;
}
.cell-detail > header strong {
  overflow: hidden;
  color: var(--acid);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell-detail > header button {
  width: 58px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font-size: 24px;
}
.cell-detail pre {
  overflow: auto;
  max-height: calc(min(72vh, 560px) - 116px);
  min-height: 120px;
  margin: 0;
  padding: 16px;
  color: var(--ink);
  font:
    11px/1.65 "Azeret Mono Variable",
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.cell-detail pre.null {
  color: var(--faint);
  font-style: italic;
}
.cell-edit-input {
  min-height: 92px;
  max-height: 180px;
  border-top: 1px solid var(--line);
  background: rgba(199, 255, 61, 0.035);
  font-size: 10px;
}
.cell-detail > footer {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  border-top: 1px solid var(--line);
}
.cell-detail > footer button {
  min-height: 48px;
  border: 0;
  border-right: 1px solid var(--line);
  background: rgba(199, 255, 61, 0.07);
  color: var(--acid);
  font: inherit;
  font-size: 9px;
}
@media (max-width: 560px) {
  .result-panel > header {
    grid-template-columns: 1fr;
  }
  .result-actions {
    border-top: 1px solid var(--line);
  }
  .result-actions > button,
  .result-actions > select {
    flex: 1 1 auto;
    border-left: 0;
    border-right: 1px solid var(--line);
  }
}

/* Mobile SQL studio — aligned with the database browser visual system. */
.query-workbench {
  gap: 0;
  margin: 0 -2px;
}
.query-connection-bar {
  position: relative;
  display: grid;
  min-height: 76px;
  grid-template-columns: 34px minmax(0, 1fr) repeat(4, 38px);
  align-items: center;
  gap: 5px;
  border-bottom: 1px solid var(--line);
  padding: 7px 5px;
}
.query-connection-bar > label:first-of-type {
  position: absolute;
  z-index: 2;
  inset: 7px 172px 7px 44px;
  margin: 0;
  opacity: 0;
}
.query-connection-bar > label:first-of-type select {
  height: 100%;
  cursor: pointer;
}
.query-connection-bar > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}
.query-connection-bar strong,
.query-connection-bar small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.query-connection-bar strong {
  color: var(--ink);
  font-size: 15px;
  letter-spacing: -0.02em;
}
.query-connection-bar small {
  color: var(--muted);
  font-size: 8px;
}
.query-connection-bar small i {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22a447;
}
.query-connection-bar > button,
.query-file-button {
  display: grid;
  min-height: 36px;
  place-items: center;
  border: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 16px;
}
.query-connection-bar > .query-back {
  position: relative;
  z-index: 3;
  min-height: 44px;
  font-size: 30px;
  font-weight: 300;
}
.query-file-button {
  position: relative;
  margin: 0;
}
.query-file-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.context-path {
  display: grid;
  min-height: 48px;
  grid-template-columns: 34px minmax(0, 1fr) auto minmax(0, 1fr) 42px;
  align-items: center;
  border-bottom: 1px solid var(--line);
}
.context-database-icon {
  display: grid;
  height: 100%;
  place-items: center;
  border-right: 1px solid var(--line);
  color: var(--muted);
  font-size: 13px;
}
.context-path select {
  height: 47px;
  border: 0;
  background: transparent;
  padding: 0 10px;
  font-size: 10px;
}
.context-path b {
  color: var(--muted);
  font-size: 10px;
  font-weight: 400;
}
.context-path button {
  align-self: stretch;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 20px;
}
.query-tabs {
  display: flex;
  min-height: 43px;
  align-items: end;
  gap: 4px;
  border-bottom: 1px solid var(--line);
  padding: 5px 5px 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.query-tabs button {
  flex: 0 0 auto;
  min-width: 102px;
  min-height: 38px;
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: 6px 6px 0 0;
  background: var(--panel);
  color: var(--muted);
  font: inherit;
  font-size: 10px;
}
.query-tabs button.active {
  color: var(--ink);
  box-shadow: inset 0 -2px var(--acid);
}
.query-tabs button span {
  float: right;
  margin-left: 20px;
  color: var(--muted);
}
.query-tabs button span:focus-visible {
  border-radius: 3px;
  outline: 2px solid var(--acid);
  outline-offset: 2px;
}
.query-tabs button:last-child {
  min-width: 38px;
  width: 38px;
  font-size: 15px;
}
.editor {
  border: 0;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}
.query-toolbar {
  position: relative;
  display: flex;
  min-height: 48px;
  align-items: stretch;
  overflow: visible;
  border-bottom: 1px solid var(--line);
}
.query-toolbar > button,
.run-control > button {
  border: 0;
  background: transparent;
  padding: 0 10px;
  color: var(--muted);
  font: inherit;
  font-size: 11px;
}
.query-toolbar button span {
  margin-left: 4px;
  font-size: 9px;
}
.query-toolbar > i {
  width: 1px;
  height: 22px;
  align-self: center;
  background: var(--line);
}
.query-toolbar > button:last-child {
  margin-left: auto;
  font-size: 17px;
}
.run-control {
  position: relative;
  display: flex;
  margin: 6px 3px 6px 6px;
  border-radius: 6px;
  background: var(--accent-soft);
}
.run-control > button {
  color: var(--acid);
}
.run-control > button:first-child {
  padding-right: 6px;
}
.run-control > button:nth-child(2) {
  padding: 0 7px;
}
.run-menu {
  position: absolute;
  z-index: 12;
  top: 39px;
  left: 0;
  overflow: hidden;
  width: 112px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.16);
}
.run-menu button {
  display: block;
  width: 100%;
  min-height: 40px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 0 12px;
  color: var(--ink);
  text-align: left;
  font: inherit;
  font-size: 9px;
}
.advanced-guard {
  border-top: 0;
  background: color-mix(in srgb, var(--amber) 7%, var(--panel));
}
.code-editor {
  display: grid;
  min-height: 150px;
  grid-template-columns: 34px minmax(0, 1fr);
  background: color-mix(in srgb, var(--panel) 98%, var(--accent-soft));
}
.line-numbers {
  display: grid;
  align-content: start;
  border-right: 1px solid var(--line);
  padding: 14px 8px;
  color: var(--faint);
  font:
    10px/1.75 "Azeret Mono Variable",
    monospace;
  text-align: right;
}
.line-numbers span {
  height: 17.5px;
}
.editor-input {
  position: relative;
  height: 100%;
  min-width: 0;
  border: 0;
  overflow: hidden;
}
.editor-input textarea,
.sql-highlight {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-height: 100%;
  margin: 0;
  border: 0;
  padding: 14px 12px;
  font:
    10px/1.75 "Azeret Mono Variable",
    monospace;
  tab-size: 2;
  white-space: pre-wrap;
  overflow-wrap: normal;
}
.sql-highlight {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: transparent;
  color: var(--ink);
  pointer-events: none;
}
.editor-input textarea {
  position: relative;
  z-index: 1;
  resize: none;
  background: transparent;
  color: transparent;
  caret-color: var(--acid);
  -webkit-text-fill-color: transparent;
}
.editor-input textarea::placeholder {
  color: var(--muted);
  -webkit-text-fill-color: var(--muted);
}
.editor-input textarea::selection {
  background: color-mix(in srgb, var(--acid) 22%, transparent);
}
.sql-highlight :deep(.sql-keyword) {
  color: #0675ff;
  font-weight: 700;
}
.sql-highlight :deep(.sql-string) {
  color: #e02c2c;
}
.sql-highlight :deep(.sql-number) {
  color: #6538e8;
}
.sql-highlight :deep(.sql-comment) {
  color: var(--muted);
  font-style: italic;
}
.suggestion-list {
  top: 58px;
  right: auto;
  bottom: auto;
  left: 48px;
  width: min(230px, calc(100% - 56px));
  max-height: 164px;
  border-color: var(--line);
  border-radius: 6px;
  background: var(--panel);
  box-shadow: 0 12px 34px rgba(0, 0, 0, 0.2);
}
.suggestion-list button {
  color: var(--ink);
}
.suggestion-list button.selected {
  background: var(--accent-soft);
  color: var(--acid);
}
.suggestion-list i[data-kind="column"] {
  border-color: color-mix(in srgb, var(--acid) 45%, var(--line));
  color: var(--acid);
}
.editor-tools {
  grid-template-columns: auto auto 1fr;
  background: var(--panel);
}
.editor-tools button {
  min-width: 72px;
  border: 0;
  border-right: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 8px;
}
.editor-tools button.active {
  background: var(--accent-soft);
  color: var(--acid);
}
.editor-tools small {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 10px;
  color: var(--muted);
  font-size: 7px;
}
.save-sql {
  background: var(--panel);
}
.save-sql input {
  background: var(--field);
  color: var(--ink);
}
.editor-result-resizer {
  display: grid;
  min-height: 22px;
  place-items: center;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
  cursor: row-resize;
  touch-action: none;
}
.editor-result-resizer i {
  width: 34px;
  height: 4px;
  border-radius: 99px;
  background: var(--faint);
  transition:
    width 120ms ease,
    background 120ms ease;
}
.editor-result-resizer:is(:hover, :focus-visible) i {
  width: 48px;
  background: var(--acid);
}
.result-tabs {
  display: grid;
  min-height: 43px;
  grid-template-columns: repeat(4, 1fr);
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: 10px 10px 0 0;
  background: var(--panel);
  margin-top: 0;
}
.result-tabs button {
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 8px;
  white-space: nowrap;
}
.result-tabs button.active {
  color: var(--ink);
  box-shadow: inset 0 -2px var(--acid);
}
.result-tabs button i {
  display: inline-grid;
  width: 17px;
  height: 17px;
  margin-left: 3px;
  place-items: center;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--acid);
  font-size: 7px;
  font-style: normal;
}
.message-panel {
  display: flex;
  min-height: 72px;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 13px;
}
.message-panel strong {
  color: #169447;
  font-size: 10px;
}
.message-panel span {
  color: var(--muted);
  font-size: 8px;
}
.result-panel {
  border-radius: 0 0 9px 9px;
}
.result-panel > header {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) auto;
}
.result-metrics {
  gap: 5px;
  padding: 8px 9px;
  letter-spacing: 0;
}
.result-metrics strong {
  color: #169447;
  font-size: 8px;
  white-space: nowrap;
}
.result-metrics span {
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-actions {
  position: relative;
  border-top: 0;
}
.result-actions > button,
.result-actions > select {
  width: 38px;
  min-height: 44px;
  flex: 0 0 38px;
  border-right: 0;
  border-left: 1px solid var(--line);
  padding: 0;
  color: var(--muted);
  font-size: 16px;
  letter-spacing: 0;
}
.result-actions > select {
  width: 48px;
  flex-basis: 48px;
  padding: 0 3px;
  font-size: 7px;
}
.result-actions .export-action {
  background: transparent;
  color: var(--acid);
}
.result-more {
  position: relative;
  width: 34px;
  flex: 0 0 34px;
}
.result-more summary {
  display: grid;
  min-height: 44px;
  place-items: center;
  border-left: 1px solid var(--line);
  color: var(--muted);
  cursor: pointer;
  font-size: 16px;
  list-style: none;
}
.result-more summary::-webkit-details-marker {
  display: none;
}
.result-more > div {
  position: absolute;
  z-index: 15;
  top: 42px;
  right: 0;
  overflow: hidden;
  width: 150px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.18);
}
.result-more label {
  display: grid;
  gap: 5px;
  padding: 8px 10px;
  color: var(--muted);
  font-size: 7px;
  letter-spacing: 0.04em;
}
.result-more label select {
  height: 32px;
  border-radius: 4px;
  font-size: 8px;
}
.result-more button {
  display: block;
  width: 100%;
  min-height: 38px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 0 10px;
  color: var(--ink);
  text-align: left;
  font: inherit;
  font-size: 8px;
}
.result-panel th {
  background: var(--field);
  color: var(--ink);
}
.query-builder {
  margin-top: 9px;
  background: var(--panel);
}
.schema-result-panel {
  margin-top: 0;
  border-top: 0;
}
.result-scroll {
  max-height: 42vh;
  scrollbar-color: var(--faint) transparent;
  scrollbar-width: thin;
}
.result-scroll th > div {
  min-height: 42px;
  padding: 0 10px;
}
.result-scroll th i {
  color: var(--faint);
  font-size: 8px;
  font-style: normal;
}
.result-scroll td > button {
  min-height: 44px;
  padding: 9px 11px;
}
.result-scroll .status-value {
  width: auto;
  min-height: 26px;
  margin: 8px 10px;
  border: 1px solid color-mix(in srgb, #169447 34%, var(--line));
  border-radius: 4px;
  background: color-mix(in srgb, #24b35a 8%, transparent);
  padding: 3px 10px;
  color: #169447;
  text-align: center;
}
.result-panel footer {
  min-height: 52px;
  justify-content: space-between;
  gap: 10px;
  border-top: 1px solid var(--line);
  padding: 8px 10px;
}
.result-panel footer > div {
  display: flex;
  align-items: center;
  gap: 8px;
}
.result-panel footer button {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  color: var(--ink);
}
.result-panel footer button:disabled {
  color: var(--faint);
}
.result-panel footer span,
.result-panel footer small {
  color: var(--muted);
  font-size: 8px;
  white-space: nowrap;
}
.result-panel footer small {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 8px 9px;
}

/* Prototype-aligned mobile query workspace. Result rendering lives in
   QueryResultPanel; this component retains execution and safety state. */
.query-workbench {
  min-width: 0;
  max-width: 100%;
  gap: var(--space-2);
  margin-top: 0;
  padding-bottom: var(--space-3);
}
.query-connection-bar,
.context-path,
.editor,
.message-panel,
.explain-panel,
.query-builder {
  min-width: 0;
  border-color: var(--divider-color);
  background: var(--card-background);
}
.query-connection-bar,
.context-path,
.editor,
.message-panel,
.explain-panel,
.query-builder {
  border-radius: var(--radius-card);
}
.query-connection-bar {
  min-height: var(--topbar-height);
  box-shadow: 0 5px 18px rgba(23, 32, 51, 0.045);
}
.context-path {
  min-height: var(--control-height);
  overflow: hidden;
}
.context-path select {
  min-width: 0;
  background: var(--input-background);
}
.query-tabs {
  min-width: 0;
  max-width: 100%;
  border-color: var(--divider-color);
  background: var(--card-background);
  scrollbar-width: thin;
}
.query-tabs button {
  min-height: var(--control-height-sm);
}
.query-tabs button.active,
.result-tabs button.active {
  color: var(--primary);
  box-shadow: inset 0 -2px var(--primary);
}
.editor {
  overflow: hidden;
  box-shadow: 0 5px 18px rgba(23, 32, 51, 0.045);
}
.query-toolbar,
.editor-tools,
.result-tabs {
  border-color: var(--divider-color);
  background: var(--card-background);
}
.query-toolbar button {
  min-height: var(--control-height-sm);
  border-radius: var(--radius-sm);
}
.code-editor,
.editor-input,
.editor-input textarea,
.sql-highlight,
.parameter-editor textarea,
.explain-panel pre {
  min-width: 0;
  max-width: 100%;
}
.code-editor,
.editor-input,
.editor-input textarea,
.sql-highlight {
  overflow-x: auto;
  overscroll-behavior-x: contain;
}
.advanced-guard {
  border-color: color-mix(in srgb, var(--warning) 35%, var(--divider-color));
  background: color-mix(in srgb, var(--warning) 7%, var(--card-background));
}
.advanced-guard:has(> input) {
  border-color: color-mix(in srgb, var(--danger) 38%, var(--divider-color));
  background: color-mix(in srgb, var(--danger) 6%, var(--card-background));
}
.advanced-guard > input {
  border-color: color-mix(in srgb, var(--danger) 38%, var(--divider-color));
  background: var(--input-background);
}
.result-tabs {
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  overscroll-behavior-x: contain;
}
.message-panel strong {
  color: var(--success);
}
.explain-panel pre {
  overflow-x: auto;
  background: var(--input-background);
}
.cell-detail {
  width: min(100%, var(--content-max-width));
  border-color: var(--divider-color);
  border-radius: var(--radius-sheet) var(--radius-sheet) 0 0;
  background: var(--card-background);
}
.cell-detail pre,
.cell-edit-input {
  max-width: 100%;
  overflow-x: auto;
  background: var(--input-background);
}
@media (max-width: 360px) {
  .query-connection-bar > button:not(.query-back):not(:last-child) {
    display: none;
  }
  .query-toolbar button span {
    display: none;
  }
  .query-toolbar > i {
    display: none;
  }
  .result-tabs button {
    padding-inline: 9px;
  }
}
</style>
