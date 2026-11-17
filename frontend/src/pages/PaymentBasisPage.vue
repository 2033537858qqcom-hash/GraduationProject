<script setup lang="ts">
import { confirmDialog } from '../composables/useConfirm';
import { computed, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { api, errorMessage } from '../lib/api';
import { formatDateTime } from '../lib/format';
import { currentUser } from '../stores/auth';
import { type PaymentCategory } from '../lib/payment';
import { financeRecordText, type FeeBasis } from '../finance';
import BaseSelect from '../components/BaseSelect.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import '../styles/finance.css';

const route = useRoute(),
  categories = ref<PaymentCategory[]>([]),
  items = ref<FeeBasis[]>([]);
const loading = ref(true),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  formOpen = ref(false);
const empty = () => ({
  username: '',
  categoryCode: '',
  billingPeriod: '',
  quantity: '1',
  previousReading: '',
  currentReading: '',
  basis: '',
  correctionOf: null as number | null,
});
const form = ref(empty()),
  requestKey = ref(crypto.randomUUID());
watch(
  form,
  () => {
    requestKey.value = crypto.randomUUID();
  },
  { deep: true },
);
const permittedCategories = computed(() => {
  const permissions = currentUser.value?.permissions ?? [],
    codes = new Set<string>();
  if (permissions.includes('academic:manage')) ['TUITION', 'TEXTBOOK'].forEach(code => codes.add(code));
  if (permissions.includes('housing:assign'))
    ['ACCOMMODATION', 'WATER', 'ELECTRICITY', 'UTILITIES'].forEach(code => codes.add(code));
  if (permissions.includes('repair:dispatch')) ['WATER', 'ELECTRICITY', 'UTILITIES'].forEach(code => codes.add(code));
  return categories.value.filter(category => category.enabled !== false && codes.has(category.code));
});
const metered = computed(() => ['WATER', 'ELECTRICITY'].includes(form.value.categoryCode));
let sequence = 0;
async function load() {
  const next = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const [catalog, page] = await Promise.all([
      api<{ categories: PaymentCategory[] }>('/payment-bases/catalog'),
      api<{ items: FeeBasis[] }>('/payment-bases'),
    ]);
    if (next !== sequence) return;
    categories.value = catalog.categories;
    items.value = page.items;
  } catch (cause) {
    if (next === sequence) error.value = errorMessage(cause);
  } finally {
    if (next === sequence) loading.value = false;
  }
}
function revise(item: FeeBasis) {
  form.value = {
    ...empty(),
    username: item.username,
    categoryCode: item.categoryCode,
    billingPeriod: item.billingPeriod,
    quantity: item.quantity,
    basis: item.basis,
    correctionOf: item.id,
  };
  formOpen.value = true;
  message.value = '请根据退回说明补充依据。原记录会保留，提交后形成修正版。';
}
async function submit() {
  if (!form.value.categoryCode) {
    error.value = '请选择收费分类。';
    return;
  }
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    await api('/payment-bases', {
      method: 'POST',
      body: JSON.stringify({
        ...form.value,
        username: form.value.username.trim(),
        billingPeriod: form.value.billingPeriod.trim(),
        basis: form.value.basis.trim(),
        requestKey: requestKey.value,
      }),
    });
    form.value = empty();
    await load();
    message.value = '收费依据已提交财务核对。此时尚未生成或发布应缴账单。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
onBeforeRouteLeave(async () =>
  busy.value
    ? false
    : !(form.value.username || form.value.basis) || (await confirmDialog('尚有未提交的收费依据，确定离开？')),
);
watch(
  () => route.fullPath,
  () => {
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <div class="payment-office">
    <ServicePageHeader
      context="岗位办理 · 收费业务依据"
      title="收费依据"
      description="提供本岗位核实过的收费依据，交由财务核定、复核和发布。"
    >
      <template #actions>
        <button
          type="button"
          class="button primary compact"
          :disabled="loading || busy"
          @click="
            form = empty();
            formOpen = !formOpen;
          "
        >
          {{ formOpen ? '收起录入表单 ×' : '提交收费依据' }}
        </button>
        <button class="button compact" :disabled="loading || busy" @click="load">刷新记录</button>
      </template>
    </ServicePageHeader>
    <p class="finance-note">
      教务可提供本学院学费与教材费依据；宿管可提供负责楼栋住户的住宿、水电依据；后勤可提供负责校区的水电依据。提交依据不会直接向学生收费。
    </p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <section v-if="formOpen || form.correctionOf" class="portal-panel finance-panel">
      <div class="finance-heading">
        <h2>{{ form.correctionOf ? `补充原依据 #${form.correctionOf}` : '提交收费依据' }}</h2>
        <button
          type="button"
          class="button compact"
          :disabled="busy"
          @click="
            form = empty();
            formOpen = false;
          "
        >
          收起表单 ×
        </button>
      </div>
      <form class="finance-form" @submit.prevent="submit">
        <fieldset :disabled="loading || busy">
          <label>
            学生账号
            <input v-model="form.username" required maxlength="64" placeholder="填写负责范围内的学生账号" />
          </label>
          <label>
            收费分类
            <BaseSelect
              v-model="form.categoryCode"
              :options="permittedCategories.map(category => ({ value: category.code, label: category.name }))"
              placeholder="请选择分类"
            />
          </label>
          <label>
            收费账期
            <input v-model="form.billingPeriod" required maxlength="80" placeholder="例如 2026年10月" />
          </label>
          <label v-if="!metered">
            数量 / 分摊用量
            <input v-model="form.quantity" required inputmode="decimal" />
          </label>
          <template v-if="metered">
            <label>
              上期表计读数
              <input v-model="form.previousReading" required inputmode="decimal" />
            </label>
            <label>
              本期表计读数
              <input v-model="form.currentReading" required inputmode="decimal" />
            </label>
            <p class="muted finance-wide">
              用量按两次读数之差计算。请在依据中说明表计对应住户及费用分摊方式，避免重复收费。
            </p>
          </template>
          <label class="finance-wide">
            收费依据与核对说明
            <textarea
              v-model="form.basis"
              required
              minlength="10"
              maxlength="1000"
              rows="4"
              placeholder="填写实际使用、住宿或教学收费依据；涉及水电分摊时说明分摊口径"
            />
          </label>
          <div class="finance-actions finance-wide">
            <button class="button primary">{{ busy ? '正在提交…' : '提交财务核对' }}</button>
            <button
              type="button"
              class="button"
              @click="
                form = empty();
                formOpen = false;
              "
            >
              取消
            </button>
          </div>
        </fieldset>
      </form>
    </section>
    <section class="portal-panel finance-panel">
      <div class="finance-heading">
        <h2>本人提供的收费依据</h2>
        <span class="muted">共 {{ items.length }} 条</span>
      </div>
      <p v-if="loading" role="status">正在读取记录…</p>
      <p v-else-if="!items.length && !error" class="empty-state">尚未提交收费依据。</p>

      <div v-if="!loading && items.length" class="finance-ledger-head" aria-hidden="true">
        <span>分类 / 依据说明</span>
        <span>关联学生 / 账期</span>
        <span>审核状态 / 时间</span>
        <span class="is-right">核定用量</span>
        <span class="is-right">操作</span>
      </div>

      <div
        v-for="item in loading ? [] : items"
        :key="item.id"
        class="finance-ledger-group"
        :class="{ 'is-expanded': form.correctionOf === item.id }"
      >
        <article
          class="finance-record finance-record--ledger"
          :class="{ 'finance-selected': String(item.id) === route.query.id || form.correctionOf === item.id }"
        >
          <div class="finance-col-main">
            <div class="finance-title-line">
              <span class="finance-badge">{{ item.categoryName }}</span>
              <h3>{{ item.studentName }} · {{ item.billingPeriod }}</h3>
            </div>
            <small class="finance-order-meta">{{ item.basis }}</small>
            <p v-if="item.reviewComment" class="finance-warning" style="margin-top: 6px">
              财务说明：{{ item.reviewComment }}
            </p>
          </div>
          <div class="finance-col-party">
            <strong>{{ item.studentName }}</strong>
            <span>{{ item.sourceReference }}</span>
          </div>
          <div class="finance-col-status">
            <span
              class="finance-status-tag"
              :data-tone="
                item.status === 'RETURNED'
                  ? 'danger'
                  : item.status === 'ACCEPTED'
                    ? 'success'
                    : item.status === 'SUBMITTED'
                      ? 'warning'
                      : 'muted'
              "
            >
              {{ financeRecordText(item.status) }}
              <template v-if="item.billId">· 已关联账单 #{{ item.billId }}</template>
            </span>
            <small class="muted">{{ formatDateTime(item.createdAt) }}</small>
          </div>
          <div class="finance-col-amount">
            <strong>{{ item.quantity }}</strong>
          </div>
          <div class="finance-col-actions">
            <button
              v-if="item.status === 'RETURNED'"
              class="button compact primary"
              :disabled="busy"
              @click="revise(item)"
            >
              补充修正
            </button>
            <span v-else class="muted" style="font-size: var(--text-meta)">已提交</span>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>
