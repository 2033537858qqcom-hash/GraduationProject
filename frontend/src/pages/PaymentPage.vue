<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { centsFromInput, safeSandboxPaymentUrl } from '../lib/payment'

interface Bill { id: number; orderNo: string; title: string; amountCents: number; status: 'UNPAID' | 'PAID'; tradeNo: string | null }
const route = useRoute()
const admin = computed(() => route.path.startsWith('/admin/'))
const bills = ref<Bill[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const form = ref({ username: '', title: '', amount: '' })
let requestKey = crypto.randomUUID()
watch(form, () => { requestKey = crypto.randomUUID() }, { deep: true })

async function load() {
  if (admin.value) return
  loading.value = true; error.value = ''
  try { bills.value = await api<Bill[]>('/payments/mine') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
async function create() {
  const amountCents = centsFromInput(form.value.amount.trim())
  if (amountCents === null) { error.value = '请输入大于零、最多两位小数的金额。'; return }
  busy.value = true; error.value = ''; message.value = ''
  try {
    const result = await api<Bill>('/admin/payments', { method: 'POST', body: JSON.stringify({ requestKey, username: form.value.username.trim(), title: form.value.title.trim(), amountCents }) })
    message.value = `演示账单已创建，订单号 ${result.orderNo}。`
    form.value = { username: '', title: '', amount: '' }
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function pay(id: number) {
  busy.value = true; error.value = ''
  try {
    const result = await api<{url: string}>(`/payments/mine/${id}/pay`, { method: 'POST' })
    if (!safeSandboxPaymentUrl(result.url)) throw new Error('支付地址无效，请稍后重试。')
    window.location.assign(result.url)
  } catch (cause) { error.value = errorMessage(cause); busy.value = false }
}
watch(admin, () => { error.value = ''; message.value = ''; void load() })
onMounted(load)
</script>

<template>
  <main class="payment-page">
    <header class="payment-heading">
      <span class="portal-context">校园服务 · 沙箱演示</span>
      <h1>{{ admin ? '演示账单管理' : '我的演示缴费' }}</h1>
      <p class="muted">{{ admin ? '为演示学生创建明确的测试账单。' : '查看本人测试账单并使用支付宝沙箱完成支付。' }}</p>
    </header>
    <p class="payment-note">本页面用于毕业设计演示，账单和支付均为沙箱测试。</p>
    <p v-if="error" class="payment-error" role="alert">{{ error }}</p>
    <p v-if="message" class="payment-success" role="status">{{ message }}</p>
    <section v-if="admin" class="portal-panel payment-panel">
      <h2>创建演示账单</h2><p class="muted">仅支持已启用的演示学生账号。</p>
      <form class="payment-form" @submit.prevent="create">
        <label>学生账号<input v-model="form.username" required maxlength="64" autocomplete="off" placeholder="例如 20260001" /></label>
        <label>费用名称<input v-model="form.title" required maxlength="120" placeholder="例如 演示场地费用" /></label>
        <label>金额（元）<input v-model="form.amount" required inputmode="decimal" placeholder="例如 12.50" /></label>
        <button class="button primary" :disabled="busy">{{ busy ? '正在创建…' : '创建演示账单' }}</button>
      </form>
    </section>
    <section v-else class="portal-panel payment-panel" :aria-busy="loading">
      <div class="payment-list-heading"><div><h2>账单记录</h2><p class="muted">支付结果以服务端确认后的状态为准。</p></div><button class="button compact" :disabled="loading || busy" @click="load">刷新状态</button></div>
      <p v-if="loading">正在加载账单…</p>
      <p v-else-if="!bills.length && !error" class="muted">目前没有演示账单。</p>
      <ul v-else class="payment-list">
        <li v-for="bill in bills" :key="bill.id">
          <div><h3>{{ bill.title }}</h3><p class="muted">订单号 {{ bill.orderNo }}</p></div>
          <div class="payment-amount"><strong>¥{{ (bill.amountCents / 100).toFixed(2) }}</strong><span>{{ bill.status === 'PAID' ? '已支付' : '待支付' }}</span></div>
          <button v-if="bill.status === 'UNPAID'" class="button primary compact" :disabled="busy" @click="pay(bill.id)">前往沙箱支付</button>
        </li>
      </ul>
    </section>
  </main>
</template>

<style scoped>
.payment-page { max-width: 1000px; margin: 0 auto; display: grid; gap: 20px; }
.payment-heading h1 { margin: 8px 0; }
.payment-note { border-left: 3px solid #b68b44; padding: 12px 16px; background: #fbf6ea; color: #6b542d; margin: 0; }
.payment-panel { padding: 26px; }
.payment-panel h2 { margin: 0 0 8px; }
.payment-form { display: grid; gap: 18px; max-width: 520px; margin-top: 24px; }
.payment-form label { display: grid; gap: 8px; font-weight: 600; }
.payment-form input { width: 100%; padding: 11px 12px; border: 1px solid #ccd5d0; border-radius: 8px; font: inherit; }
.payment-form button { justify-self: start; }
.payment-list-heading { display: flex; justify-content: space-between; align-items: start; gap: 16px; }
.payment-list { margin: 20px 0 0; padding: 0; list-style: none; }
.payment-list li { display: flex; align-items: center; gap: 24px; padding: 18px 0; border-top: 1px solid #e6ebe8; }
.payment-list li > div:first-child { flex: 1; min-width: 0; }
.payment-list h3 { margin: 0 0 6px; font-size: 1rem; }
.payment-list p { overflow-wrap: anywhere; margin: 0; font-size: .85rem; }
.payment-amount { display: grid; text-align: right; gap: 5px; white-space: nowrap; }
.payment-amount span { color: #587264; font-size: .85rem; }
.payment-error,.payment-success { padding: 12px 16px; margin: 0; border-radius: 8px; }
.payment-error { color: #9e3030; background: #fff1f0; }.payment-success { color: #246c49; background: #effaf3; }
@media(max-width: 640px) { .payment-panel { padding: 20px; }.payment-list li { flex-wrap: wrap; gap: 12px; }.payment-list li > div:first-child { flex-basis: 60%; }.payment-list li button { width: 100%; } }
</style>
