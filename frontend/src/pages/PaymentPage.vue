<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { api, ApiError, errorMessage } from '../lib/api'
import DownloadLink from '../components/DownloadLink.vue'
import BaseSelect from '../components/BaseSelect.vue'
import DormRechargePanel from '../components/DormRechargePanel.vue'
import { PAYMENT_POLL_MS, filterPaymentBills, paymentActionLabels, paymentSettlementText, paymentWorkflowText, safeAlipayQrCode, safeSandboxPaymentUrl, type PaymentBill, type PaymentCategory, type PaymentHistory } from '../lib/payment'
import '../styles/finance.css'

const route = useRoute(), router = useRouter()
const dormRecharge = ref<{ refresh: () => Promise<void> } | null>(null)
async function rechargeCreated(bill: PaymentBill) { await openBill(bill); await load(); message.value = '宿舍充值订单已创建，请核对后完成沙箱支付。' }
const categories = ref<PaymentCategory[]>([]), bills = ref<PaymentBill[]>([]), selected = ref<PaymentBill | null>(null), history = ref<PaymentHistory[]>([])
const categoryFilter = ref(''), stateFilter = ref(''), loading = ref(true), busy = ref(false), error = ref(''), message = ref('')
const stateOptions = [
  { value: '', label: '全部状态' },
  { value: 'UNPAID', label: '待支付' },
  { value: 'PAID', label: '已支付' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已作废 / 已减免' },
]
const qrImage = ref(''), qrBillId = ref<number | null>(null), refundReason = ref(''), showRefund = ref(false), refundKey = ref(crypto.randomUUID())
const money = (cents: number) => '¥' + (cents / 100).toFixed(2)
const outstanding = computed(() => bills.value.filter(bill => bill.workflowStatus === 'PUBLISHED' && bill.status === 'UNPAID' && !bill.dormRecharge))
const outstandingTotal = computed(() => outstanding.value.reduce((sum, bill) => sum + bill.amountCents, 0))
const filtered = computed(() => filterPaymentBills(bills.value, categoryFilter.value).filter(bill => !stateFilter.value ||
  (stateFilter.value === 'REFUNDED' ? bill.refundStatus === 'REFUNDED' : stateFilter.value === 'CLOSED' ? ['VOIDED', 'WAIVED'].includes(bill.workflowStatus) :
    bill.status === stateFilter.value && bill.refundStatus !== 'REFUNDED' && bill.workflowStatus === 'PUBLISHED')))
const visibleCategories = computed(() => categories.value.filter(category => category.enabled !== false || bills.value.some(bill => bill.categoryCode === category.code)))
const canPay = (bill: PaymentBill) => bill.workflowStatus === 'PUBLISHED' && bill.status === 'UNPAID' && !bill.pendingRequestId
const canRefund = (bill: PaymentBill) => bill.status === 'PAID' && !bill.pendingRequestId && ['NONE', 'REJECTED'].includes(bill.refundStatus)
const overdue = (bill: PaymentBill) => bill.status === 'UNPAID' && bill.workflowStatus === 'PUBLISHED' && bill.dueDate && bill.dueDate < new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
watch(refundReason, () => { refundKey.value = crypto.randomUUID() })
let loadSequence = 0, pollTimer: ReturnType<typeof setTimeout> | undefined
function stopPolling() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function closeQr() { stopPolling(); qrImage.value = ''; qrBillId.value = null }
function replaceBill(bill: PaymentBill) { bills.value = bills.value.map(item => item.id === bill.id ? bill : item); if (selected.value?.id === bill.id) selected.value = bill }
async function load() {
  const sequence = ++loadSequence; loading.value = true; error.value = ''
  try {
    const [catalog, items] = await Promise.all([api<PaymentCategory[]>('/payments/categories'), api<PaymentBill[]>('/payments/mine')])
    const id = Number(route.query.id)
    const detail = Number.isSafeInteger(id) && id > 0 ? await api<PaymentBill>(`/payments/mine/${id}`) : null
    const events = detail ? await api<PaymentHistory[]>(`/payments/mine/${id}/history`) : []
    if (sequence !== loadSequence) return
    categories.value = catalog; bills.value = items; selected.value = detail; history.value = events; if (detail) replaceBill(detail)
  } catch (cause) { if (sequence === loadSequence) error.value = errorMessage(cause) }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function openBill(bill: PaymentBill) { await router.replace({ query: { id: String(bill.id), tab: 'details' } }) }
async function pay(bill: PaymentBill) {
  busy.value = true; error.value = ''; message.value = ''; closeQr()
  try {
    const result = await api<{ qrCode?: string; url?: string }>(`/payments/mine/${bill.id}/pay`, { method: 'POST' })
    if (result.qrCode) {
      if (!safeAlipayQrCode(result.qrCode)) throw new Error('支付二维码无效，请联系财务核验。')
      qrImage.value = await QRCode.toDataURL(result.qrCode, { width: 240, margin: 1 }); qrBillId.value = bill.id; startPolling(bill.id)
    } else {
      if (!result.url || !safeSandboxPaymentUrl(result.url)) throw new Error('支付地址无效，请联系财务核验。')
      window.location.assign(result.url)
    }
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'BILL_PAID') { await load(); message.value = '该账单已确认支付，请查看缴费记录。' }
    else error.value = errorMessage(cause)
  } finally { busy.value = false }
}
function startPolling(id: number) {
  stopPolling(); pollTimer = setTimeout(async () => {
    if (qrBillId.value !== id) return
    try {
      const bill = await api<PaymentBill>(`/payments/mine/${id}`); replaceBill(bill)
      if (bill.lastSyncError) error.value = bill.lastSyncError
      else error.value = ''
      if (bill.status === 'PAID') { closeQr(); message.value = `「${bill.title}」已确认支付，可查看并下载缴费凭证。`; await load(); await dormRecharge.value?.refresh(); return }
      if (!canPay(bill)) { closeQr(); error.value = '账单正在办理财务调整，已暂停发起支付，请查看账单详情。'; return }
    } catch (cause) { error.value = errorMessage(cause) }
    startPolling(id)
  }, PAYMENT_POLL_MS)
}
async function refund() {
  if (!selected.value) return
  busy.value = true; error.value = ''
  try {
    await api(`/payments/mine/${selected.value.id}/refund`, { method: 'POST', body: JSON.stringify({ requestKey: refundKey.value, version: selected.value.version, reason: refundReason.value.trim() }) })
    showRefund.value = false; refundReason.value = ''; await load(); message.value = '退款申请已提交财务复核。实际退款结果以平台确认后显示的状态为准。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
const refundDirty = computed(() => showRefund.value && Boolean(refundReason.value.trim()))
function confirmLeave() {
  if (busy.value) return false
  return !refundDirty.value || window.confirm('退款原因尚未提交，确定离开并放弃？')
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (busy.value || refundDirty.value) { event.preventDefault(); event.returnValue = '' }
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => to.query.id === from.query.id || confirmLeave())
watch(() => route.query.id, (id, previousId) => {
  if (id !== previousId) { showRefund.value = false; refundReason.value = '' }
})
watch(() => route.fullPath, () => { void load() }, { immediate: true })
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { stopPolling(); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="payment-office">
    <header class="page-heading-row"><div><span class="portal-context">我的校园 · 财务服务</span><h1>缴费大厅</h1><p class="muted">查看应缴费用、核对收费依据，跟进支付与退款记录。</p></div><button class="button compact" :disabled="loading || busy" @click="load">刷新账单</button></header>
    <p class="finance-note">当前账单和支付宝支付用于毕业设计沙箱演示。缴费凭证为演示记录，不是学校正式收费票据。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <DormRechargePanel ref="dormRecharge" @created="rechargeCreated" />
    <section class="finance-metrics" aria-label="本人缴费概况"><div><span>应缴金额</span><strong>{{ loading || error ? '—' : money(outstandingTotal) }}</strong><small>已发布、尚未支付的账单</small></div><div><span>待缴笔数</span><strong>{{ loading || error ? '—' : outstanding.length }}</strong><small>请逐笔核对账期与截止日期</small></div><div><span>已确认支付</span><strong>{{ loading || error ? '—' : bills.filter(bill => bill.status === 'PAID' && bill.refundStatus !== 'REFUNDED').length }}</strong><small>可下载本人缴费凭证</small></div><div><span>退款记录</span><strong>{{ loading || error ? '—' : bills.filter(bill => bill.refundStatus === 'REFUNDED').length }}</strong><small>以支付平台确认结果为准</small></div></section>
    <section v-if="qrImage" class="portal-panel finance-panel payment-qr" aria-live="polite"><h2>使用支付宝沙箱 App 扫码支付</h2><img :src="qrImage" width="240" height="240" alt="支付宝沙箱付款二维码" /><p class="muted">支付后会自动核验结果。关闭二维码后仍可在账单中查看支付状态。</p><button class="button" @click="closeQr">关闭二维码</button></section>

    <section v-if="selected" class="portal-panel finance-panel" aria-labelledby="bill-detail-heading">
<div class="finance-heading"><div><span class="finance-badge">{{ selected.categoryName }} · {{ paymentWorkflowText(selected.workflowStatus) }} · {{ paymentSettlementText(selected) }}</span><h2 id="bill-detail-heading">{{ selected.title }}</h2><p class="muted">{{ selected.billingPeriod }}</p></div><strong class="finance-total">{{ money(selected.amountCents) }}</strong></div>
      <dl class="finance-facts"><div><dt>缴费截止</dt><dd>{{ selected.dueDate || (selected.dormRecharge ? '预充值订单' : '历史账单未设置') }}<span v-if="overdue(selected)" class="finance-warning">逾期待缴</span></dd></div><div><dt>收费标准与数量</dt><dd>{{ selected.standardName || '财务手工核定' }} · {{ selected.quantity }}</dd></div><div class="finance-wide"><dt>收费依据</dt><dd>{{ selected.basis }}</dd></div><div class="finance-wide"><dt>订单号</dt><dd>{{ selected.orderNo }}</dd></div><div v-if="selected.tradeNo" class="finance-wide"><dt>交易号</dt><dd>{{ selected.tradeNo }}</dd></div></dl>
      <p v-if="selected.lastSyncError" class="finance-warning">{{ selected.lastSyncError }}</p><p v-if="selected.pendingRequestId" class="finance-note">该账单有财务申请正在处理中。退款和账单调整尚未完成时，以当前确认状态为准。</p>
      <div class="finance-actions"><button v-if="canPay(selected)" class="button primary" :disabled="busy" @click="pay(selected)">前往沙箱支付</button><DownloadLink v-if="selected.status === 'PAID'" :href="`/api/payments/mine/${selected.id}/receipt`" expected-type="application/pdf" class="button compact">下载缴费凭证</DownloadLink><button v-if="canRefund(selected)" class="button compact" :disabled="busy" @click="showRefund = !showRefund">申请全额退款</button></div>
      <form v-if="showRefund && canRefund(selected)" class="finance-form finance-subsection" @submit.prevent="refund"><label>退款原因<textarea v-model="refundReason" required minlength="5" maxlength="500" rows="3" :disabled="busy" placeholder="请说明需要退款的原因，财务人员将进行复核" /></label><div class="finance-actions"><button class="button primary" :disabled="busy">提交退款申请</button><button type="button" class="button" :disabled="busy" @click="showRefund = false">取消</button></div></form>
      <section class="finance-subsection"><h3>办理记录</h3><ol class="finance-history"><li v-for="event in history" :key="event.id"><strong>{{ paymentActionLabels[event.action] || event.action }}</strong><span>{{ event.actorName }} · {{ event.createdAt.slice(0, 16).replace('T', ' ') }}</span><p>{{ event.reason }}</p></li></ol><p v-if="!history.length" class="muted">历史账单尚无新增办理记录。</p></section>
    </section>

    <section class="portal-panel finance-panel">
      <div class="finance-heading payment-list-heading"><h2>本人账单</h2><label class="payment-state-filter"><span>缴费状态</span><BaseSelect v-model="stateFilter" :options="stateOptions" aria-label="缴费状态" compact /></label></div>
      <nav class="payment-category-tabs" aria-label="按收费分类筛选"><button type="button" :aria-pressed="categoryFilter === ''" @click="categoryFilter = ''">全部 <span>{{ bills.length }}</span></button><button v-for="category in visibleCategories" :key="category.code" type="button" :aria-pressed="categoryFilter === category.code" @click="categoryFilter = category.code">{{ category.name }} <span>{{ filterPaymentBills(bills, category.code).length }}</span></button></nav>
      <p v-if="loading" role="status">正在读取本人账单…</p><p v-else-if="!bills.length && !error" class="empty-state">目前没有已发布账单。有新的应缴费用时，会通过站内消息通知你。</p><p v-else-if="!filtered.length && !error" class="empty-state">当前分类和状态下暂无账单，可切换筛选查看其他记录。</p>
      <article v-for="bill in loading ? [] : filtered" :key="bill.id" class="finance-record"><div><span class="finance-badge">{{ bill.categoryName }}</span><h3>{{ bill.title }}</h3><p>{{ bill.billingPeriod }}<template v-if="bill.dueDate"> · 截止 {{ bill.dueDate }}</template><span v-if="overdue(bill)" class="payment-overdue">逾期待缴</span></p><small class="muted">{{ ['VOIDED', 'WAIVED'].includes(bill.workflowStatus) ? paymentWorkflowText(bill.workflowStatus) : paymentSettlementText(bill) }}<template v-if="bill.pendingRequestId"> · 财务申请办理中</template></small></div><div class="finance-record-end"><strong>{{ money(bill.amountCents) }}</strong><div class="finance-actions"><button class="button compact" :disabled="busy" @click="openBill(bill)">查看详情</button><button v-if="canPay(bill)" class="button primary compact" :disabled="busy" @click="pay(bill)">前往沙箱支付</button></div></div></article>
    </section>
  </div>
</template>

<style scoped>
.payment-qr { display: grid; justify-items: center; gap: 14px; text-align: center; }.payment-qr img { max-width: 100%; height: auto; }.payment-overdue { display: inline-block; margin-left: 10px; color: #936023; font-size: .8rem; }.finance-form .finance-actions { margin-top: 16px; }
.payment-list-heading { align-items: center; gap: 16px; }
.payment-list-heading h2 { margin: 0; }
.payment-state-filter { width: 150px; max-width: 100%; }
.payment-category-tabs { display: flex; align-items: stretch; gap: 22px; overflow-x: auto; margin: 20px 0 14px; border-bottom: 1px solid #dce4d0; }
.payment-category-tabs button { flex: 0 0 auto; display: inline-flex; align-items: baseline; gap: 5px; padding: 11px 2px; border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; color: #7b886f; font: inherit; font-size: 13px; cursor: pointer; }
.payment-category-tabs button span { font-size: 11px; font-variant-numeric: tabular-nums; }
.payment-category-tabs button:hover { color: #315244; }
.payment-category-tabs button[aria-pressed='true'] { color: #315244; border-bottom-color: #315244; font-weight: 600; }
@media (max-width: 600px) { .payment-list-heading { align-items: flex-start; } .payment-state-filter { width: 138px; } .payment-category-tabs { gap: 18px; } }
</style>
