<script setup lang="ts">
import { useLeaveGuard } from '../composables/useEditProtection';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, errorMessage } from '../lib/api';
import { formatMoney as money } from '../lib/format';
import { currentUser } from '../stores/auth';
import DownloadLink from '../components/DownloadLink.vue';
import BaseSelect from '../components/BaseSelect.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import SkeletonBlock from '../components/SkeletonBlock.vue';
import DormMeterSettings from '../components/DormMeterSettings.vue';
import FeeCategorySettings from '../components/FeeCategorySettings.vue';
import FinanceBillDetailSection from '../components/finance/FinanceBillDetailSection.vue';
import FinanceBasesTab from '../components/finance/FinanceBasesTab.vue';
import FinanceRequestsTab from '../components/finance/FinanceRequestsTab.vue';
import FinanceReconciliationTab from '../components/finance/FinanceReconciliationTab.vue';
import {
  centsFromInput,
  paymentActionLabels,
  paymentSettlementText,
  paymentWorkflowText,
  type PaymentBill,
} from '../lib/payment';
import {
  financeRecordText,
  financeRequestTypes,
  type FeeBasis,
  type FeeStandard,
  type FinanceCatalog,
  type FinanceDetail,
  type FinancePage,
  type FinanceRequest,
  type StatementPreview,
} from '../finance';
import '../styles/finance.css';

const route = useRoute(),
  router = useRouter();
const canManage = computed(() => currentUser.value?.permissions.includes('payment:manage') ?? false);
const canReview = computed(() => currentUser.value?.permissions.includes('payment:review') ?? false);
const tabs = [
  { key: 'ledger', label: '账单台账' },
  { key: 'categories', label: '收费项目' },
  { key: 'standards', label: '收费标准' },
  { key: 'dorm-meters', label: '宿舍水电表计' },
  { key: 'bases', label: '业务收费依据' },
  { key: 'requests', label: '作废、减免与退款' },
  { key: 'reconciliation', label: '交易明细核对' },
];
const tab = computed(() => (tabs.some(item => item.key === route.query.tab) ? String(route.query.tab) : 'ledger'));
const catalog = ref<FinanceCatalog | null>(null),
  ledger = ref<FinancePage | null>(null),
  detail = ref<FinanceDetail | null>(null);
const bases = ref<FeeBasis[]>([]),
  requests = ref<FinanceRequest[]>([]);
const loading = ref(true),
  busy = ref(false),
  error = ref(''),
  message = ref('');
const filters = ref({ q: '', categoryCode: '', status: '', billingPeriod: '' });
const statusOptions = [
  { value: '', label: '全部状态' },
  ...['DRAFT', 'PENDING_REVIEW', 'RETURNED', 'PUBLISHED', 'VOIDED', 'WAIVED'].map(value => ({
    value,
    label: paymentWorkflowText(value),
  })),
  { value: 'UNPAID', label: '待支付' },
  { value: 'PAID', label: '已支付' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'OVERDUE', label: '逾期待缴' },
  { value: 'UNCONFIRMED', label: '支付结果待核实' },
];
const showBillForm = ref(false),
  editingId = ref<number | null>(null);
const emptyBill = () => ({
  usernames: '',
  categoryCode: '',
  title: '',
  billingPeriod: '',
  dueDate: '',
  standardId: '',
  quantity: '1',
  amount: '',
  basis: '',
  basisId: null as number | null,
  version: 0,
  reason: '',
});
const form = ref(emptyBill()),
  formKey = ref(crypto.randomUUID());
interface BatchPreview {
  items: {
    username: string;
    studentName: string | null;
    amountCents: number | null;
    errorCode: string | null;
    error: string | null;
  }[];
  validCount: number;
  totalAmountCents: number;
  canCreate: boolean;
}
const batchPreview = ref<BatchPreview | null>(null);
const isBatch = computed(
  () => !editingId.value && !form.value.basisId && form.value.usernames.split(/[\s,，]+/).filter(Boolean).length > 1,
);
const activeCategories = computed(() => catalog.value?.categories.filter(item => item.enabled !== false) ?? []);
const activeCategoryOptions = computed(() =>
  activeCategories.value.map(category => ({ value: category.code, label: category.name })),
);
const ledgerCategoryOptions = computed(() => [
  { value: '', label: '全部分类' },
  ...(catalog.value?.categories.map(category => ({ value: category.code, label: category.name })) ?? []),
]);
const standardsForForm = computed(
  () => catalog.value?.standards.filter(item => item.available && item.categoryCode === form.value.categoryCode) ?? [],
);
const billStandardOptions = computed(() => [
  { value: '', label: '手工核定金额' },
  ...standardsForForm.value.map(standard => ({
    value: String(standard.id),
    label: `${standard.name} · ${money(standard.unitPriceCents)}/${standard.unit}`,
  })),
]);
const campusOptions = computed(() => [
  { value: '', label: '全校区' },
  ...(catalog.value?.campuses.map(campus => ({ value: String(campus.id), label: campus.name })) ?? []),
]);
const majorOptions = computed(() => [
  { value: '', label: '全部专业' },
  ...(catalog.value?.majors.map(major => ({ value: String(major.id), label: major.name })) ?? []),
]);
const selectedStandard = computed(() => standardsForForm.value.find(item => String(item.id) === form.value.standardId));
watch(
  form,
  () => {
    formKey.value = crypto.randomUUID();
    batchPreview.value = null;
  },
  { deep: true, flush: 'sync' },
);
watch(
  () => form.value.categoryCode,
  () => {
    if (!standardsForForm.value.some(item => String(item.id) === form.value.standardId)) form.value.standardId = '';
  },
);
const standardForm = ref({
  name: '',
  categoryCode: '',
  unit: '',
  price: '',
  campusId: '',
  majorId: '',
  reason: '',
  effectiveFrom: '',
  effectiveTo: '',
});
const standardKey = ref(crypto.randomUUID());
watch(
  standardForm,
  () => {
    standardKey.value = crypto.randomUUID();
  },
  { deep: true },
);
type ActionTarget = {
  kind: 'bill' | 'standard' | 'request' | 'basis' | 'billRequest';
  id: number;
  action: string;
  version: number;
  title: string;
};
const actionTarget = ref<ActionTarget | null>(null),
  actionReason = ref(''),
  actionKey = ref(crypto.randomUUID());
const statementCsv = ref(''),
  statementPreview = ref<StatementPreview | null>(null);
watch(statementCsv, () => {
  statementPreview.value = null;
});
watch(actionReason, () => {
  actionKey.value = crypto.randomUUID();
});
let loadSequence = 0;
function queryParams() {
  const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' });
  for (const key of ['q', 'categoryCode', 'status', 'billingPeriod'])
    if (typeof route.query[key] === 'string' && route.query[key]) params.set(key, String(route.query[key]));
  return params;
}
const exportUrl = computed(() => '/api/finance/payments/export?' + queryParams().toString());
async function load() {
  const sequence = ++loadSequence;
  loading.value = true;
  error.value = '';
  for (const key of Object.keys(filters.value) as (keyof typeof filters.value)[])
    filters.value[key] = String(route.query[key] ?? '');
  try {
    const [nextCatalog, nextLedger, nextBases, nextRequests] = await Promise.all([
      api<FinanceCatalog>('/finance/payments/catalog'),
      api<FinancePage>('/finance/payments?' + queryParams()),
      api<FeeBasis[]>('/finance/payments/bases'),
      api<FinanceRequest[]>('/finance/payments/requests'),
    ]);
    const id = Number(route.query.id);
    const nextDetail =
      Number.isSafeInteger(id) && id > 0 && route.query.tab === 'details'
        ? await api<FinanceDetail>(`/finance/payments/${id}/detail`)
        : null;
    if (sequence !== loadSequence) return;
    catalog.value = nextCatalog;
    ledger.value = nextLedger;
    bases.value = nextBases;
    requests.value = nextRequests;
    detail.value = nextDetail;
  } catch (cause) {
    if (sequence === loadSequence) error.value = errorMessage(cause);
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}
async function changeTab(value: string) {
  if (!busy.value) await router.replace({ query: { ...route.query, tab: value, id: undefined } });
}
async function search() {
  await router.replace({ query: { ...filters.value, tab: 'ledger', page: '1' } });
}
async function page(value: number) {
  await router.replace({ query: { ...route.query, page: String(value) } });
}
async function openBill(id: number) {
  await router.replace({ query: { ...route.query, id: String(id), tab: 'details' } });
}
async function closeBill() {
  actionTarget.value = null;
  actionReason.value = '';
  await router.replace({ query: { ...route.query, id: undefined, tab: 'ledger' } });
}
function billTone(bill: PaymentBill) {
  if (['VOIDED', 'WAIVED'].includes(bill.workflowStatus) || bill.refundStatus === 'REFUNDED') return 'muted';
  if (bill.status === 'PAID') return 'success';
  if (bill.workflowStatus === 'RETURNED') return 'danger';
  if (bill.workflowStatus === 'PENDING_REVIEW' || bill.status === 'UNPAID') return 'warning';
  return 'default';
}
const standaloneActionTarget = computed(() => {
  if (!actionTarget.value) return false;
  if (tab.value === 'ledger' && detail.value) {
    if (
      (actionTarget.value.kind === 'bill' || actionTarget.value.kind === 'billRequest') &&
      actionTarget.value.id === detail.value.bill.id
    ) {
      return false;
    }
    if (actionTarget.value.kind === 'request' && detail.value.requests.some(req => req.id === actionTarget.value?.id)) {
      return false;
    }
  }
  if (tab.value === 'standards' && actionTarget.value.kind === 'standard') {
    return false;
  }
  return true;
});
function newBill() {
  editingId.value = null;
  form.value = emptyBill();
  showBillForm.value = true;
  message.value = '';
  error.value = '';
}
function editBill(bill: PaymentBill) {
  editingId.value = bill.id;
  showBillForm.value = true;
  form.value = {
    usernames: bill.studentUsername,
    categoryCode: bill.categoryCode,
    title: bill.title,
    billingPeriod: bill.billingPeriod,
    dueDate: bill.dueDate ?? '',
    standardId: bill.standardId ? String(bill.standardId) : '',
    quantity: bill.quantity,
    amount: (bill.amountCents / 100).toFixed(2),
    basis: bill.basis,
    basisId: bill.sourceBasisId,
    version: bill.version,
    reason: '',
  };
}
function useBasis(basis: FeeBasis) {
  newBill();
  form.value = {
    ...emptyBill(),
    usernames: basis.username,
    categoryCode: basis.categoryCode,
    title: basis.categoryName,
    billingPeriod: basis.billingPeriod,
    quantity: basis.quantity,
    basis: basis.basis,
    basisId: basis.id,
  };
}
async function saveBill() {
  if (!form.value.categoryCode) {
    error.value = '请选择收费分类。';
    return;
  }
  const usernames = form.value.usernames.split(/[\s,，]+/).filter(Boolean);
  if (!usernames.length || usernames.length > 100 || new Set(usernames).size !== usernames.length) {
    error.value = '请填写 1—100 个不重复的学生账号。';
    return;
  }
  if ((editingId.value || form.value.basisId) && usernames.length !== 1) {
    error.value = '修改账单或按单条业务依据建单时，只能指定一名学生。';
    return;
  }
  const amount = form.value.standardId ? null : centsFromInput(form.value.amount.trim());
  if (!form.value.standardId && amount === null) {
    error.value = '请核对手工金额，最多保留两位小数。';
    return;
  }
  const payload = {
    requestKey: formKey.value,
    username: usernames[0],
    categoryCode: form.value.categoryCode,
    title: form.value.title.trim(),
    billingPeriod: form.value.billingPeriod.trim(),
    dueDate: form.value.dueDate,
    standardId: form.value.standardId ? Number(form.value.standardId) : null,
    quantity: form.value.quantity,
    amountCents: amount,
    basis: form.value.basis.trim(),
    basisId: form.value.basisId,
    version: form.value.version,
    reason: form.value.reason,
  };
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    if (usernames.length > 1) {
      if (!batchPreview.value?.canCreate) {
        const key = formKey.value;
        const preview = await api<BatchPreview>('/finance/payments/batch/preview', {
          method: 'POST',
          body: JSON.stringify({ usernames, template: payload }),
        });
        if (key === formKey.value) batchPreview.value = preview;
        return;
      }
      const result = await api<PaymentBill[]>('/finance/payments/batch', {
        method: 'POST',
        body: JSON.stringify({ requestKey: formKey.value, usernames, template: payload }),
      });
      showBillForm.value = false;
      form.value = emptyBill();
      await load();
      message.value = `已生成 ${result.length} 笔草稿，请逐笔核对后提交复核。`;
    } else {
      const result = await api<PaymentBill>(
        editingId.value ? `/finance/payments/${editingId.value}` : '/finance/payments',
        { method: editingId.value ? 'PUT' : 'POST', body: JSON.stringify(payload) },
      );
      showBillForm.value = false;
      form.value = emptyBill();
      await openBill(result.id);
      await load();
      message.value = '账单草稿已保存，学生将在独立复核发布后看到。';
    }
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function createStandard() {
  if (!standardForm.value.categoryCode) {
    error.value = '请选择收费分类。';
    return;
  }
  const price = centsFromInput(standardForm.value.price.trim());
  if (price === null) {
    error.value = '请填写有效单价，最多保留两位小数。';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    await api('/finance/payments/standards', {
      method: 'POST',
      body: JSON.stringify({
        requestKey: standardKey.value,
        name: standardForm.value.name.trim(),
        categoryCode: standardForm.value.categoryCode,
        unit: standardForm.value.unit.trim(),
        unitPriceCents: price,
        campusId: standardForm.value.campusId ? Number(standardForm.value.campusId) : null,
        majorId: standardForm.value.majorId ? Number(standardForm.value.majorId) : null,
        reason: standardForm.value.reason.trim(),
        effectiveFrom: standardForm.value.effectiveFrom || null,
        effectiveTo: standardForm.value.effectiveTo || null,
      }),
    });
    standardForm.value = {
      name: '',
      categoryCode: '',
      unit: '',
      price: '',
      campusId: '',
      majorId: '',
      reason: '',
      effectiveFrom: '',
      effectiveTo: '',
    };
    await load();
    message.value = '收费标准已送交独立复核，通过后在有效期内可用于建单。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
function chooseAction(target: ActionTarget) {
  actionTarget.value = target;
  actionReason.value = '';
  actionKey.value = crypto.randomUUID();
  error.value = '';
}
function billAction(action: string) {
  if (!detail.value) return;
  if (action === 'EDIT') {
    editBill(detail.value.bill);
    return;
  }
  chooseAction({
    kind: action.startsWith('REQUEST_') ? 'billRequest' : 'bill',
    id: detail.value.bill.id,
    action,
    version: detail.value.bill.version,
    title: detail.value.bill.title,
  });
}
async function executeAction() {
  const target = actionTarget.value;
  if (!target || actionReason.value.trim().length < 5) {
    error.value = '请填写至少 5 个字符的处理说明。';
    return;
  }
  const payload = {
    action: target.action,
    version: target.version,
    requestKey: actionKey.value,
    reason: actionReason.value.trim(),
  };
  const path =
    target.kind === 'bill'
      ? `/finance/payments/${target.id}/actions`
      : target.kind === 'standard'
        ? `/finance/payments/standards/${target.id}/actions`
        : target.kind === 'request'
          ? `/finance/payments/requests/${target.id}/actions`
          : target.kind === 'basis'
            ? `/finance/payments/bases/${target.id}/return`
            : `/finance/payments/${target.id}/requests`;
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    const result = await api<{ status?: string; lastError?: string }>(path, {
      method: 'POST',
      body: JSON.stringify(
        target.kind === 'billRequest' ? { ...payload, type: target.action.replace('REQUEST_', '') } : payload,
      ),
    });
    actionTarget.value = null;
    await load();
    if (result.status === 'FAILED' || result.status === 'PROCESSING')
      error.value = result.lastError || '支付平台处理结果尚未确认，请在原申请上核对和重试。';
    else
      message.value =
        target.kind === 'billRequest' ? '申请已送交独立财务复核，尚未执行资金或账单调整。' : '处理结果已保存。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
function standardAction(item: FeeStandard, action: string) {
  chooseAction({ kind: 'standard', id: item.id, action, version: item.version, title: item.name });
}
function requestAction(item: FinanceRequest, action: string) {
  chooseAction({
    kind: 'request',
    id: item.id,
    action,
    version: item.version,
    title: `账单 #${item.billId} · ${financeRequestTypes[item.type]}`,
  });
}
async function readStatement(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (file.size > 262144) {
    error.value = '每次交易明细最多 256 KB，请分批核对。';
    return;
  }
  try {
    statementCsv.value = await file.text();
  } catch {
    error.value = '无法读取文件，请重新选择 UTF-8 编码的 CSV 文件。';
  }
}
async function compareStatement() {
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    statementPreview.value = await api<StatementPreview>('/finance/payments/reconciliation/preview', {
      method: 'POST',
      body: JSON.stringify({ csv: statementCsv.value }),
    });
    message.value = '明细核对已完成，未更改任何账单金额或到账状态。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
const billFormDirty = computed(
  () => showBillForm.value && Boolean(form.value.usernames || form.value.title || form.value.basis),
);
const standardFormDirty = computed(() => Object.values(standardForm.value).some(Boolean));
const actionFormDirty = computed(() => Boolean(actionTarget.value && actionReason.value.trim()));
const statementDirty = computed(() => Boolean(statementCsv.value.trim()) && !statementPreview.value);
const hasUnsaved = computed(
  () => billFormDirty.value || standardFormDirty.value || actionFormDirty.value || statementDirty.value,
);
useLeaveGuard(busy, hasUnsaved, '尚有未保存的财务输入（账单、收费标准、处理说明或交易明细），确定离开并放弃？');
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
      :context="`财务服务 · ${canManage ? '经办工作台' : '独立复核工作台'}`"
      title="财务收费"
      description="核对收费依据，分工经办与复核，跟进每一笔应缴费用。"
    >
      <template #actions>
        <button v-if="canManage" class="button primary" :disabled="loading || busy" @click="newBill">新建账单</button>
        <button class="button compact" :disabled="loading || busy" @click="load">刷新</button>
      </template>
    </ServicePageHeader>
    <p class="finance-note">当前使用支付宝沙箱。新账单须经其他人员复核发布；收费标准不会预置学校实际价格。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <section v-if="ledger" class="finance-metrics" aria-label="当前筛选的收费统计">
      <div>
        <span>待缴金额</span>
        <strong>{{ money(ledger.summary.unpaidAmountCents) }}</strong>
        <small>{{ ledger.summary.unpaidCount }} 笔账单</small>
      </div>
      <div>
        <span>已收款净额</span>
        <strong>{{ money(ledger.summary.paidAmountCents) }}</strong>
        <small>不含已全额退款账单</small>
      </div>
      <div>
        <span>待复核 / 逾期</span>
        <strong>{{ ledger.summary.pendingReviewCount }} / {{ ledger.summary.overdueCount }}</strong>
        <small>待办与逾期待缴分开统计</small>
      </div>
      <div>
        <span>已退款金额</span>
        <strong>{{ money(ledger.summary.refundedAmountCents) }}</strong>
        <small>以平台确认结果为准</small>
      </div>
    </section>
    <nav class="finance-tabs" aria-label="财务功能">
      <button
        v-for="item in tabs"
        :key="item.key"
        type="button"
        :aria-pressed="tab === item.key"
        :disabled="busy"
        @click="changeTab(item.key)"
      >
        {{ item.label }}
      </button>
    </nav>
    <FeeCategorySettings
      v-if="tab === 'categories'"
      :categories="catalog?.categories ?? []"
      :can-manage="canManage"
      :loading="loading"
      @changed="load"
    />
    <DormMeterSettings v-if="tab === 'dorm-meters'" :standards="catalog?.standards ?? []" :can-manage="canManage" />

    <section v-if="showBillForm" class="portal-panel finance-panel">
      <div class="finance-heading">
        <h2>{{ editingId ? '修改账单草稿' : '新建收费账单' }}</h2>
        <button class="text-button" :disabled="busy" @click="showBillForm = false">收起表单</button>
      </div>
      <form class="finance-form" @submit.prevent="saveBill">
        <fieldset :disabled="busy || loading">
          <label class="finance-wide">
            {{ editingId ? '学生账号' : '学生账号（多个账号用换行或逗号分隔，最多100人）' }}
            <textarea
              v-model="form.usernames"
              required
              :rows="editingId ? 1 : 2"
              maxlength="6500"
              placeholder="例如 20260001"
            />
          </label>
          <label>
            收费分类
            <BaseSelect
              v-model="form.categoryCode"
              :options="activeCategoryOptions"
              placeholder="请选择分类"
              :disabled="!!form.basisId"
            />
          </label>
          <label>
            费用名称
            <input v-model="form.title" required maxlength="120" placeholder="例如 2026—2027 学年学费" />
          </label>
          <label>
            收费账期
            <input
              v-model="form.billingPeriod"
              required
              maxlength="80"
              :readonly="!!form.basisId"
              placeholder="例如 2026年10月"
            />
          </label>
          <label>
            缴费截止日期
            <input v-model="form.dueDate" type="date" required />
          </label>
          <label>
            计费方式
            <BaseSelect v-model="form.standardId" :options="billStandardOptions" placeholder="手工核定金额" />
          </label>
          <label>
            数量 / 分摊用量
            <input
              v-model="form.quantity"
              required
              inputmode="decimal"
              :readonly="!!form.basisId"
              placeholder="例如 1 或 2.5"
            />
          </label>
          <p v-if="selectedStandard" class="finance-wide muted">
            采用已复核标准：{{ money(selectedStandard.unitPriceCents) }}/{{
              selectedStandard.unit
            }}。保存后请核对系统计算的总金额。
          </p>
          <label v-else>
            核定总额（元）
            <input v-model="form.amount" required inputmode="decimal" placeholder="例如 12.50" />
          </label>
          <label class="finance-wide">
            收费依据
            <textarea
              v-model="form.basis"
              required
              minlength="10"
              maxlength="1000"
              rows="3"
              placeholder="说明收费范围、依据文件或实际用量、分摊方式与金额核对情况"
            />
          </label>
          <label v-if="editingId" class="finance-wide">
            修改说明
            <textarea v-model="form.reason" required minlength="5" maxlength="500" rows="2" />
          </label>
          <p v-if="form.basisId" class="finance-wide muted">
            已关联业务收费依据 #{{ form.basisId }}，学生、分类、账期和用量须与原依据一致。
          </p>
          <section
            v-if="isBatch && batchPreview"
            class="finance-wide finance-subsection"
            aria-label="批量建单预览"
            aria-live="polite"
          >
            <h3>批量建单预览</h3>
            <p>
              校验通过 {{ batchPreview.validCount }} / {{ batchPreview.items.length }} 人 · 通过校验的金额合计
              {{ money(batchPreview.totalAmountCents) }}
            </p>
            <p class="muted">请核对学生和金额。确认生成时会再次校验，所有账单生成后仍须独立复核发布。</p>
            <p v-if="!batchPreview.canCreate" class="finance-warning">
              请修正以下问题后重新预览，全部通过后才能生成草稿。
            </p>
            <article v-for="row in batchPreview.items" :key="row.username" class="finance-record">
              <div>
                <strong>
                  {{ row.username }}
                  <template v-if="row.studentName">· {{ row.studentName }}</template>
                </strong>
                <p v-if="row.error" class="finance-warning">{{ row.error }}</p>
                <p v-else>校验通过 · {{ money(row.amountCents ?? 0) }}</p>
              </div>
            </article>
          </section>
          <div class="finance-actions finance-wide">
            <button class="button primary" :disabled="isBatch && !!batchPreview && !batchPreview.canCreate">
              {{
                busy
                  ? '正在处理…'
                  : editingId
                    ? '保存修改'
                    : isBatch
                      ? batchPreview?.canCreate
                        ? '确认生成草稿'
                        : '预览批量账单'
                      : '保存为草稿'
              }}
            </button>
            <button
              v-if="isBatch && batchPreview"
              type="button"
              class="button"
              @click="
                batchPreview = null;
                saveBill();
              "
            >
              重新预览
            </button>
            <span class="muted">保存后仍需提交独立复核。</span>
          </div>
        </fieldset>
      </form>
    </section>

    <section
      v-if="standaloneActionTarget && actionTarget"
      class="portal-panel finance-panel finance-confirm"
      aria-labelledby="finance-action-heading"
    >
      <h2 id="finance-action-heading">{{ paymentActionLabels[actionTarget.action] || actionTarget.action }}</h2>
      <p>{{ actionTarget.title }}</p>
      <p class="muted">操作会记录办理人和原因。已发布账单的作废、减免、退款须由其他财务人员复核。</p>
      <form @submit.prevent="executeAction">
        <label>
          处理说明
          <textarea v-model="actionReason" required minlength="5" maxlength="500" rows="3" :disabled="busy" />
        </label>
        <div class="finance-actions">
          <button class="button primary" :disabled="busy">{{ busy ? '正在处理…' : '确认处理' }}</button>
          <button type="button" class="button" :disabled="busy" @click="actionTarget = null">取消</button>
        </div>
      </form>
    </section>

    <section v-if="tab === 'ledger'" class="portal-panel finance-panel">
      <div class="finance-heading">
        <h2>账单台账</h2>
        <DownloadLink :href="exportUrl" expected-type="text/csv" class="button compact">导出当前筛选</DownloadLink>
      </div>
      <form class="finance-filters" @submit.prevent="search">
        <label>
          学生 / 费用名称
          <input v-model="filters.q" maxlength="120" placeholder="账号、姓名或名称" />
        </label>
        <label>
          分类
          <BaseSelect v-model="filters.categoryCode" :options="ledgerCategoryOptions" placeholder="全部分类" />
        </label>
        <label>
          状态
          <BaseSelect v-model="filters.status" :options="statusOptions" placeholder="全部状态" />
        </label>
        <label>
          账期
          <input v-model="filters.billingPeriod" maxlength="80" placeholder="完整账期名称" />
        </label>
        <button class="button" :disabled="loading || busy">查询</button>
      </form>

      <FinanceBillDetailSection
        v-if="detail && !ledger?.items.some(item => item.id === detail?.bill.id)"
        v-model:action-reason="actionReason"
        :detail="detail"
        :current-user-id="currentUser?.id"
        :can-review="canReview"
        :busy="busy"
        :action-target="actionTarget"
        @bill-action="billAction"
        @request-action="requestAction"
        @close="closeBill"
        @execute-action="executeAction"
        @cancel-action="actionTarget = null"
      />

      <SkeletonBlock v-if="loading" variant="list" :rows="5" label="正在读取账单及统计…" />
      <p v-else-if="!ledger?.items.length && !error" class="empty-state">当前筛选下没有账单。</p>

      <div v-if="!loading && ledger?.items.length" class="finance-ledger-head" aria-hidden="true">
        <span>收费项目 / 事项名称</span>
        <span>缴款人 / 账期</span>
        <span>流转状态 / 截止限期</span>
        <span class="is-right">核定金额</span>
        <span class="is-right">卷宗操作</span>
      </div>

      <div
        v-for="bill in loading ? [] : ledger?.items"
        :key="bill.id"
        class="finance-ledger-group"
        :class="{ 'is-expanded': detail?.bill.id === bill.id }"
      >
        <article
          class="finance-record finance-record--ledger"
          :class="{ 'finance-selected': detail?.bill.id === bill.id }"
        >
          <div class="finance-col-main">
            <div class="finance-title-line">
              <span class="finance-badge">{{ bill.categoryName }}</span>
              <h3>{{ bill.title }}</h3>
            </div>
            <small v-if="bill.orderNo" class="finance-order-meta">单号 {{ bill.orderNo }}</small>
          </div>
          <div class="finance-col-party">
            <strong>{{ bill.studentName }}（{{ bill.studentUsername }}）</strong>
            <span>账期：{{ bill.billingPeriod }}</span>
          </div>
          <div class="finance-col-status">
            <span class="finance-status-tag" :data-tone="billTone(bill)">
              {{ paymentWorkflowText(bill.workflowStatus) }} · {{ paymentSettlementText(bill) }}
            </span>
            <small class="muted">
              {{ bill.dueDate ? `截止 ${bill.dueDate}` : '未设截止日期' }}
            </small>
          </div>
          <div class="finance-col-amount">
            <strong>{{ money(bill.amountCents) }}</strong>
          </div>
          <div class="finance-col-actions">
            <button
              class="button compact"
              :class="{ primary: detail?.bill.id === bill.id }"
              :disabled="busy"
              @click="detail?.bill.id === bill.id ? closeBill() : openBill(bill.id)"
            >
              {{ detail?.bill.id === bill.id ? '收起卷宗 ▴' : '查看与办理 ▾' }}
            </button>
          </div>
        </article>

        <FinanceBillDetailSection
          v-if="detail && detail.bill.id === bill.id"
          v-model:action-reason="actionReason"
          :detail="detail"
          :current-user-id="currentUser?.id"
          :can-review="canReview"
          :busy="busy"
          :action-target="actionTarget"
          @bill-action="billAction"
          @request-action="requestAction"
          @close="closeBill"
          @execute-action="executeAction"
          @cancel-action="actionTarget = null"
        />
      </div>

      <div v-if="ledger && ledger.total > 0" class="finance-pagination">
        <span>
          共 {{ ledger.total }} 笔 · 第 {{ ledger.page }} / {{ Math.ceil(ledger.total / ledger.pageSize) }} 页
        </span>
        <div class="finance-actions">
          <button class="button compact" :disabled="loading || ledger.page <= 1" @click="page(ledger.page - 1)">
            上一页
          </button>
          <button
            class="button compact"
            :disabled="loading || ledger.page * ledger.pageSize >= ledger.total"
            @click="page(ledger.page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </section>

    <section v-if="tab === 'standards'" class="portal-panel finance-panel">
      <div class="finance-heading">
        <div>
          <h2>收费标准</h2>
          <p class="muted" style="margin: 4px 0 0">
            标准经独立复核后生效。调整价格时新建标准，旧账单保留建单时的价格快照。
          </p>
        </div>
      </div>
      <form v-if="canManage" class="finance-form finance-subsection" @submit.prevent="createStandard">
        <fieldset :disabled="busy || loading">
          <label>
            标准名称
            <input v-model="standardForm.name" required maxlength="120" />
          </label>
          <label>
            收费分类
            <BaseSelect v-model="standardForm.categoryCode" :options="activeCategoryOptions" placeholder="请选择分类" />
          </label>
          <label>
            计价单位
            <input v-model="standardForm.unit" required maxlength="20" placeholder="学年、月、吨、度、次" />
          </label>
          <label>
            单价（元）
            <input v-model="standardForm.price" required inputmode="decimal" />
          </label>
          <label>
            适用校区
            <BaseSelect v-model="standardForm.campusId" :options="campusOptions" placeholder="全校区" />
          </label>
          <label>
            适用专业
            <BaseSelect v-model="standardForm.majorId" :options="majorOptions" placeholder="全部专业" />
          </label>
          <label>
            标准生效日期（可选）
            <input v-model="standardForm.effectiveFrom" type="date" />
          </label>
          <label>
            标准截止日期（可选）
            <input v-model="standardForm.effectiveTo" type="date" :min="standardForm.effectiveFrom || undefined" />
          </label>
          <label class="finance-wide">
            标准依据
            <textarea v-model="standardForm.reason" required minlength="5" maxlength="500" rows="2" />
          </label>
          <div class="finance-actions finance-wide">
            <button class="button primary">提交标准复核</button>
          </div>
        </fieldset>
      </form>
      <p v-if="!loading && !catalog?.standards.length" class="empty-state">尚未建立收费标准。</p>
      <div
        v-for="standard in catalog?.standards"
        :key="standard.id"
        class="finance-ledger-group"
        :class="{ 'is-expanded': actionTarget?.kind === 'standard' && actionTarget.id === standard.id }"
      >
        <article class="finance-record finance-record--ledger">
          <div class="finance-col-main">
            <div class="finance-title-line">
              <span class="finance-badge">{{ standard.categoryName }}</span>
              <h3>{{ standard.name }}</h3>
            </div>
            <small class="finance-order-meta">{{ standard.reason }}</small>
          </div>
          <div class="finance-col-party">
            <strong>经办：{{ standard.creatorName }}</strong>
            <span>有效期：{{ standard.effectiveFrom || '不限' }} 至 {{ standard.effectiveTo || '不限' }}</span>
          </div>
          <div class="finance-col-status">
            <span
              class="finance-status-tag"
              :data-tone="
                standard.status === 'ACTIVE' && standard.available
                  ? 'success'
                  : standard.status === 'PENDING_REVIEW'
                    ? 'warning'
                    : 'muted'
              "
            >
              {{
                standard.status === 'ACTIVE'
                  ? standard.available
                    ? '生效中'
                    : '已审核 · 不在有效期'
                  : financeRecordText(standard.status)
              }}
            </span>
          </div>
          <div class="finance-col-amount">
            <strong>{{ money(standard.unitPriceCents) }}/{{ standard.unit }}</strong>
          </div>
          <div class="finance-col-actions">
            <template
              v-if="canReview && standard.status === 'PENDING_REVIEW' && standard.createdBy !== currentUser?.id"
            >
              <button class="button compact primary" :disabled="busy" @click="standardAction(standard, 'APPROVE')">
                复核通过
              </button>
              <button class="button compact" :disabled="busy" @click="standardAction(standard, 'RETURN')">退回</button>
            </template>
            <button
              v-if="canManage && standard.status === 'ACTIVE'"
              class="button compact"
              :disabled="busy"
              @click="standardAction(standard, 'RETIRE')"
            >
              停用标准
            </button>
          </div>
        </article>
        <section
          v-if="actionTarget?.kind === 'standard' && actionTarget.id === standard.id"
          class="finance-folio"
          aria-label="收费标准处理确认"
        >
          <form class="finance-confirm" @submit.prevent="executeAction">
            <h3>{{ paymentActionLabels[actionTarget.action] || actionTarget.action }} · {{ actionTarget.title }}</h3>
            <label>
              处理说明（必填，至少 5 个字）
              <textarea v-model="actionReason" required minlength="5" maxlength="500" rows="2" :disabled="busy" />
            </label>
            <div class="finance-actions">
              <button class="button primary compact" :disabled="busy">{{ busy ? '正在处理…' : '确认处理' }}</button>
              <button type="button" class="button compact" :disabled="busy" @click="actionTarget = null">取消</button>
            </div>
          </form>
        </section>
      </div>
    </section>

    <FinanceBasesTab
      v-if="tab === 'bases'"
      :bases="bases"
      :selected-id="String(route.query.id ?? '')"
      :can-manage="canManage"
      :loading="loading"
      :busy="busy"
      @use-basis="useBasis"
      @return-basis="
        basis =>
          chooseAction({
            kind: 'basis',
            id: basis.id,
            action: 'RETURN',
            version: basis.version,
            title: basis.studentName + ' · ' + basis.billingPeriod,
          })
      "
    />

    <FinanceRequestsTab
      v-if="tab === 'requests'"
      :requests="requests"
      :current-user-id="currentUser?.id"
      :can-review="canReview"
      :loading="loading"
      :busy="busy"
      @open-bill="openBill"
      @request-action="requestAction"
    />

    <FinanceReconciliationTab
      v-if="tab === 'reconciliation'"
      v-model:csv="statementCsv"
      :preview="statementPreview"
      :can-manage="canManage"
      :can-review="canReview"
      :busy="busy"
      @read-statement="readStatement"
      @compare-statement="compareStatement"
      @open-bill="openBill"
    />
  </div>
</template>
