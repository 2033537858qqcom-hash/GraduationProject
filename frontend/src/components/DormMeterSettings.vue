<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { dormQuantity, dormUtilityName, type DormMeter, type DormMeterCatalog } from '../dormRecharge'
import type { FeeStandard } from '../finance'
import BaseSelect from './BaseSelect.vue'

const props = defineProps<{ standards: FeeStandard[]; canManage: boolean }>()
const catalog = ref<DormMeterCatalog>({ rooms: [], meters: [] }), busy = ref(false), loading = ref(true), error = ref(''), message = ref('')
const empty = () => ({ id: 0, roomId: '', standardId: '', meterCode: '', enabled: true, version: 0, category: '', reason: '' })
const form = ref(empty())
let baseline = JSON.stringify(form.value)
const dirty = computed(() => JSON.stringify(form.value) !== baseline)
const roomOptions = computed(() => catalog.value.rooms.map(item => ({ value: String(item.id), label: `${item.building} · ${item.name}` })))
const standardOptions = computed(() => props.standards.filter(item => ['WATER', 'ELECTRICITY'].includes(item.categoryCode) && item.majorId === null && (item.available || (!form.value.enabled && String(item.id) === form.value.standardId)) && (!form.value.id || item.categoryCode === form.value.category)).map(item => ({ value: String(item.id), label: `${item.name} · ¥${(item.unitPriceCents / 100).toFixed(2)}/${item.unit}` })))
async function load() {
  loading.value = true; error.value = ''
  try { catalog.value = await api<DormMeterCatalog>('/finance/payments/dorm-meters') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
function clear() { form.value = empty(); baseline = JSON.stringify(form.value) }
function edit(item: DormMeter) {
  if (dirty.value && !window.confirm('还有未保存的表计输入，确定切换？')) return
  form.value = { id: item.id, roomId: String(item.roomId), standardId: String(item.standardId), meterCode: item.meterCode, enabled: item.enabled, version: item.version, category: item.category, reason: '' }
  baseline = JSON.stringify(form.value)
}
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const path = '/finance/payments/dorm-meters' + (form.value.id ? '/' + form.value.id : '')
    const payload = form.value.id ? { standardId: Number(form.value.standardId), enabled: form.value.enabled, version: form.value.version, reason: form.value.reason } : { roomId: Number(form.value.roomId), standardId: Number(form.value.standardId), meterCode: form.value.meterCode.trim() }
    await api(path, { method: form.value.id ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    clear(); await load(); message.value = '宿舍表计配置已保存。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function leave() { return !busy.value && (!dirty.value || window.confirm('还有未保存的宿舍表计输入，确定离开？')) }
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(load)
</script>

<template>
  <section class="portal-panel finance-panel">
    <div class="finance-heading"><div><h2>宿舍水电表计</h2><p class="muted">将房间水表、电表绑定至已独立复核的收费标准。每个房间各保留一个水表和电表。</p></div><button class="button compact" :disabled="busy || loading" @click="load">刷新表计</button></div>
    <p class="finance-note">当前记录演示余额，尚未连接学校表计设备。单价从已生效收费标准读取；变更标准不修改历史充值订单。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <form v-if="canManage" class="finance-form" @submit.prevent="save"><h3>{{ form.id ? '调整表计' : '绑定宿舍表计' }}</h3><fieldset :disabled="busy || loading"><label>宿舍房间<BaseSelect v-model="form.roomId" :options="roomOptions" :disabled="!!form.id" aria-label="宿舍房间" /></label><label>表计编号<input v-model="form.meterCode" required maxlength="64" :disabled="!!form.id" placeholder="例如 D1-101-E" /></label><label>已审核收费标准<BaseSelect v-model="form.standardId" :options="standardOptions" aria-label="已审核收费标准" /></label><template v-if="form.id"><label class="meter-enabled"><input v-model="form.enabled" type="checkbox" />允许新充值</label><label class="finance-wide">调整原因<textarea v-model="form.reason" required minlength="5" maxlength="500" rows="2" /></label></template><div class="finance-actions finance-wide"><button class="button primary" :disabled="!form.roomId || !form.standardId">{{ busy ? '正在保存…' : form.id ? '保存调整' : '绑定表计' }}</button><button v-if="form.id" class="button" type="button" @click="clear">取消调整</button></div></fieldset><p v-if="!standardOptions.length" class="muted">请先在“收费标准”中建立水费或电费标准，并由另一位财务复核人员审核。</p></form>
    <p v-if="loading" role="status">正在读取宿舍表计…</p><p v-else-if="!catalog.meters.length && !error" class="empty-state">还没有绑定宿舍水电表计。</p>
    <article v-for="item in catalog.meters" :key="item.id" class="finance-record"><div><span class="finance-badge">{{ dormUtilityName(item.category) }} · {{ item.enabled && item.standardAvailable ? '可充值' : '暂停充值' }}</span><h3>{{ item.building }} · {{ item.room }}</h3><p>表计 {{ item.meterCode }} · {{ item.standardName }}</p><p>¥{{ (item.unitPriceCents / 100).toFixed(2) }}/{{ item.unit }} · 演示可用量 {{ dormQuantity(item.balance) }} {{ item.unit }}</p></div><button v-if="canManage" class="button compact" :disabled="busy" @click="edit(item)">调整表计</button></article>
  </section>
</template>

<style scoped>
.finance-note { margin-top: 16px; font-size: 12px; }
.meter-enabled { display: flex; align-items: center; gap: 8px; }
.meter-enabled input { width: 16px; height: 16px; padding: 0; appearance: auto; accent-color: #315244; }
</style>
