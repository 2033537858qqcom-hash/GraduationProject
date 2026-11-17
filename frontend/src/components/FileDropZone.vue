<script setup lang="ts">
import { computed, ref } from 'vue';
import { FileSpreadsheet, FileText, Upload, X } from '@lucide/vue';

const props = withDefaults(
  defineProps<{
    accept?: string;
    disabled?: boolean;
    fileName?: string;
    title?: string;
    hint?: string;
    buttonLabel?: string;
    replaceLabel?: string;
    compact?: boolean;
    kind?: 'spreadsheet' | 'document';
  }>(),
  {
    accept: undefined,
    disabled: false,
    fileName: undefined,
    title: '选择或拖入文件到此处',
    hint: '',
    buttonLabel: '选择文件',
    replaceLabel: '更换文件',
    compact: false,
    kind: 'document',
  },
);

const emit = defineEmits<{
  change: [event: Event];
  clear: [];
}>();

const inputEl = ref<HTMLInputElement | null>(null);
const localName = ref('');
const dragging = ref(false);

const activeName = computed(() => (props.fileName !== undefined ? props.fileName : localName.value));

function pick() {
  if (props.disabled) return;
  inputEl.value?.click();
}

function handleChange(event: Event) {
  const target = event.target as HTMLInputElement;
  localName.value = target.files?.[0]?.name ?? '';
  emit('change', event);
}

function clear() {
  localName.value = '';
  if (inputEl.value) inputEl.value.value = '';
  emit('clear');
}

function onDragOver(event: DragEvent) {
  if (props.disabled) return;
  event.preventDefault();
  dragging.value = true;
}

function onDragLeave() {
  dragging.value = false;
}

function onDrop(event: DragEvent) {
  if (props.disabled) return;
  event.preventDefault();
  dragging.value = false;
  const file = event.dataTransfer?.files?.[0];
  if (!file || !inputEl.value) return;
  if (typeof DataTransfer !== 'undefined') {
    const dt = new DataTransfer();
    dt.items.add(file);
    inputEl.value.files = dt.files;
  }
  localName.value = file.name;
  inputEl.value.dispatchEvent(new Event('change', { bubbles: true }));
}

defineExpose({ clear, pick });
</script>

<template>
  <div
    class="file-drop-zone"
    :class="{
      'is-compact': compact,
      'is-dragging': dragging,
      'is-disabled': disabled,
      'has-file': Boolean(activeName),
    }"
    role="button"
    tabindex="0"
    :aria-disabled="disabled ? 'true' : undefined"
    @click="pick"
    @keydown.enter.prevent="pick"
    @keydown.space.prevent="pick"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <input
      ref="inputEl"
      type="file"
      class="sr-only file-drop-native"
      :accept="accept"
      :disabled="disabled"
      tabindex="-1"
      @change="handleChange"
      @click.stop
    />
    <span class="file-drop-icon" aria-hidden="true">
      <FileSpreadsheet v-if="activeName && kind === 'spreadsheet'" :size="compact ? 15 : 17" />
      <FileText v-else-if="activeName" :size="compact ? 15 : 17" />
      <Upload v-else :size="compact ? 15 : 17" />
    </span>
    <span class="file-drop-body">
      <strong class="file-drop-title">
        {{ activeName ? `已载入文件：${activeName}` : title }}
      </strong>
      <small v-if="hint" class="file-drop-hint">{{ hint }}</small>
    </span>
    <span class="file-drop-actions">
      <button
        v-if="activeName && !disabled"
        type="button"
        class="file-drop-clear"
        title="清除已选文件"
        @click.stop="clear"
      >
        <X :size="13" aria-hidden="true" />
        <span>清除</span>
      </button>
      <button type="button" class="button compact" :disabled="disabled" tabindex="-1" @click.stop="pick">
        {{ activeName ? replaceLabel : buttonLabel }}
      </button>
    </span>
  </div>
</template>

<style scoped>
.file-drop-zone {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-xs);
  background: var(--color-paper);
  cursor: pointer;
  transition:
    border-color var(--motion-fast) ease,
    background-color var(--motion-fast) ease;
}

.file-drop-zone.is-compact {
  padding: 11px 14px;
  gap: 12px;
}

.file-drop-zone:hover:not(.is-disabled),
.file-drop-zone.is-dragging:not(.is-disabled) {
  border-color: var(--color-brand);
  background: var(--color-surface-soft);
}

.file-drop-zone:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

.file-drop-zone.has-file {
  border-style: solid;
  border-color: var(--color-border-strong);
  background: var(--color-surface);
}

.file-drop-zone.is-disabled {
  opacity: 0.6;
  cursor: default;
}

.file-drop-native {
  width: 1px;
  height: 1px;
  padding: 0;
  border: 0;
  pointer-events: none;
}

.file-drop-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  background: var(--color-surface);
  color: var(--color-brand);
}

.file-drop-zone.is-compact .file-drop-icon {
  width: 32px;
  height: 32px;
}

.file-drop-body {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 3px;
}

.file-drop-title {
  font-size: var(--text-supporting);
  font-weight: 600;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-drop-hint {
  font-size: var(--text-meta);
  color: var(--color-text-secondary);
  line-height: 1.5;
}

.file-drop-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-drop-clear {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--text-meta);
  cursor: pointer;
}

.file-drop-clear:hover {
  border-color: var(--color-border-strong);
  color: var(--color-ink);
  background: var(--color-surface-hover);
}

@media (max-width: 640px) {
  .file-drop-zone {
    flex-wrap: wrap;
    align-items: flex-start;
  }

  .file-drop-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
