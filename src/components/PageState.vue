<script setup lang="ts">
withDefaults(
  defineProps<{
    kind?: "loading" | "empty" | "error";
    title: string;
    description?: string;
    actionLabel?: string;
    compact?: boolean;
  }>(),
  { kind: "empty", description: "", actionLabel: "", compact: false },
);

const emit = defineEmits<{ action: [] }>();
</script>

<template>
  <section class="page-state" :class="[{ compact }, `is-${kind}`]" :aria-busy="kind === 'loading'" :role="kind === 'error' ? 'alert' : 'status'">
    <span class="page-state-icon" aria-hidden="true">
      <svg v-if="kind === 'loading'" viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.3-5.7" /></svg>
      <svg v-else-if="kind === 'error'" viewBox="0 0 24 24"><path d="M12 3 2.8 19h18.4ZM12 9v4M12 17h.01" /></svg>
      <svg v-else viewBox="0 0 24 24"><path d="M4 7.5h6l2 2h8v9H4Z" /><path d="M4 10h16" /></svg>
    </span>
    <h2>{{ title }}</h2>
    <p v-if="description">{{ description }}</p>
    <button v-if="actionLabel" type="button" @click="emit('action')">{{ actionLabel }}</button>
  </section>
</template>
