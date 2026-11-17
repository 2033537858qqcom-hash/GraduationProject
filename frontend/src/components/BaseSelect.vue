<script setup lang="ts" generic="T extends string | number | boolean">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { ChevronDown, Check } from '@lucide/vue';

export interface SelectOption<Value = string | number | boolean> {
  value: Value;
  label: string;
  disabled?: boolean;
  description?: string;
}

const props = withDefaults(
  defineProps<{
    modelValue?: T | null;
    options: (SelectOption<T> | T)[];
    placeholder?: string;
    disabled?: boolean;
    compact?: boolean;
    id?: string;
    name?: string;
    ariaLabel?: string;
  }>(),
  {
    modelValue: null,
    placeholder: '请选择',
    disabled: false,
    compact: false,
    id: undefined,
    name: undefined,
    ariaLabel: undefined,
  },
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: T): void;
  (e: 'change', value: T): void;
}>();

const isOpen = ref(false);
const canTeleport = ref(false);
const teleportTarget = ref<string | Element>('body');
const containerRef = ref<HTMLElement | null>(null);
const listboxRef = ref<HTMLElement | null>(null);
const activeIndex = ref<number>(-1);
const popoverAbove = ref(false);
const popoverStyle = ref<Record<string, string>>({});

const normalizedOptions = computed<SelectOption<T>[]>(() => {
  return props.options.map(item => {
    if (typeof item === 'object' && item !== null && 'value' in item && 'label' in item) {
      return item as SelectOption<T>;
    }
    return {
      value: item as T,
      label: String(item),
    };
  });
});

const selectedOption = computed(() => {
  return normalizedOptions.value.find(opt => String(opt.value) === String(props.modelValue));
});

const displayText = computed(() => {
  if (selectedOption.value) return selectedOption.value.label;
  return props.placeholder || '请选择';
});

const isPlaceholder = computed(() => !selectedOption.value);

function toggleDropdown() {
  if (props.disabled) return;
  if (isOpen.value) {
    closeDropdown();
  } else {
    openDropdown();
  }
}

function updatePosition() {
  if (typeof window === 'undefined' || !containerRef.value) return;
  const rect = containerRef.value.getBoundingClientRect();
  const margin = 12;
  const gap = 5;
  const estimatedHeight = Math.min(normalizedOptions.value.length * 40 + 14, 272);
  const spaceBelow = window.innerHeight - rect.bottom - margin;
  const spaceAbove = rect.top - margin;
  const placeAbove = spaceBelow < estimatedHeight && spaceAbove > spaceBelow;
  popoverAbove.value = placeAbove;

  const width = Math.max(rect.width, 180);
  const maxWidth = Math.min(window.innerWidth - margin * 2, 520);
  const boundedWidth = Math.min(width, maxWidth);
  const left = Math.max(margin, Math.min(rect.left, window.innerWidth - boundedWidth - margin));
  const maxHeight = Math.max(120, Math.min(260, (placeAbove ? spaceAbove : spaceBelow) - gap - 12));

  popoverStyle.value = {
    position: 'fixed',
    left: `${left}px`,
    width: `${boundedWidth}px`,
    maxWidth: `${maxWidth}px`,
    ...(placeAbove
      ? { bottom: `${window.innerHeight - rect.top + gap}px`, top: 'auto' }
      : { top: `${rect.bottom + gap}px`, bottom: 'auto' }),
    '--select-list-max-height': `${maxHeight}px`,
  };
}

function openDropdown() {
  if (props.disabled) return;
  if (typeof document !== 'undefined' && containerRef.value) {
    teleportTarget.value = containerRef.value.closest('dialog') ?? 'body';
  }
  updatePosition();
  isOpen.value = true;
  // Set active index to currently selected option
  const currentIdx = normalizedOptions.value.findIndex(opt => String(opt.value) === String(props.modelValue));
  activeIndex.value = currentIdx >= 0 ? currentIdx : 0;

  void nextTick(() => {
    updatePosition();
    scrollToActive();
  });
}

function closeDropdown() {
  isOpen.value = false;
  activeIndex.value = -1;
}

function selectOption(option: SelectOption<T>) {
  if (option.disabled || props.disabled) return;
  emit('update:modelValue', option.value);
  emit('change', option.value);
  closeDropdown();
}

function scrollToActive() {
  if (!listboxRef.value || activeIndex.value < 0) return;
  const items = listboxRef.value.querySelectorAll<HTMLElement>('.apple-select-option');
  const target = items[activeIndex.value];
  if (target) {
    target.scrollIntoView({ block: 'nearest' });
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (props.disabled) return;

  if (!isOpen.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      openDropdown();
    }
    return;
  }

  const enabledOptions = normalizedOptions.value;

  switch (e.key) {
    case 'ArrowDown': {
      e.preventDefault();
      let nextIdx = activeIndex.value + 1;
      while (nextIdx < enabledOptions.length && enabledOptions[nextIdx]?.disabled) {
        nextIdx++;
      }
      if (nextIdx < enabledOptions.length) {
        activeIndex.value = nextIdx;
        scrollToActive();
      }
      break;
    }
    case 'ArrowUp': {
      e.preventDefault();
      let prevIdx = activeIndex.value - 1;
      while (prevIdx >= 0 && enabledOptions[prevIdx]?.disabled) {
        prevIdx--;
      }
      if (prevIdx >= 0) {
        activeIndex.value = prevIdx;
        scrollToActive();
      }
      break;
    }
    case 'Enter':
    case ' ': {
      e.preventDefault();
      if (activeIndex.value >= 0 && activeIndex.value < enabledOptions.length) {
        const opt = enabledOptions[activeIndex.value];
        if (opt && !opt.disabled) {
          selectOption(opt);
        }
      }
      break;
    }
    case 'Escape': {
      e.preventDefault();
      e.stopPropagation();
      closeDropdown();
      break;
    }
    case 'Tab': {
      closeDropdown();
      break;
    }
  }
}

function handleClickOutside(e: MouseEvent) {
  const target = e.target as Node;
  if (containerRef.value?.contains(target) || listboxRef.value?.contains(target)) {
    return;
  }
  closeDropdown();
}

function handleViewportChange() {
  if (isOpen.value) {
    updatePosition();
  }
}

watch(
  () => props.disabled,
  newVal => {
    if (newVal && isOpen.value) {
      closeDropdown();
    }
  },
);

onMounted(() => {
  canTeleport.value = typeof document !== 'undefined';
  document.addEventListener('click', handleClickOutside, true);
  window.addEventListener('resize', handleViewportChange, { passive: true });
  window.addEventListener('scroll', handleViewportChange, { capture: true, passive: true });
});

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside, true);
  window.removeEventListener('resize', handleViewportChange);
  window.removeEventListener('scroll', handleViewportChange, { capture: true });
});
</script>

<template>
  <div
    ref="containerRef"
    class="apple-select-container"
    :class="{ 'is-open': isOpen, 'is-disabled': disabled, 'is-compact': compact }"
    @keydown="handleKeydown"
  >
    <button
      :id="id"
      type="button"
      class="apple-select-trigger"
      :class="{ 'is-placeholder': isPlaceholder }"
      :disabled="disabled"
      :aria-haspopup="'listbox'"
      :aria-expanded="isOpen"
      :aria-label="ariaLabel || displayText"
      @click="toggleDropdown"
    >
      <span class="apple-select-label">{{ displayText }}</span>
      <ChevronDown class="apple-select-arrow" :size="16" aria-hidden="true" />
    </button>

    <Teleport :to="teleportTarget" :disabled="!canTeleport">
      <Transition name="apple-popover">
        <div
          v-if="isOpen"
          ref="listboxRef"
          class="apple-select-popover"
          :class="{ 'is-above': popoverAbove }"
          :style="popoverStyle"
          role="listbox"
          :aria-activedescendant="activeIndex >= 0 ? `apple-opt-${activeIndex}` : undefined"
        >
          <div class="apple-select-list">
            <button
              v-for="(option, idx) in normalizedOptions"
              :id="`apple-opt-${idx}`"
              :key="String(option.value)"
              type="button"
              class="apple-select-option"
              :class="{
                'is-selected': String(option.value) === String(modelValue),
                'is-active': activeIndex === idx,
                'is-disabled': option.disabled,
              }"
              role="option"
              :aria-selected="String(option.value) === String(modelValue)"
              :disabled="option.disabled"
              @click="selectOption(option)"
              @mouseenter="activeIndex = idx"
            >
              <div class="apple-select-option-content">
                <span class="apple-select-option-text">{{ option.label }}</span>
                <small v-if="option.description" class="apple-select-option-desc">{{ option.description }}</small>
              </div>
              <Check
                v-if="String(option.value) === String(modelValue)"
                class="apple-select-check"
                :size="15"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.apple-select-container {
  position: relative;
  display: inline-block;
  width: 100%;
  font-family: inherit;
  user-select: none;
}

/* Trigger Button */
.apple-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  height: 38px;
  padding: 0 12px;
  background-color: var(--color-white);
  color: var(--color-ink);
  font-size: var(--text-supporting);
  font-weight: 500;
  line-height: 1.5;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: 0 1px 2px rgb(41 62 52 / 4%);
  cursor: pointer;
  outline: none;
  text-align: left;
  transition:
    border-color 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    color 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.apple-select-container.is-compact .apple-select-trigger {
  height: 32px;
  padding: 0 10px;
  font-size: var(--text-supporting);
  border-radius: var(--radius-md);
}

.apple-select-trigger:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background-color: var(--color-surface-hover);
  box-shadow: 0 2px 6px rgb(41 62 52 / 7%);
}

.apple-select-container.is-open .apple-select-trigger,
.apple-select-trigger:focus-visible {
  border-color: var(--brand, var(--color-brand));
  box-shadow: var(--focus-ring);
  background-color: var(--color-white);
}

.apple-select-trigger:disabled {
  cursor: not-allowed;
  background-color: var(--color-surface-disabled);
  color: var(--color-text-tertiary);
  border-color: var(--color-border);
  opacity: 0.85;
}

.apple-select-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.apple-select-trigger.is-placeholder .apple-select-label {
  color: var(--color-text-tertiary);
  font-weight: 400;
}

/* Rotating Chevron */
.apple-select-arrow {
  flex-shrink: 0;
  color: var(--color-text-secondary);
  transition:
    transform 0.24s cubic-bezier(0.16, 1, 0.3, 1),
    color 0.2s ease;
}

.apple-select-container.is-open .apple-select-arrow {
  transform: rotate(180deg);
  color: var(--brand, var(--color-brand));
}

/* Floating Popover */
.apple-select-popover {
  position: fixed;
  z-index: 2200;
  background: var(--color-surface);
  backdrop-filter: blur(18px) saturate(180%);
  -webkit-backdrop-filter: blur(18px) saturate(180%);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-popover);
  padding: 5px;
  overflow: hidden;
}

.apple-select-popover.is-above {
  transform-origin: bottom center;
}

.apple-select-list {
  max-height: var(--select-list-max-height, 260px);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border-strong) transparent;
}

.apple-select-list::-webkit-scrollbar {
  width: 5px;
}

.apple-select-list::-webkit-scrollbar-track {
  background: transparent;
}

.apple-select-list::-webkit-scrollbar-thumb {
  background-color: var(--color-border);
  border-radius: var(--radius-lg);
}

.apple-select-list::-webkit-scrollbar-thumb:hover {
  background-color: var(--color-border-strong);
}

/* Option Items */
.apple-select-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 8px 12px;
  border: 0;
  background: transparent;
  border-radius: var(--radius-md);
  color: var(--color-ink);
  font-size: var(--text-supporting);
  line-height: 1.45;
  cursor: pointer;
  text-align: left;
  scroll-margin-block: 4px;
  transition:
    background-color 0.12s ease,
    color 0.12s ease;
}

.apple-select-container.is-compact .apple-select-option {
  padding: 6px 10px;
  font-size: var(--text-supporting);
}

.apple-select-option-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.apple-select-option-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.apple-select-option-desc {
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.apple-select-option:hover:not(:disabled),
.apple-select-option.is-active:not(:disabled) {
  background-color: var(--color-surface-soft);
  color: var(--color-brand);
}

.apple-select-option.is-selected {
  background-color: var(--color-brand-tint);
  color: var(--brand, var(--color-brand));
  font-weight: 600;
}

.apple-select-option.is-disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.apple-select-check {
  flex-shrink: 0;
  color: var(--brand, var(--color-brand));
}

/* Popover Transition */
.apple-popover-enter-active,
.apple-popover-leave-active {
  transition:
    opacity 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  transform-origin: top center;
}

.apple-popover-enter-from,
.apple-popover-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}

.apple-popover-enter-from.is-above,
.apple-popover-leave-to.is-above {
  transform: translateY(6px) scale(0.97);
}

@media (prefers-reduced-motion: reduce) {
  .apple-select-trigger,
  .apple-select-arrow,
  .apple-select-option,
  .apple-popover-enter-active,
  .apple-popover-leave-active {
    transition-duration: 0.01ms;
  }

  .apple-popover-enter-from,
  .apple-popover-leave-to,
  .apple-popover-enter-from.is-above,
  .apple-popover-leave-to.is-above {
    transform: none;
  }
}
</style>
