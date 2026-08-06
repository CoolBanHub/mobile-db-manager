<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { commitDirectTableTransaction, loadDirectTableData } from "@/lib/direct/tableData";
import type { MobileQueryDraft, MobileTableFilter, MobileTableFilterOperator, MobileTableDataResponse, MobileTableSort, MobileTableTarget, MobileTableTransactionChange } from "@/lib/mobileTypes";
import { exportQueryResult, type QueryExportFormat } from "@/lib/queryExport";

const props = defineProps<{
  target: MobileTableTarget;
}>();
const emit = defineEmits<{
  back: [];
  openQuery: [draft: Omit<MobileQueryDraft, "nonce">];
}>();

const response = ref<MobileTableDataResponse | null>(null);
const loading = ref(true);
const error = ref("");
const actionError = ref("");
const pageSize = ref(30);
const filters = ref<MobileTableFilter[]>([]);
const sort = ref<MobileTableSort | null>(null);
const filterColumn = ref("");
const filterOperator = ref<MobileTableFilterOperator>("contains");
const filterValue = ref("");
const filterPanelOpen = ref(false);
const sortPanelOpen = ref(false);
const columnPanelOpen = ref(false);
const exportFormat = ref<QueryExportFormat>("csv");
const exporting = ref(false);
const saving = ref(false);
const interactionStatus = ref("");
const pendingEdits = ref<Record<string, unknown>>({});
const pendingEditOrder = ref<Record<string, number>>({});
const pendingInserts = ref<Array<{ id: string; order: number; row: unknown[]; providedColumns: boolean[] }>>([]);
const pendingDeletes = ref<Array<{ id: string; order: number; pageRowIndex: number; row: unknown[] }>>([]);
const reviewOpen = ref(false);
const transactionConfirmed = ref(false);
const productionConfirmation = ref("");
const editorValue = ref("");
const editorIsNull = ref(false);
const insertOpen = ref(false);
const insertValues = ref<string[]>([]);
const insertNulls = ref<boolean[]>([]);
const insertIncluded = ref<boolean[]>([]);
const deleteCandidate = ref<{ pageRowIndex: number; row: unknown[] } | null>(null);
const columnWidths = ref<Record<number, number>>({});
const selectedCell = ref<{
  rowIndex: number;
  pageRowIndex: number;
  columnIndex: number;
  value: unknown;
} | null>(null);
const filterOperators: { value: MobileTableFilterOperator; label: string; needsValue: boolean }[] = [
  { value: "contains", label: "包含", needsValue: true },
  { value: "equals", label: "等于", needsValue: true },
  { value: "notEquals", label: "不等于", needsValue: true },
  { value: "startsWith", label: "开头是", needsValue: true },
  { value: "endsWith", label: "结尾是", needsValue: true },
  { value: "greaterThan", label: "大于", needsValue: true },
  { value: "greaterThanOrEqual", label: "大于等于", needsValue: true },
  { value: "lessThan", label: "小于", needsValue: true },
  { value: "lessThanOrEqual", label: "小于等于", needsValue: true },
  { value: "isNull", label: "为空", needsValue: false },
  { value: "isNotNull", label: "不为空", needsValue: false },
];
const filterNeedsValue = computed(() => filterOperators.find((item) => item.value === filterOperator.value)?.needsValue ?? true);
const pendingEntries = computed(() => Object.entries(pendingEdits.value));
const pendingCellCount = computed(() => pendingEntries.value.length);
let nextMutationOrder = 1;

type PendingOperation = {
  id: string;
  order: number;
  kind: "insert" | "update" | "delete";
  label: string;
  detail: string;
  change: MobileTableTransactionChange;
};

const pendingOperations = computed<PendingOperation[]>(() => {
  if (!response.value) return [];
  const operations: PendingOperation[] = [];
  const editsByRow = new Map<number, Array<[number, unknown]>>();
  for (const [key, value] of pendingEntries.value) {
    const [rowIndex, columnIndex] = key.split(":").map(Number);
    const edits = editsByRow.get(rowIndex) ?? [];
    edits.push([columnIndex, value]);
    editsByRow.set(rowIndex, edits);
  }
  for (const [rowIndex, edits] of editsByRow) {
    const row = response.value.result.rows[rowIndex];
    if (!row) continue;
    const values = Object.fromEntries(edits.map(([columnIndex, value]) => [response.value!.columnMeta[columnIndex].name, value]));
    operations.push({
      id: `update:${rowIndex}`,
      order: pendingEditOrder.value[String(rowIndex)] ?? 0,
      kind: "update",
      label: `更新第 ${response.value.offset + rowIndex + 1} 行`,
      detail: `${primaryKeySummary(row)} · ${Object.keys(values).join(", ")}`,
      change: { kind: "update", values, primaryKey: primaryKeyValues(row) },
    });
  }
  for (const item of pendingInserts.value) {
    const values = Object.fromEntries(
      response.value.columnMeta
        .map((column, index) => ({ column, index }))
        .filter(({ index }) => item.providedColumns[index])
        .map(({ column, index }) => [column.name, item.row[index]]),
    );
    operations.push({
      id: item.id,
      order: item.order,
      kind: "insert",
      label: "新增 1 行",
      detail: Object.keys(values).length ? Object.keys(values).join(", ") : "全部使用数据库默认值",
      change: { kind: "insert", values },
    });
  }
  for (const item of pendingDeletes.value) {
    operations.push({
      id: item.id,
      order: item.order,
      kind: "delete",
      label: `删除第 ${response.value.offset + item.pageRowIndex + 1} 行`,
      detail: primaryKeySummary(item.row),
      change: { kind: "delete", primaryKey: primaryKeyValues(item.row) },
    });
  }
  return operations.sort((left, right) => left.order - right.order);
});
const pendingCount = computed(() => pendingOperations.value.length);
// 翻页请求用版本号仲裁，较慢的旧请求不能覆盖用户刚切换到的新页。
let requestId = 0;

function handleBack() {
  if (filterPanelOpen.value || sortPanelOpen.value || columnPanelOpen.value) {
    filterPanelOpen.value = false;
    sortPanelOpen.value = false;
    columnPanelOpen.value = false;
    return true;
  }
  if (deleteCandidate.value) {
    deleteCandidate.value = null;
    return true;
  }
  if (reviewOpen.value) {
    reviewOpen.value = false;
    return true;
  }
  if (insertOpen.value) {
    insertOpen.value = false;
    return true;
  }
  if (selectedCell.value) {
    selectedCell.value = null;
    return true;
  }
  return false;
}

defineExpose({ handleBack });

function displayValue(value: unknown) {
  if (value === null) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function actionErrorMessage(action: "新增" | "删除" | "保存" | "复制" | "导出", reason: unknown) {
  const message = reason instanceof Error ? reason.message : "";
  if (/foreign key constraint|referential integrity|a foreign key constraint fails/i.test(message)) {
    return `${action}失败：这行数据仍被其他表引用。请先删除或修改关联数据后再试。`;
  }
  if (/duplicate entry|unique constraint|duplicate key|must be unique/i.test(message)) {
    return `${action}失败：主键或唯一字段的值已存在，请修改后再试。`;
  }
  if (/not null|null value in column|cannot be null/i.test(message)) {
    return `${action}失败：存在未填写的必填字段，请补充后再试。`;
  }
  return message ? `${action}失败：${message}` : `${action}失败，请重试`;
}

async function loadPage(offset: number) {
  const currentRequest = ++requestId;
  loading.value = true;
  error.value = "";
  actionError.value = "";
  try {
    const page = await loadDirectTableData({
      ...props.target,
      offset,
      limit: pageSize.value,
      filters: filters.value,
      sort: sort.value,
    });
    if (currentRequest === requestId) {
      response.value = page;
      selectedCell.value = null;
      insertOpen.value = false;
      deleteCandidate.value = null;
      pendingEdits.value = {};
      pendingEditOrder.value = {};
      pendingInserts.value = [];
      pendingDeletes.value = [];
      reviewOpen.value = false;
      transactionConfirmed.value = false;
      productionConfirmation.value = "";
      interactionStatus.value = "";
      if (offset === 0) columnWidths.value = {};
    }
  } catch (reason) {
    if (currentRequest !== requestId) return;
    error.value = reason instanceof Error ? reason.message : "表数据加载失败";
  } finally {
    if (currentRequest === requestId) {
      loading.value = false;
    }
  }
}

function applyFilter() {
  if (pendingCount.value) {
    interactionStatus.value = "请先保存或撤销当前修改，再调整筛选条件";
    return;
  }
  if (!filterColumn.value || (filterNeedsValue.value && !filterValue.value)) return;
  filters.value.push({
    column: filterColumn.value,
    operator: filterOperator.value,
    value: filterNeedsValue.value ? filterValue.value : "",
  });
  filterValue.value = "";
  filterPanelOpen.value = false;
  void loadPage(0);
}

function toggleControlPanel(panel: "filter" | "sort" | "columns") {
  filterPanelOpen.value = panel === "filter" ? !filterPanelOpen.value : false;
  sortPanelOpen.value = panel === "sort" ? !sortPanelOpen.value : false;
  columnPanelOpen.value = panel === "columns" ? !columnPanelOpen.value : false;
}

function removeFilter(index: number) {
  if (pendingCount.value) {
    interactionStatus.value = "请先保存或撤销当前修改，再调整筛选条件";
    return;
  }
  filters.value.splice(index, 1);
  void loadPage(0);
}

function cycleSort(column: string) {
  if (pendingCount.value) {
    interactionStatus.value = "请先保存或撤销当前修改，再调整排序";
    return;
  }
  if (sort.value?.column !== column) sort.value = { column, direction: "asc" };
  else if (sort.value.direction === "asc") sort.value = { column, direction: "desc" };
  else sort.value = null;
  void loadPage(0);
}

function sortIndicator(column: string) {
  if (sort.value?.column !== column) return "↕";
  return sort.value.direction === "asc" ? "↑" : "↓";
}

function filterSummary(filter: MobileTableFilter) {
  const label = filterOperators.find((item) => item.value === filter.operator)?.label ?? filter.operator;
  return filter.value ? `${filter.column} ${label} ${filter.value}` : `${filter.column} ${label}`;
}

function openQuery() {
  if (!response.value) return;
  emit("openQuery", {
    connectionId: props.target.connectionId,
    database: props.target.database,
    schema: props.target.schema,
    sql: response.value.selectTemplate,
  });
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
    interactionStatus.value = success;
    actionError.value = "";
  } catch (reason) {
    actionError.value = actionErrorMessage("复制", reason);
  }
}

function openCell(pageRowIndex: number, columnIndex: number, value: unknown) {
  if (isRowPendingDelete(pageRowIndex)) {
    interactionStatus.value = "这一行已加入删除队列；请先撤销删除后再编辑";
    return;
  }
  const currentValue = editedValue(pageRowIndex, columnIndex, value);
  selectedCell.value = {
    rowIndex: (response.value?.offset ?? 0) + pageRowIndex,
    pageRowIndex,
    columnIndex,
    value: currentValue,
  };
  editorIsNull.value = currentValue === null;
  editorValue.value = currentValue === null ? "" : cellText(currentValue);
}

function editKey(pageRowIndex: number, columnIndex: number) {
  return `${pageRowIndex}:${columnIndex}`;
}

function editedValue(pageRowIndex: number, columnIndex: number, fallback: unknown) {
  const key = editKey(pageRowIndex, columnIndex);
  return Object.prototype.hasOwnProperty.call(pendingEdits.value, key) ? pendingEdits.value[key] : fallback;
}

function isEdited(pageRowIndex: number, columnIndex: number) {
  return Object.prototype.hasOwnProperty.call(pendingEdits.value, editKey(pageRowIndex, columnIndex));
}

function selectedColumnIsPrimaryKey() {
  if (!selectedCell.value || !response.value) return false;
  return response.value.columnMeta[selectedCell.value.columnIndex]?.is_primary_key ?? false;
}

function isGeneratedColumn(extra: string | null) {
  const value = (extra ?? "").toLowerCase();
  return value === "yes" || value.includes("auto_increment") || value.includes("identity") || value.includes("generated");
}

function openInsertRow() {
  if (!response.value?.editable) {
    interactionStatus.value = "当前表不可新增数据";
    return;
  }
  insertValues.value = response.value.columnMeta.map(() => "");
  insertNulls.value = response.value.columnMeta.map((column) => column.is_nullable);
  insertIncluded.value = response.value.columnMeta.map((column) => !isGeneratedColumn(column.extra) && !column.column_default);
  actionError.value = "";
  insertOpen.value = true;
}

function openDeleteRow(pageRowIndex: number) {
  if (!response.value?.editable) {
    interactionStatus.value = "当前表不可删除数据";
    return;
  }
  if (isRowPendingDelete(pageRowIndex)) {
    interactionStatus.value = "这一行已经在删除队列中";
    return;
  }
  const row = response.value.result.rows[pageRowIndex];
  if (!row) return;
  actionError.value = "";
  deleteCandidate.value = { pageRowIndex, row: [...row] };
}

function deleteSelectedRow() {
  if (!selectedCell.value) return;
  const pageRowIndex = selectedCell.value.pageRowIndex;
  selectedCell.value = null;
  openDeleteRow(pageRowIndex);
}

function primaryKeySummary(row: unknown[]) {
  if (!response.value) return "";
  return response.value.columnMeta
    .map((column, index) => (column.is_primary_key ? `${column.name}=${displayValue(row[index])}` : ""))
    .filter(Boolean)
    .join(" · ");
}

function primaryKeyValues(row: unknown[]) {
  if (!response.value) return {};
  return Object.fromEntries(
    response.value.columnMeta
      .map((column, index) => ({ column, index }))
      .filter(({ column }) => column.is_primary_key)
      .map(({ column, index }) => [column.name, row[index]]),
  );
}

function mutationId(kind: string) {
  return `${kind}:${Date.now()}:${Math.random().toString(36).slice(2)}`;
}

function isRowPendingDelete(pageRowIndex: number) {
  return pendingDeletes.value.some((item) => item.pageRowIndex === pageRowIndex);
}

function insertRow() {
  if (!response.value) return;
  actionError.value = "";
  const row = response.value.columnMeta.map((_, index) => (insertIncluded.value[index] ? (insertNulls.value[index] ? null : insertValues.value[index]) : null));
  pendingInserts.value = [...pendingInserts.value, {
    id: mutationId("insert"),
    order: nextMutationOrder++,
    row,
    providedColumns: [...insertIncluded.value],
  }];
  insertOpen.value = false;
  interactionStatus.value = `新增行已加入事务，当前共 ${pendingCount.value} 项待提交`;
}

function deleteRow() {
  if (!response.value || !deleteCandidate.value) return;
  actionError.value = "";
  const candidate = deleteCandidate.value;
  const rowPrefix = `${candidate.pageRowIndex}:`;
  pendingEdits.value = Object.fromEntries(Object.entries(pendingEdits.value).filter(([key]) => !key.startsWith(rowPrefix)));
  const nextEditOrder = { ...pendingEditOrder.value };
  delete nextEditOrder[String(candidate.pageRowIndex)];
  pendingEditOrder.value = nextEditOrder;
  pendingDeletes.value = [...pendingDeletes.value, {
    id: mutationId("delete"),
    order: nextMutationOrder++,
    pageRowIndex: candidate.pageRowIndex,
    row: candidate.row,
  }];
  deleteCandidate.value = null;
  interactionStatus.value = `删除操作已加入事务，当前共 ${pendingCount.value} 项待提交`;
}

function stageCellEdit() {
  if (!selectedCell.value || !response.value || !response.value.editable || selectedColumnIsPrimaryKey()) return;
  const { pageRowIndex, columnIndex } = selectedCell.value;
  const nextValue: unknown = editorIsNull.value ? null : editorValue.value;
  const originalValue = response.value.result.rows[pageRowIndex]?.[columnIndex];
  const key = editKey(pageRowIndex, columnIndex);
  const unchanged = originalValue === nextValue || (originalValue !== null && cellText(originalValue) === cellText(nextValue));
  // 单元格只暂存在本地，复核后由原生侧按主键放进同一个 JDBC 事务。
  const next = { ...pendingEdits.value };
  if (unchanged) delete next[key];
  else next[key] = nextValue;
  pendingEdits.value = next;
  const rowKey = String(pageRowIndex);
  const nextOrder = { ...pendingEditOrder.value };
  const hasRowEdits = Object.keys(next).some((item) => item.startsWith(`${pageRowIndex}:`));
  if (hasRowEdits && nextOrder[rowKey] === undefined) nextOrder[rowKey] = nextMutationOrder++;
  if (!hasRowEdits) delete nextOrder[rowKey];
  pendingEditOrder.value = nextOrder;
  selectedCell.value = null;
  interactionStatus.value = unchanged ? "修改已撤销" : `修改已加入事务，当前共 ${pendingCount.value} 项待提交`;
}

function discardPendingEdits() {
  pendingEdits.value = {};
  pendingEditOrder.value = {};
  pendingInserts.value = [];
  pendingDeletes.value = [];
  reviewOpen.value = false;
  transactionConfirmed.value = false;
  productionConfirmation.value = "";
  interactionStatus.value = "全部未提交变更已撤销";
}

function openTransactionReview() {
  if (!response.value || !pendingCount.value) return;
  transactionConfirmed.value = false;
  productionConfirmation.value = "";
  actionError.value = "";
  reviewOpen.value = true;
}

function removePendingOperation(operation: PendingOperation) {
  if (operation.kind === "insert") pendingInserts.value = pendingInserts.value.filter((item) => item.id !== operation.id);
  else if (operation.kind === "delete") pendingDeletes.value = pendingDeletes.value.filter((item) => item.id !== operation.id);
  else {
    const rowIndex = operation.id.split(":")[1];
    pendingEdits.value = Object.fromEntries(Object.entries(pendingEdits.value).filter(([key]) => !key.startsWith(`${rowIndex}:`)));
    const nextOrder = { ...pendingEditOrder.value };
    delete nextOrder[rowIndex];
    pendingEditOrder.value = nextOrder;
  }
  if (!pendingCount.value) reviewOpen.value = false;
}

async function commitTransaction() {
  if (!response.value || !pendingCount.value || saving.value || !transactionConfirmed.value) return;
  if (response.value.isProduction && productionConfirmation.value !== response.value.connectionName) {
    actionError.value = "生产连接名称不匹配，事务未提交";
    return;
  }
  saving.value = true;
  actionError.value = "";
  interactionStatus.value = "";
  try {
    const result = await commitDirectTableTransaction({
      ...props.target,
      changes: pendingOperations.value.map((operation) => operation.change),
      productionConfirmation: productionConfirmation.value,
    });
    const savedCount = result.operationCount;
    const offset = response.value.offset;
    pendingEdits.value = {};
    pendingEditOrder.value = {};
    pendingInserts.value = [];
    pendingDeletes.value = [];
    reviewOpen.value = false;
    await loadPage(offset);
    interactionStatus.value = `事务已提交：${savedCount} 项操作，共影响 ${result.affectedRows} 行`;
  } catch (reason) {
    actionError.value = actionErrorMessage("保存", reason).replace("保存失败", "事务已回滚");
  } finally {
    saving.value = false;
  }
}

function copySelectedCell() {
  if (!selectedCell.value) return;
  return copyText(cellText(selectedCell.value.value), "单元格已复制");
}

function copySelectedRow() {
  if (!selectedCell.value || !response.value) return;
  const row = response.value.result.rows[selectedCell.value.pageRowIndex] ?? [];
  return copyText(row.map((value) => cellText(value).replace(/\r?\n/g, " ")).join("\t"), "当前行已复制");
}

function adjustColumn(index: number, delta: number) {
  const current = columnWidths.value[index] ?? 160;
  columnWidths.value = { ...columnWidths.value, [index]: Math.min(480, Math.max(88, current + delta)) };
}

function autoFitColumns() {
  if (!response.value) return;
  columnWidths.value = Object.fromEntries(
    response.value.result.columns.map((column, index) => {
      const widest = Math.max(column.length, ...response.value!.result.rows.map((row) => displayValue(row[index]).length));
      return [index, Math.min(360, Math.max(88, widest * 7 + 28))];
    }),
  );
}

async function sharePage() {
  if (!response.value || exporting.value) return;
  exporting.value = true;
  interactionStatus.value = "";
  actionError.value = "";
  try {
    const receipt = await exportQueryResult(
      {
        result: response.value.result,
        database: props.target.database,
        schema: props.target.schema,
      },
      exportFormat.value,
    );
    interactionStatus.value = receipt.delivery === "share" ? `已打开分享面板 · ${receipt.filename}` : `${receipt.format.toUpperCase()} 已下载 · ${receipt.filename}`;
  } catch (reason) {
    actionError.value = actionErrorMessage("导出", reason);
  } finally {
    exporting.value = false;
  }
}

onMounted(() => loadPage(0));
</script>

<template>
  <section class="table-data">
    <header class="data-toolbar">
      <button class="back" type="button" aria-label="返回表列表" @click="emit('back')">←</button>
      <div>
        <strong
          ><template v-if="target.schema">{{ target.schema }}.</template>{{ target.table }}</strong
        >
        <p>{{ response?.connectionName || target.database }} <em v-if="response?.isProduction">生产</em><span v-if="response && !response.editable">只读</span></p>
      </div>
      <button class="sql-action" :disabled="!response" type="button" aria-label="在查询页打开" @click="openQuery">⋮</button>
    </header>

    <div class="data-controls">
      <div class="data-control-bar">
        <button :class="{ active: filterPanelOpen || filters.length > 0 }" type="button" @click="toggleControlPanel('filter')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16l-6.3 7.1v5.4l-3.4 1.6v-7Z" /></svg>
          过滤<span v-if="filters.length"> ({{ filters.length }})</span><b>⌄</b>
        </button>
        <button :class="{ active: sortPanelOpen || !!sort }" type="button" @click="toggleControlPanel('sort')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4v16m0 0-3-3m3 3 3-3M16 20V4m0 0-3 3m3-3 3 3" /></svg>
          排序<b>⌄</b>
        </button>
        <button :class="{ active: columnPanelOpen }" type="button" @click="toggleControlPanel('columns')">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <rect x="4" y="5" width="16" height="14" rx="1" />
            <path d="M9 5v14m6-14v14" />
          </svg>
          列<b>⌄</b>
        </button>
      </div>
      <form v-if="filterPanelOpen" class="filter-builder" @submit.prevent="applyFilter">
        <select v-model="filterColumn" aria-label="筛选字段" :disabled="!response">
          <option value="">筛选字段</option>
          <option v-for="column in response?.result.columns ?? []" :key="column" :value="column">{{ column }}</option>
        </select>
        <select v-model="filterOperator" aria-label="筛选方式">
          <option v-for="operator in filterOperators" :key="operator.value" :value="operator.value">
            {{ operator.label }}
          </option>
        </select>
        <input v-if="filterNeedsValue" v-model="filterValue" aria-label="筛选值" maxlength="512" placeholder="输入筛选值" />
        <span v-else class="no-value">无需值</span>
        <button :disabled="!filterColumn || (filterNeedsValue && !filterValue) || loading" type="submit">应用</button>
      </form>
      <div v-if="filters.length" class="filter-chips" aria-label="已应用筛选">
        <button v-for="(filter, index) in filters" :key="`${filter.column}-${index}`" type="button" @click="removeFilter(index)">{{ filterSummary(filter) }} ×</button>
      </div>
      <div v-if="sortPanelOpen && response" class="compact-control-panel sort-options" aria-label="排序字段">
        <button v-for="column in response.result.columns" :key="column" :class="{ active: sort?.column === column }" type="button" @click="cycleSort(column)">
          {{ column }} <span>{{ sortIndicator(column) }}</span>
        </button>
      </div>
      <div v-if="columnPanelOpen && response" class="compact-control-panel column-options" aria-label="列宽设置">
        <button class="auto-width" type="button" @click="autoFitColumns">自动适配</button>
        <div v-for="(column, index) in response.result.columns" :key="column">
          <span>{{ column }}</span>
          <button type="button" :aria-label="`缩小 ${column} 列`" @click="adjustColumn(index, -32)">−</button>
          <button type="button" :aria-label="`加宽 ${column} 列`" @click="adjustColumn(index, 32)">＋</button>
        </div>
      </div>
    </div>

    <div v-if="loading" class="data-state">
      <i aria-hidden="true"></i><strong>正在读取表数据</strong>
      <p>服务端只读校验 · 每页 {{ pageSize }} 行</p>
    </div>
    <div v-else-if="error" class="data-state error">
      <b>!</b><strong>读取失败</strong>
      <p>{{ error }}</p>
      <button type="button" @click="loadPage(response?.offset ?? 0)">重试</button>
    </div>
    <template v-else-if="response">
      <div class="data-meta">
        <span>OFFSET {{ response.offset }}</span>
        <span>{{ response.result.rows.length }} ROWS</span>
        <span>{{ response.result.execution_time_ms }} MS</span>
      </div>
      <div class="result-tools">
        <button type="button" @click="autoFitColumns">AUTO WIDTH</button>
        <select v-model="exportFormat" aria-label="表数据导出格式">
          <option value="csv">CSV</option>
          <option value="json">JSON</option>
          <option value="markdown">MARKDOWN</option>
          <option value="xlsx">EXCEL XLSX</option>
        </select>
        <button class="export-action" :disabled="exporting" type="button" @click="sharePage">
          {{ exporting ? "PREPARING…" : "EXPORT ↗" }}
        </button>
      </div>
      <p v-if="actionError && !insertOpen && !deleteCandidate" class="action-error" role="alert">{{ actionError }}</p>
      <p v-if="interactionStatus" class="interaction-status" aria-live="polite">{{ interactionStatus }}</p>
      <p class="data-hint">点击单元格查看或修改内容；主键字段只读。表头 − / + 可调整列宽，导出范围为当前页。</p>
      <div class="data-scroll">
        <table>
          <colgroup>
            <col v-for="(_, index) in response.result.columns" :key="index" :style="{ width: `${columnWidths[index] ?? 120}px` }" />
          </colgroup>
          <thead>
            <tr>
              <th v-for="(column, columnIndex) in response.result.columns" :key="column">
                <div class="column-heading">
                  <button class="sort-action" :disabled="pendingCount > 0" type="button" :aria-label="`按 ${column} 排序`" :class="{ active: sort?.column === column }" @click="cycleSort(column)">
                    {{ column }} <b>{{ sortIndicator(column) }}</b>
                  </button>
                  <span class="width-controls">
                    <button type="button" :aria-label="`缩小 ${column} 列`" @click="adjustColumn(columnIndex, -32)">−</button>
                    <button type="button" :aria-label="`加宽 ${column} 列`" @click="adjustColumn(columnIndex, 32)">＋</button>
                  </span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in response.result.rows" :key="response.offset + rowIndex" :class="{ 'pending-delete-row': isRowPendingDelete(rowIndex) }">
              <td
                v-for="(value, columnIndex) in row"
                :key="columnIndex"
                :class="{
                  null: editedValue(rowIndex, columnIndex, value) === null,
                  edited: isEdited(rowIndex, columnIndex),
                  primary: response.columnMeta[columnIndex]?.is_primary_key,
                }"
                :title="displayValue(editedValue(rowIndex, columnIndex, value))"
                tabindex="0"
                role="button"
                @click="openCell(rowIndex, columnIndex, value)"
                @keydown.enter.prevent="openCell(rowIndex, columnIndex, value)"
              >
                {{ displayValue(editedValue(rowIndex, columnIndex, value)) }}
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="response.result.rows.length === 0" class="empty-data">这一页没有数据。</div>
      </div>
      <footer>
        <div class="page-buttons">
          <button :disabled="response.offset === 0 || loading || pendingCount > 0" type="button" aria-label="第一页" @click="loadPage(0)">|‹</button>
          <button :disabled="response.offset === 0 || loading || pendingCount > 0" type="button" aria-label="上一页" @click="loadPage(Math.max(0, response.offset - response.limit))">‹</button>
        </div>
        <label>
          <span>第 {{ Math.floor(response.offset / response.limit) + 1 }} 页</span>
          <select v-model.number="pageSize" aria-label="每页行数" :disabled="loading || pendingCount > 0" @change="loadPage(0)">
            <option :value="20">20 行</option>
            <option :value="30">30 行</option>
            <option :value="50">50 行</option>
          </select>
        </label>
        <div class="page-buttons next">
          <button :disabled="!response.hasMore || loading || pendingCount > 0" type="button" aria-label="下一页" @click="loadPage(response.offset + response.limit)">›</button>
          <button :disabled="!response.hasMore || loading || pendingCount > 0" type="button" aria-label="继续向后翻页" @click="loadPage(response.offset + response.limit)">›|</button>
        </div>
      </footer>
      <div v-if="response.editable" class="edit-toolbar" :class="{ active: pendingCount > 0 }">
        <div>
          <strong>{{ pendingCount ? `${pendingCount} 项待提交` : "安全事务编辑" }}</strong>
          <small>{{ pendingCellCount ? `${pendingCellCount} 个字段修改 · ` : "" }}失败自动回滚</small>
        </div>
        <button class="insert-row" :disabled="saving" type="button" @click="openInsertRow">＋ 新增行</button>
        <button class="discard-edits" :disabled="!pendingCount || saving" type="button" @click="discardPendingEdits">全部撤销</button>
        <button class="save-edits" :disabled="!pendingCount || saving" type="button" @click="openTransactionReview">
          {{ saving ? "提交中…" : `检查并提交${pendingCount ? ` (${pendingCount})` : ""}` }}
        </button>
      </div>
      <p v-else class="edit-blocked">{{ response.editBlockReason || "当前表不可编辑" }}</p>
      <p class="ordering-note">点击字段名切换升序、降序和默认顺序；存在主键时会追加主键以稳定翻页。</p>
    </template>

    <Teleport to="body">
      <div v-if="reviewOpen && response" class="cell-sheet-backdrop" @click.self="!saving && (reviewOpen = false)">
        <section class="cell-sheet transaction-sheet" role="dialog" aria-modal="true" aria-label="检查并提交事务">
          <header>
            <div>
              <span>SAFE TRANSACTION · {{ pendingCount }} OPERATIONS</span>
              <strong>检查全部待提交变更</strong>
            </div>
            <button :disabled="saving" type="button" aria-label="关闭事务检查" @click="reviewOpen = false">×</button>
          </header>
          <div class="transaction-review">
            <p class="transaction-safety">以下操作将在同一个 JDBC 事务中按顺序执行。任意一项失败或更新/删除没有恰好影响一行，全部操作都会回滚。</p>
            <article v-for="(operation, index) in pendingOperations" :key="operation.id" class="transaction-operation">
              <span :class="operation.kind">{{ index + 1 }} · {{ operation.kind.toUpperCase() }}</span>
              <div><strong>{{ operation.label }}</strong><code>{{ operation.detail }}</code></div>
              <button :disabled="saving" type="button" aria-label="移除此项变更" @click="removePendingOperation(operation)">移除</button>
            </article>
            <label class="transaction-confirm">
              <input v-model="transactionConfirmed" type="checkbox" :disabled="saving" />
              <span>我已检查上述 INSERT、UPDATE、DELETE，确认以一个事务提交</span>
            </label>
            <label v-if="response.isProduction" class="production-transaction-confirm">
              <span>生产连接确认：输入完整连接名称</span>
              <input v-model="productionConfirmation" :placeholder="response.connectionName" autocomplete="off" :disabled="saving" />
            </label>
            <p v-if="actionError" class="mutation-error" role="alert">{{ actionError }}</p>
          </div>
          <footer class="mutation-actions">
            <button :disabled="saving" type="button" @click="reviewOpen = false">继续编辑</button>
            <button
              class="commit-transaction"
              :disabled="saving || !transactionConfirmed || (response.isProduction && productionConfirmation !== response.connectionName)"
              type="button"
              @click="commitTransaction"
            >
              {{ saving ? "正在提交…" : `提交 ${pendingCount} 项变更` }}
            </button>
          </footer>
        </section>
      </div>

      <div v-if="selectedCell && response" class="cell-sheet-backdrop" @click.self="selectedCell = null">
        <section class="cell-sheet" role="dialog" aria-modal="true" aria-label="完整单元格内容">
          <header>
            <div>
              <span>ROW {{ selectedCell.rowIndex + 1 }} / COLUMN {{ selectedCell.columnIndex + 1 }}</span>
              <strong>{{ response.result.columns[selectedCell.columnIndex] }}</strong>
            </div>
            <button type="button" aria-label="关闭单元格详情" @click="selectedCell = null">×</button>
          </header>
          <div v-if="response.editable && !selectedColumnIsPrimaryKey()" class="cell-editor">
            <textarea v-model="editorValue" :disabled="editorIsNull" rows="7" aria-label="单元格新值"></textarea>
            <label>
              <input v-model="editorIsNull" type="checkbox" />
              <span>设为 NULL</span>
            </label>
            <p>修改会加入安全事务队列，检查全部变更并确认后才写入数据库。</p>
          </div>
          <pre v-else>{{ cellText(selectedCell.value) }}</pre>
          <p v-if="selectedColumnIsPrimaryKey()" class="cell-lock-note">主键字段用于定位行，为避免误更新不可直接修改。</p>
          <footer :class="{ 'has-delete': response.editable, editable: response.editable && !selectedColumnIsPrimaryKey() }">
            <button v-if="response.editable" class="delete-cell-row" type="button" @click="deleteSelectedRow">删除行</button>
            <button type="button" @click="copySelectedRow">复制整行</button>
            <button type="button" @click="copySelectedCell">复制单元格</button>
            <button v-if="response.editable && !selectedColumnIsPrimaryKey()" class="stage-edit" type="button" @click="stageCellEdit">应用修改</button>
          </footer>
        </section>
      </div>

      <div v-if="insertOpen && response" class="cell-sheet-backdrop" @click.self="insertOpen = false">
        <section class="cell-sheet row-mutation-sheet" role="dialog" aria-modal="true" aria-label="新增数据">
          <header>
            <div>
              <span>INSERT ROW</span>
              <strong>新增数据</strong>
            </div>
            <button type="button" aria-label="关闭新增数据" @click="insertOpen = false">×</button>
          </header>
          <div class="new-row-form">
            <p v-if="actionError" class="mutation-error" role="alert">{{ actionError }}</p>
            <div v-for="(column, index) in response.columnMeta" :key="column.name" class="new-row-field">
              <div class="new-row-field-heading">
                <div>
                  <strong>{{ column.name }}</strong>
                  <small>{{ column.data_type }}</small>
                </div>
                <label>
                  <input v-model="insertIncluded[index]" type="checkbox" :disabled="isGeneratedColumn(column.extra)" />
                  <span>写入</span>
                </label>
              </div>
              <input v-model="insertValues[index]" :aria-label="`${column.name} 的值`" :disabled="!insertIncluded[index] || insertNulls[index]" :placeholder="column.column_default ? `默认值：${column.column_default}` : `输入 ${column.name}`" />
              <div class="new-row-field-options">
                <label v-if="column.is_nullable">
                  <input v-model="insertNulls[index]" type="checkbox" :disabled="!insertIncluded[index]" />
                  <span>NULL</span>
                </label>
                <em v-if="!insertIncluded[index]">
                  {{ isGeneratedColumn(column.extra) ? "数据库自动生成" : column.column_default ? "使用默认值" : "不写入此字段" }}
                </em>
              </div>
            </div>
          </div>
          <footer class="mutation-actions">
            <button type="button" @click="insertOpen = false">取消</button>
            <button class="stage-edit" type="button" @click="insertRow">
              加入事务
            </button>
          </footer>
        </section>
      </div>

      <div v-if="deleteCandidate && response" class="cell-sheet-backdrop" @click.self="deleteCandidate = null">
        <section class="cell-sheet delete-sheet" role="alertdialog" aria-modal="true" aria-label="删除数据">
          <header>
            <div>
              <span>DELETE ROW</span>
              <strong>删除数据</strong>
            </div>
            <button type="button" aria-label="关闭删除确认" @click="deleteCandidate = null">×</button>
          </header>
          <div class="delete-confirmation">
            <strong>将这一行加入删除队列？</strong>
            <code>{{ primaryKeySummary(deleteCandidate.row) }}</code>
            <p>这里只暂存删除操作；提交前仍可在事务检查页移除。原生侧将使用主键精确定位。</p>
            <p v-if="actionError" class="mutation-error" role="alert">{{ actionError }}</p>
          </div>
          <footer class="mutation-actions">
            <button type="button" @click="deleteCandidate = null">取消</button>
            <button class="danger-action" type="button" @click="deleteRow">
              加入删除队列
            </button>
          </footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.table-data {
  display: grid;
  gap: 7px;
}
.data-toolbar {
  display: grid;
  min-height: 58px;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: stretch;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.data-toolbar button {
  border: 0;
  background: transparent;
  color: var(--acid);
  font: inherit;
}
.data-toolbar .back {
  border-right: 0;
  font-size: 18px;
}
.data-toolbar .sql-action {
  border-left: 0;
  padding: 0 12px;
  font-size: 20px;
  font-weight: 760;
  letter-spacing: 0.1em;
}
.data-toolbar > div {
  min-width: 0;
  padding: 8px 12px;
}
.data-toolbar strong {
  display: block;
  overflow: hidden;
  margin-top: 3px;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.data-toolbar p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.data-toolbar p em,
.data-toolbar p span {
  display: inline-block;
  margin-left: 6px;
  border: 1px solid color-mix(in srgb, var(--danger) 55%, var(--line));
  border-radius: 4px;
  padding: 1px 4px;
  color: var(--danger);
  font-size: 7px;
  font-style: normal;
}
.data-toolbar p span {
  border-color: color-mix(in srgb, var(--acid) 45%, var(--line));
  color: var(--acid);
}
.data-controls {
  display: grid;
  gap: 7px;
  background: transparent;
}
.data-control-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 7px;
}
.data-control-bar button {
  display: flex;
  min-width: 0;
  min-height: 35px;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  padding: 0 11px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.data-control-bar button.active {
  border-color: color-mix(in srgb, var(--acid) 45%, var(--line));
  color: var(--acid);
}
.data-control-bar svg {
  width: 16px;
  height: 16px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.data-control-bar b {
  margin-left: auto;
  color: var(--faint);
  font-weight: 500;
}
.compact-control-panel {
  display: flex;
  overflow-x: auto;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  padding: 8px;
  scrollbar-width: none;
}
.compact-control-panel > button {
  flex: none;
  min-height: 32px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--field);
  padding: 0 10px;
  color: var(--muted);
  font: inherit;
  font-size: 9px;
}
.compact-control-panel > button.active {
  border-color: var(--acid);
  color: var(--acid);
}
.compact-control-panel > button span {
  margin-left: 5px;
}
.column-options {
  display: grid;
  overflow: visible;
}
.column-options > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px 34px;
  align-items: center;
}
.column-options > div > span {
  overflow: hidden;
  color: var(--muted);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-options > div > button {
  min-height: 30px;
  border: 1px solid var(--line);
  background: var(--field);
  color: var(--ink);
}
.column-options .auto-width {
  width: 100%;
}
.filter-builder {
  display: grid;
  grid-template-columns: minmax(82px, 1fr) minmax(76px, 0.8fr) minmax(100px, 1.2fr) auto;
  gap: 6px;
}
.filter-builder select,
.filter-builder input,
footer select {
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--field);
  padding: 8px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.filter-builder button {
  border: 1px solid var(--acid);
  border-radius: 6px;
  background: var(--accent-soft);
  padding: 0 10px;
  color: var(--acid);
  font: inherit;
  font-size: 10px;
}
.filter-builder .no-value {
  display: grid;
  place-items: center;
  border: 1px dashed var(--line);
  color: var(--faint);
  font-size: 8px;
}
.filter-chips {
  display: flex;
  gap: 5px;
  overflow-x: auto;
}
.filter-chips button {
  flex: none;
  border: 1px solid rgba(255, 187, 61, 0.4);
  background: transparent;
  padding: 6px 8px;
  color: var(--amber);
  font: inherit;
  font-size: 7px;
}
.data-state {
  min-height: 210px;
  border: 1px dashed rgba(235, 242, 232, 0.16);
  padding: 34px 22px;
}
.data-state i {
  display: block;
  width: 24px;
  height: 24px;
  border: 2px solid var(--line);
  border-top-color: var(--acid);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.data-state b {
  color: var(--danger);
  font-size: 28px;
}
.data-state strong {
  display: block;
  margin-top: 18px;
  font-size: 14px;
}
.data-state p {
  margin: 8px 0 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.6;
}
.data-state button {
  margin-top: 16px;
  border: 1px solid var(--line);
  background: transparent;
  padding: 9px 12px;
  color: var(--acid);
}
.data-meta {
  display: none;
  gap: 14px;
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 9px 11px;
  color: var(--acid);
  font-size: 9px;
  letter-spacing: 0.1em;
}
.result-tools {
  display: none;
  grid-template-columns: 1fr auto 1fr;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--panel);
}
.result-tools button,
.result-tools select {
  min-height: 40px;
  border: 0;
  border-radius: 0;
  background: transparent;
  padding: 0 9px;
  color: var(--acid);
  font: inherit;
  font-size: 9px;
}
.result-tools select {
  border-right: 1px solid var(--line);
  border-left: 1px solid var(--line);
}
.result-tools button:first-child {
  color: var(--muted);
}
.result-tools .export-action {
  background: var(--accent-soft);
  font-weight: 720;
}
.interaction-status,
.data-hint {
  margin: 0;
  border: 1px solid var(--line);
  padding: 8px 10px;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.data-hint {
  display: none;
}
.action-error,
.mutation-error {
  margin: 0;
  border: 1px solid #fecaca;
  border-left: 3px solid #dc2626;
  background: #fef2f2;
  padding: 9px 11px;
  color: #991b1b;
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.mutation-error {
  grid-column: 1 / -1;
}
.data-scroll {
  max-height: 57vh;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--panel);
}
table {
  table-layout: fixed;
  min-width: 100%;
  width: max-content;
  border-collapse: collapse;
  white-space: nowrap;
  font-size: 9px;
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
  top: 0;
  z-index: 1;
  background: var(--field);
  color: var(--ink);
}
.column-heading {
  display: flex;
  min-height: 34px;
  align-items: stretch;
  justify-content: space-between;
}
.sort-action {
  overflow: hidden;
  flex: 1;
  border: 0;
  background: transparent;
  padding: 8px 8px;
  color: var(--ink);
  font: inherit;
  font-weight: 700;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sort-action b {
  color: var(--faint);
  font-weight: 400;
}
.sort-action.active b {
  color: var(--amber);
}
.width-controls {
  display: none;
}
.width-controls button {
  width: 27px;
  border: 0;
  border-left: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.025);
  color: var(--muted);
  font: inherit;
}
td {
  max-width: 480px;
  height: 34px;
  padding: 7px 8px;
  cursor: pointer;
}
td:active,
td:focus {
  outline: 0;
  background: var(--accent-soft);
  color: var(--acid);
}
td.null {
  color: var(--faint);
  font-style: italic;
}
td.primary {
  background: var(--field);
}
td.edited {
  position: relative;
  background: #fff7ed;
  color: #9a3412;
}
td.edited::before {
  position: absolute;
  top: 0;
  left: 0;
  border-top: 8px solid #f97316;
  border-right: 8px solid transparent;
  content: "";
}
.empty-data {
  padding: 30px 14px;
  color: var(--muted);
  font-size: 9px;
  text-align: center;
}
footer {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  border: 1px solid var(--line);
}
.table-data > footer {
  min-height: 43px;
  grid-template-columns: auto minmax(0, 1fr) auto;
  border-radius: 5px;
}
.page-buttons {
  display: flex;
  gap: 5px;
  padding: 4px 6px;
}
.page-buttons.next {
  justify-content: flex-end;
}
.page-buttons button {
  width: 31px;
  min-height: 31px;
  border: 1px solid var(--line) !important;
  border-radius: 5px;
  color: var(--ink);
}
footer button {
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--acid);
  font: inherit;
  font-size: 10px;
}
footer button:first-child {
  border-right: 1px solid var(--line);
}
footer button:last-child {
  border-left: 1px solid var(--line);
}
footer label {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 7px;
}
footer span {
  color: var(--muted);
  font-size: 10px;
  white-space: nowrap;
}
footer select {
  padding: 5px;
}
button:disabled {
  opacity: 0.4;
}
.ordering-note {
  display: none;
  margin: 0;
  color: var(--faint);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.55;
}
.cell-sheet-backdrop {
  position: fixed;
  z-index: 50;
  inset: 0;
  display: grid;
  align-items: end;
  overflow: hidden;
  padding-top: var(--safe-top);
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(4px);
}
.cell-sheet {
  display: flex;
  width: min(720px, 100%);
  max-height: min(72dvh, 560px);
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  margin: 0 auto;
  border: 1px solid color-mix(in srgb, var(--acid) 45%, var(--line));
  border-bottom: 0;
  background: var(--panel-raised);
  box-shadow: 0 -24px 70px rgba(0, 0, 0, 0.68);
}
.cell-sheet > header {
  display: flex;
  flex: 0 0 auto;
  min-height: 58px;
  align-items: stretch;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding-left: 14px;
}
.cell-sheet > header div {
  display: grid;
  min-width: 0;
  align-content: center;
  gap: 5px;
}
.cell-sheet > header span {
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.14em;
}
.cell-sheet > header strong {
  overflow: hidden;
  color: var(--acid);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell-sheet > header button {
  width: 58px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font-size: 24px;
}
.cell-sheet pre {
  flex: 1 1 auto;
  overflow: auto;
  max-height: none;
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
.cell-sheet > footer {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: 1fr 1fr;
  border: 0;
  border-top: 1px solid var(--line);
  background: var(--panel);
  padding-bottom: var(--safe-bottom);
}
.cell-sheet > footer button {
  min-height: 48px;
  border: 0;
  border-right: 1px solid var(--line);
  background: rgba(199, 255, 61, 0.07);
  color: var(--acid);
  font: inherit;
  font-size: 10px;
}
.edit-toolbar {
  position: sticky;
  z-index: 8;
  bottom: var(--safe-bottom);
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  align-items: stretch;
  border: 0;
  border-radius: 0;
  background: var(--panel);
  padding: 8px 0;
  box-shadow: 0 -10px 24px color-mix(in srgb, var(--panel) 88%, transparent);
}
.edit-toolbar.active {
  border-color: #fdba74;
  box-shadow: inset 3px 0 0 #f97316;
}
.edit-toolbar > div {
  display: none;
}
.edit-toolbar strong,
.edit-toolbar small {
  display: block;
}
.edit-toolbar strong {
  font-size: 11px;
}
.edit-toolbar small {
  margin-top: 4px;
  color: var(--muted);
  font-size: 9px;
}
.edit-toolbar input {
  min-width: 0;
  border: 0;
  border-left: 1px solid var(--line);
  outline: 0;
  background: color-mix(in srgb, var(--amber) 10%, var(--panel));
  padding: 0 10px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.edit-toolbar button {
  min-width: 0;
  min-height: 48px;
  border: 1px solid var(--acid);
  border-radius: 5px;
  background: transparent;
  color: var(--acid);
  font: inherit;
  font-size: 10px;
}
.edit-toolbar .insert-row {
  min-width: 0;
  color: var(--acid);
  font-weight: 720;
}
.edit-toolbar .save-edits {
  min-width: 0;
  border-color: var(--acid);
  background: var(--acid);
  color: #ffffff;
  font-weight: 720;
}
.edit-toolbar .discard-edits {
  border-color: var(--line);
  color: var(--muted);
}
.pending-delete-row td {
  background: color-mix(in srgb, var(--danger) 8%, var(--panel));
  color: var(--muted);
  text-decoration: line-through;
  opacity: 0.72;
}
.transaction-sheet {
  max-height: min(88dvh, 760px);
}
.transaction-review {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  gap: 8px;
  overflow-y: auto;
  padding: 12px;
  background: var(--field);
}
.transaction-safety {
  margin: 0;
  border: 1px solid color-mix(in srgb, var(--acid) 35%, var(--line));
  background: var(--accent-soft);
  padding: 10px;
  color: var(--ink);
  font-size: 9px;
  line-height: 1.6;
}
.transaction-operation {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) 44px;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 9px;
}
.transaction-operation > span {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 5px;
  color: var(--acid);
  font-size: 7px;
  text-align: center;
}
.transaction-operation > span.delete { color: var(--danger); }
.transaction-operation > span.insert { color: #0f766e; }
.transaction-operation div { min-width: 0; }
.transaction-operation strong,
.transaction-operation code {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.transaction-operation strong { font-size: 9px; }
.transaction-operation code { margin-top: 4px; color: var(--muted); font-size: 7px; }
.transaction-operation button {
  border: 0;
  background: transparent;
  color: var(--danger);
  font: inherit;
  font-size: 8px;
}
.transaction-confirm,
.production-transaction-confirm {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 11px;
  color: var(--ink);
  font-size: 9px;
  line-height: 1.5;
}
.transaction-confirm {
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
}
.transaction-confirm input { margin-top: 2px; accent-color: var(--acid); }
.production-transaction-confirm input {
  min-height: 38px;
  border: 1px solid color-mix(in srgb, var(--danger) 40%, var(--line));
  outline: 0;
  background: #ffffff;
  padding: 0 9px;
  color: var(--ink);
  font: inherit;
}
.transaction-review .mutation-error {
  margin: 0;
  color: var(--danger);
  font-size: 9px;
}
.cell-sheet > footer .commit-transaction {
  background: var(--acid);
  color: #ffffff;
  font-weight: 720;
}
.edit-blocked {
  margin: 0;
  border: 1px solid #fed7aa;
  border-left: 3px solid var(--amber);
  background: color-mix(in srgb, var(--amber) 9%, var(--panel));
  padding: 9px 11px;
  color: var(--amber);
  font-size: 10px;
}
.cell-editor {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  gap: 9px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 14px;
}
.cell-editor textarea {
  width: 100%;
  min-height: 132px;
  resize: vertical;
  border: 1px solid var(--line);
  border-radius: 0;
  outline: 0;
  background: #f8fafc;
  padding: 12px;
  color: var(--ink);
  font:
    12px/1.6 "Azeret Mono Variable",
    monospace;
}
.cell-editor textarea:focus {
  border-color: var(--acid);
  box-shadow: inset 3px 0 0 var(--acid);
}
.cell-editor label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
  font-size: 10px;
}
.cell-editor input {
  accent-color: var(--acid);
}
.cell-editor p,
.cell-lock-note {
  margin: 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.55;
}
.cell-lock-note {
  border-top: 1px solid var(--line);
  padding: 10px 14px;
}
.cell-sheet > footer.editable {
  grid-template-columns: 0.8fr 1fr 1fr 1.2fr;
}
.cell-sheet > footer.has-delete:not(.editable) {
  grid-template-columns: 0.8fr 1fr 1fr;
}
.cell-sheet > footer .delete-cell-row {
  background: color-mix(in srgb, var(--danger) 8%, var(--panel));
  color: var(--danger);
}
.cell-sheet > footer .stage-edit {
  background: var(--acid);
  color: #ffffff;
  font-weight: 720;
}
.row-action-col {
  width: 56px;
}
.row-action-heading {
  z-index: 2;
  width: 56px;
  background: var(--accent-soft);
}
.row-action-heading > button {
  width: 100%;
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--acid);
  font: inherit;
  font-size: 16px;
  font-weight: 720;
}
.row-action-cell {
  width: 56px;
  padding: 0;
  background: var(--panel);
}
.delete-row {
  width: 100%;
  min-height: 42px;
  border: 0;
  background: transparent;
  color: #dc2626;
  font: inherit;
  font-size: 9px;
}
.row-mutation-sheet {
  max-height: min(84dvh, 720px);
}
.new-row-form {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  max-height: none;
  gap: 8px;
  overflow-y: auto;
  padding: 12px;
  background: var(--field);
}
.new-row-field {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 10px;
}
.new-row-field-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.new-row-field-heading > div {
  min-width: 0;
}
.new-row-field-heading strong,
.new-row-field-heading small {
  display: block;
}
.new-row-field-heading strong {
  overflow: hidden;
  color: var(--ink);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.new-row-field-heading small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 8px;
}
.new-row-field-heading label,
.new-row-field-options label {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--muted);
  font-size: 9px;
}
.new-row-field input[type="text"],
.new-row-field > input:not([type]) {
  min-height: 38px;
  border: 1px solid var(--line);
  border-radius: 0;
  outline: 0;
  background: #ffffff;
  padding: 0 9px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.new-row-field > input:focus {
  border-color: var(--acid);
  box-shadow: inset 3px 0 0 var(--acid);
}
.new-row-field > input:disabled {
  background: #f1f5f9;
  color: var(--faint);
}
.new-row-field input[type="checkbox"] {
  accent-color: var(--acid);
}
.new-row-field-options {
  display: flex;
  min-height: 16px;
  align-items: center;
  justify-content: space-between;
}
.new-row-field-options em {
  color: var(--faint);
  font-size: 8px;
  font-style: normal;
}
.delete-confirmation {
  display: grid;
  gap: 12px;
  padding: 22px 16px;
}
.delete-confirmation > strong {
  color: #991b1b;
  font-size: 14px;
}
.delete-confirmation code {
  overflow-x: auto;
  border: 1px solid #fecaca;
  background: #fef2f2;
  padding: 11px;
  color: #991b1b;
  font-size: 10px;
  white-space: nowrap;
}
.delete-confirmation p {
  margin: 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
}
.delete-confirmation .mutation-error {
  color: #991b1b;
}
.cell-sheet > footer.mutation-actions {
  grid-template-columns: 1fr 1.2fr;
}
.cell-sheet > footer .danger-action {
  background: #dc2626;
  color: #ffffff;
  font-weight: 720;
}
@media (max-width: 560px) {
  .filter-builder {
    grid-template-columns: 1fr 1fr auto;
  }
  .filter-builder input,
  .filter-builder .no-value {
    grid-column: 1 / 3;
  }
  .filter-builder button {
    grid-column: 3;
    grid-row: 1 / 3;
  }
  .edit-toolbar {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-height: 600px) {
  .cell-sheet,
  .row-mutation-sheet {
    max-height: calc(100dvh - var(--safe-top));
  }
  .cell-editor textarea {
    min-height: 96px;
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
