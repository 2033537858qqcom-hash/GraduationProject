<script setup lang="ts" generic="T extends string | number | boolean">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ChevronDown, Check } from '@lucide/vue'

export interface SelectOption<Value = string | number | boolean> {
  value: Value
  label: string
  disabled?: boolean
  description?: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: T | null
    options: (SelectOption<T> | T)[]
    placeholder?: string
    disabled?: boolean
    compact?: boolean
    id?: string
    name?: string
    ariaLabel?: string
  }>(),
  {
    modelValue: null,
    placeholder: '请选择',
    disabled: false,
    compact: false,
    id: undefined,
    name: undefined,
    ariaLabel: undefined,
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: T): void
  (e: 'change', value: T): void
}>()

const isOpen = ref(false)
const containerRef = ref<HTMLElement | null>(null)
const listboxRef = ref<HTMLElement | null>(null)
const activeIndex = ref<number>(-1)

const normalizedOptions = computed<SelectOption<T>[]>(() => {
  return props.options.map(item => {
    if (typeof item === 'object' && item !== null && 'value' in item && 'label' in item) {
      return item as SelectOption<T>
    }
    return {
      value: item as T,
      label: String(item),
    }
  })
})

const selectedOption = computed(() => {
  return normalizedOptions.value.find(opt => String(opt.value) === String(props.modelValue))
})

const displayText = computed(() => {
  if (selectedOption.value) return selectedOption.value.label
  return props.placeholder || '请选择'
})

const isPlaceholder = computed(() => !selectedOption.value)

function toggleDropdown() {
  if (props.disabled) return
  if (isOpen.value) {
    closeDropdown()
  } else {
    openDropdown()
  }
}

function openDropdown() {
  if (props.disabled) return
  isOpen.value = true
  // Set active index to currently selected option
  const currentIdx = normalizedOptions.value.findIndex(opt => String(opt.value) === String(props.modelValue))
  activeIndex.value = currentIdx >= 0 ? currentIdx : 0

  void nextTick(() => {
    scrollToActive()
  })
}

function closeDropdown() {
  isOpen.value = false
  activeIndex.value = -1
}

function selectOption(option: SelectOption<T>) {
  if (option.disabled || props.disabled) return
  emit('update:modelValue', option.value)
  emit('change', option.value)
  closeDropdown()
}

function scrollToActive() {
  if (!listboxRef.value || activeIndex.value < 0) return
  const items = listboxRef.value.querySelectorAll<HTMLElement>('.apple-select-option')
  const target = items[activeIndex.value]
  if (target) {
    target.scrollIntoView({ block: 'nearest' })
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (props.disabled) return

  if (!isOpen.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      openDropdown()
    }
    return
  }

  const enabledOptions = normalizedOptions.value

  switch (e.key) {
    case 'ArrowDown': {
      e.preventDefault()
      let nextIdx = activeIndex.value + 1
      while (nextIdx < enabledOptions.length && enabledOptions[nextIdx]?.disabled) {
        nextIdx++
      }
      if (nextIdx < enabledOptions.length) {
        activeIndex.value = nextIdx
        scrollToActive()
      }
      break
    }
    case 'ArrowUp': {
      e.preventDefault()
      let prevIdx = activeIndex.value - 1
      while (prevIdx >= 0 && enabledOptions[prevIdx]?.disabled) {
        prevIdx--
      }
      if (prevIdx >= 0) {
        activeIndex.value = prevIdx
        scrollToActive()
      }
      break
    }
    case 'Enter':
    case ' ': {
      e.preventDefault()
      if (activeIndex.value >= 0 && activeIndex.value < enabledOptions.length) {
        const opt = enabledOptions[activeIndex.value]
        if (opt && !opt.disabled) {
          selectOption(opt)
        }
      }
      break
    }
    case 'Escape':
    case 'Tab': {
      closeDropdown()
      break
    }
  }
}

function handleClickOutside(e: MouseEvent) {
  if (!containerRef.value) return
  if (!containerRef.value.contains(e.target as Node)) {
    closeDropdown()
  }
}

watch(() => props.disabled, (newVal) => {
  if (newVal && isOpen.value) {
    closeDropdown()
  }
})

onMounted(() => {
  document.addEventListener('click', handleClickOutside, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside, true)
})
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

    <Transition name="apple-popover">
      <div
        v-if="isOpen"
        ref="listboxRef"
        class="apple-select-popover"
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
  background-color: #ffffff;
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
  border: 1px solid #d0d9e5;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
  cursor: pointer;
  outline: none;
  text-align: left;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.apple-select-container.is-compact .apple-select-trigger {
  height: 32px;
  padding: 0 10px;
  font-size: 12px;
  border-radius: 6px;
}

.apple-select-trigger:hover:not(:disabled) {
  border-color: #9eb2c7;
  background-color: #fbfcff;
  box-shadow: 0 2px 6px rgba(16, 24, 40, 0.07);
}

.apple-select-container.is-open .apple-select-trigger,
.apple-select-trigger:focus-visible {
  border-color: var(--brand, #244b78);
  box-shadow: 0 0 0 3px rgba(36, 75, 120, 0.14);
  background-color: #ffffff;
}

.apple-select-trigger:disabled {
  cursor: not-allowed;
  background-color: #f4f6f9;
  color: #94a3b8;
  border-color: #e2e8f0;
  opacity: 0.85;
}

.apple-select-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.apple-select-trigger.is-placeholder .apple-select-label {
  color: #94a3b8;
  font-weight: 400;
}

/* Rotating Chevron */
.apple-select-arrow {
  flex-shrink: 0;
  color: #5a6e85;
  transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1), color 0.2s ease;
}

.apple-select-container.is-open .apple-select-arrow {
  transform: rotate(180deg);
  color: var(--brand, #244b78);
}

/* Floating Popover */
.apple-select-popover {
  position: absolute;
  top: calc(100% + 5px);
  left: 0;
  width: 100%;
  min-width: 180px;
  max-width: min(100vw - 32px, 520px);
  z-index: 1000;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(18px) saturate(180%);
  -webkit-backdrop-filter: blur(18px) saturate(180%);
  border: 1px solid rgba(200, 212, 226, 0.85);
  border-radius: 10px;
  box-shadow:
    0 12px 30px -4px rgba(18, 38, 63, 0.15),
    0 4px 12px -2px rgba(18, 38, 63, 0.08),
    0 0 0 1px rgba(0, 0, 0, 0.02);
  padding: 5px;
  overflow: hidden;
}

.apple-select-list {
  max-height: 260px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  scrollbar-width: thin;
  scrollbar-color: rgba(36, 75, 120, 0.2) transparent;
}

.apple-select-list::-webkit-scrollbar {
  width: 5px;
}

.apple-select-list::-webkit-scrollbar-track {
  background: transparent;
}

.apple-select-list::-webkit-scrollbar-thumb {
  background-color: rgba(36, 75, 120, 0.2);
  border-radius: 10px;
}

.apple-select-list::-webkit-scrollbar-thumb:hover {
  background-color: rgba(36, 75, 120, 0.4);
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
  border-radius: 6px;
  color: #2c3e50;
  font-size: 13px;
  line-height: 1.45;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.12s ease, color 0.12s ease;
}

.apple-select-container.is-compact .apple-select-option {
  padding: 6px 10px;
  font-size: 12px;
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
  font-size: 11px;
  color: #748092;
  margin-top: 2px;
}

.apple-select-option:hover:not(:disabled),
.apple-select-option.is-active:not(:disabled) {
  background-color: #f1f5f9;
  color: #1e3a5f;
}

.apple-select-option.is-selected {
  background-color: rgba(36, 75, 120, 0.08);
  color: var(--brand, #244b78);
  font-weight: 600;
}

.apple-select-option.is-disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.apple-select-check {
  flex-shrink: 0;
  color: var(--brand, #244b78);
}

/* Popover Transition */
.apple-popover-enter-active,
.apple-popover-leave-active {
  transition: opacity 0.18s cubic-bezier(0.16, 1, 0.3, 1), transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  transform-origin: top center;
}

.apple-popover-enter-from,
.apple-popover-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}
</style>
