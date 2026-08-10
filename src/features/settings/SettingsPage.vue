<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import { deleteSshProfile, listSshProfiles, saveSshProfile } from "@/lib/direct/sshProfiles";
import { deleteSshKey, importSshKeyFile, listSshKeys, saveSshKey } from "@/lib/direct/sshKeys";
import type { MobileSshKeyDraft, MobileSshKeySummary, MobileSshProfileDraft, MobileSshProfileSummary } from "@/lib/mobileTypes";

type SettingsView = "home" | "ssh-editor" | "ssh-key-editor";

const props = defineProps<{
  density: "standard" | "compact";
}>();

const emit = defineEmits<{
  setDensity: [value: "standard" | "compact"];
  checkUpdate: [];
}>();

const activeView = ref<SettingsView>("home");
const profiles = ref<MobileSshProfileSummary[]>([]);
const keys = ref<MobileSshKeySummary[]>([]);
const profilesLoading = ref(true);
const keysLoading = ref(true);
const errorMessage = ref("");
const saving = ref(false);
const editorMessage = ref("");
const keyEditorMessage = ref("");
const importingKey = ref(false);
const actionMessage = ref("");
const pendingDelete = ref<{ kind: "profile"; value: MobileSshProfileSummary } | { kind: "key"; value: MobileSshKeySummary } | null>(null);
const draft = reactive<MobileSshProfileDraft>(blankProfile());
const keyDraft = reactive<MobileSshKeyDraft>(blankKey());

const pageTitle = computed(() => ({
  "ssh-editor": draft.id ? "编辑跳板机" : "新建跳板机",
  "ssh-key-editor": keyDraft.id ? "编辑 SSH 密钥" : "新建 SSH 密钥",
  home: "设置",
})[activeView.value]);

function blankProfile(): MobileSshProfileDraft {
  return { name: "", host: "", port: 22, username: "", hostKeyFingerprint: "", authMethod: "password", password: "", keyId: "" };
}

function blankKey(): MobileSshKeyDraft {
  return { name: "", privateKey: "", privateKeyPassphrase: "" };
}

function openView(view: SettingsView) {
  activeView.value = view;
  errorMessage.value = "";
  window.scrollTo({ top: 0, behavior: "auto" });
}

function goBack() {
  activeView.value = "home";
}

async function loadKeys() {
  keysLoading.value = true;
  errorMessage.value = "";
  try {
    keys.value = await listSshKeys();
  } catch (error) {
    keys.value = [];
    errorMessage.value = error instanceof Error ? error.message : "读取 SSH 密钥失败";
  } finally {
    keysLoading.value = false;
  }
}

async function loadProfiles() {
  profilesLoading.value = true;
  errorMessage.value = "";
  try {
    profiles.value = await listSshProfiles();
  } catch (error) {
    profiles.value = [];
    errorMessage.value = error instanceof Error ? error.message : "读取 SSH 配置失败";
  } finally {
    profilesLoading.value = false;
  }
}

function openCreate() {
  Object.assign(draft, blankProfile());
  editorMessage.value = "";
  openView("ssh-editor");
}

function openEdit(profile: MobileSshProfileSummary) {
  Object.assign(draft, {
    id: profile.id,
    name: profile.name,
    host: profile.host,
    port: profile.port,
    username: profile.username,
    hostKeyFingerprint: profile.hostKeyFingerprint,
    authMethod: profile.authMethod,
    password: "",
    keyId: profile.keyId,
  });
  editorMessage.value = profile.authMethod === "password" && profile.hasPassword
    ? "密码已安全保存，留空将继续使用原密码。"
    : "";
  openView("ssh-editor");
}

function openCreateKey() {
  Object.assign(keyDraft, blankKey());
  keyEditorMessage.value = "";
  openView("ssh-key-editor");
}

function openEditKey(key: MobileSshKeySummary) {
  Object.assign(keyDraft, { id: key.id, name: key.name, privateKey: "", privateKeyPassphrase: "" });
  keyEditorMessage.value = `已保存 ${key.keyType} 密钥（${key.fingerprint}），私钥留空将保留原值。`;
  openView("ssh-key-editor");
}

async function copyFingerprint(key: MobileSshKeySummary) {
  try {
    await navigator.clipboard.writeText(key.fingerprint);
    actionMessage.value = `已复制“${key.name}”的指纹`;
  } catch {
    actionMessage.value = "复制失败，请长按指纹手动复制";
  }
}

function explainProfileTest(profile: MobileSshProfileSummary) {
  actionMessage.value = `“${profile.name}”需在数据库连接编辑页测试完整 SSH 链路`;
}

async function submitKey() {
  keyEditorMessage.value = "";
  if (!keyDraft.name.trim() || (!keyDraft.id && !keyDraft.privateKey.trim())) {
    keyEditorMessage.value = "请填写密钥名称，并粘贴私钥或使用系统文件选择器导入。";
    return;
  }
  saving.value = true;
  try {
    await saveSshKey({ ...keyDraft });
    await Promise.all([loadKeys(), loadProfiles()]);
    activeView.value = "home";
  } catch (error) {
    keyEditorMessage.value = error instanceof Error ? error.message : "保存 SSH 密钥失败";
  } finally {
    saving.value = false;
  }
}

async function importKey() {
  keyEditorMessage.value = "";
  if (!keyDraft.name.trim()) {
    keyEditorMessage.value = "请先填写密钥名称。";
    return;
  }
  importingKey.value = true;
  try {
    await importSshKeyFile(keyDraft.name, keyDraft.privateKeyPassphrase, keyDraft.id);
    await Promise.all([loadKeys(), loadProfiles()]);
    activeView.value = "home";
  } catch (error) {
    keyEditorMessage.value = error instanceof Error ? error.message : "导入 SSH 密钥失败";
  } finally {
    importingKey.value = false;
  }
}

async function removeKey(key: MobileSshKeySummary) {
  pendingDelete.value = { kind: "key", value: key };
}

async function confirmDelete() {
  const target = pendingDelete.value;
  if (!target) return;
  errorMessage.value = "";
  try {
    if (target.kind === "key") {
      await deleteSshKey(target.value.id);
      await loadKeys();
    } else {
      await deleteSshProfile(target.value.id);
      await loadProfiles();
    }
    pendingDelete.value = null;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : target.kind === "key" ? "删除 SSH 密钥失败" : "删除 SSH 配置失败";
  }
}

async function submitProfile() {
  editorMessage.value = "";
  if (!draft.name.trim() || !draft.host.trim() || !draft.username.trim() || !draft.port) {
    editorMessage.value = "请填写配置名称、SSH 主机、端口和用户名。";
    return;
  }
  saving.value = true;
  try {
    await saveSshProfile({ ...draft });
    await loadProfiles();
    activeView.value = "home";
  } catch (error) {
    editorMessage.value = error instanceof Error ? error.message : "保存 SSH 配置失败";
  } finally {
    saving.value = false;
  }
}

async function removeProfile(profile: MobileSshProfileSummary) {
  pendingDelete.value = { kind: "profile", value: profile };
}

function handleBack() {
  if (pendingDelete.value) {
    pendingDelete.value = null;
    return true;
  }
  if (activeView.value === "home") return false;
  goBack();
  return true;
}

defineExpose({ handleBack });

onMounted(async () => {
  await Promise.all([loadProfiles(), loadKeys()]);
});
</script>

<template>
  <div class="settings-page" :class="`density-${density}`">
    <template v-if="activeView === 'home'">
      <main class="settings-home">
        <header class="settings-hero">
          <h1>安全与连接</h1>
          <p>集中管理 SSH 密钥和跳板机，创建数据库连接时直接选择。</p>
        </header>

        <section class="vault-status">
          <span class="vault-status-icon">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5 6v5c0 4.4 2.8 7.8 7 10 4.2-2.2 7-5.6 7-10V6Z" /><path d="M9.5 12.3 11.2 14l3.5-4" /></svg>
          </span>
          <span><strong>本机安全存储已启用</strong><small>私钥和口令由 Android Keystore 加密；界面只显示名称和指纹。</small></span>
        </section>

        <section class="resource-section" aria-labelledby="ssh-key-title">
          <header><div><h2 id="ssh-key-title">SSH 密钥</h2><span>{{ keys.length }} 个本机密钥</span></div><button type="button" @click="openCreateKey"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>添加密钥</button></header>
          <div class="resource-list">
            <article v-for="key in keys" :key="key.id" class="resource-card">
              <div class="resource-main"><span class="resource-icon key"><svg viewBox="0 0 24 24"><circle cx="8" cy="12" r="4" /><path d="m12 12 8-8M16 8l2 2M18 6l2 2" /></svg></span><span><strong>{{ key.name }}</strong><code>{{ key.keyType }} · {{ key.fingerprint }}</code></span><em>{{ key.usageCount ? `${key.usageCount} 个连接` : "未使用" }}</em></div>
              <footer class="resource-actions"><button class="primary" type="button" @click="copyFingerprint(key)">复制指纹</button><button type="button" @click="openEditKey(key)">编辑</button><button class="danger" type="button" @click="removeKey(key)">删除</button></footer>
            </article>
            <button v-if="!keysLoading && !keys.length" class="resource-empty" type="button" @click="openCreateKey">尚未保存密钥，点击添加</button>
            <div v-if="keysLoading" class="resource-empty">正在读取加密密钥库…</div>
          </div>
        </section>

        <section class="resource-section" aria-labelledby="ssh-host-title">
          <header><div><h2 id="ssh-host-title">SSH 跳板机</h2><span>{{ profiles.length }} 个已保存跳板机</span></div><button type="button" @click="openCreate"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>添加跳板机</button></header>
          <div class="resource-list">
            <article v-for="profile in profiles" :key="profile.id" class="resource-card">
              <div class="resource-main"><span class="resource-icon host"><svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="6" rx="1" /><rect x="4" y="13" width="16" height="6" rx="1" /><path d="M7 8h.01M7 16h.01" /></svg></span><span><strong>{{ profile.name }}</strong><code>{{ profile.username }}@{{ profile.host }}:{{ profile.port }}</code></span><em>{{ profile.authMethod === "private-key" ? "密钥认证" : "密码认证" }}</em></div>
              <footer class="resource-actions"><button class="primary" type="button" @click="explainProfileTest(profile)">测试连接</button><button type="button" @click="openEdit(profile)">编辑</button><button class="danger" type="button" @click="removeProfile(profile)">删除</button></footer>
            </article>
            <button v-if="!profilesLoading && !profiles.length" class="resource-empty" type="button" @click="openCreate">尚未保存跳板机，点击添加</button>
            <div v-if="profilesLoading" class="resource-empty">正在读取安全配置…</div>
          </div>
        </section>
        <p v-if="actionMessage" class="action-feedback" role="status">{{ actionMessage }}</p>
        <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>
      </main>
    </template>

    <section v-else class="settings-detail">
      <header class="detail-toolbar">
        <button type="button" aria-label="返回设置" @click="goBack"><svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6" /></svg></button>
        <h2>{{ pageTitle }}</h2><span></span>
      </header>

      <main v-if="activeView === 'ssh-editor'" class="detail-content">
        <form class="ssh-form" @submit.prevent="submitProfile">
          <p class="detail-intro">配置保存后可被多个数据库连接引用，修改会自动应用到引用它的连接。</p>
          <div class="form-grid">
            <label class="wide"><span>配置名称</span><input v-model="draft.name" placeholder="例如：开发跳板机" /></label>
            <label class="wide"><span>SSH 主机</span><input v-model="draft.host" autocapitalize="none" placeholder="bastion.example.com" /></label>
            <label><span>端口</span><input v-model.number="draft.port" type="number" min="1" max="65535" /></label>
            <label><span>用户名</span><input v-model="draft.username" autocapitalize="none" autocomplete="username" /></label>
            <label class="wide"><span>主机密钥 SHA256 指纹</span><input v-model="draft.hostKeyFingerprint" autocapitalize="none" placeholder="建议生产环境填写" /></label>
            <label class="wide"><span>认证方式</span><select v-model="draft.authMethod"><option value="password">密码</option><option value="private-key">私钥</option></select></label>
            <label v-if="draft.authMethod === 'password'" class="wide"><span>SSH 密码</span><input v-model="draft.password" type="password" autocomplete="new-password" :placeholder="draft.id ? '留空则继续使用已保存密码' : '请输入 SSH 密码'" /></label>
            <label v-else class="wide"><span>SSH 密钥</span><select v-model="draft.keyId"><option value="" disabled>{{ keys.length ? "请选择密钥" : "请先在设置中新增 SSH 密钥" }}</option><option v-for="key in keys" :key="key.id" :value="key.id">{{ key.name }} · {{ key.keyType }}</option></select></label>
          </div>
          <p class="vault-note">密码不会返回 WebView；独立 SSH 密钥可供多个跳板机复用，引用中的密钥无法被误删。</p>
          <p v-if="editorMessage" class="error-message">{{ editorMessage }}</p>
          <button class="save-button" :disabled="saving" type="submit">{{ saving ? "保存中…" : "保存 SSH 配置" }}</button>
        </form>
      </main>

      <main v-else-if="activeView === 'ssh-key-editor'" class="detail-content">
        <form class="ssh-form" @submit.prevent="submitKey">
          <p class="detail-intro">推荐使用系统文件选择器导入，文件内容会直接交给 Android 原生保险箱，不经过 WebView。</p>
          <div class="form-grid">
            <label class="wide"><span>密钥名称</span><input v-model="keyDraft.name" placeholder="例如：公司开发环境密钥" /></label>
            <label class="wide"><span>私钥口令</span><input v-model="keyDraft.privateKeyPassphrase" type="password" autocomplete="new-password" :placeholder="keyDraft.id ? '留空则继续使用已保存口令' : '可选；加密私钥需要填写'" /></label>
            <div class="wide import-row"><button type="button" :disabled="importingKey" @click="importKey">{{ importingKey ? "等待选择…" : keyDraft.id ? "从文件替换私钥" : "从 Android 文件导入" }}</button><span>推荐</span></div>
            <label class="wide"><span>或粘贴 OpenSSH / PEM 私钥</span><textarea v-model="keyDraft.privateKey" rows="9" autocapitalize="none" :placeholder="keyDraft.id ? '留空则保留已保存私钥' : '-----BEGIN OPENSSH PRIVATE KEY-----'"></textarea></label>
          </div>
          <p class="vault-note">保存时会校验私钥格式与口令，并计算公钥 SHA256 指纹；列表和编辑页永远不会回显私钥。</p>
          <p v-if="keyEditorMessage" class="error-message">{{ keyEditorMessage }}</p>
          <button class="save-button" :disabled="saving" type="submit">{{ saving ? "保存中…" : "保存 SSH 密钥" }}</button>
        </form>
      </main>

    </section>
    <ConfirmDialog
      :open="Boolean(pendingDelete)"
      :title="pendingDelete?.kind === 'key' ? '删除 SSH 密钥？' : '删除 SSH 跳板机？'"
      :description="pendingDelete ? `此操作会删除“${pendingDelete.value.name}”，且无法撤销。` : ''"
      confirm-label="删除"
      tone="danger"
      @cancel="pendingDelete = null"
      @confirm="confirmDelete"
    />
  </div>
</template>

<style scoped>
.settings-page { width: 100%; min-width: 0; max-width: 100%; min-height: 100dvh; padding-bottom: var(--page-bottom-offset); color: var(--ink); font-family: "PingFang SC", "Microsoft YaHei", sans-serif; }
button { -webkit-tap-highlight-color: transparent; }
.settings-toolbar, .detail-toolbar { display: grid; min-height: 74px; grid-template-columns: 42px 1fr 42px; align-items: center; border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--panel) 95%, transparent); padding: 4px 16px; }
.settings-toolbar { min-height: 58px; grid-template-columns: 1fr auto; align-items: center; border-bottom: 0; padding: 5px 18px 4px; }
.settings-brand { display: grid; gap: 1px; }.settings-brand strong { font-family: "Azeret Mono Variable", monospace; font-size: 22px; font-weight: 760; line-height: 1.05; letter-spacing: -.04em; }.settings-brand span { color: var(--muted); font-size: 12px; line-height: 1.25; }
.toolbar-actions { display: flex; align-items: center; gap: 6px; }
.settings-toolbar button, .detail-toolbar button { display: grid; width: 34px; height: 34px; place-items: center; border: 0; background: transparent; color: var(--muted); }
.settings-toolbar svg, .detail-toolbar svg { width: 21px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.settings-home { width: 100%; max-width: var(--content-max-width); margin: 0 auto; padding: var(--space-3) var(--page-inline); }
.settings-group { overflow: hidden; margin-bottom: 18px; border: 1px solid color-mix(in srgb, var(--line) 96%, transparent); border-radius: 7px; background: var(--panel); box-shadow: 0 5px 18px rgba(23, 32, 51, .025); }
.settings-group.single { margin-bottom: 9px; }
.settings-group.density-setting { margin-bottom: 18px; }
.menu-row { display: grid; width: 100%; min-height: 54px; grid-template-columns: 38px minmax(0, 1fr) auto 12px; align-items: center; gap: 8px; border: 0; border-bottom: 1px solid var(--line); background: transparent; padding: 7px 12px; text-align: left; }
.menu-row:last-child { border-bottom: 0; }.menu-icon { display: grid; width: 30px; height: 30px; place-items: center; color: var(--acid); }.menu-icon svg { width: 22px; fill: none; stroke: currentColor; stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round; }.menu-row strong { font-size: 13px; font-weight: 590; }.menu-value { color: var(--muted); font-size: 12px; }.menu-value.current { color: var(--success); }.menu-row > i { color: var(--muted); font-family: sans-serif; font-size: 24px; font-style: normal; font-weight: 300; line-height: 1; }.group-caption { margin: 0 10px 22px; color: var(--muted); font-size: 10px; line-height: 1.6; }
.density-compact .menu-row { min-height: 50px; }.density-compact .settings-group { margin-bottom: 18px; }.density-compact .settings-group.single { margin-bottom: 9px; }.density-compact .settings-group.density-setting { margin-bottom: 18px; }.density-compact .group-caption { margin-bottom: 13px; }
.settings-detail { position: fixed; z-index: 8; inset: var(--safe-top) max(0px, calc((100vw - var(--content-max-width)) / 2)) 0; min-width: 0; overflow: auto; overscroll-behavior: contain; background: var(--page-background); animation: detail-in .2s cubic-bezier(.22, 1, .36, 1); }
@keyframes detail-in { from { opacity: .72; transform: translateX(18px); } }
.detail-toolbar { position: sticky; z-index: 2; top: 0; grid-template-columns: 42px 1fr 42px; padding: 4px 8px; }.detail-toolbar h2 { margin: 0; font-size: 18px; text-align: center; }.detail-toolbar svg { width: 24px; }
.detail-content { width: 100%; min-width: 0; max-width: var(--content-max-width); margin: 0 auto; padding: var(--space-4) var(--page-inline) var(--page-bottom-offset); }.detail-intro { margin: 0 2px 14px; color: var(--muted); font-size: 10px; line-height: 1.65; }
.choice-list { display: grid; gap: 10px; }.choice-list button { display: grid; min-height: 74px; grid-template-columns: 52px 1fr 24px; align-items: center; gap: 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--field); padding: 11px 14px; text-align: left; }.choice-list button.selected { border-color: color-mix(in srgb, var(--acid) 55%, var(--line)); background: var(--accent-soft); }.choice-list button > span:nth-child(2) { display: grid; gap: 4px; }.choice-list strong { font-size: 14px; }.choice-list small { color: var(--muted); font-size: 10px; }.choice-list i { visibility: hidden; color: var(--acid); font-style: normal; }.choice-list .selected i { visibility: visible; }
.density-preview { display: grid; width: 46px; gap: 5px; }.density-preview b { height: 7px; border-radius: 3px; background: color-mix(in srgb, var(--acid) 30%, var(--line)); }.density-preview.compact { gap: 3px; }.density-preview.compact b { height: 5px; }
.info-mark { display: grid; width: 72px; height: 72px; place-items: center; border-radius: 22px; background: var(--accent-soft); color: var(--acid); }.info-mark svg { width: 36px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 10px; }.form-grid label { display: grid; min-width: 0; cursor: text; }.form-grid .wide { grid-column: 1 / -1; }.form-grid label > span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 9px; }.form-grid input, .form-grid select, .form-grid textarea { width: 100%; min-height: 48px; border: 1px solid var(--line); border-radius: 7px; outline: 0; background: var(--field); padding: 0 12px; color: var(--ink); font-size: 12px; touch-action: manipulation; }.form-grid textarea { padding-top: 10px; resize: vertical; font-family: "Azeret Mono Variable", monospace; font-size: 10px; }.form-grid :is(input, select, textarea):focus { border-color: var(--acid); box-shadow: 0 0 0 2px color-mix(in srgb, var(--acid) 12%, transparent); }.vault-note { margin: 14px 0; border-left: 2px solid var(--acid); background: var(--accent-soft); padding: 9px 10px; color: var(--muted); font-size: 9px; line-height: 1.55; }.save-button { width: 100%; min-height: 49px; border: 0; border-radius: 7px; background: var(--acid); padding: 0; color: #fff; font-size: 10px; font-weight: 720; }
.import-row { display: flex; align-items: center; gap: 8px; }.import-row button { border: 1px solid color-mix(in srgb, var(--acid) 55%, var(--line)); border-radius: 7px; background: var(--accent-soft); padding: 9px 12px; color: var(--acid); font-size: 10px; font-weight: 700; }.import-row span { color: var(--success); font-size: 9px; }
.error-message { margin: 13px 0 0; color: var(--danger); font-size: 10px; line-height: 1.5; }
.info-page, .about-page { text-align: center; }.info-page .info-mark { margin: 18px auto; }.info-page h3, .about-page h3 { margin: 15px 0 7px; font-size: 21px; }.info-page > p, .about-page > p { margin: 0 auto 28px; max-width: 420px; color: var(--muted); font-size: 12px; line-height: 1.75; }.info-page section, .about-page section { margin-bottom: 10px; border: 1px solid var(--line); border-radius: 11px; background: var(--field); padding: 14px; text-align: left; }.info-page section strong { color: var(--acid); font-size: 12px; }.info-page section p { margin: 6px 0 0; color: var(--muted); font-size: 11px; line-height: 1.65; }.app-monogram { display: grid; width: 82px; height: 82px; place-items: center; margin: 28px auto 18px; border-radius: 24px; background: var(--acid); color: white; font-family: "Azeret Mono Variable", monospace; font-size: 20px; font-weight: 800; letter-spacing: .08em; }.about-page section { display: flex; justify-content: space-between; gap: 20px; }.about-page section span { color: var(--muted); font-size: 11px; }.about-page section strong { font-size: 11px; line-height: 1.6; text-align: right; }
/* Prototype-aligned mobile shell overrides. Business-specific form layout stays scoped here. */
.settings-group { border-color: var(--divider-color); border-radius: var(--radius-card); background: var(--card-background); box-shadow: var(--shadow); }
.settings-hero { margin: var(--space-2) 2px var(--space-4); }
.settings-hero > span { color: var(--primary); font: 700 9px/1.2 "Azeret Mono Variable", monospace; letter-spacing: .14em; }
.settings-hero h1 { margin: 6px 0 5px; color: var(--ink); font-size: 24px; line-height: 1.15; letter-spacing: -.025em; }
.settings-hero p { max-width: 34em; margin: 0; color: var(--muted); font-size: 11px; line-height: 1.6; }
.vault-status { display: grid; width: 100%; min-width: 0; grid-template-columns: 42px minmax(0, 1fr) 12px; align-items: center; gap: var(--space-3); margin-bottom: var(--space-5); border: 1px solid color-mix(in srgb, var(--success) 24%, var(--divider-color)); border-radius: var(--radius-card); background: color-mix(in srgb, var(--success) 6%, var(--card-background)); padding: 12px; text-align: left; box-shadow: 0 8px 24px rgba(21, 148, 85, .06); }
.vault-status-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: color-mix(in srgb, var(--success) 12%, var(--card-background)); color: var(--success); }
.vault-status-icon svg { width: 23px; height: 23px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.vault-status > span:nth-child(2) { display: grid; min-width: 0; gap: 4px; }
.vault-status strong { font-size: 12px; font-weight: 680; }
.vault-status small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.settings-section-label { margin: 0 4px 7px; color: var(--faint); font: 650 9px/1.2 "Azeret Mono Variable", monospace; letter-spacing: .08em; text-transform: uppercase; }
.settings-group { margin-bottom: var(--space-4); }
.menu-row { min-height: 58px; padding: 8px 13px; }
.menu-row:active, .vault-status:active { background: var(--surface-pressed); }
.group-caption { margin-top: -7px; }
.settings-home { padding-top: var(--space-5); }
.settings-hero { margin: 0 2px var(--space-4); }
.settings-hero h1 { margin-top: 0; }
.vault-status { margin-bottom: var(--space-6); }
.resource-section { margin-bottom: var(--space-6); }
.resource-section > header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-2); }
.resource-section > header > div { display: grid; min-width: 0; gap: 3px; }
.resource-section h2 { margin: 0; font-size: 15px; line-height: 1.2; }
.resource-section header span { color: var(--muted); font-size: 9px; }
.resource-section header button { display: inline-flex; min-height: 34px; flex: none; align-items: center; gap: 4px; border: 1px solid color-mix(in srgb, var(--primary) 28%, var(--divider-color)); border-radius: var(--radius-input); background: var(--primary-soft); padding: 0 10px; color: var(--primary); font-size: 9px; font-weight: 700; }
.resource-section header button svg { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; }
.resource-list { display: grid; overflow: hidden; border: 1px solid var(--divider-color); border-radius: var(--radius-card); background: var(--card-background); box-shadow: 0 7px 22px rgba(23, 32, 51, .04); }
.resource-card { display: grid; width: 100%; min-width: 0; min-height: 68px; grid-template-columns: 40px minmax(0, 1fr) 12px; align-items: center; gap: var(--space-3); border: 0; border-bottom: 1px solid var(--divider-color); background: transparent; padding: 10px 12px; text-align: left; }
.resource-card:last-child { border-bottom: 0; }
.resource-card:active { background: var(--surface-pressed); }
.resource-card > span:nth-child(2) { display: grid; min-width: 0; gap: 3px; }
.resource-card strong, .resource-card code, .resource-card small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-card strong { font-size: 12px; }
.resource-card code { color: var(--muted); font-size: 8px; }
.resource-card small { color: var(--success); font-size: 8px; }
.resource-icon { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 10px; }
.resource-icon.key { background: color-mix(in srgb, var(--warning) 11%, var(--card-background)); color: var(--warning); }
.resource-icon.host { background: color-mix(in srgb, var(--success) 10%, var(--card-background)); color: var(--success); }
.resource-icon svg { width: 21px; height: 21px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.resource-empty { min-height: 64px; border: 0; background: transparent; padding: var(--space-4); color: var(--muted); font-size: 10px; text-align: center; }

/* Screenshot reference: flat SSH resource manager. */
.settings-page { background: #fff; color: #172033; }
.settings-home { --settings-button-height: 44px; --settings-radius: 7px; padding: 8px 10px 8px; font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif; }
.settings-hero { margin: 0 0 16px; border-bottom: 1px solid #dbe4ef; padding: 0 0 14px; }
.settings-hero h1 { margin: 0 0 5px; font-size: 22px; font-weight: 760; letter-spacing: -.025em; }
.settings-hero p { color: #65728a; font-size: 9px; line-height: 1.55; }
.vault-status { min-height: 60px; grid-template-columns: 35px minmax(0, 1fr); gap: 10px; margin: 0 0 16px; border: 1px solid #a9caff; border-radius: var(--settings-radius); background: #f5f9ff; padding: 9px 10px; box-shadow: none; }
.vault-status-icon { width: 32px; height: 32px; border-radius: 0; background: transparent; color: #172033; }
.vault-status-icon svg { width: 18px; height: 18px; }
.vault-status strong { color: #172033; font-size: 11px; }
.vault-status small { color: #65728a; font-size: 8px; }
.resource-section { margin-bottom: 16px; }
.resource-section > header { margin-bottom: 8px; }
.resource-section h2 { color: #172033; font-size: 15px; font-weight: 750; }
.resource-section header span { color: #65728a; font-size: 9px; }
.resource-section header button { height: var(--settings-button-height); min-height: var(--settings-button-height); border: 1px solid #0878ff; border-radius: var(--settings-radius); background: #fff; padding: 0 10px; color: #0878ff; font-size: 9px; line-height: 1; }
.resource-list { gap: 9px; overflow: visible; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.resource-card { display:block; overflow:hidden; min-height:0; border:1px solid #dbe4ef; border-radius:var(--settings-radius); background:#fff; padding:0; box-shadow:inset 0 -1px 0 #dbe4ef; }
.resource-main { display: grid; height: 74px; min-height: 74px; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 10px; }
.resource-main > span:nth-child(2) { display: grid; min-width: 0; gap: 6px; }
.resource-main strong { overflow: hidden; color: #172033; font-size: 11px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.resource-main code { overflow: hidden; color: #65728a; font-family: "Azeret Mono Variable", ui-monospace, monospace; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.resource-main em { flex: none; border: 1px solid #d4deea; border-radius: 4px; background: #f8fafd; padding: 7px 6px; color: #475467; font-size: 8px; font-style: normal; white-space: nowrap; }
.resource-icon { width: 30px; height: 30px; border-radius: 0; background: transparent !important; color: #172033 !important; }
.resource-icon svg { width: 18px; height: 18px; stroke-width: 1.55; }
.resource-actions { display:grid; grid-template-columns:repeat(3,1fr); border-top:1px solid #dbe4ef; background:#fff; box-shadow:inset 0 -1px 0 #dbe4ef; }
.resource-actions button { width:100%; height:var(--settings-button-height); min-height:var(--settings-button-height); border:0; border-radius:0; background:#fff; padding:0; color:#172033; font-size:9px; font-weight:650; line-height:1; box-shadow:inset 0 -1px 0 #dbe4ef; }
.resource-actions button + button { border-left: 1px solid #dbe4ef; }
.resource-actions button.primary { color: #0878ff; }
.resource-actions button.danger { color: #ef3340; }
.resource-actions button:active { background: #f4f7fb; }
.resource-empty { border: 1px dashed #dbe4ef; border-radius: 7px; }
.action-feedback { position: sticky; z-index: 2; bottom: 8px; margin: 0; border-radius: 6px; background: #172033; padding: 10px 12px; color: #fff; font-size: 9px; box-shadow: 0 10px 28px rgba(23, 32, 51, .2); }
@media (max-width: 360px) {
  .settings-home { padding-right: 6px; padding-left: 6px; }
  .resource-section header button { padding: 0 8px; }
  .resource-main { grid-template-columns: 30px minmax(0, 1fr) auto; gap: 7px; padding: 8px; }
}
@media (min-width: 431px) { .settings-detail { border-right: 1px solid var(--line); border-left: 1px solid var(--line); } }
</style>
