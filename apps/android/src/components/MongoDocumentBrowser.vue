<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ApiError, apiPostJson, type MobileConnectionSummary, type MongoCollectionInfo, type MongoDocumentResult } from "../lib/mobileApi";

const props = defineProps<{
  baseUrl: string;
  token: string | null;
  connection: MobileConnectionSummary;
}>();

const emit = defineEmits<{ authExpired: [] }>();

const PAGE_SIZE = 30;
const databases = ref<string[]>([]);
const collections = ref<MongoCollectionInfo[]>([]);
const documents = ref<unknown[]>([]);
const selectedDatabase = ref("");
const selectedCollection = ref<MongoCollectionInfo | null>(null);
const filter = ref("{}");
const appliedFilter = ref("{}");
const sort = ref("");
const appliedSort = ref("");
const offset = ref(0);
const total = ref(0);
const totalIsExact = ref(true);
const loading = ref(false);
const errorMessage = ref("");
const expandedDocument = ref<number | null>(null);

const level = computed(() => (selectedCollection.value ? "documents" : selectedDatabase.value ? "collections" : "databases"));
const rangeLabel = computed(() => {
  if (!documents.value.length) return "0";
  const end = offset.value + documents.value.length;
  return `${offset.value + 1}–${end} / ${totalIsExact.value ? total.value : `${total.value}+`}`;
});

function handleError(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    emit("authExpired");
    return;
  }
  errorMessage.value = error instanceof Error ? error.message : "MongoDB 请求失败";
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
  selectedDatabase.value = "";
  selectedCollection.value = null;
  collections.value = [];
  documents.value = [];
  await run(async () => {
    databases.value = await apiPostJson<string[]>(props.baseUrl, "/api/mongo/list-databases", props.token, { connectionId: props.connection.id });
  });
}

async function openDatabase(database: string) {
  selectedDatabase.value = database;
  selectedCollection.value = null;
  documents.value = [];
  await run(async () => {
    collections.value = await apiPostJson<MongoCollectionInfo[]>(props.baseUrl, "/api/mongo/list-collections", props.token, { connectionId: props.connection.id, database });
  });
}

async function openCollection(collection: MongoCollectionInfo) {
  selectedCollection.value = collection;
  filter.value = "{}";
  appliedFilter.value = "{}";
  sort.value = "";
  appliedSort.value = "";
  offset.value = 0;
  await loadDocuments();
}

async function loadDocuments() {
  if (!selectedCollection.value) return;
  await run(async () => {
    const result = await apiPostJson<MongoDocumentResult>(props.baseUrl, "/api/mongo/find-documents", props.token, {
      connectionId: props.connection.id,
      database: selectedDatabase.value,
      collection: selectedCollection.value?.name,
      skip: offset.value,
      limit: PAGE_SIZE,
      filter: appliedFilter.value.trim() || "{}",
      sort: appliedSort.value.trim() || undefined,
    });
    documents.value = result.extended_documents ?? result.documents;
    total.value = result.total;
    totalIsExact.value = result.total_is_exact ?? true;
    expandedDocument.value = null;
  });
}

async function applyQuery() {
  try {
    JSON.parse(filter.value.trim() || "{}");
    if (sort.value.trim()) JSON.parse(sort.value);
  } catch {
    errorMessage.value = "筛选与排序必须是有效的 JSON 对象";
    return;
  }
  appliedFilter.value = filter.value.trim() || "{}";
  appliedSort.value = sort.value.trim();
  offset.value = 0;
  await loadDocuments();
}

async function previousPage() {
  offset.value = Math.max(0, offset.value - PAGE_SIZE);
  await loadDocuments();
}

async function nextPage() {
  offset.value += PAGE_SIZE;
  await loadDocuments();
}

function goBack() {
  errorMessage.value = "";
  if (selectedCollection.value) {
    selectedCollection.value = null;
    documents.value = [];
  } else if (selectedDatabase.value) {
    selectedDatabase.value = "";
    collections.value = [];
  }
}

function preview(document: unknown) {
  const text = JSON.stringify(document);
  return text.length > 180 ? `${text.slice(0, 180)}…` : text;
}

function pretty(document: unknown) {
  return JSON.stringify(document, null, 2);
}

onMounted(loadDatabases);
</script>

<template>
  <section class="mongo-browser">
    <header class="browser-header">
      <button v-if="level !== 'databases'" type="button" @click="goBack">←</button>
      <div>
        <small>MONGODB / {{ level.toUpperCase() }}</small>
        <h4>{{ selectedCollection?.name || selectedDatabase || "文档浏览器" }}</h4>
        <p>
          {{ connection.name }}<template v-if="selectedDatabase"> / {{ selectedDatabase }}</template>
        </p>
      </div>
      <span class="mongo-glyph">{ }</span>
    </header>

    <div v-if="level === 'documents'" class="mongo-query">
      <label><span>FILTER</span><textarea v-model="filter" rows="2" autocapitalize="none" spellcheck="false"></textarea></label>
      <label><span>SORT · 可选</span><input v-model="sort" autocapitalize="none" spellcheck="false" placeholder='{ "createdAt": -1 }' /></label>
      <button :disabled="loading" type="button" @click="applyQuery">应用查询</button>
    </div>

    <p v-if="errorMessage" class="browser-error">{{ errorMessage }} <button type="button" @click="level === 'databases' ? loadDatabases() : level === 'collections' ? openDatabase(selectedDatabase) : loadDocuments()">重试</button></p>

    <div v-if="loading" class="browser-loading"><i></i><span>正在读取 MongoDB</span></div>

    <div v-else-if="level === 'databases'" class="resource-list">
      <button v-for="database in databases" :key="database" type="button" @click="openDatabase(database)">
        <span class="resource-icon">DB</span
        ><span
          ><b>{{ database }}</b
          ><small>DATABASE</small></span
        ><i>›</i>
      </button>
      <p v-if="!databases.length" class="browser-empty">此连接没有可见数据库。</p>
    </div>

    <div v-else-if="level === 'collections'" class="resource-list">
      <button v-for="collection in collections" :key="collection.id" type="button" @click="openCollection(collection)">
        <span class="resource-icon">◫</span>
        <span
          ><b>{{ collection.name }}</b
          ><small>{{ collection.kind || "collection" }}</small></span
        ><i>›</i>
      </button>
      <p v-if="!collections.length" class="browser-empty">此数据库没有可见集合。</p>
    </div>

    <div v-else class="document-stage">
      <div class="document-summary">
        <span>{{ rangeLabel }} DOCUMENTS</span><b>{{ appliedFilter === "{}" ? "ALL" : "FILTERED" }}</b>
      </div>
      <article v-for="(document, index) in documents" :key="index" class="document-card">
        <button type="button" @click="expandedDocument = expandedDocument === index ? null : index">
          <span>#{{ String(offset + index + 1).padStart(4, "0") }}</span>
          <code>{{ expandedDocument === index ? pretty(document) : preview(document) }}</code>
          <b>{{ expandedDocument === index ? "−" : "+" }}</b>
        </button>
      </article>
      <p v-if="!documents.length" class="browser-empty">没有匹配的文档。</p>
      <footer class="pager">
        <button :disabled="loading || offset === 0" type="button" @click="previousPage">← 上一页</button>
        <span>{{ rangeLabel }}</span>
        <button :disabled="loading || offset + documents.length >= total" type="button" @click="nextPage">下一页 →</button>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.mongo-browser {
  margin-top: 14px;
}
.browser-header {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--line);
  border-top: 2px solid #62d6ff;
  background: linear-gradient(120deg, rgba(98, 214, 255, 0.08), transparent 48%), var(--panel);
  padding: 14px;
}
.browser-header > button {
  width: 34px;
  height: 40px;
  border: 1px solid var(--line);
  background: transparent;
  color: #62d6ff;
}
.browser-header small {
  color: #62d6ff;
  font-size: 8px;
  letter-spacing: 0.12em;
}
.browser-header h4 {
  overflow: hidden;
  margin: 5px 0 0;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.browser-header p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-glyph {
  color: #62d6ff;
  font-size: 16px;
  font-weight: 750;
}
.mongo-query {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  border-top: 0;
  padding: 12px;
}
.mongo-query label span {
  display: block;
  margin-bottom: 5px;
  color: var(--muted);
  font-size: 8px;
}
.mongo-query textarea,
.mongo-query input {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 0;
  outline: 0;
  background: #080a09;
  padding: 9px 10px;
  color: var(--ink);
  font-size: 10px;
  resize: vertical;
}
.mongo-query button {
  min-height: 40px;
  border: 0;
  background: #62d6ff;
  color: #071116;
  font-size: 9px;
  font-weight: 750;
}
.browser-error {
  border: 1px solid rgba(255, 101, 95, 0.35);
  background: rgba(255, 101, 95, 0.08);
  padding: 11px;
  color: #ff918d;
  font-size: 9px;
  line-height: 1.5;
}
.browser-error button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
}
.browser-loading,
.browser-empty {
  display: flex;
  min-height: 130px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 1px dashed var(--line);
  color: var(--muted);
  font-size: 10px;
}
.browser-loading i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #62d6ff;
  box-shadow: 0 0 14px #62d6ff;
  animation: pulse 1s infinite alternate;
}
.resource-list {
  margin-top: 9px;
}
.resource-list > button {
  display: grid;
  width: 100%;
  grid-template-columns: 38px 1fr auto;
  align-items: center;
  gap: 11px;
  border: 1px solid var(--line);
  background: var(--panel);
  padding: 12px;
  text-align: left;
}
.resource-list > button + button {
  margin-top: 6px;
}
.resource-list .resource-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgba(98, 214, 255, 0.32);
  color: #62d6ff;
  font-size: 9px;
}
.resource-list b,
.resource-list small {
  display: block;
}
.resource-list b {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resource-list small {
  margin-top: 5px;
  color: var(--muted);
  font-size: 8px;
  text-transform: uppercase;
}
.resource-list > button > i {
  color: #62d6ff;
  font-style: normal;
  font-size: 20px;
}
.document-stage {
  margin-top: 10px;
}
.document-summary {
  display: flex;
  justify-content: space-between;
  margin-bottom: 7px;
  color: var(--muted);
  font-size: 8px;
  letter-spacing: 0.1em;
}
.document-summary b {
  color: #62d6ff;
}
.document-card {
  border: 1px solid var(--line);
  background: #0c0f0d;
}
.document-card + .document-card {
  margin-top: 6px;
}
.document-card button {
  display: grid;
  width: 100%;
  grid-template-columns: auto 1fr auto;
  align-items: start;
  gap: 9px;
  border: 0;
  background: transparent;
  padding: 11px;
  text-align: left;
}
.document-card span {
  color: #62d6ff;
  font-size: 8px;
}
.document-card code {
  overflow: hidden;
  color: #cbd4cb;
  font-size: 9px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.document-card b {
  color: var(--muted);
}
.pager {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.pager button {
  min-height: 40px;
  border: 1px solid var(--line);
  background: transparent;
  color: #62d6ff;
  font-size: 9px;
}
.pager button:last-child {
  grid-column: 3;
}
.pager button:disabled {
  color: var(--faint);
}
.pager span {
  color: var(--muted);
  font-size: 8px;
}
@keyframes pulse {
  to {
    opacity: 0.35;
    transform: scale(0.7);
  }
}
</style>
