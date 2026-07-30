<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { exportQueryResult, type QueryExportFormat } from "../lib/queryExport";
import {
  deleteDirectTableRow,
  insertDirectTableRow,
  loadDirectTableData,
  updateDirectTableCell,
} from "../lib/directDatabase";
import type {
  MobileQueryDraft,
  MobileTableFilter,
  MobileTableFilterOperator,
  MobileTableDataResponse,
  MobileTableSort,
  MobileTableTarget,
} from "../lib/mobileTypes";

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
const exportFormat = ref<QueryExportFormat>("csv");
const exporting = ref(false);
const saving = ref(false);
const inserting = ref(false);
const deleting = ref(false);
const interactionStatus = ref("");
const productionConfirmation = ref("");
const pendingEdits = ref<Record<string, unknown>>({});
const editorValue = ref("");
const editorIsNull = ref(false);
const insertOpen = ref(false);
const insertValues = ref<string[]>([]);
const insertNulls = ref<boolean[]>([]);
const insertIncluded = ref<boolean[]>([]);
const deleteCandidate = ref<{ pageRowIndex: number; row: unknown[] } | null>(null);
const rowMutationConfirmation = ref("");
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
const pendingCount = computed(() => pendingEntries.value.length);
let requestId = 0;

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
  void loadPage(0);
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
  if (!response.value?.editable || pendingCount.value) {
    interactionStatus.value = pendingCount.value ? "请先保存或撤销当前修改，再新增数据" : "当前表不可新增数据";
    return;
  }
  insertValues.value = response.value.columnMeta.map(() => "");
  insertNulls.value = response.value.columnMeta.map((column) => column.is_nullable);
  insertIncluded.value = response.value.columnMeta.map((column) => !isGeneratedColumn(column.extra) && !column.column_default);
  rowMutationConfirmation.value = "";
  actionError.value = "";
  insertOpen.value = true;
}

function openDeleteRow(pageRowIndex: number) {
  if (!response.value?.editable || pendingCount.value) {
    interactionStatus.value = pendingCount.value ? "请先保存或撤销当前修改，再删除数据" : "当前表不可删除数据";
    return;
  }
  const row = response.value.result.rows[pageRowIndex];
  if (!row) return;
  rowMutationConfirmation.value = "";
  actionError.value = "";
  deleteCandidate.value = { pageRowIndex, row: [...row] };
}

function mutationConfirmationValid() {
  return !response.value?.isProduction || rowMutationConfirmation.value.trim() === response.value.connectionName;
}

function primaryKeySummary(row: unknown[]) {
  if (!response.value) return "";
  return response.value.columnMeta
    .map((column, index) => (column.is_primary_key ? `${column.name}=${displayValue(row[index])}` : ""))
    .filter(Boolean)
    .join(" · ");
}

async function insertRow() {
  if (!response.value || inserting.value || !mutationConfirmationValid()) return;
  inserting.value = true;
  actionError.value = "";
  try {
    const row = response.value.columnMeta.map((_, index) => (insertIncluded.value[index] ? (insertNulls.value[index] ? null : insertValues.value[index]) : null));
    const result = await insertDirectTableRow({
        ...props.target,
        row,
        providedColumns: insertIncluded.value,
        productionConfirmation: rowMutationConfirmation.value || null,
    });
    if (result.affectedRows !== 1) throw new Error(`新增操作影响了 ${result.affectedRows} 行`);
    insertOpen.value = false;
    await loadPage(0);
    interactionStatus.value = "已新增 1 行数据";
  } catch (reason) {
    actionError.value = actionErrorMessage("新增", reason);
  } finally {
    inserting.value = false;
  }
}

async function deleteRow() {
  if (!response.value || !deleteCandidate.value || deleting.value || !mutationConfirmationValid()) return;
  deleting.value = true;
  actionError.value = "";
  try {
    const result = await deleteDirectTableRow({
        ...props.target,
        originalRow: deleteCandidate.value.row,
        productionConfirmation: rowMutationConfirmation.value || null,
    });
    if (result.affectedRows !== 1) {
      throw new Error(result.affectedRows === 0 ? "目标行已变化或不存在，请刷新后重试" : `删除操作影响了 ${result.affectedRows} 行`);
    }
    const offset = response.value.offset;
    deleteCandidate.value = null;
    await loadPage(offset);
    interactionStatus.value = "已删除 1 行数据";
  } catch (reason) {
    actionError.value = actionErrorMessage("删除", reason);
  } finally {
    deleting.value = false;
  }
}

function stageCellEdit() {
  if (!selectedCell.value || !response.value || !response.value.editable || selectedColumnIsPrimaryKey()) return;
  const { pageRowIndex, columnIndex } = selectedCell.value;
  const nextValue: unknown = editorIsNull.value ? null : editorValue.value;
  const originalValue = response.value.result.rows[pageRowIndex]?.[columnIndex];
  const key = editKey(pageRowIndex, columnIndex);
  const unchanged = originalValue === nextValue || (originalValue !== null && cellText(originalValue) === cellText(nextValue));
  const next = { ...pendingEdits.value };
  if (unchanged) delete next[key];
  else next[key] = nextValue;
  pendingEdits.value = next;
  selectedCell.value = null;
  interactionStatus.value = unchanged ? "修改已撤销" : `已暂存 ${pendingCount.value} 处修改，点击保存后写入数据库`;
}

function discardPendingEdits() {
  pendingEdits.value = {};
  productionConfirmation.value = "";
  interactionStatus.value = "未保存的修改已撤销";
}

async function savePendingEdits() {
  if (!response.value || !pendingCount.value || saving.value) return;
  if (response.value.isProduction && productionConfirmation.value.trim() !== response.value.connectionName) {
    actionError.value = `生产连接请输入完整连接名“${response.value.connectionName}”`;
    return;
  }
  saving.value = true;
  actionError.value = "";
  interactionStatus.value = "";
  try {
    for (const [key, value] of pendingEntries.value) {
      const [pageRowIndex, columnIndex] = key.split(":").map(Number);
      const row = response.value.result.rows[pageRowIndex];
      const column = response.value.result.columns[columnIndex];
      if (!row || column === undefined) {
        throw new Error(`暂存修改的位置已失效（第 ${pageRowIndex + 1} 行，第 ${columnIndex + 1} 列），请刷新后重试`);
      }
      const result = await updateDirectTableCell({
          ...props.target,
          column,
          originalRow: row,
          value,
          productionConfirmation: productionConfirmation.value || null,
      });
      if (result.affectedRows !== 1) {
        throw new Error(result.affectedRows === 0 ? "目标行已变化或不存在，请刷新后重试" : "更新影响了多行，已停止后续保存");
      }
    }
    const savedCount = pendingCount.value;
    await loadPage(response.value.offset);
    interactionStatus.value = `已保存 ${savedCount} 处修改`;
  } catch (reason) {
    actionError.value = actionErrorMessage("保存", reason);
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
        <span>{{ response?.editable ? "EDITABLE TABLE DATA" : "TABLE DATA" }}</span>
        <strong>{{ target.table }}</strong>
        <p>
          {{ target.database }}<template v-if="target.schema"> / {{ target.schema }}</template>
        </p>
      </div>
      <button class="sql-action" :disabled="!response" type="button" @click="openQuery">SQL ↗</button>
    </header>

    <div class="data-controls">
      <form class="filter-builder" @submit.prevent="applyFilter">
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
      <div v-if="response.editable" class="edit-toolbar" :class="{ active: pendingCount > 0 }">
        <div>
          <strong>{{ pendingCount ? `${pendingCount} 处待保存` : "点击单元格编辑数据" }}</strong>
          <small>按主键精确写入 · 新增、修改或删除后自动刷新</small>
        </div>
        <input v-if="response.isProduction && pendingCount" v-model="productionConfirmation" :placeholder="`输入 ${response.connectionName} 确认`" :aria-label="`输入连接名 ${response.connectionName} 确认生产写入`" />
        <button class="insert-row" :disabled="pendingCount > 0 || saving || inserting || deleting" type="button" @click="openInsertRow">＋ 新增</button>
        <button :disabled="!pendingCount || saving" type="button" @click="discardPendingEdits">撤销</button>
        <button class="save-edits" :disabled="!pendingCount || saving" type="button" @click="savePendingEdits">
          {{ saving ? "保存中…" : "保存变更" }}
        </button>
      </div>
      <p v-else class="edit-blocked">{{ response.editBlockReason || "当前表不可编辑" }}</p>
      <p v-if="actionError && !insertOpen && !deleteCandidate" class="action-error" role="alert">{{ actionError }}</p>
      <p v-if="interactionStatus" class="interaction-status" aria-live="polite">{{ interactionStatus }}</p>
      <p class="data-hint">点击单元格查看或修改内容；主键字段只读。表头 − / + 可调整列宽，导出范围为当前页。</p>
      <div class="data-scroll">
        <table>
          <colgroup>
            <col class="row-action-col" />
            <col v-for="(_, index) in response.result.columns" :key="index" :style="{ width: `${columnWidths[index] ?? 160}px` }" />
          </colgroup>
          <thead>
            <tr>
              <th class="row-action-heading">
                <button :disabled="pendingCount > 0 || saving || inserting || deleting" type="button" aria-label="新增数据" @click="openInsertRow">＋</button>
              </th>
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
            <tr v-for="(row, rowIndex) in response.result.rows" :key="response.offset + rowIndex">
              <td class="row-action-cell">
                <button class="delete-row" :disabled="pendingCount > 0 || saving || inserting || deleting" type="button" :aria-label="`删除第 ${rowIndex + 1} 行`" @click.stop="openDeleteRow(rowIndex)">删除</button>
              </td>
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
        <button :disabled="response.offset === 0 || loading || pendingCount > 0" type="button" @click="loadPage(Math.max(0, response.offset - response.limit))">← 上一页</button>
        <label>
          <span>第 {{ Math.floor(response.offset / response.limit) + 1 }} 页</span>
          <select v-model.number="pageSize" aria-label="每页行数" :disabled="loading || pendingCount > 0" @change="loadPage(0)">
            <option :value="20">20 行</option>
            <option :value="30">30 行</option>
            <option :value="50">50 行</option>
          </select>
        </label>
        <button :disabled="!response.hasMore || loading || pendingCount > 0" type="button" @click="loadPage(response.offset + response.limit)">下一页 →</button>
      </footer>
      <p class="ordering-note">点击字段名切换升序、降序和默认顺序；存在主键时会追加主键以稳定翻页。</p>
    </template>

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
          <p>修改会先暂存在当前页面，点击“保存变更”后才写入数据库。</p>
        </div>
        <pre v-else>{{ cellText(selectedCell.value) }}</pre>
        <p v-if="selectedColumnIsPrimaryKey()" class="cell-lock-note">主键字段用于定位行，为避免误更新不可直接修改。</p>
        <footer :class="{ editable: response.editable && !selectedColumnIsPrimaryKey() }">
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
          <input v-if="response.isProduction" v-model="rowMutationConfirmation" class="production-confirmation" :placeholder="`生产连接：输入 ${response.connectionName} 确认`" :aria-label="`输入连接名 ${response.connectionName} 确认新增`" />
        </div>
        <footer class="mutation-actions">
          <button type="button" @click="insertOpen = false">取消</button>
          <button class="stage-edit" :disabled="inserting || !mutationConfirmationValid()" type="button" @click="insertRow">
            {{ inserting ? "新增中…" : "确认新增" }}
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
          <strong>确认永久删除这一行？</strong>
          <code>{{ primaryKeySummary(deleteCandidate.row) }}</code>
          <p>系统会使用主键精确定位。删除操作无法撤销。</p>
          <p v-if="actionError" class="mutation-error" role="alert">{{ actionError }}</p>
          <input v-if="response.isProduction" v-model="rowMutationConfirmation" class="production-confirmation" :placeholder="`生产连接：输入 ${response.connectionName} 确认`" :aria-label="`输入连接名 ${response.connectionName} 确认删除`" />
        </div>
        <footer class="mutation-actions">
          <button type="button" @click="deleteCandidate = null">取消</button>
          <button class="danger-action" :disabled="deleting || !mutationConfirmationValid()" type="button" @click="deleteRow">
            {{ deleting ? "删除中…" : "确认删除" }}
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.table-data {
  display: grid;
  gap: 10px;
}
.data-toolbar {
  display: grid;
  min-height: 68px;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: stretch;
  border: 1px solid var(--line);
  border-top: 2px solid var(--acid);
  background: var(--panel);
}
.data-toolbar button {
  border: 0;
  background: transparent;
  color: var(--acid);
  font: inherit;
}
.data-toolbar .back {
  border-right: 1px solid var(--line);
  font-size: 16px;
}
.data-toolbar .sql-action {
  border-left: 1px solid var(--line);
  padding: 0 12px;
  font-size: 10px;
  font-weight: 760;
  letter-spacing: 0.1em;
}
.data-toolbar > div {
  min-width: 0;
  padding: 10px 12px;
}
.data-toolbar span {
  color: var(--acid);
  font-size: 9px;
  letter-spacing: 0.14em;
}
.data-toolbar strong {
  display: block;
  overflow: hidden;
  margin-top: 5px;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.data-toolbar p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.data-controls {
  display: grid;
  gap: 7px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 8px;
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
  border-radius: 0;
  background: #0b0d0c;
  padding: 8px;
  color: var(--text);
  font: inherit;
  font-size: 10px;
}
.filter-builder button {
  border: 1px solid var(--acid);
  background: transparent;
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
  display: flex;
  gap: 14px;
  border: 1px solid var(--line);
  padding: 9px 11px;
  color: var(--acid);
  font-size: 9px;
  letter-spacing: 0.1em;
}
.result-tools {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  border: 1px solid var(--line);
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
  background: linear-gradient(135deg, rgba(199, 255, 61, 0.16), rgba(199, 255, 61, 0.04));
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
  color: var(--faint);
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
  max-height: 54vh;
  overflow: auto;
  border: 1px solid var(--line);
  background: #0b0d0c;
}
table {
  table-layout: fixed;
  min-width: 100%;
  width: max-content;
  border-collapse: collapse;
  white-space: nowrap;
  font-size: 11px;
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
  background: #171b18;
  color: var(--acid);
}
.column-heading {
  display: flex;
  min-height: 40px;
  align-items: stretch;
  justify-content: space-between;
}
.sort-action {
  overflow: hidden;
  flex: 1;
  border: 0;
  background: transparent;
  padding: 11px 10px;
  color: var(--acid);
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
  display: flex;
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
  padding: 12px 10px;
  cursor: pointer;
}
td:active,
td:focus {
  outline: 0;
  background: rgba(199, 255, 61, 0.1);
  color: var(--acid);
}
td.null {
  color: var(--faint);
  font-style: italic;
}
td.primary {
  background: #f8fafc;
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
  margin: 0;
  color: var(--faint);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.55;
}
.cell-sheet-backdrop {
  position: fixed;
  z-index: 30;
  inset: 0;
  display: grid;
  align-items: end;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(4px);
}
.cell-sheet {
  max-height: min(72vh, 560px);
  overflow: hidden;
  border: 1px solid rgba(199, 255, 61, 0.45);
  border-bottom: 0;
  background: #0b0e0c;
  box-shadow: 0 -24px 70px rgba(0, 0, 0, 0.68);
}
.cell-sheet > header {
  display: flex;
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
.cell-sheet > footer {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 0;
  border-top: 1px solid var(--line);
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto auto;
  align-items: stretch;
  border: 1px solid var(--line);
  background: #ffffff;
}
.edit-toolbar.active {
  border-color: #fdba74;
  box-shadow: inset 3px 0 0 #f97316;
}
.edit-toolbar > div {
  min-width: 0;
  padding: 10px 12px;
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
  background: #fff7ed;
  padding: 0 10px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.edit-toolbar button {
  min-width: 62px;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 10px;
}
.edit-toolbar .insert-row {
  min-width: 74px;
  color: var(--acid);
  font-weight: 720;
}
.edit-toolbar .save-edits {
  min-width: 88px;
  background: var(--acid);
  color: #ffffff;
  font-weight: 720;
}
.edit-blocked {
  margin: 0;
  border: 1px solid #fed7aa;
  border-left: 3px solid var(--amber);
  background: #fff7ed;
  padding: 9px 11px;
  color: #9a3412;
  font-size: 10px;
}
.cell-editor {
  display: grid;
  gap: 9px;
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
  grid-template-columns: 1fr 1fr 1.2fr;
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
  background: #eff6ff;
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
  background: #ffffff;
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
  max-height: min(84vh, 720px);
}
.new-row-form {
  display: grid;
  max-height: calc(min(84vh, 720px) - 116px);
  gap: 8px;
  overflow-y: auto;
  padding: 12px;
  background: #f8fafc;
}
.new-row-field {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  background: #ffffff;
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
.production-confirmation {
  min-height: 42px;
  border: 1px solid #fdba74;
  border-radius: 0;
  outline: 0;
  background: #fff7ed;
  padding: 0 10px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
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
    grid-template-columns: minmax(0, 1fr) auto auto auto;
  }
  .edit-toolbar input {
    grid-column: 1 / -1;
    grid-row: 2;
    min-height: 42px;
    border-top: 1px solid var(--line);
    border-left: 0;
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
