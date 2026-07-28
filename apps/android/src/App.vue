<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
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
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 8_000);

  try {
    const response = await fetch(`${profile.baseUrl}/api/auth/check`, {
      method: "GET",
      credentials: "include",
      headers: { Accept: "application/json" },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`服务器返回 ${response.status}`);
    const auth = (await response.json()) as AuthCheckResponse;
    checkState.value = auth.setup_required || (auth.required && !auth.authenticated) ? "auth" : "ready";
    statusMessage.value = auth.setup_required ? "服务器需要先设置管理密码" : auth.required && !auth.authenticated ? "服务器可访问，请继续登录" : "连接检查通过";
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof DOMException && error.name === "AbortError" ? "连接超时，请检查地址与网络" : "无法访问服务器；移动认证与跨域支持将在下一阶段接入";
  } finally {
    window.clearTimeout(timeout);
  }
}

async function saveAndCheck() {
  statusMessage.value = "";
  try {
    const profile = saveServerProfile({
      name: serverName.value,
      baseUrl: serverUrl.value,
    });
    savedProfile.value = profile;
    serverName.value = profile.name;
    serverUrl.value = profile.baseUrl;
    await checkServer(profile);
  } catch (error) {
    checkState.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "服务器配置无效";
  }
}

function resetProfile() {
  clearServerProfile();
  savedProfile.value = null;
  serverName.value = "我的 DBX";
  serverUrl.value = "";
  checkState.value = "idle";
  statusMessage.value = "";
}

function sectionLabel(section: MobileSection) {
  return {
    connections: "连接",
    query: "查询",
    history: "历史",
    settings: "设置",
  }[section];
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
        <span class="status-dot"></span>
        {{ statusLabel }}
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
        <label>
          <span>节点名称</span>
          <input v-model="serverName" autocomplete="organization" placeholder="生产环境 DBX" />
        </label>
        <label>
          <span>服务器地址</span>
          <input v-model="serverUrl" inputmode="url" autocapitalize="none" autocomplete="url" placeholder="https://dbx.example.com" />
        </label>
        <p class="field-hint">生产环境建议使用 HTTPS。不要把数据库账号或密码写进地址。</p>
        <button class="primary-action" :disabled="checkState === 'checking'" type="submit">
          <span>{{ checkState === "checking" ? "正在连接" : "保存并检查" }}</span>
          <b aria-hidden="true">↗</b>
        </button>
        <p v-if="statusMessage" class="form-message" :data-tone="statusTone">{{ statusMessage }}</p>
      </form>

      <footer class="setup-footer">
        <span>ANDROID MVP</span>
        <span>SECURE RELAY MODE</span>
      </footer>
    </main>

    <main v-else class="workspace-view">
      <section class="node-hero">
        <div>
          <p class="sequence">ACTIVE RELAY</p>
          <h2>{{ savedProfile.name }}</h2>
          <p class="server-address">{{ savedProfile.baseUrl }}</p>
        </div>
        <div class="signal-glyph" :data-tone="statusTone" aria-hidden="true">
          <i></i><i></i><i></i><i></i>
        </div>
      </section>

      <section class="status-panel">
        <div>
          <span class="panel-kicker">LINK STATUS</span>
          <strong>{{ statusLabel }}</strong>
          <p>{{ statusMessage || "点击右上角状态可重新检查服务器。" }}</p>
        </div>
        <button type="button" @click="checkServer(savedProfile)">重新检查</button>
      </section>

      <section class="section-stage">
        <div class="section-heading">
          <span>02 / {{ activeSection.toUpperCase() }}</span>
          <h3>{{ sectionLabel(activeSection) }}</h3>
        </div>

        <div v-if="activeSection === 'connections'" class="empty-module">
          <div class="module-icon">⌁</div>
          <h4>连接目录即将接入</h4>
          <p>下一阶段将读取服务器连接列表，并提供数据库、Schema、表和字段的逐级浏览。</p>
        </div>
        <div v-else-if="activeSection === 'query'" class="empty-module">
          <div class="module-icon">›_</div>
          <h4>移动查询工作台</h4>
          <p>全屏 SQL 编辑、执行控制、危险语句确认和结果分页将在认证完成后接入。</p>
        </div>
        <div v-else-if="activeSection === 'history'" class="empty-module">
          <div class="module-icon">↺</div>
          <h4>最近活动</h4>
          <p>查询历史和收藏 SQL 将与 DBX Server 保持一致。</p>
        </div>
        <div v-else class="settings-list">
          <button type="button" @click="resetProfile">
            <span><b>更换服务器</b><small>清除当前设备上的节点配置</small></span>
            <i>→</i>
          </button>
        </div>
      </section>
    </main>

    <nav v-if="savedProfile" class="bottom-nav" aria-label="主导航">
      <button v-for="section in (['connections', 'query', 'history', 'settings'] as MobileSection[])" :key="section" :class="{ active: activeSection === section }" type="button" @click="activeSection = section">
        <span class="nav-symbol" aria-hidden="true">{{ { connections: "◫", query: "›_", history: "↺", settings: "⌘" }[section] }}</span>
        <span>{{ sectionLabel(section) }}</span>
      </button>
    </nav>
  </div>
</template>
