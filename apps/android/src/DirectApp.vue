<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import ConnectionManager from "./components/ConnectionManager.vue";
import HistoryLibrary from "./components/HistoryLibrary.vue";
import MetadataBrowser from "./components/MetadataBrowser.vue";
import QueryWorkbench from "./components/QueryWorkbench.vue";
import UnsupportedConnectionBrowser from "./components/UnsupportedConnectionBrowser.vue";
import { databaseCapability, isMobileSqlDatabase } from "./lib/databaseCapabilities";
import { DIRECT_DATABASE_URL } from "./lib/directDatabase";
import { apiGetJson, type MobileConnectionSummary, type MobileQueryDraft } from "./lib/mobileApi";

type MobileSection = "connections" | "query" | "history" | "settings";

const activeSection = ref<MobileSection>("connections");
const connections = ref<MobileConnectionSummary[]>([]);
const connectionsLoading = ref(true);
const connectionsError = ref("");
const browsingConnection = ref<MobileConnectionSummary | null>(null);
const queryDraft = ref<MobileQueryDraft | null>(null);
const requireDeviceLock = ref(localStorage.getItem("dbx-mobile.direct.require-lock") !== "false");
const sqlConnections = computed(() => connections.value.filter((connection) => isMobileSqlDatabase(connection.dbType)));
const browsingMode = computed(() =>
  browsingConnection.value ? databaseCapability(browsingConnection.value.dbType).browse : null,
);
let queryDraftNonce = 0;

async function loadConnections() {
  connectionsLoading.value = true;
  connectionsError.value = "";
  try {
    connections.value = await apiGetJson(DIRECT_DATABASE_URL, "/api/mobile/connections", null, {});
  } catch (error) {
    connections.value = [];
    connectionsError.value = error instanceof Error ? error.message : "读取本机连接失败";
  } finally {
    connectionsLoading.value = false;
  }
}

function openConnectionBrowser(connection: MobileConnectionSummary) {
  browsingConnection.value = connection;
}

function openQueryDraft(draft: Omit<MobileQueryDraft, "nonce">) {
  queryDraft.value = { ...draft, nonce: ++queryDraftNonce };
  activeSection.value = "query";
}

function sectionLabel(section: MobileSection) {
  return { connections: "连接", query: "查询", history: "历史", settings: "设置" }[section];
}

function saveSecurityPreference() {
  localStorage.setItem("dbx-mobile.direct.require-lock", String(requireDeviceLock.value));
}

onMounted(loadConnections);
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
      <div class="status-chip" data-tone="success"><span class="status-dot"></span>本机直连</div>
    </header>

    <main class="workspace-view">
      <section class="node-hero">
        <div>
          <p class="sequence">ANDROID NATIVE DRIVERS</p>
          <h2>数据库直接连接</h2>
          <p class="server-address">凭据由 Android Keystore 加密保存在本机</p>
        </div>
        <div class="signal-glyph" data-tone="success" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
      </section>

      <section class="status-panel">
        <div>
          <span class="panel-kicker">DIRECT MODE</span>
          <strong>不依赖 DBX Web</strong>
          <p>查询流量由手机直接发送到数据库。生产环境请配合 VPN 或数据库 IP 白名单。</p>
        </div>
        <span>{{ connections.length }} 个连接</span>
      </section>

      <section class="section-stage">
        <div class="section-heading">
          <span>01 / {{ activeSection.toUpperCase() }}</span>
          <h3>{{ sectionLabel(activeSection) }}</h3>
        </div>

        <div v-if="activeSection === 'connections'">
          <div v-if="connectionsLoading" class="empty-module compact">
            <div class="module-icon">⌁</div>
            <h4>正在解锁本机连接</h4>
          </div>
          <div v-else-if="connectionsError" class="empty-module compact">
            <div class="module-icon">!</div>
            <h4>{{ connectionsError }}</h4>
            <button class="inline-action" type="button" @click="loadConnections">重试</button>
          </div>
          <template v-else>
            <button v-if="browsingConnection" class="catalog-back" type="button" @click="browsingConnection = null">← 返回连接管理</button>
            <MetadataBrowser
              v-if="browsingConnection && browsingMode === 'relational'"
              :base-url="DIRECT_DATABASE_URL"
              :connections="[browsingConnection]"
              :token="null"
              @open-query="openQueryDraft"
            />
            <UnsupportedConnectionBrowser
              v-else-if="browsingConnection"
              :connection="browsingConnection"
            />
            <ConnectionManager
              v-else
              :base-url="DIRECT_DATABASE_URL"
              :connections="connections"
              server-id="android-local"
              :token="null"
              @browse="openConnectionBrowser"
              @changed="loadConnections"
            />
          </template>
        </div>

        <QueryWorkbench
          v-else-if="activeSection === 'query'"
          :base-url="DIRECT_DATABASE_URL"
          :connections="sqlConnections"
          :draft="queryDraft"
          :token="null"
          @draft-consumed="queryDraft = null"
        />

        <HistoryLibrary
          v-else-if="activeSection === 'history'"
          :base-url="DIRECT_DATABASE_URL"
          :connections="connections"
          :token="null"
          @open-query="openQueryDraft"
        />

        <div v-else class="settings-stack">
          <section class="settings-card">
            <div class="card-index">LOCAL / SECURITY</div>
            <h3>本机安全</h3>
            <p class="field-hint">数据库密码不会离开设备，完整连接配置使用 Android Keystore AES-GCM 加密。</p>
            <label class="secure-toggle">
              <input v-model="requireDeviceLock" type="checkbox" @change="saveSecurityPreference" />
              <span><b>启动时使用系统锁屏</b><small>预留设置；后续版本将把每次首次解锁与数据库凭据读取绑定</small></span>
            </label>
          </section>
          <section class="settings-card">
            <div class="card-index">NETWORK / NOTICE</div>
            <h3>网络建议</h3>
            <p class="field-hint">不要把生产数据库端口直接暴露到公网。优先通过 WireGuard、Tailscale 或企业 VPN 接入内网，并为移动账号设置最小权限。</p>
          </section>
        </div>
      </section>
    </main>

    <nav class="bottom-nav" aria-label="主导航">
      <button
        v-for="section in ['connections', 'query', 'history', 'settings'] as MobileSection[]"
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
