<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import PageState from "@/components/PageState.vue";
import ConnectionCatalogSheets, { type ConnectionAdvancedFilters } from "./ConnectionCatalogSheets.vue";
import postgresIcon from "@/assets/database-icons/postgres.svg";
import redisIcon from "@/assets/database-icons/redis.svg";
import mongodbIcon from "@/assets/database-icons/mongodb.svg";
import sqlserverIcon from "@/assets/database-icons/sqlserver.svg";
import etcdIcon from "@/assets/database-icons/etcd.svg";
import { getConnectionPreference, getConnectionSortMode, removeConnectionPreference, saveConnectionPreference, saveConnectionSortMode, type ConnectionEnvironment, type ConnectionSortMode } from "@/lib/connectionPreferences";
import { databaseCapability, mobileDatabaseCapabilities } from "@/lib/databaseCapabilities";
import { deleteDirectConnection, getDirectConnection, saveDirectConnection, testDirectConnection } from "@/lib/direct/connections";
import { listSshProfiles } from "@/lib/direct/sshProfiles";
import type { MobileConnectionDraft, MobileConnectionSummary, MobileSshProfileSummary } from "@/lib/mobileTypes";

const props = defineProps<{ connections: MobileConnectionSummary[] }>();

const emit = defineEmits<{
  changed: [];
  browse: [connection: MobileConnectionSummary];
}>();

const search = ref("");
const environment = ref<"all" | ConnectionEnvironment>("all");
const favoritesOnly = ref(false);
const sortOpen = ref(false);
const advancedSearchOpen = ref(false);
const sortMode = ref<ConnectionSortMode>(getConnectionSortMode());
const pendingSortMode = ref<ConnectionSortMode>(sortMode.value);
const emptyAdvancedFilters = (): ConnectionAdvancedFilters => ({ type: "all", environment: "all", transport: "all", tag: "", favorite: false, production: false });
const advancedFilters = ref<ConnectionAdvancedFilters>(emptyAdvancedFilters());
const pendingAdvancedFilters = ref<ConnectionAdvancedFilters>(emptyAdvancedFilters());
const collapsedGroups = ref(new Set<string>());
const editorOpen = ref(false);
const advancedOpen = ref(false);
const editorStep = ref<1 | 2 | 3 | 4>(1);
const saving = ref(false);
const testing = ref(false);
const editorMessage = ref("");
const editorTone = ref<"success" | "danger" | "neutral">("neutral");
const groupDraft = ref("未分组");
const preservedTags = ref<string[]>([]);
const preferenceRevision = ref(0);
const hasStoredConnectionString = ref(false);
const sshProfiles = ref<MobileSshProfileSummary[]>([]);
const sshProfilesError = ref("");
const deleteCandidate = ref<MobileConnectionSummary | null>(null);
const sslCertificateError = computed(() => editorTone.value === "danger" && editorMessage.value.startsWith("SSL 证书验证失败"));

const databaseTypes = mobileDatabaseCapabilities;
const environmentOptions = [
  { value: "development", label: "开发" },
  { value: "staging", label: "预发" },
  { value: "production", label: "生产" },
] as const satisfies ReadonlyArray<{ value: ConnectionEnvironment; label: string }>;

function blankDraft(): MobileConnectionDraft {
  const defaults = databaseCapability("postgres");
  return {
    name: "",
    note: "",
    dbType: "postgres",
    host: defaults.defaultHost,
    port: defaults.port,
    username: defaults.defaultUsername,
    password: "",
    database: defaults.defaultDatabase,
    color: null,
    ssl: false,
    sslMode: "verify-full",
    readOnly: false,
    isProduction: false,
    connectTimeoutSecs: 10,
    queryTimeoutSecs: 60,
    keepaliveIntervalSecs: 30,
    proxyEnabled: false,
    proxyHost: "",
    proxyPort: 8080,
    proxyUsername: "",
    proxyPassword: "",
    sshEnabled: false,
    sshProfileId: "",
    sshHost: "",
    sshPort: 22,
    sshUsername: "",
    sshHostKeyFingerprint: "",
    sshPassword: "",
    sshAuthMethod: "password",
    sshPrivateKey: "",
    sshPrivateKeyPassphrase: "",
    connectionString: "",
  };
}

const draft = reactive<MobileConnectionDraft>(blankDraft());
const currentCapability = computed(() => databaseCapability(draft.dbType));
const selectedSshProfile = computed(() => sshProfiles.value.find((profile) => profile.id === draft.sshProfileId) ?? null);
const preferenceEnvironment = ref<ConnectionEnvironment>("development");
const advancedFilterCount = computed(() => Object.entries(advancedFilters.value).filter(([key, value]) => key === "favorite" || key === "production" ? value : value !== "all" && value !== "").length);
const activeFilterCount = computed(() => (environment.value === "all" ? 0 : 1) + (favoritesOnly.value ? 1 : 0) + advancedFilterCount.value);
const tagOptions = computed(() => {
  void preferenceRevision.value;
  const values = new Set<string>();
  for (const connection of props.connections) {
    const item = preference(connection);
    if (item.group && item.group !== "未分组") values.add(item.group);
    item.tags.forEach((tag) => values.add(tag));
  }
  return [...values].sort((left, right) => left.localeCompare(right, "zh-CN", { numeric: true }));
});
const editorStepCopy = computed(() => [
  { label: "基本", description: "类型与用途" },
  { label: "认证", description: "服务器与账号" },
  { label: "安全", description: "TLS 与隧道" },
  { label: "确认", description: "测试并保存" },
]);

const filteredConnections = computed(() => {
  // preferenceRevision 是 localStorage 的响应式桥；偏好写入后递增即可触发重新筛选。
  void preferenceRevision.value;
  const needle = search.value.trim().toLocaleLowerCase();
  return props.connections.filter((connection) => {
    const preference = getConnectionPreference(connection.id, connection.isProduction);
    if (favoritesOnly.value && !preference.favorite) return false;
    if (environment.value !== "all" && preference.environment !== environment.value) return false;
    const advanced = advancedFilters.value;
    if (advanced.type !== "all" && connection.dbType !== advanced.type) return false;
    if (advanced.environment !== "all" && preference.environment !== advanced.environment) return false;
    if (advanced.favorite && !preference.favorite) return false;
    if (advanced.production && preference.environment !== "production") return false;
    if (advanced.tag && preference.group !== advanced.tag && !preference.tags.includes(advanced.tag)) return false;
    const isDirect = !connection.ssl && !connection.sshEnabled && !connection.proxyEnabled;
    if (advanced.transport === "direct" && !isDirect) return false;
    if (advanced.transport === "tls" && !connection.ssl) return false;
    if (advanced.transport === "ssh" && !connection.sshEnabled) return false;
    if (advanced.transport === "http" && !connection.proxyEnabled) return false;
    return !needle || [connection.name, connection.host, connection.database, connection.dbType, connection.note, preference.group, ...preference.tags].filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle));
  });
});

function compareConnections(left: MobileConnectionSummary, right: MobileConnectionSummary) {
  const leftPreference = preference(left);
  const rightPreference = preference(right);
  const defaultTypeOrder = ["postgres", "mysql", "redis", "mongodb", "sqlserver", "etcd"];
  const compareDefault = () => {
    const leftIndex = defaultTypeOrder.indexOf(left.dbType);
    const rightIndex = defaultTypeOrder.indexOf(right.dbType);
    return (leftIndex < 0 ? defaultTypeOrder.length : leftIndex) - (rightIndex < 0 ? defaultTypeOrder.length : rightIndex)
      || left.name.localeCompare(right.name, "zh-CN", { numeric: true });
  };
  if (sortMode.value === "name") return left.name.localeCompare(right.name, "zh-CN", { numeric: true });
  if (sortMode.value === "environment") {
    const rank = { production: 0, staging: 1, development: 2 };
    return rank[leftPreference.environment] - rank[rightPreference.environment] || left.name.localeCompare(right.name, "zh-CN", { numeric: true });
  }
  if (sortMode.value === "pinned" && leftPreference.pinned !== rightPreference.pinned) return leftPreference.pinned ? -1 : 1;
  return rightPreference.lastUsedAt - leftPreference.lastUsedAt || compareDefault();
}

const groupedConnections = computed(() => {
  const groups = new Map<string, MobileConnectionSummary[]>();
  for (const connection of [...filteredConnections.value].sort(compareConnections)) {
    const type = connection.dbType.toLocaleLowerCase();
    groups.set(type, [...(groups.get(type) ?? []), connection]);
  }
  return [...groups.entries()]
    .map(([type, items]) => ({ type, label: databaseCapability(type).label, items }))
    .sort((left, right) => compareConnections(left.items[0], right.items[0]));
});

function preference(connection: MobileConnectionSummary) {
  return getConnectionPreference(connection.id, connection.isProduction);
}

function databaseIcon(type: MobileConnectionSummary["dbType"]) {
  const icons: Record<string, string> = {
    postgres: postgresIcon,
    redis: redisIcon,
    mongodb: mongodbIcon,
    sqlserver: sqlserverIcon,
    etcd: etcdIcon,
  };
  return icons[type] ?? "";
}

function databaseUsageTag(type: MobileConnectionSummary["dbType"]) {
  const tags: Record<string, string> = {
    postgres: "核心库",
    mysql: "MySQL",
    redis: "缓存",
    mongodb: "NoSQL",
    sqlserver: "MSSQL",
    etcd: "KV",
  };
  return tags[type] ?? databaseCapability(type).label;
}

function databaseName(connection: MobileConnectionSummary) {
  const name = connection.database?.trim();
  if (name) return name;
  return connection.dbType === "etcd" ? "无数据库" : "未指定数据库";
}

function toggleGroup(type: string) {
  const next = new Set(collapsedGroups.value);
  if (next.has(type)) next.delete(type);
  else next.add(type);
  collapsedGroups.value = next;
}

function environmentLabel(value: ConnectionEnvironment) {
  return { development: "开发", staging: "预发", production: "生产" }[value];
}

function toggleFavorite(connection: MobileConnectionSummary) {
  const current = preference(connection);
  saveConnectionPreference(connection.id, { ...current, favorite: !current.favorite });
  preferenceRevision.value += 1;
}

function togglePinned(connection: MobileConnectionSummary) {
  const current = preference(connection);
  saveConnectionPreference(connection.id, { ...current, pinned: !current.pinned });
  preferenceRevision.value += 1;
}

function openConnection(connection: MobileConnectionSummary) {
  saveConnectionPreference(connection.id, { ...preference(connection), lastUsedAt: Date.now() });
  preferenceRevision.value += 1;
  emit("browse", connection);
}

function openSort() {
  pendingSortMode.value = sortMode.value;
  advancedSearchOpen.value = false;
  sortOpen.value = true;
}

function openAdvancedSearch() {
  pendingAdvancedFilters.value = { ...advancedFilters.value };
  sortOpen.value = false;
  advancedSearchOpen.value = true;
}

function applySort() {
  sortMode.value = pendingSortMode.value;
  saveConnectionSortMode(sortMode.value);
  sortOpen.value = false;
}

function resetSort() {
  pendingSortMode.value = "recent";
}

function applyAdvancedFilters() {
  advancedFilters.value = { ...pendingAdvancedFilters.value };
  environment.value = "all";
  favoritesOnly.value = false;
  advancedSearchOpen.value = false;
}

function resetAdvancedFilters() {
  pendingAdvancedFilters.value = emptyAdvancedFilters();
  advancedFilters.value = emptyAdvancedFilters();
}

function resetEditor() {
  Object.assign(draft, blankDraft());
  hasStoredConnectionString.value = false;
  groupDraft.value = "未分组";
  preservedTags.value = [];
  preferenceEnvironment.value = "development";
  advancedOpen.value = false;
  editorStep.value = 1;
  editorMessage.value = "";
  editorTone.value = "neutral";
}

function openCreate() {
  resetEditor();
  editorOpen.value = true;
}

async function loadSavedSshProfiles() {
  sshProfilesError.value = "";
  try {
    sshProfiles.value = await listSshProfiles();
  } catch (error) {
    sshProfiles.value = [];
    sshProfilesError.value = error instanceof Error ? error.message : "读取已保存 SSH 配置失败";
  }
}

async function openEdit(connection: MobileConnectionSummary) {
  resetEditor();
  editorOpen.value = true;
  editorMessage.value = "正在读取安全配置…";
  try {
    const value = await getDirectConnection(connection.id);
    // 原生层只返回 has* 标记；秘密字段保持空白，保存时由原生保险箱决定是否沿用旧值。
    Object.assign(draft, {
      id: value.id,
      name: value.name,
      note: value.note,
      dbType: value.dbType,
      host: value.host,
      port: value.port,
      username: value.username,
      password: "",
      database: value.database,
      color: null,
      ssl: value.ssl,
      sslMode: value.sslMode ?? "verify-full",
      readOnly: value.readOnly,
      isProduction: value.isProduction,
      connectTimeoutSecs: value.connectTimeoutSecs,
      queryTimeoutSecs: value.queryTimeoutSecs,
      keepaliveIntervalSecs: value.keepaliveIntervalSecs,
      proxyEnabled: value.proxyEnabled,
      proxyHost: value.proxyHost,
      proxyPort: value.proxyPort,
      proxyUsername: value.proxyUsername,
      proxyPassword: "",
      sshEnabled: value.sshEnabled ?? false,
      sshProfileId: value.sshProfileId ?? "",
      sshHost: value.sshHost ?? "",
      sshPort: value.sshPort ?? 22,
      sshUsername: value.sshUsername ?? "",
      sshHostKeyFingerprint: value.sshHostKeyFingerprint ?? "",
      sshPassword: "",
      sshAuthMethod: value.sshAuthMethod ?? "password",
      sshPrivateKey: "",
      sshPrivateKeyPassphrase: "",
      connectionString: value.connectionString,
    });
    hasStoredConnectionString.value = value.hasConnectionString;
    const local = preference(connection);
    groupDraft.value = local.group;
    preservedTags.value = local.tags;
    preferenceEnvironment.value = local.environment;
    const preserved: string[] = [];
    if (value.hasPassword || value.hasProxyPassword || value.hasSshPassword) preserved.push("密码");
    if (value.hasSshPrivateKey || value.hasSshPrivateKeyPassphrase) preserved.push("SSH 私钥");
    if (value.hasConnectionString) preserved.push("连接串");
    if (value.tunnelLayerCount) preserved.push(`${value.tunnelLayerCount} 层 SSH/HTTP 隧道`);
    const storageLabel = "本机加密仓库";
    editorMessage.value = preserved.length ? `${preserved.join("、")}留空或不修改时将继续使用${storageLabel}已保存的配置。` : "";
  } catch (error) {
    handleError(error);
  }
}

function handleError(error: unknown) {
  editorTone.value = "danger";
  editorMessage.value = error instanceof Error ? error.message : "操作失败";
  if (editorMessage.value.startsWith("SSL 证书验证失败")) openSslSettings();
}

function openSslSettings() {
  editorStep.value = 3;
  nextTick(() => {
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(".editor-sheet")?.scrollTo({ top: 0, behavior: "smooth" });
    });
  });
}

function validateDraft(): boolean {
  if (!draft.name.trim() || (!draft.host.trim() && !draft.connectionString.trim() && !hasStoredConnectionString.value) || !draft.port) {
    editorTone.value = "danger";
    editorMessage.value = "请填写连接名称、主机（或连接串）和端口。";
    editorStep.value = !draft.name.trim() ? 1 : 2;
    return false;
  }
  if (draft.proxyEnabled && (!draft.proxyHost.trim() || !draft.proxyPort)) {
    editorTone.value = "danger";
    editorMessage.value = "启用代理后必须填写代理主机和端口。";
    editorStep.value = 3;
    return false;
  }
  if (draft.sshEnabled && !draft.sshProfileId && (!draft.sshHost.trim() || !draft.sshPort || !draft.sshUsername.trim())) {
    editorTone.value = "danger";
    editorMessage.value = "启用 SSH 后必须填写 SSH 主机、端口和用户名。";
    editorStep.value = 3;
    return false;
  }
  return true;
}

function goToEditorStep(step: 1 | 2 | 3 | 4) {
  editorStep.value = step;
  advancedOpen.value = false;
  nextTick(() => document.querySelector<HTMLElement>(".editor-sheet")?.scrollTo({ top: 0, behavior: "smooth" }));
}

function advanceEditor() {
  if (editorStep.value === 1 && !draft.name.trim()) {
    editorTone.value = "danger";
    editorMessage.value = "请先填写连接名称。";
    return;
  }
  if (editorStep.value === 2 && (!draft.host.trim() && !draft.connectionString.trim() && !hasStoredConnectionString.value)) {
    editorTone.value = "danger";
    editorMessage.value = "请填写数据库主机或连接串。";
    return;
  }
  editorMessage.value = "";
  editorTone.value = "neutral";
  goToEditorStep(Math.min(4, editorStep.value + 1) as 1 | 2 | 3 | 4);
}

function retreatEditor() {
  if (editorStep.value === 1) {
    editorOpen.value = false;
    return;
  }
  goToEditorStep((editorStep.value - 1) as 1 | 2 | 3);
}

function resetFilters() {
  environment.value = "all";
  favoritesOnly.value = false;
  advancedFilters.value = emptyAdvancedFilters();
  pendingAdvancedFilters.value = emptyAdvancedFilters();
}

async function testConnection() {
  if (!validateDraft()) return;
  testing.value = true;
  editorMessage.value = "正在从手机直接测试连接…";
  editorTone.value = "neutral";
  try {
    const result = await testDirectConnection(draft);
    editorTone.value = "success";
    editorMessage.value = result || "连接测试通过";
  } catch (error) {
    handleError(error);
  } finally {
    testing.value = false;
  }
}

async function saveConnection() {
  if (!validateDraft()) return;
  saving.value = true;
  editorMessage.value = "正在保存…";
  editorTone.value = "neutral";
  try {
    // 环境标签不仅用于列表筛选：生产环境必须同步到原生连接配置，确保所有数据库写入
    // 都会经过“输入完整连接名称”的安全门，不能只依赖 WebView 中的视觉标签。
    draft.isProduction = preferenceEnvironment.value === "production";
    const saved = await saveDirectConnection(draft);
    saveConnectionPreference(saved.id, {
      group: groupDraft.value.trim() || "未分组",
      favorite: draft.id ? preference(saved).favorite : false,
      pinned: draft.id ? preference(saved).pinned : false,
      environment: preferenceEnvironment.value,
      tags: preservedTags.value,
      lastUsedAt: draft.id ? preference(saved).lastUsedAt : 0,
    });
    editorOpen.value = false;
    emit("changed");
  } catch (error) {
    handleError(error);
  } finally {
    saving.value = false;
  }
}

async function deleteConnection(connection: MobileConnectionSummary) {
  try {
    await deleteDirectConnection(connection.id);
    removeConnectionPreference(connection.id);
    deleteCandidate.value = null;
    emit("changed");
  } catch (error) {
    handleError(error);
  }
}

function changeDatabaseType() {
  const selected = databaseCapability(draft.dbType);
  const defaultUsernames = new Set(databaseTypes.map((item) => item.defaultUsername));
  if (!draft.host.trim()) draft.host = selected.defaultHost;
  draft.port = selected.port;
  draft.ssl = false;
  draft.database = selected.defaultDatabase;
  if (!draft.username.trim() || defaultUsernames.has(draft.username)) {
    draft.username = selected.defaultUsername;
  }
}

function handleBack() {
  if (deleteCandidate.value) {
    deleteCandidate.value = null;
    return true;
  }
  if (advancedOpen.value) {
    advancedOpen.value = false;
    return true;
  }
  if (sortOpen.value || advancedSearchOpen.value) {
    sortOpen.value = false;
    advancedSearchOpen.value = false;
    return true;
  }
  if (editorOpen.value) {
    if (editorStep.value > 1) {
      retreatEditor();
      return true;
    }
    editorOpen.value = false;
    return true;
  }
  return false;
}

defineExpose({ handleBack });
onMounted(loadSavedSshProfiles);
</script>

<template>
  <div class="connection-manager manage-mode">
    <div class="catalog-tools">
      <label class="catalog-search">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6" /><path d="m16 16 4 4" /></svg>
        <input v-model="search" type="search" placeholder="搜索名称、主机、标签或分组" />
        <button v-if="search" type="button" aria-label="清除搜索" @click="search = ''">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 7 10 10M17 7 7 17" /></svg>
        </button>
      </label>
      <button class="filter-button" type="button" aria-label="连接排序" :aria-expanded="sortOpen" :class="{ active: sortOpen || sortMode !== 'recent' }" @click="openSort">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4v16M5 7l3-3 3 3M16 20V4m-3 13 3 3 3-3" /></svg>
      </button>
      <button class="filter-button" type="button" :aria-label="advancedFilterCount ? `高级搜索，已应用 ${advancedFilterCount} 个条件` : '打开高级搜索'" :aria-expanded="advancedSearchOpen" :class="{ active: advancedSearchOpen || advancedFilterCount > 0 }" @click="openAdvancedSearch">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M7 12h10M10 18h4" /></svg>
        <i v-if="advancedFilterCount">{{ advancedFilterCount }}</i>
      </button>
    </div>

    <div class="filter-strip">
      <button :class="{ active: environment === 'all' }" type="button" @click="environment = 'all'">全部</button>
      <button :class="{ active: favoritesOnly }" type="button" @click="favoritesOnly = !favoritesOnly">收藏</button>
      <button :class="{ active: environment === 'development' }" type="button" @click="environment = 'development'">开发</button>
      <button :class="{ active: environment === 'staging' }" type="button" @click="environment = 'staging'">预发</button>
      <button :class="{ active: environment === 'production' }" type="button" @click="environment = 'production'">生产</button>
      <button v-if="activeFilterCount" class="reset-filter" type="button" @click="resetFilters">重置</button>
    </div>

    <PageState v-if="groupedConnections.length === 0" compact title="没有匹配的连接" description="调整搜索或筛选条件，也可以直接创建新的数据库连接。" />

    <section v-for="group in groupedConnections" :key="group.type" class="connection-group">
      <button class="group-heading" type="button" :aria-expanded="!collapsedGroups.has(group.type)" @click="toggleGroup(group.type)">
        <span><b :class="{ collapsed: collapsedGroups.has(group.type) }">⌄</b>{{ group.label }}</span>
        <small>{{ group.items.length }}</small>
      </button>
      <article v-for="connection in collapsedGroups.has(group.type) ? [] : group.items" :key="connection.id" class="managed-connection">
        <div class="connection-main" role="button" tabindex="0" @click="openConnection(connection)" @keydown.enter="openConnection(connection)">
          <span class="database-mark" :data-type="connection.dbType">
            <img v-if="databaseIcon(connection.dbType)" :src="databaseIcon(connection.dbType)" alt="" />
            <svg v-else viewBox="0 0 24 24" aria-hidden="true">
              <ellipse cx="12" cy="5.5" rx="7" ry="2.7" />
              <path d="M5 5.5v6c0 1.5 3.1 2.7 7 2.7s7-1.2 7-2.7v-6M5 11.5v6c0 1.5 3.1 2.7 7 2.7s7-1.2 7-2.7v-6" />
            </svg>
          </span>
          <span class="connection-copy">
            <span class="connection-title">
              <strong>{{ connection.name }}</strong>
              <em :data-env="preference(connection).environment">{{ environmentLabel(preference(connection).environment) }}</em>
            </span>
            <p>{{ connection.host }}:{{ connection.port }}</p>
            <span class="connection-tags">
              <i v-for="tag in preference(connection).tags" :key="tag">{{ tag }}</i>
              <i>{{ databaseUsageTag(connection.dbType) }}</i>
              <i v-if="connection.ssl">TLS</i>
            </span>
          </span>
          <button class="card-favorite" :class="{ favorite: preference(connection).favorite }" :aria-label="preference(connection).favorite ? '取消收藏' : '收藏'" type="button" @click.stop="toggleFavorite(connection)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1-4.4-4.3 6.1-.9Z" /></svg>
          </button>
        </div>
        <div class="connection-actions">
          <span :title="databaseName(connection)">{{ databaseName(connection) }}</span>
          <button :class="{ pinned: preference(connection).pinned }" type="button" @click="togglePinned(connection)">{{ preference(connection).pinned ? "取消置顶" : "置顶" }}</button>
          <button type="button" @click="openEdit(connection)">编辑</button>
          <button class="danger-text" type="button" @click="deleteCandidate = connection">删除</button>
        </div>
      </article>
    </section>

    <button class="add-connection" type="button" aria-label="新建连接" @click="openCreate">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
    </button>

    <ConfirmDialog
      :open="!!deleteCandidate"
      tone="danger"
      title="删除数据库连接？"
      :description="deleteCandidate ? `连接“${deleteCandidate.name}”及本机加密保存的凭据将被移除，此操作无法撤销。` : ''"
      confirm-label="永久删除"
      @cancel="deleteCandidate = null"
      @confirm="deleteCandidate && deleteConnection(deleteCandidate)"
    />

    <ConnectionCatalogSheets
      :advanced-open="advancedSearchOpen"
      :sort-open="sortOpen"
      :advanced-filter-count="advancedFilterCount"
      :filters="pendingAdvancedFilters"
      :sort-mode="pendingSortMode"
      :tag-options="tagOptions"
      @close="sortOpen = false; advancedSearchOpen = false"
      @reset-filters="resetAdvancedFilters"
      @apply-filters="applyAdvancedFilters"
      @reset-sort="resetSort"
      @apply-sort="applySort"
      @update:filters="pendingAdvancedFilters = $event"
      @update:sort-mode="pendingSortMode = $event"
    />

    <Teleport to="body">
      <div v-if="editorOpen" class="sheet-backdrop" @click.self="editorOpen = false">
        <form class="editor-sheet" @submit.prevent="saveConnection">
          <div class="sheet-handle"></div>
          <header>
            <div>
              <small>CONNECTION EDITOR</small>
              <h4>{{ draft.id ? "编辑连接" : "创建连接" }}</h4>
            </div>
            <button type="button" aria-label="关闭" @click="editorOpen = false"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
          </header>

          <nav class="editor-progress" aria-label="连接编辑步骤">
            <button v-for="(step, index) in editorStepCopy" :key="step.label" :class="{ active: editorStep === index + 1, complete: editorStep > index + 1 }" type="button" @click="goToEditorStep((index + 1) as 1 | 2 | 3 | 4)">
              <span>{{ editorStep > index + 1 ? "✓" : index + 1 }}</span>
              <b>{{ step.label }}</b>
              <small>{{ step.description }}</small>
            </button>
          </nav>

          <section v-if="editorStep === 1" class="connection-tab-panel">
            <div class="step-heading"><small>STEP 1 / 4</small><h5>这是什么连接？</h5><p>选择数据库类型，并用名称、分组和环境帮助后续查找。</p></div>
            <div class="database-type-grid" role="group" aria-label="数据库类型">
              <button v-for="item in databaseTypes" :key="item.value" :class="{ selected: draft.dbType === item.value }" type="button" @click="draft.dbType = item.value; changeDatabaseType()">
                <span class="type-icon"><img v-if="databaseIcon(item.value)" :src="databaseIcon(item.value)" alt="" /><svg v-else viewBox="0 0 24 24" aria-hidden="true"><ellipse cx="12" cy="6" rx="6" ry="2.5" /><path d="M6 6v6c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5V6M6 12v6c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5v-6" /></svg></span>
                <span><strong>{{ item.label }}</strong><small>默认端口 {{ item.port }}</small></span>
              </button>
            </div>
            <div class="editor-grid">
              <label class="wide"><span>连接名称</span><input v-model="draft.name" placeholder="例如：订单生产库" /></label>
              <label class="wide"><span>标签或分组</span><input v-model="groupDraft" placeholder="例如：核心业务" /></label>
              <fieldset class="wide environment-picker">
                <legend>环境标签</legend>
                <label v-for="item in environmentOptions" :key="item.value" :data-env="item.value">
                  <input v-model="preferenceEnvironment" type="radio" name="connection-environment" :value="item.value" />
                  <span>{{ item.label }}</span>
                </label>
              </fieldset>
            </div>
            <p v-if="currentCapability.browse === 'unsupported'" class="capability-notice">此类型可在手机端创建、测试和编辑；当前没有专用数据浏览器，不会调用关系型 Schema API。</p>
          </section>

          <section v-else-if="editorStep === 2" class="connection-tab-panel">
            <div class="step-heading"><small>STEP 2 / 4</small><h5>服务器与认证</h5><p>敏感信息只会交给 Android 原生安全存储，不会从连接详情回显。</p></div>
            <div class="editor-grid">
              <label class="wide"><span>主机</span><input v-model="draft.host" autocapitalize="none" placeholder="db.internal" /></label>
              <p class="wide remote-hint">
                <strong>跨网络连接</strong>
                请填写公网域名、组网 VPN 地址，或在下一步配置 SSH 跳板机。
              </p>
              <label><span>端口</span><input v-model.number="draft.port" type="number" min="1" max="65535" /></label>
              <label><span>数据库</span><input v-model="draft.database" :disabled="draft.dbType === 'etcd'" autocapitalize="none" :placeholder="draft.dbType === 'etcd' ? 'etcd 不使用数据库编号' : '可选，例如 login_system'" /></label>
              <label><span>用户名</span><input v-model="draft.username" autocapitalize="none" autocomplete="username" /></label>
              <label><span>密码</span><input v-model="draft.password" type="password" autocomplete="new-password" placeholder="留空则不修改" /></label>
              <label class="wide"><span>备注</span><textarea v-model="draft.note" rows="2" placeholder="用途、负责人或维护窗口"></textarea></label>
            </div>

            <p v-if="currentCapability.browse === 'unsupported'" class="capability-notice">此类型可在手机端创建、测试和编辑；当前没有专用数据浏览器，不会调用关系型 Schema API。</p>

            <div v-if="draft.dbType === 'mongodb'" class="special-editor">
              <div class="editor-grid">
                <label class="wide"><span>MongoDB URI</span><input v-model="draft.connectionString" autocapitalize="none" autocomplete="off" placeholder="mongodb://…；留空保留已保存 URI" /></label>
              </div>
              <p>普通主机模式下用户名和密码默认在 <code>admin</code> 库认证；若账号创建在其他认证库，请使用 URI 并添加 <code>authSource=库名</code>。</p>
            </div>

            <div v-if="draft.dbType === 'redis'" class="special-editor">
              <p class="security-note">当前安卓原生驱动只支持 Redis Standalone，数据库编号请填写在“数据库”字段。</p>
            </div>

            <div v-if="draft.dbType === 'sqlserver'" class="special-editor">
              <p class="security-note">SQL Server 使用 Android 兼容的 TDS 驱动；开启 SSL 后，“要求”允许自签名证书，“验证 CA/完整验证”会校验证书。</p>
            </div>

            <div v-if="draft.dbType === 'etcd'" class="special-editor">
              <p class="security-note">使用 etcd v3 JSON Gateway。用户名和密码用于 etcd Auth；未启用认证时可留空。etcd 没有数据库编号，“数据库”字段保持为空即可。</p>
            </div>

            <div class="toggle-grid">
              <label><input v-model="draft.readOnly" type="checkbox" /><span>只读连接</span></label>
              <p class="production-hint">选择“生产”环境会自动开启生产写入确认。</p>
            </div>

            <button class="advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
              <span>超时与保活参数</span><svg :class="{ expanded: advancedOpen }" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5" /></svg>
            </button>
            <div v-if="advancedOpen" class="advanced-editor">
              <div class="editor-grid">
                <label><span>连接超时 / 秒</span><input v-model.number="draft.connectTimeoutSecs" type="number" min="1" max="300" /></label>
                <label><span>查询超时 / 秒</span><input v-model.number="draft.queryTimeoutSecs" type="number" min="1" max="3600" /></label>
                <label><span>保活间隔 / 秒</span><input v-model.number="draft.keepaliveIntervalSecs" type="number" min="1" max="3600" /></label>
              </div>
            </div>
          </section>

          <section v-else-if="editorStep === 3" class="connection-tab-panel">
            <div class="step-heading"><small>STEP 3 / 4</small><h5>安全与隧道</h5><p>只启用当前网络环境真正需要的传输方式。</p></div>
            <div class="security-stack">
              <section class="transport-panel">
                <div class="transport-heading"><div><small>ENCRYPTED TRANSPORT</small><h5>SSL / TLS</h5></div><label class="rail-switch"><input v-model="draft.ssl" type="checkbox" /><span></span></label></div>
                <p>跨公网直连时建议开启，并优先验证服务器证书和主机名。</p>
                <div v-if="draft.ssl" class="editor-grid"><label class="wide"><span>证书验证模式</span><select v-model="draft.sslMode"><option value="required">仅加密（允许自签名证书）</option><option value="verify-ca">验证证书颁发机构</option><option value="verify-full">验证证书和主机名</option></select></label></div>
                <p v-if="draft.ssl && draft.sslMode === 'required'" class="security-note">仅加密不会验证服务器身份，生产连接建议使用完整验证。</p>
              </section>

              <section class="transport-panel">
                <div class="transport-heading"><div><small>LOCAL PORT FORWARD</small><h5>SSH 隧道</h5></div><label class="rail-switch"><input v-model="draft.sshEnabled" type="checkbox" /><span></span></label></div>
                <p>手机先登录跳板机，再由跳板机访问数据库地址。</p>
                <template v-if="draft.sshEnabled">
                  <div class="editor-grid ssh-profile-picker"><label class="wide"><span>已保存的 SSH 配置</span><select v-model="draft.sshProfileId"><option value="">手动填写本连接</option><option v-for="profile in sshProfiles" :key="profile.id" :value="profile.id">{{ profile.name }} · {{ profile.username }}@{{ profile.host }}</option></select></label></div>
                  <div v-if="selectedSshProfile" class="saved-ssh-card"><div><strong>{{ selectedSshProfile.name }}</strong><code>{{ selectedSshProfile.username }}@{{ selectedSshProfile.host }}:{{ selectedSshProfile.port }}</code></div><span>{{ selectedSshProfile.authMethod === "private-key" ? `密钥：${selectedSshProfile.keyName || "引用已失效"}` : "密码已加密保存" }}</span></div>
                  <p v-if="sshProfilesError" class="security-note">{{ sshProfilesError }}</p>
                  <p v-else-if="!sshProfiles.length" class="security-note">还没有预存配置，可前往底部“设置”页面添加；也可以继续手动填写。</p>
                  <div v-if="!draft.sshProfileId" class="editor-grid">
                    <label class="wide"><span>SSH 主机</span><input v-model="draft.sshHost" autocapitalize="none" placeholder="bastion.example.com" /></label>
                    <label><span>SSH 端口</span><input v-model.number="draft.sshPort" type="number" min="1" max="65535" /></label>
                    <label><span>SSH 用户名</span><input v-model="draft.sshUsername" autocapitalize="none" autocomplete="username" /></label>
                    <label class="wide"><span>主机密钥 SHA256 指纹</span><input v-model="draft.sshHostKeyFingerprint" autocapitalize="none" placeholder="可选，例如 SHA256:AbCd…" /></label>
                    <label class="wide"><span>认证方式</span><select v-model="draft.sshAuthMethod"><option value="password">密码</option><option value="private-key">私钥</option></select></label>
                    <label v-if="draft.sshAuthMethod === 'password'" class="wide"><span>SSH 密码</span><input v-model="draft.sshPassword" type="password" autocomplete="new-password" placeholder="留空则沿用已保存密码" /></label>
                    <template v-else><label class="wide"><span>OpenSSH / PEM 私钥</span><textarea v-model="draft.sshPrivateKey" rows="7" autocapitalize="none" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----&#10;留空则沿用已保存私钥"></textarea></label><label class="wide"><span>私钥口令</span><input v-model="draft.sshPrivateKeyPassphrase" type="password" autocomplete="new-password" placeholder="可选；留空则沿用" /></label></template>
                  </div>
                  <p class="security-note">{{ draft.sshProfileId ? "连接仅保存 SSH 配置 ID，凭据不会复制到 WebView。" : "SSH 密码和私钥通过 Android Keystore 加密保存。" }}生产环境请填写主机密钥指纹。</p>
                </template>
              </section>

              <section class="transport-panel">
                <div class="transport-heading"><div><small>HTTP CONNECT</small><h5>HTTP 代理</h5></div><label class="rail-switch"><input v-model="draft.proxyEnabled" type="checkbox" /><span></span></label></div>
                <p>通过支持 CONNECT 的 HTTP 代理建立数据库 TCP 通道。</p>
                <div v-if="draft.proxyEnabled" class="editor-grid"><label class="wide"><span>代理主机</span><input v-model="draft.proxyHost" autocapitalize="none" placeholder="proxy.example.com" /></label><label><span>代理端口</span><input v-model.number="draft.proxyPort" type="number" min="1" max="65535" /></label><label><span>代理用户名</span><input v-model="draft.proxyUsername" autocomplete="username" placeholder="可选" /></label><label class="wide"><span>代理密码</span><input v-model="draft.proxyPassword" type="password" autocomplete="new-password" placeholder="可选；留空则沿用已保存密码" /></label></div>
                <p v-if="draft.proxyEnabled" class="security-note">HTTP 代理本身不等于加密；链路不可信时请同时开启数据库 TLS。</p>
              </section>
            </div>
          </section>

          <section v-else class="connection-tab-panel review-step">
            <div class="step-heading"><small>STEP 4 / 4</small><h5>确认连接信息</h5><p>保存前检查目标与安全策略，也可以先测试手机到数据库的连通性。</p></div>
            <dl class="connection-review">
              <div><dt>名称</dt><dd>{{ draft.name || "未命名连接" }}</dd></div>
              <div><dt>数据库</dt><dd>{{ currentCapability.label }}</dd></div>
              <div><dt>环境</dt><dd>{{ environmentLabel(preferenceEnvironment) }}</dd></div>
              <div><dt>服务器</dt><dd>{{ draft.host || "连接串" }}:{{ draft.port }}</dd></div>
              <div><dt>认证</dt><dd>{{ draft.username || "未填写用户名" }}</dd></div>
              <div><dt>安全通道</dt><dd>{{ [draft.ssl && "TLS", draft.sshEnabled && "SSH", draft.proxyEnabled && "HTTP"].filter(Boolean).join(" + ") || "未启用" }}</dd></div>
              <div><dt>写入策略</dt><dd :class="{ danger: preferenceEnvironment === 'production', readonly: draft.readOnly }">{{ draft.readOnly ? "只读连接" : preferenceEnvironment === "production" ? "生产写入确认" : "允许写入" }}</dd></div>
            </dl>
            <button class="review-test" :disabled="testing || saving" type="button" @click="testConnection">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 12h8M12 8v8" /><path d="M6 3h12v5a6 6 0 0 1-12 0Z" /><path d="M8 21h8" /></svg>
              <span><strong>{{ testing ? "正在测试连接…" : "测试连接" }}</strong><small>从当前手机直接验证数据库连通性</small></span>
            </button>
            <p class="security-note final-note">密码、连接串、代理密码和 SSH 私钥不会从原生安全存储返回 WebView。</p>
          </section>

          <p v-if="editorMessage" class="editor-message" :data-tone="editorTone">{{ editorMessage }}</p>
          <button v-if="sslCertificateError" class="error-action" type="button" @click="openSslSettings">打开 SSL 设置</button>
          <footer class="editor-footer">
            <button class="test-action" :disabled="testing || saving" type="button" @click="retreatEditor">{{ editorStep === 1 ? "取消" : "上一步" }}</button>
            <button v-if="editorStep < 4" class="save-action" :disabled="saving || testing" type="button" @click="advanceEditor">下一步</button>
            <button v-else class="save-action" :disabled="saving || testing" type="submit">{{ saving ? "保存中…" : draft.id ? "保存修改" : "保存连接" }}</button>
          </footer>
        </form>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.connection-manager {
  margin-top: 2px;
  font-weight: 400;
}
.catalog-tools {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 42px 42px;
  gap: var(--space-2);
}
.catalog-search {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
  padding: 0 var(--space-3);
  box-shadow: 0 4px 16px rgba(23, 32, 51, 0.04);
}
.catalog-search > svg {
  width: 18px;
  height: 18px;
  flex: none;
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.catalog-search button {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  border: 0;
  background: transparent;
  color: var(--muted);
}
.catalog-search button svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
}
.catalog-search input {
  width: 100%;
  min-width: 0;
  height: 42px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 11px;
}
.filter-button {
  position: relative;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
  color: var(--muted);
}
.filter-button i {
  position: absolute;
  top: 3px;
  right: 3px;
  display: grid;
  width: 15px;
  height: 15px;
  place-items: center;
  border-radius: 50%;
  background: var(--acid);
  color: #fff;
  font-size: 7px;
  font-style: normal;
}
.filter-button svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.filter-button.active {
  border-color: var(--acid);
  background: var(--accent-soft);
  color: var(--acid);
}
.add-connection {
  position: fixed;
  z-index: 8;
  right: 20px;
  bottom: calc(var(--bottom-nav-height) + var(--page-bottom-safe) + var(--space-2));
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: linear-gradient(145deg, #2487ff, #0868ee);
  color: #fff;
  box-shadow: 0 10px 26px rgba(22, 119, 255, 0.38);
}
.add-connection svg {
  width: 25px;
  height: 25px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
}
.filter-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin: 10px 0 14px;
}
.filter-strip button {
  flex: 1 1 54px;
  min-width: 54px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: transparent;
  padding: 6px 11px;
  color: var(--muted);
  font-size: 10px;
}
.filter-strip button.active {
  border-color: var(--acid);
  background: color-mix(in srgb, var(--acid) 7%, transparent);
  color: var(--acid);
}
.filter-strip .reset-filter {
  border-color: transparent;
  color: var(--danger);
}
.connection-group {
  margin-top: 10px;
}
.group-heading {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  border: 0;
  background: transparent;
  padding: 0 4px;
  color: var(--muted);
  font: inherit;
  font-size: 10px;
  text-align: left;
}
.group-heading span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.group-heading span b {
  color: var(--ink);
  font-size: 11px;
  font-weight: 500;
  transition: transform 150ms ease;
}
.group-heading span b.collapsed {
  transform: rotate(-90deg);
}
.group-heading small {
  color: var(--muted);
  font:
    9px "Azeret Mono Variable",
    monospace;
}
.managed-connection {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
  box-shadow: 0 5px 18px rgba(23, 32, 51, 0.045);
  transition: border-color 150ms ease, box-shadow 150ms ease, transform 150ms ease;
}
.managed-connection + .managed-connection {
  margin-top: 5px;
}
.connection-main {
  display: grid;
  width: 100%;
  grid-template-columns: 50px minmax(0, 1fr) 32px;
  align-items: center;
  gap: 10px;
  padding: 9px 8px 7px;
  text-align: left;
  cursor: pointer;
}
.managed-connection:active {
  border-color: color-mix(in srgb, var(--acid) 32%, var(--line));
  transform: scale(0.995);
}
.connection-main:focus-visible {
  outline: 2px solid var(--acid);
  outline-offset: -2px;
}
.database-mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 10px;
  background: transparent;
  color: var(--acid);
}
.database-mark img {
  width: 38px;
  height: 38px;
  object-fit: contain;
}
.database-mark svg {
  width: 36px;
  height: 36px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
}
.connection-copy {
  min-width: 0;
}
.connection-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
}
.connection-title strong {
  overflow: hidden;
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.connection-title em {
  flex: none;
  border: 1px solid color-mix(in srgb, var(--acid) 28%, var(--line));
  border-radius: 4px;
  background: var(--accent-soft);
  padding: 2px 5px;
  color: var(--acid);
  font-size: 8px;
  font-style: normal;
}
.connection-main p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--muted);
  font:
    9px "Azeret Mono Variable",
    monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.connection-main .connection-tags {
  display: flex;
  overflow: hidden;
  gap: 4px;
  margin-top: 5px;
}
.connection-tags i {
  flex: none;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--field);
  padding: 2px 5px;
  color: var(--muted);
  font-size: 8px;
  font-style: normal;
  line-height: 1.35;
}
.connection-tags i:last-child {
  color: var(--acid);
}
.card-favorite {
  width: 34px;
  height: 38px;
  border: 0;
  background: transparent;
  color: var(--faint);
}
.card-favorite svg {
  width: 21px;
  height: 21px;
  fill: transparent;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linejoin: round;
}
.card-favorite.favorite {
  color: #f6b800;
}
.card-favorite.favorite svg {
  fill: currentColor;
}
.connection-actions {
  display: none;
  min-height: 30px;
  align-items: center;
  justify-content: flex-end;
  border-top: 1px solid color-mix(in srgb, var(--line) 55%, transparent);
  padding: 0 4px 0 68px;
}
.manage-mode .connection-actions {
  display: flex;
}
.connection-actions span {
  overflow: hidden;
  flex: 1;
  color: var(--faint);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.connection-actions button {
  min-width: 40px;
  border: 0;
  background: transparent;
  padding: 4px 7px;
  color: var(--muted);
  font-size: 8px;
}
.connection-actions .danger-text {
  color: var(--danger);
}
.connection-actions button.pinned {
  color: var(--acid);
}
.catalog-empty {
  min-height: 180px;
  border: 1px dashed var(--line);
  padding: 28px 20px;
}
.catalog-empty > b {
  color: var(--acid);
  font-size: 28px;
}
.catalog-empty strong {
  display: block;
  margin-top: 18px;
  font-size: 14px;
}
.catalog-empty p {
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 11px;
  line-height: 1.6;
}
.sheet-backdrop {
  position: fixed;
  z-index: 20;
  inset: 0;
  display: flex;
  align-items: stretch;
  justify-content: center;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(6px);
}
.editor-sheet {
  overflow-y: auto;
  width: min(720px, 100%);
  height: 100%;
  max-height: none;
  border: 0;
  background: var(--panel-raised);
  padding: 10px 18px 0;
  box-shadow: 0 -30px 90px rgba(0, 0, 0, 0.35);
  overscroll-behavior: contain;
}
.sheet-handle {
  width: 42px;
  height: 3px;
  margin: 0 auto 15px;
  background: #4e554f;
}
.editor-sheet header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 19px;
}
.editor-sheet header small {
  color: var(--acid);
  font-size: 8px;
  letter-spacing: 0.13em;
}
.editor-sheet h4 {
  margin: 6px 0 0;
  font-size: 21px;
}
.editor-sheet header button {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 0;
  background: transparent;
  color: var(--muted);
}
.editor-sheet header button svg {
  width: 21px;
  height: 21px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
}
.connection-tabs {
  position: sticky;
  z-index: 10;
  top: 0;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin: 2px 0 18px;
  border-bottom: 1px solid var(--line);
  background: var(--panel-raised);
  padding-top: 8px;
  box-shadow: 0 8px 14px rgba(0, 0, 0, 0.1);
}
.connection-tabs button {
  position: relative;
  display: flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.04em;
}
.connection-tabs button.active {
  border-bottom-color: var(--acid);
  color: var(--ink);
}
.connection-tabs i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--faint);
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.035);
}
.connection-tabs i[data-on="true"] {
  background: var(--acid);
  box-shadow: 0 0 8px rgba(199, 255, 61, 0.65);
}
.editor-progress {
  position: sticky;
  z-index: 10;
  top: -10px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0 -18px 20px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--panel-raised) 96%, transparent);
  padding: 9px 12px 11px;
  backdrop-filter: blur(18px);
}
.editor-progress button {
  position: relative;
  display: grid;
  min-width: 0;
  place-items: center;
  gap: 2px;
  border: 0;
  background: transparent;
  color: var(--muted);
}
.editor-progress button::after {
  position: absolute;
  top: 13px;
  right: -25%;
  width: 50%;
  height: 1px;
  background: var(--line);
  content: "";
}
.editor-progress button:last-child::after {
  display: none;
}
.editor-progress button > span {
  display: grid;
  z-index: 1;
  width: 27px;
  height: 27px;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--panel-raised);
  font-size: 10px;
}
.editor-progress button b {
  margin-top: 3px;
  font-size: 10px;
  font-weight: 680;
}
.editor-progress button small {
  overflow: hidden;
  max-width: 100%;
  font-size: 7px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.editor-progress button.active,
.editor-progress button.complete {
  color: var(--acid);
}
.editor-progress button.active > span,
.editor-progress button.complete > span {
  border-color: var(--acid);
  background: var(--accent-soft);
}
.editor-progress button.complete::after {
  background: color-mix(in srgb, var(--acid) 50%, var(--line));
}
.step-heading {
  margin-bottom: 18px;
}
.step-heading > small {
  color: var(--acid);
  font: 8px "Azeret Mono Variable", monospace;
  letter-spacing: 0.12em;
}
.step-heading h5 {
  margin: 7px 0 4px;
  font-size: 18px;
}
.step-heading p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.55;
}
.database-type-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 17px;
}
.database-type-grid > button {
  display: grid;
  min-width: 0;
  min-height: 66px;
  grid-template-columns: 36px minmax(0, 1fr);
  align-items: center;
  gap: 9px;
  border: 1px solid var(--line);
  background: var(--field);
  padding: 8px;
  text-align: left;
}
.database-type-grid > button.selected {
  border-color: color-mix(in srgb, var(--acid) 55%, var(--line));
  background: var(--accent-soft);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--acid) 16%, transparent);
}
.type-icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
}
.type-icon img,
.type-icon svg {
  width: 30px;
  height: 30px;
  object-fit: contain;
}
.type-icon svg {
  fill: none;
  stroke: var(--acid);
  stroke-width: 1.6;
}
.database-type-grid strong,
.database-type-grid small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.database-type-grid strong {
  font-size: 10px;
}
.database-type-grid small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 8px;
}
.connection-tab-panel {
  animation: tab-in 0.16s ease-out;
}
@keyframes tab-in {
  from {
    opacity: 0.35;
    transform: translateY(3px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 10px;
}
.editor-grid label {
  min-width: 0;
  cursor: text;
}
.editor-grid > .wide {
  grid-column: 1 / -1;
}
.editor-grid label > span {
  display: block;
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 9px;
}
.environment-picker {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  min-width: 0;
  margin: 0;
  border: 0;
  padding: 0;
}
.environment-picker legend {
  grid-column: 1 / -1;
  margin-bottom: 6px;
  padding: 0;
  color: var(--muted);
  font-size: 9px;
}
.environment-picker label {
  position: relative;
  display: grid;
  min-height: 48px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--field);
  color: var(--muted);
  cursor: pointer;
}
.environment-picker input {
  position: absolute;
  width: 1px;
  min-height: 1px;
  opacity: 0;
}
.environment-picker label > span {
  margin: 0;
  color: inherit;
  font-size: 11px;
  font-weight: 620;
}
.environment-picker label:has(input:focus-visible) {
  outline: 2px solid var(--acid);
  outline-offset: 2px;
}
.environment-picker label:has(input:checked) {
  border-color: color-mix(in srgb, var(--acid) 38%, var(--line));
  background: var(--accent-soft);
  color: var(--acid);
  box-shadow: inset 0 -2px 0 color-mix(in srgb, var(--acid) 58%, transparent);
}
.remote-hint {
  margin: -3px 0 0;
  border-left: 2px solid var(--acid);
  background: rgba(37, 99, 235, 0.045);
  padding: 9px 10px;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.55;
}
.remote-hint strong {
  display: block;
  margin-bottom: 3px;
  color: var(--ink);
  font-size: 10px;
}
.editor-grid input,
.editor-grid select,
.editor-grid textarea {
  width: 100%;
  min-height: 48px;
  border: 1px solid var(--line);
  border-radius: 7px;
  outline: 0;
  background: var(--field);
  padding: 0 12px;
  color: var(--ink);
  font-size: 12px;
  touch-action: manipulation;
}
.editor-grid textarea {
  padding-top: 10px;
  resize: vertical;
}
.editor-grid input:focus,
.editor-grid select:focus,
.editor-grid textarea:focus {
  border-color: var(--acid);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--acid) 12%, transparent);
}
.toggle-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 5px;
  margin: 16px 0;
}
.toggle-grid label,
.proxy-switch {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--line);
  padding: 8px;
  color: var(--muted);
  font-size: 9px;
}
.toggle-grid input,
.proxy-switch input {
  accent-color: var(--acid);
}
.toggle-grid .production-hint {
  display: flex;
  min-height: 42px;
  align-items: center;
  margin: 0;
  border-left: 2px solid var(--acid);
  background: var(--accent-soft);
  padding: 8px 10px;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.55;
}
.advanced-toggle {
  display: flex;
  width: 100%;
  justify-content: space-between;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--field);
  padding: 13px;
  color: var(--ink);
  font-size: 10px;
  text-align: left;
}
.advanced-toggle svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: var(--acid);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform 150ms ease;
}
.advanced-toggle svg.expanded {
  transform: rotate(180deg);
}
.advanced-toggle b {
  color: var(--acid);
}
.advanced-editor {
  border: 1px solid var(--line);
  border-top: 0;
  padding: 14px 12px;
}
.transport-panel {
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--acid) 5%, var(--panel)), var(--panel) 45%);
  padding: 15px 13px;
}
.security-stack {
  display: grid;
  gap: 10px;
}
.transport-panel > p {
  margin: 0 0 18px;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.65;
}
.ssh-profile-picker {
  margin-bottom: 12px;
}
.saved-ssh-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
  border: 1px solid color-mix(in srgb, var(--acid) 28%, var(--line));
  border-radius: 8px;
  background: var(--accent-soft);
  padding: 11px 12px;
}
.saved-ssh-card > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}
.saved-ssh-card strong {
  font-size: 11px;
}
.saved-ssh-card code {
  overflow: hidden;
  color: var(--muted);
  font-size: 9px;
  text-overflow: ellipsis;
}
.saved-ssh-card > span {
  flex: none;
  color: var(--acid);
  font-size: 8px;
}
.transport-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.transport-heading small {
  color: var(--acid);
  font-size: 7px;
  letter-spacing: 0.17em;
}
.transport-heading h5 {
  margin: 5px 0 0;
  font-size: 16px;
}
.rail-switch {
  position: relative;
}
.rail-switch input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
.rail-switch span {
  display: block;
  width: 43px;
  height: 23px;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: var(--field);
  padding: 3px;
}
.rail-switch span::after {
  display: block;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--faint);
  content: "";
  transition:
    transform 0.16s ease,
    background 0.16s ease;
}
.rail-switch input:checked + span {
  border-color: rgba(199, 255, 61, 0.55);
}
.rail-switch input:checked + span::after {
  transform: translateX(18px);
  background: var(--acid);
  box-shadow: 0 0 10px rgba(199, 255, 61, 0.45);
}
.transport-panel .security-note {
  margin: 14px 0 0;
  border-left: 2px solid var(--amber);
  background: rgba(255, 184, 76, 0.035);
  padding: 9px 10px;
  color: var(--muted);
  font-size: 9px;
}
.special-editor {
  margin-top: 12px;
  border: 1px solid var(--line);
  padding: 12px;
  background: rgba(199, 255, 61, 0.025);
}
.capability-notice,
.tunnel-note {
  border-left: 2px solid var(--amber);
  padding: 9px 11px;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.55;
}
.tunnel-note {
  margin: 13px 0;
  border-left-color: var(--acid);
}
.inline-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 20px;
}
.inline-check input {
  width: auto;
  min-height: auto;
}
.inline-check span {
  margin: 0 !important;
}
.editor-message {
  margin: 13px 0 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.5;
}
.editor-message[data-tone="success"] {
  color: var(--acid);
}
.editor-message[data-tone="danger"] {
  color: var(--danger);
}
.error-action {
  width: 100%;
  min-height: 42px;
  margin-top: 8px;
  border: 1px solid #ff918d;
  background: rgba(255, 145, 141, 0.05);
  color: #ffb0ad;
  font-size: 10px;
}
.editor-sheet .editor-footer {
  position: sticky;
  z-index: 12;
  bottom: 0;
  display: grid;
  grid-template-columns: 1fr 1.35fr;
  gap: 8px;
  margin: 17px -18px 0;
  border-top: 1px solid var(--line);
  background: color-mix(in srgb, var(--panel-raised) 96%, transparent);
  padding: 11px 18px calc(11px + var(--safe-bottom));
  backdrop-filter: blur(18px);
}
.editor-sheet .editor-footer button {
  min-height: 49px;
  font-weight: 720;
  font-size: 10px;
}
.test-action {
  border: 1px solid var(--acid);
  background: transparent;
  color: var(--acid);
}
.save-action {
  border: 0;
  border-radius: 7px;
  background: var(--acid);
  color: #fff;
}
.test-action {
  border-radius: 7px;
}
.editor-sheet .editor-footer button:disabled {
  opacity: 0.55;
}
.connection-review {
  overflow: hidden;
  margin: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--panel);
}
.connection-review > div {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 10px;
  padding: 11px 13px;
}
.connection-review > div + div {
  border-top: 1px solid var(--line);
}
.connection-review dt,
.connection-review dd {
  margin: 0;
  font-size: 10px;
}
.connection-review dt {
  color: var(--muted);
}
.connection-review dd {
  overflow-wrap: anywhere;
  text-align: right;
}
.connection-review dd.danger {
  color: var(--danger);
}
.connection-review dd.readonly {
  color: var(--acid);
}
.review-test {
  display: grid;
  width: 100%;
  min-height: 58px;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin-top: 13px;
  border: 1px solid color-mix(in srgb, var(--acid) 45%, var(--line));
  background: var(--accent-soft);
  padding: 9px 12px;
  color: var(--acid);
  text-align: left;
}
.review-test svg {
  width: 25px;
  height: 25px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.review-test strong,
.review-test small {
  display: block;
}
.review-test strong {
  font-size: 11px;
}
.review-test small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 8px;
}
.final-note {
  margin-top: 13px;
  border-left: 2px solid var(--success);
  background: color-mix(in srgb, var(--success) 6%, transparent);
  padding: 9px 10px;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.55;
}
@media (min-width: 720px) {
  .sheet-backdrop {
    align-items: flex-end;
  }
  .editor-sheet {
    height: auto;
    max-height: 93dvh;
    border: 1px solid var(--line);
    border-bottom: 0;
  }
}
</style>
