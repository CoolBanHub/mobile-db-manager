<script setup lang="ts">
defineProps<{
  title: string;
  subtitle?: string;
  canGoBack?: boolean;
  home?: boolean;
  production?: boolean;
  readOnly?: boolean;
  actionLabel?: string;
}>();

const emit = defineEmits<{ back: []; action: [] }>();
</script>

<template>
  <header class="app-header" :class="{ 'home-header': home }">
    <button v-if="canGoBack" class="header-back" type="button" aria-label="返回" @click="emit('back')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
    </button>
    <div class="app-title">
      <h1>{{ title }}</h1>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="production || readOnly" class="header-statuses" aria-label="连接状态">
      <span v-if="production" class="header-badge danger">生产</span>
      <span v-if="readOnly" class="header-readonly">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3 5.5 5.8v5.3c0 4.3 2.7 7.7 6.5 9.9 3.8-2.2 6.5-5.6 6.5-9.9V5.8Z" />
          <path d="M9.5 12.2 11.2 14l3.6-4" />
        </svg>
        只读
      </span>
    </div>
    <slot name="actions">
      <button v-if="actionLabel" class="header-action" type="button" :aria-label="actionLabel" @click="emit('action')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6" />
          <path d="m16 16 4 4" />
        </svg>
      </button>
    </slot>
  </header>
</template>
