<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ApiError, apiPostJson, type MobileConnectionSummary, type RedisBlob, type RedisCommandResult, type RedisDatabaseInfo, type RedisKeyInfo, type RedisScanResult, type RedisValue } from "../lib/mobileApi";

const props = defineProps<{
  baseUrl: string;
  token: string | null;
  connection: MobileConnectionSummary;
}>();

const emit = defineEmits<{ authExpired: [] }>();

type Mode = "keys" | "console";
type ConsoleEntry = { command: string; output: string; safety: string; failed: boolean };

const mode = ref<Mode>("keys");
const databases = ref<RedisDatabaseInfo[]>([]);
const selectedDb = ref(0);
const keys = ref<RedisKeyInfo[]>([]);
const cursor = ref(0);
const pattern = ref("*");
const appliedPattern = ref("*");
const selectedKey = ref<RedisKeyInfo | null>(null);
const selectedValue = ref<RedisValue | null>(null);
const loading = ref(false);
const loadingMore = ref(false);
const errorMessage = ref("");
const command = ref("INFO");
const commandRunning = ref(false);
const consoleEntries = ref<ConsoleEntry[]>([]);

const currentDatabase = computed(() => databases.value.find((database) => database.db === selectedDb.value));

function handleError(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    emit("authExpired");
    return;
  }
  errorMessage.value = error instanceof Error ? error.message : "Redis 请求失败";
}

async function run(action: () => Promise<void>) {
  loading.value = true;
  errorMessage.value = "";
  try {
    await action();
  } catch (error) {
    handleError(error);
  } finally {
    loading.value = false;
  }
}

async function loadDatabases() {
  await run(async () => {
    databases.value = await apiPostJson<RedisDatabaseInfo[]>(props.baseUrl, "/api/redis/list-databases", props.token, { connectionId: props.connection.id });
    if (databases.value.length && !databases.value.some((database) => database.db === selectedDb.value)) {
      selectedDb.value = databases.value[0].db;
    }
    await scanKeys(true);
  });
}

async function scanKeys(reset: boolean) {
  if (reset) {
    cursor.value = 0;
    keys.value = [];
    selectedKey.value = null;
    selectedValue.value = null;
  }
  const result = await apiPostJson<RedisScanResult>(props.baseUrl, "/api/redis/scan-keys-batch", props.token, {
    connectionId: props.connection.id,
    db: selectedDb.value,
    cursor: cursor.value,
    pattern: appliedPattern.value,
    count: 80,
    maxIterations: 4,
    includeTypes: true,
  });
  keys.value = reset ? result.keys : [...keys.value, ...result.keys];
  cursor.value = result.cursor;
}

async function changeDatabase() {
  await run(() => scanKeys(true));
}

async function applySearch() {
  appliedPattern.value = pattern.value.trim() || "*";
  await run(() => scanKeys(true));
}

async function loadMore() {
  loadingMore.value = true;
  errorMessage.value = "";
  try {
    await scanKeys(false);
  } catch (error) {
    handleError(error);
  } finally {
    loadingMore.value = false;
  }
}

async function openKey(key: RedisKeyInfo) {
  selectedKey.value = key;
  await run(async () => {
    selectedValue.value = await apiPostJson<RedisValue>(props.baseUrl, "/api/redis/get-value", props.token, { connectionId: props.connection.id, db: selectedDb.value, keyRaw: key.key_raw });
  });
}

function closeValue() {
  selectedKey.value = null;
  selectedValue.value = null;
}

function decodeBlob(blob: RedisBlob): string {
  if (blob.encoding === "binary") return `[binary] ${blob.raw_base64}`;
  try {
    const bytes = Uint8Array.from(atob(blob.raw_base64), (character) => character.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  } catch {
    return blob.raw_base64;
  }
}

function valueText(value: RedisValue): string {
  const data = value.data;
  if (data.kind === "string") return decodeBlob(data.content);
  if (data.kind === "json") {
    try {
      return JSON.stringify(JSON.parse(data.value), null, 2);
    } catch {
      return data.value;
    }
  }
  if (data.kind === "list") return data.items.map((item) => `${item.index}: ${decodeBlob(item.value)}`).join("\n");
  if (data.kind === "set") return data.items.map((item) => decodeBlob(item.member)).join("\n");
  if (data.kind === "hash") return data.items.map((item) => `${decodeBlob(item.field)} → ${decodeBlob(item.value)}`).join("\n");
  if (data.kind === "zset") return data.items.map((item) => `${item.score} · ${decodeBlob(item.member)}`).join("\n");
  if (data.kind === "stream") return data.entries.map((entry) => `${entry.id}\n${entry.fields.map((field) => `  ${field.field}: ${field.value}`).join("\n")}`).join("\n");
  return "(无法预览此类型)";
}

function ttlLabel(ttl?: number) {
  if (ttl === undefined) return "TTL ?";
  if (ttl === -1) return "PERSIST";
  if (ttl < 0) return "EXPIRED";
  return `TTL ${ttl}s`;
}

const READ_COMMANDS = new Set([
  "BITCOUNT",
  "DBSIZE",
  "ECHO",
  "EXISTS",
  "GET",
  "GETBIT",
  "GETRANGE",
  "HEXISTS",
  "HGET",
  "HGETALL",
  "HKEYS",
  "HLEN",
  "HMGET",
  "HRANDFIELD",
  "HSCAN",
  "HSTRLEN",
  "HVALS",
  "INFO",
  "LINDEX",
  "LLEN",
  "LRANGE",
  "MEMORY",
  "MGET",
  "OBJECT",
  "PING",
  "PTTL",
  "RANDOMKEY",
  "SCAN",
  "SCARD",
  "SDIFF",
  "SINTER",
  "SISMEMBER",
  "SMEMBERS",
  "SMISMEMBER",
  "SRANDMEMBER",
  "SSCAN",
  "STRLEN",
  "SUNION",
  "TTL",
  "TYPE",
  "XINFO",
  "XLEN",
  "XPENDING",
  "XRANGE",
  "XREAD",
  "XREVRANGE",
  "ZCARD",
  "ZCOUNT",
  "ZLEXCOUNT",
  "ZMSCORE",
  "ZRANGE",
  "ZRANGEBYSCORE",
  "ZRANK",
  "ZREVRANGE",
  "ZREVRANGEBYSCORE",
  "ZREVRANK",
  "ZSCAN",
  "ZSCORE",
]);
const BLOCKED_COMMANDS = new Set(["ACL", "CONFIG", "DEBUG", "EVAL", "EVALSHA", "FCALL", "FLUSHALL", "FLUSHDB", "KEYS", "MODULE", "SCRIPT", "SHUTDOWN"]);

function firstCommandToken(source: string) {
  return source.trim().split(/\s+/, 1)[0]?.toUpperCase() ?? "";
}

async function executeCommand() {
  const source = command.value.trim();
  if (!source || commandRunning.value) return;
  const token = firstCommandToken(source);
  if (BLOCKED_COMMANDS.has(token)) {
    errorMessage.value = `移动端已阻止高风险命令 ${token}`;
    return;
  }
  if (!READ_COMMANDS.has(token) && !window.confirm(`${token} 可能修改 Redis 数据。确定在 DB ${selectedDb.value} 执行？`)) return;
  commandRunning.value = true;
  errorMessage.value = "";
  try {
    const result = await apiPostJson<RedisCommandResult>(props.baseUrl, "/api/redis/execute-command", props.token, { connectionId: props.connection.id, db: selectedDb.value, command: source, skipSafetyCheck: false });
    consoleEntries.value.unshift({
      command: result.command || source,
      output: JSON.stringify(result.value, null, 2),
      safety: result.safety,
      failed: false,
    });
    command.value = "";
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) emit("authExpired");
    consoleEntries.value.unshift({
      command: source,
      output: error instanceof Error ? error.message : "命令执行失败",
      safety: "error",
      failed: true,
    });
  } finally {
    commandRunning.value = false;
  }
}

onMounted(loadDatabases);
</script>

<template>
  <section class="redis-workbench">
    <header class="redis-header">
      <div>
        <small>REDIS / LIVE SESSION</small>
        <h4>{{ connection.name }}</h4>
        <p>{{ connection.host }}:{{ connection.port }} · DB {{ selectedDb }}</p>
      </div>
      <span>R//</span>
    </header>

    <div class="mode-switch">
      <button :class="{ active: mode === 'keys' }" type="button" @click="mode = 'keys'">KEY 浏览器</button>
      <button :class="{ active: mode === 'console' }" type="button" @click="mode = 'console'">命令工作台</button>
    </div>

    <div class="db-strip">
      <label
        ><span>DATABASE</span
        ><select v-model.number="selectedDb" @change="changeDatabase">
          <option v-for="database in databases" :key="database.db" :value="database.db">DB {{ database.db }} · {{ database.keys }} keys</option>
        </select></label
      >
      <b>{{ currentDatabase?.keys ?? 0 }} KEYS</b>
    </div>

    <p v-if="errorMessage" class="redis-error">{{ errorMessage }}</p>

    <template v-if="mode === 'keys'">
      <form class="key-search" @submit.prevent="applySearch">
        <input v-model="pattern" autocapitalize="none" spellcheck="false" placeholder="user:*" />
        <button :disabled="loading" type="submit">SCAN</button>
      </form>

      <div v-if="selectedValue" class="value-view">
        <button type="button" @click="closeValue">← 返回 Key 列表</button>
        <div class="value-meta">
          <span>{{ selectedValue.redis_type }}</span
          ><b>{{ selectedValue.key_display }}</b
          ><small>{{ ttlLabel(selectedValue.ttl) }}</small>
        </div>
        <pre>{{ valueText(selectedValue) }}</pre>
      </div>

      <div v-else-if="loading" class="redis-loading">正在扫描 DB {{ selectedDb }}…</div>
      <div v-else class="key-list">
        <button v-for="key in keys" :key="key.key_raw" type="button" @click="openKey(key)">
          <span :data-type="key.key_type || 'key'">{{ (key.key_type || "key").slice(0, 4) }}</span>
          <b>{{ key.key_display }}</b>
          <small>{{ ttlLabel(key.ttl) }}</small
          ><i>›</i>
        </button>
        <p v-if="!keys.length" class="redis-empty">没有匹配 {{ appliedPattern }} 的 Key。</p>
        <button v-if="cursor !== 0" class="load-more" :disabled="loadingMore" type="button" @click="loadMore">
          {{ loadingMore ? "继续扫描…" : "加载更多 Key" }}
        </button>
      </div>
    </template>

    <div v-else class="console-stage">
      <form @submit.prevent="executeCommand">
        <span>DB{{ selectedDb }} &gt;</span>
        <textarea v-model="command" rows="3" autocapitalize="none" spellcheck="false" placeholder="GET user:42"></textarea>
        <button :disabled="commandRunning || !command.trim()" type="submit">{{ commandRunning ? "RUNNING" : "EXECUTE ↵" }}</button>
      </form>
      <p class="console-hint">高风险管理命令在移动端禁用；写命令执行前需要确认，服务端只读/生产策略仍会生效。</p>
      <article v-for="(entry, index) in consoleEntries" :key="index" :class="{ failed: entry.failed }">
        <header>
          <span>DB{{ selectedDb }} &gt; {{ entry.command }}</span
          ><b>{{ entry.safety }}</b>
        </header>
        <pre>{{ entry.output }}</pre>
      </article>
      <p v-if="!consoleEntries.length" class="redis-empty">输入 Redis 命令开始会话，例如 PING、INFO、GET user:42。</p>
    </div>
  </section>
</template>

<style scoped>
.redis-workbench {
  margin-top: 14px;
}
.redis-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid var(--line);
  border-top: 2px solid #ff4d9d;
  background: linear-gradient(120deg, rgba(255, 77, 157, 0.09), transparent 48%), var(--panel);
  padding: 14px;
}
.redis-header small {
  color: #ff77b4;
  font-size: 8px;
  letter-spacing: 0.12em;
}
.redis-header h4 {
  margin: 5px 0 0;
  font-size: 16px;
}
.redis-header p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 9px;
}
.redis-header > span {
  color: #ff4d9d;
  font-size: 18px;
  font-weight: 800;
}
.mode-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 1px solid var(--line);
  border-top: 0;
}
.mode-switch button {
  min-height: 42px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 9px;
}
.mode-switch button + button {
  border-left: 1px solid var(--line);
}
.mode-switch button.active {
  background: rgba(255, 77, 157, 0.09);
  color: #ff77b4;
  box-shadow: inset 0 -2px #ff4d9d;
}
.db-strip {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin: 13px 0 9px;
}
.db-strip label {
  min-width: 0;
}
.db-strip label span {
  display: block;
  margin-bottom: 5px;
  color: var(--muted);
  font-size: 8px;
}
.db-strip select {
  max-width: 210px;
  height: 38px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: #0b0d0c;
  padding: 0 9px;
  color: var(--ink);
  font-size: 9px;
}
.db-strip > b {
  color: #ff77b4;
  font-size: 8px;
  letter-spacing: 0.09em;
}
.redis-error {
  border: 1px solid rgba(255, 101, 95, 0.35);
  background: rgba(255, 101, 95, 0.08);
  padding: 10px;
  color: #ff918d;
  font-size: 9px;
  line-height: 1.5;
}
.key-search {
  display: grid;
  grid-template-columns: 1fr auto;
}
.key-search input {
  min-width: 0;
  height: 43px;
  border: 1px solid var(--line);
  border-right: 0;
  border-radius: 0;
  outline: 0;
  background: #080a09;
  padding: 0 11px;
  color: var(--ink);
  font-size: 10px;
}
.key-search button {
  width: 70px;
  border: 0;
  background: #ff4d9d;
  color: #1a0710;
  font-size: 9px;
  font-weight: 800;
}
.redis-loading,
.redis-empty {
  display: grid;
  min-height: 120px;
  place-items: center;
  border: 1px dashed var(--line);
  color: var(--muted);
  font-size: 9px;
  text-align: center;
}
.key-list {
  margin-top: 8px;
}
.key-list > button:not(.load-more) {
  display: grid;
  width: 100%;
  grid-template-columns: 36px 1fr auto auto;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 11px;
  text-align: left;
}
.key-list > button + button {
  margin-top: 6px;
}
.key-list > button > span {
  display: grid;
  width: 36px;
  height: 30px;
  place-items: center;
  background: rgba(255, 77, 157, 0.1);
  color: #ff77b4;
  font-size: 7px;
  text-transform: uppercase;
}
.key-list > button > b {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.key-list > button > small {
  color: var(--muted);
  font-size: 7px;
}
.key-list > button > i {
  color: #ff77b4;
  font-style: normal;
}
.load-more {
  width: 100%;
  min-height: 42px;
  border: 1px solid rgba(255, 77, 157, 0.35);
  background: transparent;
  color: #ff77b4;
  font-size: 9px;
}
.value-view > button {
  margin-bottom: 8px;
  border: 0;
  background: transparent;
  padding: 7px 0;
  color: #ff77b4;
  font-size: 9px;
}
.value-meta {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  border-bottom: 0;
  padding: 11px;
}
.value-meta span {
  background: rgba(255, 77, 157, 0.12);
  padding: 5px 7px;
  color: #ff77b4;
  font-size: 7px;
  text-transform: uppercase;
}
.value-meta b {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.value-meta small {
  color: var(--muted);
  font-size: 7px;
}
.value-view pre,
.console-stage article pre {
  overflow: auto;
  max-height: 52dvh;
  margin: 0;
  border: 1px solid var(--line);
  background: #070908;
  padding: 13px;
  color: #d6ded4;
  font-size: 9px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.console-stage > form {
  display: grid;
  grid-template-columns: auto 1fr;
  border: 1px solid var(--line);
  background: #070908;
  padding: 11px;
}
.console-stage > form > span {
  padding: 8px 8px 0 0;
  color: #ff77b4;
  font-size: 9px;
}
.console-stage textarea {
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 10px;
  line-height: 1.6;
  resize: vertical;
}
.console-stage form button {
  grid-column: 2;
  min-height: 36px;
  border: 0;
  background: #ff4d9d;
  color: #1a0710;
  font-size: 8px;
  font-weight: 800;
}
.console-hint {
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.55;
}
.console-stage article {
  margin-top: 8px;
}
.console-stage article header {
  display: flex;
  justify-content: space-between;
  border: 1px solid var(--line);
  border-bottom: 0;
  background: var(--panel);
  padding: 9px;
  color: #ff77b4;
  font-size: 8px;
}
.console-stage article header b {
  color: var(--muted);
  text-transform: uppercase;
}
.console-stage article.failed header,
.console-stage article.failed pre {
  border-color: rgba(255, 101, 95, 0.3);
  color: #ff918d;
}
</style>
