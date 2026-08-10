<script setup lang="ts">
type AppNavigationTarget = "connections" | "browse" | "query" | "settings";

defineProps<{ active: AppNavigationTarget }>();
const emit = defineEmits<{ select: [target: AppNavigationTarget] }>();
</script>

<template>
  <nav class="bottom-nav" aria-label="主导航">
    <button :class="{ active: active === 'connections' }" type="button" @click="emit('select', 'connections')">
      <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true">
        <ellipse cx="12" cy="6" rx="6.5" ry="2.5" />
        <path d="M5.5 6v6c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5V6M5.5 12v6c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5v-6" />
      </svg>
      <span>连接</span>
    </button>
    <button :class="{ active: active === 'browse' }" type="button" @click="emit('select', 'browse')">
      <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M3.5 6.5h6l2 2h9v10.5a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19Z" />
        <path d="M3.5 9h17" />
      </svg>
      <span>浏览</span>
    </button>
    <button :class="{ active: active === 'query' }" type="button" @click="emit('select', 'query')">
      <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 7 4 5-4 5M13 18h6" /></svg>
      <span>查询</span>
    </button>
    <button :class="{ active: active === 'settings' }" type="button" @click="emit('select', 'settings')">
      <svg class="nav-symbol" viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6 1.7 1.7 0 0 0 10 3v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z" />
      </svg>
      <span>设置</span>
    </button>
  </nav>
</template>
