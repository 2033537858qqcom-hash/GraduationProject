<script setup lang="ts">
import { formatDateTime } from '../../lib/format';
import { financeRecordText, financeRequestTypes, type FinanceRequest } from '../../finance';

defineProps<{
  requests: FinanceRequest[];
  currentUserId: number | null | undefined;
  canReview: boolean;
  loading: boolean;
  busy: boolean;
}>();

const emit = defineEmits<{
  openBill: [billId: number];
  requestAction: [request: FinanceRequest, action: 'APPROVE' | 'REJECT' | 'RETRY'];
}>();
</script>

<template>
  <section class="portal-panel finance-panel">
    <div class="finance-heading">
      <div>
        <h2>作废、减免与退款</h2>
        <p class="muted" style="margin: 4px 0 0">须由其他财务人员复核；只有支付平台确认后才会显示退款或关单完成。</p>
      </div>
    </div>
    <p v-if="!loading && !requests.length" class="empty-state">暂无财务处理申请。</p>

    <div v-if="!loading && requests.length" class="finance-ledger-head" aria-hidden="true">
      <span>申请类型 / 关联账单</span>
      <span>申请人 / 提交时间</span>
      <span>处理状态</span>
      <span class="is-right">申请说明</span>
      <span class="is-right">复核操作</span>
    </div>

    <div v-for="request in requests" :key="request.id" class="finance-ledger-group">
      <article class="finance-record finance-record--ledger">
        <div class="finance-col-main">
          <div class="finance-title-line">
            <span class="finance-badge">{{ financeRequestTypes[request.type] }}</span>
            <h3>账单 #{{ request.billId }}</h3>
          </div>
          <p v-if="request.lastError" class="finance-warning" style="margin-top: 4px">{{ request.lastError }}</p>
        </div>
        <div class="finance-col-party">
          <strong>{{ request.requesterName }}</strong>
          <span>{{ formatDateTime(request.createdAt) }}</span>
        </div>
        <div class="finance-col-status">
          <span
            class="finance-status-tag"
            :data-tone="
              request.status === 'SUBMITTED'
                ? 'warning'
                : request.status === 'FAILED'
                  ? 'danger'
                  : request.status === 'APPROVED'
                    ? 'success'
                    : 'muted'
            "
          >
            {{ financeRecordText(request.status) }}
          </span>
        </div>
        <div class="finance-col-party" style="text-align: right">
          <span :title="request.reason">{{ request.reason }}</span>
        </div>
        <div class="finance-col-actions">
          <button class="button compact" :disabled="busy" @click="emit('openBill', request.billId)">查看卷宗 ▾</button>
          <template v-if="canReview && request.status === 'SUBMITTED' && request.requestedBy !== currentUserId">
            <button class="button compact primary" :disabled="busy" @click="emit('requestAction', request, 'APPROVE')">
              复核通过
            </button>
            <button class="button compact" :disabled="busy" @click="emit('requestAction', request, 'REJECT')">
              不予通过
            </button>
          </template>
          <button
            v-if="request.status === 'FAILED'"
            class="button compact"
            :disabled="busy"
            @click="emit('requestAction', request, 'RETRY')"
          >
            核对并重试原申请
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
