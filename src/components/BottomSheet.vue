<script setup lang="ts">
withDefaults(
  defineProps<{ open: boolean; title: string; description?: string; closeLabel?: string }>(),
  { description: "", closeLabel: "关闭" },
);

const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="ui-sheet-backdrop" role="presentation" @click.self="emit('close')">
      <section class="ui-sheet" role="dialog" aria-modal="true" :aria-label="title">
        <div class="ui-sheet-handle" aria-hidden="true"></div>
        <header class="ui-sheet-header">
          <div><h2>{{ title }}</h2><p v-if="description">{{ description }}</p></div>
          <button type="button" :aria-label="closeLabel" @click="emit('close')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
          </button>
        </header>
        <div class="ui-sheet-body"><slot /></div>
        <footer v-if="$slots.footer" class="ui-sheet-footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>
