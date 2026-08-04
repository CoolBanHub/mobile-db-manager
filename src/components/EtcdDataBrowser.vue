<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  loadDirectEtcdEntries,
  loadDirectEtcdEntry,
  loadDirectEtcdOverview,
  mutateDirectEtcd,
} from "../lib/directDatabase";
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

// 这里只控制交互状态，真正的写权限和生产确认由原生插件再次校验。
const canWrite = computed(() =>
  !props.connection.readOnly
  && (!props.connection.isProduction || productionConfirmation.value === props.connection.name),
);
const binaryValue = computed(() => detail.value?.value.startsWith("base64:") === true);

function handleBack() {
  if (createOpen.value) {
    createOpen.value = false;
    return true;
  }
  if (detail.value) {
    detail.value = null;
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

function keyBadge(key: string) {
  const part = key.split("/").filter(Boolean).at(0) ?? "key";
  return part.slice(0, 3).toUpperCase();
}

async function reload() {
  loading.value = true;
  error.value = "";
  status.value = "";
  try {
    // 集群状态和键范围查询可以并行；范围结果在原生层限制为最多 200 条。
    const [nextOverview, page] = await Promise.all([
      loadDirectEtcdOverview(props.connection.id),
      loadDirectEtcdEntries(props.connection.id, activePrefix.value),
    ]);
    overview.value = nextOverview;
    entries.value = page.entries;
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
    await mutateDirectEtcd(
      props.connection.id,
      "put",
      key.trim(),
      value,
      productionConfirmation.value,
      lease,
    );
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
    await mutateDirectEtcd(
      props.connection.id,
      "delete",
      detail.value.key,
      "",
      productionConfirmation.value,
    );
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
    <header class="browser-header">
      <div><small>ETCD V3 / KEYSPACE</small><h4>{{ connection.name }}</h4><p>{{ connection.host }}:{{ connection.port }}</p></div>
      <button type="button" @click="reload">↻</button>
    </header>

    <div v-if="overview" class="overview-grid">
      <article><small>VERSION</small><strong>{{ overview.version }}</strong></article>
      <article><small>KEYS</small><strong>{{ overview.keyCount }}</strong></article>
      <article><small>DB SIZE</small><strong>{{ formatBytes(overview.dbSize) }}</strong></article>
    </div>

    <label v-if="connection.isProduction" class="production-confirm">
      <span>生产写入确认：输入连接名称</span>
      <input v-model="productionConfirmation" :placeholder="connection.name" />
    </label>

    <p v-if="error" class="message danger">{{ error }}</p>
    <p v-else-if="status" class="message">{{ status }}</p>

    <template v-if="detail">
      <button class="back-action" type="button" @click="detail = null">← 返回键列表</button>
      <article class="detail-card">
        <small>KEY</small><h5>{{ detail.key }}</h5>
        <div class="revision-grid">
          <span>创建版本 <b>{{ detail.createRevision }}</b></span>
          <span>修改版本 <b>{{ detail.modRevision }}</b></span>
          <span>版本 <b>{{ detail.version }}</b></span>
          <span>租约 <b>{{ detail.lease === '0' ? '无' : detail.lease }}</b></span>
        </div>
        <label><span>值</span><textarea v-model="valueDraft" rows="12" :readonly="!canWrite || binaryValue"></textarea></label>
        <p v-if="binaryValue" class="binary-note">这是非 UTF-8 二进制值，当前版本仅以 Base64 预览，已禁止文本覆盖以避免损坏数据。</p>
        <div class="detail-actions">
          <button :disabled="!canWrite || binaryValue || busy" type="button" @click="saveDetail">保存值</button>
          <button class="danger-action" :disabled="!canWrite || busy" type="button" @click="deleteEntry">删除键</button>
        </div>
      </article>
    </template>

    <template v-else>
      <form class="prefix-search" @submit.prevent="applyPrefix">
        <input v-model="prefixDraft" placeholder="键前缀，例如 /services/" autocapitalize="none" />
        <button type="submit">查询</button>
        <button :disabled="!canWrite" type="button" @click="createOpen = !createOpen">＋ 新增</button>
      </form>

      <form v-if="createOpen" class="create-card" @submit.prevent="createEntry">
        <label><span>键</span><input v-model="createKey" placeholder="/config/example" autocapitalize="none" /></label>
        <label><span>值</span><textarea v-model="createValue" rows="5"></textarea></label>
        <button :disabled="!canWrite || busy" type="submit">写入 etcd</button>
      </form>

      <div v-if="loading" class="browser-state">正在读取 etcd 键空间…</div>
      <div v-else-if="entries.length === 0" class="browser-state">没有匹配的键</div>
      <div v-else class="key-list">
        <button v-for="entry in entries" :key="entry.key" type="button" @click="openEntry(entry.key)">
          <i>{{ keyBadge(entry.key) }}</i>
          <span><strong>{{ entry.key }}</strong><small>{{ entry.value || '空值' }}</small></span>
          <b>›</b>
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.etcd-browser { margin-top: 18px; }
.browser-header { display: flex; align-items: flex-start; justify-content: space-between; border-bottom: 1px solid var(--line); padding: 0 0 16px; }
.browser-header small, .detail-card > small { color: var(--acid); font-size: 8px; letter-spacing: .14em; }
.browser-header h4 { margin: 6px 0 0; font-size: 22px; }
.browser-header p { margin: 5px 0 0; color: var(--muted); font-size: 9px; }
.browser-header button { border: 1px solid var(--line); background: var(--panel); padding: 9px 12px; color: var(--acid); }
.overview-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; margin: 13px 0; }
.overview-grid article { min-width: 0; border: 1px solid var(--line); background: var(--panel); padding: 11px; }
.overview-grid small { display: block; color: var(--muted); font-size: 7px; }
.overview-grid strong { display: block; overflow: hidden; margin-top: 6px; font-size: 11px; text-overflow: ellipsis; }
.prefix-search { display: grid; grid-template-columns: 1fr auto auto; gap: 6px; margin: 13px 0; }
.prefix-search input, .production-confirm input, .create-card input, .detail-card textarea, .create-card textarea { width: 100%; border: 1px solid var(--line); border-radius: 0; outline: 0; background: #080a09; padding: 11px; color: var(--ink); font-size: 11px; }
.prefix-search button, .create-card button, .detail-actions button, .back-action { border: 1px solid var(--line); background: var(--panel); padding: 10px 12px; color: var(--acid); font-size: 9px; }
.prefix-search button:disabled, .detail-actions button:disabled, .create-card button:disabled { opacity: .35; }
.key-list { display: grid; gap: 6px; }
.key-list button { display: grid; grid-template-columns: 38px 1fr auto; align-items: center; gap: 10px; border: 1px solid var(--line); background: var(--panel); padding: 11px; color: var(--ink); text-align: left; }
.key-list i { display: grid; width: 38px; height: 38px; place-items: center; background: rgba(199,255,61,.08); color: var(--acid); font-size: 8px; font-style: normal; }
.key-list span { min-width: 0; }
.key-list strong, .key-list small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.key-list strong { font-size: 11px; }
.key-list small { margin-top: 5px; color: var(--muted); font-size: 8px; }
.key-list > button > b { color: var(--acid); font-size: 18px; }
.browser-state { min-height: 150px; border: 1px dashed var(--line); padding: 35px 18px; color: var(--muted); font-size: 11px; }
.message { margin: 10px 0; border-left: 2px solid var(--acid); background: rgba(199,255,61,.04); padding: 9px 11px; color: var(--muted); font-size: 9px; }
.message.danger { border-left-color: var(--danger); color: #ff918d; }
.production-confirm, .create-card label, .detail-card label { display: block; margin: 12px 0; }
.production-confirm span, .create-card label span, .detail-card label span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 9px; }
.create-card, .detail-card { border: 1px solid var(--line); background: var(--panel); padding: 14px; }
.detail-card h5 { overflow-wrap: anywhere; margin: 7px 0 12px; font-size: 15px; }
.revision-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.revision-grid span { border: 1px solid var(--line); padding: 8px; color: var(--muted); font-size: 8px; }
.revision-grid b { display: block; margin-top: 4px; color: var(--ink); }
.detail-card textarea, .create-card textarea { resize: vertical; line-height: 1.55; }
.detail-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.detail-actions .danger-action { color: #ff918d; }
.binary-note { border-left: 2px solid var(--amber); padding: 8px 10px; color: var(--muted); font-size: 9px; line-height: 1.5; }
.back-action { margin: 12px 0; }
@media (max-width: 430px) { .prefix-search { grid-template-columns: 1fr auto; } .prefix-search input { grid-column: 1 / -1; } }
</style>
