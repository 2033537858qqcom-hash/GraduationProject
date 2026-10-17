<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import HousingTransferMaterials from '../components/HousingTransferMaterials.vue'
import { api, errorMessage } from '../lib/api'
import { housingRequestKey, housingWhen, transferStatus, type HousingTransfer } from '../housing'
const route = useRoute()
const review = computed(() => route.path.startsWith('/student-affairs'))
const items = ref<HousingTransfer[]>([])
const canCreate = computed(() => !items.value.some(item => ['DRAFT', 'SUBMITTED', 'RETURNED', 'AWAITING_ASSIGNMENT', 'AWAITING_CHECKIN'].includes(item.status)))
const loading = ref(true)
const busy = ref(false)
const pendingMaterials = ref<Record<number, boolean>>({}), workingMaterials = ref<Record<number, boolean>>({})
const hasPendingMaterials = computed(() => Object.values(pendingMaterials.value).some(Boolean))
const materialBusy = computed(() => Object.values(workingMaterials.value).some(Boolean))
function canLeave() { return !busy.value && !materialBusy.value && (!hasPendingMaterials.value || window.confirm('所选材料尚未上传，离开后需要重新选择。确认离开？')) }
function beforeUnload(event: BeforeUnloadEvent) { if (hasPendingMaterials.value || materialBusy.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
const error = ref('')
const success = ref('')
const form = ref({ reason: '', preferredArea: '', materialNote: '' })
const comment = ref('')
const editing = ref<HousingTransfer | null>(null)
function editDraft(item: HousingTransfer) { editing.value = item; form.value = { reason: item.reason, preferredArea: item.preferredArea, materialNote: item.materialNote }; key = housingRequestKey(); void nextTick(() => document.getElementById('housing-form-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })) }
function cancelEdit() { editing.value = null; form.value = { reason: '', preferredArea: '', materialNote: '' }; key = housingRequestKey() }
let key = housingRequestKey()
let sequence = 0
async function load() {
  const stamp = ++sequence
  loading.value = true
  error.value = ''
  items.value = []
  try {
    const mode = review.value ? 'review' : 'mine'
    const result = await api<HousingTransfer[]>('/housing/transfers/' + mode + (review.value ? '?status=SUBMITTED' : ''))
    const id = route.query.id
    if (id !== undefined) {
      if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id) || route.query.tab !== 'details') throw new Error('申请链接无效，请从办事记录或消息重新打开。')
      const detail = await api<HousingTransfer>('/housing/transfers/' + mode + '/' + id)
      result.unshift(detail)
    }
    if (stamp !== sequence) return
    items.value = result.filter((item, index) => result.findIndex(row => row.id === item.id) === index)
    if (id) { await nextTick(); if (stamp === sequence) document.getElementById('transfer-' + id)?.scrollIntoView({ block: 'start' }) }
  }
  catch (cause) { if (stamp === sequence) error.value = errorMessage(cause) }
  finally { if (stamp === sequence) loading.value = false }
}
async function create() {
  if (materialBusy.value || hasPendingMaterials.value) { error.value = '请先上传或清除所选材料。'; return }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    if (editing.value) {
      await api('/housing/transfers/mine/' + editing.value.id, { method: 'PUT', body: JSON.stringify({ requestKey: key, version: editing.value.version, ...form.value }) })
      cancelEdit(); success.value = '资料修改已保存，请核对材料后再提交或补交。'; await load(); return
    }
    const created = await api<HousingTransfer>('/housing/transfers/mine', { method: 'POST', body: JSON.stringify({ requestKey: key, version: 0, ...form.value }) })
    key = housingRequestKey()
    form.value = { reason: '', preferredArea: '', materialNote: '' }
    success.value = '草稿已保存，可展开申请材料上传附件，核对后再提交。'
    await load()
    await nextTick(); document.getElementById('transfer-' + created.id)?.scrollIntoView({ block: 'start' })
  } catch (cause) { error.value = errorMessage(cause); key = housingRequestKey() }
  finally { busy.value = false }
}
async function act(item: HousingTransfer, action: string) {
  if (hasPendingMaterials.value || materialBusy.value || editing.value) { error.value = '请先保存资料修改，并上传或清除所选材料。'; return }
  if (!comment.value.trim() && !['submit', 'supplement'].includes(action)) { error.value = '请填写说明'; return }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const path = review.value ? '/housing/transfers/review/' + item.id + '/' + action : '/housing/transfers/mine/' + item.id + '/' + action
    const body = action === 'supplement' ? { requestKey: key, version: item.version, reason: item.reason, preferredArea: item.preferredArea, materialNote: item.materialNote } : { requestKey: key, version: item.version, comment: comment.value.trim() || '提交' }
    await api(path, { method: 'POST', body: JSON.stringify(body) })
    key = housingRequestKey()
    comment.value = ''
    success.value = '已记录本次处理。'
    await load()
    await nextTick(); document.getElementById('transfer-' + item.id)?.scrollIntoView({ block: 'start' })
  } catch (cause) { error.value = errorMessage(cause); key = housingRequestKey() }
  finally { busy.value = false }
}
onBeforeRouteLeave(canLeave); onBeforeRouteUpdate(canLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
watch(() => route.fullPath, () => { cancelEdit(); void load() })
onBeforeUnmount(() => { sequence++; window.removeEventListener('beforeunload', beforeUnload) })
</script>
<template>
  <div class="repair-settings housing-page">
    <div class="repair-page-heading"><div><span class="portal-context">{{ review ? '学生事务处理' : '学生服务中心' }}</span><h1>{{ review ? '学院调宿初审' : '调宿申请' }}</h1><p>{{ review ? '核对本学院待初审申请。通过后由目标楼栋宿管安排床位。' : '在现有床位上申请调整。提交后原床位保持入住，不能自行选择具体床位。' }}</p></div><button class="button compact" :disabled="loading || busy || materialBusy || hasPendingMaterials" @click="load"><RefreshCw :size="15" />刷新申请</button></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <form v-if="!review && (editing || canCreate)" class="portal-panel repair-editor" @submit.prevent="create"><h2 id="housing-form-heading">{{ editing ? (editing.status === 'RETURNED' ? '编辑补交资料 #' : '修改调宿草稿 #') + editing.id : '新建调宿草稿' }}</h2><div class="repair-editor-form"><label class="repair-form-wide"><div class="label-heading-row"><span>调宿原因</span><small class="muted">必填 · 最多 500 字</small></div><textarea v-model="form.reason" required maxlength="500" rows="3" :disabled="busy || materialBusy" placeholder="请详细说明调宿的具体原因（例如：作息时间差异、生活习惯调整、身体健康需要低楼层等）" /></label><label><div class="label-heading-row"><span>意向区域</span><small class="muted">必填</small></div><input v-model="form.preferredArea" required maxlength="200" :disabled="busy || materialBusy" placeholder="例如：东校区低楼层、静音宿舍区" /></label><label><div class="label-heading-row"><span>材料说明</span><small class="muted">必填</small></div><input v-model="form.materialNote" required maxlength="500" :disabled="busy || materialBusy" placeholder="说明辅导员与宿管核验材料用途（证明文件保存草稿后在下方添加）" /></label><div class="form-actions repair-form-wide"><button class="button primary compact" :disabled="busy || materialBusy">{{ busy ? '正在保存…' : editing ? '保存资料修改' : '保存调宿草稿' }}</button><button v-if="editing" type="button" class="button compact" :disabled="busy || materialBusy" @click="cancelEdit">取消修改</button></div></div></form>
    <p v-if="loading" role="status">正在读取调宿申请…</p>
    <p v-else-if="!items.length" class="empty-state">{{ review ? '当前没有待初审的调宿申请。' : '还没有调宿申请。' }}</p>
    <div v-else class="repair-card-grid housing-transfer-list" :class="{ single: items.length === 1 }"><article v-for="item in items" :id="'transfer-' + item.id" :key="item.id" class="portal-panel repair-config-card" :aria-label="'调宿申请 #' + item.id"><p v-if="String(item.id) === route.query.id" class="data-note">当前选择的调宿申请 #{{ item.id }} · 请核对下方原始资料</p><div class="section-heading"><h2>{{ review ? item.studentName + ' · ' + item.studentUsername : '申请 #' + item.id }}</h2><span class="repair-status">{{ transferStatus[item.status] || item.status }}</span></div><p>{{ item.sourcePlace }}</p><p class="muted">意向 {{ item.preferredArea }}</p><p>{{ item.reason }}</p><p v-if="item.materialNote" class="muted">材料：{{ item.materialNote }}</p><p v-if="item.reviewComment">初审意见：{{ item.reviewComment }}</p><p v-if="item.targetPlace">{{ item.status === 'COMPLETED' ? '新住宿' : item.status === 'AWAITING_CHECKIN' ? '预留床位' : '原安排床位' }} {{ item.targetPlace }}<template v-if="item.reserveUntil && item.status === 'AWAITING_CHECKIN'">，请于 {{ housingWhen(item.reserveUntil) }} 前入住</template></p><p v-if="item.cancelRequested && item.status === 'AWAITING_CHECKIN'">已请求取消，等待宿管确认。</p><div class="repair-card-actions"><button v-if="!review && (item.allowedActions.includes('edit') || item.allowedActions.includes('supplement'))" class="text-button" type="button" :disabled="busy || materialBusy" @click="editDraft(item)">{{ item.status === 'RETURNED' ? '编辑补交资料' : '修改草稿' }}</button><button v-if="!review && item.allowedActions.includes('submit')" class="text-button" type="button" :disabled="busy || materialBusy || hasPendingMaterials || !!editing" @click="act(item, 'submit')">核对后提交</button><button v-if="item.allowedActions.includes('supplement')" class="text-button" type="button" :disabled="busy || materialBusy || hasPendingMaterials || !!editing" @click="act(item, 'supplement')">核对后补交</button><button v-if="item.allowedActions.includes('withdraw')" class="text-button" type="button" :disabled="busy || materialBusy" @click="act(item, 'withdraw')">撤回</button><button v-if="item.allowedActions.includes('cancel-request')" class="text-button" type="button" :disabled="busy || materialBusy" @click="act(item, 'cancel-request')">请求取消</button><button v-if="item.allowedActions.includes('approve')" class="text-button" type="button" :disabled="busy || materialBusy" @click="act(item, 'approve')">通过</button><button v-if="item.allowedActions.includes('return')" class="text-button" type="button" :disabled="busy || materialBusy" @click="act(item, 'return')">退回补交</button><button v-if="item.allowedActions.includes('reject')" class="text-button" type="button" :disabled="busy || materialBusy" @click="act(item, 'reject')">拒绝</button></div><HousingTransferMaterials :request="item" :view="review ? 'review' : 'mine'" :busy="busy || materialBusy" :initial-open="String(item.id) === route.query.id" @changed="version => item.version = version" @pending="value => pendingMaterials[item.id] = value" @working="value => workingMaterials[item.id] = value" /></article></div>
    <label v-if="items.some(item => item.allowedActions.length)" class="repair-editor"><div class="label-heading-row"><span>处理说明</span><small class="muted">最多 200 字</small></div><textarea v-model="comment" maxlength="200" rows="3" :disabled="busy || materialBusy" placeholder="撤回、取消或审核时填写处理意见或补充说明" /></label>
  </div>
</template>

<style scoped>
.housing-transfer-list.single { grid-template-columns: minmax(0, 1fr); }
.housing-transfer-list { margin-top: 18px; }
</style>
