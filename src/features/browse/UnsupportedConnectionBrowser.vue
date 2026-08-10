<script setup lang="ts">
import { databaseCapability } from "@/lib/databaseCapabilities";
import type { MobileConnectionSummary } from "@/lib/mobileTypes";

const props = defineProps<{ connection: MobileConnectionSummary }>();
</script>

<template>
  <section class="unsupported-browser">
    <span>NO MOBILE DATA BROWSER</span>
    <h4>{{ databaseCapability(props.connection.dbType).label }}</h4>
    <p>此连接类型暂未提供安卓端专用数据浏览器。连接配置仍可在返回后的“编辑”中维护，手机不会向它发送关系型 Schema 请求。</p>
    <dl>
      <div><dt>连接</dt><dd>{{ props.connection.name }}</dd></div>
      <div><dt>地址</dt><dd>{{ props.connection.host }}:{{ props.connection.port }}</dd></div>
      <div><dt>类型</dt><dd>{{ props.connection.dbType }}</dd></div>
    </dl>
  </section>
</template>

<style scoped>
.unsupported-browser { min-width: 0; max-width: 100%; margin-top: var(--space-3); border: 1px solid var(--divider-color); border-radius: var(--radius-card); background: var(--card-background); padding: var(--space-6) var(--space-4); box-shadow: var(--shadow); }
.unsupported-browser > span { color: var(--amber); font-size: 8px; letter-spacing: .14em; }
.unsupported-browser h4 { margin: 9px 0 8px; font-size: 20px; }
.unsupported-browser p { margin: 0; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 11px; line-height: 1.7; }
.unsupported-browser dl { margin: var(--space-5) 0 0; border-top: 1px solid var(--divider-color); }
.unsupported-browser dl div { display: grid; min-width: 0; grid-template-columns: 64px minmax(0, 1fr); gap: 10px; border-bottom: 1px solid var(--divider-color); padding: 10px 0; font-size: 10px; }
.unsupported-browser dt { color: var(--muted); }
.unsupported-browser dd { min-width: 0; overflow: hidden; margin: 0; text-overflow: ellipsis; white-space: nowrap; }
</style>
