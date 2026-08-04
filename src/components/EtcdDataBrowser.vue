<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { loadDirectEtcdEntries, loadDirectEtcdEntry, loadDirectEtcdOverview, mutateDirectEtcd } from "../lib/direct/etcd";
import type { MobileConnectionSummary, MobileEtcdEntry, MobileEtcdOverview } from "../lib/mobileTypes";

const props = defineProps<{ connection: MobileConnectionSummary }>();

const overview = ref<MobileEtcdOverview | null>(null);
const entries = ref<MobileEtcdEntry[]>([]);
const prefixDraft = ref("");
const activePrefix = ref("");
const loading = ref(true);
const busy = ref(false);
const error = ref("");
const status = ref("");
const detail = ref<MobileEtcdEntry | null>(null);
const valueDraft = ref("");
const createOpen = ref(false);
const createKey = ref("");
const createValue = ref("");
const productionConfirmation = ref("");
const expandedPrefixes = ref(new Set<string>());
const inspectorTab = ref<"overview" | "value" | "metadata">("overview");

interface EtcdTreeNode {
  id: string;
  label: string;
  children: EtcdTreeNode[];
  entry?: MobileEtcdEntry;
}

interface FlatEtcdTreeNode extends EtcdTreeNode {
  depth: number;
}

// 这里只控制交互状态，真正的写权限和生产确认由原生插件再次校验。
const canWrite = computed(() => !props.connection.readOnly && (!props.connection.isProduction || productionConfirmation.value === props.connection.name));
const binaryValue = computed(() => detail.value?.value.startsWith("base64:") === true);
const keyTree = computed<EtcdTreeNode[]>(() => {
  const roots: EtcdTreeNode[] = [];
  for (const entry of entries.value) {
    const segments = entry.key.split("/").filter(Boolean);
    if (segments.length === 0) segments.push(entry.key || "(空键)");
    let branch = roots;
    let id = entry.key.startsWith("/") ? "" : "relative:";
    segments.forEach((segment, index) => {
      id = `${id}/${segment}`;
      let node = branch.find((candidate) => candidate.id === id);
      if (!node) {
        node = { id, label: segment, children: [] };
        branch.push(node);
      }
      if (index === segments.length - 1) node.entry = entry;
      branch = node.children;
    });
  }
  return roots;
});
const visibleTreeNodes = computed<FlatEtcdTreeNode[]>(() => {
  const rows: FlatEtcdTreeNode[] = [];
  const visit = (nodes: EtcdTreeNode[], depth: number) => {
    for (const node of nodes) {
      rows.push({ ...node, depth });
      if (node.children.length && expandedPrefixes.value.has(node.id)) visit(node.children, depth + 1);
    }
  };
  visit(keyTree.value, 0);
  return rows;
});

function handleBack() {
  if (createOpen.value) {
    createOpen.value = false;
    return true;
  }
  if (detail.value) {
    detail.value = null;
    inspectorTab.value = "overview";
    return true;
  }
  return false;
}

defineExpose({ handleBack });

function formatBytes(value: string | undefined) {
  const bytes = Number(value ?? 0);
  if (!Number.isFinite(bytes)) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function expandTreePrefixes() {
  const prefixes = new Set<string>();
  const visit = (nodes: EtcdTreeNode[], depth: number) => {
    for (const node of nodes) {
      if (node.children.length && depth < 2) prefixes.add(node.id);
      visit(node.children, depth + 1);
    }
  };
  visit(keyTree.value, 0);
  expandedPrefixes.value = prefixes;
}

function toggleTreeNode(node: FlatEtcdTreeNode) {
  if (!node.children.length && node.entry) {
    void openEntry(node.entry.key);
    return;
  }
  const next = new Set(expandedPrefixes.value);
  if (next.has(node.id)) next.delete(node.id);
  else next.add(node.id);
  expandedPrefixes.value = next;
}

async function reload() {
  loading.value = true;
  error.value = "";
  status.value = "";
  try {
    // 集群状态和键范围查询可以并行；范围结果在原生层限制为最多 200 条。
    const [nextOverview, page] = await Promise.all([loadDirectEtcdOverview(props.connection.id), loadDirectEtcdEntries(props.connection.id, activePrefix.value)]);
    overview.value = nextOverview;
    entries.value = page.entries;
    expandTreePrefixes();
    if (page.more) status.value = "结果超过 200 条，请输入更精确的键前缀";
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "etcd 数据加载失败";
  } finally {
    loading.value = false;
  }
}

async function applyPrefix() {
  activePrefix.value = prefixDraft.value.trim();
  detail.value = null;
  await reload();
}

async function openEntry(key: string) {
  busy.value = true;
  error.value = "";
  status.value = "";
  createOpen.value = false;
  try {
    detail.value = await loadDirectEtcdEntry(props.connection.id, key);
    valueDraft.value = detail.value.value;
    inspectorTab.value = "value";
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "etcd 键读取失败";
  } finally {
    busy.value = false;
  }
}

async function put(key: string, value: string, message: string, lease = "0") {
  if (!canWrite.value || !key.trim() || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await mutateDirectEtcd(props.connection.id, "put", key.trim(), value, productionConfirmation.value, lease);
    activePrefix.value = prefixDraft.value.trim();
    await reload();
    status.value = message;
    createOpen.value = false;
    if (detail.value?.key === key.trim()) await openEntry(key.trim());
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "etcd 写入失败";
  } finally {
    busy.value = false;
  }
}

async function saveDetail() {
  if (!detail.value) return;
  // 保留原租约写回，避免编辑值时意外把租约解绑。
  await put(detail.value.key, valueDraft.value, "键值已保存", detail.value.lease);
}

async function createEntry() {
  if (!createKey.value.trim()) {
    error.value = "请输入 etcd 键";
    return;
  }
  const key = createKey.value.trim();
  await put(key, createValue.value, "键已创建");
  createKey.value = "";
  createValue.value = "";
}

async function deleteEntry() {
  if (!detail.value || !canWrite.value || busy.value) return;
  if (!window.confirm(`删除 etcd 键“${detail.value.key}”？此操作不可撤销。`)) return;
  busy.value = true;
  error.value = "";
  try {
    await mutateDirectEtcd(props.connection.id, "delete", detail.value.key, "", productionConfirmation.value);
    detail.value = null;
    await reload();
    status.value = "键已删除";
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "etcd 删除失败";
  } finally {
    busy.value = false;
  }
}

onMounted(reload);
</script>

<template>
  <section class="etcd-browser">
    <form class="browser-search" role="search" @submit.prevent="applyPrefix">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="11" cy="11" r="6" />
        <path d="m16 16 4 4" />
      </svg>
      <input v-model="prefixDraft" type="search" placeholder="搜索键前缀，例如 /services/" autocapitalize="none" />
      <button type="submit" aria-label="查询键前缀">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M8 12h8M10 17h4" /></svg>
      </button>
    </form>

    <label v-if="connection.isProduction" class="production-confirm">
      <span>生产写入确认：输入连接名称</span>
      <input v-model="productionConfirmation" :placeholder="connection.name" />
    </label>

    <p v-if="error" class="message danger">{{ error }}</p>
    <p v-else-if="status" class="message">{{ status }}</p>

    <div class="keyspace-view">
      <div class="tree-root-label">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m7 9 5 5 5-5" />
          <ellipse cx="12" cy="5" rx="6" ry="3" />
          <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
        </svg>
        <span>键空间 ({{ entries.length }})</span>
        <div class="tree-actions">
          <button :disabled="!canWrite" type="button" aria-label="新增键" @click="createOpen = true">＋</button>
          <button :disabled="loading" type="button" aria-label="刷新键空间" @click="reload">↻</button>
        </div>
      </div>

      <div v-if="loading" class="browser-state">正在读取 etcd 键空间…</div>
      <div v-else-if="entries.length === 0" class="browser-state">没有匹配的键</div>
      <div v-else class="key-tree" role="tree" aria-label="etcd 键空间">
        <button v-for="node in visibleTreeNodes" :key="node.id" class="tree-node" :class="{ expanded: expandedPrefixes.has(node.id), selected: !!node.entry && detail?.key === node.entry.key }" :style="{ '--tree-depth': node.depth }" type="button" role="treeitem" @click="toggleTreeNode(node)">
          <span v-if="node.children.length" class="tree-chevron">›</span>
          <span v-else class="tree-spacer"></span>
          <svg v-if="node.children.length" class="tree-icon folder-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M3.5 7.5h6l2-2h9v13h-17Z" />
          </svg>
          <svg v-else class="tree-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 3.5h8l4 4v13H6Z" />
            <path d="M14 3.5v4h4" />
          </svg>
          <span class="tree-label">{{ node.label }}</span>
          <small v-if="node.entry">{{ node.entry.value || "空值" }}</small>
        </button>
      </div>

      <section class="key-inspector">
        <nav aria-label="etcd 键详情">
          <button :class="{ active: inspectorTab === 'overview' }" type="button" @click="inspectorTab = 'overview'">概览</button>
          <button :class="{ active: inspectorTab === 'value' }" :disabled="!detail" type="button" @click="inspectorTab = 'value'">值</button>
          <button :class="{ active: inspectorTab === 'metadata' }" :disabled="!detail" type="button" @click="inspectorTab = 'metadata'">元数据</button>
        </nav>

        <div v-if="inspectorTab === 'overview'" class="overview-summary">
          <div class="summary-title">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <ellipse cx="12" cy="5" rx="6" ry="3" />
              <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
            </svg>
            <strong>{{ activePrefix || "/" }}</strong>
          </div>
          <dl>
            <dt>版本</dt>
            <dd>{{ overview?.version || "—" }}</dd>
            <dt>协议</dt>
            <dd>v3 JSON</dd>
          </dl>
          <dl>
            <dt>键</dt>
            <dd>{{ overview?.keyCount ?? entries.length }}</dd>
            <dt>大小</dt>
            <dd>{{ formatBytes(overview?.dbSize) }}</dd>
          </dl>
        </div>

        <div v-else-if="detail && inspectorTab === 'value'" class="value-inspector">
          <header>
            <strong :title="detail.key">{{ detail.key }}</strong
            ><small>{{ binaryValue ? "BASE64" : "UTF-8" }}</small>
          </header>
          <textarea v-model="valueDraft" rows="4" :readonly="!canWrite || binaryValue"></textarea>
          <p v-if="binaryValue" class="binary-note">二进制值仅提供 Base64 预览，禁止文本覆盖。</p>
          <div class="detail-actions">
            <button :disabled="!canWrite || binaryValue || busy" type="button" @click="saveDetail">保存值</button>
            <button class="danger-action" :disabled="!canWrite || busy" type="button" @click="deleteEntry">删除键</button>
          </div>
        </div>

        <div v-else-if="detail" class="metadata-summary">
          <strong :title="detail.key">{{ detail.key }}</strong>
          <dl>
            <dt>创建版本</dt>
            <dd>{{ detail.createRevision }}</dd>
            <dt>修改版本</dt>
            <dd>{{ detail.modRevision }}</dd>
            <dt>版本</dt>
            <dd>{{ detail.version }}</dd>
            <dt>租约</dt>
            <dd>{{ detail.lease === "0" ? "无" : detail.lease }}</dd>
          </dl>
        </div>
      </section>
    </div>

    <div v-if="createOpen" class="editor-backdrop" role="presentation" @click.self="createOpen = false">
      <form class="create-sheet" role="dialog" aria-modal="true" aria-label="新增 etcd 键" @submit.prevent="createEntry">
        <header>
          <div><small>ETCD V3</small><strong>新增键值</strong></div>
          <button type="button" aria-label="关闭" @click="createOpen = false">×</button>
        </header>
        <label><span>键</span><input v-model="createKey" placeholder="/config/example" autocapitalize="none" /></label>
        <label><span>值</span><textarea v-model="createValue" rows="6"></textarea></label>
        <button class="primary-action" :disabled="!canWrite || busy" type="submit">写入 etcd</button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.etcd-browser {
  display: grid;
  height: calc(100dvh - 165px - var(--safe-top) - var(--safe-bottom));
  min-height: 420px;
  grid-template-rows: auto auto minmax(0, 1fr);
  margin-top: 0;
}
.browser-search {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 40px;
  align-items: center;
  min-height: 39px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--field);
}
.browser-search > svg {
  width: 17px;
  height: 17px;
  margin-left: 11px;
  fill: none;
  stroke: var(--muted);
  stroke-linecap: round;
  stroke-width: 1.7;
}
.browser-search input {
  width: 100%;
  min-width: 0;
  height: 37px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.browser-search button {
  display: grid;
  height: 27px;
  place-items: center;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
}
.browser-search button svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 1.7;
}
.production-confirm {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
  border-left: 2px solid var(--danger);
  background: color-mix(in srgb, var(--danger) 5%, transparent);
  padding: 7px 9px;
}
.production-confirm span {
  color: var(--muted);
  font-size: 8px;
}
.production-confirm input,
.create-sheet input,
.create-sheet textarea,
.value-inspector textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 5px;
  outline: 0;
  background: var(--field);
  padding: 9px 10px;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.keyspace-view {
  display: grid;
  min-height: 0;
  grid-template-rows: auto minmax(150px, 1fr) auto;
}
.tree-root-label,
.tree-node {
  display: flex;
  align-items: center;
  color: var(--ink);
  font-family: "PingFang SC", system-ui, sans-serif;
}
.tree-root-label {
  min-height: 31px;
  gap: 7px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 600;
}
.tree-root-label > svg,
.tree-icon {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.55;
}
.tree-actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}
.tree-actions button {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--acid);
  font: inherit;
  font-size: 15px;
}
.tree-actions button:active {
  background: var(--accent-soft);
}
.tree-actions button:disabled {
  opacity: 0.32;
}
.key-tree {
  overflow-y: auto;
  min-height: 150px;
  padding: 2px 0 8px;
  scrollbar-width: none;
}
.key-tree::-webkit-scrollbar {
  display: none;
}
.tree-node {
  width: 100%;
  min-height: 30px;
  gap: 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  padding: 3px 7px 3px calc(18px + var(--tree-depth) * 22px);
  text-align: left;
  font-size: 10px;
  line-height: 1.2;
}
.tree-node:active,
.tree-node.selected {
  background: var(--accent-soft);
}
.tree-node.selected {
  color: var(--acid);
}
.tree-chevron,
.tree-spacer {
  width: 10px;
  flex: 0 0 10px;
}
.tree-chevron {
  color: var(--muted);
  font-size: 16px;
  line-height: 1;
  transition: transform 120ms ease;
}
.tree-node.expanded > .tree-chevron {
  transform: rotate(90deg);
}
.folder-icon {
  color: var(--muted);
}
.tree-label {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-node small {
  overflow: hidden;
  max-width: 42%;
  margin-left: auto;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.browser-state {
  min-height: 150px;
  padding: 35px 18px;
  color: var(--muted);
  font-size: 10px;
}
.message {
  margin: 0 0 7px;
  border-left: 2px solid var(--acid);
  background: color-mix(in srgb, var(--acid) 5%, transparent);
  padding: 7px 9px;
  color: var(--muted);
  font-size: 8px;
}
.message.danger {
  border-left-color: var(--danger);
  color: var(--danger);
}
.key-inspector {
  overflow: hidden;
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: 12px 12px 0 0;
  background: color-mix(in srgb, var(--panel) 96%, transparent);
  box-shadow: 0 -7px 22px rgba(0, 0, 0, 0.04);
}
.key-inspector nav {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-bottom: 1px solid var(--line);
}
.key-inspector nav button {
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 10px;
}
.key-inspector nav button.active {
  color: var(--acid);
  box-shadow: inset 0 -2px var(--acid);
}
.key-inspector nav button:disabled {
  opacity: 0.35;
}
.overview-summary,
.metadata-summary {
  display: grid;
  min-height: 88px;
  grid-template-columns: minmax(0, 1.2fr) 1fr 1fr;
  align-items: center;
  padding: 10px;
}
.summary-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}
.summary-title svg {
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
}
.summary-title strong,
.metadata-summary > strong {
  overflow: hidden;
  min-width: 0;
  color: var(--ink);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.overview-summary dl,
.metadata-summary dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 8px;
  margin: 0;
  border-left: 1px solid var(--line);
  padding-left: 10px;
  font-size: 8px;
}
.overview-summary dt,
.metadata-summary dt {
  color: var(--muted);
}
.overview-summary dd,
.metadata-summary dd {
  overflow: hidden;
  margin: 0;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.value-inspector {
  padding: 9px 10px 10px;
}
.value-inspector header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}
.value-inspector header strong {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.value-inspector header small {
  margin-left: auto;
  color: var(--muted);
  font-size: 7px;
}
.value-inspector textarea,
.create-sheet textarea {
  resize: vertical;
  line-height: 1.5;
}
.detail-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
  margin-top: 7px;
}
.detail-actions button,
.primary-action {
  min-height: 36px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--acid);
  font: inherit;
  font-size: 9px;
}
.detail-actions .danger-action {
  color: var(--danger);
}
.detail-actions button:disabled,
.primary-action:disabled {
  opacity: 0.35;
}
.metadata-summary {
  grid-template-columns: minmax(0, 1fr) 2fr;
}
.metadata-summary dl {
  grid-template-columns: auto 1fr auto 1fr;
}
.binary-note {
  margin: 7px 0 0;
  border-left: 2px solid var(--amber);
  padding: 6px 8px;
  color: var(--muted);
  font-size: 8px;
}
.editor-backdrop {
  position: fixed;
  z-index: 40;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.34);
}
.create-sheet {
  width: 100%;
  border-radius: 18px 18px 0 0;
  background: var(--panel);
  padding: 18px 18px calc(18px + var(--safe-bottom));
  box-shadow: 0 -16px 45px rgba(0, 0, 0, 0.18);
}
.create-sheet header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.create-sheet header div {
  display: grid;
  gap: 3px;
}
.create-sheet header small {
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.14em;
}
.create-sheet header strong {
  font-size: 15px;
}
.create-sheet header button {
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 22px;
}
.create-sheet label {
  display: block;
  margin-bottom: 12px;
}
.create-sheet label span {
  display: block;
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 9px;
}
.primary-action {
  width: 100%;
  border-color: var(--acid);
  background: var(--accent-soft);
}
@media (max-width: 430px) {
  .overview-summary {
    grid-template-columns: minmax(0, 1.15fr) 1fr 1fr;
  }
  .overview-summary dl,
  .metadata-summary dl {
    padding-left: 8px;
  }
}
</style>
