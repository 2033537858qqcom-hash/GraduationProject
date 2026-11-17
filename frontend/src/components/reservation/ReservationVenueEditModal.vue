<script setup lang="ts">
import { Edit3, X } from '@lucide/vue';
import type { VenueManagementItem } from '../../reservations';

defineProps<{
  show: boolean;
  venue: VenueManagementItem | null;
  busy: boolean;
}>();

const name = defineModel<string>('name');
const capacity = defineModel<number | null>('capacity');
const availableEquipment = defineModel<string>('availableEquipment');
const rules = defineModel<string>('rules');
const enabled = defineModel<boolean>('enabled');

const emit = defineEmits<{
  close: [];
  save: [];
}>();
</script>

<template>
  <div v-if="show && venue" class="modal-overlay" @click.self="emit('close')" @keydown.esc="emit('close')">
    <div
      class="modal-card venue-edit-modal"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      aria-labelledby="venue-edit-title"
    >
      <div class="modal-header">
        <h2 id="venue-edit-title">
          <Edit3 :size="18" />
          修改场地资源配置 · {{ venue.name }}
        </h2>
        <button class="close-icon-btn" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <form class="booking-form" @submit.prevent="emit('save')">
        <div class="form-grid-two">
          <label>
            场地/设备名称
            <input v-model="name" required maxlength="100" />
          </label>
          <label>
            容纳人数（人）
            <input v-model.number="capacity" type="number" min="1" max="10000" />
          </label>
        </div>

        <label>
          可用配套设备与技术规格
          <input v-model="availableEquipment" maxlength="500" placeholder="例如：投影仪、无线麦克风*2、调音台" />
        </label>

        <label>
          <div class="label-heading-row">
            <span>借用守则与场地公约</span>
            <small class="muted">最多 1000 字</small>
          </div>
          <textarea
            v-model="rules"
            rows="3"
            maxlength="1000"
            placeholder="填写使用要求、保洁守则、禁止携带外卖等约定"
          />
        </label>

        <label
          class="checkbox-label"
          style="display: flex; align-items: center; gap: 8px; margin-top: 8px; cursor: pointer"
        >
          <input v-model="enabled" type="checkbox" />
          <span>开启该场地/设备的对外预约开放（取消勾选将暂停开放）</span>
        </label>
        <p v-if="!enabled" class="alert" role="status">
          停用会取消尚未开始的待审和已批准预约，并通知申请人。正在使用的场地须先完成结束核销，才能停用。
        </p>

        <div class="form-actions modal-actions" style="margin-top: 18px">
          <button type="submit" class="button primary compact" :disabled="busy">保存配置</button>
          <button type="button" class="button compact" @click="emit('close')">取消</button>
        </div>
      </form>
    </div>
  </div>
</template>
