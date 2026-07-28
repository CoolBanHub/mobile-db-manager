<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import MetadataBrowser from "./components/MetadataBrowser.vue";
import { apiFetch, withApiTimeout, type MobileConnectionSummary, type MobileLoginResponse } from "./lib/mobileApi";
import { clearServerProfile, loadServerProfile, saveServerProfile, type ServerProfile } from "./lib/serverProfile";

type CheckState = "idle" | "checking" | "ready" | "auth" | "error";
type MobileSection = "connections" | "query" | "history" | "settings";

interface AuthCheckResponse {
  authenticated: boolean;
  required: boolean;
  setup_required: boolean;
}

const savedProfile = ref<ServerProfile | null>(loadServerProfile());
const serverName = ref(savedProfile.value?.name ?? "我的 DBX");
const serverUrl = ref(savedProfile.value?.baseUrl ?? "");
const checkState = ref<CheckState>("idle");
const statusMessage = ref("");
const activeSection = ref<MobileSection>("connections");
const password = ref("");
const sessionToken = ref<string | null>(null);
const loginPending = ref(false);
const logoutPending = ref(false);
const setupRequired = ref(false);
const connections = ref<MobileConnectionSummary[]>([]);
const connectionsLoading = ref(false);
const connectionsError = ref("");

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
    });
    setupRequired.value = auth.setup_required;
    checkState.value = auth.setup_required || (auth.required && !auth.authenticated) ? "auth" : "ready";
    statusMessage.value = auth.setup_required
      ? "请先在 DBX Web 中设置管理密码"
      : auth.required && !auth.authenticated
        ? "服务器可访问，请继续登录"
        : "连接检查通过";
    if (checkState.value === "ready") await loadConnections(profile);
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "无法访问服务器，请检查地址、HTTPS 证书与网络";
  }
}

async function saveAndCheck() {
  statusMessage.value = "";
  try {
    const profile = saveServerProfile({ name: serverName.value, baseUrl: serverUrl.value });
    savedProfile.value = profile;
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
    });

    sessionToken.value = body.token;
    password.value = "";
    checkState.value = "ready";
    statusMessage.value = "登录成功；令牌仅保留在本次 App 运行期间";
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
    const response = await apiFetch(profile.baseUrl, "/api/mobile/connections", sessionToken.value, {
      headers: { Accept: "application/json" },
    });
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

async function revokeCurrentSession() {
  const profile = savedProfile.value;
  const token = sessionToken.value;
  if (!profile || !token) return;
  const response = await withApiTimeout((signal) =>
    apiFetch(profile.baseUrl, "/api/auth/logout", token, {
      method: "POST",
      signal,
    }),
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

function clearProfileLocally() {
  clearServerProfile();
  savedProfile.value = null;
  serverName.value = "我的 DBX";
  serverUrl.value = "";
  checkState.value = "idle";
  statusMessage.value = "";
  sessionToken.value = null;
  connections.value = [];
}

async function resetProfile() {
  logoutPending.value = true;
  try {
    await revokeCurrentSession();
    clearProfileLocally();
  } catch (error) {
    checkState.value = "error";
    statusMessage.value =
      error instanceof Error ? `${error.message}，未切换服务器，可重试` : "注销失败，未切换服务器";
  } finally {
    logoutPending.value = false;
  }
}

function sectionLabel(section: MobileSection) {
  return { connections: "连接", query: "查询", history: "历史", settings: "设置" }[section];
}

onMounted(() => {
  if (savedProfile.value) void checkServer(savedProfile.value);
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
      <button v-if="savedProfile" class="status-chip" :data-tone="statusTone" type="button" @click="checkServer(savedProfile)">
        <span class="status-dot"></span>{{ statusLabel }}
      </button>
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
          <span>{{ checkState === "checking" ? "正在连接" : "保存并检查" }}</span><b aria-hidden="true">↗</b>
        </button>
        <p v-if="statusMessage" class="form-message" :data-tone="statusTone">{{ statusMessage }}</p>
      </form>

      <footer class="setup-footer"><span>ANDROID MVP</span><span>SECURE RELAY MODE</span></footer>
    </main>

    <main v-else class="workspace-view">
      <section class="node-hero">
        <div>
          <p class="sequence">ACTIVE RELAY</p>
          <h2>{{ savedProfile.name }}</h2>
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
          <button class="primary-action" type="button" @click="checkServer(savedProfile)">
            <span>重新检查</span><b aria-hidden="true">↻</b>
          </button>
        </template>
        <form v-else @submit.prevent="login">
          <h3>验证管理密码</h3>
          <p>密码只用于本次登录请求，不会保存在手机配置中。</p>
          <label>
            <span>DBX 管理密码</span>
            <input v-model="password" type="password" autocomplete="current-password" placeholder="••••••••" />
          </label>
          <button class="primary-action" :disabled="loginPending || !password" type="submit">
            <span>{{ loginPending ? "正在验证" : "登录服务器" }}</span><b aria-hidden="true">→</b>
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
            <div class="module-icon">⌁</div><h4>正在读取连接目录</h4>
          </div>
          <div v-else-if="connectionsError" class="empty-module compact">
            <div class="module-icon">!</div><h4>{{ connectionsError }}</h4>
            <button class="inline-action" type="button" @click="loadConnections(savedProfile)">重试</button>
          </div>
          <MetadataBrowser
            v-else
            :base-url="savedProfile.baseUrl"
            :connections="connections"
            :token="sessionToken"
            @auth-expired="handleMetadataAuthExpired"
          />
        </div>
        <div v-else-if="activeSection === 'query'" class="empty-module">
          <div class="module-icon">›_</div><h4>移动查询工作台</h4>
          <p>全屏 SQL 编辑、执行控制、危险语句确认和结果分页将在下一阶段接入。</p>
        </div>
        <div v-else-if="activeSection === 'history'" class="empty-module">
          <div class="module-icon">↺</div><h4>最近活动</h4><p>查询历史和收藏 SQL 将与 DBX Server 保持一致。</p>
        </div>
        <div v-else class="settings-list">
          <button v-if="sessionToken" :disabled="logoutPending" type="button" @click="logout">
            <span><b>{{ logoutPending ? "正在撤销会话" : "退出登录" }}</b><small>服务端确认后才会清除本地令牌</small></span><i>→</i>
          </button>
          <button :disabled="logoutPending" type="button" @click="resetProfile">
            <span><b>更换服务器</b><small>先撤销当前会话，再清除节点配置</small></span><i>→</i>
          </button>
        </div>
      </section>
    </main>

    <nav v-if="savedProfile && checkState !== 'auth'" class="bottom-nav" aria-label="主导航">
      <button
        v-for="section in (['connections', 'query', 'history', 'settings'] as MobileSection[])"
        :key="section"
        :class="{ active: activeSection === section }"
        type="button"
        @click="activeSection = section"
      >
        <span class="nav-symbol" aria-hidden="true">{{ { connections: "◫", query: "›_", history: "↺", settings: "⌘" }[section] }}</span>
        <span>{{ sectionLabel(section) }}</span>
      </button>
    </nav>
  </div>
</template>
