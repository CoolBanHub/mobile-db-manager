<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { loadDirectMongoCollections, loadDirectMongoDatabases, loadDirectMongoDocuments, mutateDirectMongo } from "@/lib/direct/mongo";
import type { MobileConnectionSummary } from "@/lib/mobileTypes";

const props = defineProps<{ connection: MobileConnectionSummary }>();

const databases = ref<string[]>([]);
const collections = ref<string[]>([]);
const documents = ref<string[]>([]);
const selectedDatabase = ref("");
const selectedCollection = ref("");
const selectedDocument = ref("");
const documentEditorOpen = ref(false);
const documentDraft = ref("");
const objectSearch = ref("");
const filterDraft = ref("{}");
const activeFilter = ref("{}");
const filterOpen = ref(false);
const inspectorTab = ref<"document" | "structure" | "indexes">("document");
const offset = ref(0);
const limit = 25;
const hasMore = ref(false);
const loading = ref(true);
const busy = ref(false);
const error = ref("");
const collectionError = ref("");
const status = ref("");
const createOpen = ref(false);
const createDraft = ref("{\n  \n}");
const productionConfirmation = ref("");

// 文档以 MongoDB Extended JSON 字符串保存，编辑时不会丢失 ObjectId 等 BSON 类型。
const canWrite = computed(() => !props.connection.readOnly && (!props.connection.isProduction || productionConfirmation.value === props.connection.name));
const searchNeedle = computed(() => objectSearch.value.trim().toLocaleLowerCase());
const visibleDatabases = computed(() => databases.value.filter((name) => !searchNeedle.value || name === selectedDatabase.value || name.toLocaleLowerCase().includes(searchNeedle.value)));
const visibleCollections = computed(() => collections.value.filter((name) => !searchNeedle.value || name === selectedCollection.value || name.toLocaleLowerCase().includes(searchNeedle.value)));
const visibleDocuments = computed(() => documents.value.filter((document) => !searchNeedle.value || `${documentTitle(document)} ${documentPreview(document)} ${documentIdentity(document)}`.toLocaleLowerCase().includes(searchNeedle.value)));
const selectedDocumentValue = computed(() => parsedDocument(selectedDocument.value));

function handleBack() {
  if (createOpen.value) {
    createOpen.value = false;
    return true;
  }
  if (documentEditorOpen.value) {
    documentEditorOpen.value = false;
    return true;
  }
  if (selectedDocument.value) {
    selectedDocument.value = "";
    documentDraft.value = "";
    return true;
  }
  if (selectedCollection.value) {
    selectedCollection.value = "";
    documents.value = [];
    collectionError.value = "";
    return true;
  }
  if (selectedDatabase.value) {
    selectedDatabase.value = "";
    collections.value = [];
    return true;
  }
  return false;
}

function getQueryContext() {
  return {
    connectionId: props.connection.id,
    database: selectedDatabase.value || props.connection.database || databases.value[0] || "",
    schema: null,
  };
}

defineExpose({ getQueryContext, handleBack });

function showDatabases() {
  selectedDatabase.value = "";
  selectedCollection.value = "";
  selectedDocument.value = "";
  collections.value = [];
  documents.value = [];
  collectionError.value = "";
  createOpen.value = false;
}

function showCollections() {
  selectedCollection.value = "";
  selectedDocument.value = "";
  documents.value = [];
  collectionError.value = "";
  createOpen.value = false;
}

function message(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback;
}

function documentLoadMessage(reason: unknown) {
  const raw = message(reason, "MongoDB 文档加载失败");
  if (/unauthorized|not authorized|error 13|code.?13/i.test(raw)) {
    return `当前账号无权读取 ${selectedDatabase.value}.${selectedCollection.value}，请授予 find 权限或选择其他集合。`;
  }
  if (raw.length > 180) return "文档读取失败，请检查当前账号权限、筛选条件或连接状态。";
  return raw;
}

function prettyJson(source: string) {
  try {
    return JSON.stringify(JSON.parse(source), null, 2);
  } catch {
    return source;
  }
}

function parsedDocument(source: string): Record<string, unknown> {
  try {
    const value = JSON.parse(source) as unknown;
    return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function documentIdentity(source: string) {
  const identity = parsedDocument(source)._id;
  if (identity === undefined) return "NO _ID";
  if (typeof identity === "string" || typeof identity === "number") return String(identity);
  return JSON.stringify(identity);
}

function documentPreview(source: string) {
  const value = parsedDocument(source);
  const fields = Object.entries(value)
    .filter(([key]) => key !== "_id")
    .slice(0, 3)
    .map(([key, item]) => `${key}: ${compactValue(item)}`);
  return fields.join(" · ") || "仅包含 _id";
}

function documentTitle(source: string) {
  const value = parsedDocument(source);
  for (const key of ["name", "title", "username", "email", "label"]) {
    const candidate = value[key];
    if (typeof candidate === "string" && candidate.trim()) return candidate;
  }
  return documentIdentity(source);
}

function shortIdentity(source: string) {
  const identity = documentIdentity(source);
  const objectId = identity.match(/[a-f\d]{24}/i)?.[0];
  if (objectId) return `${objectId.slice(0, 7)}…${objectId.slice(-5)}`;
  return identity.length > 18 ? `${identity.slice(0, 8)}…${identity.slice(-5)}` : identity;
}

function documentBytes(source: string) {
  return new TextEncoder().encode(source).length;
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  return `${(value / 1024).toFixed(1)} KB`;
}

function documentCreatedAt(source: string) {
  const identity = documentIdentity(source);
  const match = identity.match(/[a-f\d]{24}/i);
  if (!match) return "—";
  const date = new Date(Number.parseInt(match[0].slice(0, 8), 16) * 1000);
  return Number.isNaN(date.valueOf()) ? "—" : date.toISOString().slice(0, 10);
}

function documentStatus(source: string) {
  const statusValue = parsedDocument(source).status;
  return typeof statusValue === "string" || typeof statusValue === "number" ? String(statusValue) : "—";
}

function compactValue(value: unknown) {
  if (value === null) return "null";
  if (typeof value === "string") return value.length > 28 ? `${value.slice(0, 28)}…` : value;
  const text = JSON.stringify(value);
  return text.length > 34 ? `${text.slice(0, 34)}…` : text;
}

function validObjectJson(source: string, label: string) {
  // 查询条件和写入文档都必须是对象，拒绝数组或标量进入原生驱动。
  try {
    const value = JSON.parse(source) as unknown;
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error();
    }
    return true;
  } catch {
    error.value = `${label}必须是一个有效的 JSON 对象`;
    return false;
  }
}

async function loadDatabases() {
  loading.value = true;
  error.value = "";
  try {
    databases.value = await loadDirectMongoDatabases(props.connection.id);
    const preferred = props.connection.database?.trim();
    if (preferred && databases.value.includes(preferred)) await openDatabase(preferred);
  } catch (reason) {
    error.value = message(reason, "MongoDB 数据库列表加载失败");
  } finally {
    loading.value = false;
  }
}

async function openDatabase(name: string) {
  if (selectedDatabase.value === name) {
    selectedDatabase.value = "";
    selectedCollection.value = "";
    selectedDocument.value = "";
    collections.value = [];
    documents.value = [];
    collectionError.value = "";
    return;
  }
  loading.value = true;
  error.value = "";
  status.value = "";
  selectedDatabase.value = name;
  selectedCollection.value = "";
  selectedDocument.value = "";
  collectionError.value = "";
  try {
    collections.value = await loadDirectMongoCollections(props.connection.id, name);
  } catch (reason) {
    error.value = message(reason, "MongoDB 集合列表加载失败");
  } finally {
    loading.value = false;
  }
}

async function openCollection(name: string) {
  if (selectedCollection.value === name) {
    selectedCollection.value = "";
    selectedDocument.value = "";
    documents.value = [];
    collectionError.value = "";
    return;
  }
  selectedCollection.value = name;
  selectedDocument.value = "";
  createOpen.value = false;
  activeFilter.value = "{}";
  filterDraft.value = "{}";
  offset.value = 0;
  documents.value = [];
  hasMore.value = false;
  collectionError.value = "";
  await loadDocuments();
}

async function loadDocuments() {
  if (!selectedDatabase.value || !selectedCollection.value) return;
  loading.value = true;
  error.value = "";
  collectionError.value = "";
  status.value = "";
  documents.value = [];
  hasMore.value = false;
  try {
    const page = await loadDirectMongoDocuments(props.connection.id, selectedDatabase.value, selectedCollection.value, activeFilter.value, offset.value, limit);
    documents.value = page.documents;
    offset.value = page.offset;
    hasMore.value = page.hasMore;
  } catch (reason) {
    collectionError.value = documentLoadMessage(reason);
  } finally {
    loading.value = false;
  }
}

async function applyFilter() {
  if (!validObjectJson(filterDraft.value, "查询条件")) return;
  activeFilter.value = filterDraft.value.trim() || "{}";
  offset.value = 0;
  await loadDocuments();
}

async function changePage(delta: number) {
  offset.value = Math.max(0, offset.value + delta * limit);
  await loadDocuments();
}

function openDocument(source: string) {
  selectedDocument.value = source;
  documentDraft.value = prettyJson(source);
  documentEditorOpen.value = false;
  inspectorTab.value = "document";
  createOpen.value = false;
  error.value = "";
  status.value = "";
}

function openDocumentEditor() {
  if (!selectedDocument.value) return;
  documentDraft.value = prettyJson(selectedDocument.value);
  documentEditorOpen.value = true;
}

async function runMutation(action: "insert" | "replace" | "delete", payload: Record<string, unknown>, success: string) {
  if (!canWrite.value || busy.value) return null;
  busy.value = true;
  error.value = "";
  status.value = "";
  try {
    const result = await mutateDirectMongo(props.connection.id, selectedDatabase.value, selectedCollection.value, action, payload, productionConfirmation.value);
    status.value = success;
    return result;
  } catch (reason) {
    error.value = message(reason, "MongoDB 写入失败");
    return null;
  } finally {
    busy.value = false;
  }
}

async function insertDocument() {
  if (!validObjectJson(createDraft.value, "新文档")) return;
  const result = await runMutation("insert", { document: createDraft.value }, "文档已插入");
  if (!result) return;
  createOpen.value = false;
  createDraft.value = "{\n  \n}";
  offset.value = 0;
  await loadDocuments();
  status.value = "文档已插入";
}

async function replaceDocument() {
  if (!selectedDocument.value || !validObjectJson(documentDraft.value, "文档")) return;
  const result = await runMutation("replace", { original: selectedDocument.value, document: documentDraft.value }, "文档已保存");
  if (!result) return;
  selectedDocument.value = documentDraft.value;
  documentDraft.value = prettyJson(documentDraft.value);
  await loadDocuments();
  openDocument(selectedDocument.value);
  status.value = "文档已保存";
}

async function deleteDocument() {
  if (!selectedDocument.value || !window.confirm("永久删除此 MongoDB 文档？此操作不可撤销。")) return;
  const result = await runMutation("delete", { original: selectedDocument.value }, "文档已删除");
  if (!result) return;
  selectedDocument.value = "";
  documentEditorOpen.value = false;
  documentDraft.value = "";
  await loadDocuments();
  status.value = "文档已删除";
}

onMounted(loadDatabases);
</script>

<template>
  <section class="mongo-browser">
    <template v-if="!documentEditorOpen">
      <section class="mongo-browser-search">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6" />
          <path d="m16 16 4 4" />
        </svg>
        <input v-model="objectSearch" type="search" placeholder="搜索数据库、集合或文档" />
        <button type="button" aria-label="文档筛选" @click="filterOpen = !filterOpen">≡</button>
      </section>

      <section v-if="filterOpen" class="mongo-filter">
        <label><span>EXTENDED JSON 筛选</span><textarea v-model="filterDraft" rows="2" spellcheck="false"></textarea></label>
        <button :disabled="!selectedCollection" type="button" @click="applyFilter">应用</button>
      </section>

      <p v-if="error" class="mongo-message error" role="alert">{{ error }}</p>
      <p v-else-if="status" class="mongo-message success">{{ status }}</p>

      <div v-if="loading && databases.length === 0" class="mongo-loading"><i></i><strong>正在读取 MongoDB</strong></div>
      <div v-else class="mongo-tree-view" :aria-busy="loading">
        <div class="mongo-tree-root">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <rect x="4" y="3" width="16" height="18" rx="2" />
            <path d="M4 8h16M4 14h16M9 3v18" />
          </svg>
          <span>数据库 ({{ databases.length }})</span>
        </div>

        <div class="mongo-tree" role="tree" aria-label="MongoDB 对象树">
          <template v-for="databaseName in visibleDatabases" :key="databaseName">
            <button class="mongo-tree-node database" :class="{ expanded: selectedDatabase === databaseName }" type="button" role="treeitem" @click="openDatabase(databaseName)">
              <span class="mongo-chevron">›</span>
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <ellipse cx="12" cy="5" rx="6" ry="3" />
                <path d="M6 5v10c0 1.7 2.7 3 6 3s6-1.3 6-3V5M6 10c0 1.7 2.7 3 6 3s6-1.3 6-3" />
              </svg>
              <span>{{ databaseName }}</span>
            </button>

            <template v-if="selectedDatabase === databaseName">
              <button class="mongo-tree-node group expanded" type="button" @click="showDatabases">
                <span class="mongo-chevron">›</span>
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <rect x="3.5" y="4" width="17" height="16" rx="1.5" />
                  <path d="M3.5 9h17M9 4v16M15 4v16" />
                </svg>
                <span>集合 ({{ collections.length }})</span>
              </button>

              <template v-for="collectionName in visibleCollections" :key="collectionName">
                <div class="mongo-collection-row">
                  <button class="mongo-tree-node collection" :class="{ expanded: selectedCollection === collectionName }" type="button" @click="openCollection(collectionName)">
                    <span class="mongo-chevron">›</span>
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <rect x="3.5" y="4" width="17" height="16" rx="1.5" />
                      <path d="M3.5 9h17M9 4v16M15 4v16" />
                    </svg>
                    <span>{{ collectionName }}</span
                    ><small v-if="selectedCollection === collectionName">{{ hasMore ? `${offset + documents.length}+` : offset + documents.length }}</small>
                  </button>
                  <button v-if="selectedCollection === collectionName" class="mongo-tree-add" :disabled="connection.readOnly" type="button" aria-label="新建文档" @click="createOpen = !createOpen">＋</button>
                </div>

                <template v-if="selectedCollection === collectionName">
                  <div v-if="collectionError" class="mongo-collection-warning" role="status">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <rect x="5" y="10" width="14" height="10" rx="2" />
                      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                    </svg>
                    <span
                      ><strong>无法读取文档</strong><small>{{ collectionError }}</small></span
                    >
                  </div>
                  <button v-for="document in visibleDocuments" :key="documentIdentity(document)" class="mongo-tree-node document" :class="{ selected: selectedDocument === document }" type="button" @click="openDocument(document)">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6zM14 3v5h4" /></svg>
                    <span>{{ documentTitle(document) }}</span
                    ><small>_id: {{ shortIdentity(document) }}</small>
                  </button>
                  <button v-if="hasMore" class="mongo-tree-node document load" type="button" @click="changePage(1)">
                    <span>…</span><span>加载下一页</span><small>{{ offset + 1 }}—{{ offset + documents.length }}</small>
                  </button>
                  <div v-if="documents.length === 0 && !loading && !collectionError" class="mongo-tree-empty">没有匹配的文档</div>
                </template>
              </template>
            </template>
          </template>
          <div v-if="databases.length === 0" class="mongo-tree-empty root">没有可访问的数据库</div>
        </div>

        <section class="mongo-inspector">
          <nav aria-label="MongoDB 文档工具">
            <button :class="{ active: inspectorTab === 'document' }" type="button" @click="inspectorTab = 'document'">文档</button>
            <button :class="{ active: inspectorTab === 'structure' }" type="button" @click="inspectorTab = 'structure'">结构</button>
            <button :class="{ active: inspectorTab === 'indexes' }" type="button" @click="inspectorTab = 'indexes'">索引</button>
          </nav>
          <div v-if="inspectorTab === 'document' && selectedDocument" class="mongo-document-summary">
            <button class="mongo-document-title" type="button" @click="openDocumentEditor">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6zM14 3v5h4" /></svg>
              <span
                ><strong>{{ documentTitle(selectedDocument) }}</strong
                ><small>{{ selectedDatabase }}.{{ selectedCollection }}</small></span
              ><b>›</b>
            </button>
            <dl>
              <dt>_id</dt>
              <dd>{{ shortIdentity(selectedDocument) }}</dd>
              <dt>状态</dt>
              <dd>{{ documentStatus(selectedDocument) }}</dd>
              <dt>字段</dt>
              <dd>{{ Object.keys(selectedDocumentValue).length }}</dd>
            </dl>
            <dl>
              <dt>大小</dt>
              <dd>{{ formatBytes(documentBytes(selectedDocument)) }}</dd>
              <dt>创建</dt>
              <dd>{{ documentCreatedAt(selectedDocument) }}</dd>
              <dt>类型</dt>
              <dd>Extended JSON</dd>
            </dl>
          </div>
          <div v-else-if="inspectorTab === 'structure' && selectedDocument" class="mongo-structure-summary">
            <span v-for="(value, key) in selectedDocumentValue" :key="key"
              ><b>{{ key }}</b
              ><small>{{ Array.isArray(value) ? "Array" : value === null ? "Null" : typeof value }}</small></span
            >
          </div>
          <div v-else-if="inspectorTab === 'indexes' && selectedCollection" class="mongo-inspector-hint">当前原生接口暂未返回集合索引元数据</div>
          <div v-else class="mongo-inspector-hint">选择上方文档查看摘要</div>
        </section>
      </div>

      <section v-if="createOpen" class="mongo-editor create mongo-create-floating">
        <div><span>NEW DOCUMENT / EXTENDED JSON</span><b>_id 可省略</b></div>
        <textarea v-model="createDraft" rows="8" spellcheck="false"></textarea>
        <button :disabled="!canWrite || busy" type="button" @click="insertDocument">插入文档</button>
      </section>
    </template>

    <template v-else>
      <div class="document-toolbar">
        <button type="button" @click="documentEditorOpen = false">← 文档列表</button>
        <span>EXTENDED JSON</span>
      </div>

      <section v-if="connection.isProduction || connection.readOnly" class="mongo-guard">
        <template v-if="connection.readOnly">
          <strong>READ ONLY</strong>
          <p>此连接已锁定为只读。</p>
        </template>
        <template v-else>
          <strong>PRODUCTION GUARD</strong>
          <p>输入完整连接名后才能保存或删除：{{ connection.name }}</p>
          <input v-model="productionConfirmation" :placeholder="connection.name" />
        </template>
      </section>

      <section class="document-identity">
        <small>IMMUTABLE / _ID</small>
        <strong>{{ documentIdentity(selectedDocument) }}</strong>
        <p>替换文档时会保留原始 BSON 类型和 _id。</p>
      </section>

      <section class="mongo-editor">
        <div>
          <span>DOCUMENT BODY</span><b>{{ documentDraft.length }} CHARS</b>
        </div>
        <textarea v-model="documentDraft" rows="19" spellcheck="false"></textarea>
        <button :disabled="!canWrite || busy" type="button" @click="replaceDocument">保存完整文档</button>
      </section>
      <button class="mongo-delete" :disabled="!canWrite || busy" type="button" @click="deleteDocument">永久删除此文档</button>
    </template>
  </section>
</template>

<style scoped>
.mongo-browser {
  --mongo: #11855b;
  --mongo-dark: #073b2b;
  --mongo-soft: #ecfdf5;
  margin-top: 0;
  border: 0;
  background: transparent;
}
.mongo-browser-search {
  display: grid;
  min-height: 40px;
  grid-template-columns: 28px minmax(0, 1fr) 40px;
  align-items: center;
  margin-bottom: 5px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--field);
}
.mongo-browser-search svg {
  width: 16px;
  height: 16px;
  margin-left: 10px;
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.7;
  stroke-linecap: round;
}
.mongo-browser-search input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 10px;
}
.mongo-browser-search > button {
  align-self: stretch;
  border: 0;
  border-left: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  font-size: 15px;
}
.mongo-tree-view {
  display: grid;
  height: calc(100dvh - 205px);
  min-height: 380px;
  grid-template-rows: auto minmax(0, 1fr) auto;
  margin: 0 -2px;
}
.mongo-tree-root,
.mongo-tree-node {
  display: flex;
  align-items: center;
  color: var(--ink);
  font-family: "PingFang SC", system-ui, sans-serif;
}
.mongo-tree-root {
  min-height: 29px;
  gap: 7px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 550;
}
.mongo-tree-root svg,
.mongo-tree-node svg,
.mongo-document-title svg {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.55;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.mongo-tree {
  overflow-y: auto;
  min-height: 160px;
  padding-bottom: 8px;
  scrollbar-width: none;
}
.mongo-tree::-webkit-scrollbar {
  display: none;
}
.mongo-tree-node {
  width: 100%;
  min-height: 28px;
  gap: 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  padding: 2px 6px;
  text-align: left;
  font-size: 10px;
  line-height: 1.2;
}
.mongo-tree-node:active {
  background: color-mix(in srgb, var(--accent-soft) 68%, transparent);
}
.mongo-tree-node.database {
  padding-left: 18px;
}
.mongo-tree-node.group {
  padding-left: 35px;
}
.mongo-tree-node.collection {
  padding-left: 58px;
}
.mongo-tree-node.document {
  padding-left: 86px;
}
.mongo-tree-node.document.selected {
  background: var(--accent-soft);
  color: var(--acid);
}
.mongo-tree-node.document.load {
  color: var(--acid);
}
.mongo-chevron {
  width: 10px;
  flex: 0 0 10px;
  color: var(--muted);
  font-size: 16px;
  line-height: 1;
  transition: transform 120ms ease;
}
.mongo-tree-node.expanded > .mongo-chevron {
  transform: rotate(90deg);
}
.mongo-tree-node > span:not(.mongo-chevron) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-tree-node > small {
  overflow: hidden;
  margin-left: auto;
  color: var(--muted);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-collection-row {
  position: relative;
}
.mongo-tree-add {
  position: absolute;
  top: 3px;
  right: 6px;
  width: 24px;
  height: 22px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--acid);
  font-size: 15px;
}
.mongo-tree-add:disabled {
  color: var(--faint);
}
.mongo-collection-row:has(.mongo-tree-add) .mongo-tree-node {
  padding-right: 34px;
}
.mongo-tree-empty {
  min-height: 34px;
  padding: 9px 12px 9px 108px;
  color: var(--muted);
  font-size: 9px;
}
.mongo-tree-empty.root {
  padding-left: 24px;
}
.mongo-collection-warning {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: 9px;
  margin: 3px 7px 5px 82px;
  border-left: 2px solid var(--amber);
  border-radius: 0 5px 5px 0;
  background: color-mix(in srgb, var(--amber) 8%, transparent);
  padding: 9px 10px;
  color: var(--ink);
}
.mongo-collection-warning svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: var(--amber);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.6;
}
.mongo-collection-warning span {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.mongo-collection-warning strong {
  font-size: 9px;
}
.mongo-collection-warning small {
  color: var(--muted);
  font-size: 8px;
  line-height: 1.5;
}
.mongo-inspector {
  border: 1px solid var(--line);
  border-bottom: 0;
  border-radius: 12px 12px 0 0;
  background: color-mix(in srgb, var(--panel) 96%, transparent);
  box-shadow: 0 -7px 22px rgba(0, 0, 0, 0.04);
}
.mongo-inspector nav {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-bottom: 1px solid var(--line);
}
.mongo-inspector nav button {
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 10px;
}
.mongo-inspector nav button.active {
  color: var(--acid);
  box-shadow: inset 0 -2px var(--acid);
}
.mongo-document-summary {
  display: grid;
  min-height: 112px;
  grid-template-columns: minmax(118px, 1fr) 0.9fr 1fr;
  gap: 10px;
  padding: 12px;
}
.mongo-document-title {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 8px;
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--ink);
  text-align: left;
}
.mongo-document-title svg {
  width: 22px;
  height: 22px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 5px;
}
.mongo-document-title span {
  display: grid;
  min-width: 0;
  gap: 4px;
}
.mongo-document-title strong,
.mongo-document-title small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-document-title strong {
  font-size: 11px;
}
.mongo-document-title small {
  color: var(--muted);
  font-size: 8px;
}
.mongo-document-title > b {
  margin-left: auto;
  color: var(--faint);
  font-weight: 400;
}
.mongo-document-summary dl {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-content: start;
  gap: 7px 7px;
  margin: 0;
  padding-left: 9px;
  border-left: 1px solid var(--line);
  font-size: 8px;
}
.mongo-document-summary dt {
  color: var(--muted);
}
.mongo-document-summary dd {
  overflow: hidden;
  margin: 0;
  color: var(--ink);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-structure-summary {
  display: flex;
  overflow-x: auto;
  min-height: 100px;
  align-content: flex-start;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px;
}
.mongo-structure-summary span {
  display: flex;
  min-height: 26px;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--line);
  border-radius: 5px;
  padding: 0 8px;
  font-size: 8px;
}
.mongo-structure-summary small {
  color: var(--muted);
}
.mongo-inspector-hint {
  min-height: 90px;
  padding: 24px 14px;
  color: var(--muted);
  text-align: center;
  font-size: 10px;
}
.mongo-create-floating {
  position: fixed;
  z-index: 20;
  right: 12px;
  bottom: calc(88px + env(safe-area-inset-bottom));
  left: 12px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: 0 16px 46px rgba(0, 0, 0, 0.2);
}
.mongo-masthead {
  display: flex;
  min-height: 126px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(118deg, #fff 0 65%, var(--mongo-soft) 65%);
  padding: 20px;
}
.mongo-masthead span,
.mongo-heading span,
.mongo-editor span {
  color: var(--mongo);
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.16em;
}
.mongo-masthead h4 {
  margin: 8px 0 4px;
  font-size: 23px;
  letter-spacing: -0.04em;
}
.mongo-masthead p {
  margin: 0;
  color: var(--muted);
  font-size: 9px;
}
.mongo-mark {
  position: relative;
  display: grid;
  width: 64px;
  height: 64px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgba(17, 133, 91, 0.28);
  border-radius: 50%;
}
.mongo-mark::before,
.mongo-mark i {
  position: absolute;
  border: 1px solid rgba(17, 133, 91, 0.18);
  border-radius: 50%;
  content: "";
}
.mongo-mark::before {
  inset: 7px;
}
.mongo-mark i {
  inset: 15px;
  animation: mongo-pulse 2s ease-in-out infinite;
}
.mongo-mark b {
  color: var(--mongo);
  font-size: 18px;
}
.mongo-crumbs {
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: 7px;
  overflow: auto;
  margin-bottom: 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--field);
  padding: 0 10px;
  white-space: nowrap;
}
.mongo-crumbs button {
  border: 0;
  background: transparent;
  padding: 6px 0;
  color: var(--acid);
  font-size: 8px;
}
.mongo-crumbs b {
  color: var(--faint);
  font-weight: 400;
}
.mongo-crumbs span {
  color: var(--mongo-dark);
  font-size: 8px;
}
.mongo-heading {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 0;
  padding: 0 7px;
}
.mongo-heading > div {
  display: grid;
  gap: 4px;
}
.mongo-heading b,
.mongo-editor b {
  color: var(--muted);
  font-size: 8px;
  font-weight: 600;
}
.mongo-heading > button {
  border: 1px solid var(--acid);
  border-radius: 5px;
  background: transparent;
  padding: 7px 10px;
  color: var(--acid);
  font-size: 8px;
}
.mongo-index > button,
.document-list > button {
  display: grid;
  width: 100%;
  min-height: 48px;
  grid-template-columns: 34px minmax(0, 1fr) 18px;
  align-items: center;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 0 8px;
  text-align: left;
}
.mongo-index > button:active,
.document-list > button:active {
  background: var(--mongo-soft);
}
.mongo-index i,
.document-list i {
  display: grid;
  width: 27px;
  height: 27px;
  place-items: center;
  border: 1px solid rgba(17, 133, 91, 0.28);
  color: var(--mongo);
  font-size: 7px;
  font-style: normal;
}
.mongo-index span,
.document-list span {
  min-width: 0;
}
.mongo-index small,
.document-list small {
  display: block;
  overflow: hidden;
  margin-bottom: 3px;
  color: var(--faint);
  font-size: 7px;
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-index strong,
.document-list strong {
  display: block;
  overflow: hidden;
  color: var(--ink);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mongo-index > button > b,
.document-list > button > b {
  color: var(--faint);
  font-size: 18px;
  font-weight: 400;
}
.mongo-filter {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 58px;
  gap: 6px;
  margin-bottom: 7px;
  border-bottom: 0;
}
.mongo-filter label {
  display: grid;
  gap: 3px;
  min-height: 44px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--field);
  padding: 6px 10px;
}
.mongo-filter label span {
  color: var(--faint);
  font-size: 7px;
  letter-spacing: 0.12em;
}
.mongo-filter textarea {
  min-width: 0;
  resize: vertical;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 9px;
  line-height: 1.5;
}
.mongo-filter button {
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  color: var(--acid);
  font-size: 8px;
}
.mongo-message {
  margin: 0;
  border-bottom: 1px solid var(--line);
  padding: 11px 14px;
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.5;
}
.mongo-message.error {
  background: #fff1f2;
  color: var(--danger);
}
.mongo-message.success {
  background: var(--mongo-soft);
  color: var(--mongo-dark);
}
.mongo-loading,
.mongo-empty {
  display: grid;
  min-height: 180px;
  place-items: center;
  align-content: center;
  gap: 12px;
  padding: 24px;
  text-align: center;
}
.mongo-loading i {
  width: 24px;
  height: 24px;
  border: 2px solid var(--line);
  border-top-color: var(--mongo);
  border-radius: 50%;
  animation: spin 800ms linear infinite;
}
.mongo-loading strong,
.mongo-empty strong {
  font-size: 11px;
}
.mongo-empty p {
  margin: 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
  line-height: 1.6;
}
.mongo-guard {
  border-bottom: 1px solid var(--line);
  background: #fffbeb;
  padding: 12px 14px;
}
.mongo-guard strong {
  color: var(--amber);
  font-size: 8px;
  letter-spacing: 0.12em;
}
.mongo-guard p {
  margin: 6px 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 9px;
}
.mongo-guard input {
  width: 100%;
  min-height: 38px;
  border: 1px solid var(--line);
  outline: 0;
  background: #fff;
  padding: 0 10px;
  font-size: 9px;
}
.mongo-editor {
  border-bottom: 1px solid var(--line);
  background: #fff;
}
.mongo-editor.create {
  background: var(--mongo-soft);
}
.mongo-editor > div {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding: 0 13px;
}
.mongo-editor textarea {
  display: block;
  width: calc(100% - 24px);
  min-height: 190px;
  resize: vertical;
  margin: 12px;
  border: 1px solid var(--line);
  outline: 0;
  background: #f8fafc;
  padding: 12px;
  color: var(--ink);
  font-size: 9px;
  line-height: 1.6;
}
.mongo-editor > button {
  width: 100%;
  min-height: 47px;
  border: 0;
  border-top: 1px solid var(--line);
  background: var(--mongo);
  color: #fff;
  font-size: 9px;
}
.mongo-pagination {
  display: grid;
  min-height: 50px;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  border-top: 1px solid var(--line);
}
.mongo-pagination button {
  height: 100%;
  border: 0;
  background: #fff;
  color: var(--acid);
  font-size: 8px;
}
.mongo-pagination button:last-child {
  text-align: right;
  padding-right: 14px;
}
.mongo-pagination button:first-child {
  text-align: left;
  padding-left: 14px;
}
.mongo-pagination span {
  color: var(--muted);
  font-size: 8px;
}
.document-toolbar {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  padding: 0 12px;
}
.document-toolbar button {
  border: 0;
  background: transparent;
  color: var(--acid);
  font-size: 9px;
}
.document-toolbar span {
  color: var(--mongo);
  font-size: 8px;
  letter-spacing: 0.14em;
}
.document-identity {
  border-bottom: 1px solid var(--line);
  background: linear-gradient(135deg, var(--mongo-soft), #fff 48%);
  padding: 17px 15px;
}
.document-identity small {
  display: block;
  color: var(--mongo);
  font-size: 8px;
  letter-spacing: 0.14em;
}
.document-identity strong {
  display: block;
  overflow-wrap: anywhere;
  margin: 8px 0;
  font-size: 12px;
  line-height: 1.45;
}
.document-identity p {
  margin: 0;
  color: var(--muted);
  font-family: "PingFang SC", sans-serif;
  font-size: 8px;
}
.mongo-delete {
  width: 100%;
  min-height: 49px;
  border: 0;
  background: #fff1f2;
  color: var(--danger);
  font-size: 9px;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.42;
}
@keyframes mongo-pulse {
  50% {
    inset: 10px;
    opacity: 0.35;
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
