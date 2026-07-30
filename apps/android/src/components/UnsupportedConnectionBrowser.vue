<script setup lang="ts">
import type { MobileConnectionSummary } from "../lib/mobileTypes";
import { databaseCapability } from "../lib/databaseCapabilities";

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
.unsupported-browser { margin-top: 14px; border: 1px solid var(--line); background: var(--panel); padding: 24px 18px; }
.unsupported-browser > span { color: var(--amber); font-size: 8px; letter-spacing: .14em; }
.unsupported-browser h4 { margin: 9px 0 8px; font-size: 20px; }
.unsupported-browser p { margin: 0; color: var(--muted); font-family: "PingFang SC", sans-serif; font-size: 11px; line-height: 1.7; }
.unsupported-browser dl { margin: 20px 0 0; border-top: 1px solid var(--line); }
.unsupported-browser dl div { display: grid; grid-template-columns: 64px 1fr; gap: 10px; border-bottom: 1px solid var(--line); padding: 10px 0; font-size: 10px; }
.unsupported-browser dt { color: var(--muted); }
.unsupported-browser dd { overflow: hidden; margin: 0; text-overflow: ellipsis; white-space: nowrap; }
</style>
