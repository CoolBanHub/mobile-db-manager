<script setup lang="ts">
import { App as CapacitorApp } from "@capacitor/app";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import { StatusBar, Style } from "@capacitor/status-bar";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import ConnectionManager from "./components/ConnectionManager.vue";
import EtcdDataBrowser from "./components/EtcdDataBrowser.vue";
import MetadataBrowser from "./components/MetadataBrowser.vue";
import MongoDataBrowser from "./components/MongoDataBrowser.vue";
import QueryWorkbench from "./components/QueryWorkbench.vue";
import RedisDataBrowser from "./components/RedisDataBrowser.vue";
import UnsupportedConnectionBrowser from "./components/UnsupportedConnectionBrowser.vue";
import { databaseCapability } from "./lib/databaseCapabilities";
import { listDirectConnections } from "./lib/directDatabase";
import type { MobileConnectionSummary, MobileQueryDraft } from "./lib/mobileTypes";

type MobileSection = "connections" | "query";
type ColorTheme = "light" | "dark";
type BackHandler = { handleBack: () => boolean };
type BrowseQueryContext = { connectionId: string; database: string; schema: string | null };
type BrowseHandler = BackHandler & { getQueryContext: () => BrowseQueryContext | null };

const activeSection = ref<MobileSection>("connections");
const sectionHistory = ref<MobileSection[]>([]);
const connections = ref<MobileConnectionSummary[]>([]);
const connectionsLoading = ref(true);
const connectionsError = ref("");
const browsingConnection = ref<MobileConnectionSummary | null>(null);
const queryDraft = ref<MobileQueryDraft | null>(null);
const connectionManager = ref<BackHandler | null>(null);
const metadataBrowser = ref<BrowseHandler | null>(null);
const mongoDataBrowser = ref<BrowseHandler | null>(null);
const redisDataBrowser = ref<BrowseHandler | null>(null);
const etcdDataBrowser = ref<BackHandler | null>(null);
const queryWorkbench = ref<BackHandler | null>(null);
const theme = ref<ColorTheme>("light");
// 数据库能力表决定浏览器组件；新增类型时无需在多个导航入口重复判断。
const queryConnections = computed(() => connections.value.filter((connection) => ["postgres", "mysql", "sqlserver", "redis", "mongodb"].includes(connection.dbType)));
const browsingMode = computed(() => (browsingConnection.value ? databaseCapability(browsingConnection.value.dbType).browse : null));
const headerTitle = computed(() => browsingConnection.value?.name || (activeSection.value === "connections" ? "DBX" : sectionLabel(activeSection.value)));
const headerSubtitle = computed(() => {
  if (browsingConnection.value) {
    const connection = browsingConnection.value;
    return `${connection.host}:${connection.port}`;
  }
  return activeSection.value === "connections" ? "连接" : "SQL 工作台";
});
let queryDraftNonce = 0;
let backButtonListener: PluginListenerHandle | null = null;
const LAST_BROWSE_CONNECTION_KEY = "dbx-last-browse-connection";

async function loadConnections() {
  connectionsLoading.value = true;
  connectionsError.value = "";
  try {
    connections.value = await listDirectConnections();
  } catch (error) {
    connections.value = [];
    connectionsError.value = error instanceof Error ? error.message : "读取本机连接失败";
  } finally {
    connectionsLoading.value = false;
  }
}

function openConnectionBrowser(connection: MobileConnectionSummary) {
  browsingConnection.value = connection;
  localStorage.setItem(LAST_BROWSE_CONNECTION_KEY, connection.id);
  window.scrollTo({ top: 0, behavior: "auto" });
}

function navigateTo(section: MobileSection) {
  if (section === activeSection.value) return;
  sectionHistory.value.push(activeSection.value);
  activeSection.value = section;
}

function openQueryDraft(draft: Omit<MobileQueryDraft, "nonce">) {
  queryDraft.value = { ...draft, nonce: ++queryDraftNonce };
  navigateTo("query");
}

function sectionLabel(section: MobileSection) {
  return { connections: "连接", query: "查询" }[section];
}

function applyTheme(value: ColorTheme) {
  theme.value = value;
  document.documentElement.dataset.theme = value;
  localStorage.setItem("dbx-color-theme", value);
  if (Capacitor.isNativePlatform()) {
    void StatusBar.setStyle({ style: value === "dark" ? Style.Light : Style.Dark });
    void StatusBar.setBackgroundColor({ color: value === "dark" ? "#06111d" : "#ffffff" });
  }
}

function toggleTheme() {
  applyTheme(theme.value === "light" ? "dark" : "light");
}

function openBrowse() {
  activeSection.value = "connections";
  if (browsingConnection.value) return;
  const lastConnectionId = localStorage.getItem(LAST_BROWSE_CONNECTION_KEY);
  const lastConnection = connections.value.find((connection) => connection.id === lastConnectionId);
  if (lastConnection) browsingConnection.value = lastConnection;
}

function currentBrowseQueryContext(): BrowseQueryContext | null {
  // 浏览页把当前数据库/schema 交给查询页，避免用户切换功能后重新选择上下文。
  if (!browsingConnection.value) return null;
  if (browsingMode.value === "relational") return metadataBrowser.value?.getQueryContext() ?? null;
  if (browsingMode.value === "redis") return redisDataBrowser.value?.getQueryContext() ?? null;
  if (browsingMode.value === "mongo") return mongoDataBrowser.value?.getQueryContext() ?? null;
  return null;
}

function openQueryFromBrowse() {
  if (activeSection.value === "query") return;
  const lastConnectionId = localStorage.getItem(LAST_BROWSE_CONNECTION_KEY);
  const fallbackConnection = browsingConnection.value ?? connections.value.find((connection) => connection.id === lastConnectionId) ?? null;
  if (!fallbackConnection || !queryConnections.value.some((connection) => connection.id === fallbackConnection.id)) {
    navigateTo("query");
    return;
  }
  const context = currentBrowseQueryContext() ?? {
    connectionId: fallbackConnection.id,
    database: fallbackConnection.database ?? "",
    schema: null,
  };
  openQueryDraft({
    ...context,
    sql: fallbackConnection.dbType === "mongodb" ? "db." : fallbackConnection.dbType === "redis" ? "" : "SELECT 1;",
  });
}

function focusConnectionSearch() {
  document.querySelector<HTMLInputElement>(".catalog-search input")?.focus();
}

function handleHeaderMore() {
  toggleTheme();
}

function leaveQuery() {
  const previousSection = sectionHistory.value.pop();
  activeSection.value = previousSection && previousSection !== "query" ? previousSection : "connections";
}

function activeContentHandlesBack() {
  // Android 返回键优先关闭最深层的弹窗/详情，再逐级退出浏览器和顶层页面。
  if (activeSection.value === "connections") {
    if (!browsingConnection.value) return connectionManager.value?.handleBack() ?? false;
    if (browsingMode.value === "redis") return redisDataBrowser.value?.handleBack() ?? false;
    if (browsingMode.value === "mongo") return mongoDataBrowser.value?.handleBack() ?? false;
    if (browsingMode.value === "etcd") return etcdDataBrowser.value?.handleBack() ?? false;
    return metadataBrowser.value?.handleBack() ?? false;
  }
  if (activeSection.value === "query") return queryWorkbench.value?.handleBack() ?? false;
  return false;
}

function handleHardwareBack() {
  if (activeContentHandlesBack()) return;

  if (activeSection.value === "connections" && browsingConnection.value) {
    browsingConnection.value = null;
    return;
  }

  const previousSection = sectionHistory.value.pop();
  if (previousSection) {
    activeSection.value = previousSection;
    return;
  }

  if (activeSection.value !== "connections") {
    activeSection.value = "connections";
    return;
  }

  void CapacitorApp.exitApp();
}

onMounted(async () => {
  const storedTheme = localStorage.getItem("dbx-color-theme");
  applyTheme(storedTheme === "light" || storedTheme === "dark" ? storedTheme : window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  if (Capacitor.isNativePlatform()) {
    backButtonListener = await CapacitorApp.addListener("backButton", handleHardwareBack);
  }
  await loadConnections();
});

watch(activeSection, () => window.scrollTo({ top: 0, behavior: "smooth" }));

onBeforeUnmount(() => {
  void backButtonListener?.remove();
});
</script>

<template>
  <div class="app-shell">
    <div class="ambient-grid" aria-hidden="true"></div>

    <main class="workspace-view">
      <section class="section-stage">
        <header v-if="activeSection !== 'query'" class="app-header" :class="{ 'home-header': activeSection === 'connections' && !browsingConnection }">
          <button v-if="activeSection === 'connections' && browsingConnection" class="header-back" type="button" aria-label="返回连接列表" @click="browsingConnection = null">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
          </button>
          <div class="app-title">
            <h1>{{ headerTitle }}</h1>
            <p>{{ headerSubtitle }}</p>
          </div>
          <span v-if="browsingConnection?.isProduction" class="header-badge danger">生产</span>
          <span v-if="browsingConnection?.readOnly" class="header-readonly">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 3 5.5 5.8v5.3c0 4.3 2.7 7.7 6.5 9.9 3.8-2.2 6.5-5.6 6.5-9.9V5.8Z" />
              <path d="M9.5 12.2 11.2 14l3.6-4" />
            </svg>
            只读
          </span>
          <button v-if="activeSection === 'connections' && !browsingConnection" class="header-action" type="button" aria-label="搜索连接" @click="focusConnectionSearch">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="6" />
              <path d="m16 16 4 4" />
            </svg>
          </button>
          <button class="theme-toggle" type="button" :aria-label="theme === 'light' ? '切换深色模式' : '切换浅色模式'" :title="theme === 'light' ? '切换深色模式' : '切换浅色模式'" @click="handleHeaderMore">
            <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20.4 15.2A8.2 8.2 0 0 1 8.8 3.6 8.5 8.5 0 1 0 20.4 15.2Z" />
            </svg>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="4" />
              <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
            </svg>
          </button>
        </header>

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
            <MetadataBrowser v-if="browsingConnection && browsingMode === 'relational'" ref="metadataBrowser" :connections="[browsingConnection]" @open-query="openQueryDraft" />
            <RedisDataBrowser v-else-if="browsingConnection && browsingMode === 'redis'" ref="redisDataBrowser" :connection="browsingConnection" />
            <MongoDataBrowser v-else-if="browsingConnection && browsingMode === 'mongo'" ref="mongoDataBrowser" :connection="browsingConnection" />
            <EtcdDataBrowser v-else-if="browsingConnection && browsingMode === 'etcd'" ref="etcdDataBrowser" :connection="browsingConnection" />
            <UnsupportedConnectionBrowser v-else-if="browsingConnection" :connection="browsingConnection" />
            <ConnectionManager v-else ref="connectionManager" :connections="connections" @browse="openConnectionBrowser" @changed="loadConnections" />
          </template>
        </div>

        <QueryWorkbench v-else-if="activeSection === 'query'" ref="queryWorkbench" :connections="queryConnections" :draft="queryDraft" @back="leaveQuery" @draft-consumed="queryDraft = null" @more="handleHeaderMore" />
      </section>
    </main>

    <nav class="bottom-nav" aria-label="主导航">
      <button
        :class="{ active: activeSection === 'connections' && !browsingConnection }"
        type="button"
        @click="
          browsingConnection = null;
          navigateTo('connections');
        "
      >
        <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true">
          <ellipse cx="12" cy="6" rx="6.5" ry="2.5" />
          <path d="M5.5 6v6c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5V6M5.5 12v6c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5v-6" />
        </svg>
        <span>连接</span>
      </button>
      <button :class="{ active: activeSection === 'connections' && !!browsingConnection }" type="button" @click="openBrowse">
        <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M3.5 6.5h6l2 2h9v10.5a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19Z" />
          <path d="M3.5 9h17" />
        </svg>
        <span>浏览</span>
      </button>
      <button :class="{ active: activeSection === 'query' }" type="button" @click="openQueryFromBrowse">
        <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 7 4 5-4 5M13 18h6" /></svg>
        <span>查询</span>
      </button>
    </nav>
  </div>
</template>
