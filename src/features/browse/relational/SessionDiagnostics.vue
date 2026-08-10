<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { interruptDirectSession, isActiveDatabaseSession, loadDirectDiagnostics, type DirectSessionAction } from "@/lib/direct/diagnostics";
import type { MobileConnectionSummary, MobileDatabaseLock, MobileDatabaseSession } from "@/lib/mobileTypes";

type DiagnosticTab = "active" | "sessions" | "locks";

const props = defineProps<{
  connection: MobileConnectionSummary;
  database: string;
}>();

const emit = defineEmits<{ back: [] }>();

const tab = ref<DiagnosticTab>("active");
const sessions = ref<MobileDatabaseSession[]>([]);
const locks = ref<MobileDatabaseLock[]>([]);
const sessionsError = ref("");
const locksError = ref("");
const loading = ref(false);
const autoRefresh = ref(true);
const lastUpdatedAt = ref<Date | null>(null);
const actionSessionId = ref("");
const actionMessage = ref("");
let refreshTimer: ReturnType<typeof setInterval> | null = null;

const activeSessions = computed(() => sessions.value.filter(isActiveDatabaseSession));
const longRunningCount = computed(() => activeSessions.value.filter((session) => Math.max(session.durationMs, session.transactionDurationMs) >= 30_000).length);
const visibleSessions = computed(() => (tab.value === "active" ? activeSessions.value : sessions.value));

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "数据库诊断请求失败";
}

async function refresh() {
  if (loading.value || document.visibilityState === "hidden") return;
  loading.value = true;
  actionMessage.value = "";
  const [sessionResult, lockResult] = await Promise.allSettled([
    loadDirectDiagnostics(props.connection.id, props.database, "sessions"),
    loadDirectDiagnostics(props.connection.id, props.database, "locks"),
  ]);
  if (sessionResult.status === "fulfilled") {
    sessions.value = sessionResult.value;
    sessionsError.value = "";
  } else {
    sessionsError.value = errorMessage(sessionResult.reason);
  }
  if (lockResult.status === "fulfilled") {
    locks.value = lockResult.value;
    locksError.value = "";
  } else {
    locksError.value = errorMessage(lockResult.reason);
  }
  lastUpdatedAt.value = new Date();
  loading.value = false;
}

function formatDuration(milliseconds: number) {
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) return "—";
  if (milliseconds < 1_000) return `${Math.round(milliseconds)} ms`;
  const seconds = Math.floor(milliseconds / 1_000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`;
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}

function sessionTitle(session: MobileDatabaseSession) {
  return `${session.user || "未知用户"} · #${session.sessionId}`;
}

function productionConfirmation() {
  if (!props.connection.isProduction) return "";
  return window.prompt(`生产连接操作确认：请输入完整连接名称“${props.connection.name}”`) ?? "";
}

async function interrupt(session: MobileDatabaseSession, action: DirectSessionAction) {
  if (props.connection.readOnly || actionSessionId.value) return;
  const verb = action === "cancel" ? "取消此会话正在执行的 SQL" : "终止此数据库会话";
  if (!window.confirm(`${verb}？\n\n${sessionTitle(session)}\n该操作可能导致事务回滚。`)) return;
  const confirmation = productionConfirmation();
  if (props.connection.isProduction && confirmation !== props.connection.name) {
    actionMessage.value = "生产连接名称不匹配，操作已取消";
    return;
  }
  actionSessionId.value = session.sessionId;
  actionMessage.value = "";
  try {
    const message = await interruptDirectSession({
      connectionId: props.connection.id,
      database: props.database,
      sessionId: session.sessionId,
      action,
      productionConfirmation: confirmation,
    });
    await refresh();
    actionMessage.value = message;
  } catch (error) {
    actionMessage.value = errorMessage(error);
  } finally {
    actionSessionId.value = "";
  }
}

function installRefreshTimer() {
  refreshTimer = setInterval(() => {
    if (autoRefresh.value) void refresh();
  }, 15_000);
}

onMounted(() => {
  void refresh();
  installRefreshTimer();
});

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});
</script>

<template>
  <section class="diagnostics">
    <header class="diagnostic-header">
      <button type="button" aria-label="返回数据库浏览" @click="emit('back')">←</button>
      <div>
        <strong>会话与锁</strong>
        <p>{{ connection.name }} / {{ database }}</p>
      </div>
      <button class="refresh" :disabled="loading" type="button" @click="refresh()">{{ loading ? "…" : "↻" }}</button>
    </header>

    <div class="diagnostic-summary">
      <span><b>{{ sessions.length }}</b>会话</span>
      <span><b>{{ activeSessions.length }}</b>活动</span>
      <span :class="{ warning: longRunningCount }"><b>{{ longRunningCount }}</b>超过 30 秒</span>
      <span :class="{ danger: locks.length }"><b>{{ locks.length }}</b>阻塞</span>
    </div>

    <nav class="diagnostic-tabs" aria-label="会话诊断分类">
      <button :class="{ active: tab === 'active' }" type="button" @click="tab = 'active'">正在执行</button>
      <button :class="{ active: tab === 'sessions' }" type="button" @click="tab = 'sessions'">全部会话</button>
      <button :class="{ active: tab === 'locks' }" type="button" @click="tab = 'locks'">锁等待</button>
    </nav>

    <div class="refresh-options">
      <label><input v-model="autoRefresh" type="checkbox" /> 每 15 秒刷新</label>
      <span v-if="lastUpdatedAt">{{ lastUpdatedAt.toLocaleTimeString() }}</span>
    </div>

    <p v-if="actionMessage" class="action-message">{{ actionMessage }}</p>

    <div v-if="tab !== 'locks'" class="diagnostic-list">
      <div v-if="sessionsError" class="diagnostic-state error">
        <strong>无法读取会话</strong><p>{{ sessionsError }}</p>
      </div>
      <article v-for="session in visibleSessions" :key="session.sessionId" class="session-card" :class="{ waiting: session.waitType }">
        <header>
          <div>
            <strong>{{ sessionTitle(session) }}</strong>
            <p>{{ session.database || "未选择数据库" }} · {{ session.client || "未知客户端" }}</p>
          </div>
          <span>{{ formatDuration(Math.max(session.durationMs, session.transactionDurationMs)) }}</span>
        </header>
        <dl>
          <dt>状态</dt><dd>{{ session.state || session.command || "—" }}</dd>
          <dt v-if="session.waitType">等待</dt><dd v-if="session.waitType">{{ session.waitType }} {{ session.waitEvent }}</dd>
          <dt v-if="session.transactionStartedAt">事务开始</dt><dd v-if="session.transactionStartedAt">{{ session.transactionStartedAt }}</dd>
        </dl>
        <pre v-if="session.query">{{ session.query }}</pre>
        <div class="session-actions">
          <button v-if="connection.dbType !== 'sqlserver'" :disabled="connection.readOnly || !!actionSessionId || !session.query" type="button" @click="interrupt(session, 'cancel')">取消 SQL</button>
          <button class="danger" :disabled="connection.readOnly || !!actionSessionId" type="button" @click="interrupt(session, 'terminate')">
            {{ actionSessionId === session.sessionId ? "处理中…" : "终止会话" }}
          </button>
        </div>
      </article>
      <div v-if="!sessionsError && !loading && visibleSessions.length === 0" class="diagnostic-state">
        <strong>{{ tab === "active" ? "当前没有正在执行的 SQL 或长事务" : "当前没有其他数据库会话" }}</strong>
      </div>
    </div>

    <div v-else class="diagnostic-list">
      <div v-if="locksError" class="diagnostic-state error">
        <strong>无法读取锁等待</strong><p>{{ locksError }}</p>
        <small>该诊断通常需要监控权限，例如 PostgreSQL pg_read_all_stats、MySQL PROCESS，或 SQL Server VIEW SERVER STATE / VIEW SERVER PERFORMANCE STATE。</small>
      </div>
      <article v-for="lock in locks" :key="`${lock.waitingSessionId}:${lock.blockingSessionId}:${lock.objectName}`" class="lock-card">
        <header>
          <strong>#{{ lock.waitingSessionId }} 等待 #{{ lock.blockingSessionId }}</strong>
          <span>{{ formatDuration(lock.durationMs) }}</span>
        </header>
        <p>{{ lock.waitType || "LOCK" }}<template v-if="lock.objectName"> · {{ lock.objectName }}</template></p>
        <label>等待中的 SQL</label><pre v-if="lock.waitingQuery">{{ lock.waitingQuery }}</pre>
        <label>阻塞方 SQL</label><pre v-if="lock.blockingQuery">{{ lock.blockingQuery }}</pre>
      </article>
      <div v-if="!locksError && !loading && locks.length === 0" class="diagnostic-state"><strong>当前没有检测到阻塞链</strong></div>
    </div>

    <p v-if="connection.readOnly" class="readonly-note">此连接已设为只读，只允许查看诊断信息。</p>
  </section>
</template>

<style scoped>
.diagnostics { display: grid; gap: 9px; padding-bottom: 20px; }
.diagnostic-header { display: grid; grid-template-columns: 34px minmax(0, 1fr) 34px; align-items: center; gap: 7px; }
.diagnostic-header button { width: 34px; height: 36px; border: 0; background: transparent; color: var(--ink); font-size: 18px; }
.diagnostic-header .refresh { border: 1px solid var(--line); border-radius: 8px; color: var(--acid); }
.diagnostic-header strong { font-size: 13px; }
.diagnostic-header p { overflow: hidden; margin: 3px 0 0; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.diagnostic-summary { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--line); border-radius: 8px; }
.diagnostic-summary span { display: grid; min-height: 48px; place-items: center; align-content: center; gap: 3px; color: var(--muted); font-size: 7px; }
.diagnostic-summary span + span { border-left: 1px solid var(--line); }
.diagnostic-summary b { color: var(--ink); font-size: 14px; }
.diagnostic-summary .warning b { color: var(--amber); }
.diagnostic-summary .danger b { color: var(--danger); }
.diagnostic-tabs { display: grid; grid-template-columns: repeat(3, 1fr); border-bottom: 1px solid var(--line); }
.diagnostic-tabs button { min-height: 37px; border: 0; background: transparent; color: var(--muted); font: inherit; font-size: 9px; }
.diagnostic-tabs button.active { color: var(--acid); box-shadow: inset 0 -2px var(--acid); }
.refresh-options { display: flex; align-items: center; justify-content: space-between; color: var(--muted); font-size: 8px; }
.refresh-options label { display: flex; align-items: center; gap: 6px; }
.action-message, .readonly-note { margin: 0; border: 1px solid var(--line); border-radius: 7px; padding: 9px 10px; color: var(--amber); font-size: 8px; line-height: 1.5; }
.diagnostic-list { display: grid; gap: 8px; }
.session-card, .lock-card { overflow: hidden; border: 1px solid var(--line); border-radius: 9px; background: var(--panel); }
.session-card > header, .lock-card > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 11px; }
.session-card > header strong, .lock-card > header strong { font-size: 10px; }
.session-card > header p, .lock-card > p { margin: 4px 0 0; color: var(--muted); font-size: 8px; }
.session-card > header > span, .lock-card > header > span { flex: 0 0 auto; color: var(--acid); font-size: 8px; }
.session-card.waiting { border-color: color-mix(in srgb, var(--amber) 40%, var(--line)); }
.session-card dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 4px 8px; margin: 0; padding: 0 11px 9px; font-size: 8px; }
.session-card dt { color: var(--muted); }
.session-card dd { overflow: hidden; margin: 0; text-overflow: ellipsis; white-space: nowrap; }
pre { overflow: auto; max-height: 130px; margin: 0; border-top: 1px solid var(--line); background: var(--field); padding: 10px 11px; color: var(--ink); font: 8px/1.55 "Azeret Mono Variable", monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.session-actions { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--line); }
.session-actions button { min-height: 35px; border: 0; background: transparent; color: var(--acid); font: inherit; font-size: 8px; }
.session-actions button + button { border-left: 1px solid var(--line); }
.session-actions button.danger { color: var(--danger); }
.session-actions button:disabled { color: var(--faint); opacity: .55; }
.lock-card > p { padding: 0 11px 9px; }
.lock-card label { display: block; padding: 8px 11px 5px; border-top: 1px solid var(--line); color: var(--muted); font-size: 7px; }
.lock-card label + pre { border-top: 0; }
.diagnostic-state { min-height: 120px; border: 1px dashed var(--line); padding: 25px 18px; color: var(--muted); text-align: center; }
.diagnostic-state strong { font-size: 10px; }
.diagnostic-state p { margin: 8px 0 0; font-size: 8px; line-height: 1.6; }
.diagnostic-state small { display: block; margin-top: 9px; font-size: 7px; line-height: 1.6; }
.diagnostic-state.error strong { color: var(--danger); }

.diagnostics {
  min-width: 0;
  max-width: 100%;
  padding-bottom: var(--space-4);
}
.diagnostic-summary,
.session-card,
.lock-card,
.action-message,
.readonly-note,
.diagnostic-state {
  border-color: var(--divider-color);
  border-radius: var(--radius-card);
  background: var(--card-background);
}
.diagnostic-tabs {
  border-color: var(--divider-color);
  background: var(--card-background);
}
.diagnostic-tabs button.active {
  color: var(--primary);
  box-shadow: inset 0 -2px var(--primary);
}
.session-card pre,
.lock-card pre {
  max-width: 100%;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  background: var(--input-background);
}
.readonly-note {
  border-color: color-mix(in srgb, var(--readonly) 35%, var(--divider-color));
  background: color-mix(in srgb, var(--readonly) 7%, var(--card-background));
  color: var(--readonly);
}
.session-actions button.danger {
  background: color-mix(in srgb, var(--danger) 6%, var(--card-background));
}
</style>
