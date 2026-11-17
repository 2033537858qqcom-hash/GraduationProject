<script setup lang="ts">
import { financeRecordText, type FeeBasis } from '../../finance';

defineProps<{
  bases: FeeBasis[];
  selectedId: string;
  canManage: boolean;
  loading: boolean;
  busy: boolean;
}>();

const emit = defineEmits<{
  useBasis: [basis: FeeBasis];
  returnBasis: [basis: FeeBasis];
}>();
</script>

<template>
  <section class="portal-panel finance-panel">
    <div class="finance-heading">
      <div>
        <h2>业务收费依据</h2>
        <p class="muted" style="margin: 4px 0 0">业务岗位只提供负责范围内的依据，金额由财务核定，发布仍需独立复核。</p>
      </div>
    </div>
    <p v-if="!loading && !bases.length" class="empty-state">暂无业务部门提交的收费依据。</p>

    <div v-if="!loading && bases.length" class="finance-ledger-head" aria-hidden="true">
      <span>收费分类 / 依据说明</span>
      <span>关联学生 / 账期</span>
      <span>业务来源 / 状态</span>
      <span class="is-right">核定用量</span>
      <span class="is-right">经办操作</span>
    </div>

    <div v-for="basis in bases" :key="basis.id" class="finance-ledger-group">
      <article
        class="finance-record finance-record--ledger"
        :class="{ 'finance-selected': String(basis.id) === selectedId }"
      >
        <div class="finance-col-main">
          <div class="finance-title-line">
            <span class="finance-badge">{{ basis.categoryName }}</span>
            <h3>{{ basis.studentName }} · {{ basis.billingPeriod }}</h3>
          </div>
          <small class="finance-order-meta">{{ basis.basis }}</small>
        </div>
        <div class="finance-col-party">
          <strong>{{ basis.studentName }}</strong>
          <span>提供人：{{ basis.creatorName }}</span>
        </div>
        <div class="finance-col-status">
          <span
            class="finance-status-tag"
            :data-tone="basis.status === 'SUBMITTED' ? 'warning' : basis.status === 'ACCEPTED' ? 'success' : 'muted'"
          >
            {{ financeRecordText(basis.status) }}
          </span>
          <small class="muted">{{ basis.sourceReference }}</small>
        </div>
        <div class="finance-col-amount">
          <strong>{{ basis.quantity }}</strong>
        </div>
        <div class="finance-col-actions">
          <template v-if="canManage && basis.status === 'SUBMITTED'">
            <button class="button compact primary" :disabled="busy" @click="emit('useBasis', basis)">
              按此依据建单
            </button>
            <button class="button compact" :disabled="busy" @click="emit('returnBasis', basis)">退回补充</button>
          </template>
          <span v-else class="muted" style="font-size: var(--text-meta)">已归档</span>
        </div>
      </article>
    </div>
  </section>
</template>
