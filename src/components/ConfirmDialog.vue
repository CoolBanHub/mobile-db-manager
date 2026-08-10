<script setup lang="ts">
withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    description: string;
    confirmLabel?: string;
    cancelLabel?: string;
    tone?: "default" | "danger";
    busy?: boolean;
  }>(),
  { confirmLabel: "确认", cancelLabel: "取消", tone: "default", busy: false },
);

const emit = defineEmits<{ cancel: []; confirm: [] }>();
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="ui-dialog-backdrop" role="presentation" @click.self="emit('cancel')">
      <section class="ui-dialog" :data-tone="tone" role="alertdialog" aria-modal="true" :aria-label="title">
        <span class="ui-dialog-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 3 3 7v5c0 4.5 3 7.7 9 9 6-1.3 9-4.5 9-9V7Z" /><path d="M12 8v5M12 17h.01" /></svg>
        </span>
        <h2>{{ title }}</h2>
        <p>{{ description }}</p>
        <slot />
        <footer>
          <button type="button" :disabled="busy" @click="emit('cancel')">{{ cancelLabel }}</button>
          <button class="primary" type="button" :disabled="busy" @click="emit('confirm')">{{ busy ? "处理中…" : confirmLabel }}</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>
