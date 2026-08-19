<script setup lang="ts">
import { App as CapacitorApp } from "@capacitor/app";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import { StatusBar, Style } from "@capacitor/status-bar";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import AppBottomNav from "@/components/AppBottomNav.vue";
import AppTopBar from "@/components/AppTopBar.vue";
import PageState from "@/components/PageState.vue";
import UnsupportedConnectionBrowser from "@/features/browse/UnsupportedConnectionBrowser.vue";
import EtcdDataBrowser from "@/features/browse/etcd/EtcdDataBrowser.vue";
import MongoDataBrowser from "@/features/browse/mongo/MongoDataBrowser.vue";
import MetadataBrowser from "@/features/browse/relational/MetadataBrowser.vue";
import RedisDataBrowser from "@/features/browse/redis/RedisDataBrowser.vue";
import ConnectionManager from "@/features/connections/ConnectionManager.vue";
import QueryLanding from "@/features/query/QueryLanding.vue";
import QueryWorkbench from "@/features/query/QueryWorkbench.vue";
import SettingsPage from "@/features/settings/SettingsPage.vue";
import UpdateBanner from "@/features/update/UpdateBanner.vue";
import { checkLatestAppUpdate, downloadLatestAppUpdate, supportsAppUpdate, type AppUpdateInfo } from "@/lib/appUpdate";
import { databaseCapability } from "@/lib/databaseCapabilities";
import { listDirectConnections } from "@/lib/direct/connections";
import type { MobileConnectionSummary, MobileQueryDraft } from "@/lib/mobileTypes";

type MobileSection = "connections" | "query" | "settings";
type AppNavigationTarget = "connections" | "browse" | "query" | "settings";
type InterfaceDensity = "standard" | "compact";
type UpdateState = "idle" | "checking" | "available" | "current" | "downloading" | "downloaded" | "error";
type VisibleUpdateState = Exclude<UpdateState, "idle">;
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
const queryWorkspaceOpen = ref(false);
const connectionManager = ref<BackHandler | null>(null);
const metadataBrowser = ref<BrowseHandler | null>(null);
const mongoDataBrowser = ref<BrowseHandler | null>(null);
const redisDataBrowser = ref<BrowseHandler | null>(null);
const etcdDataBrowser = ref<BackHandler | null>(null);
const queryWorkbench = ref<BackHandler | null>(null);
const settingsPage = ref<BackHandler | null>(null);
const density = ref<InterfaceDensity>("compact");
const canCheckAppUpdate = supportsAppUpdate();
const updateState = ref<UpdateState>("idle");
const updateInfo = ref<AppUpdateInfo | null>(null);
const updateMessage = ref("");
const pageMotion = ref<"next" | "previous" | "">("");
// 数据库能力表决定浏览器组件；新增类型时无需在多个导航入口重复判断。
const queryConnections = computed(() => connections.value.filter((connection) => ["postgres", "mysql", "sqlserver", "redis", "mongodb"].includes(connection.dbType)));
const browsingMode = computed(() => (browsingConnection.value ? databaseCapability(browsingConnection.value.dbType).browse : null));
const visibleUpdateState = computed<VisibleUpdateState | null>(() => updateState.value === "idle" ? null : updateState.value);
const headerTitle = computed(() => browsingConnection.value?.name ?? "");
const headerSubtitle = computed(() => {
  const connection = browsingConnection.value;
  return connection ? `${connection.host}:${connection.port}` : "";
});
const activeNavigationTarget = computed<AppNavigationTarget>(() => {
  if (activeSection.value === "query" || activeSection.value === "settings") return activeSection.value;
  return browsingConnection.value ? "browse" : "connections";
});
let queryDraftNonce = 0;
let backButtonListener: PluginListenerHandle | null = null;
let pageMotionTimer: ReturnType<typeof setTimeout> | null = null;
let pageSwipeStart: { pointerId: number; x: number; y: number; startedAt: number } | null = null;
let suppressClickUntil = 0;
const LAST_BROWSE_CONNECTION_KEY = "mobile-db-last-browse-connection";
const LEGACY_LAST_BROWSE_CONNECTION_KEY = "dbx-last-browse-connection";
const INTERFACE_DENSITY_KEY = "mobile-db-interface-density";
const UPDATE_DISMISSED_TAG_KEY = "mobile-db-dismissed-update-tag";
const PAGE_SWIPE_IGNORED_SELECTOR = "button, label, input, textarea, select, [role='dialog'], [role='separator'], .result-scroll, .query-tabs, .object-tabs, .detail-tabs, .schema-switcher, .update-banner";

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
  queryWorkspaceOpen.value = true;
  navigateTo("query");
}

function createQuery() {
  queryDraft.value = null;
  queryWorkspaceOpen.value = true;
}

function sectionLabel(section: MobileSection) {
  return { connections: "连接", query: "查询", settings: "设置" }[section];
}

function applyDensity(value: InterfaceDensity) {
  density.value = value;
  document.documentElement.dataset.density = value;
  localStorage.setItem(INTERFACE_DENSITY_KEY, value);
}

function openBrowse() {
  activeSection.value = "connections";
  if (browsingConnection.value) return;
  const lastConnectionId = localStorage.getItem(LAST_BROWSE_CONNECTION_KEY) ?? localStorage.getItem(LEGACY_LAST_BROWSE_CONNECTION_KEY);
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
  const lastConnectionId = localStorage.getItem(LAST_BROWSE_CONNECTION_KEY) ?? localStorage.getItem(LEGACY_LAST_BROWSE_CONNECTION_KEY);
  const fallbackConnection = browsingConnection.value ?? connections.value.find((connection) => connection.id === lastConnectionId) ?? null;
  if (!fallbackConnection || !queryConnections.value.some((connection) => connection.id === fallbackConnection.id)) {
    queryWorkspaceOpen.value = false;
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
    sql: fallbackConnection.dbType === "mongodb" ? "db." : fallbackConnection.dbType === "redis" ? "" : "SELECT 1 AS result;",
  });
}

function selectNavigation(target: AppNavigationTarget) {
  if (target === "connections") {
    browsingConnection.value = null;
    navigateTo("connections");
    return;
  }
  if (target === "browse") {
    openBrowse();
    return;
  }
  if (target === "query") {
    openQueryFromBrowse();
    return;
  }
  navigateTo("settings");
}

function pagePosition() {
  if (activeSection.value === "settings") return 3;
  if (activeSection.value === "query") return 2;
  return browsingConnection.value ? 1 : 0;
}

function animatePageMotion(direction: "next" | "previous") {
  if (pageMotionTimer) clearTimeout(pageMotionTimer);
  pageMotion.value = "";
  requestAnimationFrame(() => {
    pageMotion.value = direction;
    pageMotionTimer = setTimeout(() => {
      pageMotion.value = "";
      pageMotionTimer = null;
    }, 220);
  });
}

function switchPageBySwipe(direction: "next" | "previous") {
  const before = pagePosition();
  if (direction === "next") {
    if (before === 0) openBrowse();
    else if (before === 1) openQueryFromBrowse();
    else if (before === 2) navigateTo("settings");
  } else if (before === 3) {
    navigateTo("query");
  } else if (before === 2) {
    leaveQuery();
  } else if (before === 1) {
    browsingConnection.value = null;
    activeSection.value = "connections";
  }
  if (pagePosition() === before) return;
  suppressClickUntil = performance.now() + 420;
  animatePageMotion(direction);
}

function startPageSwipe(event: PointerEvent) {
  if (!event.isPrimary || (event.pointerType === "mouse" && event.button !== 0)) return;
  const target = event.target instanceof Element ? event.target : null;
  if (target?.closest(PAGE_SWIPE_IGNORED_SELECTOR)) return;
  pageSwipeStart = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, startedAt: performance.now() };
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
}

function finishPageSwipe(event: PointerEvent) {
  const start = pageSwipeStart;
  pageSwipeStart = null;
  if (!start || start.pointerId !== event.pointerId) return;
  const deltaX = event.clientX - start.x;
  const deltaY = event.clientY - start.y;
  const distance = Math.abs(deltaX);
  const duration = Math.max(1, performance.now() - start.startedAt);
  const velocity = distance / duration;
  const isHorizontal = distance > Math.abs(deltaY) * 1.3;
  const passedThreshold = distance >= 56 || (distance >= 34 && velocity >= 0.5);
  if (duration <= 800 && isHorizontal && passedThreshold) switchPageBySwipe(deltaX < 0 ? "next" : "previous");
}

function cancelPageSwipe() {
  pageSwipeStart = null;
}

function blockClickAfterSwipe(event: MouseEvent) {
  if (performance.now() >= suppressClickUntil) return;
  event.preventDefault();
  event.stopPropagation();
}

function updateErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "无法连接 GitHub Releases";
}

async function checkForUpdates(manual = false) {
  if (!canCheckAppUpdate || updateState.value === "checking" || updateState.value === "downloading") return;
  if (manual) {
    updateState.value = "checking";
    updateMessage.value = "";
  }
  try {
    const info = await checkLatestAppUpdate();
    updateInfo.value = info;
    const dismissedTag = localStorage.getItem(UPDATE_DISMISSED_TAG_KEY);
    if (info.hasUpdate && info.latestTag !== dismissedTag) {
      updateState.value = "available";
      updateMessage.value = "";
    } else if (manual) {
      updateState.value = "current";
      updateMessage.value = info.latestTag ? `最新 Release：${info.latestTag}` : "";
    } else {
      updateState.value = "idle";
    }
  } catch (error) {
    // 后台自动检查失败不能阻塞数据库工作流；用户手动检查时才展示可重试错误。
    if (!manual) {
      console.warn("Update check failed", error);
      return;
    }
    updateState.value = "error";
    updateMessage.value = updateErrorMessage(error);
  }
}

async function downloadUpdate() {
  if (!updateInfo.value || updateState.value === "downloading") return;
  updateState.value = "downloading";
  updateMessage.value = updateInfo.value.apkName;
  try {
    const result = await downloadLatestAppUpdate(updateInfo.value);
    updateState.value = "downloaded";
    updateMessage.value = result.openedExternal ? "已打开下载页面" : `已开始下载：${result.fileName}`;
  } catch (error) {
    updateState.value = "error";
    updateMessage.value = updateErrorMessage(error);
  }
}

function dismissUpdate() {
  if (updateInfo.value?.latestTag && (updateState.value === "available" || updateState.value === "downloaded")) {
    localStorage.setItem(UPDATE_DISMISSED_TAG_KEY, updateInfo.value.latestTag);
  }
  updateState.value = "idle";
  updateMessage.value = "";
}

function leaveQuery() {
  if (queryWorkspaceOpen.value) {
    queryWorkspaceOpen.value = false;
    queryDraft.value = null;
    return;
  }
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
  if (activeSection.value === "settings") return settingsPage.value?.handleBack() ?? false;
  return false;
}

function handleHardwareBack() {
  if (activeContentHandlesBack()) return;

  if (activeSection.value === "query" && queryWorkspaceOpen.value) {
    queryWorkspaceOpen.value = false;
    queryDraft.value = null;
    return;
  }

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
  delete document.documentElement.dataset.theme;
  localStorage.removeItem("mobile-db-color-theme");
  localStorage.removeItem("dbx-color-theme");
  if (Capacitor.isNativePlatform()) {
    void StatusBar.setStyle({ style: Style.Dark });
    void StatusBar.setBackgroundColor({ color: "#ffffff" });
  }
  const storedDensity = localStorage.getItem(INTERFACE_DENSITY_KEY);
  applyDensity(storedDensity === "standard" ? "standard" : "compact");
  if (Capacitor.isNativePlatform()) {
    backButtonListener = await CapacitorApp.addListener("backButton", handleHardwareBack);
  }
  void checkForUpdates(false);
  await loadConnections();
});

watch(activeSection, () => window.scrollTo({ top: 0, behavior: "smooth" }));

onBeforeUnmount(() => {
  if (pageMotionTimer) clearTimeout(pageMotionTimer);
  void backButtonListener?.remove();
});
</script>

<template>
  <div class="app-shell" @click.capture="blockClickAfterSwipe" @pointercancel="cancelPageSwipe" @pointerdown="startPageSwipe" @pointerup="finishPageSwipe">
    <div class="ambient-grid" aria-hidden="true"></div>

    <main class="workspace-view">
      <section class="section-stage" :class="pageMotion ? `page-enter-${pageMotion}` : ''">
        <AppTopBar
          v-if="activeSection === 'connections' && browsingConnection"
          :title="headerTitle"
          :subtitle="headerSubtitle"
          can-go-back
          :production="browsingConnection?.isProduction"
          :read-only="browsingConnection?.readOnly"
          @back="browsingConnection = null"
        />
        <UpdateBanner v-if="visibleUpdateState && activeSection !== 'query'" :state="visibleUpdateState" :info="updateInfo" :message="updateMessage" @check="checkForUpdates(true)" @download="downloadUpdate" @dismiss="dismissUpdate" />

        <div v-if="activeSection === 'connections'">
          <PageState v-if="connectionsLoading" compact kind="loading" title="正在读取本机连接" description="安全存储中的连接信息正在加载。" />
          <PageState v-else-if="connectionsError" compact kind="error" :title="connectionsError" description="请检查本机存储状态后重试。" action-label="重新加载" @action="loadConnections" />
          <template v-else>
            <MetadataBrowser v-if="browsingConnection && browsingMode === 'relational'" ref="metadataBrowser" :connections="[browsingConnection]" @open-query="openQueryDraft" />
            <RedisDataBrowser v-else-if="browsingConnection && browsingMode === 'redis'" ref="redisDataBrowser" :connection="browsingConnection" />
            <MongoDataBrowser v-else-if="browsingConnection && browsingMode === 'mongo'" ref="mongoDataBrowser" :connection="browsingConnection" />
            <EtcdDataBrowser v-else-if="browsingConnection && browsingMode === 'etcd'" ref="etcdDataBrowser" :connection="browsingConnection" />
            <UnsupportedConnectionBrowser v-else-if="browsingConnection" :connection="browsingConnection" />
            <ConnectionManager v-else ref="connectionManager" :connections="connections" @browse="openConnectionBrowser" @changed="loadConnections" />
          </template>
        </div>

        <template v-else-if="activeSection === 'query'">
          <QueryWorkbench v-if="queryWorkspaceOpen" ref="queryWorkbench" :connections="queryConnections" :draft="queryDraft" @back="leaveQuery" @draft-consumed="queryDraft = null" />
          <QueryLanding v-else @create="createQuery" />
        </template>
        <SettingsPage
          v-else-if="activeSection === 'settings'"
          ref="settingsPage"
          :density="density"
          @set-density="applyDensity"
          @check-update="checkForUpdates(true)"
        />
      </section>
    </main>

    <AppBottomNav :active="activeNavigationTarget" @select="selectNavigation" />
  </div>
</template>
