<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";

export interface InlineSelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

const props = withDefaults(defineProps<{
  modelValue: string;
  options: InlineSelectOption[];
  ariaLabel: string;
  placeholder?: string;
  disabled?: boolean;
}>(), { placeholder: "请选择", disabled: false });

const emit = defineEmits<{ "update:modelValue": [value: string]; change: [value: string] }>();
const root = ref<HTMLElement | null>(null);
const open = ref(false);
const selectedLabel = computed(() => props.options.find((option) => option.value === props.modelValue)?.label || props.placeholder);

function toggle() {
  if (!props.disabled) open.value = !open.value;
}

function choose(option: InlineSelectOption) {
  if (option.disabled) return;
  emit("update:modelValue", option.value);
  emit("change", option.value);
  open.value = false;
}

function closeOutside(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) open.value = false;
}

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === "Escape") open.value = false;
}

onMounted(() => {
  document.addEventListener("pointerdown", closeOutside);
  document.addEventListener("keydown", closeOnEscape);
});
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", closeOutside);
  document.removeEventListener("keydown", closeOnEscape);
});
</script>

<template>
  <div ref="root" class="inline-select" :class="{ open, disabled }">
    <button class="inline-select-trigger" type="button" :aria-label="ariaLabel" :aria-expanded="open" :disabled="disabled" @click="toggle">
      <span>{{ selectedLabel }}</span>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
    </button>
    <div v-if="open" class="inline-select-menu" role="listbox" :aria-label="ariaLabel">
      <button v-for="option in options" :key="option.value" :class="{ selected: option.value === modelValue }" :disabled="option.disabled" type="button" role="option" :aria-selected="option.value === modelValue" @click="choose(option)">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6" /></svg>
        <span>{{ option.label }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.inline-select { position:relative; min-width:0; }
.inline-select-trigger { display:flex; width:100%; min-height:44px; align-items:center; justify-content:space-between; gap:8px; border:1px solid var(--divider-color); border-radius:6px; background:var(--input-background); padding:0 11px; color:var(--text-primary); font:inherit; font-size:11px; text-align:left; }
.inline-select-trigger span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.inline-select-trigger svg { width:15px; height:15px; flex:none; fill:none; stroke:currentColor; stroke-width:2; stroke-linecap:round; stroke-linejoin:round; transition:transform 140ms ease; }
.open .inline-select-trigger { border-color:var(--primary); box-shadow:0 0 0 1px var(--primary); }
.open .inline-select-trigger svg { transform:rotate(180deg); }
.inline-select-menu { position:absolute; z-index:30; top:calc(100% + 5px); right:0; left:0; overflow:auto; max-height:260px; border:1px solid var(--divider-color); border-radius:8px; background:var(--card-background); padding:4px; box-shadow:0 12px 30px rgba(23,32,51,.16); }
.inline-select-menu button { display:grid; width:100%; min-height:36px; grid-template-columns:18px minmax(0,1fr); align-items:center; gap:5px; border:0; border-radius:5px; background:transparent; padding:0 7px; color:var(--text-primary); font:inherit; font-size:10px; text-align:left; }
.inline-select-menu button:active,.inline-select-menu button.selected { background:var(--primary-soft); color:var(--primary); }
.inline-select-menu svg { width:14px; height:14px; visibility:hidden; fill:none; stroke:currentColor; stroke-width:2.2; stroke-linecap:round; stroke-linejoin:round; }
.inline-select-menu button.selected svg { visibility:visible; }
.disabled { opacity:.5; }
</style>
