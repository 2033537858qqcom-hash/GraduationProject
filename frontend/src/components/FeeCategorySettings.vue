<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import type { PaymentCategory } from '../lib/payment'

defineProps<{ categories: PaymentCategory[]; canManage: boolean; loading: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const form = ref({ name: '', description: '', reason: '' }), requestKey = ref(crypto.randomUUID())
const target = ref<PaymentCategory | null>(null), stateReason = ref(''), busy = ref(false), error = ref(''), message = ref('')
const dirty = computed(() => Object.values(form.value).some(value => value.trim()) || Boolean(target.value && stateReason.value.trim()))
watch(form, () => { requestKey.value = crypto.randomUUID() }, { deep: true })
async function create() {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api('/finance/payments/categories', { method: 'POST', body: JSON.stringify({ ...form.value, requestKey: requestKey.value }) })
    form.value = { name: '', description: '', reason: '' }; message.value = '收费项目已建立，可在收费标准中核定价格。'; emit('changed')
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function choose(item: PaymentCategory) {
  if (target.value && stateReason.value.trim() && !window.confirm('还有未提交的调整原因，确定切换？')) return
  target.value = item; stateReason.value = ''; error.value = ''
}
async function update() {
  if (!target.value || busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    await api(`/finance/payments/categories/${encodeURIComponent(target.value.code)}`, { method: 'PUT', body: JSON.stringify({ enabled: target.value.enabled === false, version: target.value.version, reason: stateReason.value.trim() }) })
    message.value = target.value.enabled === false ? '收费项目已启用。' : '收费项目已停用；历史账单仍可查询。'
    target.value = null; stateReason.value = ''; emit('changed')
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function leave() { return !busy.value && (!dirty.value || window.confirm('还有未保存的收费项目输入，确定离开？')) }
function beforeUnload(event: BeforeUnloadEvent) { if (busy.value || dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <section class="portal-panel finance-panel" aria-labelledby="fee-categories-heading">
    <h2 id="fee-categories-heading">收费项目</h2><p class="muted">维护学费、校服费等项目；价格须在收费标准中核定并由另一位财务人员复核。</p>
    <p class="finance-note">项目名称用于历史账单，建立后保留原名称。停用前请先处理启用标准、未发布账单、待处理依据和宿舍表计。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <form v-if="canManage" class="finance-form finance-subsection" @submit.prevent="create"><h3>新增收费项目</h3><fieldset :disabled="busy || loading"><label>项目名称<input v-model="form.name" required maxlength="40" placeholder="例如校服费" /></label><label>项目说明<input v-model="form.description" required maxlength="160" placeholder="说明费用用途" /></label><label class="finance-wide">新增原因<textarea v-model="form.reason" required minlength="5" maxlength="500" rows="2" /></label><div class="finance-actions finance-wide"><button class="button primary">{{ busy ? '正在保存…' : '新增项目' }}</button></div></fieldset></form>
    <form v-if="target" class="finance-subsection finance-confirm" @submit.prevent="update"><h3>{{ target.enabled === false ? '启用' : '停用' }}“{{ target.name }}”</h3><label>调整原因<textarea v-model="stateReason" required minlength="5" maxlength="500" rows="2" :disabled="busy" /></label><div class="finance-actions"><button class="button primary" :disabled="busy">确认{{ target.enabled === false ? '启用' : '停用' }}</button><button type="button" class="button" :disabled="busy" @click="target = null; stateReason = ''">取消</button></div></form>
    <p v-if="loading" role="status">正在读取收费项目…</p><p v-else-if="!categories.length" class="empty-state">尚未建立收费项目。</p>
    <article v-for="item in categories" :key="item.code" class="finance-record"><div><span class="finance-badge">{{ item.enabled === false ? '已停用' : '启用中' }}</span><h3>{{ item.name }}</h3><p>{{ item.description }}</p></div><button v-if="canManage && item.code !== 'OTHER'" class="button compact" :disabled="busy || loading" @click="choose(item)">{{ item.enabled === false ? '启用' : '停用' }}</button></article>
  </section>
</template>

<style scoped>
.finance-note { margin: 16px 0; font-size: 12px; }
.finance-confirm { padding: 18px; border: 1px solid #dce4d0; border-radius: 6px; }
.finance-confirm label { margin-bottom: 14px; }
</style>
