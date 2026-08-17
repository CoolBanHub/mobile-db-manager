<script setup lang="ts">
const emit = defineEmits<{ create: [] }>();
</script>

<template>
  <section class="query-landing" aria-labelledby="query-landing-title">
    <div class="query-landing-content">
      <svg class="query-landing-icon" viewBox="0 0 24 24" aria-hidden="true">
        <rect x="4.5" y="3.5" width="15" height="17" rx="1.5" />
        <path d="m8.5 9 2.5 2.5L8.5 14M13.5 14H16" />
      </svg>
      <h1 id="query-landing-title">统一查询工作台</h1>
      <p>根据连接能力加载 SQL、键值、文档或前缀查询模板</p>
      <button type="button" @click="emit('create')">新建查询</button>
    </div>
  </section>
</template>

<style scoped>
.query-landing {
  display: grid;
  min-height: calc(100dvh - var(--page-top-safe) - var(--bottom-nav-height) - var(--safe-bottom) - var(--space-2));
  margin-inline: calc(var(--page-inline) * -1);
  place-items: center;
  background: var(--surface);
}

.query-landing-content {
  display: flex;
  align-items: center;
  flex-direction: column;
  width: 100%;
  padding: 0 18px;
  transform: translateY(-42px);
  text-align: center;
}

.query-landing-icon {
  width: 16px;
  height: 16px;
  margin-bottom: 14px;
  fill: none;
  stroke: var(--primary);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.6;
}

.query-landing h1 {
  margin: 0;
  color: #202939;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.query-landing p {
  margin: 13px 0 10px;
  color: #687489;
  font-size: 14px;
  font-weight: 550;
  line-height: 1.5;
}

.query-landing button {
  min-height: 26px;
  border: 1px solid var(--primary);
  border-radius: 0;
  background: var(--surface);
  padding: 2px 7px;
  color: var(--primary);
  font-size: 14px;
  font-weight: 650;
  line-height: 20px;
}

.query-landing button:active {
  background: var(--primary-soft);
}

@media (prefers-reduced-motion: no-preference) {
  .query-landing-content {
    animation: query-landing-enter 220ms ease-out both;
  }
}

@keyframes query-landing-enter {
  from {
    opacity: 0;
    transform: translateY(-34px);
  }
}
</style>
