<script setup lang="ts">
import { computed } from 'vue';
import { formatDateTime, formatMoney as money } from '../../lib/format';
import { paymentActionLabels, paymentSettlementText, paymentWorkflowText } from '../../lib/payment';
import { financeRecordText, financeRequestTypes, type FinanceDetail, type FinanceRequest } from '../../finance';

const props = defineProps<{
  detail: FinanceDetail;
  currentUserId: number | null | undefined;
  canReview: boolean;
  busy: boolean;
  actionTarget?: {
    kind: string;
    id: number;
    action: string;
    version: number;
    title: string;
  } | null;
  actionReason?: string;
}>();

const emit = defineEmits<{
  billAction: [action: string];
  requestAction: [request: FinanceRequest, action: 'APPROVE' | 'REJECT' | 'RETRY'];
  close: [];
  'update:actionReason': [value: string];
  executeAction: [];
  cancelAction: [];
}>();

const inlineActionActive = computed(() => {
  if (!props.actionTarget) return false;
  if (
    (props.actionTarget.kind === 'bill' || props.actionTarget.kind === 'billRequest') &&
    props.actionTarget.id === props.detail.bill.id
  ) {
    return true;
  }
  if (props.actionTarget.kind === 'request') {
    return props.detail.requests.some(req => req.id === props.actionTarget?.id);
  }
  return false;
});
</script>

<template>
  <section class="finance-folio" aria-label="账单卷宗详情">
    <div class="finance-folio-bar">
      <div class="finance-folio-title">
        <span class="finance-folio-kicker">
          账单卷宗档案 · #{{ detail.bill.id }} · {{ paymentWorkflowText(detail.bill.workflowStatus) }} ·
          {{ paymentSettlementText(detail.bill) }}
        </span>
        <h2>{{ detail.bill.title }}</h2>
      </div>
      <div class="finance-folio-bar-end">
        <strong class="finance-total">{{ money(detail.bill.amountCents) }}</strong>
        <button type="button" class="button compact" :disabled="busy" @click="emit('close')">收起卷宗 ×</button>
      </div>
    </div>

    <div class="finance-folio-grid">
      <div class="finance-folio-main">
        <dl class="finance-facts">
          <div>
            <dt>缴款学生</dt>
            <dd>{{ detail.bill.studentName }}（{{ detail.bill.studentUsername }}）</dd>
          </div>
          <div>
            <dt>收费账期</dt>
            <dd>{{ detail.bill.billingPeriod }}</dd>
          </div>
          <div>
            <dt>缴费截止</dt>
            <dd>{{ detail.bill.dueDate || '历史账单未设置' }}</dd>
          </div>
          <div>
            <dt>经办人员</dt>
            <dd>{{ detail.bill.creatorName }}</dd>
          </div>
          <div>
            <dt>收费标准</dt>
            <dd>{{ detail.bill.standardName || '手工核定金额' }}</dd>
          </div>
          <div>
            <dt>核定数量</dt>
            <dd>{{ detail.bill.quantity }}</dd>
          </div>
          <div class="finance-wide">
            <dt>收费依据</dt>
            <dd>{{ detail.bill.basis }}</dd>
          </div>
          <div :class="{ 'finance-wide': !detail.bill.tradeNo }">
            <dt>订单编号</dt>
            <dd>{{ detail.bill.orderNo }}</dd>
          </div>
          <div v-if="detail.bill.tradeNo">
            <dt>平台交易号</dt>
            <dd>{{ detail.bill.tradeNo }}</dd>
          </div>
        </dl>

        <p v-if="detail.bill.lastSyncError" class="alert">{{ detail.bill.lastSyncError }}</p>
        <p v-if="detail.bill.pendingRequestId" class="finance-note">
          该账单有待完成的财务处理申请，学生暂不能继续发起支付。
        </p>

        <section v-if="detail.requests.length" class="finance-subsection">
          <h3>关联财务处理申请</h3>
          <article v-for="request in detail.requests" :key="request.id" class="finance-record">
            <div>
              <strong>{{ financeRequestTypes[request.type] }} · {{ financeRecordText(request.status) }}</strong>
              <p>{{ request.reason }}</p>
              <p v-if="request.lastError" class="finance-warning">{{ request.lastError }}</p>
            </div>
            <div class="finance-actions">
              <template v-if="canReview && request.status === 'SUBMITTED' && request.requestedBy !== currentUserId">
                <button
                  class="button compact primary"
                  :disabled="busy"
                  @click="emit('requestAction', request, 'APPROVE')"
                >
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
        </section>
      </div>

      <div class="finance-folio-side">
        <div class="finance-folio-console">
          <div class="finance-folio-console-head">
            <h3>卷宗办理台</h3>
            <span class="finance-badge">{{ paymentWorkflowText(detail.bill.workflowStatus) }}</span>
          </div>

          <div v-if="detail.allowedActions.length" class="finance-actions">
            <button
              v-for="action in detail.allowedActions"
              :key="action"
              type="button"
              class="button compact"
              :class="{
                primary:
                  (inlineActionActive && actionTarget?.action === action) || ['SUBMIT', 'APPROVE'].includes(action),
              }"
              :disabled="busy"
              @click="emit('billAction', action)"
            >
              {{ paymentActionLabels[action] || action }}
            </button>
          </div>
          <p v-else class="muted" style="margin: 0; font-size: var(--text-supporting)">
            当前状态下无需您执行经办或复核操作，可查阅下方办理流转记录。
          </p>

          <form
            v-if="inlineActionActive && actionTarget"
            class="finance-confirm"
            aria-labelledby="finance-inline-action-heading"
            @submit.prevent="emit('executeAction')"
          >
            <h3 id="finance-inline-action-heading">
              {{ paymentActionLabels[actionTarget.action] || actionTarget.action }} · {{ actionTarget.title }}
            </h3>
            <p class="muted" style="margin: 4px 0 8px; font-size: var(--text-meta)">
              操作将记入账单审计轨迹。已发布账单的作废、减免、退款须由其他财务人员复核。
            </p>
            <label>
              处理说明（必填，至少 5 个字）
              <textarea
                :value="actionReason"
                required
                minlength="5"
                maxlength="500"
                rows="2"
                :disabled="busy"
                placeholder="请填写复核意见、退回原因或调整依据…"
                @input="emit('update:actionReason', ($event.target as HTMLTextAreaElement).value)"
              />
            </label>
            <div class="finance-actions" style="margin-top: 8px">
              <button class="button primary compact" :disabled="busy">
                {{ busy ? '正在处理…' : '确认提交处理' }}
              </button>
              <button type="button" class="button compact" :disabled="busy" @click="emit('cancelAction')">取消</button>
            </div>
          </form>
        </div>

        <section class="finance-subsection">
          <h3>办理记录（{{ detail.history.length }}）</h3>
          <ol class="finance-history">
            <li v-for="event in detail.history" :key="event.id">
              <strong>{{ paymentActionLabels[event.action] || event.action }}</strong>
              <span>{{ event.actorName }} · {{ formatDateTime(event.createdAt) }}</span>
              <p>{{ event.reason }}</p>
            </li>
          </ol>
        </section>
      </div>
    </div>
  </section>
</template>
