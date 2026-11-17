<script setup lang="ts">
import { ShieldCheck, X } from '@lucide/vue';
import BaseSelect from '../BaseSelect.vue';
import type { VenueManagementItem } from '../../reservations';

defineProps<{
  show: boolean;
  venue: VenueManagementItem | null;
  busy: boolean;
  error: string;
  success: string;
  candidateOptions: { value: string; label: string }[];
}>();

const selectedCandidateId = defineModel<string>('candidateId', { required: true });

const emit = defineEmits<{
  close: [];
  addManager: [];
  removeManager: [userId: number];
}>();
</script>

<template>
  <div v-if="show && venue" class="modal-overlay" @click.self="emit('close')" @keydown.esc="emit('close')">
    <div
      class="modal-card manager-modal-card"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      aria-labelledby="manager-modal-title"
    >
      <div class="modal-header">
        <h2 id="manager-modal-title">
          <ShieldCheck :size="18" />
          配置责任审批人 · {{ venue.name }}
        </h2>
        <button class="close-icon-btn" @click="emit('close')">
          <X :size="18" />
        </button>
      </div>

      <div class="modal-body-content" style="display: flex; flex-direction: column; gap: 16px">
        <p class="muted" style="font-size: 13px; line-height: 1.6">
          未完成预约需要至少一位有效且非申请人本人的办理人员。添加负责人会通知其接手待审批、待使用及使用中的预约；请先添加接任人员，再解除原负责人。
        </p>
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <p v-if="success" class="success-message" role="status">{{ success }}</p>
        <p class="muted">
          待审批 {{ venue.pendingReviewCount }} 条 · 待使用 {{ venue.pendingUsageCount }} 条 · 使用中
          {{ venue.activeUseCount }} 条
        </p>
        <p v-if="venue.unattendedTaskCount > 0" class="alert" role="status">
          当前 {{ venue.unattendedTaskCount }} 条未完成预约无人办理，请添加接任人员。
        </p>

        <div class="assigned-managers-list">
          <h4 style="font-size: 14px; margin-bottom: 8px; font-weight: 600">
            已指定责任审批人 ({{ venue.managers.length }})
          </h4>
          <div
            v-if="!venue.managers.length"
            class="empty-state"
            style="
              padding: 16px;
              background: var(--color-danger-soft);
              border: 1px dashed var(--color-danger-border);
              border-radius: var(--radius-md);
            "
          >
            <span style="color: var(--color-danger); font-size: var(--text-supporting)">
              目前尚未指定任何审批责任人！未配置责任人前该场地无法受理申请。
            </span>
          </div>
          <ul
            v-else
            class="manager-item-list"
            style="list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px"
          >
            <li
              v-for="m in venue.managers"
              :key="m.userId"
              class="manager-row-item"
              style="
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 10px 14px;
                background: var(--color-surface);
                border: 1px solid var(--color-border);
                border-radius: 6px;
              "
            >
              <div style="display: flex; flex-direction: column; gap: 2px">
                <strong style="font-size: 14px; color: var(--color-ink)">
                  {{ m.fullName }} · {{ m.eligible ? '可审批' : '已失效：账号停用或缺少审批权限' }}
                </strong>
                <span style="font-size: 12px; color: var(--color-text-secondary)">
                  账号：{{ m.username }} · 部门：{{ m.departmentName }} · 工号：{{ m.identityNumber }}
                </span>
              </div>
              <button
                class="text-button"
                style="color: var(--color-danger)"
                :disabled="busy"
                @click="emit('removeManager', m.userId)"
              >
                解除指定
              </button>
            </li>
          </ul>
        </div>

        <div
          class="add-manager-box"
          style="
            padding: 14px;
            background: var(--color-surface-soft);
            border-radius: 6px;
            display: flex;
            flex-direction: column;
            gap: 10px;
          "
        >
          <h4 style="font-size: 13px; font-weight: 600; color: var(--color-heading)">添加新的责任审批人</h4>
          <div style="display: flex; gap: 10px; align-items: center">
            <div style="flex: 1">
              <BaseSelect
                v-model="selectedCandidateId"
                :options="[{ value: '', label: '从具备借用审批权限的人员中选择…' }, ...candidateOptions]"
              />
            </div>
            <button class="button primary compact" :disabled="!selectedCandidateId || busy" @click="emit('addManager')">
              添加指定
            </button>
          </div>
        </div>
      </div>

      <div class="modal-footer" style="margin-top: 16px">
        <button class="button compact" @click="emit('close')">完成并关闭</button>
      </div>
    </div>
  </div>
</template>
