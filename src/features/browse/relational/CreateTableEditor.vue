<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import type { MobileConnectionSummary } from "@/lib/mobileTypes";
import {
  buildCreateTableSql,
  dataTypesByDatabase,
  defaultCreateTableDraft,
  defaultTableColumn,
  supportedTableDialect,
  validateCreateTableDraft,
  type TableCheckDraft,
  type TableForeignKeyDraft,
  type TableIndexDraft,
  type TableTriggerDraft,
} from "@/lib/tableDefinition";

type EditorTab = "columns" | "indexes" | "foreignKeys" | "triggers" | "checks" | "options" | "comment" | "preview";

const props = defineProps<{
  connection: MobileConnectionSummary;
  database: string;
  schema: string;
}>();

const emit = defineEmits<{
  close: [];
  openQuery: [sql: string];
}>();

// 设计器只负责生成和预览 SQL，不直接执行写入。生成结果交给查询工作台后，
// 仍会经过原生侧的只读校验、写入确认和生产连接名称确认。
const draft = reactive(defaultCreateTableDraft(props.schema));
const activeTab = ref<EditorTab>("columns");
let nextId = 2;

const tabs = computed<Array<{ key: EditorTab; label: string; count?: number }>>(() => [
  { key: "columns", label: "字段", count: draft.columns.length },
  { key: "indexes", label: "索引", count: draft.indexes.length },
  { key: "foreignKeys", label: "外键", count: draft.foreignKeys.length },
  { key: "triggers", label: "触发器", count: draft.triggers.length },
  { key: "checks", label: "检查", count: draft.checks.length },
  { key: "options", label: "选项" },
  { key: "comment", label: "注释" },
  { key: "preview", label: "SQL 预览" },
]);
const dbType = computed(() => props.connection.dbType);
const dataTypes = computed(() => supportedTableDialect(dbType.value) ? dataTypesByDatabase[dbType.value] : []);
const validationErrors = computed(() => validateCreateTableDraft(draft, dbType.value));
const previewSql = computed(() => buildCreateTableSql(draft, dbType.value));
const targetLabel = computed(() => [props.connection.name, props.database, draft.schema].filter(Boolean).join(" / "));

function addColumn() {
  draft.columns.push({ ...defaultTableColumn(nextId++), name: "", primaryKey: false, autoIncrement: false });
}

function removeColumn(index: number) {
  if (draft.columns.length === 1) return;
  draft.columns.splice(index, 1);
}

function moveColumn(index: number, offset: -1 | 1) {
  const target = index + offset;
  if (target < 0 || target >= draft.columns.length) return;
  const [column] = draft.columns.splice(index, 1);
  draft.columns.splice(target, 0, column);
}

function addIndex() {
  const item: TableIndexDraft = { id: nextId++, name: `idx_${draft.name || "table"}`, columns: "", unique: false };
  draft.indexes.push(item);
}

function addForeignKey() {
  const item: TableForeignKeyDraft = {
    id: nextId++, name: `fk_${draft.name || "table"}`, column: "", referenceSchema: draft.schema,
    referenceTable: "", referenceColumn: "id", onUpdate: "", onDelete: "",
  };
  draft.foreignKeys.push(item);
}

function addCheck() {
  const item: TableCheckDraft = { id: nextId++, name: `chk_${draft.name || "table"}`, expression: "" };
  draft.checks.push(item);
}

function addTrigger() {
  const item: TableTriggerDraft = { id: nextId++, name: `trg_${draft.name || "table"}`, sql: "" };
  draft.triggers.push(item);
}

function removeById<T extends { id: number }>(items: T[], id: number) {
  const index = items.findIndex((item) => item.id === id);
  if (index >= 0) items.splice(index, 1);
}

function openQuery() {
  if (validationErrors.value.length) {
    activeTab.value = "preview";
    return;
  }
  emit("openQuery", previewSql.value);
}
</script>

<template>
  <Teleport to="body">
    <div class="definition-backdrop" @click.self="emit('close')">
      <section class="definition-sheet" role="dialog" aria-modal="true" aria-labelledby="create-table-title">
        <header class="definition-header">
          <button type="button" aria-label="关闭新建表" @click="emit('close')">←</button>
          <div>
            <small>TABLE DESIGNER</small>
            <h3 id="create-table-title">新建表</h3>
            <p>{{ targetLabel }}</p>
          </div>
          <button class="preview-shortcut" type="button" @click="activeTab = 'preview'">SQL</button>
        </header>

        <div class="table-identity">
          <label><span>表名</span><input v-model.trim="draft.name" autocapitalize="none" placeholder="例如 orders" /></label>
          <label><span>Schema</span><input v-model.trim="draft.schema" autocapitalize="none" placeholder="例如 public / dbo" /></label>
        </div>

        <nav class="definition-tabs" aria-label="新建表设置">
          <button v-for="tab in tabs" :key="tab.key" :class="{ active: activeTab === tab.key }" type="button" @click="activeTab = tab.key">
            {{ tab.label }}<b v-if="tab.count">{{ tab.count }}</b>
          </button>
        </nav>

        <main class="definition-body">
          <section v-if="activeTab === 'columns'" class="tab-panel">
            <div class="panel-heading"><div><strong>字段定义</strong><p>左右滑动编辑完整属性；主键支持多字段组合。</p></div><button type="button" @click="addColumn">＋ 字段</button></div>
            <div class="column-grid-scroll">
              <div class="column-grid column-grid-head" aria-hidden="true">
                <span>名称</span><span>类型</span><span>长度</span><span>小数点</span><span>不是 Null</span><span>自增</span><span>主键</span><span>唯一</span><span>默认值</span><span>操作</span>
              </div>
              <div v-for="(column, index) in draft.columns" :key="column.id" class="column-grid column-grid-row">
                <input v-model.trim="column.name" :aria-label="`字段 ${index + 1} 名称`" autocapitalize="none" placeholder="字段名" />
                <select v-model="column.dataType" :aria-label="`字段 ${index + 1} 类型`"><option v-for="type in dataTypes" :key="type" :value="type">{{ type }}</option></select>
                <input v-model.trim="column.length" :aria-label="`字段 ${index + 1} 长度`" inputmode="numeric" placeholder="—" />
                <input v-model.trim="column.scale" :aria-label="`字段 ${index + 1} 小数位`" inputmode="numeric" placeholder="—" />
                <label class="grid-check"><input v-model="column.nullable" type="checkbox" /><span>{{ column.nullable ? "允许" : "非空" }}</span></label>
                <label class="grid-check"><input v-model="column.autoIncrement" type="checkbox" /><span>自增</span></label>
                <label class="grid-check"><input v-model="column.primaryKey" type="checkbox" /><span>PK</span></label>
                <label class="grid-check"><input v-model="column.unique" type="checkbox" /><span>唯一</span></label>
                <input v-model="column.defaultValue" :aria-label="`字段 ${index + 1} 默认值`" autocapitalize="none" placeholder="NULL / CURRENT_TIMESTAMP" />
                <div class="row-actions"><button :disabled="index === 0" type="button" @click="moveColumn(index, -1)">↑</button><button :disabled="index === draft.columns.length - 1" type="button" @click="moveColumn(index, 1)">↓</button><button class="danger" :disabled="draft.columns.length === 1" type="button" @click="removeColumn(index)">×</button></div>
              </div>
            </div>
          </section>

          <section v-else-if="activeTab === 'indexes'" class="tab-panel">
            <div class="panel-heading"><div><strong>索引</strong><p>字段使用逗号分隔，生成独立 CREATE INDEX。</p></div><button type="button" @click="addIndex">＋ 索引</button></div>
            <article v-for="item in draft.indexes" :key="item.id" class="definition-card">
              <label><span>索引名称</span><input v-model.trim="item.name" autocapitalize="none" /></label>
              <label><span>字段</span><input v-model.trim="item.columns" autocapitalize="none" placeholder="user_id, created_at" /></label>
              <label class="inline-option"><input v-model="item.unique" type="checkbox" /><span>唯一索引</span></label>
              <button class="remove-card" type="button" @click="removeById(draft.indexes, item.id)">删除</button>
            </article>
            <p v-if="!draft.indexes.length" class="empty-panel">暂未添加索引</p>
          </section>

          <section v-else-if="activeTab === 'foreignKeys'" class="tab-panel">
            <div class="panel-heading"><div><strong>外键</strong><p>引用目标会按当前数据库方言安全引用。</p></div><button type="button" @click="addForeignKey">＋ 外键</button></div>
            <article v-for="item in draft.foreignKeys" :key="item.id" class="definition-card foreign-grid">
              <label><span>约束名称</span><input v-model.trim="item.name" /></label><label><span>本表字段</span><input v-model.trim="item.column" /></label>
              <label><span>引用 Schema</span><input v-model.trim="item.referenceSchema" /></label><label><span>引用表</span><input v-model.trim="item.referenceTable" /></label>
              <label><span>引用字段</span><input v-model.trim="item.referenceColumn" /></label>
              <label><span>删除动作</span><select v-model="item.onDelete"><option value="">默认</option><option>CASCADE</option><option>SET NULL</option><option>RESTRICT</option><option>NO ACTION</option></select></label>
              <label><span>更新动作</span><select v-model="item.onUpdate"><option value="">默认</option><option>CASCADE</option><option>SET NULL</option><option>RESTRICT</option><option>NO ACTION</option></select></label>
              <button class="remove-card" type="button" @click="removeById(draft.foreignKeys, item.id)">删除</button>
            </article>
            <p v-if="!draft.foreignKeys.length" class="empty-panel">暂未添加外键</p>
          </section>

          <section v-else-if="activeTab === 'triggers'" class="tab-panel">
            <div class="panel-heading"><div><strong>触发器</strong><p>触发器语法差异较大，请填写当前数据库可执行的完整 SQL。</p></div><button type="button" @click="addTrigger">＋ 触发器</button></div>
            <article v-for="item in draft.triggers" :key="item.id" class="definition-card">
              <label><span>触发器名称</span><input v-model.trim="item.name" /></label>
              <label><span>触发器 SQL</span><textarea v-model="item.sql" rows="7" autocapitalize="none" placeholder="CREATE TRIGGER …"></textarea></label>
              <button class="remove-card" type="button" @click="removeById(draft.triggers, item.id)">删除</button>
            </article>
            <p v-if="!draft.triggers.length" class="empty-panel">暂未添加触发器</p>
          </section>

          <section v-else-if="activeTab === 'checks'" class="tab-panel">
            <div class="panel-heading"><div><strong>检查约束</strong><p>表达式只填写 CHECK 括号内的内容。</p></div><button type="button" @click="addCheck">＋ 检查</button></div>
            <article v-for="item in draft.checks" :key="item.id" class="definition-card">
              <label><span>约束名称</span><input v-model.trim="item.name" /></label>
              <label><span>表达式</span><input v-model.trim="item.expression" autocapitalize="none" placeholder="amount >= 0" /></label>
              <button class="remove-card" type="button" @click="removeById(draft.checks, item.id)">删除</button>
            </article>
            <p v-if="!draft.checks.length" class="empty-panel">暂未添加检查约束</p>
          </section>

          <section v-else-if="activeTab === 'options'" class="tab-panel option-panel">
            <label class="inline-option"><input v-model="draft.ifNotExists" type="checkbox" /><span>仅当表不存在时创建</span></label>
              <label class="inline-option"><input v-model="draft.temporary" :disabled="dbType === 'sqlserver'" type="checkbox" /><span>临时表</span></label>
            <template v-if="dbType === 'mysql'">
              <label><span>存储引擎</span><input v-model.trim="draft.engine" placeholder="InnoDB" /></label>
              <label><span>默认字符集</span><input v-model.trim="draft.charset" placeholder="utf8mb4" /></label>
              <label><span>排序规则</span><input v-model.trim="draft.collation" placeholder="可选" /></label>
            </template>
            <p v-if="dbType === 'sqlserver'" class="option-note">SQL Server 临时表只在创建它的连接会话中有效；当前直连查询每次独立建连，因此不提供此选项。</p>
          </section>

          <section v-else-if="activeTab === 'comment'" class="tab-panel comment-panel">
            <label><span>表注释</span><textarea v-model="draft.comment" rows="4" placeholder="说明表的业务用途、数据来源或维护责任"></textarea></label>
            <div class="column-comments">
              <label v-for="column in draft.columns" :key="column.id"><span>{{ column.name || "未命名字段" }}</span><input v-model="column.comment" placeholder="字段注释" /></label>
            </div>
            <p v-if="dbType === 'sqlserver'" class="option-note">SQL Server 扩展属性注释暂不自动生成；注释内容仍保留在设计中供复制。</p>
          </section>

          <section v-else class="tab-panel preview-panel">
            <div v-if="validationErrors.length" class="validation-errors"><strong>需要修正</strong><p v-for="error in validationErrors" :key="error">{{ error }}</p></div>
            <div class="preview-heading"><span>{{ dbType.toUpperCase() }}</span><small>{{ database }}{{ draft.schema ? ` / ${draft.schema}` : "" }}</small></div>
            <pre><code>{{ previewSql }}</code></pre>
            <p>SQL 会在高级执行模式中打开；执行前仍需确认写入，生产连接还需输入完整连接名称。</p>
          </section>
        </main>

        <footer class="definition-footer">
          <span v-if="connection.readOnly">此连接为只读，生成的 SQL 无法执行</span>
          <span v-else-if="connection.isProduction">生产连接将在执行前要求名称确认</span>
          <span v-else>{{ validationErrors.length ? `${validationErrors.length} 项待修正` : "结构校验通过" }}</span>
          <button type="button" @click="activeTab = 'preview'">预览 SQL</button>
          <button class="primary" :disabled="validationErrors.length > 0" type="button" @click="openQuery">在查询工作台打开</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.definition-backdrop { position: fixed; z-index: 40; inset: 0; display: flex; justify-content: center; background: rgba(1, 8, 17, .58); backdrop-filter: blur(5px); }
.definition-sheet { display: grid; width: min(880px, 100%); height: 100dvh; grid-template-rows: auto auto auto minmax(0, 1fr) auto; background: var(--panel-raised); color: var(--ink); box-shadow: 0 0 60px rgba(0,0,0,.25); }
.definition-header { display: grid; grid-template-columns: 38px minmax(0, 1fr) 48px; align-items: center; gap: 8px; border-bottom: 1px solid var(--line); padding: calc(10px + var(--safe-top)) 14px 10px; }
.definition-header button { min-height: 38px; border: 0; background: transparent; color: var(--acid); font-size: 18px; }
.definition-header .preview-shortcut { border: 1px solid color-mix(in srgb, var(--acid) 30%, var(--line)); border-radius: 6px; font: 9px "Azeret Mono Variable", monospace; }
.definition-header small { color: var(--acid); font: 7px "Azeret Mono Variable", monospace; letter-spacing: .16em; }
.definition-header h3 { margin: 3px 0 0; font-size: 19px; }
.definition-header p { overflow: hidden; margin: 3px 0 0; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.table-identity { display: grid; grid-template-columns: 1.2fr 1fr; gap: 8px; padding: 10px 14px; }
.table-identity label span, .definition-card label > span, .option-panel label > span, .comment-panel label > span { display: block; margin-bottom: 5px; color: var(--muted); font-size: 8px; }
.table-identity input, .definition-card input, .definition-card select, .definition-card textarea, .option-panel input, .comment-panel input, .comment-panel textarea { width: 100%; min-height: 42px; border: 1px solid var(--line); border-radius: 6px; outline: 0; background: var(--field); padding: 0 10px; color: var(--ink); font: inherit; font-size: 10px; }
.definition-card textarea, .comment-panel textarea { padding-top: 10px; resize: vertical; }
.table-identity input:focus, .definition-card :is(input,select,textarea):focus, .option-panel input:focus, .comment-panel :is(input,textarea):focus { border-color: var(--acid); box-shadow: 0 0 0 2px color-mix(in srgb, var(--acid) 10%, transparent); }
.definition-tabs { display: flex; overflow-x: auto; border-block: 1px solid var(--line); padding: 0 8px; scrollbar-width: none; }
.definition-tabs button { position: relative; flex: none; min-width: 66px; min-height: 43px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--muted); font-size: 9px; }
.definition-tabs button.active { border-bottom-color: var(--acid); color: var(--acid); font-weight: 700; }
.definition-tabs b { margin-left: 4px; border-radius: 8px; background: var(--accent-soft); padding: 1px 4px; color: var(--acid); font-size: 7px; }
.definition-body { overflow: auto; min-height: 0; padding: 12px 14px 90px; }
.tab-panel { animation: panel-in .15s ease-out; }
@keyframes panel-in { from { opacity: .45; transform: translateY(3px); } }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 11px; }
.panel-heading strong { font-size: 12px; }
.panel-heading p { margin: 3px 0 0; color: var(--muted); font-size: 8px; line-height: 1.5; }
.panel-heading button { flex: none; min-height: 34px; border: 1px solid color-mix(in srgb, var(--acid) 34%, var(--line)); border-radius: 5px; background: var(--accent-soft); padding: 0 10px; color: var(--acid); font-size: 8px; }
.column-grid-scroll { overflow-x: auto; border: 1px solid var(--line); border-radius: 7px; background: var(--panel); }
.column-grid { display: grid; min-width: 950px; grid-template-columns: 145px 130px 64px 64px 78px 70px 62px 62px 185px 90px; }
.column-grid-head { position: sticky; top: 0; z-index: 2; background: var(--accent-soft); color: var(--muted); }
.column-grid-head span { padding: 8px 7px; border-right: 1px solid var(--line); font-size: 7px; }
.column-grid-row { min-height: 48px; border-top: 1px solid var(--line); }
.column-grid-row > input, .column-grid-row > select { min-width: 0; border: 0; border-right: 1px solid var(--line); outline: 0; background: transparent; padding: 0 7px; color: var(--ink); font: inherit; font-size: 9px; }
.column-grid-row > input:focus, .column-grid-row > select:focus { background: var(--accent-soft); box-shadow: inset 0 -2px 0 var(--acid); }
.grid-check { display: grid; place-content: center; gap: 2px; border-right: 1px solid var(--line); color: var(--muted); font-size: 7px; text-align: center; }
.grid-check input { margin: auto; accent-color: var(--acid); }
.row-actions { display: flex; align-items: center; justify-content: center; gap: 2px; }
.row-actions button { width: 25px; height: 29px; border: 1px solid var(--line); background: var(--field); color: var(--muted); }
.row-actions button.danger, .remove-card { color: var(--danger); }
.row-actions button:disabled { opacity: .35; }
.definition-card { position: relative; display: grid; grid-template-columns: 1fr 1.4fr auto; gap: 9px; align-items: end; margin-bottom: 8px; border: 1px solid var(--line); border-radius: 7px; background: var(--panel); padding: 11px; }
.definition-card.foreign-grid { grid-template-columns: repeat(2, 1fr); }
.definition-card .remove-card { min-height: 42px; border: 1px solid color-mix(in srgb, var(--danger) 30%, var(--line)); background: transparent; padding: 0 10px; }
.inline-option { display: flex; min-height: 42px; align-items: center; gap: 8px; color: var(--muted); font-size: 9px; }
.inline-option input { width: auto; min-height: auto; accent-color: var(--acid); }
.inline-option span { margin: 0 !important; }
.empty-panel { border: 1px dashed var(--line); padding: 28px 12px; color: var(--faint); font-size: 9px; text-align: center; }
.option-panel { display: grid; gap: 9px; }
.option-panel > label:not(.inline-option), .comment-panel > label { display: block; }
.option-note { border-left: 2px solid var(--acid); background: var(--accent-soft); padding: 9px; color: var(--muted); font-size: 8px; line-height: 1.55; }
.column-comments { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; margin-top: 12px; }
.preview-heading { display: flex; justify-content: space-between; margin-bottom: 7px; color: var(--muted); font: 8px "Azeret Mono Variable", monospace; }
.preview-heading span { color: var(--acid); }
.preview-panel pre { overflow: auto; min-height: 280px; margin: 0; border: 1px solid var(--line); border-radius: 7px; background: color-mix(in srgb, var(--field) 94%, #000); padding: 13px; color: var(--ink); font: 9px/1.65 "Azeret Mono Variable", monospace; white-space: pre-wrap; }
.preview-panel > p { color: var(--muted); font-size: 8px; line-height: 1.6; }
.validation-errors { margin-bottom: 10px; border-left: 2px solid var(--danger); background: color-mix(in srgb, var(--danger) 5%, transparent); padding: 9px; color: var(--danger); font-size: 8px; }
.validation-errors strong { display: block; margin-bottom: 4px; }
.validation-errors p { margin: 2px 0; }
.definition-footer { position: fixed; z-index: 3; right: max(0px, calc((100vw - 880px) / 2)); bottom: 0; left: max(0px, calc((100vw - 880px) / 2)); display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 7px; border-top: 1px solid var(--line); background: color-mix(in srgb, var(--panel-raised) 95%, transparent); padding: 9px 14px calc(9px + var(--safe-bottom)); backdrop-filter: blur(12px); }
.definition-footer span { overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.definition-footer button { min-height: 42px; border: 1px solid var(--acid); border-radius: 6px; background: transparent; padding: 0 12px; color: var(--acid); font-size: 9px; }
.definition-footer button.primary { border: 0; background: var(--acid); color: #fff; font-weight: 700; }
.definition-footer button:disabled { opacity: .45; }
@media (max-width: 560px) { .definition-card, .definition-card.foreign-grid { grid-template-columns: 1fr; } .column-comments { grid-template-columns: 1fr; } .definition-footer { grid-template-columns: 1fr 1.35fr; } .definition-footer span { grid-column: 1 / -1; } }
</style>
