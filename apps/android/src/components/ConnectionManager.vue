<script setup lang="ts">
import { computed, nextTick, reactive, ref } from "vue";
import { deleteDirectConnection, getDirectConnection, saveDirectConnection, testDirectConnection } from "../lib/directDatabase";
import type { MobileConnectionDraft, MobileConnectionSummary } from "../lib/mobileTypes";
import { databaseCapability, mobileDatabaseCapabilities } from "../lib/databaseCapabilities";
import { getConnectionPreference, parseConnectionTags, removeConnectionPreference, saveConnectionPreference, type ConnectionEnvironment } from "../lib/connectionPreferences";
import postgresIcon from "../../../desktop/public/icons/database/postgres.svg";
import redisIcon from "../../../desktop/public/icons/database/redis.svg";
import mongodbIcon from "../../../desktop/public/icons/database/mongodb.svg";
import sqlserverIcon from "../../../desktop/public/icons/database/sqlserver.svg";
import etcdIcon from "../../../desktop/public/icons/database/etcd.svg";

const props = defineProps<{ connections: MobileConnectionSummary[] }>();

const emit = defineEmits<{
  changed: [];
  browse: [connection: MobileConnectionSummary];
}>();

const search = ref("");
const environment = ref<"all" | ConnectionEnvironment>("all");
const favoritesOnly = ref(false);
const collapsedGroups = ref(new Set<string>());
const editorOpen = ref(false);
const advancedOpen = ref(false);
const editorTab = ref<"general" | "ssl" | "ssh" | "http">("general");
const saving = ref(false);
const testing = ref(false);
const editorMessage = ref("");
const editorTone = ref<"success" | "danger" | "neutral">("neutral");
const groupDraft = ref("未分组");
const tagDraft = ref("");
const preferenceRevision = ref(0);
const hasStoredConnectionString = ref(false);
const sslCertificateError = computed(() => editorTone.value === "danger" && editorMessage.value.startsWith("SSL 证书验证失败"));

const databaseTypes = mobileDatabaseCapabilities;

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
    color: "#c7ff3d",
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
const preferenceEnvironment = ref<ConnectionEnvironment>("development");

const filteredConnections = computed(() => {
  // preferenceRevision 是 localStorage 的响应式桥；偏好写入后递增即可触发重新筛选。
  void preferenceRevision.value;
  const needle = search.value.trim().toLocaleLowerCase();
  return props.connections.filter((connection) => {
    const preference = getConnectionPreference(connection.id, connection.isProduction);
    if (favoritesOnly.value && !preference.favorite) return false;
    if (environment.value !== "all" && preference.environment !== environment.value) return false;
    return !needle || [connection.name, connection.host, connection.database, connection.dbType, connection.note, preference.group, ...preference.tags].filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle));
  });
});

const groupedConnections = computed(() => {
  const groups = new Map<string, MobileConnectionSummary[]>();
  for (const connection of filteredConnections.value) {
    const type = connection.dbType.toLocaleLowerCase();
    groups.set(type, [...(groups.get(type) ?? []), connection]);
  }
  const order = ["postgres", "mysql", "redis", "mongodb", "sqlserver", "etcd"];
  return [...groups.entries()]
    .map(([type, items]) => ({ type, label: databaseCapability(type).label, items }))
    .sort((left, right) => {
      const leftIndex = order.indexOf(left.type);
      const rightIndex = order.indexOf(right.type);
      if (leftIndex === -1 && rightIndex === -1) return left.label.localeCompare(right.label);
      if (leftIndex === -1) return 1;
      if (rightIndex === -1) return -1;
      return leftIndex - rightIndex;
    });
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

function resetEditor() {
  Object.assign(draft, blankDraft());
  hasStoredConnectionString.value = false;
  groupDraft.value = "未分组";
  tagDraft.value = "";
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
      color: value.color,
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
    tagDraft.value = local.tags.join(", ");
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
  editorTab.value = "ssl";
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
    const saved = await saveDirectConnection(draft);
    saveConnectionPreference(saved.id, {
      group: groupDraft.value.trim() || "未分组",
      favorite: draft.id ? preference(saved).favorite : false,
      environment: preferenceEnvironment.value,
      tags: parseConnectionTags(tagDraft.value),
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
    await deleteDirectConnection(connection.id);
    removeConnectionPreference(connection.id);
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
  if (advancedOpen.value) {
    advancedOpen.value = false;
    return true;
  }
  if (editorOpen.value) {
    editorOpen.value = false;
    return true;
  }
  return false;
}

defineExpose({ handleBack });
</script>

<template>
  <div class="connection-manager manage-mode">
    <div class="catalog-tools">
      <label class="catalog-search">
        <span aria-hidden="true">⌕</span>
        <input v-model="search" type="search" placeholder="搜索连接或标签" />
      </label>
      <button class="filter-button" type="button" aria-label="显示收藏连接" :class="{ active: favoritesOnly }" @click="favoritesOnly = !favoritesOnly">≡</button>
    </div>

    <div class="filter-strip">
      <button :class="{ active: environment === 'all' }" type="button" @click="environment = 'all'">全部</button>
      <button :class="{ active: favoritesOnly }" type="button" @click="favoritesOnly = !favoritesOnly">收藏</button>
      <button :class="{ active: environment === 'development' }" type="button" @click="environment = 'development'">开发</button>
      <button :class="{ active: environment === 'staging' }" type="button" @click="environment = 'staging'">预发</button>
      <button :class="{ active: environment === 'production' }" type="button" @click="environment = 'production'">生产</button>
    </div>

    <div v-if="groupedConnections.length === 0" class="browser-state catalog-empty">
      <b>∅</b><strong>没有匹配的连接</strong>
      <p>调整搜索或筛选条件，也可以直接创建新的数据库连接。</p>
    </div>

    <section v-for="group in groupedConnections" :key="group.type" class="connection-group">
      <button class="group-heading" type="button" :aria-expanded="!collapsedGroups.has(group.type)" @click="toggleGroup(group.type)">
        <span><b :class="{ collapsed: collapsedGroups.has(group.type) }">⌄</b>{{ group.label }}</span>
        <small>{{ group.items.length }}</small>
      </button>
      <article v-for="connection in collapsedGroups.has(group.type) ? [] : group.items" :key="connection.id" class="managed-connection">
        <div class="connection-main" role="button" tabindex="0" @click="emit('browse', connection)" @keydown.enter="emit('browse', connection)">
          <span class="database-mark" :data-type="connection.dbType" :style="{ '--connection-color': connection.color || 'var(--acid)' }">
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
          <button class="card-favorite" :class="{ favorite: preference(connection).favorite }" :aria-label="preference(connection).favorite ? '取消收藏' : '收藏'" type="button" @click.stop="toggleFavorite(connection)">{{ preference(connection).favorite ? "★" : "☆" }}</button>
        </div>
        <div class="connection-actions">
          <span>{{ connection.note || "点击卡片浏览数据" }}</span>
          <button type="button" @click="openEdit(connection)">编辑</button>
          <button class="danger-text" type="button" @click="deleteConnection(connection)">删除</button>
        </div>
      </article>
    </section>

    <button class="add-connection" type="button" aria-label="新建连接" @click="openCreate">＋</button>

    <Teleport to="body">
      <div v-if="editorOpen" class="sheet-backdrop" @click.self="editorOpen = false">
        <form class="editor-sheet" @submit.prevent="saveConnection">
          <div class="sheet-handle"></div>
          <header>
            <div>
              <small>CONNECTION EDITOR</small>
              <h4>{{ draft.id ? "编辑连接" : "创建连接" }}</h4>
            </div>
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
              <label
                ><span>类型</span
                ><select v-model="draft.dbType" @change="changeDatabaseType">
                  <option v-for="item in databaseTypes" :key="item.value" :value="item.value">{{ item.label }}</option>
                </select></label
              >
              <label><span>分组</span><input v-model="groupDraft" placeholder="核心业务" /></label>
              <label class="wide"><span>自定义标签</span><input v-model="tagDraft" placeholder="核心、只读、临时（使用逗号分隔）" /></label>
              <label class="wide"><span>主机</span><input v-model="draft.host" autocapitalize="none" placeholder="db.internal" /></label>
              <p class="wide remote-hint">
                <strong>跨网络连接</strong>
                请填写公网域名、组网 VPN 地址，或在 SSH 页配置公网跳板机；手机流量下无法访问 192.168.x.x、10.x.x.x 等局域网地址。
              </p>
              <label><span>端口</span><input v-model.number="draft.port" type="number" min="1" max="65535" /></label>
              <label><span>数据库</span><input v-model="draft.database" :disabled="draft.dbType === 'etcd'" autocapitalize="none" :placeholder="draft.dbType === 'etcd' ? 'etcd 不使用数据库编号' : '可选，例如 login_system'" /></label>
              <label><span>用户名</span><input v-model="draft.username" autocapitalize="none" autocomplete="username" /></label>
              <label><span>密码</span><input v-model="draft.password" type="password" autocomplete="new-password" placeholder="留空则不修改" /></label>
              <label class="wide"><span>备注</span><textarea v-model="draft.note" rows="2" placeholder="用途、负责人或维护窗口"></textarea></label>
              <label
                ><span>环境</span
                ><select v-model="preferenceEnvironment">
                  <option value="development">开发</option>
                  <option value="staging">预发</option>
                  <option value="production">生产</option>
                </select></label
              >
              <label><span>标识色</span><input v-model="draft.color" type="color" /></label>
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
              <label><input v-model="draft.isProduction" type="checkbox" /><span>生产保护</span></label>
            </div>

            <button class="advanced-toggle" type="button" @click="advancedOpen = !advancedOpen">
              <span>超时与保活参数</span><b>{{ advancedOpen ? "−" : "＋" }}</b>
            </button>
            <div v-if="advancedOpen" class="advanced-editor">
              <div class="editor-grid">
                <label><span>连接超时 / 秒</span><input v-model.number="draft.connectTimeoutSecs" type="number" min="1" max="300" /></label>
                <label><span>查询超时 / 秒</span><input v-model.number="draft.queryTimeoutSecs" type="number" min="1" max="3600" /></label>
                <label><span>保活间隔 / 秒</span><input v-model.number="draft.keepaliveIntervalSecs" type="number" min="1" max="3600" /></label>
              </div>
            </div>
          </section>

          <section v-show="editorTab === 'ssl'" class="connection-tab-panel transport-panel">
            <div class="transport-heading">
              <div>
                <small>ENCRYPTED TRANSPORT</small>
                <h5>SSL / TLS</h5>
              </div>
              <label class="rail-switch"><input v-model="draft.ssl" type="checkbox" /><span></span></label>
            </div>
            <p>新连接默认关闭 SSL。跨公网直连时建议开启；验证模式要求服务器证书由 Android 信任的 CA 签发。</p>
            <div class="editor-grid">
              <label class="wide"
                ><span>证书验证模式</span
                ><select v-model="draft.sslMode" :disabled="!draft.ssl">
                  <option value="required">仅加密（允许自签名证书）</option>
                  <option value="verify-ca">验证证书颁发机构</option>
                  <option value="verify-full">验证证书和主机名</option>
                </select></label
              >
            </div>
            <p v-if="draft.ssl && draft.sslMode === 'required'" class="security-note">仅加密模式不会验证服务器身份，适合本地自签名环境；生产库建议使用完整验证。</p>
          </section>

          <section v-show="editorTab === 'ssh'" class="connection-tab-panel transport-panel">
            <div class="transport-heading">
              <div>
                <small>LOCAL PORT FORWARD</small>
                <h5>SSH 隧道</h5>
              </div>
              <label class="rail-switch"><input v-model="draft.sshEnabled" type="checkbox" /><span></span></label>
            </div>
            <p>手机先登录跳板机，再由跳板机访问“常规”页中的数据库地址。</p>
            <div class="editor-grid">
              <label class="wide"><span>SSH 主机</span><input v-model="draft.sshHost" :disabled="!draft.sshEnabled" autocapitalize="none" placeholder="bastion.example.com" /></label>
              <label><span>SSH 端口</span><input v-model.number="draft.sshPort" :disabled="!draft.sshEnabled" type="number" min="1" max="65535" /></label>
              <label><span>SSH 用户名</span><input v-model="draft.sshUsername" :disabled="!draft.sshEnabled" autocapitalize="none" autocomplete="username" /></label>
              <label class="wide"><span>主机密钥 SHA256 指纹</span><input v-model="draft.sshHostKeyFingerprint" :disabled="!draft.sshEnabled" autocapitalize="none" placeholder="可选，例如 SHA256:AbCd…" /></label>
              <label class="wide"
                ><span>认证方式</span
                ><select v-model="draft.sshAuthMethod" :disabled="!draft.sshEnabled">
                  <option value="password">密码</option>
                  <option value="private-key">私钥</option>
                </select></label
              >
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
              <div>
                <small>HTTP CONNECT</small>
                <h5>HTTP 代理</h5>
              </div>
              <label class="rail-switch"><input v-model="draft.proxyEnabled" type="checkbox" /><span></span></label>
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
  grid-template-columns: 1fr 40px;
  gap: 7px;
}
.catalog-search {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--field);
  padding: 0 12px;
  box-shadow: none;
}
.catalog-search span {
  color: var(--muted);
  font-size: 18px;
}
.catalog-search input {
  width: 100%;
  min-width: 0;
  height: 38px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 11px;
}
.filter-button {
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--field);
  color: var(--muted);
  font-size: 20px;
  transform: rotate(180deg);
}
.filter-button.active {
  border-color: var(--acid);
  color: var(--acid);
}
.add-connection {
  position: fixed;
  z-index: 8;
  right: 20px;
  bottom: calc(76px + var(--safe-bottom));
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: linear-gradient(145deg, #2487ff, #0868ee);
  color: #fff;
  box-shadow: 0 10px 26px rgba(22, 119, 255, 0.38);
  font-size: 28px;
  font-weight: 400;
}
.filter-strip {
  display: flex;
  overflow-x: auto;
  gap: 7px;
  margin: 10px 0 14px;
  scrollbar-width: none;
}
.filter-strip button {
  flex: 1 0 54px;
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
  border-radius: 6px;
  background: color-mix(in srgb, var(--panel) 94%, var(--acid));
  box-shadow: none;
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
  color: var(--connection-color);
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
  border: 1px solid color-mix(in srgb, var(--success) 40%, var(--line));
  border-radius: 4px;
  background: color-mix(in srgb, var(--success) 8%, transparent);
  padding: 2px 5px;
  color: var(--success);
  font-size: 8px;
  font-style: normal;
}
.connection-title em[data-env="production"] {
  border-color: color-mix(in srgb, var(--danger) 45%, var(--line));
  color: var(--danger);
}
.connection-title em[data-env="staging"] {
  border-color: color-mix(in srgb, var(--amber) 45%, var(--line));
  color: var(--amber);
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
  font-size: 20px;
}
.card-favorite.favorite {
  color: #f6b800;
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
  padding: 10px 18px calc(20px + var(--safe-bottom));
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
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 27px;
  line-height: 1;
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
.editor-grid input[type="color"] {
  padding: 5px;
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
.advanced-toggle b {
  color: var(--acid);
}
.advanced-editor {
  border: 1px solid var(--line);
  border-top: 0;
  padding: 14px 12px;
}
.transport-panel {
  min-height: 360px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--acid) 5%, var(--panel)), var(--panel) 45%);
  padding: 15px 13px;
}
.transport-panel > p {
  margin: 0 0 18px;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 10px;
  line-height: 1.65;
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
  color: #ff918d;
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
.editor-sheet footer {
  display: grid;
  grid-template-columns: 1fr 1.35fr;
  gap: 8px;
  margin-top: 17px;
}
.editor-sheet footer button {
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
.editor-sheet footer button:disabled {
  opacity: 0.55;
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
