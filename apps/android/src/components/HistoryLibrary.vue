<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  ApiError,
  apiDeleteJson,
  apiGetJson,
  apiPostJson,
  type MobileConnectionSummary,
  type MobileHistoryEntry,
  type MobileQueryDraft,
  type SavedSqlFile,
  type SavedSqlLibrary,
} from "../lib/mobileApi";

const props = defineProps<{ baseUrl: string; token: string | null; connections: MobileConnectionSummary[] }>();
const emit = defineEmits<{ authExpired: []; openQuery: [draft: Omit<MobileQueryDraft, "nonce">] }>();
const mode = ref<"history" | "saved">("history");
const history = ref<MobileHistoryEntry[]>([]);
const library = ref<SavedSqlLibrary>({ folders: [], files: [] });
const loading = ref(true);
const error = ref("");
const search = ref("");
const busyId = ref("");

const connectionNames = computed(() => new Map(props.connections.map((item) => [item.id, item.name])));
const filteredHistory = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase();
  if (!needle) return history.value;
  return history.value.filter((item) =>
    [item.connectionName, item.database, item.schema ?? "", item.sql, item.error ?? ""]
      .join("\n")
      .toLocaleLowerCase()
      .includes(needle),
  );
});
const filteredFiles = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase();
  if (!needle) return library.value.files;
  return library.value.files.filter((item) =>
    [item.name, connectionNames.value.get(item.connectionId) ?? "", item.database, item.schema ?? ""]
      .join("\n")
      .toLocaleLowerCase()
      .includes(needle),
  );
});

function fail(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit("authExpired");
  else error.value = reason instanceof Error ? reason.message : "请求失败";
}

function hasConnection(connectionId: string) {
  return connectionNames.value.has(connectionId);
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [entries, saved] = await Promise.all([
      apiGetJson<MobileHistoryEntry[]>(props.baseUrl, "/api/mobile/history", props.token, { limit: 50 }),
      apiGetJson<SavedSqlLibrary>(props.baseUrl, "/api/mobile/saved-sql", props.token, {}),
    ]);
    history.value = entries;
    library.value = saved;
  } catch (reason) {
    fail(reason);
  } finally {
    loading.value = false;
  }
}

function openHistory(item: MobileHistoryEntry) {
  emit("openQuery", {
    connectionId: item.connectionId,
    database: item.database,
    schema: item.schema,
    sql: item.sql,
  });
}

async function saveHistory(item: MobileHistoryEntry) {
  busyId.value = item.id;
  error.value = "";
  try {
    const saved = await apiPostJson<SavedSqlFile>(
      props.baseUrl,
      "/api/mobile/saved-sql",
      props.token,
      {
        connectionId: item.connectionId,
        database: item.database,
        schema: item.schema,
        name: `查询 ${new Date(item.executedAt).toLocaleString()}`,
        sql: item.sql,
      },
      { timeoutMs: 8_000 },
    );
    library.value.files = [...library.value.files.filter((file) => file.id !== saved.id), saved];
    mode.value = "saved";
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

async function openSaved(item: SavedSqlFile) {
  busyId.value = item.id;
  error.value = "";
  try {
    const loaded = await apiGetJson<SavedSqlFile | null>(
      props.baseUrl,
      `/api/mobile/saved-sql/${encodeURIComponent(item.id)}`,
      props.token,
      {},
    );
    if (!loaded) throw new Error("保存的 SQL 已不存在");
    emit("openQuery", {
      connectionId: loaded.connectionId,
      database: loaded.database,
      schema: loaded.schema,
      sql: loaded.sql,
    });
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

async function removeSaved(item: SavedSqlFile) {
  if (!window.confirm(`删除“${item.name}”？此操作会同步到 DBX Server。`)) return;
  busyId.value = item.id;
  error.value = "";
  try {
    await apiDeleteJson<null>(props.baseUrl, `/api/mobile/saved-sql/${encodeURIComponent(item.id)}`, props.token);
    library.value.files = library.value.files.filter((file) => file.id !== item.id);
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

onMounted(load);
</script>

<template>
  <div class="history-library">
    <div class="mode-switch">
      <button :class="{ active: mode === 'history' }" type="button" @click="mode = 'history'">查询历史</button>
      <button :class="{ active: mode === 'saved' }" type="button" @click="mode = 'saved'">已保存 SQL · {{ library.files.length }}</button>
    </div>
    <input v-model="search" class="search" type="search" placeholder="搜索连接、数据库或 SQL" />
    <div v-if="error" class="module-error">{{ error }} <button type="button" @click="load">重试</button></div>
    <div v-if="loading" class="empty">正在同步 DBX Server…</div>
    <div v-else-if="mode === 'history'" class="entry-list">
      <article v-for="item in filteredHistory" :key="item.id" class="entry">
        <header><b :class="{ failed: !item.success }">{{ item.success ? "SUCCESS" : "FAILED" }}</b><time>{{ formatTime(item.executedAt) }}</time></header>
        <p>{{ item.connectionName }} / {{ item.database }}<span v-if="item.schema"> / {{ item.schema }}</span></p>
        <pre>{{ item.sql }}</pre>
        <small v-if="item.error">{{ item.error }}</small>
        <footer><span>{{ item.executionTimeMs }} ms</span><button :disabled="!hasConnection(item.connectionId)" type="button" @click="openHistory(item)">打开</button><button :disabled="!hasConnection(item.connectionId) || busyId === item.id" type="button" @click="saveHistory(item)">收藏</button></footer>
      </article>
      <div v-if="filteredHistory.length === 0" class="empty">没有匹配的查询历史。</div>
    </div>
    <div v-else class="entry-list">
      <article v-for="item in filteredFiles" :key="item.id" class="entry saved">
        <header><b>SAVED SQL</b><time>{{ formatTime(item.updatedAt) }}</time></header>
        <h4>{{ item.name }}</h4>
        <p>{{ connectionNames.get(item.connectionId) ?? item.connectionId }} / {{ item.database }}<span v-if="item.schema"> / {{ item.schema }}</span></p>
        <footer><span></span><button :disabled="!hasConnection(item.connectionId) || busyId === item.id" type="button" @click="openSaved(item)">打开</button><button :disabled="busyId === item.id" class="danger" type="button" @click="removeSaved(item)">删除</button></footer>
      </article>
      <div v-if="filteredFiles.length === 0" class="empty">还没有保存的 SQL。</div>
    </div>
  </div>
</template>

<style scoped>
.history-library { display: grid; gap: 10px; margin-top: 16px; }
.mode-switch { display: grid; grid-template-columns: 1fr 1fr; border: 1px solid var(--line); }
.mode-switch button { min-height: 42px; border: 0; background: var(--panel); color: var(--muted); font: inherit; font-size: 9px; }
.mode-switch button + button { border-left: 1px solid var(--line); }
.mode-switch button.active { background: var(--acid); color: #10130c; font-weight: 760; }
.search { min-height: 42px; border: 1px solid var(--line); background: #0b0d0c; padding: 0 12px; color: var(--ink); font: inherit; font-size: 10px; outline: none; }
.entry-list { display: grid; gap: 9px; }
.entry { min-width: 0; border: 1px solid var(--line); border-left: 2px solid var(--acid); background: var(--panel); padding: 12px; }
.entry header, .entry footer { display: flex; align-items: center; gap: 8px; }
.entry header { justify-content: space-between; color: var(--faint); font-size: 8px; }
.entry header b { color: var(--acid); letter-spacing: .1em; }
.entry header b.failed, .entry small { color: var(--danger); }
.entry p { margin: 8px 0; color: var(--muted); font-size: 9px; }
.entry pre { max-height: 110px; overflow: hidden; margin: 0; color: var(--ink); font: 10px/1.55 "Azeret Mono Variable", monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.entry small { display: block; margin-top: 8px; font-size: 8px; overflow-wrap: anywhere; }
.entry h4 { margin: 10px 0 0; color: var(--ink); font-size: 12px; }
.entry footer { justify-content: flex-end; margin-top: 11px; }
.entry footer span { margin-right: auto; color: var(--faint); font-size: 8px; }
.entry footer button, .module-error button { min-height: 30px; border: 1px solid var(--line); background: transparent; padding: 0 10px; color: var(--acid); font: inherit; font-size: 8px; }
.entry footer button.danger { color: var(--danger); }
.empty, .module-error { border: 1px solid var(--line); padding: 22px 14px; color: var(--muted); font-size: 9px; text-align: center; }
.module-error { color: var(--danger); }
</style>
