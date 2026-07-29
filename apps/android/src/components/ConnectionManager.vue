<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import {
  ApiError,
  apiDeleteJson,
  apiGetJson,
  apiPostJson,
  type MobileConnectionDraft,
  type MobileConnectionEditor,
  type MobileConnectionSummary,
  type MobileDatabaseType,
} from "../lib/mobileApi";
import {
  getConnectionPreference,
  removeConnectionPreference,
  saveConnectionPreference,
  type ConnectionEnvironment,
} from "../lib/connectionPreferences";

const props = defineProps<{
  baseUrl: string;
  serverId: string;
  token: string | null;
  connections: MobileConnectionSummary[];
}>();

const emit = defineEmits<{
  authExpired: [];
  changed: [];
  browse: [connection: MobileConnectionSummary];
}>();

const search = ref("");
const environment = ref<"all" | ConnectionEnvironment>("all");
const favoritesOnly = ref(false);
const editorOpen = ref(false);
const advancedOpen = ref(false);
const saving = ref(false);
const testing = ref(false);
const editorMessage = ref("");
const editorTone = ref<"success" | "danger" | "neutral">("neutral");
const groupDraft = ref("未分组");
const preferenceRevision = ref(0);

const databaseTypes: { value: MobileDatabaseType; label: string; port: number }[] = [
  { value: "postgres", label: "PostgreSQL", port: 5432 },
  { value: "mysql", label: "MySQL", port: 3306 },
  { value: "sqlserver", label: "SQL Server", port: 1433 },
  { value: "mongodb", label: "MongoDB", port: 27017 },
  { value: "redis", label: "Redis", port: 6379 },
  { value: "clickhouse", label: "ClickHouse", port: 8123 },
  { value: "oracle", label: "Oracle", port: 1521 },
  { value: "sqlite", label: "SQLite", port: 1 },
];

function blankDraft(): MobileConnectionDraft {
  return {
    name: "",
    note: "",
    dbType: "postgres",
    host: "",
    port: 5432,
    username: "",
    password: "",
    database: null,
    color: "#c7ff3d",
    ssl: true,
    readOnly: false,
    isProduction: false,
    connectTimeoutSecs: 10,
    queryTimeoutSecs: 60,
    idleTimeoutSecs: 60,
    keepaliveIntervalSecs: 30,
    caCertPath: "",
    clientCertPath: "",
    clientKeyPath: "",
    proxyEnabled: false,
    proxyType: "socks5",
    proxyHost: "",
    proxyPort: 1080,
    proxyUsername: "",
    proxyPassword: "",
  };
}

const draft = reactive<MobileConnectionDraft>(blankDraft());
const preferenceEnvironment = ref<ConnectionEnvironment>("development");

const filteredConnections = computed(() => {
  void preferenceRevision.value;
  const needle = search.value.trim().toLocaleLowerCase();
  return props.connections.filter((connection) => {
    const preference = getConnectionPreference(props.serverId, connection.id, connection.isProduction);
    if (favoritesOnly.value && !preference.favorite) return false;
    if (environment.value !== "all" && preference.environment !== environment.value) return false;
    return (
      !needle ||
      [connection.name, connection.host, connection.database, connection.dbType, connection.note, preference.group]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase().includes(needle))
    );
  });
});

const groupedConnections = computed(() => {
  const groups = new Map<string, MobileConnectionSummary[]>();
  for (const connection of filteredConnections.value) {
    const group = getConnectionPreference(props.serverId, connection.id, connection.isProduction).group;
    groups.set(group, [...(groups.get(group) ?? []), connection]);
  }
  return [...groups.entries()].sort(([left], [right]) => left.localeCompare(right));
});

function preference(connection: MobileConnectionSummary) {
  return getConnectionPreference(props.serverId, connection.id, connection.isProduction);
}

function toggleFavorite(connection: MobileConnectionSummary) {
  const current = preference(connection);
  saveConnectionPreference(props.serverId, connection.id, { ...current, favorite: !current.favorite });
  preferenceRevision.value += 1;
}

function resetEditor() {
  Object.assign(draft, blankDraft());
  groupDraft.value = "未分组";
  preferenceEnvironment.value = "development";
  advancedOpen.value = false;
  editorMessage.value = "";
  editorTone.value = "neutral";
}

function openCreate() {
  resetEditor();
  editorOpen.value = true;
}

async function openEdit(connection: MobileConnectionSummary) {
  resetEditor();
  editorOpen.value = true;
  editorMessage.value = "正在读取安全配置…";
  try {
    const value = await apiGetJson<MobileConnectionEditor>(
      props.baseUrl,
      `/api/mobile/connections/${encodeURIComponent(connection.id)}`,
      props.token,
      {},
    );
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
      color: value.color,
      ssl: value.ssl,
      readOnly: value.readOnly,
      isProduction: value.isProduction,
      connectTimeoutSecs: value.connectTimeoutSecs,
      queryTimeoutSecs: value.queryTimeoutSecs,
      idleTimeoutSecs: value.idleTimeoutSecs,
      keepaliveIntervalSecs: value.keepaliveIntervalSecs,
      caCertPath: value.caCertPath,
      clientCertPath: value.clientCertPath,
      clientKeyPath: value.clientKeyPath,
      proxyEnabled: value.proxyEnabled,
      proxyType: value.proxyType,
      proxyHost: value.proxyHost,
      proxyPort: value.proxyPort,
      proxyUsername: value.proxyUsername,
      proxyPassword: "",
    });
    const local = preference(connection);
    groupDraft.value = local.group;
    preferenceEnvironment.value = local.environment;
    editorMessage.value =
      value.hasPassword || value.hasProxyPassword ? "密码字段留空将继续使用服务端已保存的凭据。" : "";
  } catch (error) {
    handleError(error);
  }
}

function handleError(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    emit("authExpired");
    editorOpen.value = false;
    return;
  }
  editorTone.value = "danger";
  editorMessage.value = error instanceof Error ? error.message : "操作失败";
}

function validateDraft(): boolean {
  if (!draft.name.trim() || !draft.host.trim() || !draft.port) {
    editorTone.value = "danger";
    editorMessage.value = "请填写连接名称、主机和端口。";
    return false;
  }
  if (draft.proxyEnabled && (!draft.proxyHost.trim() || !draft.proxyPort)) {
    editorTone.value = "danger";
    editorMessage.value = "启用代理后必须填写代理主机和端口。";
    return false;
  }
  return true;
}

async function testConnection() {
  if (!validateDraft()) return;
  testing.value = true;
  editorMessage.value = "正在通过服务器测试连接…";
  editorTone.value = "neutral";
  try {
    const result = await apiPostJson<{ message: string }>(
      props.baseUrl,
      "/api/mobile/connections/test",
      props.token,
      draft,
      { timeoutMs: Math.max(35_000, draft.connectTimeoutSecs * 1_000 + 5_000) },
    );
    editorTone.value = "success";
    editorMessage.value = result.message || "连接测试通过";
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
    const saved = await apiPostJson<MobileConnectionSummary>(
      props.baseUrl,
      "/api/mobile/connections/save",
      props.token,
      draft,
    );
    saveConnectionPreference(props.serverId, saved.id, {
      group: groupDraft.value.trim() || "未分组",
      favorite: draft.id ? preference(saved).favorite : false,
      environment: preferenceEnvironment.value,
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
  if (!window.confirm(`删除连接“${connection.name}”？服务端保存的凭据也会一并移除。`)) return;
  try {
    await apiDeleteJson(props.baseUrl, `/api/mobile/connections/${encodeURIComponent(connection.id)}`, props.token);
    removeConnectionPreference(props.serverId, connection.id);
    emit("changed");
  } catch (error) {
    handleError(error);
  }
}

function changeDatabaseType() {
  const selected = databaseTypes.find((item) => item.value === draft.dbType);
  if (selected) draft.port = selected.port;
  draft.ssl = draft.dbType !== "sqlite";
}
</script>

<template>
  <div class="connection-manager">
    <div class="catalog-tools">
      <label class="catalog-search">
        <span aria-hidden="true">⌕</span>
        <input v-model="search" type="search" placeholder="搜索连接、地址、分组…" />
      </label>
      <button class="add-connection" type="button" @click="openCreate"><b>＋</b><span>新建</span></button>
    </div>

    <div class="filter-strip">
      <button :class="{ active: environment === 'all' }" type="button" @click="environment = 'all'">全部</button>
      <button :class="{ active: environment === 'development' }" type="button" @click="environment = 'development'">开发</button>
      <button :class="{ active: environment === 'staging' }" type="button" @click="environment = 'staging'">预发</button>
      <button :class="{ active: environment === 'production' }" type="button" @click="environment = 'production'">生产</button>
      <button :class="{ active: favoritesOnly }" type="button" @click="favoritesOnly = !favoritesOnly">★ 收藏</button>
    </div>

    <div v-if="groupedConnections.length === 0" class="browser-state catalog-empty">
      <b>∅</b><strong>没有匹配的连接</strong><p>调整搜索或筛选条件，也可以直接创建新的数据库连接。</p>
    </div>

    <section v-for="[group, items] in groupedConnections" :key="group" class="connection-group">
      <div class="group-heading"><span>{{ group }}</span><small>{{ items.length }} LINKS</small></div>
      <article v-for="connection in items" :key="connection.id" class="managed-connection">
        <button class="connection-main" type="button" @click="emit('browse', connection)">
          <i :style="{ background: connection.color || 'var(--acid)' }"></i>
          <span>
            <small>
              {{ connection.dbType }}
              <em :data-env="preference(connection).environment">{{ preference(connection).environment.slice(0, 4) }}</em>
            </small>
            <strong>{{ connection.name }}</strong>
            <p>{{ connection.host }}:{{ connection.port }}<template v-if="connection.database"> / {{ connection.database }}</template></p>
          </span>
          <b>›</b>
        </button>
        <div class="connection-actions">
          <button :aria-label="preference(connection).favorite ? '取消收藏' : '收藏'" type="button" @click="toggleFavorite(connection)">
            {{ preference(connection).favorite ? "★" : "☆" }}
          </button>
          <button type="button" @click="openEdit(connection)">编辑</button>
          <button class="danger-text" type="button" @click="deleteConnection(connection)">删除</button>
        </div>
      </article>
    </section>

    <div v-if="editorOpen" class="sheet-backdrop" @click.self="editorOpen = false">
      <form class="editor-sheet" @submit.prevent="saveConnection">
        <div class="sheet-handle"></div>
        <header>
          <div><small>CONNECTION EDITOR</small><h4>{{ draft.id ? "编辑连接" : "创建连接" }}</h4></div>
          <button type="button" aria-label="关闭" @click="editorOpen = false">×</button>
        </header>

        <div class="editor-grid">
          <label class="wide"><span>名称</span><input v-model="draft.name" placeholder="订单库" /></label>
          <label><span>类型</span><select v-model="draft.dbType" @change="changeDatabaseType">
            <option v-for="item in databaseTypes" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select></label>
          <label><span>分组</span><input v-model="groupDraft" placeholder="核心业务" /></label>
          <label class="wide"><span>主机 / 文件路径</span><input v-model="draft.host" autocapitalize="none" placeholder="db.internal" /></label>
          <label><span>端口</span><input v-model.number="draft.port" type="number" min="1" max="65535" /></label>
          <label><span>数据库</span><input v-model="draft.database" autocapitalize="none" placeholder="可选" /></label>
          <label><span>用户名</span><input v-model="draft.username" autocapitalize="none" autocomplete="username" /></label>
          <label><span>密码</span><input v-model="draft.password" type="password" autocomplete="new-password" placeholder="留空则不修改" /></label>
          <label class="wide"><span>备注</span><textarea v-model="draft.note" rows="2" placeholder="用途、负责人或维护窗口"></textarea></label>
          <label><span>环境</span><select v-model="preferenceEnvironment">
            <option value="development">开发</option><option value="staging">预发</option><option value="production">生产</option>
          </select></label>
          <label><span>标识色</span><input v-model="draft.color" type="color" /></label>
        </div>

        <div class="toggle-grid">
          <label><input v-model="draft.ssl" type="checkbox" /><span>启用 TLS / SSL</span></label>
          <label><input v-model="draft.readOnly" type="checkbox" /><span>只读连接</span></label>
          <label><input v-model="draft.isProduction" type="checkbox" /><span>生产保护</span></label>
        </div>

        <button class="advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
          <span>超时、代理与证书</span><b>{{ advancedOpen ? "−" : "＋" }}</b>
        </button>
        <div v-if="advancedOpen" class="advanced-editor">
          <div class="editor-grid">
            <label><span>连接超时 / 秒</span><input v-model.number="draft.connectTimeoutSecs" type="number" min="1" max="300" /></label>
            <label><span>查询超时 / 秒</span><input v-model.number="draft.queryTimeoutSecs" type="number" min="1" max="3600" /></label>
            <label><span>空闲超时 / 秒</span><input v-model.number="draft.idleTimeoutSecs" type="number" min="1" max="3600" /></label>
            <label><span>保活间隔 / 秒</span><input v-model.number="draft.keepaliveIntervalSecs" type="number" min="1" max="3600" /></label>
            <label class="wide"><span>CA 证书（服务器路径）</span><input v-model="draft.caCertPath" autocapitalize="none" placeholder="/etc/dbx/ca.pem" /></label>
            <label><span>客户端证书路径</span><input v-model="draft.clientCertPath" autocapitalize="none" /></label>
            <label><span>客户端密钥路径</span><input v-model="draft.clientKeyPath" type="password" autocapitalize="none" /></label>
          </div>
          <label class="proxy-switch"><input v-model="draft.proxyEnabled" type="checkbox" /><span>通过代理连接</span></label>
          <div v-if="draft.proxyEnabled" class="editor-grid proxy-fields">
            <label><span>代理类型</span><select v-model="draft.proxyType"><option value="socks5">SOCKS5</option><option value="http">HTTP</option></select></label>
            <label><span>代理端口</span><input v-model.number="draft.proxyPort" type="number" min="1" max="65535" /></label>
            <label class="wide"><span>代理主机</span><input v-model="draft.proxyHost" autocapitalize="none" /></label>
            <label><span>代理用户名</span><input v-model="draft.proxyUsername" autocomplete="username" /></label>
            <label><span>代理密码</span><input v-model="draft.proxyPassword" type="password" autocomplete="new-password" /></label>
          </div>
        </div>

        <p v-if="editorMessage" class="editor-message" :data-tone="editorTone">{{ editorMessage }}</p>
        <footer>
          <button class="test-action" :disabled="testing || saving" type="button" @click="testConnection">
            {{ testing ? "测试中…" : "测试连接" }}
          </button>
          <button class="save-action" :disabled="saving || testing" type="submit">{{ saving ? "保存中…" : "保存连接" }}</button>
        </footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.connection-manager { margin-top: 16px; }
.catalog-tools { display: grid; grid-template-columns: 1fr auto; gap: 8px; }
.catalog-search { display: flex; min-width: 0; align-items: center; gap: 9px; border: 1px solid var(--line); background: var(--panel); padding: 0 12px; }
.catalog-search span { color: var(--acid); font-size: 18px; }
.catalog-search input { width: 100%; min-width: 0; height: 46px; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 11px; }
.add-connection { display: flex; align-items: center; gap: 5px; border: 0; background: var(--acid); padding: 0 13px; color: #11150d; font-size: 10px; font-weight: 750; }
.add-connection b { font-size: 17px; }
.filter-strip { display: flex; overflow-x: auto; gap: 6px; margin: 10px 0 18px; scrollbar-width: none; }
.filter-strip button { flex: none; border: 1px solid var(--line); background: transparent; padding: 8px 10px; color: var(--muted); font-size: 9px; }
.filter-strip button.active { border-color: rgba(199,255,61,.5); background: rgba(199,255,61,.09); color: var(--acid); }
.connection-group { margin-top: 18px; }
.group-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 7px; color: var(--muted); font-size: 9px; letter-spacing: .11em; }
.group-heading small { color: var(--faint); font-size: 8px; }
.managed-connection { border: 1px solid var(--line); background: var(--panel); }
.managed-connection + .managed-connection { margin-top: 7px; }
.connection-main { display: grid; width: 100%; grid-template-columns: 4px 1fr auto; align-items: center; gap: 13px; border: 0; background: transparent; padding: 14px 13px; text-align: left; }
.connection-main > i { width: 4px; height: 38px; }
.connection-main span { min-width: 0; }
.connection-main small, .connection-main strong, .connection-main p { display: block; }
.connection-main small { color: var(--muted); font-size: 8px; text-transform: uppercase; }
.connection-main small em { margin-left: 7px; border: 1px solid var(--line); padding: 2px 4px; color: var(--acid); font-style: normal; }
.connection-main small em[data-env="production"] { color: var(--danger); }
.connection-main small em[data-env="staging"] { color: var(--amber); }
.connection-main strong { overflow: hidden; margin-top: 5px; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.connection-main p { overflow: hidden; margin: 5px 0 0; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.connection-main > b { color: var(--acid); font-size: 20px; }
.connection-actions { display: flex; justify-content: flex-end; border-top: 1px solid var(--line); padding: 5px 7px; }
.connection-actions button { min-width: 47px; border: 0; background: transparent; padding: 7px 9px; color: var(--muted); font-size: 9px; }
.connection-actions button:first-child { margin-right: auto; color: var(--acid); font-size: 16px; }
.connection-actions .danger-text { color: #ff918d; }
.catalog-empty { min-height: 180px; border: 1px dashed var(--line); padding: 28px 20px; }
.catalog-empty > b { color: var(--acid); font-size: 28px; }
.catalog-empty strong { display: block; margin-top: 18px; font-size: 14px; }
.catalog-empty p { color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 11px; line-height: 1.6; }
.sheet-backdrop { position: fixed; z-index: 20; inset: 0; display: flex; align-items: flex-end; justify-content: center; background: rgba(0,0,0,.72); backdrop-filter: blur(6px); }
.editor-sheet { overflow-y: auto; width: min(720px, 100%); max-height: 93dvh; border: 1px solid var(--line); border-bottom: 0; background: #121512; padding: 10px 18px calc(20px + var(--safe-bottom)); box-shadow: 0 -30px 90px rgba(0,0,0,.65); }
.sheet-handle { width: 42px; height: 3px; margin: 0 auto 15px; background: #4e554f; }
.editor-sheet header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 19px; }
.editor-sheet header small { color: var(--acid); font-size: 8px; letter-spacing: .13em; }
.editor-sheet h4 { margin: 6px 0 0; font-size: 21px; }
.editor-sheet header button { border: 0; background: transparent; color: var(--muted); font-size: 27px; line-height: 1; }
.editor-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 10px; }
.editor-grid label, .settings-card label { min-width: 0; }
.editor-grid label.wide { grid-column: 1 / -1; }
.editor-grid label > span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 9px; }
.editor-grid input, .editor-grid select, .editor-grid textarea { width: 100%; min-height: 43px; border: 1px solid var(--line); border-radius: 0; outline: 0; background: #080a09; padding: 0 10px; color: var(--ink); font-size: 11px; }
.editor-grid textarea { padding-top: 10px; resize: vertical; }
.editor-grid input:focus, .editor-grid select:focus, .editor-grid textarea:focus { border-color: rgba(199,255,61,.55); }
.editor-grid input[type="color"] { padding: 5px; }
.toggle-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 5px; margin: 16px 0; }
.toggle-grid label, .proxy-switch { display: flex; min-height: 42px; align-items: center; gap: 7px; border: 1px solid var(--line); padding: 8px; color: var(--muted); font-size: 9px; }
.toggle-grid input, .proxy-switch input { accent-color: var(--acid); }
.advanced-toggle { display: flex; width: 100%; justify-content: space-between; border: 1px solid var(--line); background: rgba(255,255,255,.025); padding: 13px; color: var(--ink); font-size: 10px; text-align: left; }
.advanced-toggle b { color: var(--acid); }
.advanced-editor { border: 1px solid var(--line); border-top: 0; padding: 14px 12px; }
.proxy-switch { margin: 15px 0 10px; }
.proxy-fields { padding-top: 2px; }
.editor-message { margin: 13px 0 0; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 10px; line-height: 1.5; }
.editor-message[data-tone="success"] { color: var(--acid); }
.editor-message[data-tone="danger"] { color: #ff918d; }
.editor-sheet footer { display: grid; grid-template-columns: 1fr 1.35fr; gap: 8px; margin-top: 17px; }
.editor-sheet footer button { min-height: 49px; font-weight: 720; font-size: 10px; }
.test-action { border: 1px solid var(--acid); background: transparent; color: var(--acid); }
.save-action { border: 0; background: var(--acid); color: #11150d; }
.editor-sheet footer button:disabled { opacity: .55; }
</style>
