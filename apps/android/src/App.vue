<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import ConnectionManager from "./components/ConnectionManager.vue";
import HistoryLibrary from "./components/HistoryLibrary.vue";
import MetadataBrowser from "./components/MetadataBrowser.vue";
import MongoDocumentBrowser from "./components/MongoDocumentBrowser.vue";
import QueryWorkbench from "./components/QueryWorkbench.vue";
import RedisWorkbench from "./components/RedisWorkbench.vue";
import { apiFetch, withApiTimeout, type MobileConnectionSummary, type MobileLoginResponse, type MobileQueryDraft } from "./lib/mobileApi";
import { deleteServerProfile, loadServerProfile, loadServerProfiles, saveServerProfile, setActiveServerProfile, type ServerProfile } from "./lib/serverProfile";
import { loadSecureSession, removeSecureSession, saveSecureSession } from "./lib/secureSession";

type CheckState = "idle" | "checking" | "ready" | "auth" | "error";
type MobileSection = "connections" | "query" | "history" | "settings";

interface AuthCheckResponse {
  authenticated: boolean;
  required: boolean;
  setup_required: boolean;
}

const savedProfile = ref<ServerProfile | null>(loadServerProfile());
const serverProfiles = ref<ServerProfile[]>(loadServerProfiles());
const serverName = ref(savedProfile.value?.name ?? "我的 DBX");
const serverUrl = ref(savedProfile.value?.baseUrl ?? "");
const checkState = ref<CheckState>("idle");
const statusMessage = ref("");
const activeSection = ref<MobileSection>("connections");
const password = ref("");
const sessionToken = ref<string | null>(null);
const loginPending = ref(false);
const logoutPending = ref(false);
const requireDeviceUnlock = ref(true);
const setupRequired = ref(false);
const connections = ref<MobileConnectionSummary[]>([]);
const connectionsLoading = ref(false);
const connectionsError = ref("");
const queryDraft = ref<MobileQueryDraft | null>(null);
const browsingConnection = ref<MobileConnectionSummary | null>(null);
let queryDraftNonce = 0;

const statusLabel = computed(() => {
  if (checkState.value === "checking") return "正在握手";
  if (checkState.value === "ready") return "服务在线";
  if (checkState.value === "auth") return "等待登录";
  if (checkState.value === "error") return "连接失败";
  return savedProfile.value ? "尚未检查" : "未配置";
});

const statusTone = computed(() => {
  if (checkState.value === "ready") return "success";
  if (checkState.value === "auth") return "warning";
  if (checkState.value === "error") return "danger";
  return "neutral";
});

async function checkServer(profile: ServerProfile) {
  checkState.value = "checking";
  statusMessage.value = "";

  try {
    const auth = await withApiTimeout(async (signal) => {
      const response = await apiFetch(profile.baseUrl, "/api/auth/check", sessionToken.value, {
        method: "GET",
        credentials: "include",
        headers: { Accept: "application/json" },
        signal,
      });
      if (!response.ok) throw new Error(`服务器返回 ${response.status}`);
      return response.json() as Promise<AuthCheckResponse>;
    }, profile.network.requestTimeoutMs);
    setupRequired.value = auth.setup_required;
    checkState.value = auth.setup_required || (auth.required && !auth.authenticated) ? "auth" : "ready";
    statusMessage.value = auth.setup_required ? "请先在 DBX Web 中设置管理密码" : auth.required && !auth.authenticated ? "服务器可访问，请继续登录" : "连接检查通过";
    if (checkState.value === "ready") await loadConnections(profile);
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "无法访问服务器，请检查地址、HTTPS 证书与网络";
  }
}

async function saveAndCheck() {
  statusMessage.value = "";
  try {
    const profile = saveServerProfile({
      id: savedProfile.value?.id,
      name: serverName.value,
      baseUrl: serverUrl.value,
      network: savedProfile.value?.network,
    });
    savedProfile.value = profile;
    serverProfiles.value = loadServerProfiles();
    serverName.value = profile.name;
    serverUrl.value = profile.baseUrl;
    await checkServer(profile);
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "服务器配置无效";
  }
}

async function login() {
  const profile = savedProfile.value;
  if (!profile || !password.value) return;
  loginPending.value = true;
  statusMessage.value = "";

  try {
    const body = await withApiTimeout(async (signal) => {
      const response = await apiFetch(profile.baseUrl, "/api/auth/mobile-login", null, {
        method: "POST",
        credentials: "omit",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ password: password.value }),
        signal,
      });
      if (response.status === 401) throw new Error("密码不正确");
      if (response.status === 429) {
        const errorBody = (await response.json().catch(() => null)) as { error?: string } | null;
        throw new Error(errorBody?.error ?? "登录尝试过多，请稍后再试");
      }
      if (response.status === 409) throw new Error("请先在 DBX Web 中设置管理密码");
      if (!response.ok) throw new Error(`登录失败（${response.status}）`);
      return response.json() as Promise<MobileLoginResponse>;
    }, profile.network.requestTimeoutMs);

    sessionToken.value = body.token;
    if (body.token) {
      await saveSecureSession(profile.id, { token: body.token, expiresAt: body.expiresAt }, requireDeviceUnlock.value);
    }
    password.value = "";
    checkState.value = "ready";
    statusMessage.value = body.token ? "登录成功；令牌已由 Android Keystore 加密保存" : "登录成功";
    await loadConnections(profile);
  } catch (error) {
    checkState.value = "auth";
    statusMessage.value = error instanceof Error ? error.message : "登录失败";
  } finally {
    loginPending.value = false;
  }
}

async function loadConnections(profile: ServerProfile) {
  connectionsLoading.value = true;
  connectionsError.value = "";
  try {
    const response = await withApiTimeout(
      (signal) =>
        apiFetch(profile.baseUrl, "/api/mobile/connections", sessionToken.value, {
          headers: { Accept: "application/json" },
          signal,
        }),
      profile.network.requestTimeoutMs,
    );
    if (response.status === 401) {
      sessionToken.value = null;
      checkState.value = "auth";
      throw new Error("登录已失效，请重新登录");
    }
    if (!response.ok) throw new Error(`连接列表加载失败（${response.status}）`);
    connections.value = (await response.json()) as MobileConnectionSummary[];
  } catch (error) {
    connections.value = [];
    connectionsError.value = error instanceof Error ? error.message : "连接列表加载失败";
  } finally {
    connectionsLoading.value = false;
  }
}

function handleMetadataAuthExpired() {
  sessionToken.value = null;
  connections.value = [];
  checkState.value = "auth";
  statusMessage.value = "登录已失效，请重新登录";
}

function openConnectionBrowser(connection: MobileConnectionSummary) {
  browsingConnection.value = connection;
}

async function revokeCurrentSession() {
  const profile = savedProfile.value;
  const token = sessionToken.value;
  if (!profile || !token) return;
  const response = await withApiTimeout(
    (signal) =>
      apiFetch(profile.baseUrl, "/api/auth/logout", token, {
        method: "POST",
        signal,
      }),
    profile.network.requestTimeoutMs,
  );
  // An unauthorized token is already unusable, so local removal is safe.
  if (!response.ok && response.status !== 401) {
    throw new Error(`服务器未确认注销（${response.status}）`);
  }
}

async function logout() {
  logoutPending.value = true;
  try {
    await revokeCurrentSession();
    if (savedProfile.value) await removeSecureSession(savedProfile.value.id);
    sessionToken.value = null;
    connections.value = [];
    checkState.value = "auth";
    statusMessage.value = "服务端已撤销当前移动会话";
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof Error ? `${error.message}，令牌已保留，可重试` : "注销失败，令牌已保留";
  } finally {
    logoutPending.value = false;
  }
}

function prepareNewServer() {
  savedProfile.value = null;
  serverName.value = "我的 DBX";
  serverUrl.value = "";
  checkState.value = "idle";
  statusMessage.value = "";
  sessionToken.value = null;
  connections.value = [];
}

async function removeCurrentProfile() {
  const profile = savedProfile.value;
  if (!profile) return;
  logoutPending.value = true;
  try {
    await revokeCurrentSession();
    await removeSecureSession(profile.id);
    savedProfile.value = deleteServerProfile(profile.id);
    serverProfiles.value = loadServerProfiles();
    sessionToken.value = null;
    connections.value = [];
    if (savedProfile.value) {
      serverName.value = savedProfile.value.name;
      serverUrl.value = savedProfile.value.baseUrl;
      await activateProfile(savedProfile.value.id);
    } else {
      prepareNewServer();
    }
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof Error ? `${error.message}，未切换服务器，可重试` : "注销失败，未切换服务器";
  } finally {
    logoutPending.value = false;
  }
}

async function activateProfile(id: string) {
  const profile = setActiveServerProfile(id);
  savedProfile.value = profile;
  serverProfiles.value = loadServerProfiles();
  serverName.value = profile.name;
  serverUrl.value = profile.baseUrl;
  connections.value = [];
  sessionToken.value = null;
  checkState.value = "checking";
  statusMessage.value = "正在解锁此服务器的登录令牌…";
  try {
    const session = await loadSecureSession(profile.id);
    sessionToken.value = session?.token ?? null;
  } catch {
    statusMessage.value = "未能解锁保存的令牌，请重新登录";
  }
  await checkServer(profile);
}

async function updateServerSettings() {
  if (!savedProfile.value) return;
  try {
    const profile = saveServerProfile({
      ...savedProfile.value,
      name: serverName.value,
      baseUrl: serverUrl.value,
    });
    savedProfile.value = profile;
    serverProfiles.value = loadServerProfiles();
    statusMessage.value = "服务器设置已保存，正在验证网络策略…";
    await checkServer(profile);
  } catch (error) {
    statusMessage.value = error instanceof Error ? error.message : "设置保存失败";
    checkState.value = "error";
  }
}

function sectionLabel(section: MobileSection) {
  return { connections: "连接", query: "查询", history: "历史", settings: "设置" }[section];
}

function openQueryDraft(draft: Omit<MobileQueryDraft, "nonce">) {
  queryDraft.value = { ...draft, nonce: ++queryDraftNonce };
  activeSection.value = "query";
}

onMounted(async () => {
  if (savedProfile.value) await activateProfile(savedProfile.value.id);
});
</script>

<template>
  <div class="app-shell">
    <div class="ambient-grid" aria-hidden="true"></div>

    <header class="topbar">
      <div class="brand-lockup">
        <div class="brand-mark" aria-hidden="true"><span></span><span></span><span></span></div>
        <div>
          <p class="eyebrow">DATABASE FIELD TERMINAL</p>
          <h1>DBX<span>/M</span></h1>
        </div>
      </div>
      <button v-if="savedProfile" class="status-chip" :data-tone="statusTone" type="button" @click="checkServer(savedProfile)"><span class="status-dot"></span>{{ statusLabel }}</button>
    </header>

    <main v-if="!savedProfile" class="setup-view">
      <section class="setup-intro">
        <p class="sequence">01 — LINK A SERVER</p>
        <h2>把你的数据库<br />装进口袋。</h2>
        <p class="intro-copy">DBX Mobile 通过你的 DBX Web 节点安全访问数据库。驱动、密码和长任务仍留在服务器。</p>
      </section>

      <form class="server-card" @submit.prevent="saveAndCheck">
        <div class="card-index">SERVER PROFILE / 001</div>
        <label><span>节点名称</span><input v-model="serverName" autocomplete="organization" placeholder="生产环境 DBX" /></label>
        <label>
          <span>服务器地址</span>
          <input v-model="serverUrl" inputmode="url" autocapitalize="none" autocomplete="url" placeholder="https://dbx.example.com" />
        </label>
        <p class="field-hint">生产环境建议使用 HTTPS。不要把数据库账号或密码写进地址。</p>
        <button class="primary-action" :disabled="checkState === 'checking'" type="submit">
          <span>{{ checkState === "checking" ? "正在连接" : "保存并检查" }}</span
          ><b aria-hidden="true">↗</b>
        </button>
        <p v-if="statusMessage" class="form-message" :data-tone="statusTone">{{ statusMessage }}</p>
      </form>

      <section v-if="serverProfiles.length" class="saved-server-list">
        <div class="group-heading">
          <span>已保存服务器</span><small>{{ serverProfiles.length }} NODES</small>
        </div>
        <button v-for="profile in serverProfiles" :key="profile.id" type="button" @click="activateProfile(profile.id)">
          <span
            ><b>{{ profile.name }}</b
            ><small>{{ profile.baseUrl }}</small></span
          ><i>→</i>
        </button>
      </section>

      <footer class="setup-footer"><span>ANDROID MVP</span><span>SECURE RELAY MODE</span></footer>
    </main>

    <main v-else class="workspace-view">
      <section class="node-hero">
        <div>
          <p class="sequence">ACTIVE RELAY</p>
          <select v-if="serverProfiles.length > 1" class="server-switcher" :value="savedProfile.id" aria-label="快速切换服务器" @change="activateProfile(($event.target as HTMLSelectElement).value)">
            <option v-for="profile in serverProfiles" :key="profile.id" :value="profile.id">{{ profile.name }}</option>
          </select>
          <h2 v-else>{{ savedProfile.name }}</h2>
          <p class="server-address">{{ savedProfile.baseUrl }}</p>
        </div>
        <div class="signal-glyph" :data-tone="statusTone" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
      </section>

      <section class="status-panel">
        <div>
          <span class="panel-kicker">LINK STATUS</span>
          <strong>{{ statusLabel }}</strong>
          <p>{{ statusMessage || "点击右上角状态可重新检查服务器。" }}</p>
        </div>
        <button type="button" @click="checkServer(savedProfile)">重新检查</button>
      </section>

      <section v-if="checkState === 'auth'" class="login-card">
        <div class="card-index">MOBILE AUTH / BEARER</div>
        <template v-if="setupRequired">
          <h3>服务器尚未完成安全设置</h3>
          <p>请先用浏览器打开 DBX Web 设置管理密码，然后返回这里重新检查。</p>
          <button class="primary-action" type="button" @click="checkServer(savedProfile)"><span>重新检查</span><b aria-hidden="true">↻</b></button>
        </template>
        <form v-else @submit.prevent="login">
          <h3>验证管理密码</h3>
          <p>密码只用于本次登录请求，不会保存在手机配置中。</p>
          <label>
            <span>DBX 管理密码</span>
            <input v-model="password" type="password" autocomplete="current-password" placeholder="••••••••" />
          </label>
          <button class="primary-action" :disabled="loginPending || !password" type="submit">
            <span>{{ loginPending ? "正在验证" : "登录服务器" }}</span
            ><b aria-hidden="true">→</b>
          </button>
        </form>
      </section>

      <section v-else class="section-stage">
        <div class="section-heading">
          <span>02 / {{ activeSection.toUpperCase() }}</span>
          <h3>{{ sectionLabel(activeSection) }}</h3>
        </div>

        <div v-if="activeSection === 'connections'">
          <div v-if="connectionsLoading" class="empty-module compact">
            <div class="module-icon">⌁</div>
            <h4>正在读取连接目录</h4>
          </div>
          <div v-else-if="connectionsError" class="empty-module compact">
            <div class="module-icon">!</div>
            <h4>{{ connectionsError }}</h4>
            <button class="inline-action" type="button" @click="loadConnections(savedProfile)">重试</button>
          </div>
          <template v-else>
            <button v-if="browsingConnection" class="catalog-back" type="button" @click="browsingConnection = null">← 返回连接管理</button>
            <MetadataBrowser v-if="browsingConnection && !['mongodb', 'redis'].includes(browsingConnection.dbType)" :base-url="savedProfile.baseUrl" :connections="[browsingConnection]" :token="sessionToken" @auth-expired="handleMetadataAuthExpired" @open-query="openQueryDraft" />
            <MongoDocumentBrowser v-else-if="browsingConnection?.dbType === 'mongodb'" :base-url="savedProfile.baseUrl" :connection="browsingConnection" :token="sessionToken" @auth-expired="handleMetadataAuthExpired" />
            <RedisWorkbench v-else-if="browsingConnection?.dbType === 'redis'" :base-url="savedProfile.baseUrl" :connection="browsingConnection" :token="sessionToken" @auth-expired="handleMetadataAuthExpired" />
            <ConnectionManager v-else :base-url="savedProfile.baseUrl" :connections="connections" :server-id="savedProfile.id" :token="sessionToken" @auth-expired="handleMetadataAuthExpired" @browse="openConnectionBrowser" @changed="loadConnections(savedProfile)" />
          </template>
        </div>
        <QueryWorkbench v-else-if="activeSection === 'query'" :base-url="savedProfile.baseUrl" :connections="connections" :draft="queryDraft" :token="sessionToken" @auth-expired="handleMetadataAuthExpired" @draft-consumed="queryDraft = null" />
        <HistoryLibrary v-else-if="activeSection === 'history'" :base-url="savedProfile.baseUrl" :connections="connections" :token="sessionToken" @auth-expired="handleMetadataAuthExpired" @open-query="openQueryDraft" />
        <div v-else class="settings-stack">
          <form class="settings-card" @submit.prevent="updateServerSettings">
            <div class="card-index">SERVER / NETWORK</div>
            <label><span>节点名称</span><input v-model="serverName" /></label>
            <label><span>服务器地址</span><input v-model="serverUrl" inputmode="url" autocapitalize="none" /></label>
            <label>
              <span>请求超时</span>
              <select v-model.number="savedProfile.network.requestTimeoutMs">
                <option :value="5000">5 秒</option>
                <option :value="8000">8 秒</option>
                <option :value="15000">15 秒</option>
                <option :value="30000">30 秒</option>
                <option :value="60000">60 秒</option>
              </select>
            </label>
            <label>
              <span>服务器代理</span>
              <input v-model="savedProfile.network.proxyUrl" autocapitalize="none" spellcheck="false" placeholder="http://proxy.internal:8080" />
              <small class="network-field-hint">支持 HTTP 与 SOCKS5；仅影响此服务器的移动端请求。</small>
            </label>
            <label>
              <span>证书 SHA-256 指纹 / SPKI Pin</span>
              <input v-model="savedProfile.network.certificatePin" autocapitalize="characters" spellcheck="false" placeholder="AA:BB:… 或 sha256/BASE64" />
              <small class="network-field-hint">HTTPS 握手后校验叶证书；证书不匹配时始终拒绝连接。</small>
            </label>
            <label class="secure-toggle danger-toggle">
              <input v-model="savedProfile.network.allowInvalidCertificate" type="checkbox" />
              <span><b>允许无效 HTTPS 证书</b><small>跳过系统 CA 与主机名验证，仅用于受控内网；建议同时固定证书指纹。</small></span>
            </label>
            <label class="secure-toggle">
              <input v-model="requireDeviceUnlock" type="checkbox" />
              <span><b>系统凭据 / 生物识别解锁</b><small>下次登录保存令牌时要求设备验证</small></span>
            </label>
            <button class="primary-action" type="submit"><span>保存并检查网络</span><b>↗</b></button>
          </form>
          <div class="settings-list">
            <button type="button" @click="prepareNewServer">
              <span><b>添加服务器</b><small>保留当前节点，创建另一套服务器配置</small></span
              ><i>＋</i>
            </button>
            <button v-if="sessionToken" :disabled="logoutPending" type="button" @click="logout">
              <span
                ><b>{{ logoutPending ? "正在撤销会话" : "退出登录" }}</b
                ><small>服务端确认后才会清除本地令牌</small></span
              ><i>→</i>
            </button>
            <button class="danger-setting" :disabled="logoutPending" type="button" @click="removeCurrentProfile">
              <span><b>删除此服务器</b><small>先撤销当前会话，再移除节点与安全令牌</small></span
              ><i>×</i>
            </button>
          </div>
        </div>
      </section>
    </main>

    <nav v-if="savedProfile && checkState !== 'auth'" class="bottom-nav" aria-label="主导航">
      <button v-for="section in ['connections', 'query', 'history', 'settings'] as MobileSection[]" :key="section" :class="{ active: activeSection === section }" type="button" @click="activeSection = section">
        <span class="nav-symbol" aria-hidden="true">{{ { connections: "◫", query: "›_", history: "↺", settings: "⌘" }[section] }}</span>
        <span>{{ sectionLabel(section) }}</span>
      </button>
    </nav>
  </div>
</template>
