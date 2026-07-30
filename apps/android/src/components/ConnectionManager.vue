<script setup lang="ts">
import { computed, nextTick, reactive, ref } from "vue";
import {
  ApiError,
  apiDeleteJson,
  apiGetJson,
  apiPostJson,
  type MobileConnectionDraft,
  type MobileConnectionEditor,
  type MobileConnectionSummary,
} from "../lib/mobileApi";
import { databaseCapability, mobileDatabaseCapabilities } from "../lib/databaseCapabilities";
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
const editorTab = ref<"general" | "ssl" | "ssh" | "http">("general");
const saving = ref(false);
const testing = ref(false);
const editorMessage = ref("");
const editorTone = ref<"success" | "danger" | "neutral">("neutral");
const groupDraft = ref("未分组");
const preferenceRevision = ref(0);
const sslCertificateError = computed(() =>
  editorTone.value === "danger" && editorMessage.value.startsWith("SSL 证书验证失败"),
);

const databaseTypes = mobileDatabaseCapabilities.filter((item) =>
  ["postgres", "mysql", "sqlserver"].includes(item.value),
);

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
    sslMode: "verify-full",
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
    proxyType: "http",
    proxyHost: "",
    proxyPort: 8080,
    proxyUsername: "",
    proxyPassword: "",
    sshEnabled: false,
    sshHost: "",
    sshPort: 22,
    sshUsername: "",
    sshHostKeyFingerprint: "",
    sshPassword: "",
    sshAuthMethod: "password",
    sshPrivateKey: "",
    sshPrivateKeyPassphrase: "",
    connectionString: "",
    oracleConnectionType: "service_name",
    sysdba: false,
    urlParams: "",
    initScript: "",
    visibleDatabases: [],
    visibleSchemas: {},
    productionDatabases: [],
    redisConnectionMode: "standalone",
    redisSentinelMaster: "",
    redisSentinelNodes: "",
    redisSentinelUsername: "",
    redisSentinelPassword: "",
    redisSentinelTls: false,
    redisClusterNodes: "",
    jdbcDriverClass: "",
    jdbcDriverPaths: [],
    driverProfile: "",
    driverLabel: "",
  };
}

const draft = reactive<MobileConnectionDraft>(blankDraft());
const currentCapability = computed(() => databaseCapability(draft.dbType));
const visibleDatabasesText = computed({
  get: () => draft.visibleDatabases.join("\n"),
  set: (value: string) => {
    draft.visibleDatabases = splitLines(value);
  },
});
const productionDatabasesText = computed({
  get: () => draft.productionDatabases.join("\n"),
  set: (value: string) => {
    draft.productionDatabases = splitLines(value);
  },
});
const jdbcDriverPathsText = computed({
  get: () => draft.jdbcDriverPaths.join("\n"),
  set: (value: string) => {
    draft.jdbcDriverPaths = splitLines(value);
  },
});
const visibleSchemasText = computed({
  get: () => Object.entries(draft.visibleSchemas).map(([database, schemas]) => `${database}: ${schemas.join(", ")}`).join("\n"),
  set: (value: string) => {
    draft.visibleSchemas = Object.fromEntries(
      value
        .split(/\r?\n/)
        .map((line) => line.split(/:(.*)/s))
        .map(([database, schemas]) => [database?.trim(), (schemas ?? "").split(",").map((item) => item.trim()).filter(Boolean)])
        .filter(([database, schemas]) => Boolean(database) && schemas.length > 0),
    );
  },
});
const preferenceEnvironment = ref<ConnectionEnvironment>("development");

function splitLines(value: string): string[] {
  return value.split(/[\r\n,]+/).map((item) => item.trim()).filter(Boolean);
}

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
  editorTab.value = "general";
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
      sslMode: value.sslMode ?? "verify-full",
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
      sshEnabled: value.sshEnabled ?? false,
      sshHost: value.sshHost ?? "",
      sshPort: value.sshPort ?? 22,
      sshUsername: value.sshUsername ?? "",
      sshHostKeyFingerprint: value.sshHostKeyFingerprint ?? "",
      sshPassword: "",
      sshAuthMethod: value.sshAuthMethod ?? "password",
      sshPrivateKey: "",
      sshPrivateKeyPassphrase: "",
      connectionString: value.connectionString,
      oracleConnectionType: value.oracleConnectionType,
      sysdba: value.sysdba,
      urlParams: value.urlParams,
      initScript: value.initScript,
      visibleDatabases: value.visibleDatabases,
      visibleSchemas: value.visibleSchemas,
      productionDatabases: value.productionDatabases,
      redisConnectionMode: value.redisConnectionMode,
      redisSentinelMaster: value.redisSentinelMaster,
      redisSentinelNodes: value.redisSentinelNodes,
      redisSentinelUsername: value.redisSentinelUsername,
      redisSentinelPassword: "",
      redisSentinelTls: value.redisSentinelTls,
      redisClusterNodes: value.redisClusterNodes,
      jdbcDriverClass: value.jdbcDriverClass,
      jdbcDriverPaths: value.jdbcDriverPaths,
      driverProfile: value.driverProfile,
      driverLabel: value.driverLabel,
    });
    const local = preference(connection);
    groupDraft.value = local.group;
    preferenceEnvironment.value = local.environment;
    const preserved: string[] = [];
    if (value.hasPassword || value.hasProxyPassword || value.hasSshPassword || value.hasRedisSentinelPassword) preserved.push("密码");
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
  if (error instanceof ApiError && error.status === 401) {
    emit("authExpired");
    editorOpen.value = false;
    return;
  }
  editorTone.value = "danger";
  editorMessage.value = error instanceof Error ? error.message : "操作失败";
  if (editorMessage.value.startsWith("SSL 证书验证失败")) openSslSettings();
}

function openSslSettings() {
  editorTab.value = "ssl";
  nextTick(() => {
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(".editor-sheet")?.scrollTo({ top: 0, behavior: "smooth" });
    });
  });
}

function validateDraft(): boolean {
  if (!draft.name.trim() || (!draft.host.trim() && !draft.connectionString.trim()) || !draft.port) {
    editorTone.value = "danger";
    editorMessage.value = "请填写连接名称、主机（或连接串）和端口。";
    return false;
  }
  if (draft.proxyEnabled && (!draft.proxyHost.trim() || !draft.proxyPort)) {
    editorTone.value = "danger";
    editorMessage.value = "启用代理后必须填写代理主机和端口。";
    return false;
  }
  if (draft.sshEnabled && (!draft.sshHost.trim() || !draft.sshPort || !draft.sshUsername.trim())) {
    editorTone.value = "danger";
    editorMessage.value = "启用 SSH 后必须填写 SSH 主机、端口和用户名。";
    editorTab.value = "ssh";
    return false;
  }
  return true;
}

async function testConnection() {
  if (!validateDraft()) return;
  testing.value = true;
  editorMessage.value = "正在从手机直接测试连接…";
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
  if (!window.confirm(`删除连接“${connection.name}”？加密保存在本机的凭据也会一并移除。`)) return;
  try {
    await apiDeleteJson(props.baseUrl, `/api/mobile/connections/${encodeURIComponent(connection.id)}`, props.token);
    removeConnectionPreference(props.serverId, connection.id);
    emit("changed");
  } catch (error) {
    handleError(error);
  }
}

function changeDatabaseType() {
  const selected = databaseCapability(draft.dbType);
  draft.port = selected.port;
  draft.ssl = !selected.local;
  draft.database = null;
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

        <nav class="connection-tabs" aria-label="连接方式">
          <button :class="{ active: editorTab === 'general' }" type="button" @click="editorTab = 'general'">常规</button>
          <button :class="{ active: editorTab === 'ssl' }" type="button" @click="editorTab = 'ssl'"><i :data-on="draft.ssl"></i>SSL</button>
          <button :class="{ active: editorTab === 'ssh' }" type="button" @click="editorTab = 'ssh'"><i :data-on="draft.sshEnabled"></i>SSH</button>
          <button :class="{ active: editorTab === 'http' }" type="button" @click="editorTab = 'http'"><i :data-on="draft.proxyEnabled"></i>HTTP</button>
        </nav>

        <section v-show="editorTab === 'general'" class="connection-tab-panel">
        <div class="editor-grid">
          <label class="wide"><span>名称</span><input v-model="draft.name" placeholder="订单库" /></label>
          <label><span>类型</span><select v-model="draft.dbType" @change="changeDatabaseType">
            <option v-for="item in databaseTypes" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select></label>
          <label><span>分组</span><input v-model="groupDraft" placeholder="核心业务" /></label>
          <label class="wide"><span>主机 / 文件路径</span><input v-model="draft.host" autocapitalize="none" :placeholder="currentCapability.local ? '/data/database.db' : 'db.internal'" /></label>
          <label><span>端口</span><input v-model.number="draft.port" type="number" min="1" max="65535" /></label>
          <label><span>数据库</span><input v-model="draft.database" autocapitalize="none" placeholder="可选，例如 login_system" /></label>
          <label><span>用户名</span><input v-model="draft.username" autocapitalize="none" autocomplete="username" /></label>
          <label><span>密码</span><input v-model="draft.password" type="password" autocomplete="new-password" placeholder="留空则不修改" /></label>
          <label class="wide"><span>备注</span><textarea v-model="draft.note" rows="2" placeholder="用途、负责人或维护窗口"></textarea></label>
          <label><span>环境</span><select v-model="preferenceEnvironment">
            <option value="development">开发</option><option value="staging">预发</option><option value="production">生产</option>
          </select></label>
          <label><span>标识色</span><input v-model="draft.color" type="color" /></label>
        </div>

        <p v-if="currentCapability.browse === 'unsupported'" class="capability-notice">
          此类型可在手机端创建、测试和编辑；当前没有专用数据浏览器，不会调用关系型 Schema API。
        </p>

        <div v-if="draft.dbType === 'oracle'" class="special-editor">
          <div class="editor-grid">
            <label><span>Oracle 连接方式</span><select v-model="draft.oracleConnectionType">
              <option value="service_name">Service Name</option><option value="sid">SID</option><option value="tns">TNS</option>
            </select></label>
            <label class="inline-check"><input v-model="draft.sysdba" type="checkbox" /><span>SYSDBA</span></label>
            <label v-if="draft.oracleConnectionType === 'tns'" class="wide"><span>TNS 连接串</span><textarea v-model="draft.connectionString" rows="3" placeholder="留空保留已保存的 TNS 配置"></textarea></label>
          </div>
        </div>

        <div v-if="draft.dbType === 'mongodb'" class="special-editor">
          <div class="editor-grid">
            <label class="wide"><span>MongoDB URI</span><input v-model="draft.connectionString" autocapitalize="none" autocomplete="off" placeholder="mongodb://…；留空保留已保存 URI" /></label>
          </div>
        </div>

        <div v-if="draft.dbType === 'redis'" class="special-editor">
          <div class="editor-grid">
            <label><span>Redis 模式</span><select v-model="draft.redisConnectionMode">
              <option value="standalone">Standalone</option><option value="sentinel">Sentinel</option><option value="cluster">Cluster</option>
            </select></label>
            <template v-if="draft.redisConnectionMode === 'sentinel'">
              <label><span>Sentinel Master</span><input v-model="draft.redisSentinelMaster" /></label>
              <label class="wide"><span>Sentinel 节点</span><textarea v-model="draft.redisSentinelNodes" rows="2" placeholder="host1:26379, host2:26379"></textarea></label>
              <label><span>Sentinel 用户名</span><input v-model="draft.redisSentinelUsername" /></label>
              <label><span>Sentinel 密码</span><input v-model="draft.redisSentinelPassword" type="password" placeholder="留空则不修改" /></label>
              <label class="inline-check"><input v-model="draft.redisSentinelTls" type="checkbox" /><span>Sentinel TLS</span></label>
            </template>
            <label v-else-if="draft.redisConnectionMode === 'cluster'" class="wide"><span>Cluster 节点</span><textarea v-model="draft.redisClusterNodes" rows="2" placeholder="host1:6379, host2:6379"></textarea></label>
          </div>
        </div>

        <div class="toggle-grid">
          <label><input v-model="draft.readOnly" type="checkbox" /><span>只读连接</span></label>
          <label><input v-model="draft.isProduction" type="checkbox" /><span>生产保护</span></label>
        </div>

        <button class="advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
          <span>高级参数、驱动与网络</span><b>{{ advancedOpen ? "−" : "＋" }}</b>
        </button>
        <div v-if="advancedOpen" class="advanced-editor">
          <div class="editor-grid">
            <label><span>连接超时 / 秒</span><input v-model.number="draft.connectTimeoutSecs" type="number" min="1" max="300" /></label>
            <label><span>查询超时 / 秒</span><input v-model.number="draft.queryTimeoutSecs" type="number" min="1" max="3600" /></label>
            <label><span>空闲超时 / 秒</span><input v-model.number="draft.idleTimeoutSecs" type="number" min="1" max="3600" /></label>
            <label><span>保活间隔 / 秒</span><input v-model.number="draft.keepaliveIntervalSecs" type="number" min="1" max="3600" /></label>
            <label class="wide"><span>URL 参数</span><input v-model="draft.urlParams" autocapitalize="none" placeholder="key=value&…" /></label>
            <label class="wide"><span>初始化脚本</span><textarea v-model="draft.initScript" rows="4" placeholder="连接建立后执行；请按 Android 驱动能力配置"></textarea></label>
            <label><span>可见数据库（每行一个）</span><textarea v-model="visibleDatabasesText" rows="4"></textarea></label>
            <label><span>生产数据库（每行一个）</span><textarea v-model="productionDatabasesText" rows="4"></textarea></label>
            <label class="wide"><span>可见 Schema（database: schema1, schema2）</span><textarea v-model="visibleSchemasText" rows="4"></textarea></label>
            <label><span>驱动 Profile</span><input v-model="draft.driverProfile" autocapitalize="none" /></label>
            <label><span>驱动显示名称</span><input v-model="draft.driverLabel" /></label>
            <label class="wide"><span>JDBC 驱动类</span><input v-model="draft.jdbcDriverClass" autocapitalize="none" placeholder="com.example.Driver" /></label>
            <label class="wide"><span>JDBC 驱动路径（每行一个，本机路径）</span><textarea v-model="jdbcDriverPathsText" rows="3"></textarea></label>
            <label v-if="draft.dbType === 'jdbc'" class="wide"><span>JDBC 连接串</span><input v-model="draft.connectionString" autocapitalize="none" autocomplete="off" placeholder="jdbc:…；留空保留已保存连接串" /></label>
          </div>
        </div>
        </section>

        <section v-show="editorTab === 'ssl'" class="connection-tab-panel transport-panel">
          <div class="transport-heading">
            <div><small>ENCRYPTED TRANSPORT</small><h5>SSL / TLS</h5></div>
            <label class="rail-switch"><input v-model="draft.ssl" type="checkbox" /><span></span></label>
          </div>
          <p>对数据库连接进行加密。验证模式要求服务器证书由 Android 信任的 CA 签发。</p>
          <div class="editor-grid">
            <label class="wide"><span>证书验证模式</span><select v-model="draft.sslMode" :disabled="!draft.ssl">
              <option value="required">仅加密（允许自签名证书）</option>
              <option value="verify-ca">验证证书颁发机构</option>
              <option value="verify-full">验证证书和主机名</option>
            </select></label>
          </div>
          <p v-if="draft.ssl && draft.sslMode === 'required'" class="security-note">仅加密模式不会验证服务器身份，适合本地自签名环境；生产库建议使用完整验证。</p>
        </section>

        <section v-show="editorTab === 'ssh'" class="connection-tab-panel transport-panel">
          <div class="transport-heading">
            <div><small>LOCAL PORT FORWARD</small><h5>SSH 隧道</h5></div>
            <label class="rail-switch"><input v-model="draft.sshEnabled" type="checkbox" /><span></span></label>
          </div>
          <p>手机先登录跳板机，再由跳板机访问“常规”页中的数据库地址。</p>
          <div class="editor-grid">
            <label class="wide"><span>SSH 主机</span><input v-model="draft.sshHost" :disabled="!draft.sshEnabled" autocapitalize="none" placeholder="bastion.example.com" /></label>
            <label><span>SSH 端口</span><input v-model.number="draft.sshPort" :disabled="!draft.sshEnabled" type="number" min="1" max="65535" /></label>
            <label><span>SSH 用户名</span><input v-model="draft.sshUsername" :disabled="!draft.sshEnabled" autocapitalize="none" autocomplete="username" /></label>
            <label class="wide"><span>主机密钥 SHA256 指纹</span><input v-model="draft.sshHostKeyFingerprint" :disabled="!draft.sshEnabled" autocapitalize="none" placeholder="可选，例如 SHA256:AbCd…" /></label>
            <label class="wide"><span>认证方式</span><select v-model="draft.sshAuthMethod" :disabled="!draft.sshEnabled">
              <option value="password">密码</option><option value="private-key">私钥</option>
            </select></label>
            <label v-if="draft.sshAuthMethod === 'password'" class="wide"><span>SSH 密码</span><input v-model="draft.sshPassword" :disabled="!draft.sshEnabled" type="password" autocomplete="new-password" placeholder="留空则沿用已保存密码" /></label>
            <template v-else>
              <label class="wide"><span>OpenSSH / PEM 私钥</span><textarea v-model="draft.sshPrivateKey" :disabled="!draft.sshEnabled" rows="7" autocapitalize="none" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----&#10;留空则沿用已保存私钥"></textarea></label>
              <label class="wide"><span>私钥口令</span><input v-model="draft.sshPrivateKeyPassphrase" :disabled="!draft.sshEnabled" type="password" autocomplete="new-password" placeholder="可选；留空则沿用" /></label>
            </template>
          </div>
          <p class="security-note">SSH 密码和私钥通过 Android Keystore 加密保存。生产环境请填写主机密钥指纹，防止跳板机被冒充。</p>
        </section>

        <section v-show="editorTab === 'http'" class="connection-tab-panel transport-panel">
          <div class="transport-heading">
            <div><small>HTTP CONNECT</small><h5>HTTP 代理</h5></div>
            <label class="rail-switch"><input v-model="draft.proxyEnabled" type="checkbox" @change="draft.proxyType = 'http'" /><span></span></label>
          </div>
          <p>通过支持 CONNECT 方法的 HTTP 代理建立数据库 TCP 通道；也可作为 SSH 跳板机的上游代理。</p>
          <div class="editor-grid">
            <label class="wide"><span>代理主机</span><input v-model="draft.proxyHost" :disabled="!draft.proxyEnabled" autocapitalize="none" placeholder="proxy.example.com" /></label>
            <label><span>代理端口</span><input v-model.number="draft.proxyPort" :disabled="!draft.proxyEnabled" type="number" min="1" max="65535" /></label>
            <label><span>代理用户名</span><input v-model="draft.proxyUsername" :disabled="!draft.proxyEnabled" autocomplete="username" placeholder="可选" /></label>
            <label class="wide"><span>代理密码</span><input v-model="draft.proxyPassword" :disabled="!draft.proxyEnabled" type="password" autocomplete="new-password" placeholder="可选；留空则沿用已保存密码" /></label>
          </div>
          <p class="security-note">HTTP 代理本身不等于加密；若代理链路不可信，请同时在 SSL 页启用数据库 TLS。</p>
        </section>

        <p v-if="editorMessage" class="editor-message" :data-tone="editorTone">{{ editorMessage }}</p>
        <button v-if="sslCertificateError" class="error-action" type="button" @click="openSslSettings">打开 SSL 设置</button>
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
.connection-tabs { position: sticky; z-index: 10; top: 0; display: grid; grid-template-columns: repeat(4, 1fr); margin: 2px 0 18px; border-bottom: 1px solid var(--line); background: #121512; padding-top: 8px; box-shadow: 0 8px 14px rgba(0,0,0,.24); }
.connection-tabs button { position: relative; display: flex; min-height: 40px; align-items: center; justify-content: center; gap: 6px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--muted); font-size: 10px; letter-spacing: .04em; }
.connection-tabs button.active { border-bottom-color: var(--acid); color: var(--ink); }
.connection-tabs i { width: 6px; height: 6px; border-radius: 50%; background: var(--faint); box-shadow: 0 0 0 2px rgba(255,255,255,.035); }
.connection-tabs i[data-on="true"] { background: var(--acid); box-shadow: 0 0 8px rgba(199,255,61,.65); }
.connection-tab-panel { animation: tab-in .16s ease-out; }
@keyframes tab-in { from { opacity: .35; transform: translateY(3px); } to { opacity: 1; transform: none; } }
.editor-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 10px; }
.editor-grid label, .settings-card label { min-width: 0; cursor: text; }
.editor-grid label.wide { grid-column: 1 / -1; }
.editor-grid label > span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 9px; }
.editor-grid input, .editor-grid select, .editor-grid textarea { width: 100%; min-height: 48px; border: 1px solid var(--line); border-radius: 0; outline: 0; background: #080a09; padding: 0 12px; color: var(--ink); font-size: 12px; touch-action: manipulation; }
.editor-grid textarea { padding-top: 10px; resize: vertical; }
.editor-grid input:focus, .editor-grid select:focus, .editor-grid textarea:focus { border-color: rgba(199,255,61,.55); }
.editor-grid input[type="color"] { padding: 5px; }
.toggle-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 5px; margin: 16px 0; }
.toggle-grid label, .proxy-switch { display: flex; min-height: 42px; align-items: center; gap: 7px; border: 1px solid var(--line); padding: 8px; color: var(--muted); font-size: 9px; }
.toggle-grid input, .proxy-switch input { accent-color: var(--acid); }
.advanced-toggle { display: flex; width: 100%; justify-content: space-between; border: 1px solid var(--line); background: rgba(255,255,255,.025); padding: 13px; color: var(--ink); font-size: 10px; text-align: left; }
.advanced-toggle b { color: var(--acid); }
.advanced-editor { border: 1px solid var(--line); border-top: 0; padding: 14px 12px; }
.transport-panel { min-height: 360px; border: 1px solid var(--line); background: linear-gradient(145deg, rgba(199,255,61,.035), transparent 45%); padding: 15px 13px; }
.transport-panel > p { margin: 0 0 18px; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 10px; line-height: 1.65; }
.transport-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.transport-heading small { color: var(--acid); font-size: 7px; letter-spacing: .17em; }
.transport-heading h5 { margin: 5px 0 0; font-size: 16px; }
.rail-switch { position: relative; }
.rail-switch input { position: absolute; opacity: 0; pointer-events: none; }
.rail-switch span { display: block; width: 43px; height: 23px; border: 1px solid var(--line); background: #080a09; padding: 3px; }
.rail-switch span::after { display: block; width: 15px; height: 15px; background: var(--faint); content: ""; transition: transform .16s ease, background .16s ease; }
.rail-switch input:checked + span { border-color: rgba(199,255,61,.55); }
.rail-switch input:checked + span::after { transform: translateX(18px); background: var(--acid); box-shadow: 0 0 10px rgba(199,255,61,.45); }
.transport-panel .security-note { margin: 14px 0 0; border-left: 2px solid var(--amber); background: rgba(255,184,76,.035); padding: 9px 10px; color: var(--muted); font-size: 9px; }
.special-editor { margin-top: 12px; border: 1px solid var(--line); padding: 12px; background: rgba(199,255,61,.025); }
.capability-notice, .tunnel-note { border-left: 2px solid var(--amber); padding: 9px 11px; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 10px; line-height: 1.55; }
.tunnel-note { margin: 13px 0; border-left-color: var(--acid); }
.inline-check { display: flex; align-items: center; gap: 8px; padding-top: 20px; }
.inline-check input { width: auto; min-height: auto; }
.inline-check span { margin: 0 !important; }
.editor-message { margin: 13px 0 0; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 10px; line-height: 1.5; }
.editor-message[data-tone="success"] { color: var(--acid); }
.editor-message[data-tone="danger"] { color: #ff918d; }
.error-action { width: 100%; min-height: 42px; margin-top: 8px; border: 1px solid #ff918d; background: rgba(255,145,141,.05); color: #ffb0ad; font-size: 10px; }
.editor-sheet footer { display: grid; grid-template-columns: 1fr 1.35fr; gap: 8px; margin-top: 17px; }
.editor-sheet footer button { min-height: 49px; font-weight: 720; font-size: 10px; }
.test-action { border: 1px solid var(--acid); background: transparent; color: var(--acid); }
.save-action { border: 0; background: var(--acid); color: #11150d; }
.editor-sheet footer button:disabled { opacity: .55; }
</style>
