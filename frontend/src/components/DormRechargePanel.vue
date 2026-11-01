<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { api, ApiError, errorMessage } from '../lib/api'
import type { PaymentBill } from '../lib/payment'
import { dormQuantity, dormUtilityName, type DormQuote, type DormRechargeOverview } from '../dormRecharge'
import BaseSelect from './BaseSelect.vue'

const emit = defineEmits<{ created: [bill: PaymentBill] }>()
const overview = ref<DormRechargeOverview | null>(null), loading = ref(true), busy = ref(false), error = ref(''), noDorm = ref(false)
const meterId = ref(''), mode = ref('AMOUNT'), value = ref(''), quote = ref<DormQuote | null>(null), requestKey = ref(crypto.randomUUID())
const meter = computed(() => overview.value?.meters.find(item => String(item.id) === meterId.value))
const choices = computed(() => overview.value?.meters.filter(item => item.enabled && item.standardAvailable).map(item => ({ value: String(item.id), label: `${dormUtilityName(item.category)} · ${item.building} ${item.room}` })) ?? [])
const money = (cents: number) => '¥' + (cents / 100).toFixed(2)
watch([meterId, mode, value, () => meter.value?.unitPriceCents, () => meter.value?.standardId], () => { quote.value = null; requestKey.value = crypto.randomUUID() })
async function refresh() {
  loading.value = true; error.value = ''; noDorm.value = false
  try {
    overview.value = await api<DormRechargeOverview>('/payments/mine/dorm-recharge')
    if (!choices.value.some(item => item.value === meterId.value)) meterId.value = choices.value[0]?.value ?? ''
  } catch (cause) {
    overview.value = null
    if (cause instanceof ApiError && cause.code === 'NO_CURRENT_DORM') noDorm.value = true
    else error.value = errorMessage(cause)
  } finally { loading.value = false }
}
function payload() { return { requestKey: requestKey.value, meterId: Number(meterId.value), mode: mode.value, value: value.value.trim(), expectedUnitPriceCents: meter.value?.unitPriceCents } }
async function preview() {
  if (!meter.value || busy.value) return
  busy.value = true; error.value = ''; quote.value = null
  try { quote.value = await api<DormQuote>('/payments/mine/dorm-recharge/quote', { method: 'POST', body: JSON.stringify(payload()) }) }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function create() {
  if (!quote.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const bill = await api<PaymentBill>('/payments/mine/dorm-recharge', { method: 'POST', body: JSON.stringify(payload()) })
    value.value = ''; quote.value = null; busy.value = false
    emit('created', bill)
    await refresh()
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function leave() { return !busy.value && (!value.value.trim() || window.confirm('还有未创建订单的宿舍充值输入，确定离开？')) }
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(refresh)
defineExpose({ refresh })
</script>

<template>
  <section class="portal-panel finance-panel dorm-recharge" aria-labelledby="dorm-recharge-heading">
    <div class="finance-heading"><div><h2 id="dorm-recharge-heading">宿舍水电预充值</h2><p class="muted">一位室友为宿舍充值，宿舍成员共享到账记录。</p></div><button class="button compact" :disabled="loading || busy" @click="refresh">刷新宿舍</button></div>
    <p class="finance-note">当前为演示充值，水电表设备未接入。这里的用量仅记录演示到账，尚未扣减实际使用；室友之间的分摊自行处理。</p>
    <p v-if="loading" role="status">正在核对当前宿舍…</p>
    <p v-else-if="noDorm" class="empty-state">尚无有效的宿舍入住记录，请先联系宿管办理入住。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <template v-if="overview && !loading">
      <p v-if="!overview.meters.length" class="empty-state">当前宿舍尚未绑定水电表，请联系宿管和财务配置。</p>
      <div v-else class="dorm-meter-grid"><article v-for="item in overview.meters" :key="item.id" class="dorm-meter-card"><span>{{ item.building }} · {{ item.room }} · {{ dormUtilityName(item.category) }}</span><strong>{{ dormQuantity(item.balance) }} <small>{{ item.unit }}</small></strong><small>演示可用量 · 表计 {{ item.meterCode }}</small><p>{{ money(item.unitPriceCents) }}/{{ item.unit }} · {{ item.standardName }}</p><span v-if="!item.enabled || !item.standardAvailable" class="finance-warning">当前暂停充值</span></article></div>
      <form v-if="choices.length" class="finance-form" @submit.prevent="preview"><fieldset :disabled="busy"><label>充值宿舍表计<BaseSelect v-model="meterId" :options="choices" aria-label="充值宿舍表计" /></label><label>充值方式<BaseSelect v-model="mode" :options="[{ value: 'AMOUNT', label: '按金额充值' }, { value: 'QUANTITY', label: '按用量充值' }]" aria-label="充值方式" /></label><label>{{ mode === 'AMOUNT' ? '充值金额（元）' : `充值用量（${meter?.unit ?? ''}）` }}<input v-model="value" required inputmode="decimal" maxlength="20" :placeholder="mode === 'AMOUNT' ? '例如 10.00' : '例如 20'" /></label><div class="finance-actions"><button class="button" :disabled="!meterId || !value.trim()">{{ busy ? '正在核对…' : '核对充值' }}</button></div></fieldset></form>
      <div v-if="quote && meter" class="finance-subsection" aria-live="polite"><p><strong>{{ meter.building }} {{ meter.room }} · {{ dormUtilityName(meter.category) }}</strong></p><p>应付 {{ money(quote.amountCents) }}，演示到账 {{ dormQuantity(quote.quantity) }} {{ meter.unit }}。创建后请在本人账单中完成沙箱支付。</p><button class="button primary" :disabled="busy" @click="create">创建充值订单</button></div>
      <section v-if="overview.history.length" class="finance-subsection"><h3>本宿舍最近充值记录</h3><article v-for="item in overview.history" :key="item.id" class="finance-record"><div><strong>{{ dormUtilityName(item.category) }} · {{ money(item.amountCents) }}</strong><p>{{ item.payer }} · {{ item.createdAt.slice(0, 16) }}</p><small>{{ item.reversedAt ? '已退款并冲正' : item.creditedAt ? '已到账（演示）' : item.status === 'CLOSED' ? '订单已关闭' : item.status === 'PAID' ? '到账待核对' : '尚未支付' }}</small></div></article></section>
    </template>
  </section>
</template>

<style scoped>
.dorm-recharge .finance-note { margin-top: 16px; font-size: 12px; }
.dorm-meter-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(220px,1fr)); gap: 14px; margin-top: 20px; }
.dorm-meter-card { display: grid; gap: 9px; padding: 18px; border: 1px solid #dce4d0; border-radius: 6px; background: #f7f9f2; color: #506b41; }
.dorm-meter-card strong { font-size: 23px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.dorm-meter-card small { font-size: 12px; font-weight: 400; }
.dorm-meter-card p { margin: 0; font-size: 12px; }
@media (max-width:600px) { .dorm-meter-grid { grid-template-columns: minmax(0,1fr); } }
</style>
