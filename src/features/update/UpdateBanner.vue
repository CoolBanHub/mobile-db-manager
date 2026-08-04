<script setup lang="ts">
import { computed } from "vue";
import type { AppUpdateInfo } from "@/lib/appUpdate";

type UpdateState = "checking" | "available" | "current" | "downloading" | "downloaded" | "error";

const props = defineProps<{
  state: UpdateState;
  info: AppUpdateInfo | null;
  message: string;
}>();

defineEmits<{
  check: [];
  download: [];
  dismiss: [];
}>();

const title = computed(() => {
  if (props.state === "available") return `发现新版本 ${props.info?.latestTag ?? ""}`.trim();
  if (props.state === "downloading") return "正在交给系统下载";
  if (props.state === "downloaded") return "下载任务已创建";
  if (props.state === "current") return "当前已是最新版本";
  if (props.state === "error") return "更新检查失败";
  return "正在检查更新";
});

const detail = computed(() => {
  if (props.message) return props.message;
  if (props.state === "available" && props.info) {
    return `${props.info.currentVersion || "当前版本"} -> ${props.info.latestVersion}`;
  }
  return "";
});

function formatSize(bytes: number) {
  if (!bytes) return "";
  const mb = bytes / 1024 / 1024;
  return `${mb.toFixed(mb >= 10 ? 0 : 1)} MB`;
}
</script>

<template>
  <aside class="update-banner" :data-state="state">
    <div class="update-mark" aria-hidden="true">
      <svg v-if="state === 'available' || state === 'downloading'" viewBox="0 0 24 24">
        <path d="M12 3v11" />
        <path d="m7 10 5 5 5-5" />
        <path d="M5 20h14" />
      </svg>
      <svg v-else-if="state === 'downloaded' || state === 'current'" viewBox="0 0 24 24">
        <path d="M20 6 9 17l-5-5" />
      </svg>
      <svg v-else-if="state === 'error'" viewBox="0 0 24 24">
        <path d="M12 8v5" />
        <path d="M12 17h.01" />
        <path d="M10.3 3.8 2.7 17a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z" />
      </svg>
      <span v-else>...</span>
    </div>
    <div class="update-copy">
      <h3>{{ title }}</h3>
      <p v-if="detail">{{ detail }}</p>
      <small v-if="state === 'available' && info">{{ info.apkName }} {{ formatSize(info.apkSize) }}</small>
    </div>
    <div class="update-actions">
      <button v-if="state === 'available'" class="update-primary" type="button" @click="$emit('download')">下载</button>
      <button v-if="state === 'error'" class="update-secondary" type="button" @click="$emit('check')">重试</button>
      <button class="update-dismiss" type="button" aria-label="关闭更新提示" @click="$emit('dismiss')">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
      </button>
    </div>
  </aside>
</template>
