<script setup lang="ts">
import type { QueryResult } from "@/lib/mobileTypes";
import type { QueryExportFormat } from "@/lib/queryExport";

type ChartRow = { label: string; value: number; width: number };

const props = defineProps<{
  result: QueryResult;
  statusText: string;
  executing: boolean;
  exporting: boolean;
  exportStatus: string;
  exportFormat: QueryExportFormat;
  pendingEditCount: number;
  chartRows: ChartRow[];
  showChart: boolean;
  columnWidths: Record<number, number>;
  resultOffset: number;
  resultPage: number;
  pageSize: number;
}>();

const emit = defineEmits<{
  refresh: [];
  copy: [];
  export: [];
  "update:exportFormat": [format: QueryExportFormat];
  buildUpdateSql: [];
  toggleChart: [];
  autoFit: [];
  openCell: [rowIndex: number, columnIndex: number, value: unknown];
  page: [offset: number];
}>();

function displayValue(value: unknown) {
  if (value === null) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function changeExportFormat(event: Event) {
  emit("update:exportFormat", (event.target as HTMLSelectElement).value as QueryExportFormat);
}
</script>

<template>
  <section class="result-panel">
    <header>
      <div class="result-metrics">
        <strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 12 4 4 8-9" /></svg>执行成功</strong>
        <span>{{ statusText }} · {{ result.execution_time_ms }} ms</span>
        <em v-if="result.has_more">MORE</em>
      </div>
      <div v-if="result.columns.length" class="result-actions">
        <button type="button" :disabled="executing" aria-label="刷新结果" title="刷新结果" @click="emit('refresh')"><svg viewBox="0 0 24 24"><path d="M20 7v5h-5M4 17v-5h5" /><path d="M6.1 8a7 7 0 0 1 11.7-1L20 12M4 12l2.2 5a7 7 0 0 0 11.7-1" /></svg></button>
        <button type="button" aria-label="复制结果" title="复制结果" @click="emit('copy')"><svg viewBox="0 0 24 24"><rect x="8" y="8" width="11" height="11" rx="2" /><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3" /></svg></button>
        <button class="export-action" :disabled="exporting" type="button" :aria-label="`导出并分享 ${exportFormat.toUpperCase()} 查询结果`" title="导出结果" @click="emit('export')"><svg viewBox="0 0 24 24"><path d="M12 3v12M7 10l5 5 5-5M5 21h14" /></svg></button>
        <details class="result-more">
          <summary aria-label="更多结果操作"><svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></svg></summary>
          <div>
            <label>导出格式<select :value="exportFormat" aria-label="查询结果导出格式" @change="changeExportFormat"><option value="csv">CSV</option><option value="json">JSON</option><option value="markdown">MARKDOWN</option><option value="xlsx">EXCEL XLSX</option></select></label>
            <button v-if="pendingEditCount" type="button" @click="emit('buildUpdateSql')">生成修改 SQL（{{ pendingEditCount }}）</button>
            <button v-if="chartRows.length" type="button" @click="emit('toggleChart')">{{ showChart ? "显示表格" : "显示图表" }}</button>
            <button type="button" @click="emit('autoFit')">自动调整列宽</button>
          </div>
        </details>
      </div>
    </header>
    <p v-if="exportStatus" class="export-status" aria-live="polite">{{ exportStatus }}</p>
    <div v-if="showChart && chartRows.length" class="query-chart">
      <article v-for="(item, index) in chartRows" :key="index"><span :title="item.label">{{ item.label }}</span><i><b :style="{ width: `${item.width}%` }"></b></i><strong>{{ item.value }}</strong></article>
    </div>
    <div v-if="result.columns.length && !showChart" class="result-scroll">
      <table>
        <colgroup><col v-for="(_, index) in result.columns" :key="index" :style="{ width: `${columnWidths[index] ?? 160}px` }" /></colgroup>
        <thead><tr><th v-for="(column, index) in result.columns" :key="`${column}:${index}`"><div><span>{{ column }}</span></div></th></tr></thead>
        <tbody><tr v-for="(row, rowIndex) in result.rows" :key="resultOffset + rowIndex"><td v-for="(_, index) in result.columns" :key="index" :class="{ null: row[index] === null }"><button :class="{ 'status-value': result.columns[index].toLocaleLowerCase() === 'status' && ['active', 'paid', 'success', 'enabled'].includes(String(row[index]).toLocaleLowerCase()) }" type="button" @click="emit('openCell', rowIndex, index, row[index])">{{ displayValue(row[index]) }}</button></td></tr></tbody>
      </table>
    </div>
    <p v-else>执行成功，{{ statusText }}。</p>
    <footer v-if="result.columns.length">
      <div>
        <button :disabled="executing || resultOffset === 0" aria-label="第一页" @click="emit('page', 0)"><svg viewBox="0 0 24 24"><path d="M6 5v14M18 6l-6 6 6 6" /></svg></button>
        <button :disabled="executing || resultOffset === 0" aria-label="上一页" @click="emit('page', Math.max(0, resultOffset - pageSize))"><svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6" /></svg></button>
        <span>第 {{ resultPage }} 页</span>
        <button :disabled="executing || !result.has_more" aria-label="下一页" @click="emit('page', resultOffset + pageSize)"><svg viewBox="0 0 24 24"><path d="m9 18 6-6-6-6" /></svg></button>
      </div>
      <small>{{ pageSize }} / 页</small>
    </footer>
  </section>
</template>

<style scoped>
.result-panel { min-width: 0; overflow: hidden; border: 1px solid var(--divider-color); border-radius: var(--radius-card); background: var(--card-background); }
.result-panel > header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: var(--space-2); padding: var(--space-3); border-bottom: 1px solid var(--divider-color); }
.result-metrics { display: grid; min-width: 0; gap: 2px; }
.result-metrics strong { display: inline-flex; align-items: center; gap: 5px; color: var(--success); font-size: 11px; }
.result-metrics strong svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 2; }
.result-metrics span { overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.result-metrics em { color: var(--primary); font-size: 7px; font-style: normal; }
.result-actions { display: flex; flex: none; align-items: center; gap: 3px; }
.result-actions > button, .result-more summary { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid var(--divider-color); border-radius: var(--radius-sm); background: var(--input-background); color: var(--muted); }
.result-actions svg, footer button svg { width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.export-action { color: var(--primary) !important; }
.result-more { position: relative; }
.result-more summary { list-style: none; cursor: pointer; }
.result-more summary::-webkit-details-marker { display: none; }
.result-more > div { position: absolute; z-index: 8; top: 39px; right: 0; display: grid; width: 210px; gap: 7px; border: 1px solid var(--divider-color); border-radius: var(--radius-card); background: var(--card-background); padding: var(--space-3); box-shadow: var(--shadow-raised); }
.result-more label { display: grid; gap: 4px; color: var(--muted); font-size: 8px; }
.result-more select, .result-more button { min-height: 36px; border: 1px solid var(--divider-color); background: var(--input-background); color: var(--ink); font-size: 9px; }
.export-status { margin: 0; border-bottom: 1px solid var(--divider-color); padding: 8px 11px; color: var(--primary); font-size: 8px; }
.result-scroll { min-width: 0; max-width: 100%; overflow-x: auto; overscroll-behavior-x: contain; }
table { min-width: max-content; border-collapse: collapse; table-layout: fixed; }
th, td { height: 38px; border-right: 1px solid var(--divider-color); border-bottom: 1px solid var(--divider-color); text-align: left; }
th { position: sticky; top: 0; z-index: 1; background: var(--input-background); padding: 0 10px; color: var(--primary); font-size: 8px; }
td > button { display: block; overflow: hidden; width: 100%; height: 100%; border: 0; border-radius: 0; background: transparent; padding: 0 10px; color: var(--ink); font: 9px "Azeret Mono Variable", monospace; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
td.null > button { color: var(--faint); font-style: italic; }
.status-value { color: var(--success) !important; }
.query-chart { display: grid; gap: 8px; padding: var(--space-3); }
.query-chart article { display: grid; grid-template-columns: minmax(70px, .8fr) 2fr auto; align-items: center; gap: 8px; }
.query-chart article > span { overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.query-chart i { height: 7px; border-radius: 999px; background: var(--input-background); }
.query-chart i b { display: block; height: 100%; border-radius: inherit; background: var(--primary); }
.query-chart strong { font-size: 8px; }
.result-panel > p { margin: 0; padding: var(--space-4); color: var(--muted); font-size: 9px; }
footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 10px calc(8px + var(--page-bottom-safe)); border-top: 1px solid var(--divider-color); }
footer > div { display: flex; align-items: center; gap: 4px; }
footer button { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid var(--divider-color); background: var(--input-background); }
footer span, footer small { color: var(--muted); font-size: 8px; }
</style>
