<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import { type PaymentCategory } from '../lib/payment'
import { financeRecordText, type FeeBasis } from '../finance'
import '../styles/finance.css'

const route = useRoute(), categories = ref<PaymentCategory[]>([]), items = ref<FeeBasis[]>([])
const loading = ref(true), busy = ref(false), error = ref(''), message = ref('')
const empty = () => ({ username: '', categoryCode: '', billingPeriod: '', quantity: '1', previousReading: '', currentReading: '', basis: '', correctionOf: null as number | null })
const form = ref(empty()), requestKey = ref(crypto.randomUUID())
watch(form, () => { requestKey.value = crypto.randomUUID() }, { deep: true })
const permittedCategories = computed(() => {
  const permissions = currentUser.value?.permissions ?? [], codes = new Set<string>()
  if (permissions.includes('academic:manage')) ['TUITION', 'TEXTBOOK'].forEach(code => codes.add(code))
  if (permissions.includes('housing:assign')) ['ACCOMMODATION', 'WATER', 'ELECTRICITY', 'UTILITIES'].forEach(code => codes.add(code))
  if (permissions.includes('repair:dispatch')) ['WATER', 'ELECTRICITY', 'UTILITIES'].forEach(code => codes.add(code))
  return categories.value.filter(category => codes.has(category.code))
})
const metered = computed(() => ['WATER', 'ELECTRICITY'].includes(form.value.categoryCode))
let sequence = 0
async function load() {
  const next = ++sequence; loading.value = true; error.value = ''
  try {
    const [catalog, page] = await Promise.all([api<{ categories: PaymentCategory[] }>('/payment-bases/catalog'), api<{ items: FeeBasis[] }>('/payment-bases')])
    if (next !== sequence) return
    categories.value = catalog.categories; items.value = page.items
  } catch (cause) { if (next === sequence) error.value = errorMessage(cause) }
  finally { if (next === sequence) loading.value = false }
}
function revise(item: FeeBasis) {
  form.value = { ...empty(), username: item.username, categoryCode: item.categoryCode, billingPeriod: item.billingPeriod, quantity: item.quantity, basis: item.basis, correctionOf: item.id }
  message.value = '请根据退回说明补充依据。原记录会保留，提交后形成修正版。'
}
async function submit() {
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api('/payment-bases', { method: 'POST', body: JSON.stringify({ ...form.value, username: form.value.username.trim(), billingPeriod: form.value.billingPeriod.trim(), basis: form.value.basis.trim(), requestKey: requestKey.value }) })
    form.value = empty(); await load(); message.value = '收费依据已提交财务核对。此时尚未生成或发布应缴账单。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
onBeforeRouteLeave(() => busy.value ? false : !(form.value.username || form.value.basis) || window.confirm('尚有未提交的收费依据，确定离开？'))
watch(() => route.fullPath, () => { void load() }, { immediate: true })
</script>

<template>
  <div class="payment-office">
<header class="page-heading-row"><div><span class="portal-context">岗位办理 · 收费业务依据</span><h1>收费依据</h1><p class="muted">提供本岗位核实过的收费依据，交由财务核定、复核和发布。</p></div><button class="button compact" :disabled="loading || busy" @click="load">刷新记录</button></header>
    <p class="finance-note">教务可提供本学院学费与教材费依据；宿管可提供负责楼栋住户的住宿、水电依据；后勤可提供负责校区的水电依据。提交依据不会直接向学生收费。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <section class="portal-panel finance-panel"><h2>{{ form.correctionOf ? `补充原依据 #${form.correctionOf}` : '提交收费依据' }}</h2><form class="finance-form" @submit.prevent="submit"><fieldset :disabled="loading || busy"><label>学生账号<input v-model="form.username" required maxlength="64" placeholder="填写负责范围内的学生账号" /></label><label>收费分类<select v-model="form.categoryCode" required><option value="" disabled>请选择分类</option><option v-for="category in permittedCategories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label><label>收费账期<input v-model="form.billingPeriod" required maxlength="80" placeholder="例如 2026年10月" /></label><label v-if="!metered">数量 / 分摊用量<input v-model="form.quantity" required inputmode="decimal" /></label><template v-if="metered"><label>上期表计读数<input v-model="form.previousReading" required inputmode="decimal" /></label><label>本期表计读数<input v-model="form.currentReading" required inputmode="decimal" /></label><p class="muted finance-wide">用量按两次读数之差计算。请在依据中说明表计对应住户及费用分摊方式，避免重复收费。</p></template><label class="finance-wide">收费依据与核对说明<textarea v-model="form.basis" required minlength="10" maxlength="1000" rows="4" placeholder="填写实际使用、住宿或教学收费依据；涉及水电分摊时说明分摊口径" /></label><div class="finance-actions finance-wide"><button class="button primary">{{ busy ? '正在提交…' : '提交财务核对' }}</button><button v-if="form.correctionOf" type="button" class="button" @click="form = empty()">取消修正</button></div></fieldset></form></section>
    <section class="portal-panel finance-panel"><h2>本人提供的收费依据</h2><p v-if="loading" role="status">正在读取记录…</p><p v-else-if="!items.length && !error" class="empty-state">尚未提交收费依据。</p><article v-for="item in loading ? [] : items" :key="item.id" class="finance-record" :class="{ 'finance-selected': String(item.id) === route.query.id }"><div><span class="finance-badge">{{ item.categoryName }} · {{ financeRecordText(item.status) }}</span><h3>{{ item.studentName }} · {{ item.billingPeriod }}</h3><p>{{ item.basis }}</p><p class="muted">{{ item.sourceReference }} · 数量 {{ item.quantity }}</p><p v-if="item.reviewComment" class="finance-warning">财务说明：{{ item.reviewComment }}</p><small class="muted">提交时间 {{ item.createdAt.slice(0, 16).replace('T', ' ') }}<template v-if="item.billId"> · 已关联账单 #{{ item.billId }}</template></small></div><button v-if="item.status === 'RETURNED'" class="button compact" :disabled="busy" @click="revise(item)">补充并创建修正版</button></article></section>
  </div>
</template>
