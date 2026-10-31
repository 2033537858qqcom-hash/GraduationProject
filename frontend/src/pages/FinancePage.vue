<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import DownloadLink from '../components/DownloadLink.vue'
import { centsFromInput, paymentActionLabels, paymentSettlementText, paymentWorkflowText, type PaymentBill } from '../lib/payment'
import { financeRecordText, financeRequestTypes, type FeeBasis, type FeeStandard, type FinanceCatalog, type FinanceDetail, type FinancePage, type FinanceRequest, type StatementPreview } from '../finance'
import '../styles/finance.css'

const route = useRoute(), router = useRouter()
const canManage = computed(() => currentUser.value?.permissions.includes('payment:manage') ?? false)
const canReview = computed(() => currentUser.value?.permissions.includes('payment:review') ?? false)
const tabs = [{ key: 'ledger', label: '账单台账' }, { key: 'standards', label: '收费标准' }, { key: 'bases', label: '业务收费依据' }, { key: 'requests', label: '作废、减免与退款' }, { key: 'reconciliation', label: '交易明细核对' }]
const tab = computed(() => tabs.some(item => item.key === route.query.tab) ? String(route.query.tab) : 'ledger')
const catalog = ref<FinanceCatalog | null>(null), ledger = ref<FinancePage | null>(null), detail = ref<FinanceDetail | null>(null)
const bases = ref<FeeBasis[]>([]), requests = ref<FinanceRequest[]>([])
const loading = ref(true), busy = ref(false), error = ref(''), message = ref('')
const filters = ref({ q: '', categoryCode: '', status: '', billingPeriod: '' })
const statusOptions = [{ value: '', label: '全部状态' }, ...['DRAFT', 'PENDING_REVIEW', 'RETURNED', 'PUBLISHED', 'VOIDED', 'WAIVED'].map(value => ({ value, label: paymentWorkflowText(value) })),
  { value: 'UNPAID', label: '待支付' }, { value: 'PAID', label: '已支付' }, { value: 'REFUNDED', label: '已退款' }, { value: 'OVERDUE', label: '逾期待缴' }, { value: 'UNCONFIRMED', label: '支付结果待核实' }]
const money = (cents: number) => '¥' + (cents / 100).toFixed(2)
const showBillForm = ref(false), editingId = ref<number | null>(null)
const emptyBill = () => ({ usernames: '', categoryCode: '', title: '', billingPeriod: '', dueDate: '', standardId: '', quantity: '1', amount: '', basis: '', basisId: null as number | null, version: 0, reason: '' })
const form = ref(emptyBill()), formKey = ref(crypto.randomUUID())
const standardsForForm = computed(() => catalog.value?.standards.filter(item => item.status === 'ACTIVE' && item.categoryCode === form.value.categoryCode) ?? [])
const selectedStandard = computed(() => standardsForForm.value.find(item => String(item.id) === form.value.standardId))
watch(form, () => { formKey.value = crypto.randomUUID() }, { deep: true })
watch(() => form.value.categoryCode, () => { if (!standardsForForm.value.some(item => String(item.id) === form.value.standardId)) form.value.standardId = '' })
const standardForm = ref({ name: '', categoryCode: '', unit: '', price: '', campusId: '', majorId: '', reason: '' })
const standardKey = ref(crypto.randomUUID())
watch(standardForm, () => { standardKey.value = crypto.randomUUID() }, { deep: true })
type ActionTarget = { kind: 'bill' | 'standard' | 'request' | 'basis' | 'billRequest'; id: number; action: string; version: number; title: string }
const actionTarget = ref<ActionTarget | null>(null), actionReason = ref(''), actionKey = ref(crypto.randomUUID())
const statementCsv = ref(''), statementPreview = ref<StatementPreview | null>(null)
watch(statementCsv, () => { statementPreview.value = null })
watch(actionReason, () => { actionKey.value = crypto.randomUUID() })
let loadSequence = 0
function queryParams() {
  const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
  for (const key of ['q', 'categoryCode', 'status', 'billingPeriod']) if (typeof route.query[key] === 'string' && route.query[key]) params.set(key, String(route.query[key]))
  return params
}
const exportUrl = computed(() => '/api/finance/payments/export?' + queryParams().toString())
async function load() {
  const sequence = ++loadSequence; loading.value = true; error.value = ''
  for (const key of Object.keys(filters.value) as (keyof typeof filters.value)[]) filters.value[key] = String(route.query[key] ?? '')
  try {
    const [nextCatalog, nextLedger, nextBases, nextRequests] = await Promise.all([
      api<FinanceCatalog>('/finance/payments/catalog'), api<FinancePage>('/finance/payments?' + queryParams()),
      api<FeeBasis[]>('/finance/payments/bases'), api<FinanceRequest[]>('/finance/payments/requests'),
    ])
    const id = Number(route.query.id)
    const nextDetail = Number.isSafeInteger(id) && id > 0 && route.query.tab === 'details' ? await api<FinanceDetail>(`/finance/payments/${id}/detail`) : null
    if (sequence !== loadSequence) return
    catalog.value = nextCatalog; ledger.value = nextLedger; bases.value = nextBases; requests.value = nextRequests; detail.value = nextDetail
  } catch (cause) { if (sequence === loadSequence) error.value = errorMessage(cause) }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function changeTab(value: string) { if (!busy.value) await router.replace({ query: { ...route.query, tab: value, id: undefined } }) }
async function search() { await router.replace({ query: { ...filters.value, tab: 'ledger', page: '1' } }) }
async function page(value: number) { await router.replace({ query: { ...route.query, page: String(value) } }) }
async function openBill(id: number) { await router.replace({ query: { ...route.query, id: String(id), tab: 'details' } }) }
function newBill() { editingId.value = null; form.value = emptyBill(); showBillForm.value = true; message.value = ''; error.value = '' }
function editBill(bill: PaymentBill) {
  editingId.value = bill.id; showBillForm.value = true
  form.value = { usernames: bill.studentUsername, categoryCode: bill.categoryCode, title: bill.title, billingPeriod: bill.billingPeriod, dueDate: bill.dueDate ?? '',
    standardId: bill.standardId ? String(bill.standardId) : '', quantity: bill.quantity, amount: (bill.amountCents / 100).toFixed(2), basis: bill.basis, basisId: bill.sourceBasisId, version: bill.version, reason: '' }
}
function useBasis(basis: FeeBasis) {
  newBill(); form.value = { ...emptyBill(), usernames: basis.username, categoryCode: basis.categoryCode, title: basis.categoryName,
    billingPeriod: basis.billingPeriod, quantity: basis.quantity, basis: basis.basis, basisId: basis.id }
}
async function saveBill() {
  const usernames = form.value.usernames.split(/[\s,，]+/).filter(Boolean)
  if (!usernames.length || usernames.length > 100 || new Set(usernames).size !== usernames.length) { error.value = '请填写1—100个不重复的学生账号。'; return }
  if ((editingId.value || form.value.basisId) && usernames.length !== 1) { error.value = '修改账单或按单条业务依据建单时，只能指定一名学生。'; return }
  const amount = form.value.standardId ? null : centsFromInput(form.value.amount.trim())
  if (!form.value.standardId && amount === null) { error.value = '请核对手工金额，最多保留两位小数。'; return }
  const payload = { requestKey: formKey.value, username: usernames[0], categoryCode: form.value.categoryCode, title: form.value.title.trim(), billingPeriod: form.value.billingPeriod.trim(),
    dueDate: form.value.dueDate, standardId: form.value.standardId ? Number(form.value.standardId) : null, quantity: form.value.quantity,
    amountCents: amount, basis: form.value.basis.trim(), basisId: form.value.basisId, version: form.value.version, reason: form.value.reason }
  busy.value = true; error.value = ''; message.value = ''
  try {
    if (usernames.length > 1) {
      const result = await api<PaymentBill[]>('/finance/payments/batch', { method: 'POST', body: JSON.stringify({ requestKey: formKey.value, usernames, template: payload }) })
      showBillForm.value = false; form.value = emptyBill(); await load(); message.value = `已生成 ${result.length} 笔草稿，请逐笔核对后提交复核。`
    } else {
      const result = await api<PaymentBill>(editingId.value ? `/finance/payments/${editingId.value}` : '/finance/payments', { method: editingId.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
      showBillForm.value = false; form.value = emptyBill(); await openBill(result.id); await load(); message.value = '账单草稿已保存，学生将在独立复核发布后看到。'
    }
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function createStandard() {
  const price = centsFromInput(standardForm.value.price.trim())
  if (price === null) { error.value = '请填写有效单价，最多保留两位小数。'; return }
  busy.value = true; error.value = ''
  try {
    await api('/finance/payments/standards', { method: 'POST', body: JSON.stringify({ requestKey: standardKey.value, name: standardForm.value.name.trim(), categoryCode: standardForm.value.categoryCode,
      unit: standardForm.value.unit.trim(), unitPriceCents: price, campusId: standardForm.value.campusId ? Number(standardForm.value.campusId) : null, majorId: standardForm.value.majorId ? Number(standardForm.value.majorId) : null, reason: standardForm.value.reason.trim() }) })
    standardForm.value = { name: '', categoryCode: '', unit: '', price: '', campusId: '', majorId: '', reason: '' }; await load(); message.value = '收费标准已送交独立复核，通过后可用于建单。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function chooseAction(target: ActionTarget) { actionTarget.value = target; actionReason.value = ''; actionKey.value = crypto.randomUUID(); error.value = '' }
function billAction(action: string) {
  if (!detail.value) return
  if (action === 'EDIT') { editBill(detail.value.bill); return }
  chooseAction({ kind: action.startsWith('REQUEST_') ? 'billRequest' : 'bill', id: detail.value.bill.id, action, version: detail.value.bill.version, title: detail.value.bill.title })
}
async function executeAction() {
  const target = actionTarget.value
  if (!target || actionReason.value.trim().length < 5) { error.value = '请填写至少5个字符的处理说明。'; return }
  const payload = { action: target.action, version: target.version, requestKey: actionKey.value, reason: actionReason.value.trim() }
  const path = target.kind === 'bill' ? `/finance/payments/${target.id}/actions` : target.kind === 'standard' ? `/finance/payments/standards/${target.id}/actions`
    : target.kind === 'request' ? `/finance/payments/requests/${target.id}/actions` : target.kind === 'basis' ? `/finance/payments/bases/${target.id}/return` : `/finance/payments/${target.id}/requests`
  busy.value = true; error.value = ''; message.value = ''
  try {
    const result = await api<{ status?: string; lastError?: string }>(path, { method: 'POST', body: JSON.stringify(target.kind === 'billRequest' ? { ...payload, type: target.action.replace('REQUEST_', '') } : payload) })
    actionTarget.value = null; await load()
    if (result.status === 'FAILED' || result.status === 'PROCESSING') error.value = result.lastError || '支付平台处理结果尚未确认，请在原申请上核对和重试。'
    else message.value = target.kind === 'billRequest' ? '申请已送交独立财务复核，尚未执行资金或账单调整。' : '处理结果已保存。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function standardAction(item: FeeStandard, action: string) { chooseAction({ kind: 'standard', id: item.id, action, version: item.version, title: item.name }) }
function requestAction(item: FinanceRequest, action: string) { chooseAction({ kind: 'request', id: item.id, action, version: item.version, title: `账单 #${item.billId} · ${financeRequestTypes[item.type]}` }) }
async function readStatement(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (file.size > 262144) { error.value = '每次交易明细最多256KB，请分批核对。'; return }
  try { statementCsv.value = await file.text() }
  catch { error.value = '无法读取文件，请重新选择UTF-8 CSV文件。' }
}
async function compareStatement() {
  busy.value = true; error.value = ''; message.value = ''
  try { statementPreview.value = await api<StatementPreview>('/finance/payments/reconciliation/preview', { method: 'POST', body: JSON.stringify({ csv: statementCsv.value }) }); message.value = '明细核对已完成，未更改任何账单金额或到账状态。' }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
const statementResults: Record<string, string> = { MATCH: '一致', DIFFERENCE: '存在差异', NOT_FOUND: '本地无此订单', INVALID: '格式待核对', DUPLICATE: '重复订单' }
const hasUnsaved = computed(() => showBillForm.value && (form.value.usernames || form.value.title || form.value.basis))
onBeforeRouteLeave(() => busy.value ? false : !hasUnsaved.value || window.confirm('尚有未保存的账单内容，确定离开？'))
watch(() => route.fullPath, () => { void load() }, { immediate: true })
</script>

<template>
  <div class="payment-office">
    <header class="page-heading-row"><div><span class="portal-context">财务服务 · {{ canManage ? '经办工作台' : '独立复核工作台' }}</span><h1>财务收费</h1><p class="muted">核对收费依据，分工经办与复核，跟进每一笔应缴费用。</p></div><div class="finance-actions"><button v-if="canManage" class="button primary" :disabled="loading || busy" @click="newBill">新建账单</button><button class="button compact" :disabled="loading || busy" @click="load">刷新</button></div></header>
    <p class="finance-note">当前使用支付宝沙箱。新账单须经其他人员复核发布；收费标准不会预置学校实际价格。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <section v-if="ledger" class="finance-metrics" aria-label="当前筛选的收费统计"><div><span>待缴金额</span><strong>{{ money(ledger.summary.unpaidAmountCents) }}</strong><small>{{ ledger.summary.unpaidCount }} 笔账单</small></div><div><span>已收款净额</span><strong>{{ money(ledger.summary.paidAmountCents) }}</strong><small>不含已全额退款账单</small></div><div><span>待复核 / 逾期</span><strong>{{ ledger.summary.pendingReviewCount }} / {{ ledger.summary.overdueCount }}</strong><small>待办与逾期待缴分开统计</small></div><div><span>已退款金额</span><strong>{{ money(ledger.summary.refundedAmountCents) }}</strong><small>以平台确认结果为准</small></div></section>
    <nav class="finance-tabs" aria-label="财务功能"><button v-for="item in tabs" :key="item.key" type="button" :aria-pressed="tab === item.key" :disabled="busy" @click="changeTab(item.key)">{{ item.label }}</button></nav>

    <section v-if="showBillForm" class="portal-panel finance-panel">
<div class="finance-heading"><h2>{{ editingId ? '修改账单草稿' : '新建收费账单' }}</h2><button class="text-button" :disabled="busy" @click="showBillForm = false">收起表单</button></div>
      <form class="finance-form" @submit.prevent="saveBill">
<fieldset :disabled="busy || loading">
        <label class="finance-wide">{{ editingId ? '学生账号' : '学生账号（多个账号用换行或逗号分隔，最多100人）' }}<textarea v-model="form.usernames" required :rows="editingId ? 1 : 2" maxlength="6500" placeholder="例如 20260001" /></label>
        <label>收费分类<select v-model="form.categoryCode" required :disabled="!!form.basisId"><option value="" disabled>请选择分类</option><option v-for="category in catalog?.categories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label>
        <label>费用名称<input v-model="form.title" required maxlength="120" placeholder="例如 2026—2027 学年学费" /></label>
        <label>收费账期<input v-model="form.billingPeriod" required maxlength="80" :readonly="!!form.basisId" placeholder="例如 2026年10月" /></label>
        <label>缴费截止日期<input v-model="form.dueDate" type="date" required /></label>
        <label>计费方式<select v-model="form.standardId"><option value="">手工核定金额</option><option v-for="standard in standardsForForm" :key="standard.id" :value="String(standard.id)">{{ standard.name }} · {{ money(standard.unitPriceCents) }}/{{ standard.unit }}</option></select></label>
        <label>数量 / 分摊用量<input v-model="form.quantity" required inputmode="decimal" :readonly="!!form.basisId" placeholder="例如 1 或 2.5" /></label>
        <p v-if="selectedStandard" class="finance-wide muted">采用已复核标准：{{ money(selectedStandard.unitPriceCents) }}/{{ selectedStandard.unit }}。保存后请核对系统计算的总金额。</p>
        <label v-else>核定总额（元）<input v-model="form.amount" required inputmode="decimal" placeholder="例如 12.50" /></label>
        <label class="finance-wide">收费依据<textarea v-model="form.basis" required minlength="10" maxlength="1000" rows="3" placeholder="说明收费范围、依据文件或实际用量、分摊方式与金额核对情况" /></label>
        <label v-if="editingId" class="finance-wide">修改说明<textarea v-model="form.reason" required minlength="5" maxlength="500" rows="2" /></label>
        <p v-if="form.basisId" class="finance-wide muted">已关联业务收费依据 #{{ form.basisId }}，学生、分类、账期和用量须与原依据一致。</p>
        <div class="finance-actions finance-wide"><button class="button primary">{{ busy ? '正在保存…' : editingId ? '保存修改' : '保存为草稿' }}</button><span class="muted">保存后仍需提交独立复核。</span></div>
      </fieldset>
</form>
    </section>

    <section v-if="actionTarget" class="portal-panel finance-panel finance-confirm" aria-labelledby="finance-action-heading"><h2 id="finance-action-heading">{{ paymentActionLabels[actionTarget.action] || actionTarget.action }}</h2><p>{{ actionTarget.title }}</p><p class="muted">操作会记录办理人和原因。已发布账单的作废、减免、退款须由其他财务人员复核。</p><form @submit.prevent="executeAction"><label>处理说明<textarea v-model="actionReason" required minlength="5" maxlength="500" rows="3" :disabled="busy" /></label><div class="finance-actions"><button class="button primary" :disabled="busy">{{ busy ? '正在处理…' : '确认处理' }}</button><button type="button" class="button" :disabled="busy" @click="actionTarget = null">取消</button></div></form></section>

    <section v-if="detail && tab === 'ledger'" class="portal-panel finance-panel">
<div class="finance-heading"><div><span class="finance-badge">{{ paymentWorkflowText(detail.bill.workflowStatus) }} · {{ paymentSettlementText(detail.bill) }}</span><h2>{{ detail.bill.title }}</h2><p class="muted">{{ detail.bill.studentName }} · {{ detail.bill.studentUsername }} · {{ detail.bill.billingPeriod }}</p></div><strong class="finance-total">{{ money(detail.bill.amountCents) }}</strong></div>
      <dl class="finance-facts"><div><dt>缴费截止</dt><dd>{{ detail.bill.dueDate || '历史账单未设置' }}</dd></div><div><dt>经办人员</dt><dd>{{ detail.bill.creatorName }}</dd></div><div><dt>收费标准</dt><dd>{{ detail.bill.standardName || '手工核定金额' }}</dd></div><div><dt>数量</dt><dd>{{ detail.bill.quantity }}</dd></div><div class="finance-wide"><dt>收费依据</dt><dd>{{ detail.bill.basis }}</dd></div><div class="finance-wide"><dt>订单号</dt><dd>{{ detail.bill.orderNo }}</dd></div><div v-if="detail.bill.tradeNo" class="finance-wide"><dt>平台交易号</dt><dd>{{ detail.bill.tradeNo }}</dd></div></dl>
      <p v-if="detail.bill.lastSyncError" class="alert">{{ detail.bill.lastSyncError }}</p><p v-if="detail.bill.pendingRequestId" class="finance-note">该账单有待完成的财务处理申请，学生暂不能继续发起支付。</p>
      <div class="finance-actions"><button v-for="action in detail.allowedActions" :key="action" class="button compact" :disabled="busy" @click="billAction(action)">{{ paymentActionLabels[action] || action }}</button></div>
      <section v-if="detail.requests.length" class="finance-subsection"><h3>财务处理申请</h3><article v-for="request in detail.requests" :key="request.id" class="finance-record"><div><strong>{{ financeRequestTypes[request.type] }} · {{ financeRecordText(request.status) }}</strong><p>{{ request.reason }}</p><p v-if="request.lastError" class="finance-warning">{{ request.lastError }}</p></div><div class="finance-actions"><template v-if="canReview && request.status === 'SUBMITTED' && request.requestedBy !== currentUser?.id"><button class="button compact" :disabled="busy" @click="requestAction(request, 'APPROVE')">复核通过</button><button class="button compact" :disabled="busy" @click="requestAction(request, 'REJECT')">不予通过</button></template><button v-if="request.status === 'FAILED'" class="button compact" :disabled="busy" @click="requestAction(request, 'RETRY')">核对并重试原申请</button></div></article></section>
      <section class="finance-subsection"><h3>办理记录</h3><ol class="finance-history"><li v-for="event in detail.history" :key="event.id"><strong>{{ paymentActionLabels[event.action] || event.action }}</strong><span>{{ event.actorName }} · {{ event.createdAt.slice(0, 16).replace('T', ' ') }}</span><p>{{ event.reason }}</p></li></ol></section>
    </section>

    <section v-if="tab === 'ledger'" class="portal-panel finance-panel">
<div class="finance-heading"><h2>账单台账</h2><DownloadLink :href="exportUrl" expected-type="text/csv" class="button compact">导出当前筛选</DownloadLink></div>
      <form class="finance-filters" @submit.prevent="search"><label>学生 / 费用名称<input v-model="filters.q" maxlength="120" placeholder="账号、姓名或名称" /></label><label>分类<select v-model="filters.categoryCode"><option value="">全部分类</option><option v-for="category in catalog?.categories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label><label>状态<select v-model="filters.status"><option v-for="option in statusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label>账期<input v-model="filters.billingPeriod" maxlength="80" placeholder="完整账期名称" /></label><button class="button" :disabled="loading || busy">查询</button></form>
      <p v-if="loading" role="status">正在读取账单及统计…</p><p v-else-if="!ledger?.items.length && !error" class="empty-state">当前筛选下没有账单。</p>
      <article v-for="bill in loading ? [] : ledger?.items" :key="bill.id" class="finance-record"><div><span class="finance-badge">{{ bill.categoryName }}</span><h3>{{ bill.title }}</h3><p>{{ bill.studentName }} · {{ bill.studentUsername }} · {{ bill.billingPeriod }}</p><small class="muted">{{ paymentWorkflowText(bill.workflowStatus) }} · {{ paymentSettlementText(bill) }}<template v-if="bill.dueDate"> · 截止 {{ bill.dueDate }}</template></small></div><div class="finance-record-end"><strong>{{ money(bill.amountCents) }}</strong><button class="button compact" :disabled="busy" @click="openBill(bill.id)">查看与办理</button></div></article>
      <div v-if="ledger && ledger.total > 0" class="finance-pagination"><span>共 {{ ledger.total }} 笔 · 第 {{ ledger.page }} / {{ Math.ceil(ledger.total / ledger.pageSize) }} 页</span><div class="finance-actions"><button class="button compact" :disabled="loading || ledger.page <= 1" @click="page(ledger.page - 1)">上一页</button><button class="button compact" :disabled="loading || ledger.page * ledger.pageSize >= ledger.total" @click="page(ledger.page + 1)">下一页</button></div></div>
    </section>

    <section v-if="tab === 'standards'" class="portal-panel finance-panel">
<h2>收费标准</h2><p class="muted">标准经独立复核后生效。调整价格时新建标准，旧账单保留建单时的价格快照。</p>
      <form v-if="canManage" class="finance-form finance-subsection" @submit.prevent="createStandard"><fieldset :disabled="busy || loading"><label>标准名称<input v-model="standardForm.name" required maxlength="120" /></label><label>收费分类<select v-model="standardForm.categoryCode" required><option value="" disabled>请选择分类</option><option v-for="category in catalog?.categories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label><label>计价单位<input v-model="standardForm.unit" required maxlength="20" placeholder="学年、月、吨、度、次" /></label><label>单价（元）<input v-model="standardForm.price" required inputmode="decimal" /></label><label>适用校区<select v-model="standardForm.campusId"><option value="">全校区</option><option v-for="campus in catalog?.campuses" :key="campus.id" :value="String(campus.id)">{{ campus.name }}</option></select></label><label>适用专业<select v-model="standardForm.majorId"><option value="">全部专业</option><option v-for="major in catalog?.majors" :key="major.id" :value="String(major.id)">{{ major.name }}</option></select></label><label class="finance-wide">标准依据<textarea v-model="standardForm.reason" required minlength="5" maxlength="500" rows="2" /></label><button class="button primary">提交标准复核</button></fieldset></form>
      <p v-if="!loading && !catalog?.standards.length" class="empty-state">尚未建立收费标准。</p><article v-for="standard in catalog?.standards" :key="standard.id" class="finance-record"><div><span class="finance-badge">{{ standard.categoryName }} · {{ financeRecordText(standard.status) }}</span><h3>{{ standard.name }}</h3><p>{{ money(standard.unitPriceCents) }}/{{ standard.unit }} · {{ standard.creatorName }}</p><p class="muted">{{ standard.reason }}</p></div><div class="finance-actions"><template v-if="canReview && standard.status === 'PENDING_REVIEW' && standard.createdBy !== currentUser?.id"><button class="button compact" :disabled="busy" @click="standardAction(standard, 'APPROVE')">复核通过</button><button class="button compact" :disabled="busy" @click="standardAction(standard, 'RETURN')">退回</button></template><button v-if="canManage && standard.status === 'ACTIVE'" class="button compact" :disabled="busy" @click="standardAction(standard, 'RETIRE')">停用标准</button></div></article>
    </section>

    <section v-if="tab === 'bases'" class="portal-panel finance-panel"><h2>业务收费依据</h2><p class="muted">业务岗位只提供负责范围内的依据，金额由财务核定，发布仍需独立复核。</p><p v-if="!loading && !bases.length" class="empty-state">暂无业务部门提交的收费依据。</p><article v-for="basis in bases" :key="basis.id" class="finance-record" :class="{ 'finance-selected': String(basis.id) === route.query.id }"><div><span class="finance-badge">{{ basis.categoryName }} · {{ financeRecordText(basis.status) }}</span><h3>{{ basis.studentName }} · {{ basis.billingPeriod }}</h3><p>{{ basis.basis }}</p><p class="muted">{{ basis.sourceReference }} · 数量 {{ basis.quantity }} · 提供人 {{ basis.creatorName }}</p></div><div v-if="canManage && basis.status === 'SUBMITTED'" class="finance-actions"><button class="button compact" :disabled="busy" @click="useBasis(basis)">按此依据建单</button><button class="button compact" :disabled="busy" @click="chooseAction({ kind: 'basis', id: basis.id, action: 'RETURN', version: basis.version, title: basis.studentName + ' · ' + basis.billingPeriod })">退回补充</button></div></article></section>

    <section v-if="tab === 'requests'" class="portal-panel finance-panel"><h2>作废、减免与退款</h2><p class="muted">须由其他财务人员复核；只有支付平台确认后才会显示退款或关单完成。</p><p v-if="!loading && !requests.length" class="empty-state">暂无财务处理申请。</p><article v-for="request in requests" :key="request.id" class="finance-record"><div><span class="finance-badge">{{ financeRequestTypes[request.type] }} · {{ financeRecordText(request.status) }}</span><h3>账单 #{{ request.billId }}</h3><p>{{ request.reason }}</p><p class="muted">申请人 {{ request.requesterName }} · {{ request.createdAt.slice(0, 16).replace('T', ' ') }}</p><p v-if="request.lastError" class="finance-warning">{{ request.lastError }}</p></div><div class="finance-actions"><button class="button compact" :disabled="busy" @click="openBill(request.billId)">查看账单</button><template v-if="canReview && request.status === 'SUBMITTED' && request.requestedBy !== currentUser?.id"><button class="button compact" :disabled="busy" @click="requestAction(request, 'APPROVE')">复核通过</button><button class="button compact" :disabled="busy" @click="requestAction(request, 'REJECT')">不予通过</button></template><button v-if="request.status === 'FAILED'" class="button compact" :disabled="busy" @click="requestAction(request, 'RETRY')">核对并重试原申请</button></div></article></section>
    <section v-if="tab === 'reconciliation'" class="portal-panel finance-panel">
      <div class="finance-heading"><h2>交易明细核对</h2><DownloadLink href="/api/finance/payments/reconciliation/template" expected-type="text/csv" class="button compact">下载核对模板</DownloadLink></div>
      <p class="muted">将支付平台导出的交易明细按模板整理为UTF-8 CSV，可比较订单号、金额、交易号及支付状态。每批最多256KB、2000笔。</p>
      <p class="finance-note">导入明细只用于发现差异，不会将账单标记为已支付。发现差异后，请进入原账单使用“核验支付结果”向支付平台查单。</p>
      <form class="finance-form" @submit.prevent="compareStatement"><label>选择交易明细文件<input type="file" accept=".csv,text/csv" :disabled="busy" @change="readStatement" /></label><label>交易明细内容<textarea v-model="statementCsv" required maxlength="262144" rows="8" :disabled="busy" placeholder="订单号,金额（元）,交易号,支付状态" /></label><button class="button primary" :disabled="busy">{{ busy ? '正在核对…' : '生成差异核对结果' }}</button></form>
      <section v-if="statementPreview" class="finance-subsection"><p>一致 {{ statementPreview.matchedCount }} 笔 · 差异 {{ statementPreview.differenceCount }} 笔 · 本地无订单 {{ statementPreview.notFoundCount }} 笔 · 格式异常 {{ statementPreview.invalidCount }} 笔 · 重复 {{ statementPreview.duplicateCount }} 笔</p><article v-for="row in statementPreview.items" :key="row.line" class="finance-record"><div><span class="finance-badge">第 {{ row.line }} 行 · {{ statementResults[row.result] }}</span><h3>{{ row.orderNo }}</h3><p>明细金额 {{ row.amount }} 元 · 本地金额 {{ row.localAmountCents === null ? '—' : money(row.localAmountCents) }}</p><p>{{ row.explanation }}</p></div><button v-if="row.billId" class="button compact" :disabled="busy" @click="openBill(row.billId)">查看原账单</button></article></section>
    </section>
  </div>
</template>
