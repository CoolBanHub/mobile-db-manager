<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  clearDirectHistory,
  deleteDirectHistoryEntry,
  searchDirectHistory,
} from "@/lib/direct/history";
import {
  deleteDirectSavedSql,
  deleteDirectSavedSqlFolder,
  loadDirectSavedSql,
  loadDirectSavedSqlLibrary,
  saveDirectSavedSql,
  saveDirectSavedSqlFolder,
} from "@/lib/direct/savedSql";
import type {
  MobileConnectionSummary,
  MobileHistoryCursor,
  MobileHistoryEntry,
  MobileHistoryPage,
  MobileQueryDraft,
  SavedSqlFolder,
  SavedSqlFile,
  SavedSqlLibrary,
} from "@/lib/mobileTypes";

const PAGE_SIZE = 20;

const props = defineProps<{ connections: MobileConnectionSummary[] }>();
const emit = defineEmits<{ openQuery: [draft: Omit<MobileQueryDraft, "nonce">] }>();
const mode = ref<"history" | "saved">("history");
const history = ref<MobileHistoryEntry[]>([]);
const library = ref<SavedSqlLibrary>({ folders: [], files: [] });
const loading = ref(true);
const loadingMore = ref(false);
const error = ref("");
const search = ref("");
const connectionId = ref("");
const success = ref<"" | "true" | "false">("");
const startedDate = ref("");
const endedDate = ref("");
const nextCursor = ref<MobileHistoryCursor | null>(null);
const total = ref(0);
const busyId = ref("");
const currentFolderId = ref<string | null>(null);
const manageAction = ref<"new-folder" | "rename-file" | "rename-folder" | "move-file" | "move-folder" | null>(null);
const manageFile = ref<SavedSqlFile | null>(null);
const manageFolder = ref<SavedSqlFolder | null>(null);
const manageName = ref("");
const manageConnectionId = ref("");
const manageTargetFolderId = ref("");
const detail = ref<MobileHistoryEntry | null>(null);
const lastSyncedAt = ref<Date | null>(null);
let searchTimer: number | undefined;
let requestVersion = 0;

const connectionNames = computed(() => new Map(props.connections.map((item) => [item.id, item.name])));
const folderById = computed(() => new Map(library.value.folders.map((item) => [item.id, item])));
const currentFolder = computed(() =>
  currentFolderId.value ? folderById.value.get(currentFolderId.value) ?? null : null,
);
const folderTrail = computed(() => {
  const result: SavedSqlFolder[] = [];
  // seen 防止损坏的本地目录关系形成环，导致面包屑计算无限循环。
  const seen = new Set<string>();
  let folder = currentFolder.value;
  while (folder && !seen.has(folder.id)) {
    result.unshift(folder);
    seen.add(folder.id);
    folder = folder.parentFolderId ? folderById.value.get(folder.parentFolderId) ?? null : null;
  }
  return result;
});
function folderPath(folderId: string | null) {
  if (!folderId) return "根目录";
  const names: string[] = [];
  const seen = new Set<string>();
  let folder = folderById.value.get(folderId);
  while (folder && !seen.has(folder.id)) {
    names.unshift(folder.name);
    seen.add(folder.id);
    folder = folder.parentFolderId ? folderById.value.get(folder.parentFolderId) : undefined;
  }
  return names.join(" / ") || "根目录";
}
const filteredFiles = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase();
  if (!needle) return library.value.files.filter((item) => item.folderId === currentFolderId.value);
  return library.value.files.filter((item) =>
    [
      item.name,
      connectionNames.value.get(item.connectionId) ?? "",
      item.database,
      item.schema ?? "",
      folderPath(item.folderId),
    ]
      .join("\n")
      .toLocaleLowerCase()
      .includes(needle),
  );
});
const childFolders = computed(() => {
  if (search.value.trim()) return [];
  return library.value.folders.filter((item) => item.parentFolderId === currentFolderId.value);
});
const unavailableMoveFolderIds = computed(() => {
  const unavailable = new Set<string>();
  if (manageAction.value !== "move-folder" || !manageFolder.value) return unavailable;
  const queue = [manageFolder.value.id];
  // 当前目录及全部后代都不能成为移动目标，否则会制造目录环。
  while (queue.length) {
    const id = queue.shift()!;
    if (unavailable.has(id)) continue;
    unavailable.add(id);
    queue.push(...library.value.folders.filter((folder) => folder.parentFolderId === id).map((folder) => folder.id));
  }
  return unavailable;
});
const moveTargets = computed(() => {
  const connection = manageConnectionId.value;
  return library.value.folders.filter(
    (folder) => folder.connectionId === connection && !unavailableMoveFolderIds.value.has(folder.id),
  );
});
const activeFilterCount = computed(
  () => [connectionId.value, success.value, startedDate.value, endedDate.value].filter(Boolean).length,
);
const syncLabel = computed(() =>
  lastSyncedAt.value
    ? `已刷新 ${lastSyncedAt.value.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`
    : "刷新",
);
const manageTitle = computed(() => {
  return {
    "new-folder": "新建目录",
    "rename-file": "重命名 SQL",
    "rename-folder": "重命名目录",
    "move-file": "移动 SQL",
    "move-folder": "移动目录",
  }[manageAction.value ?? "new-folder"];
});

function fail(reason: unknown) {
  error.value = reason instanceof Error ? reason.message : "请求失败";
}

function hasConnection(id: string) {
  return connectionNames.value.has(id);
}

function dateBoundary(value: string, endOfDay: boolean) {
  if (!value) return undefined;
  const suffix = endOfDay ? "T23:59:59.999" : "T00:00:00.000";
  return new Date(`${value}${suffix}`).toISOString();
}

function historyRequest(cursor: MobileHistoryCursor | null) {
  return {
    searchText: search.value.trim(),
    connectionId: connectionId.value || undefined,
    success: success.value === "" ? undefined : success.value === "true",
    startedAt: dateBoundary(startedDate.value, false),
    endedAt: dateBoundary(endedDate.value, true),
    cursor: cursor ?? undefined,
    limit: PAGE_SIZE,
  };
}

async function fetchHistoryPage(cursor: MobileHistoryCursor | null) {
  return searchDirectHistory(historyRequest(cursor));
}

async function refreshHistory(options: { silent?: boolean; preserveDepth?: boolean } = {}) {
  if (startedDate.value && endedDate.value && startedDate.value > endedDate.value) {
    error.value = "开始日期不能晚于结束日期";
    return;
  }
  const version = ++requestVersion;
  // 筛选条件快速变化时只接受最后一次请求；静默刷新可保持当前已加载深度。
  if (!options.silent) loading.value = true;
  error.value = "";
  try {
    const desiredCount = options.preserveDepth ? Math.max(history.value.length, PAGE_SIZE) : PAGE_SIZE;
    let page = await fetchHistoryPage(null);
    const entries = [...page.entries];
    while (page.nextCursor && entries.length < desiredCount) {
      page = await fetchHistoryPage(page.nextCursor);
      entries.push(...page.entries);
    }
    if (version !== requestVersion) return;
    history.value = entries;
    nextCursor.value = page.nextCursor;
    total.value = page.total;
    lastSyncedAt.value = new Date();
    if (detail.value) {
      detail.value = entries.find((item) => item.id === detail.value?.id) ?? null;
    }
  } catch (reason) {
    if (version === requestVersion) fail(reason);
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}

async function loadMore() {
  if (!nextCursor.value || loadingMore.value) return;
  const version = requestVersion;
  loadingMore.value = true;
  error.value = "";
  try {
    const page = await fetchHistoryPage(nextCursor.value);
    if (version !== requestVersion) return;
    const known = new Set(history.value.map((item) => item.id));
    history.value.push(...page.entries.filter((item) => !known.has(item.id)));
    nextCursor.value = page.nextCursor;
    total.value = page.total;
    lastSyncedAt.value = new Date();
  } catch (reason) {
    fail(reason);
  } finally {
    loadingMore.value = false;
  }
}

async function loadSavedLibrary() {
  library.value = loadDirectSavedSqlLibrary();
  if (currentFolderId.value && !library.value.folders.some((folder) => folder.id === currentFolderId.value)) {
    currentFolderId.value = null;
  }
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    await Promise.all([refreshHistory({ silent: true }), loadSavedLibrary()]);
  } catch (reason) {
    fail(reason);
  } finally {
    loading.value = false;
  }
}

function scheduleSearch() {
  window.clearTimeout(searchTimer);
  if (mode.value === "saved") return;
  searchTimer = window.setTimeout(() => void refreshHistory(), 300);
}

function switchMode(nextMode: "history" | "saved") {
  mode.value = nextMode;
  if (nextMode === "history") void refreshHistory();
  else {
    loading.value = true;
    error.value = "";
    void loadSavedLibrary()
      .catch(fail)
      .finally(() => {
        loading.value = false;
      });
  }
}

function applyFilters() {
  window.clearTimeout(searchTimer);
  void refreshHistory();
}

function resetFilters() {
  connectionId.value = "";
  success.value = "";
  startedDate.value = "";
  endedDate.value = "";
  void refreshHistory();
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
    const saved = saveDirectSavedSql({
        connectionId: item.connectionId,
        folderId: null,
        database: item.database,
        schema: item.schema,
        name: `查询 ${new Date(item.executedAt).toLocaleString()}`,
        sql: item.sql,
    });
    library.value.files = [...library.value.files.filter((file) => file.id !== saved.id), saved];
    mode.value = "saved";
    detail.value = null;
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

async function removeHistory(item: MobileHistoryEntry) {
  if (!window.confirm("删除这条本机查询历史？")) return;
  busyId.value = item.id;
  error.value = "";
  try {
    deleteDirectHistoryEntry(item.id);
    history.value = history.value.filter((entry) => entry.id !== item.id);
    total.value = Math.max(0, total.value - 1);
    if (detail.value?.id === item.id) detail.value = null;
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

async function clearHistory() {
  if (!window.confirm("清空全部本机查询历史？此操作无法撤销。")) return;
  busyId.value = "clear-history";
  error.value = "";
  try {
    clearDirectHistory();
    history.value = [];
    nextCursor.value = null;
    total.value = 0;
    detail.value = null;
    lastSyncedAt.value = new Date();
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
    const loaded = loadDirectSavedSql(item.id);
    if (!loaded) throw new Error("保存的 SQL 已不存在");
    emit("openQuery", {
      connectionId: loaded.connectionId,
      database: loaded.database,
      schema: loaded.schema,
      sql: loaded.sql,
      savedSqlId: loaded.id,
      savedSqlName: loaded.name,
      savedSqlFolderId: loaded.folderId,
    });
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

function openFolder(folder: SavedSqlFolder) {
  currentFolderId.value = folder.id;
  search.value = "";
}

function openFolderAt(id: string | null) {
  currentFolderId.value = id;
  search.value = "";
}

function beginNewFolder() {
  manageAction.value = "new-folder";
  manageFile.value = null;
  manageFolder.value = null;
  manageName.value = "";
  manageConnectionId.value = currentFolder.value?.connectionId ?? props.connections[0]?.id ?? "";
  manageTargetFolderId.value =
    currentFolder.value?.connectionId === manageConnectionId.value ? currentFolder.value.id : "";
}

function beginRenameFile(item: SavedSqlFile) {
  manageAction.value = "rename-file";
  manageFile.value = item;
  manageFolder.value = null;
  manageName.value = item.name.replace(/\.sql$/i, "");
  manageConnectionId.value = item.connectionId;
  manageTargetFolderId.value = item.folderId ?? "";
}

function beginMoveFile(item: SavedSqlFile) {
  manageAction.value = "move-file";
  manageFile.value = item;
  manageFolder.value = null;
  manageName.value = item.name;
  manageConnectionId.value = item.connectionId;
  manageTargetFolderId.value = item.folderId ?? "";
}

function beginRenameFolder(item: SavedSqlFolder) {
  manageAction.value = "rename-folder";
  manageFile.value = null;
  manageFolder.value = item;
  manageName.value = item.name;
  manageConnectionId.value = item.connectionId;
  manageTargetFolderId.value = item.parentFolderId ?? "";
}

function beginMoveFolder(item: SavedSqlFolder) {
  manageAction.value = "move-folder";
  manageFile.value = null;
  manageFolder.value = item;
  manageName.value = item.name;
  manageConnectionId.value = item.connectionId;
  manageTargetFolderId.value = item.parentFolderId ?? "";
}

function closeManage() {
  manageAction.value = null;
  manageFile.value = null;
  manageFolder.value = null;
}

async function saveFileMetadata(item: SavedSqlFile, changes: { name?: string; folderId?: string | null }) {
  return saveDirectSavedSql({
      id: item.id,
      connectionId: item.connectionId,
      folderId: changes.folderId === undefined ? item.folderId : changes.folderId,
      database: item.database,
      schema: item.schema,
      name: changes.name ?? item.name,
  });
}

async function submitManage() {
  const action = manageAction.value;
  if (!action || !manageConnectionId.value) return;
  if ((action === "new-folder" || action.startsWith("rename-")) && !manageName.value.trim()) return;
  busyId.value = manageFile.value?.id ?? manageFolder.value?.id ?? "new-folder";
  error.value = "";
  try {
    if (action === "rename-file" && manageFile.value) {
      const saved = await saveFileMetadata(manageFile.value, { name: manageName.value.trim() });
      library.value.files = library.value.files.map((file) => (file.id === saved.id ? saved : file));
    } else if (action === "move-file" && manageFile.value) {
      const saved = await saveFileMetadata(manageFile.value, { folderId: manageTargetFolderId.value || null });
      library.value.files = library.value.files.map((file) => (file.id === saved.id ? saved : file));
    } else {
      const existing = manageFolder.value;
      const folder = saveDirectSavedSqlFolder({
          id: existing?.id,
          connectionId: manageConnectionId.value,
          parentFolderId:
            action === "new-folder"
              ? manageTargetFolderId.value || null
              : action === "move-folder"
                ? manageTargetFolderId.value || null
                : existing?.parentFolderId ?? null,
          name: action === "rename-folder" || action === "new-folder" ? manageName.value.trim() : existing?.name,
      });
      library.value.folders = [...library.value.folders.filter((item) => item.id !== folder.id), folder];
    }
    closeManage();
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

async function removeFolder(item: SavedSqlFolder) {
  if (!window.confirm(`删除目录“${item.name}”及其中所有子目录和 SQL？此操作无法撤销。`)) return;
  busyId.value = item.id;
  error.value = "";
  try {
    deleteDirectSavedSqlFolder(item.id);
    library.value = loadDirectSavedSqlLibrary();
    if (currentFolderId.value && !library.value.folders.some((folder) => folder.id === currentFolderId.value)) {
      currentFolderId.value = null;
    }
  } catch (reason) {
    fail(reason);
  } finally {
    busyId.value = "";
  }
}

async function removeSaved(item: SavedSqlFile) {
  const target = "本机 SQL 库";
  if (!window.confirm(`从${target}删除“${item.name}”？`)) return;
  busyId.value = item.id;
  error.value = "";
  try {
    deleteDirectSavedSql(item.id);
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

function handleBack() {
  if (manageAction.value) {
    closeManage();
    return true;
  }
  if (detail.value) {
    detail.value = null;
    return true;
  }
  if (currentFolderId.value) {
    currentFolderId.value = currentFolder.value?.parentFolderId ?? null;
    return true;
  }
  return false;
}

defineExpose({ handleBack });

onMounted(() => {
  void load();
});

onBeforeUnmount(() => {
  ++requestVersion;
  window.clearTimeout(searchTimer);
});
</script>

<template>
  <div class="history-library">
    <div class="mode-switch">
      <button :class="{ active: mode === 'history' }" type="button" @click="switchMode('history')">查询历史 · {{ total }}</button>
      <button :class="{ active: mode === 'saved' }" type="button" @click="switchMode('saved')">已保存 SQL · {{ library.files.length }}</button>
    </div>

    <div class="search-row">
      <input
        v-model="search"
        class="search"
        type="search"
        :placeholder="mode === 'history' ? '搜索连接、数据库或 SQL' : '搜索已保存 SQL'"
        @input="scheduleSearch"
      />
      <button v-if="mode === 'history'" class="sync-button" type="button" aria-label="立即刷新" @click="refreshHistory({ preserveDepth: true })">
        <span></span>{{ syncLabel }}
      </button>
    </div>

    <section v-if="mode === 'history'" class="filters" aria-label="查询历史筛选">
      <select v-model="connectionId" aria-label="按连接筛选" @change="applyFilters">
        <option value="">全部连接</option>
        <option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option>
      </select>
      <select v-model="success" aria-label="按状态筛选" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="true">仅成功</option>
        <option value="false">仅失败</option>
      </select>
      <label><span>从</span><input v-model="startedDate" type="date" @change="applyFilters" /></label>
      <label><span>至</span><input v-model="endedDate" type="date" @change="applyFilters" /></label>
      <button v-if="activeFilterCount" class="reset-filter" type="button" @click="resetFilters">重置 {{ activeFilterCount }}</button>
    </section>

    <div v-if="error" class="module-error">{{ error }} <button type="button" @click="load">重试</button></div>
    <div v-if="loading" class="empty">正在读取本机 SQL 库…</div>
    <div v-else-if="mode === 'history'" class="entry-list">
      <div class="list-toolbar">
        <span>已加载 {{ history.length }} / {{ total }}</span>
        <button :disabled="busyId === 'clear-history'" type="button" @click="clearHistory">清空历史</button>
      </div>
      <article v-for="item in history" :key="item.id" class="entry" @click="detail = item">
        <header><b :class="{ failed: !item.success }">{{ item.success ? "SUCCESS" : "FAILED" }}</b><time>{{ formatTime(item.executedAt) }}</time></header>
        <p>{{ item.connectionName }} / {{ item.database }}<span v-if="item.schema"> / {{ item.schema }}</span></p>
        <pre>{{ item.sql }}</pre>
        <small v-if="item.error">{{ item.error }}</small>
        <footer>
          <span>{{ item.executionTimeMs }} ms</span>
          <button type="button" @click.stop="detail = item">详情</button>
          <button :disabled="!hasConnection(item.connectionId)" type="button" @click.stop="openHistory(item)">打开</button>
          <button :disabled="busyId === item.id" class="danger" type="button" @click.stop="removeHistory(item)">删除</button>
        </footer>
      </article>
      <button v-if="nextCursor" class="load-more" :disabled="loadingMore" type="button" @click="loadMore">
        {{ loadingMore ? "正在加载…" : `继续加载（剩余 ${Math.max(0, total - history.length)}）` }}
      </button>
      <div v-if="history.length === 0" class="empty">没有匹配的查询历史。</div>
    </div>
    <div v-else class="entry-list saved-library">
      <div class="saved-toolbar">
        <nav aria-label="当前目录">
          <button type="button" :class="{ active: !currentFolderId }" @click="openFolderAt(null)">ROOT</button>
          <template v-for="folder in folderTrail" :key="folder.id">
            <span>/</span><button type="button" :class="{ active: folder.id === currentFolderId }" @click="openFolderAt(folder.id)">{{ folder.name }}</button>
          </template>
        </nav>
        <button type="button" @click="beginNewFolder">＋ 目录</button>
      </div>
      <article v-for="folder in childFolders" :key="folder.id" class="folder-entry">
        <button class="folder-main" type="button" @click="openFolder(folder)">
          <i aria-hidden="true">⌑</i>
          <span>
            <small>{{ connectionNames.get(folder.connectionId) ?? folder.connectionId }}</small>
            <strong>{{ folder.name }}</strong>
          </span>
          <b>→</b>
        </button>
        <footer>
          <span>{{ library.files.filter((file) => file.folderId === folder.id).length }} SQL</span>
          <button type="button" @click="beginRenameFolder(folder)">重命名</button>
          <button type="button" @click="beginMoveFolder(folder)">移动</button>
          <button :disabled="busyId === folder.id" class="danger" type="button" @click="removeFolder(folder)">删除</button>
        </footer>
      </article>
      <article v-for="item in filteredFiles" :key="item.id" class="entry saved">
        <header><b>SAVED SQL</b><time>{{ formatTime(item.updatedAt) }}</time></header>
        <h4>{{ item.name }}</h4>
        <p>{{ connectionNames.get(item.connectionId) ?? item.connectionId }} / {{ item.database }}<span v-if="item.schema"> / {{ item.schema }}</span></p>
        <small v-if="search.trim()" class="file-path">{{ folderPath(item.folderId) }}</small>
        <footer>
          <span></span>
          <button :disabled="!hasConnection(item.connectionId) || busyId === item.id" type="button" @click="openSaved(item)">打开</button>
          <button :disabled="busyId === item.id" type="button" @click="beginRenameFile(item)">重命名</button>
          <button :disabled="busyId === item.id" type="button" @click="beginMoveFile(item)">移动</button>
          <button :disabled="busyId === item.id" class="danger" type="button" @click="removeSaved(item)">删除</button>
        </footer>
      </article>
      <div v-if="filteredFiles.length === 0 && childFolders.length === 0" class="empty">
        {{ search.trim() ? "没有匹配的已保存 SQL。" : "这个目录还是空的。" }}
      </div>
    </div>

    <div v-if="detail" class="detail-backdrop" role="presentation" @click.self="detail = null">
      <article class="detail-sheet" role="dialog" aria-modal="true" aria-labelledby="history-detail-title">
        <header class="detail-header">
          <div><p>QUERY RECORD</p><h3 id="history-detail-title">历史详情</h3></div>
          <button type="button" aria-label="关闭历史详情" @click="detail = null">×</button>
        </header>
        <div class="detail-status" :class="{ failed: !detail.success }">
          <b>{{ detail.success ? "执行成功" : "执行失败" }}</b><time>{{ formatTime(detail.executedAt) }}</time>
        </div>
        <dl>
          <div><dt>连接</dt><dd>{{ detail.connectionName }}</dd></div>
          <div><dt>数据库</dt><dd>{{ detail.database }}</dd></div>
          <div><dt>Schema</dt><dd>{{ detail.schema || "—" }}</dd></div>
          <div><dt>耗时</dt><dd>{{ detail.executionTimeMs }} ms</dd></div>
        </dl>
        <section><span>SQL</span><pre>{{ detail.sql }}</pre></section>
        <section v-if="detail.error" class="detail-error"><span>ERROR</span><p>{{ detail.error }}</p></section>
        <footer>
          <button class="danger" :disabled="busyId === detail.id" type="button" @click="removeHistory(detail)">删除</button>
          <button :disabled="!hasConnection(detail.connectionId) || busyId === detail.id" type="button" @click="saveHistory(detail)">收藏</button>
          <button class="primary" :disabled="!hasConnection(detail.connectionId)" type="button" @click="openHistory(detail)">打开查询</button>
        </footer>
      </article>
    </div>

    <div v-if="manageAction" class="detail-backdrop" role="presentation" @click.self="closeManage">
      <form class="detail-sheet manage-sheet" role="dialog" aria-modal="true" aria-labelledby="saved-manage-title" @submit.prevent="submitManage">
        <header class="detail-header">
          <div><p>LIBRARY CONTROL</p><h3 id="saved-manage-title">{{ manageTitle }}</h3></div>
          <button type="button" aria-label="关闭" @click="closeManage">×</button>
        </header>
        <label v-if="manageAction === 'new-folder'">
          <span>连接</span>
          <select v-model="manageConnectionId" @change="manageTargetFolderId = ''">
            <option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>
        <label v-if="manageAction === 'new-folder' || manageAction.startsWith('rename-')">
          <span>名称</span>
          <input v-model="manageName" maxlength="120" autofocus placeholder="输入名称" />
        </label>
        <label v-if="manageAction === 'new-folder' || manageAction.startsWith('move-')">
          <span>目标目录</span>
          <select v-model="manageTargetFolderId">
            <option value="">根目录</option>
            <option v-for="folder in moveTargets" :key="folder.id" :value="folder.id">{{ folderPath(folder.id) }}</option>
          </select>
        </label>
        <p v-if="manageAction === 'move-folder'" class="manage-note">不能移动到自身或其子目录。</p>
        <footer>
          <button type="button" @click="closeManage">取消</button>
          <button
            class="primary"
            :disabled="busyId !== '' || !manageConnectionId || ((manageAction === 'new-folder' || manageAction.startsWith('rename-')) && !manageName.trim())"
            type="submit"
          >{{ busyId ? "保存中…" : "确认" }}</button>
        </footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.history-library { display: grid; gap: 10px; margin-top: 16px; }
.mode-switch { display: grid; grid-template-columns: 1fr 1fr; border: 1px solid var(--line); }
.mode-switch button { min-height: 42px; border: 0; background: var(--panel); color: var(--muted); font: inherit; font-size: 9px; }
.mode-switch button + button { border-left: 1px solid var(--line); }
.mode-switch button.active { background: var(--acid); color: #10130c; font-weight: 760; }
.search-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; }
.search { min-width: 0; min-height: 42px; border: 1px solid var(--line); background: #0b0d0c; padding: 0 12px; color: var(--ink); font: inherit; font-size: 10px; outline: none; }
.sync-button { display: flex; min-height: 42px; align-items: center; gap: 6px; border: 1px solid var(--line); border-left: 0; background: var(--panel); padding: 0 10px; color: var(--muted); font: inherit; font-size: 8px; }
.sync-button span { width: 6px; height: 6px; border-radius: 50%; background: var(--acid); box-shadow: 0 0 8px rgba(199, 255, 61, .6); }
.filters { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; border: 1px solid var(--line); background: rgba(20, 24, 21, .55); padding: 8px; }
.filters select, .filters input { width: 100%; min-width: 0; min-height: 36px; border: 1px solid var(--line); border-radius: 0; background: #0b0d0c; padding: 0 8px; color: var(--ink); font: inherit; font-size: 9px; color-scheme: dark; }
.filters label { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 5px; color: var(--faint); font-size: 8px; }
.reset-filter { grid-column: 1 / -1; min-height: 30px; border: 1px dashed var(--line); background: transparent; color: var(--acid); font: inherit; font-size: 8px; }
.entry-list { display: grid; gap: 9px; }
.saved-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: stretch; border: 1px solid var(--line); background: var(--panel); }
.saved-toolbar nav { display: flex; min-width: 0; align-items: center; gap: 4px; overflow-x: auto; padding: 0 8px; }
.saved-toolbar nav span { color: var(--faint); font-size: 8px; }
.saved-toolbar button { flex: none; min-height: 40px; border: 0; background: transparent; color: var(--muted); font: inherit; font-size: 8px; }
.saved-toolbar > button { border-left: 1px solid var(--line); padding: 0 11px; color: var(--acid); }
.saved-toolbar nav button.active { color: var(--acid); }
.folder-entry { border: 1px solid var(--line); border-left: 2px solid var(--amber); background: linear-gradient(110deg, rgba(255,187,61,.055), var(--panel) 44%); }
.folder-main { display: grid; width: 100%; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 9px; border: 0; background: transparent; padding: 12px; color: var(--ink); text-align: left; }
.folder-main i { display: grid; width: 30px; height: 26px; place-items: center; border: 1px solid rgba(255,187,61,.5); color: var(--amber); font-style: normal; }
.folder-main span { min-width: 0; }
.folder-main small { display: block; overflow: hidden; color: var(--faint); font-size: 7px; text-overflow: ellipsis; white-space: nowrap; }
.folder-main strong { display: block; overflow: hidden; margin-top: 5px; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.folder-main > b { color: var(--amber); font-weight: 400; }
.folder-entry footer { display: flex; align-items: center; justify-content: flex-end; gap: 6px; border-top: 1px solid var(--line); padding: 7px 9px; }
.folder-entry footer span { margin-right: auto; color: var(--faint); font-size: 7px; }
.folder-entry footer button { min-height: 28px; border: 0; background: transparent; color: var(--acid); font: inherit; font-size: 7px; }
.folder-entry footer button.danger { color: var(--danger); }
.list-toolbar { display: flex; align-items: center; justify-content: space-between; color: var(--faint); font-size: 8px; }
.list-toolbar button { border: 0; background: transparent; color: var(--danger); font: inherit; font-size: 8px; }
.entry { min-width: 0; border: 1px solid var(--line); border-left: 2px solid var(--acid); background: var(--panel); padding: 12px; cursor: pointer; }
.entry header, .entry footer { display: flex; align-items: center; gap: 8px; }
.entry header { justify-content: space-between; color: var(--faint); font-size: 8px; }
.entry header b { color: var(--acid); letter-spacing: .1em; }
.entry header b.failed, .entry small { color: var(--danger); }
.entry p { margin: 8px 0; color: var(--muted); font-size: 9px; }
.entry pre { max-height: 110px; overflow: hidden; margin: 0; color: var(--ink); font: 10px/1.55 "Azeret Mono Variable", monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.entry small { display: block; margin-top: 8px; font-size: 8px; overflow-wrap: anywhere; }
.entry h4 { margin: 10px 0 0; color: var(--ink); font-size: 12px; }
.entry footer { justify-content: flex-end; margin-top: 11px; }
.entry.saved footer { flex-wrap: wrap; }
.entry footer span { margin-right: auto; color: var(--faint); font-size: 8px; }
.entry footer button, .module-error button { min-height: 30px; border: 1px solid var(--line); background: transparent; padding: 0 10px; color: var(--acid); font: inherit; font-size: 8px; }
.entry footer button.danger, button.danger { color: var(--danger); }
.load-more { min-height: 44px; border: 1px solid var(--line); background: linear-gradient(90deg, rgba(199,255,61,.08), transparent); color: var(--acid); font: inherit; font-size: 9px; }
.empty, .module-error { border: 1px solid var(--line); padding: 22px 14px; color: var(--muted); font-size: 9px; text-align: center; }
.module-error { color: var(--danger); }
.detail-backdrop { position: fixed; z-index: 20; inset: 0; display: flex; align-items: flex-end; background: rgba(0, 0, 0, .72); backdrop-filter: blur(6px); }
.detail-sheet { width: 100%; max-height: 88dvh; overflow: auto; border-top: 2px solid var(--acid); background: #111512; padding: 18px 18px calc(18px + var(--safe-bottom)); box-shadow: 0 -22px 80px rgba(0,0,0,.6); }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-header p, .detail-sheet section > span { margin: 0; color: var(--acid); font-size: 8px; letter-spacing: .14em; }
.detail-header h3 { margin: 5px 0 0; font-size: 21px; }
.detail-header button { width: 34px; height: 34px; border: 1px solid var(--line); background: transparent; color: var(--muted); font-size: 20px; }
.detail-status { display: flex; justify-content: space-between; margin-top: 20px; border-left: 2px solid var(--acid); background: rgba(199,255,61,.06); padding: 10px; color: var(--acid); font-size: 9px; }
.detail-status.failed { border-color: var(--danger); background: rgba(255,101,95,.06); color: var(--danger); }
.detail-status time { color: var(--muted); }
.detail-sheet dl { display: grid; grid-template-columns: 1fr 1fr; margin: 16px 0; border-top: 1px solid var(--line); border-left: 1px solid var(--line); }
.detail-sheet dl div { min-width: 0; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 9px; }
.detail-sheet dt { color: var(--faint); font-size: 8px; }
.detail-sheet dd { overflow: hidden; margin: 5px 0 0; color: var(--ink); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.detail-sheet section { margin-top: 16px; }
.detail-sheet section pre { max-height: 34dvh; overflow: auto; border: 1px solid var(--line); background: #090b0a; padding: 13px; color: var(--ink); font: 10px/1.65 "Azeret Mono Variable", monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.detail-error p { border-left: 2px solid var(--danger); background: rgba(255,101,95,.06); padding: 11px; color: var(--danger); font-size: 9px; line-height: 1.6; overflow-wrap: anywhere; }
.detail-sheet > footer { position: sticky; bottom: 0; display: grid; grid-template-columns: auto auto 1fr; gap: 7px; margin-top: 18px; background: #111512; padding-top: 9px; }
.detail-sheet > footer button { min-height: 42px; border: 1px solid var(--line); background: #111512; padding: 0 11px; color: var(--acid); font: inherit; font-size: 9px; }
.detail-sheet > footer button.primary { background: var(--acid); color: #10130c; font-weight: 760; }
.manage-sheet { display: block; }
.manage-sheet > label { display: grid; gap: 7px; margin-top: 17px; }
.manage-sheet > label > span { color: var(--acid); font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }
.manage-sheet input, .manage-sheet select { width: 100%; min-height: 44px; border: 1px solid var(--line); border-radius: 0; background: #090b0a; padding: 0 11px; color: var(--ink); font: inherit; font-size: 10px; outline: none; }
.manage-note { border-left: 2px solid var(--amber); background: rgba(255,187,61,.055); padding: 10px; color: var(--muted); font-size: 8px; }
.manage-sheet > footer { grid-template-columns: 1fr 1fr; }
.file-path { display: block; margin-top: 8px; color: var(--amber); font-size: 7px; }
button:disabled { opacity: .45; }
</style>
