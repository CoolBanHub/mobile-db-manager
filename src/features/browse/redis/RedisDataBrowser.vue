<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import InlineSelect from "@/components/InlineSelect.vue";
import { loadDirectRedisKey, loadDirectRedisOverview, mutateDirectRedis, scanDirectRedisKeys } from "@/lib/direct/redis";
import type { MobileConnectionSummary, MobileRedisKeyDetail, MobileRedisOverview } from "@/lib/mobileTypes";

const props = defineProps<{ connection: MobileConnectionSummary }>();

const database = ref(Math.max(0, Number.parseInt(props.connection.database ?? "0", 10) || 0));
const databaseDraft = ref(String(database.value));
const overview = ref<MobileRedisOverview | null>(null);
const keys = ref<string[]>([]);
const cursor = ref("0");
const patternDraft = ref("");
const activePattern = ref("*");
const typeFilter = ref("all");
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
const keyDetails = ref<Record<string, MobileRedisKeyDetail>>({});

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
const redisTypeOptions = [
  { value: "all", label: "所有类型" }, { value: "string", label: "String" }, { value: "hash", label: "Hash" },
  { value: "list", label: "List" }, { value: "set", label: "Set" }, { value: "zset", label: "ZSet" }, { value: "stream", label: "Stream" },
];
const redisDatabaseOptions = computed(() => redisDatabases.value.map((item) => ({ value: String(item.index), label: `DB ${item.index} · ${formatCount(item.keyCount)}` })));
const filteredKeys = computed(() =>
  keys.value.filter((key) => typeFilter.value === "all" || !keyDetails.value[key] || keyDetails.value[key]?.type === typeFilter.value),
);

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
  if (value < 60_000) return `${Math.ceil(value / 1_000)}s`;
  if (value < 3_600_000) return `${Math.ceil(value / 60_000)}m`;
  return `${Math.ceil(value / 3_600_000)}h`;
}

function valuePreview(item: MobileRedisKeyDetail | undefined) {
  if (!item) return "读取中…";
  if (item.type === "string") return String(item.value ?? "");
  const values = Array.isArray(item.value) ? item.value.map((value) => String(value ?? "")) : [];
  if (item.type === "hash" || item.type === "zset") return `${Math.floor(values.length / 2)} fields`;
  if (item.type === "set") return `${values.length} fields`;
  return `${values.length} items`;
}

async function hydrateKeyDetails(keyNames: string[]) {
  const sourceDatabase = database.value;
  const queue = keyNames.filter((key) => !keyDetails.value[key]);
  let index = 0;
  const worker = async () => {
    while (index < queue.length) {
      const key = queue[index++];
      try {
        const item = await loadDirectRedisKey(props.connection.id, sourceDatabase, key);
        if (database.value !== sourceDatabase) return;
        if (!keys.value.includes(key)) continue;
        keyDetails.value = { ...keyDetails.value, [key]: item };
      } catch {
        // 单个键可能在 SCAN 后过期；表格保留该行并显示未知摘要，不阻断其他键。
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(5, queue.length) }, () => worker()));
}

async function reload() {
  loading.value = true;
  error.value = "";
  status.value = "";
  detail.value = null;
  createOpen.value = false;
  keys.value = [];
  keyDetails.value = {};
  cursor.value = "0";
  try {
    // 概览与首屏 SCAN 相互独立，并行加载可减少移动网络下的等待时间。
    const [nextOverview, page] = await Promise.all([loadDirectRedisOverview(props.connection.id, database.value), scanDirectRedisKeys(props.connection.id, database.value, "0", activePattern.value)]);
    overview.value = nextOverview;
    keys.value = page.keys;
    cursor.value = page.cursor;
    void hydrateKeyDetails(page.keys);
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

function applyDatabaseValue(value: string) {
  databaseDraft.value = value;
  void applyDatabase();
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
    void hydrateKeyDetails(page.keys);
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "继续扫描 Redis 键失败";
  } finally {
    loadingMore.value = false;
  }
}

function handleKeyScroll(event: Event) {
  const target = event.currentTarget as HTMLElement;
  if (target.scrollTop + target.clientHeight >= target.scrollHeight - 56) void loadMore();
}

async function openKey(key: string) {
  detailLoading.value = true;
  error.value = "";
  status.value = "";
  createOpen.value = false;
  try {
    detail.value = await loadDirectRedisKey(props.connection.id, database.value, key);
    keyDetails.value = { ...keyDetails.value, [key]: detail.value };
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
  const nextDetails = { ...keyDetails.value };
  delete nextDetails[key];
  keyDetails.value = nextDetails;
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
        <input v-model="patternDraft" type="search" autocapitalize="none" placeholder="搜索键或命名空间" @keyup.enter="applySearch" />
        <button type="button" aria-label="筛选 Redis 键" @click="applySearch">≡</button>
      </section>

      <p v-if="error" class="redis-message error" role="alert">{{ error }}</p>
      <p v-else-if="status" class="redis-message success">{{ status }}</p>

      <div v-if="loading" class="redis-loading"><i></i><strong>正在扫描键空间</strong></div>
      <section v-else class="redis-table-view">
        <div class="redis-table-tools">
          <label>
            <span>类型</span>
            <InlineSelect v-model="typeFilter" :options="redisTypeOptions" ariaLabel="Redis 键类型" />
          </label>
          <label>
            <span>逻辑库</span>
            <InlineSelect :model-value="databaseDraft" :options="redisDatabaseOptions" ariaLabel="Redis 逻辑库" @change="applyDatabaseValue" />
          </label>
          <button :disabled="!canWrite" type="button" @click="createOpen = !createOpen">＋ 新增</button>
        </div>

        <div class="redis-scan-summary">
          <span>SCAN 游标分批加载</span>
          <strong>{{ formatCount(overview?.keyCount) }} 个键</strong>
        </div>

        <div class="redis-table-scroll" @scroll.passive="handleKeyScroll">
          <table aria-label="Redis 键列表">
            <colgroup><col class="key-column" /><col class="type-column" /><col class="value-column" /><col class="ttl-column" /></colgroup>
            <thead><tr><th>键</th><th>类型</th><th>值</th><th>TTL</th></tr></thead>
            <tbody>
              <tr v-for="key in filteredKeys" :key="key" @click="openKey(key)">
                <td><button class="redis-key-cell" type="button" :aria-label="`打开 ${key}`" :title="key">{{ key }}</button></td>
                <td><span class="redis-type" :class="keyDetails[key]?.type">{{ keyDetails[key]?.type || "…" }}</span></td>
                <td><span class="redis-value-preview" :title="valuePreview(keyDetails[key])">{{ valuePreview(keyDetails[key]) }}</span></td>
                <td><span class="redis-ttl">{{ keyDetails[key] ? formatTtl(keyDetails[key].ttlMs) : "—" }}</span></td>
              </tr>
            </tbody>
          </table>
          <div v-if="filteredKeys.length === 0" class="redis-tree-empty">没有匹配的键</div>
        </div>

      </section>

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
.redis-table-view {
  display: grid;
  height: calc(100dvh - 205px);
  min-height: 380px;
  grid-template-rows: auto minmax(0, 1fr) auto;
  margin: 0 -2px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px 7px 0 0;
  background: var(--panel);
}
.redis-table-tools {
  display: grid;
  min-height: 41px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: center;
  border-bottom: 1px solid var(--line);
  background: var(--field);
}
.redis-table-tools label {
  display: grid;
  min-width: 0;
  align-self: stretch;
  align-content: center;
  gap: 1px;
  border-right: 1px solid var(--line);
  padding: 4px 8px;
}
.redis-table-tools label > span {
  color: var(--faint);
  font-size: 6px;
  letter-spacing: 0.08em;
}
.redis-table-tools select {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 8px;
}
.redis-table-tools > button {
  align-self: stretch;
  border: 0;
  background: transparent;
  padding: 0 10px;
  color: var(--acid);
  font: inherit;
  font-size: 8px;
  font-weight: 650;
}
.redis-table-scroll {
  overflow: auto;
  min-height: 0;
  scrollbar-color: color-mix(in srgb, var(--muted) 38%, transparent) transparent;
  scrollbar-width: thin;
}
.redis-table-scroll table {
  width: 100%;
  min-width: 420px;
  border-collapse: collapse;
  table-layout: fixed;
  color: var(--ink);
  font-family: "PingFang SC", system-ui, sans-serif;
}
.redis-table-scroll .key-column {
  width: 39%;
}
.redis-table-scroll .type-column {
  width: 17%;
}
.redis-table-scroll .value-column {
  width: 29%;
}
.redis-table-scroll .ttl-column {
  width: 15%;
}
.redis-table-scroll th {
  position: sticky;
  z-index: 2;
  top: 0;
  height: 29px;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--field) 94%, var(--panel));
  padding: 0 8px;
  color: var(--muted);
  text-align: left;
  font-size: 7px;
  font-weight: 650;
}
.redis-table-scroll td {
  height: 34px;
  overflow: hidden;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  padding: 0 8px;
  font-size: 8px;
}
.redis-table-scroll th:last-child,
.redis-table-scroll td:last-child {
  border-right: 0;
}
.redis-table-scroll tr.namespace {
  background: color-mix(in srgb, var(--field) 75%, transparent);
  font-weight: 580;
}
.redis-table-scroll tbody tr.key:active {
  background: color-mix(in srgb, var(--accent-soft) 64%, transparent);
}
.redis-key-cell {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 33px;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  padding: 0 0 0 calc(var(--key-depth) * 14px);
  color: inherit;
  text-align: left;
}
.redis-key-cell svg {
  width: 13px;
  height: 13px;
  flex: 0 0 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.55;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.redis-key-cell > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-chevron,
.redis-key-spacer {
  width: 8px;
  flex: 0 0 8px;
}
.redis-chevron {
  color: var(--muted);
  font-size: 14px;
  line-height: 1;
  transition: transform 120ms ease;
}
.redis-chevron.expanded {
  transform: rotate(90deg);
}
.redis-type,
.namespace-type {
  display: inline-flex;
  max-width: 100%;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 3px;
  background: var(--panel);
  padding: 2px 4px;
  color: var(--muted);
  font-size: 6px;
  letter-spacing: 0.04em;
  text-overflow: ellipsis;
  text-transform: uppercase;
  white-space: nowrap;
}
.redis-type.string {
  border-color: color-mix(in srgb, #16a34a 32%, var(--line));
  background: color-mix(in srgb, #dcfce7 44%, var(--panel));
  color: #15803d;
}
.redis-type.hash,
.redis-type.zset {
  border-color: color-mix(in srgb, #d97706 34%, var(--line));
  background: color-mix(in srgb, #fef3c7 46%, var(--panel));
  color: #b45309;
}
.redis-type.list,
.redis-type.stream {
  border-color: color-mix(in srgb, var(--acid) 38%, var(--line));
  background: color-mix(in srgb, var(--accent-soft) 58%, var(--panel));
  color: var(--acid);
}
.redis-type.set {
  border-color: color-mix(in srgb, #7c3aed 34%, var(--line));
  background: color-mix(in srgb, #ede9fe 48%, var(--panel));
  color: #6d28d9;
}
.redis-value-preview,
.redis-ttl,
.namespace-count {
  display: block;
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-table-footer {
  display: grid;
  min-height: 45px;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  border-top: 1px solid var(--line);
  padding: 5px 9px;
  color: var(--muted);
  font-size: 7px;
}
.redis-table-footer > div {
  display: grid;
  min-width: 0;
  gap: 1px;
}
.redis-table-footer strong {
  color: var(--ink);
  font-size: 8px;
}
.redis-table-footer > div span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.redis-table-footer button {
  min-height: 27px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--panel);
  padding: 0 8px;
  color: var(--acid);
  font-size: 7px;
}
.redis-tree-empty {
  min-height: 80px;
  padding: 30px 12px;
  color: var(--muted);
  text-align: center;
  font-size: 9px;
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

.redis-browser {
  min-width: 0;
  max-width: 100%;
  overflow-x: clip;
  padding-bottom: var(--space-3);
}
.redis-browser-search,
.redis-table-view,
.key-identity,
.ttl-console,
.value-editor,
.collection-view,
.raw-value,
.redis-create-panel {
  border-color: var(--divider-color);
  background: var(--card-background);
}
.redis-browser-search,
.redis-table-view,
.key-identity,
.ttl-console,
.value-editor,
.collection-view,
.raw-value,
.redis-create-panel {
  border-radius: var(--radius-card);
}
.redis-table-scroll,
.value-editor textarea,
.raw-value pre,
.collection-row code {
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  overscroll-behavior-x: contain;
}
.redis-table-scroll th {
  background: var(--input-background);
  color: var(--primary);
}
.write-guard {
  border-color: color-mix(in srgb, var(--warning) 36%, var(--divider-color));
  border-radius: var(--radius-card);
  background: color-mix(in srgb, var(--warning) 7%, var(--card-background));
}
.write-guard:has(input) {
  border-color: color-mix(in srgb, var(--danger) 38%, var(--divider-color));
  background: color-mix(in srgb, var(--danger) 6%, var(--card-background));
}
.delete-key {
  border: 1px solid color-mix(in srgb, var(--danger) 32%, var(--divider-color));
  background: color-mix(in srgb, var(--danger) 7%, var(--card-background));
}
.redis-browser-search { min-height: 48px; border-radius: var(--radius-card); box-shadow: 0 5px 18px rgba(23, 32, 51, .035); }
.redis-browser-search input { min-height: 46px; font-size: 12px; }
.redis-table-view { overflow: hidden; box-shadow: 0 7px 22px rgba(23, 32, 51, .04); }
.redis-table-view header { background: var(--primary-soft); }
.key-identity, .ttl-console, .value-editor, .collection-view { box-shadow: 0 7px 22px rgba(23, 32, 51, .04); }
/* Prototype list layout */
.redis-browser { gap: 8px; }
.redis-browser-search { min-height: 47px; border: 1px solid var(--divider-color); border-radius: 6px; box-shadow: none; }
.redis-browser-search input { min-height: 45px; font-size: 12px; }
.redis-browser-search button { display:grid; width:44px; place-items:center; border-left:1px solid var(--divider-color); font-size:0; }
.redis-browser-search button::before { width: 16px; height: 12px; background: linear-gradient(var(--text-secondary),var(--text-secondary)) 0 0/16px 1px no-repeat,linear-gradient(var(--text-secondary),var(--text-secondary)) 3px 5px/10px 1px no-repeat,linear-gradient(var(--text-secondary),var(--text-secondary)) 6px 10px/4px 1px no-repeat; content:""; }
.redis-table-view { height:auto; min-height:0; grid-template-rows:auto auto minmax(0,1fr); gap:0; margin:0; overflow:visible; border:0; border-radius:0; background:transparent; box-shadow:none; }
.redis-table-tools { grid-template-columns: minmax(0,1fr) minmax(0,1.28fr) auto; gap: 8px; }
.redis-table-tools label { align-content:stretch; border:0; padding:0; }
.redis-table-tools label span { margin-bottom: 6px; color: var(--text-secondary); font-size: 10px; }
.redis-table-tools select,.redis-table-tools > button { min-height:44px; border:1px solid var(--divider-color); border-radius:6px; background:var(--input-background); padding:0 11px; font-size:11px; }
.redis-table-tools label:nth-child(2) select { border-color: #e6a000; box-shadow: inset 0 0 0 1px #e6a000; }
.redis-table-tools > button { background:var(--card-background); color:var(--primary); white-space:nowrap; }
.redis-scan-summary { display:flex; min-height:43px; align-items:center; justify-content:space-between; padding:0 8px; color:var(--text-secondary); font-size:10px; }
.redis-scan-summary strong { color:var(--text-primary); font-size:10px; }
.redis-table-scroll { height:calc(100dvh - 350px); min-height:300px; max-height:none; border:1px solid var(--divider-color); border-radius:0; }
.redis-table-scroll table { min-width:100%; font-size:10px; }
.redis-table-scroll th { height:40px; background:var(--input-background); color:var(--text-secondary); }
.redis-table-scroll td { height:40px; }
.redis-key-cell { display:block; height:40px; overflow:hidden; padding:0 7px; font-size:10px; line-height:40px; text-overflow:ellipsis; white-space:nowrap; }
.redis-type { border:1px solid #9dc8ff; border-radius:3px; background:#edf6ff; padding:2px 4px; color:var(--primary); font-size:8px; text-transform:uppercase; }
.redis-table-footer { display:none; }
</style>
