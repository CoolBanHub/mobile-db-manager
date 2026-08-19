<script setup lang="ts">
import type { ConnectionEnvironment, ConnectionSortMode } from "@/lib/connectionPreferences";

export type ConnectionTransportFilter = "all" | "direct" | "tls" | "ssh" | "http";

export interface ConnectionAdvancedFilters {
  type: string;
  environment: "all" | ConnectionEnvironment;
  transport: ConnectionTransportFilter;
  tag: string;
  favorite: boolean;
  production: boolean;
}

const props = defineProps<{
  advancedOpen: boolean;
  sortOpen: boolean;
  advancedFilterCount: number;
  filters: ConnectionAdvancedFilters;
  sortMode: ConnectionSortMode;
  tagOptions: string[];
}>();

const emit = defineEmits<{
  close: [];
  resetFilters: [];
  applyFilters: [];
  resetSort: [];
  applySort: [];
  "update:filters": [filters: ConnectionAdvancedFilters];
  "update:sortMode": [mode: ConnectionSortMode];
}>();

function updateFilter<K extends keyof ConnectionAdvancedFilters>(key: K, value: ConnectionAdvancedFilters[K]) {
  emit("update:filters", { ...props.filters, [key]: value });
}

const sortOptions: Array<{ value: ConnectionSortMode; label: string; hint: string }> = [
  { value: "recent", label: "最近使用", hint: "最近打开的连接排在前面。" },
  { value: "name", label: "连接名称", hint: "按连接名称的拼音和数字顺序排列。" },
  { value: "environment", label: "环境优先", hint: "生产、预发、开发连接依次排列。" },
  { value: "pinned", label: "置顶优先", hint: "已置顶的连接优先，其余连接按最近使用排列。" },
];
</script>

<template>
  <Teleport to="body">
    <div v-if="advancedOpen || sortOpen" class="catalog-sheet-backdrop" @click.self="emit('close')">
      <section class="catalog-sheet" :class="{ 'catalog-sheet--sort': sortOpen }" role="dialog" aria-modal="true" :aria-labelledby="advancedOpen ? 'advanced-search-title' : 'connection-sort-title'">
        <div class="catalog-sheet-handle" aria-hidden="true"></div>

        <template v-if="advancedOpen">
          <header>
            <div><strong id="advanced-search-title">高级搜索</strong><small v-if="advancedFilterCount">已应用 {{ advancedFilterCount }} 个条件</small></div>
            <button type="button" aria-label="关闭高级搜索" @click="emit('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
          </header>
          <div class="advanced-filter-grid">
            <label><span>数据库类型</span><select :value="filters.type" @change="updateFilter('type', ($event.target as HTMLSelectElement).value)"><option value="all">全部类型</option><option value="postgres">PostgreSQL</option><option value="mysql">MySQL / MariaDB</option><option value="sqlserver">SQL Server</option><option value="mongodb">MongoDB</option><option value="redis">Redis</option><option value="etcd">etcd</option></select></label>
            <label><span>环境</span><select :value="filters.environment" @change="updateFilter('environment', ($event.target as HTMLSelectElement).value as ConnectionAdvancedFilters['environment'])"><option value="all">全部环境</option><option value="development">开发</option><option value="staging">预发</option><option value="production">生产</option></select></label>
            <label><span>连接方式</span><select :value="filters.transport" @change="updateFilter('transport', ($event.target as HTMLSelectElement).value as ConnectionTransportFilter)"><option value="all">全部方式</option><option value="direct">直接连接</option><option value="tls">TLS</option><option value="ssh">SSH 隧道</option><option value="http">HTTP 隧道</option></select></label>
            <label><span>标签或分组</span><select :value="filters.tag" @change="updateFilter('tag', ($event.target as HTMLSelectElement).value)"><option value="">全部标签与分组</option><option v-for="item in tagOptions" :key="item" :value="item">{{ item }}</option></select></label>
          </div>
          <div class="advanced-filter-options">
            <label><input :checked="filters.favorite" type="checkbox" @change="updateFilter('favorite', ($event.target as HTMLInputElement).checked)" />仅收藏</label>
            <label><input :checked="filters.production" type="checkbox" @change="updateFilter('production', ($event.target as HTMLInputElement).checked)" />仅生产连接</label>
          </div>
          <footer><button type="button" @click="emit('resetFilters')">重置</button><button class="primary" type="button" @click="emit('applyFilters')">应用筛选</button></footer>
        </template>

        <template v-else>
          <header>
            <strong id="connection-sort-title">连接排序</strong>
            <button type="button" aria-label="关闭连接排序" @click="emit('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
          </header>
          <div class="sort-mode-grid" role="radiogroup" aria-label="排序方式">
            <button v-for="item in sortOptions" :key="item.value" :class="{ active: sortMode === item.value }" type="button" role="radio" :aria-checked="sortMode === item.value" @click="emit('update:sortMode', item.value)">{{ item.label }}</button>
          </div>
          <p class="sort-hint">{{ sortOptions.find((item) => item.value === sortMode)?.hint }}</p>
          <footer><button type="button" @click="emit('resetSort')">恢复默认</button><button class="primary" type="button" @click="emit('applySort')">应用排序</button></footer>
        </template>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.catalog-sheet-backdrop {
  position: fixed;
  z-index: 90;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(16, 24, 40, 0.48);
}
.catalog-sheet {
  width: min(100%, var(--content-max-width));
  max-height: min(78dvh, 620px);
  overflow-y: auto;
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: var(--radius-sheet) var(--radius-sheet) 0 0;
  background: var(--panel-raised);
  padding: 9px 16px calc(18px + env(safe-area-inset-bottom));
  box-shadow: var(--shadow-raised);
  overscroll-behavior: contain;
}
.catalog-sheet--sort {
  border-color: #cfe1fa;
  border-bottom: 0;
  border-radius: 18px 18px 0 0;
  background: #f8fbff;
  padding: 9px 16px calc(24px + env(safe-area-inset-bottom));
  box-shadow: 0 -18px 50px rgba(16, 24, 40, 0.2);
}
.catalog-sheet-handle { width: 38px; height: 4px; margin: 0 auto 10px; border-radius: 4px; background: var(--faint); }
header { display: flex; min-height: 48px; align-items: center; justify-content: space-between; margin-bottom: 10px; }
header > div { display: grid; gap: 3px; }
header strong { color: var(--ink); font-size: 15px; }
header small, .sort-hint { color: var(--muted); font-size: 9px; }
header button { display: grid; width: 44px; height: 44px; place-items: center; border: 0; background: transparent; color: var(--muted); }
header svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-width: 1.8; }
.advanced-filter-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 8px; }
.advanced-filter-grid label { display: grid; min-width: 0; gap: 5px; color: var(--muted); font-size: 9px; }
.advanced-filter-grid select { width: 100%; min-width: 0; min-height: 44px; border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--surface); padding: 0 9px; color: var(--ink); font: inherit; font-size: 10px; }
.advanced-filter-options { display: flex; gap: 20px; margin-top: 14px; }
.advanced-filter-options label { display: flex; min-height: 36px; align-items: center; gap: 8px; color: var(--ink); font-size: 10px; }
.advanced-filter-options input { width: 17px; height: 17px; accent-color: var(--acid); }
.catalog-sheet--sort .catalog-sheet-handle { background: #c6ced9; }
.catalog-sheet--sort header { min-height: 44px; margin-bottom: 9px; }
.catalog-sheet--sort header strong { font-size: 11px; }
.catalog-sheet--sort header button { color: #667085; }
.sort-mode-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; margin-bottom: 10px; }
.sort-mode-grid button { min-height: 44px; border: 1px solid #d0d5dd; border-radius: 6px; background: #fff; color: #344054; font: inherit; font-size: 11px; }
.sort-mode-grid button.active { border-color: var(--acid); background: var(--accent-soft); color: var(--acid); }
.sort-hint { margin: 0 0 8px; color: #667085; font-size: 11px; }
footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
footer button { min-height: 44px; border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--surface); padding: 0 16px; color: var(--ink); font: inherit; font-size: 10px; }
footer button.primary { border-color: var(--acid); background: var(--acid); color: #fff; }
.catalog-sheet--sort footer { gap: 7px; margin-top: 10px; }
.catalog-sheet--sort footer button { border-color: #d0d5dd; border-radius: 5px; background: #fff; padding: 0 12px; color: #344054; font-size: 11px; }
.catalog-sheet--sort footer button.primary { border-color: #0878ff; background: #0878ff; color: #fff; }
@media (max-width: 360px) { .catalog-sheet { padding-inline: 13px; } .advanced-filter-grid { gap: 8px 6px; } }
</style>
