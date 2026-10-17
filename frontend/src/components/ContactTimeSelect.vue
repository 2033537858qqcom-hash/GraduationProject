<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, Clock, X } from '@lucide/vue'
import BaseSelect from './BaseSelect.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    disabled?: boolean
    placeholder?: string
  }>(),
  {
    modelValue: '',
    disabled: false,
    placeholder: '请选择方便联系的时间',
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const quickPills = [
  { label: '随时可联系', value: '随时可联系' },
  { label: '工作日课后 (16:30-18:30)', value: '工作日课后 (16:30-18:30)' },
  { label: '工作日晚上 (18:30-21:30)', value: '工作日晚上 (18:30-21:30)' },
  { label: '工作日全天', value: '工作日全天 (08:30-17:30)' },
  { label: '周末全天', value: '周末全天 (09:00-21:00)' },
]

const standardOptions = [
  { value: '随时可联系', label: '随时可联系 (08:30 - 21:30)' },
  { value: '工作日全天 (08:30-17:30)', label: '工作日全天 (08:30 - 17:30)' },
  { value: '工作日上午 (08:30-12:00)', label: '工作日上午 (08:30 - 12:00)' },
  { value: '工作日下午 (14:00-18:00)', label: '工作日下午 (14:00 - 18:00)' },
  { value: '工作日课后 (16:30-18:30)', label: '工作日课后 (16:30 - 18:30)' },
  { value: '工作日晚上 (18:30-21:30)', label: '工作日晚上 (18:30 - 21:30)' },
  { value: '周末全天 (09:00-21:00)', label: '周末全天 (09:00 - 21:00)' },
  { value: '周末上午 (09:00-12:00)', label: '周末上午 (09:00 - 12:00)' },
  { value: '周末下午 (14:00-18:00)', label: '周末下午 (14:00 - 18:00)' },
  { value: '今天下午 (14:00-18:00)', label: '今天下午 (14:00 - 18:00)' },
  { value: '今天晚上 (18:30-21:30)', label: '今天晚上 (18:30 - 21:30)' },
  { value: '明天上午 (08:30-12:00)', label: '明天上午 (08:30 - 12:00)' },
  { value: '明天下午 (14:00-18:00)', label: '明天下午 (14:00 - 18:00)' },
  { value: '明天晚上 (18:30-21:30)', label: '明天晚上 (18:30 - 21:30)' },
  { value: '后天下午 (14:00-18:00)', label: '后天下午 (14:00 - 18:00)' },
  { value: '__CUSTOM_PICKER__', label: '📅 指定具体日期与时间段…' },
  { value: '__MANUAL_INPUT__', label: '✏️ 手动填写其他时间备注…' },
]

const selectDropdownOptions = computed(() => [
  { value: '', label: props.placeholder },
  ...standardOptions,
])

const isCustomDateMode = ref(false)
const isManualInputMode = ref(false)
const todayStr = new Date().toISOString().slice(0, 10)
const customDate = ref(todayStr)
const customSlot = ref('下午 (14:00-18:00)')

const slotOptions = [
  { value: '全天 (08:30-21:00)', label: '全天 (08:30 - 21:00)' },
  { value: '上午 (08:30-12:00)', label: '上午 (08:30 - 12:00)' },
  { value: '下午 (14:00-18:00)', label: '下午 (14:00 - 18:00)' },
  { value: '课后 (16:30-18:30)', label: '课后 (16:30 - 18:30)' },
  { value: '晚上 (18:30-21:30)', label: '晚上 (18:30 - 21:30)' },
]

const selectValue = computed({
  get() {
    if (isCustomDateMode.value) return '__CUSTOM_PICKER__'
    if (isManualInputMode.value) return '__MANUAL_INPUT__'
    const found = standardOptions.find(opt => opt.value === props.modelValue)
    return found ? found.value : (props.modelValue ? '__MANUAL_INPUT__' : '')
  },
  set(val: string) {
    if (val === '__CUSTOM_PICKER__') {
      isCustomDateMode.value = true
      isManualInputMode.value = false
      applyCustomDateTime()
    } else if (val === '__MANUAL_INPUT__') {
      isManualInputMode.value = true
      isCustomDateMode.value = false
    } else {
      isCustomDateMode.value = false
      isManualInputMode.value = false
      emit('update:modelValue', val)
    }
  }
})

function selectPill(val: string) {
  isCustomDateMode.value = false
  isManualInputMode.value = false
  emit('update:modelValue', val)
}

function applyCustomDateTime() {
  const dateStr = customDate.value ? customDate.value : '近期'
  emit('update:modelValue', `${dateStr} ${customSlot.value}`)
}

function onDateChange() {
  applyCustomDateTime()
}

function onSlotChange() {
  applyCustomDateTime()
}

function onManualInput(e: Event) {
  const target = e.target as HTMLInputElement
  emit('update:modelValue', target.value)
}

function clearTime() {
  isCustomDateMode.value = false
  isManualInputMode.value = false
  emit('update:modelValue', '')
}
</script>

<template>
  <div class="contact-time-select">
    <div class="contact-time-main">
      <template v-if="isCustomDateMode">
        <div class="contact-time-custom-picker">
          <input
            v-model="customDate"
            type="date"
            :min="todayStr"
            :disabled="disabled"
            aria-label="选择具体日期"
            @change="onDateChange"
          />
          <BaseSelect
            v-model="customSlot"
            :options="slotOptions"
            :disabled="disabled"
            compact
            aria-label="选择具体时间段"
            @change="onSlotChange"
          />
          <button type="button" class="contact-time-back-btn" :disabled="disabled" @click="isCustomDateMode = false">
            返回常用选项
          </button>
        </div>
      </template>

      <template v-else-if="isManualInputMode">
        <div class="contact-time-manual">
          <input
            :value="modelValue"
            maxlength="100"
            placeholder="填写具体时间备注，如：周三下午第3节课后"
            :disabled="disabled"
            @input="onManualInput"
          />
          <button type="button" class="contact-time-back-btn" :disabled="disabled" @click="isManualInputMode = false">
            返回下拉选择
          </button>
        </div>
      </template>

      <template v-else>
        <BaseSelect
          v-model="selectValue"
          :options="selectDropdownOptions"
          :disabled="disabled"
          aria-label="方便联系的时间"
        />
      </template>
    </div>

    <!-- Quick pills for immediate 1-click selection -->
    <div class="contact-time-pills" role="group" aria-label="快捷常用时段">
      <button
        v-for="pill in quickPills"
        :key="pill.value"
        type="button"
        class="contact-time-pill"
        :class="{ active: modelValue === pill.value }"
        :disabled="disabled"
        @click="selectPill(pill.value)"
      >
        <Check v-if="modelValue === pill.value" :size="11" />
        {{ pill.label }}
      </button>
    </div>

    <div v-if="modelValue" class="contact-time-current">
      <span class="contact-time-tag">
        <Clock :size="13" />
        <span>已选：<strong>{{ modelValue }}</strong></span>
      </span>
      <button
        v-if="!disabled"
        type="button"
        class="contact-time-clear"
        aria-label="清空已选时间"
        @click="clearTime"
      >
        <X :size="13" />
      </button>
    </div>

    <small class="contact-time-hint">仅作联系参考，不代表已预约维修</small>
  </div>
</template>

<style scoped>
.contact-time-select {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.contact-time-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.contact-time-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 2px;
}

.contact-time-pill {
  appearance: none;
  border: 1px solid #dbe3ed;
  background: #f8fafc;
  color: #475569;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.15s ease;
  line-height: 1.4;
  user-select: none;
}

.contact-time-pill:hover:not(:disabled) {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}

.contact-time-pill.active {
  border-color: #3b82f6;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 500;
  box-shadow: 0 1px 2px rgba(59, 130, 246, 0.1);
}

.contact-time-pill:disabled {
  opacity: 0.5;
  cursor: default;
}

.contact-time-custom-picker {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.contact-time-custom-picker input[type="date"] {
  height: 34px;
  padding: 4px 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 12px;
  background: #fff;
  color: #1e293b;
}

.contact-time-manual {
  display: flex;
  align-items: center;
  gap: 8px;
}

.contact-time-manual input {
  flex: 1;
}

.contact-time-back-btn {
  background: none;
  border: none;
  color: #2563eb;
  font-size: 11px;
  cursor: pointer;
  padding: 4px 6px;
  text-decoration: underline;
  white-space: nowrap;
}

.contact-time-back-btn:hover:not(:disabled) {
  color: #1d4ed8;
}

.contact-time-current {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 10px;
  background: #f1f5f9;
  border-radius: 6px;
  font-size: 11.5px;
  color: #334155;
}

.contact-time-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.contact-time-tag strong {
  font-weight: 600;
  color: #1d4ed8;
}

.contact-time-clear {
  border: none;
  background: transparent;
  color: #94a3b8;
  display: grid;
  place-items: center;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
}

.contact-time-clear:hover {
  background: #e2e8f0;
  color: #475569;
}

.contact-time-hint {
  font-size: 11px;
  color: #64748b;
  line-height: 1.4;
  margin-top: 1px;
}
</style>
