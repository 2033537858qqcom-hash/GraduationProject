<script setup lang="ts">
import { CalendarDays, CheckCircle2, RefreshCw, X } from '@lucide/vue';
import { occupancyStatus, type TimeSlot, type VenueSummary } from '../../reservations';

defineProps<{
  show: boolean;
  venue: VenueSummary | null;
  loading: boolean;
  slots: TimeSlot[];
  formatHour: (iso: string) => string;
}>();

const date = defineModel<string>('date', { required: true });

const emit = defineEmits<{
  close: [];
  refresh: [];
  book: [venue: VenueSummary];
}>();
</script>

<template>
  <div v-if="show && venue" class="modal-overlay" @click.self="emit('close')" @keydown.esc="emit('close')">
    <div
      class="modal-card schedule-modal-card"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      aria-labelledby="schedule-modal-title"
    >
      <div class="modal-header">
        <h2 id="schedule-modal-title">
          <CalendarDays :size="18" />
          {{ venue.name }} · 排期占用情况
        </h2>
        <button class="close-icon-btn" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <div class="schedule-controls">
        <label>
          查询日期
          <input v-model="date" type="date" @change="emit('refresh')" />
        </label>
        <button class="button compact" :disabled="loading" @click="emit('refresh')">
          <RefreshCw :size="14" />
          刷新排期
        </button>
      </div>

      <div class="schedule-content">
        <p v-if="loading" class="loading-state">正在查询当天排期…</p>
        <div v-else-if="!slots.length" class="empty-state">
          <CheckCircle2 :size="24" style="color: #10b981; margin-bottom: 8px" />
          <p>该日期目前全天无任何预约占用，时段充裕！</p>
        </div>
        <ul v-else class="schedule-slots-timeline">
          <li v-for="slot in slots" :key="slot.startsAt" class="schedule-slot-item">
            <div class="slot-time">
              <strong>{{ formatHour(slot.startsAt) }} - {{ formatHour(slot.endsAt) }}</strong>
              <span class="slot-status-badge">{{ occupancyStatus(slot.status) }}</span>
            </div>
            <div class="slot-desc">
              <h4>{{ slot.activityTheme }}</h4>
            </div>
          </li>
        </ul>
      </div>

      <div class="modal-footer">
        <button
          class="button primary compact"
          @click="
            emit('close');
            emit('book', venue);
          "
        >
          预约该场地
        </button>
        <button class="button compact" @click="emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>
