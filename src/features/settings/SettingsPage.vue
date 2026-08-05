<script setup lang="ts">
import { App as CapacitorApp } from "@capacitor/app";
import { computed, onMounted, reactive, ref } from "vue";
import { deleteSshProfile, listSshProfiles, saveSshProfile } from "@/lib/direct/sshProfiles";
import type { MobileSshProfileDraft, MobileSshProfileSummary } from "@/lib/mobileTypes";

type SettingsView = "home" | "density" | "ssh" | "ssh-editor" | "privacy" | "about";

const props = defineProps<{
  density: "standard" | "compact";
}>();

const emit = defineEmits<{
  setDensity: [value: "standard" | "compact"];
  checkUpdate: [];
}>();

const activeView = ref<SettingsView>("home");
const profiles = ref<MobileSshProfileSummary[]>([]);
const profilesLoading = ref(true);
const errorMessage = ref("");
const saving = ref(false);
const editorMessage = ref("");
const appVersion = ref("0.1.0");
const draft = reactive<MobileSshProfileDraft>(blankProfile());

const pageTitle = computed(() => ({
  density: "界面密度",
  ssh: "SSH 跳板机",
  "ssh-editor": draft.id ? "编辑 SSH 配置" : "新建 SSH 配置",
  privacy: "隐私与安全",
  about: "关于 DBX",
  home: "设置",
})[activeView.value]);

function blankProfile(): MobileSshProfileDraft {
  return { name: "", host: "", port: 22, username: "", hostKeyFingerprint: "", authMethod: "password", password: "", privateKey: "", privateKeyPassphrase: "" };
}

function openView(view: SettingsView) {
  activeView.value = view;
  errorMessage.value = "";
  window.scrollTo({ top: 0, behavior: "auto" });
}

function goBack() {
  if (activeView.value === "ssh-editor") activeView.value = "ssh";
  else activeView.value = "home";
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
    privateKey: "",
    privateKeyPassphrase: "",
  });
  editorMessage.value = profile.authMethod === "password" && profile.hasPassword
    ? "密码已安全保存，留空将继续使用原密码。"
    : profile.hasPrivateKey ? "私钥已安全保存，留空将继续使用原私钥。" : "";
  openView("ssh-editor");
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
    activeView.value = "ssh";
  } catch (error) {
    editorMessage.value = error instanceof Error ? error.message : "保存 SSH 配置失败";
  } finally {
    saving.value = false;
  }
}

async function removeProfile(profile: MobileSshProfileSummary) {
  if (!window.confirm(`删除 SSH 配置“${profile.name}”？`)) return;
  errorMessage.value = "";
  try {
    await deleteSshProfile(profile.id);
    await loadProfiles();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "删除 SSH 配置失败";
  }
}

function handleBack() {
  if (activeView.value === "home") return false;
  goBack();
  return true;
}

defineExpose({ handleBack });

onMounted(async () => {
  await loadProfiles();
  try {
    appVersion.value = (await CapacitorApp.getInfo()).version;
  } catch {
    // 浏览器预览没有原生应用信息，保留构建时默认版本。
  }
});
</script>

<template>
  <div class="settings-page" :class="`density-${density}`">
    <template v-if="activeView === 'home'">
      <header class="settings-toolbar">
        <div class="settings-brand"><strong>DBX</strong><span>设置</span></div>
        <div class="toolbar-actions">
          <button type="button" aria-label="帮助与安全说明" @click="openView('privacy')"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M9.8 9a2.4 2.4 0 1 1 3.5 2.1c-.9.5-1.3 1-1.3 2M12 17h.01" /></svg></button>
          <button type="button" aria-label="关于 DBX" @click="openView('about')"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="5" r="1" fill="currentColor" stroke="none" /><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" /><circle cx="12" cy="19" r="1" fill="currentColor" stroke="none" /></svg></button>
        </div>
      </header>

      <main class="settings-home">
        <section class="settings-group single density-setting">
          <button class="menu-row" type="button" @click="openView('density')">
            <span class="menu-icon"><svg viewBox="0 0 24 24"><path d="M5 6h14M5 12h14M5 18h14" /></svg></span>
            <strong>界面密度</strong><span class="menu-value">{{ density === "compact" ? "紧凑" : "标准" }}</span><i>›</i>
          </button>
        </section>

        <section class="settings-group single">
          <button class="menu-row" type="button" @click="openView('ssh')">
            <span class="menu-icon"><svg viewBox="0 0 24 24"><ellipse cx="12" cy="6" rx="6" ry="2.5" /><path d="M6 6v6c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5V6M6 12v6c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5v-6" /><path d="m10 11 2 2 3-4" /></svg></span>
            <strong>SSH 跳板机</strong><span class="menu-value">{{ profiles.length ? `${profiles.length} 个配置` : "未配置" }}</span><i>›</i>
          </button>
        </section>
        <p class="group-caption">密码与私钥仅保存在 Android Keystore 加密保险箱中</p>

        <section class="settings-group">
          <button class="menu-row" type="button" @click="openView('privacy')">
            <span class="menu-icon"><svg viewBox="0 0 24 24"><path d="M12 3 5 6v5c0 4.4 2.8 7.8 7 10 4.2-2.2 7-5.6 7-10V6Z" /><path d="M9.5 12.3 11.2 14l3.5-4" /></svg></span>
            <strong>隐私与安全</strong><span class="menu-value"></span><i>›</i>
          </button>
          <button class="menu-row" type="button" @click="openView('about')">
            <span class="menu-icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /><path d="M12 10v7M12 7h.01" /></svg></span>
            <strong>关于 DBX</strong><span class="menu-value">{{ appVersion }}</span><i>›</i>
          </button>
        </section>
      </main>
    </template>

    <section v-else class="settings-detail">
      <header class="detail-toolbar">
        <button type="button" aria-label="返回设置" @click="goBack"><svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6" /></svg></button>
        <h2>{{ pageTitle }}</h2><span></span>
      </header>

      <main v-if="activeView === 'density'" class="detail-content">
        <p class="detail-intro">调整工具栏和导航的空间占用，不影响数据库内容。</p>
        <div class="choice-list density-list">
          <button :class="{ selected: density === 'standard' }" type="button" @click="emit('setDensity', 'standard')"><span class="density-preview"><b></b><b></b><b></b></span><span><strong>标准</strong><small>更舒适的触控间距</small></span><i>✓</i></button>
          <button :class="{ selected: density === 'compact' }" type="button" @click="emit('setDensity', 'compact')"><span class="density-preview compact"><b></b><b></b><b></b><b></b></span><span><strong>紧凑</strong><small>同屏显示更多内容</small></span><i>✓</i></button>
        </div>
      </main>

      <main v-else-if="activeView === 'ssh'" class="detail-content ssh-page">
        <div class="detail-heading"><div><h3>已保存配置</h3><p>数据库连接可直接选择这些跳板机。</p></div><button type="button" @click="openCreate">＋ 新建</button></div>
        <div v-if="profilesLoading" class="empty-state">正在读取安全配置…</div>
        <div v-else-if="!profiles.length" class="empty-state"><span>SSH</span><strong>尚未保存跳板机</strong><p>添加后可在多个数据库连接之间安全复用。</p><button type="button" @click="openCreate">添加配置</button></div>
        <article v-for="profile in profiles" :key="profile.id" class="profile-card" @click="openEdit(profile)">
          <span class="profile-icon">SSH</span><div><strong>{{ profile.name }}</strong><code>{{ profile.username }}@{{ profile.host }}:{{ profile.port }}</code><small>{{ profile.authMethod === "private-key" ? "私钥认证" : "密码认证" }} · 已加密</small></div><i>›</i>
          <button type="button" aria-label="删除配置" @click.stop="removeProfile(profile)">删除</button>
        </article>
        <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>
      </main>

      <main v-else-if="activeView === 'ssh-editor'" class="detail-content">
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
            <template v-else><label class="wide"><span>OpenSSH / PEM 私钥</span><textarea v-model="draft.privateKey" rows="7" autocapitalize="none" :placeholder="draft.id ? '留空则继续使用已保存私钥' : '-----BEGIN OPENSSH PRIVATE KEY-----'"></textarea></label><label class="wide"><span>私钥口令</span><input v-model="draft.privateKeyPassphrase" type="password" autocomplete="new-password" placeholder="可选；留空则沿用" /></label></template>
          </div>
          <p class="vault-note">密码、私钥和口令不会返回 WebView；编辑时留空由原生保险箱保留旧值。</p>
          <p v-if="editorMessage" class="error-message">{{ editorMessage }}</p>
          <button class="save-button" :disabled="saving" type="submit">{{ saving ? "保存中…" : "保存 SSH 配置" }}</button>
        </form>
      </main>

      <main v-else-if="activeView === 'privacy'" class="detail-content info-page">
        <div class="info-mark"><svg viewBox="0 0 24 24"><path d="M12 3 5 6v5c0 4.4 2.8 7.8 7 10 4.2-2.2 7-5.6 7-10V6Z" /><path d="M9.5 12.3 11.2 14l3.5-4" /></svg></div>
        <h3>数据留在你的设备</h3><p>应用从 Android 手机直接连接数据库，不经过 DBX 后端、桌面代理、账号系统或移动 API 网关。</p>
        <section><strong>Android Keystore</strong><p>数据库密码、连接串、代理密码、SSH 密码、私钥和口令使用 AES-GCM 加密保存。</p></section>
        <section><strong>写入保护</strong><p>只读连接会阻止写入；生产连接执行写入前需要再次输入完整连接名称。</p></section>
        <section><strong>主机身份</strong><p>生产 SSH 跳板机建议配置 SHA256 主机密钥指纹，并为数据库启用受信任 TLS 证书。</p></section>
      </main>

      <main v-else class="detail-content about-page">
        <div class="app-monogram">DBX</div><h3>Mobile DB Manager</h3><p>版本 {{ appVersion }}</p>
        <section><span>运行方式</span><strong>Android 原生直连</strong></section><section><span>支持数据库</span><strong>PostgreSQL · MySQL · SQL Server<br />Redis · MongoDB · etcd</strong></section><section><span>项目标识</span><strong>mobile-db-manager</strong></section>
        <button class="save-button" type="button" @click="emit('checkUpdate')">检查 GitHub Release 更新</button>
      </main>
    </section>
  </div>
</template>

<style scoped>
.settings-page { min-height: 100dvh; padding-bottom: 92px; color: var(--ink); font-family: "PingFang SC", "Microsoft YaHei", sans-serif; }
button { -webkit-tap-highlight-color: transparent; }
.settings-toolbar, .detail-toolbar { display: grid; min-height: 74px; grid-template-columns: 42px 1fr 42px; align-items: center; border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--panel) 95%, transparent); padding: 4px 16px; }
.settings-toolbar { min-height: 58px; grid-template-columns: 1fr auto; align-items: center; border-bottom: 0; padding: 5px 18px 4px; }
.settings-brand { display: grid; gap: 1px; }.settings-brand strong { font-family: "Azeret Mono Variable", monospace; font-size: 22px; font-weight: 760; line-height: 1.05; letter-spacing: -.04em; }.settings-brand span { color: var(--muted); font-size: 12px; line-height: 1.25; }
.toolbar-actions { display: flex; align-items: center; gap: 6px; }
.settings-toolbar button, .detail-toolbar button { display: grid; width: 34px; height: 34px; place-items: center; border: 0; background: transparent; color: var(--muted); }
.settings-toolbar svg, .detail-toolbar svg { width: 21px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.settings-home { padding: 14px 18px 12px; }
.settings-group { overflow: hidden; margin-bottom: 18px; border: 1px solid color-mix(in srgb, var(--line) 96%, transparent); border-radius: 7px; background: var(--panel); box-shadow: 0 5px 18px rgba(23, 32, 51, .025); }
.settings-group.single { margin-bottom: 9px; }
.settings-group.density-setting { margin-bottom: 18px; }
.menu-row { display: grid; width: 100%; min-height: 54px; grid-template-columns: 38px minmax(0, 1fr) auto 12px; align-items: center; gap: 8px; border: 0; border-bottom: 1px solid var(--line); background: transparent; padding: 7px 12px; text-align: left; }
.menu-row:last-child { border-bottom: 0; }.menu-icon { display: grid; width: 30px; height: 30px; place-items: center; color: var(--acid); }.menu-icon svg { width: 22px; fill: none; stroke: currentColor; stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round; }.menu-row strong { font-size: 13px; font-weight: 590; }.menu-value { color: var(--muted); font-size: 12px; }.menu-value.current { color: var(--success); }.menu-row > i { color: var(--muted); font-family: sans-serif; font-size: 24px; font-style: normal; font-weight: 300; line-height: 1; }.group-caption { margin: 0 10px 22px; color: var(--muted); font-size: 10px; line-height: 1.6; }
.density-compact .menu-row { min-height: 50px; }.density-compact .settings-group { margin-bottom: 18px; }.density-compact .settings-group.single { margin-bottom: 9px; }.density-compact .settings-group.density-setting { margin-bottom: 18px; }.density-compact .group-caption { margin-bottom: 13px; }
.settings-detail { position: fixed; z-index: 8; inset: var(--safe-top) 0 0; overflow: auto; background: var(--panel); animation: detail-in .2s cubic-bezier(.22, 1, .36, 1); }
@keyframes detail-in { from { opacity: .72; transform: translateX(18px); } }
.detail-toolbar { position: sticky; z-index: 2; top: 0; grid-template-columns: 42px 1fr 42px; padding: 4px 8px; }.detail-toolbar h2 { margin: 0; font-size: 18px; text-align: center; }.detail-toolbar svg { width: 24px; }
.detail-content { width: min(100%, 720px); margin: 0 auto; padding: 24px 16px calc(28px + var(--safe-bottom)); }.detail-intro { margin: 0 2px 17px; color: var(--muted); font-size: 12px; line-height: 1.7; }
.choice-list { display: grid; gap: 10px; }.choice-list button { display: grid; min-height: 74px; grid-template-columns: 52px 1fr 24px; align-items: center; gap: 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--field); padding: 11px 14px; text-align: left; }.choice-list button.selected { border-color: color-mix(in srgb, var(--acid) 55%, var(--line)); background: var(--accent-soft); }.choice-list button > span:nth-child(2) { display: grid; gap: 4px; }.choice-list strong { font-size: 14px; }.choice-list small { color: var(--muted); font-size: 10px; }.choice-list i { visibility: hidden; color: var(--acid); font-style: normal; }.choice-list .selected i { visibility: visible; }
.density-preview { display: grid; width: 46px; gap: 5px; }.density-preview b { height: 7px; border-radius: 3px; background: color-mix(in srgb, var(--acid) 30%, var(--line)); }.density-preview.compact { gap: 3px; }.density-preview.compact b { height: 5px; }
.info-mark { display: grid; width: 72px; height: 72px; place-items: center; border-radius: 22px; background: var(--accent-soft); color: var(--acid); }.info-mark svg { width: 36px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.detail-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }.detail-heading h3 { margin: 0 0 4px; font-size: 17px; }.detail-heading p { margin: 0; color: var(--muted); font-size: 10px; }.detail-heading > button, .empty-state button { border: 0; border-radius: 9px; background: var(--accent-soft); padding: 9px 12px; color: var(--acid); font-size: 11px; font-weight: 700; }
.profile-card { position: relative; display: grid; grid-template-columns: 45px 1fr 18px; align-items: center; gap: 11px; margin-bottom: 10px; border: 1px solid var(--line); border-radius: 12px; background: var(--field); padding: 12px 12px 34px; }.profile-icon { display: grid; width: 43px; height: 43px; place-items: center; border-radius: 11px; background: var(--accent-soft); color: var(--acid); font-family: "Azeret Mono Variable", monospace; font-size: 9px; font-weight: 800; }.profile-card > div { display: grid; min-width: 0; gap: 3px; }.profile-card strong { font-size: 13px; }.profile-card code { overflow: hidden; color: var(--muted); font-size: 10px; text-overflow: ellipsis; }.profile-card small { color: var(--acid); font-size: 9px; }.profile-card > i { color: var(--muted); font-size: 25px; font-style: normal; }.profile-card > button { position: absolute; right: 10px; bottom: 7px; border: 0; background: transparent; color: var(--danger); font-size: 9px; }
.empty-state { display: grid; justify-items: center; gap: 8px; padding: 54px 20px; color: var(--muted); text-align: center; font-size: 11px; }.empty-state > span { display: grid; width: 58px; height: 58px; place-items: center; border-radius: 17px; background: var(--accent-soft); color: var(--acid); font-family: "Azeret Mono Variable", monospace; }.empty-state strong { color: var(--ink); font-size: 14px; }.empty-state p { margin: 0 0 8px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.form-grid label { display: grid; gap: 6px; }.form-grid .wide { grid-column: 1 / -1; }.form-grid label > span { color: var(--muted); font-size: 11px; }.form-grid input, .form-grid select, .form-grid textarea { min-width: 0; border: 1px solid var(--line); border-radius: 10px; outline: 0; background: var(--field); padding: 12px; color: var(--ink); font-size: 13px; }.form-grid textarea { resize: vertical; font-family: "Azeret Mono Variable", monospace; font-size: 10px; }.form-grid :is(input, select, textarea):focus { border-color: var(--acid); }.vault-note { margin: 16px 0; border-left: 2px solid var(--acid); background: var(--accent-soft); padding: 11px 12px; color: var(--muted); font-size: 10px; line-height: 1.6; }.save-button { width: 100%; border: 0; border-radius: 11px; background: var(--acid); padding: 14px; color: white; font-weight: 700; }
.error-message { margin: 12px 0; color: var(--danger); font-size: 11px; line-height: 1.55; }
.info-page, .about-page { text-align: center; }.info-page .info-mark { margin: 18px auto; }.info-page h3, .about-page h3 { margin: 15px 0 7px; font-size: 21px; }.info-page > p, .about-page > p { margin: 0 auto 28px; max-width: 420px; color: var(--muted); font-size: 12px; line-height: 1.75; }.info-page section, .about-page section { margin-bottom: 10px; border: 1px solid var(--line); border-radius: 11px; background: var(--field); padding: 14px; text-align: left; }.info-page section strong { color: var(--acid); font-size: 12px; }.info-page section p { margin: 6px 0 0; color: var(--muted); font-size: 11px; line-height: 1.65; }.app-monogram { display: grid; width: 82px; height: 82px; place-items: center; margin: 28px auto 18px; border-radius: 24px; background: var(--acid); color: white; font-family: "Azeret Mono Variable", monospace; font-size: 20px; font-weight: 800; letter-spacing: .08em; }.about-page section { display: flex; justify-content: space-between; gap: 20px; }.about-page section span { color: var(--muted); font-size: 11px; }.about-page section strong { font-size: 11px; line-height: 1.6; text-align: right; }
@media (min-width: 720px) { .settings-detail { right: calc((100vw - 720px) / 2); left: calc((100vw - 720px) / 2); border-right: 1px solid var(--line); border-left: 1px solid var(--line); } }
</style>
