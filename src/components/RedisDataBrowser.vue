<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { loadDirectRedisKey, loadDirectRedisOverview, mutateDirectRedis, scanDirectRedisKeys } from "../lib/directDatabase";
import type { MobileConnectionSummary, MobileRedisKeyDetail, MobileRedisOverview } from "../lib/mobileTypes";

const props = defineProps<{ connection: MobileConnectionSummary }>();

const database = ref(Math.max(0, Number.parseInt(props.connection.database ?? "0", 10) || 0));
const databaseDraft = ref(String(database.value));
const overview = ref<MobileRedisOverview | null>(null);
const keys = ref<string[]>([]);
const cursor = ref("0");
const patternDraft = ref("");
const activePattern = ref("*");
const loading = ref(true);
const loadingMore = ref(false);
const error = ref("");
const detail = ref<MobileRedisKeyDetail | null>(null);
const detailLoading = ref(false);
const mutationBusy = ref(false);
const status = ref("");
const productionConfirmation = ref("");
const stringValue = ref("");
const editField = ref("");
const editValue = ref("");
const editMember = ref("");
const editScore = ref("");
const ttlSeconds = ref("");
const createOpen = ref(false);
const createKey = ref("");
const createValue = ref("");
const keysExpanded = ref(true);
const inspectorTab = ref<"structure" | "data" | "command">("structure");

// UI 校验用于即时禁用按钮；原生插件会再次强制校验只读和生产连接名称。
const canWrite = computed(() => !props.connection.readOnly && (!props.connection.isProduction || productionConfirmation.value === props.connection.name));
const hasMore = computed(() => cursor.value !== "0");
const detailPairs = computed(() => alternatingPairs(detail.value?.value));
const detailList = computed(() => (Array.isArray(detail.value?.value) ? detail.value.value : []));
const redisDatabases = computed(() => {
  const entries = new Map<number, number>();
  for (const line of overview.value?.keyspace.split(/\r?\n/) ?? []) {
    const match = line.match(/^db(\d+):keys=(\d+)/);
    if (match) entries.set(Number(match[1]), Number(match[2]));
  }
  if (!entries.has(database.value)) entries.set(database.value, overview.value?.keyCount ?? 0);
  return [...entries].sort(([left], [right]) => left - right).map(([index, keyCount]) => ({ index, keyCount }));
});

function handleBack() {
  if (createOpen.value) {
    createOpen.value = false;
    return true;
  }
  if (detail.value) {
    detail.value = null;
    status.value = "";
    return true;
  }
  return false;
}

function getQueryContext() {
  return { connectionId: props.connection.id, database: String(database.value), schema: null };
}

defineExpose({ getQueryContext, handleBack });

function normalizedPattern() {
  const value = patternDraft.value.trim();
  if (!value) return "*";
  return /[*?[\]]/.test(value) ? value : `*${value}*`;
}

function alternatingPairs(value: unknown): Array<{ left: string; right: string }> {
  if (!Array.isArray(value)) return [];
  const result: Array<{ left: string; right: string }> = [];
  for (let index = 0; index < value.length; index += 2) {
    result.push({
      left: String(value[index] ?? ""),
      right: String(value[index + 1] ?? ""),
    });
  }
  return result;
}

function keyBadge(key: string) {
  const separator = key.search(/[:/._-]/);
  return (separator > 0 ? key.slice(0, separator) : key).slice(0, 3).toUpperCase() || "KEY";
}

function formatCount(value: number | undefined) {
  if (value === undefined) return "—";
  return new Intl.NumberFormat("zh-CN").format(value);
}

function formatBytes(value: number | null) {
  if (value === null || value < 0) return "—";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatTtl(value: number) {
  if (value === -1) return "永久";
  if (value === -2) return "已过期";
  if (value < 1_000) return `${value} ms`;
  if (value < 60_000) return `${Math.ceil(value / 1_000)} 秒`;
  if (value < 3_600_000) return `${Math.ceil(value / 60_000)} 分钟`;
  return `${Math.ceil(value / 3_600_000)} 小时`;
}

function parseKeyspaceInfo(info: string) {
  const line = info.split(/\r?\n/).find((item) => item.startsWith(`db${database.value}:`));
  return line?.slice(line.indexOf(":") + 1).replaceAll(",", " · ") ?? "当前逻辑库";
}

async function reload() {
  loading.value = true;
  error.value = "";
  status.value = "";
  detail.value = null;
  createOpen.value = false;
  keys.value = [];
  cursor.value = "0";
  try {
    // 概览与首屏 SCAN 相互独立，并行加载可减少移动网络下的等待时间。
    const [nextOverview, page] = await Promise.all([loadDirectRedisOverview(props.connection.id, database.value), scanDirectRedisKeys(props.connection.id, database.value, "0", activePattern.value)]);
    overview.value = nextOverview;
    keys.value = page.keys;
    cursor.value = page.cursor;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "Redis 数据加载失败";
  } finally {
    loading.value = false;
  }
}

async function applyDatabase() {
  const next = Number.parseInt(databaseDraft.value, 10);
  if (!Number.isInteger(next) || next < 0) {
    error.value = "Redis 数据库编号必须是非负整数";
    return;
  }
  database.value = next;
  activePattern.value = normalizedPattern();
  await reload();
}

async function openDatabase(index: number) {
  if (database.value === index) {
    keysExpanded.value = !keysExpanded.value;
    return;
  }
  database.value = index;
  databaseDraft.value = String(index);
  keysExpanded.value = true;
  inspectorTab.value = "structure";
  await reload();
}

async function applySearch() {
  activePattern.value = normalizedPattern();
  await reload();
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return;
  loadingMore.value = true;
  error.value = "";
  try {
    const page = await scanDirectRedisKeys(props.connection.id, database.value, cursor.value, activePattern.value);
    // Redis SCAN 允许重复返回键，合并分页时必须去重。
    keys.value = [...new Set([...keys.value, ...page.keys])];
    cursor.value = page.cursor;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "继续扫描 Redis 键失败";
  } finally {
    loadingMore.value = false;
  }
}

async function openKey(key: string) {
  detailLoading.value = true;
  error.value = "";
  status.value = "";
  createOpen.value = false;
  try {
    detail.value = await loadDirectRedisKey(props.connection.id, database.value, key);
    stringValue.value = detail.value.type === "string" ? String(detail.value.value ?? "") : "";
    ttlSeconds.value = detail.value.ttlMs >= 0 ? String(Math.ceil(detail.value.ttlMs / 1_000)) : "";
    editField.value = "";
    editValue.value = "";
    editMember.value = "";
    editScore.value = "";
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "Redis 键读取失败";
  } finally {
    detailLoading.value = false;
  }
}

async function runMutation(action: string, payload: Record<string, unknown>, success: string, refreshDetail = true) {
  if (!canWrite.value || mutationBusy.value) return;
  mutationBusy.value = true;
  error.value = "";
  status.value = "";
  try {
    await mutateDirectRedis(props.connection.id, database.value, action, payload, productionConfirmation.value);
    if (refreshDetail && detail.value) await openKey(detail.value.key);
    overview.value = await loadDirectRedisOverview(props.connection.id, database.value);
    status.value = success;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "Redis 写入失败";
  } finally {
    mutationBusy.value = false;
  }
}

async function saveString() {
  if (!detail.value) return;
  await runMutation("set-string", { key: detail.value.key, value: stringValue.value }, "String 已保存");
}

async function saveHashField() {
  if (!detail.value || !editField.value.trim()) return;
  await runMutation("hset", { key: detail.value.key, field: editField.value, value: editValue.value }, "Hash 字段已写入");
  editField.value = "";
  editValue.value = "";
}

async function saveListItem(index?: number) {
  if (!detail.value) return;
  if (index === undefined) {
    await runMutation("rpush", { key: detail.value.key, value: editValue.value }, "List 元素已追加");
  } else {
    await runMutation("lset", { key: detail.value.key, index, value: editValue.value }, `索引 ${index} 已保存`);
  }
  editValue.value = "";
}

async function editListItem(index: number, current: unknown) {
  const value = window.prompt(`覆盖 List 索引 ${index}`, String(current ?? ""));
  if (value === null) return;
  editValue.value = value;
  await saveListItem(index);
}

async function saveSetMember() {
  if (!detail.value || !editMember.value) return;
  await runMutation("sadd", { key: detail.value.key, member: editMember.value }, "Set 成员已添加");
  editMember.value = "";
}

async function saveZsetMember() {
  if (!detail.value || !editMember.value || !editScore.value) return;
  await runMutation("zadd", { key: detail.value.key, member: editMember.value, score: editScore.value }, "ZSet 成员已写入");
  editMember.value = "";
  editScore.value = "";
}

async function updateTtl() {
  if (!detail.value) return;
  const seconds = Number.parseInt(ttlSeconds.value, 10);
  if (!Number.isInteger(seconds) || seconds <= 0) {
    error.value = "TTL 必须是大于 0 的整数秒；0 会删除 Redis 键，因此已阻止";
    return;
  }
  await runMutation("expire", { key: detail.value.key, seconds }, "TTL 已更新");
}

async function persistKey() {
  if (!detail.value) return;
  await runMutation("persist", { key: detail.value.key }, "已移除过期时间");
}

async function deleteKey() {
  if (!detail.value || !window.confirm(`永久删除 Redis 键“${detail.value.key}”？`)) return;
  const key = detail.value.key;
  await runMutation("delete", { key }, "键已删除", false);
  detail.value = null;
  keys.value = keys.value.filter((item) => item !== key);
}

async function createStringKey() {
  if (!createKey.value.trim()) return;
  const key = createKey.value;
  await runMutation("set-string", { key, value: createValue.value }, "String 键已创建", false);
  createOpen.value = false;
  createKey.value = "";
  createValue.value = "";
  await reload();
  await openKey(key);
}

onMounted(reload);
</script>

<template>
  <section class="redis-browser">
    <div v-if="detail" class="redis-detail-toolbar">
      <button type="button" @click="detail = null">← DB {{ database }}</button>
      <span>{{ detail.type.toUpperCase() }}</span>
    </div>

    <template v-if="!detail">
      <section class="redis-browser-search">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6" />
          <path d="m16 16 4 4" />
        </svg>
        <input v-model="patternDraft" type="search" autocapitalize="none" placeholder="搜索数据库或键" @keyup.enter="applySearch" />
        <button type="button" aria-label="筛选 Redis 键" @click="applySearch">≡</button>
      </section>

      <p v-if="error" class="redis-message error" role="alert">{{ error }}</p>
      <p v-else-if="status" class="redis-message success">{{ status }}</p>

      <div v-if="loading" class="redis-loading"><i></i><strong>正在扫描键空间</strong></div>
      <div v-else class="redis-tree-view">
        <div class="redis-tree-root">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m7 9 5 5 5-5" />
            <ellipse cx="12" cy="5" rx="6" ry="3" />
            <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
          </svg>
          <span>数据库 ({{ redisDatabases.length }})</span>
        </div>

        <div class="redis-tree" role="tree" aria-label="Redis 数据库对象树">
          <template v-for="item in redisDatabases" :key="item.index">
            <button class="redis-tree-node database" :class="{ expanded: database === item.index }" type="button" role="treeitem" @click="openDatabase(item.index)">
              <span class="redis-chevron">›</span>
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <ellipse cx="12" cy="5" rx="6" ry="3" />
                <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
              </svg>
              <span>DB {{ item.index }}</span
              ><small>{{ formatCount(item.keyCount) }}</small>
            </button>
            <template v-if="database === item.index">
              <div class="redis-tree-group-row">
                <button class="redis-tree-node group" :class="{ expanded: keysExpanded }" type="button" @click="keysExpanded = !keysExpanded">
                  <span class="redis-chevron">›</span><span class="redis-tree-symbol">◇</span><span>键 ({{ keys.length }})</span>
                </button>
                <button class="redis-tree-add" :disabled="connection.readOnly" type="button" aria-label="新建键" @click="createOpen = !createOpen">＋</button>
              </div>
              <template v-if="keysExpanded">
                <button v-for="key in keys" :key="key" class="redis-tree-node leaf" type="button" @click="openKey(key)">
                  <span class="redis-tree-symbol">◆</span><span>{{ key }}</span
                  ><small>{{ keyBadge(key) }}</small>
                </button>
                <button v-if="hasMore" class="redis-tree-node leaf load" :disabled="loadingMore" type="button" @click="loadMore">
                  <span class="redis-tree-symbol">…</span><span>{{ loadingMore ? "扫描中" : "继续扫描" }}</span
                  ><small>CURSOR</small>
                </button>
                <div v-if="keys.length === 0" class="redis-tree-empty">没有匹配的键</div>
              </template>
            </template>
          </template>
        </div>

        <section class="redis-inspector">
          <nav aria-label="Redis 数据库工具">
            <button :class="{ active: inspectorTab === 'structure' }" type="button" @click="inspectorTab = 'structure'">结构</button>
            <button :class="{ active: inspectorTab === 'data' }" type="button" @click="inspectorTab = 'data'">数据</button>
            <button :class="{ active: inspectorTab === 'command' }" type="button" @click="inspectorTab = 'command'">命令</button>
          </nav>
          <div v-if="inspectorTab === 'structure' && overview" class="redis-summary">
            <div class="redis-summary-title">
              <span class="redis-stack-icon">▱</span><strong>DB {{ database }}</strong>
            </div>
            <dl>
              <dt>类型</dt>
              <dd>Redis</dd>
              <dt>匹配</dt>
              <dd>{{ activePattern }}</dd>
              <dt>状态</dt>
              <dd>{{ hasMore ? "扫描中" : "已完成" }}</dd>
            </dl>
            <dl>
              <dt>键</dt>
              <dd>{{ formatCount(overview.keyCount) }}</dd>
              <dt>已加载</dt>
              <dd>{{ keys.length }}</dd>
              <dt>范围</dt>
              <dd>{{ parseKeyspaceInfo(overview.keyspace) }}</dd>
            </dl>
          </div>
          <div v-else-if="inspectorTab === 'data'" class="redis-inspector-hint">选择上方键即可查看和编辑数据</div>
          <div v-else class="redis-command-panel">
            <label><span>逻辑库</span><input v-model="databaseDraft" inputmode="numeric" aria-label="Redis 数据库编号" @keyup.enter="applyDatabase" /></label>
            <button type="button" @click="applyDatabase">切换 DB</button>
          </div>
        </section>
      </div>

      <section v-if="createOpen" class="redis-create-panel redis-create-floating">
        <label><span>KEY</span><input v-model="createKey" autocapitalize="none" placeholder="cache:user:1" /></label>
        <label><span>VALUE</span><textarea v-model="createValue" rows="3"></textarea></label>
        <button :disabled="!canWrite || mutationBusy || !createKey.trim()" type="button" @click="createStringKey">创建 String 键</button>
      </section>
    </template>

    <template v-else>
      <p v-if="error" class="redis-message error" role="alert">{{ error }}</p>
      <p v-else-if="status" class="redis-message success">{{ status }}</p>
      <div v-if="detailLoading" class="redis-loading"><i></i><strong>正在读取键值</strong></div>
      <template v-else>
        <section class="key-identity">
          <small>KEY / {{ detail.type.toUpperCase() }}</small>
          <h5>{{ detail.key }}</h5>
          <div>
            <span
              >TTL <b>{{ formatTtl(detail.ttlMs) }}</b></span
            >
            <span
              >MEM <b>{{ formatBytes(detail.memoryBytes) }}</b></span
            >
            <span v-if="detail.length !== undefined"
              >LEN <b>{{ formatCount(detail.length) }}</b></span
            >
          </div>
        </section>

        <section v-if="connection.isProduction || connection.readOnly" class="write-guard">
          <template v-if="connection.readOnly">
            <strong>READ ONLY</strong>
            <p>此连接已设置为只读，数据编辑入口已锁定。</p>
          </template>
          <template v-else>
            <strong>PRODUCTION GUARD</strong>
            <p>输入完整连接名后才允许修改：{{ connection.name }}</p>
            <input v-model="productionConfirmation" :placeholder="connection.name" />
          </template>
        </section>

        <section class="ttl-console">
          <label><span>TTL / SECONDS</span><input v-model="ttlSeconds" inputmode="numeric" placeholder="例如 3600" /></label>
          <button :disabled="!canWrite || mutationBusy" type="button" @click="updateTtl">设置</button>
          <button :disabled="!canWrite || mutationBusy" type="button" @click="persistKey">永久</button>
        </section>

        <section v-if="detail.type === 'string'" class="value-editor">
          <div class="value-heading">
            <span>STRING VALUE</span><b>{{ stringValue.length }} CHARS</b>
          </div>
          <textarea v-model="stringValue" rows="10" spellcheck="false"></textarea>
          <button :disabled="!canWrite || mutationBusy" type="button" @click="saveString">保存 String</button>
        </section>

        <section v-else-if="detail.type === 'hash'" class="collection-view">
          <div class="value-heading"><span>HASH FIELDS</span><b>PREVIEW 200</b></div>
          <article v-for="pair in detailPairs" :key="pair.left">
            <div>
              <strong>{{ pair.left }}</strong>
              <pre>{{ pair.right }}</pre>
            </div>
            <button :disabled="!canWrite || mutationBusy" type="button" @click="runMutation('hdel', { key: detail.key, field: pair.left }, 'Hash 字段已删除')">删除</button>
          </article>
          <div class="collection-add">
            <input v-model="editField" placeholder="字段" />
            <input v-model="editValue" placeholder="值" />
            <button :disabled="!canWrite || mutationBusy || !editField.trim()" type="button" @click="saveHashField">写入字段</button>
          </div>
        </section>

        <section v-else-if="detail.type === 'list'" class="collection-view">
          <div class="value-heading"><span>LIST ITEMS</span><b>PREVIEW 200</b></div>
          <article v-for="(item, index) in detailList" :key="index">
            <i>{{ index }}</i>
            <pre>{{ String(item ?? "") }}</pre>
            <button :disabled="!canWrite || mutationBusy" type="button" @click="editListItem(index, item)">覆盖</button>
          </article>
          <div class="collection-add">
            <input v-model="editValue" placeholder="追加到 List 尾部" />
            <button :disabled="!canWrite || mutationBusy" type="button" @click="saveListItem()">RPUSH</button>
          </div>
        </section>

        <section v-else-if="detail.type === 'set'" class="collection-view">
          <div class="value-heading"><span>SET MEMBERS</span><b>PREVIEW 200</b></div>
          <article v-for="member in detailList" :key="String(member)">
            <pre>{{ String(member ?? "") }}</pre>
            <button :disabled="!canWrite || mutationBusy" type="button" @click="runMutation('srem', { key: detail.key, member }, 'Set 成员已删除')">删除</button>
          </article>
          <div class="collection-add">
            <input v-model="editMember" placeholder="新成员" />
            <button :disabled="!canWrite || mutationBusy || !editMember" type="button" @click="saveSetMember">SADD</button>
          </div>
        </section>

        <section v-else-if="detail.type === 'zset'" class="collection-view">
          <div class="value-heading"><span>SORTED SET</span><b>PREVIEW 200</b></div>
          <article v-for="pair in detailPairs" :key="pair.left">
            <div>
              <strong>{{ pair.right }}</strong>
              <pre>{{ pair.left }}</pre>
            </div>
            <button :disabled="!canWrite || mutationBusy" type="button" @click="runMutation('zrem', { key: detail.key, member: pair.left }, 'ZSet 成员已删除')">删除</button>
          </article>
          <div class="collection-add zset">
            <input v-model="editMember" placeholder="成员" />
            <input v-model="editScore" inputmode="decimal" placeholder="分数" />
            <button :disabled="!canWrite || mutationBusy || !editMember || !editScore" type="button" @click="saveZsetMember">ZADD</button>
          </div>
        </section>

        <section v-else class="raw-value">
          <div class="value-heading">
            <span>{{ detail.type.toUpperCase() }} PREVIEW</span><b>READ ONLY</b>
          </div>
          <pre>{{ JSON.stringify(detail.value, null, 2) }}</pre>
        </section>

        <button class="delete-key" :disabled="!canWrite || mutationBusy" type="button" @click="deleteKey">永久删除此键</button>
      </template>
    </template>
  </section>
</template>

<style scoped>
.redis-browser {
  --redis: #dc2626;
  --redis-soft: #fff1f2;
  margin-top: 0;
  border: 0;
  background: transparent;
}
.redis-browser-search {
  display: grid;
  min-height: 40px;
  grid-template-columns: 28px minmax(0, 1fr) 40px;
  align-items: center;
  margin-bottom: 5px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--field);
}
.redis-browser-search svg {
  width: 16px;
  height: 16px;
  margin-left: 10px;
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.7;
  stroke-linecap: round;
}
.redis-browser-search input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.redis-browser-search button {
  align-self: stretch;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font-size: 15px;
}
.redis-tree-view {
  display: grid;
  height: calc(100dvh - 205px);
  min-height: 380px;
  grid-template-rows: auto minmax(0, 1fr) auto;
  margin: 0 -2px;
}
.redis-tree-root,
.redis-tree-node {
  display: flex;
  align-items: center;
  color: var(--ink);
  font-family: "PingFang SC", system-ui, sans-serif;
}
.redis-tree-root {
  min-height: 29px;
  gap: 7px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 550;
}
.redis-tree-root svg,
.redis-tree-node svg {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.55;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.redis-tree {
  overflow-y: auto;
  min-height: 160px;
  padding-bottom: 8px;
  scrollbar-width: none;
}
.redis-tree::-webkit-scrollbar {
  display: none;
}
.redis-tree-node {
  width: 100%;
  min-height: 28px;
  gap: 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  padding: 2px 6px;
  text-align: left;
  font-size: 10px;
  line-height: 1.2;
}
.redis-tree-node:active {
  background: color-mix(in srgb, var(--accent-soft) 68%, transparent);
}
.redis-tree-node.database {
  padding-left: 18px;
}
.redis-tree-group-row {
  position: relative;
}
.redis-tree-node.group {
  padding-left: 35px;
}
.redis-tree-node.leaf {
  padding-left: 58px;
}
.redis-chevron {
  width: 10px;
  flex: 0 0 10px;
  color: var(--muted);
  font-size: 16px;
  line-height: 1;
  transition: transform 120ms ease;
}
.redis-tree-node.expanded > .redis-chevron {
  transform: rotate(90deg);
}
.redis-tree-symbol {
  width: 15px;
  flex: 0 0 15px;
  color: var(--redis);
  text-align: center;
  font-size: 10px;
}
.redis-tree-node > small {
  overflow: hidden;
  margin-left: auto;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-tree-node.leaf > span:nth-child(2) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-tree-node.load {
  color: var(--acid);
}
.redis-tree-add {
  position: absolute;
  top: 3px;
  right: 6px;
  width: 24px;
  height: 22px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--acid);
  font-size: 15px;
}
.redis-tree-add:disabled {
  color: var(--faint);
}
.redis-tree-empty {
  min-height: 34px;
  padding: 9px 12px 9px 80px;
  color: var(--muted);
  font-size: 9px;
}
.redis-inspector {
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: 12px 12px 0 0;
  background: color-mix(in srgb, var(--panel) 96%, transparent);
  box-shadow: 0 -7px 22px rgba(0, 0, 0, 0.04);
}
.redis-inspector nav {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-bottom: 1px solid var(--line);
}
.redis-inspector nav button {
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 10px;
}
.redis-inspector nav button.active {
  color: var(--acid);
  box-shadow: inset 0 -2px var(--acid);
}
.redis-summary {
  display: grid;
  min-height: 104px;
  grid-template-columns: minmax(92px, 0.85fr) 1fr 1fr;
  gap: 11px;
  padding: 13px 12px 15px;
}
.redis-summary-title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding-top: 2px;
  font-size: 11px;
}
.redis-stack-icon {
  color: var(--redis);
  font-size: 20px;
  line-height: 1;
}
.redis-summary dl {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-content: start;
  gap: 7px 8px;
  margin: 0;
  padding-left: 10px;
  border-left: 1px solid var(--line);
  font-size: 8px;
}
.redis-summary dt {
  color: var(--muted);
}
.redis-summary dd {
  overflow: hidden;
  margin: 0;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-inspector-hint {
  min-height: 90px;
  padding: 24px 14px;
  color: var(--muted);
  text-align: center;
  font-size: 10px;
}
.redis-command-panel {
  display: grid;
  min-height: 90px;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 14px;
}
.redis-command-panel label {
  display: grid;
  gap: 4px;
  color: var(--muted);
  font-size: 8px;
}
.redis-command-panel input,
.redis-command-panel button {
  min-height: 36px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--field);
  padding: 0 10px;
  color: var(--ink);
  font: inherit;
  font-size: 9px;
}
.redis-command-panel button {
  align-self: end;
  color: var(--acid);
}
.redis-create-floating {
  position: fixed;
  z-index: 20;
  right: 12px;
  bottom: calc(88px + env(safe-area-inset-bottom));
  left: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: 0 16px 46px rgba(0, 0, 0, 0.2);
}
.redis-masthead {
  display: flex;
  min-height: 126px;
  align-items: center;
  justify-content: space-between;
  overflow: hidden;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(118deg, #fff 0 66%, var(--redis-soft) 66%);
  padding: 20px;
}
.redis-masthead span,
.key-list-heading span,
.value-heading span {
  color: var(--redis);
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.16em;
}
.redis-masthead h4 {
  margin: 8px 0 4px;
  font-size: 23px;
  letter-spacing: -0.04em;
}
.redis-masthead p {
  margin: 0;
  color: var(--muted);
  font-size: 9px;
}
.pulse-orbit {
  position: relative;
  display: grid;
  width: 64px;
  height: 64px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgba(220, 38, 38, 0.24);
  border-radius: 50%;
}
.pulse-orbit::before,
.pulse-orbit i {
  position: absolute;
  border: 1px solid rgba(220, 38, 38, 0.18);
  border-radius: 50%;
  content: "";
}
.pulse-orbit::before {
  inset: 7px;
}
.pulse-orbit i {
  inset: 15px;
  animation: redis-pulse 2s ease-in-out infinite;
}
.pulse-orbit b {
  position: relative;
  color: var(--redis);
  font-size: 18px;
}
.redis-console,
.ttl-console {
  display: grid;
  grid-template-columns: 1fr auto;
  border-bottom: 1px solid var(--line);
}
.redis-console {
  grid-template-columns: 68px 42px minmax(0, 1fr) 42px;
  gap: 6px;
  margin-bottom: 7px;
  border-bottom: 0;
}
.redis-console label,
.ttl-console label {
  display: grid;
  align-content: center;
  min-height: 39px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--field);
  padding: 5px 9px;
}
.redis-console label span,
.ttl-console label span {
  color: var(--faint);
  font-size: 7px;
  letter-spacing: 0.12em;
}
.redis-console input,
.ttl-console input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  padding: 5px 0 0;
  color: var(--ink);
  font-size: 11px;
}
.redis-console button,
.ttl-console button {
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  color: var(--acid);
  font-size: 9px;
}
.redis-console button:last-child,
.ttl-console button:last-child {
  border-right: 0;
}
.redis-metrics {
  display: grid;
  grid-template-columns: 0.8fr 0.8fr 1.4fr;
  margin-bottom: 7px;
  border: 1px solid var(--line);
  border-radius: 7px;
}
.redis-metrics article {
  display: grid;
  min-height: 58px;
  align-content: center;
  border-right: 1px solid var(--line);
  padding: 8px 10px;
}
.redis-metrics article:nth-child(2) {
  border-right: 1px solid var(--line);
}
.redis-metrics .metric-wide {
  grid-column: auto;
  min-height: 58px;
  border-top: 0;
  border-right: 0;
}
.redis-metrics small {
  color: var(--faint);
  font-size: 7px;
  letter-spacing: 0.12em;
}
.redis-metrics strong {
  margin: 4px 0 1px;
  font-size: 14px;
}
.redis-metrics span {
  overflow: hidden;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.key-list-heading,
.value-heading {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 0;
  padding: 0 7px;
}
.key-list-heading div {
  display: grid;
  gap: 3px;
}
.key-list-heading strong,
.value-heading b {
  color: var(--muted);
  font-size: 8px;
  font-weight: 600;
}
.key-list-heading button {
  border: 1px solid var(--acid);
  border-radius: 5px;
  background: transparent;
  padding: 7px 10px;
  color: var(--acid);
  font-size: 8px;
}
.redis-key-list > button {
  display: grid;
  width: 100%;
  min-height: 47px;
  grid-template-columns: 34px minmax(0, 1fr) 18px;
  align-items: center;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 0 8px;
  text-align: left;
}
.redis-key-list > button:active {
  background: var(--redis-soft);
}
.redis-key-list i {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 1px solid rgba(220, 38, 38, 0.28);
  color: var(--redis);
  font-size: 7px;
  font-style: normal;
}
.redis-key-list span {
  min-width: 0;
}
.redis-key-list small {
  display: block;
  margin-bottom: 3px;
  color: var(--faint);
  font-size: 7px;
  letter-spacing: 0.06em;
}
.redis-key-list strong {
  display: block;
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-key-list b {
  color: var(--faint);
  font-size: 18px;
  font-weight: 400;
}
.redis-load-more {
  width: 100%;
  min-height: 48px;
  border: 0;
  background: #f8fafc;
  color: var(--acid);
  font-size: 9px;
}
.redis-loading,
.redis-empty {
  display: grid;
  min-height: 180px;
  place-items: center;
  align-content: center;
  gap: 12px;
  padding: 24px;
  text-align: center;
}
.redis-loading i {
  width: 24px;
  height: 24px;
  border: 2px solid var(--line);
  border-top-color: var(--redis);
  border-radius: 50%;
  animation: spin 800ms linear infinite;
}
.redis-loading strong,
.redis-empty strong {
  font-size: 11px;
}
.redis-empty p {
  margin: 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.6;
}
.redis-message {
  margin: 0;
  border-bottom: 1px solid var(--line);
  padding: 11px 14px;
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.5;
}
.redis-message.error {
  background: #fff1f2;
  color: var(--danger);
}
.redis-message.success {
  background: #eff6ff;
  color: var(--acid);
}
.redis-detail-toolbar {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding: 0 12px;
}
.redis-detail-toolbar button {
  border: 0;
  background: transparent;
  color: var(--acid);
  font-size: 9px;
}
.redis-detail-toolbar span {
  color: var(--redis);
  font-size: 8px;
  letter-spacing: 0.14em;
}
.key-identity {
  border-bottom: 1px solid var(--line);
  background: linear-gradient(135deg, var(--redis-soft), #fff 45%);
  padding: 18px 16px;
}
.key-identity small {
  color: var(--redis);
  font-size: 8px;
  letter-spacing: 0.14em;
}
.key-identity h5 {
  overflow-wrap: anywhere;
  margin: 9px 0 18px;
  font-size: 17px;
  line-height: 1.35;
}
.key-identity > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.key-identity > div span {
  border: 1px solid var(--line);
  background: #fff;
  padding: 6px 8px;
  color: var(--muted);
  font-size: 7px;
}
.key-identity b {
  margin-left: 4px;
  color: var(--ink);
}
.write-guard {
  border-bottom: 1px solid var(--line);
  background: #fffbeb;
  padding: 12px 14px;
}
.write-guard strong {
  color: var(--amber);
  font-size: 8px;
  letter-spacing: 0.12em;
}
.write-guard p {
  margin: 6px 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
}
.write-guard input {
  width: 100%;
  min-height: 38px;
  border: 1px solid var(--line);
  outline: 0;
  background: #fff;
  padding: 0 10px;
  font-size: 9px;
}
.value-editor,
.collection-view,
.raw-value {
  border-bottom: 1px solid var(--line);
  background: #fff;
}
.value-editor textarea,
.redis-create-panel textarea {
  display: block;
  width: calc(100% - 24px);
  min-height: 140px;
  resize: vertical;
  margin: 12px;
  border: 1px solid var(--line);
  outline: 0;
  background: #f8fafc;
  padding: 12px;
  color: var(--ink);
  font-size: 10px;
  line-height: 1.6;
}
.value-editor > button {
  width: 100%;
  min-height: 46px;
  border: 0;
  border-top: 1px solid var(--line);
  background: var(--acid);
  color: #fff;
  font-size: 9px;
}
.collection-view > article {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--line);
  padding: 9px 12px;
}
.collection-view article > div,
.collection-view article > pre {
  min-width: 0;
  flex: 1;
}
.collection-view article strong {
  display: block;
  overflow-wrap: anywhere;
  color: var(--acid);
  font-size: 9px;
}
.collection-view pre,
.raw-value pre {
  overflow: auto;
  margin: 3px 0 0;
  color: var(--ink);
  font: inherit;
  font-size: 9px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.collection-view article > i {
  color: var(--faint);
  font-size: 8px;
  font-style: normal;
}
.collection-view article > button {
  flex: 0 0 auto;
  border: 1px solid var(--line);
  background: #fff;
  padding: 6px 8px;
  color: var(--danger);
  font-size: 8px;
}
.collection-add {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 7px;
  padding: 10px;
}
.collection-add:not(.zset):has(input:only-of-type) {
  grid-template-columns: 1fr auto;
}
.collection-add input,
.redis-create-panel input {
  min-width: 0;
  min-height: 39px;
  border: 1px solid var(--line);
  outline: 0;
  padding: 0 9px;
  font-size: 9px;
}
.collection-add button,
.redis-create-panel > button {
  border: 0;
  background: var(--acid);
  padding: 0 12px;
  color: #fff;
  font-size: 8px;
}
.raw-value pre {
  max-height: 420px;
  margin: 0;
  padding: 14px;
}
.delete-key {
  width: 100%;
  min-height: 48px;
  border: 0;
  background: #fff1f2;
  color: var(--danger);
  font-size: 9px;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.42;
}
.redis-create-panel {
  display: grid;
  gap: 9px;
  border-bottom: 1px solid var(--line);
  background: var(--redis-soft);
  padding: 12px;
}
.redis-create-panel label {
  display: grid;
  gap: 5px;
}
.redis-create-panel label span {
  color: var(--faint);
  font-size: 7px;
  letter-spacing: 0.12em;
}
.redis-create-panel textarea {
  width: 100%;
  min-height: 70px;
  margin: 0;
  background: #fff;
}
.redis-create-panel > button {
  min-height: 42px;
}
@media (max-width: 390px) {
  .redis-console {
    grid-template-columns: 64px 40px minmax(0, 1fr) 40px;
  }
  .redis-console label {
    padding-inline: 8px;
  }
  .collection-add {
    grid-template-columns: 1fr;
  }
  .collection-add button {
    min-height: 38px;
  }
}
@keyframes redis-pulse {
  50% {
    inset: 10px;
    opacity: 0.35;
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
