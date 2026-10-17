<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { AlertCircle, ClipboardCheck, History, MapPin, Plus, RefreshCw, Wrench } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { repairLocationQuery, type RepairPublicLocation } from '../repairSettings'
import { currentUser } from '../stores/auth'
import type { Paged } from '../foundation'
import { cancellationStatuses, repairActions, repairCancellationMutation, repairCategories, repairDraft, repairForm, repairOperation, repairSnapshot, repairStatuses } from '../repairs'
import type { RepairDetail, RepairHistory, RepairImage, RepairItem, RepairSummary } from '../repairs'
import BaseSelect from '../components/BaseSelect.vue'
import ContactTimeSelect from '../components/ContactTimeSelect.vue'

const route = useRoute(), router = useRouter()
const mode = computed(() => route.path.endsWith('/dispatch') ? 'dispatch' : route.path.endsWith('/tasks') ? 'work' : 'mine')
const root = computed(() => '/repairs/' + mode.value)
const title = computed(() => mode.value === 'mine' ? '我的校园报修' : mode.value === 'dispatch' ? '后勤分诊与派单' : '我的维修任务')
const intro = computed(() => mode.value === 'mine' ? '记录校园设施故障，跟进实际处理进度，维修后由你验收。' : mode.value === 'dispatch' ? '核对负责范围内的故障，补充资料、分配人员并跟进处理。' : '查看当前分配给你的任务，记录进展并提交维修结果。')
const list = ref<Paged<RepairItem>>({ items: [], total: 0, page: 1, pageSize: 20 })
const records = ref<Paged<RepairHistory>>({ items: [], total: 0, page: 1, pageSize: 10 })
const selected = ref<RepairDetail | null>(null), summary = ref<RepairSummary | null>(null)
const loading = ref(true), busy = ref(false), editing = ref(false), error = ref(''), success = ref(''), status = ref('')
const cancellationFilter = ref('')
const campuses = ref<{ id: number; name: string }[]>([]), campusId = ref(''), locations = ref<Paged<{ id: number; name: string; campusName: string; detailHint: string; provenance: string }>>({ items: [], total: 0, page: 1, pageSize: 100 })
const technicians = ref<{ id: number; name: string; departmentName: string | null }[]>([]), technicianId = ref(''), comment = ref(''), stars = ref('5'), priority = ref('NORMAL')
const prefilledLocation = ref<RepairPublicLocation | null>(null)
const form = reactive(repairForm())
const cancellationOptions = [
  { value: '', label: '全部工单' },
  { value: 'true', label: '取消待处理' },
  { value: 'false', label: '无待处理取消' },
]
const statusOptions = computed(() => [
  { value: '', label: '全部状态' },
  ...Object.entries(repairStatuses).map(([code, label]) => ({ value: code, label })),
])
const campusOptions = computed(() => campuses.value.map(c => ({ value: String(c.id), label: c.name })))
const locationOptions = computed(() => [
  { value: '', label: locations.value.items.length ? '请选择开放地点' : (loading.value ? '正在读取地点…' : '当前校区暂无可选地点') },
  ...(form.locationId && !locations.value.items.some(l => String(l.id) === form.locationId)
    ? [{ value: form.locationId, label: `${selected.value?.locationName || prefilledLocation.value?.name || '当前选中地点'}（当前选择）` }]
    : []),
  ...locations.value.items.map(l => ({
    value: String(l.id),
    label: `${l.name}${l.provenance === 'DEMO' ? ' · 演示' : ''}`,
  })),
])
const categoryOptions = computed(() => [
  { value: '', label: '请选择类别' },
  ...Object.entries(repairCategories).map(([code, label]) => ({ value: code, label })),
])
const purposeOptions = [
  { value: 'RESULT', label: '本次维修结果' },
  { value: 'PROGRESS', label: '维修进展' },
]
const technicianOptions = computed(() => [
  { value: '', label: '请选择有效维修人员' },
  ...technicians.value.map(person => ({
    value: String(person.id),
    label: `${person.name}${person.departmentName ? ' · ' + person.departmentName : ''}`,
  })),
])
const priorityOptions = [
  { value: 'LOW', label: '低' },
  { value: 'NORMAL', label: '普通' },
  { value: 'HIGH', label: '高' },
]
const starOptions = Array.from({ length: 5 }, (_, i) => ({ value: String(i + 1), label: `${i + 1} 星` }))
const file = ref<File | null>(null), fileInput = ref<HTMLInputElement | null>(null), purpose = ref('FAULT')
const operationKey = repairOperation()
let baseline = '', sequence = 0, locationSequence = 0
const dirty = computed(() => editing.value && JSON.stringify(form) !== baseline || !!comment.value.trim() || !!file.value || !!technicianId.value || !!selected.value && priority.value !== selected.value.priority)
const historyView = computed(() => route.query.tab === 'history')
const dialog = ref<HTMLDialogElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null), confirmation = ref('')
let resolveConfirmation: ((accept: boolean) => void) | null = null
function can(action: string) { return !!selected.value?.allowedActions.includes(action) }
function time(value: string | null) { return value ? value.slice(0, 16).replace('T', ' ') : '尚未发生' }
function imageUrl(image: RepairImage) { return `/api${root.value}/${selected.value?.id}/attachments/${image.id}` }
async function ask(message: string) {
  confirmation.value = message
  return new Promise<boolean>(resolve => { resolveConfirmation = resolve; void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() }) })
}
function finish(accept: boolean) { dialog.value?.close(); const resolve = resolveConfirmation; resolveConfirmation = null; resolve?.(accept) }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在保存，请等待完成后再离开。'; return false }
  return !dirty.value || await ask('还有未保存的资料、处理说明或待上传图片。离开将放弃这些输入，是否继续？')
}
function clearInputs() { editing.value = false; comment.value = ''; technicianId.value = ''; file.value = null; if (fileInput.value) fileInput.value.value = '' }
async function readLocations(page = 1) {
  const request = ++locationSequence
  const params = new URLSearchParams({ page: String(page), pageSize: '100' }); if (campusId.value) params.set('campusId', campusId.value)
  const result = await api<typeof locations.value>('/repairs/locations?' + params)
  if (request === locationSequence) locations.value = result
}
async function locationPage(page: number) { try { await readLocations(page) } catch (cause) { error.value = errorMessage(cause) } }
async function changeCampus() { form.locationId = ''; await locationPage(1) }
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; clearInputs(); selected.value = null; prefilledLocation.value = null
  try {
    cancellationFilter.value = String(route.query.cancellationPending ?? '')
    status.value = String(route.query.status ?? (mode.value === 'dispatch' && cancellationFilter.value !== 'true' ? 'SUBMITTED' : ''))
    const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' }); if (status.value) params.set('status', status.value)
    if (cancellationFilter.value) params.set('cancellationPending', cancellationFilter.value)
    const [items, counts] = await Promise.all([api<typeof list.value>(root.value + '?' + params), api<RepairSummary>('/repairs/summary')])
    if (request !== sequence) return
    list.value = items; summary.value = counts
    if (mode.value === 'mine' && !campuses.value.length) {
      const catalog = await api<{ campuses: typeof campuses.value }>('/repairs/catalog')
      if (request !== sequence) return
      campuses.value = catalog.campuses; campusId.value = String(campuses.value.find(c => c.name === currentUser.value?.campusName)?.id ?? campuses.value[0]?.id ?? '')
    }
    if (route.query.id) {
      const detail = await api<RepairDetail>(root.value + '/' + String(route.query.id))
      if (request !== sequence) return
      selected.value = detail; priority.value = detail.priority; purpose.value = mode.value === 'mine' ? 'FAULT' : 'RESULT'
      if (historyView.value) {
        const history = await api<typeof records.value>(`${root.value}/${detail.id}/history?page=${Math.max(1, Number(route.query.historyPage) || 1)}&pageSize=10`)
        if (request !== sequence) return
        records.value = history
      }
      if (can('assign') || can('reassign')) {
        const people = await api<typeof technicians.value>(`/repairs/dispatch/${detail.id}/technicians`)
        if (request !== sequence) return
        technicians.value = people
      }
    } else if (route.query.new === '1' && mode.value === 'mine') {
      Object.assign(form, repairForm())
      const locationId = repairLocationQuery(route.query.location)
      if (route.query.location !== undefined && locationId === null) throw new Error('地点链接无效，请从当前住宿重新进入。')
      if (locationId !== null) {
        const place = await api<RepairPublicLocation>('/repairs/locations/' + locationId)
        if (request !== sequence) return
        prefilledLocation.value = place; campusId.value = String(place.campusId); form.locationId = String(place.id)
      }
      baseline = JSON.stringify(form); editing.value = true; await readLocations()
    }
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function refresh() { if (await allowLeave()) { success.value = ''; await load() } }
async function navigate(query: Record<string, string | undefined>) { await router.replace({ query }) }
async function choose(item: RepairItem) { await navigate({ ...route.query as Record<string, string>, id: String(item.id), tab: 'details', new: undefined }) }
async function newDraft() { await navigate({ new: '1' }) }
async function search() { await navigate({ status: status.value, cancellationPending: cancellationFilter.value || undefined, page: '1' }) }
async function changeCancellationFilter() { if (cancellationFilter.value === 'true') status.value = ''; await search() }
async function page(number: number, history = false) { await navigate({ ...route.query as Record<string, string>, [history ? 'historyPage' : 'page']: String(number) }) }
async function edit() {
  if (!await allowLeave()) return
  Object.assign(form, repairForm(selected.value)); baseline = JSON.stringify(form); editing.value = true; campusId.value = String(selected.value?.campusId ?? campusId.value)
  try { await readLocations() } catch (cause) { error.value = errorMessage(cause) }
}
async function saved(result: RepairDetail, message: string) {
  clearInputs(); baseline = JSON.stringify(form); busy.value = false
  if (mode.value === 'work' && !result.technicianId) await navigate({ status: 'ASSIGNED' })
  else if (route.query.id === String(result.id) && !route.query.new) await load()
  else await navigate({ id: String(result.id), tab: 'details', status: status.value })
  success.value = message
}
async function save() {
  busy.value = true; error.value = ''; success.value = ''
  const endpoint = selected.value ? root.value + '/' + selected.value.id : root.value
  const fields = { ...repairDraft(form), version: selected.value?.version ?? 0 }
  try { const result = await api<RepairDetail>(endpoint, { method: selected.value ? 'PUT' : 'POST', body: JSON.stringify({ ...fields, requestKey: operationKey(endpoint, fields) }) }); await saved(result, '资料已保存。核对地点、说明及图片后再提交报修。') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function transition(action: string) {
  if (!selected.value || busy.value) return
  if (file.value) { error.value = '还有待上传图片，请先上传或清除选择。'; return }
  if (['assign', 'reassign'].includes(action) && !technicianId.value) { error.value = '请选择当前负责范围匹配的维修人员。'; return }
  const fallback: Record<string, string> = { submit: '本人核对资料后提交', accept: '维修人员确认接单', confirm: '本人确认问题已解决' }
  const note = comment.value.trim() || fallback[action] || ''
  if (action !== 'evaluation' && !note) { error.value = '请填写处理说明后再操作。'; return }
  let cancelMutation: ReturnType<typeof repairCancellationMutation>
  try { cancelMutation = repairCancellationMutation(selected.value.id, action, selected.value.cancellation?.id ?? null, selected.value.version, note) }
  catch (cause) { error.value = errorMessage(cause); return }
  const selectedTechnician = technicians.value.find(t => t.id === Number(technicianId.value))
  const cancelPrompts: Record<string, string> = { 'request-cancellation': '申请取消后，维修和验收将暂停，等待后勤决定。确认申请？', 'withdraw-cancellation': '撤销取消申请后，继续当前维修或验收流程。确认撤销？', 'approve-cancellation': '同意后关闭工单，结束当前维修任务并保留历史。确认同意取消？', 'reject-cancellation': '拒绝后继续原维修或验收流程，请核对处理说明。确认拒绝取消？' }
  const prompt = cancelPrompts[action] ?? (action === 'submit' ? '提交后由后勤分诊，地点和类别固定。确认提交这份报修吗？' : action === 'confirm' ? '确认这次维修已经解决问题吗？确认后工单完成。' : `${repairActions[action]}${selectedTechnician && ['assign', 'reassign'].includes(action) ? '：' + selectedTechnician.name : ''}，确认继续吗？`)
  if (!await ask(prompt)) return
  busy.value = true; error.value = ''; success.value = ''
  const endpoint = cancelMutation?.endpoint ?? `${root.value}/${selected.value.id}/${action}`
  const body = cancelMutation?.body ?? { version: selected.value.version, comment: note, technicianId: ['assign', 'reassign'].includes(action) ? Number(technicianId.value) : null, resultRound: ['confirm', 'reopen', 'evaluation'].includes(action) ? selected.value.completionRound : null, stars: action === 'evaluation' ? Number(stars.value) : null, priority: action === 'priority' ? priority.value : null }
  try { const result = await api<RepairDetail>(endpoint, { method: 'POST', body: JSON.stringify({ ...body, requestKey: operationKey(endpoint, body) }) }); await saved(result, repairActions[action] + '已记录。') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file.value && (!['image/jpeg', 'image/png'].includes(file.value.type) || file.value.size < 1 || file.value.size > 2 * 1024 * 1024)) { error.value = '请选择 2 MB 以内的 JPEG 或 PNG 图片。'; clearFile() }
}
function clearFile() { file.value = null; if (fileInput.value) fileInput.value.value = '' }
async function upload() {
  if (!selected.value || !file.value || busy.value) return
  busy.value = true; error.value = ''
  const endpoint = `${root.value}/${selected.value.id}/attachments`, fingerprint = { version: selected.value.version, purpose: purpose.value, name: file.value.name, size: file.value.size, modified: file.value.lastModified }
  const data = new FormData(); data.set('file', file.value); data.set('version', String(selected.value.version)); data.set('purpose', purpose.value); data.set('requestKey', operationKey(endpoint, fingerprint))
  try { await saved(await api<RepairDetail>(endpoint, { method: 'POST', body: data }), '图片已保存，历史版本保留。') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function removeImage(image: RepairImage) {
  if (!selected.value || !await ask('从当前资料移除这张图片？已保存的历史记录仍保留。')) return
  busy.value = true; error.value = ''
  const endpoint = `${root.value}/${selected.value.id}/attachments/${image.id}/remove`, body = { version: selected.value.version, comment: '移除当前图片' }
  try { await saved(await api<RepairDetail>(endpoint, { method: 'POST', body: JSON.stringify({ ...body, requestKey: operationKey(endpoint, body) }) }), '图片已从当前资料移除。') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function removable(image: RepairImage) { return image.purpose === 'FAULT' && can('remove-fault') || image.purpose === 'RESULT' && image.resultRound === (selected.value?.completionRound ?? 0) + 1 && can('remove-result') }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
watch(() => route.fullPath, () => { void load() })
onBeforeUnmount(() => { sequence++; locationSequence++; finish(false); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="repair-workflow">
    <header class="repair-page-heading"><div><span class="eyebrow">校园设施服务</span><h1>{{ title }}</h1><p>{{ intro }}</p></div><div class="repair-inline-actions"><RouterLink v-if="mode === 'dispatch'" class="button compact" to="/logistics/repairs/statistics">查看报修统计</RouterLink><button class="button compact" :disabled="busy || loading" @click="refresh"><RefreshCw :size="15" />刷新记录</button><button v-if="mode === 'mine'" class="button primary compact" :disabled="busy || editing" @click="newDraft"><Plus :size="15" />新建报修</button></div></header>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success" role="status">{{ success }}</p>
    <div v-if="summary" class="repair-queue-note"><ClipboardCheck :size="18" /><span v-if="mode === 'mine'">待你补充 {{ summary.mineReturned }} 条 · 待你验收 {{ summary.mineAwaitingConfirmation }} 条 · 取消待处理 {{ summary.mineCancellationPending }} 条</span><span v-else-if="mode === 'dispatch'">当前范围待分诊 {{ summary.dispatchPending }} 条 · 取消待处理 {{ summary.dispatchCancellationPending }} 条</span><span v-else>待接单 {{ summary.workAssigned }} 条 · 处理中 {{ summary.workInProgress }} 条</span></div>
    <div class="repair-workflow-grid">
      <section class="portal-panel repair-request-list" aria-label="报修记录列表"><div class="repair-list-filter"><label>取消申请<BaseSelect v-model="cancellationFilter" :options="cancellationOptions" :disabled="busy || loading" compact @change="changeCancellationFilter" /></label><label>工单状态<BaseSelect v-model="status" :options="statusOptions" :disabled="busy || loading" compact @change="search" /></label><span>共 {{ list.total }} 条</span></div><p v-if="loading" role="status" class="loading-state">正在读取报修记录…</p><p v-else-if="!list.items.length" class="empty-state">当前筛选没有记录。{{ mode === 'mine' ? '可新建报修并先保存草稿。' : '只显示你当前有权访问的工单。' }}</p><button v-for="item in list.items" :key="item.id" class="repair-request-card" :class="{ selected: selected?.id === item.id }" :disabled="busy" @click="choose(item)"><span class="repair-card-top"><span>报修 #{{ item.id }}</span><span class="status-label">{{ repairStatuses[item.status] }}</span></span><strong>{{ item.title || '待补充故障标题' }}</strong><span v-if="item.cancellationPending" class="repair-card-attention">取消待处理 · 办理已暂停</span><span v-else-if="item.confirmationOverdue" class="repair-card-attention">待跟进验收</span><span><MapPin :size="13" />{{ item.campusName }} {{ item.locationName || '待选择地点' }}</span><small>{{ repairCategories[item.category] || '类别待填写' }} · {{ time(item.updatedAt) }}</small></button><div class="pagination"><span>第 {{ list.page }} 页</span><button class="button compact" :disabled="busy || loading || list.page <= 1" @click="page(list.page - 1)">上一页</button><button class="button compact" :disabled="busy || loading || list.page * list.pageSize >= list.total" @click="page(list.page + 1)">下一页</button></div></section>
      <section class="portal-panel repair-request-detail" aria-label="报修资料与处理">
        <form v-if="editing" class="repair-workflow-form" @submit.prevent="save"><div class="section-heading"><h2>{{ selected ? '修改报修资料' : '填写报修资料' }}</h2><button type="button" class="text-button" :disabled="busy" @click="refresh">取消编辑</button></div><p class="muted">可以先保存不完整的草稿。地点、类别在提交后固定，联系方式只对当前相关人员可见。</p><fieldset :disabled="busy"><div class="form-grid"><label>所在校区<BaseSelect v-model="campusId" :options="campusOptions" :disabled="!!selected?.submittedAt" @change="changeCampus" /></label><label>报修地点<BaseSelect v-model="form.locationId" :options="locationOptions" :disabled="!!selected?.submittedAt" /></label></div><p v-if="!loading && !locations.total" class="data-note">当前校区还没有开放地点，可先保存故障描述，联系管理员配置后再提交。</p><div class="form-grid"><label>故障类别<BaseSelect v-model="form.category" :options="categoryOptions" :disabled="!!selected?.submittedAt" /></label><label>故障标题<input v-model="form.title" maxlength="100" placeholder="例如：教室右侧照明灯不亮" /></label></div><label>具体位置补充<input v-model="form.locationDetail" maxlength="300" placeholder="例如：进门右侧第二盏灯" /></label><label><div class="label-heading-row"><span>故障现象与影响</span><small class="muted">最多 2000 字</small></div><textarea v-model="form.description" maxlength="2000" rows="5" placeholder="请详细描述故障现象、初次出现时间和具体影响（图片可在保存草稿后上传）" /></label><div class="form-grid"><label>联系手机或邮箱<input v-model="form.contact" maxlength="80" autocomplete="off" /></label><label>方便联系的时间<ContactTimeSelect v-model="form.contactWindow" :disabled="busy" /></label></div></fieldset><button class="button primary compact" :disabled="busy" type="submit">{{ busy ? '正在保存…' : '保存报修资料' }}</button></form>
        <template v-else-if="selected">
          <p v-if="!selected.locationEligible" class="data-note" role="status">{{ selected.locationEligibilityMessage }}</p>
          <div class="section-heading"><div><span class="eyebrow">报修 #{{ selected.id }}</span><h2>{{ selected.title || '待填写故障标题' }}</h2></div><span class="status-label">{{ repairStatuses[selected.status] }}</span></div>
          <nav class="repair-detail-tabs" aria-label="报修详情栏目"><button class="text-button" :class="{ selected: !historyView }" :disabled="busy" @click="navigate({ ...route.query as Record<string, string>, tab: 'details' })"><Wrench :size="15" />资料与处理</button><button class="text-button" :class="{ selected: historyView }" :disabled="busy" @click="navigate({ ...route.query as Record<string, string>, tab: 'history', historyPage: '1' })"><History :size="15" />完整办理记录</button></nav>
          <template v-if="historyView"><article v-for="record in records.items" :key="record.id" class="repair-history-card"><strong>{{ repairActions[record.action] || record.action }} · {{ repairStatuses[record.status] }}</strong><small>{{ record.actorName }} · {{ time(record.createdAt) }}</small><p>{{ record.comment }}</p><details v-if="repairSnapshot(record.snapshot)"><summary>查看当时保存的资料与图片</summary><p>{{ repairSnapshot(record.snapshot)?.title }}</p><p class="preserve-lines">{{ repairSnapshot(record.snapshot)?.description }}</p><p>具体位置：{{ repairSnapshot(record.snapshot)?.locationDetail || '未填写' }}</p><p>联系方式：{{ repairSnapshot(record.snapshot)?.contact || '未填写' }}</p><a v-for="image in repairSnapshot(record.snapshot)?.images" :key="image.id" class="text-button" :href="imageUrl(image)" target="_blank" rel="noopener">下载当时图片：{{ image.name }}</a></details></article><div class="pagination"><span>共 {{ records.total }} 条记录</span><button class="button compact" :disabled="records.page <= 1 || busy" @click="page(records.page - 1, true)">上一页记录</button><button class="button compact" :disabled="records.page * records.pageSize >= records.total || busy" @click="page(records.page + 1, true)">下一页记录</button></div></template>
          <template v-else>
            <section v-if="selected.cancellation" class="repair-cancellation" :class="{ pending: selected.cancellation.status === 'PENDING' }"><h3>{{ cancellationStatuses[selected.cancellation.status] }}</h3><small>{{ selected.cancellation.requesterName }} · {{ time(selected.cancellation.createdAt) }}</small><p>申请原因：{{ selected.cancellation.reason }}</p><p v-if="selected.cancellation.status === 'PENDING'">取消等待后勤处理，维修、资料更新和本人验收已暂停。报修人可撤销此申请。</p><template v-else><p>{{ selected.cancellation.decisionActorName }} · {{ time(selected.cancellation.resolvedAt) }}</p><p>处理说明：{{ selected.cancellation.decisionComment }}</p></template></section>
            <p v-if="selected.confirmationOverdue" class="repair-overdue-note">本次维修结果已等待验收超过演示提醒时间，请核对结果后确认或反馈问题。提醒不会自动完成工单。</p>
            <dl class="repair-facts"><div><dt>报修地点</dt><dd>{{ selected.campusName }} {{ selected.locationName || '待填写' }}</dd></div><div><dt>故障类别</dt><dd>{{ repairCategories[selected.category] || '待填写' }}</dd></div><div><dt>报修人</dt><dd>{{ selected.reporterName }}</dd></div><div><dt>当前维修人员</dt><dd>{{ selected.technicianName || '尚未派单' }}</dd></div><div><dt>联系信息</dt><dd>{{ selected.contact || '待填写' }}<small>{{ selected.contactWindow }}</small></dd></div><div><dt>处理优先级</dt><dd>{{ { LOW: '低', NORMAL: '普通', HIGH: '高' }[selected.priority] }}</dd></div></dl><p v-if="selected.locationDetail" class="data-note">具体位置：{{ selected.locationDetail }}</p><div class="repair-description"><h3>故障说明</h3><p class="preserve-lines">{{ selected.description || '尚未补充' }}</p></div><p v-if="selected.processingComment" class="repair-processing-note">最近处理说明：{{ selected.processingComment }}</p>
            <div v-if="selected.images.length" class="repair-image-list"><h3>已保存图片</h3><article v-for="image in selected.images" :key="image.id"><div><span>{{ image.purpose === 'FAULT' ? '故障图片' : image.purpose === 'PROGRESS' ? '维修进展' : '结果第 ' + image.resultRound + ' 轮' }}</span><a :href="imageUrl(image)" target="_blank" rel="noopener">{{ image.name }} · 下载查看</a></div><button v-if="removable(image)" class="text-button" :disabled="busy" @click="removeImage(image)">移除当前图片</button></article></div>
            <section v-if="selected.assignments?.length" class="repair-assignments-section">
              <div class="section-subheading">
                <h3>派工与改派轨迹 ({{ selected.assignments.length }})</h3>
                <span class="muted-note">记录责任人员分配与改派流转</span>
              </div>
              <div class="repair-assignment-list">
                <article v-for="(asn, idx) in selected.assignments" :key="asn.id" class="repair-assignment-card" :class="{ current: !asn.endedAt }">
                  <div class="assignment-header">
                    <span class="assignment-index">第 {{ idx + 1 }} 次派工</span>
                    <span class="assignment-badge" :class="asn.endedAt ? 'ended' : 'active'">
                      {{ asn.endedAt ? '已改派 / 历史' : '当前指派' }}
                    </span>
                  </div>
                  <div class="assignment-body">
                    <div class="assignment-row">
                      <span><strong>维修人员：</strong>{{ asn.technicianName }}</span>
                      <span style="margin-left: 12px"><strong>调度人员：</strong>{{ asn.actorName }}</span>
                    </div>
                    <div class="assignment-row">
                      <span><strong>派单时间：</strong><small>{{ time(asn.assignedAt) }}</small></span>
                      <span v-if="asn.endedAt" style="margin-left: 12px"><strong>改派时间：</strong><small>{{ time(asn.endedAt) }}</small></span>
                    </div>
                    <p class="assignment-comment" v-if="asn.comment">
                      <strong>派工／改派说明：</strong>{{ asn.comment }}
                    </p>
                  </div>
                </article>
              </div>
            </section>
            <section v-if="can('upload-fault') || can('upload-result')" class="repair-upload"><h3>补充图片</h3><label v-if="mode === 'work'">图片用途<BaseSelect v-model="purpose" :options="purposeOptions" :disabled="busy" /></label><label>选择 JPEG 或 PNG 图片<input ref="fileInput" type="file" accept="image/jpeg,image/png" :disabled="busy" @change="selectFile" /></label><small>单张最多 2 MB；故障最多 6 张，进展与本轮结果各最多 3 张。刷新后待上传图片须重新选择。</small><div class="repair-inline-actions"><button class="button compact" :disabled="busy || !file" @click="upload">上传并保存图片</button><button v-if="file" class="text-button" :disabled="busy" @click="clearFile">清除选择</button></div></section>
            <section v-for="completion in selected.completions" :key="completion.resultRound" class="repair-completion"><h3>第 {{ completion.resultRound }} 次维修结果</h3><small>{{ completion.technicianName }} · {{ time(completion.createdAt) }}</small><p class="preserve-lines">{{ completion.comment }}</p><a v-for="image in completion.images" :key="image.id" class="text-button" :href="imageUrl(image)" target="_blank" rel="noopener">{{ image.name }} · 下载结果图片</a></section>
            <div v-if="selected.evaluation" class="repair-completion"><h3>你的服务评价 · {{ selected.evaluation.stars }} / 5 星</h3><p>{{ selected.evaluation.comment || '未填写文字评价' }}</p></div>
            <div class="repair-timestamps"><span>首次提交：{{ time(selected.submittedAt) }}</span><span>本人确认完成：{{ time(selected.confirmedAt) }}</span></div>
            <section v-if="selected.allowedActions.length" class="repair-operation-panel"><h3>{{ mode === 'mine' ? '你可以继续办理' : '当前可处理操作' }}</h3><button v-if="can('edit')" class="button compact" :disabled="busy" @click="edit">修改报修资料</button><label v-if="can('assign') || can('reassign')">维修人员<BaseSelect v-model="technicianId" :options="technicianOptions" :disabled="busy" /></label><p v-if="(can('assign') || can('reassign')) && !technicians.length" class="data-note">尚无当前范围匹配的有效维修人员，请联系管理员配置。</p><label v-if="can('priority')">处理优先级<BaseSelect v-model="priority" :options="priorityOptions" :disabled="busy" /></label><label v-if="can('evaluation')">服务评分<BaseSelect v-model="stars" :options="starOptions" :disabled="busy" /></label><label><div class="label-heading-row"><span>处理说明{{ can('evaluation') ? '／评价（可选）' : '' }}</span><small class="muted">最多 {{ can('evaluation') ? 500 : 2000 }} 字</small></div><textarea v-model="comment" :maxlength="can('evaluation') ? 500 : 2000" rows="3" :disabled="busy" placeholder="补充、拒绝、派单、进展、结果、退回及撤回须填写说明" /></label><div class="repair-inline-actions"><button v-for="action in selected.allowedActions.filter(a => !a.startsWith('upload-') && !a.startsWith('remove-') && a !== 'edit')" :key="action" class="button compact" :class="{ primary: ['submit', 'assign', 'accept', 'complete', 'confirm', 'evaluation'].includes(action) }" :disabled="busy || (['assign', 'reassign'].includes(action) && !technicians.length)" @click="transition(action)">{{ repairActions[action] }}</button></div></section>
          </template>
        </template>
        <div v-else class="repair-empty-detail"><Wrench :size="32" /><h2>{{ mode === 'mine' ? '每一次报修，都有可追踪的进度' : '选择一条工单查看资料' }}</h2><p>{{ mode === 'mine' ? '从左侧查看已有记录，或新建报修描述故障。' : '处理操作根据当前状态、负责范围与分配人员显示。' }}</p></div>
      </section>
    </div>
    <p class="data-note">毕业设计演示流程。办理记录随实际操作更新；取消申请由当前后勤岗位处理。</p>
    <dialog ref="dialog" class="confirmation-dialog" aria-labelledby="repair-dialog-title" @cancel.prevent="finish(false)">
      <div class="dialog-header">
        <div class="dialog-icon-badge">
          <AlertCircle :size="20" />
        </div>
        <h2 id="repair-dialog-title">请核对本次操作</h2>
      </div>
      <p>{{ confirmation }}</p>
      <div class="dialog-actions">
        <button ref="cancelButton" type="button" class="button compact dialog-btn-cancel" @click="finish(false)">取消，继续编辑</button>
        <button type="button" class="button primary compact dialog-btn-confirm" @click="finish(true)">确认继续</button>
      </div>
    </dialog>
  </div>
</template>
