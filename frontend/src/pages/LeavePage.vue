<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { CalendarDays, ClipboardCheck, FileText, History, Plus, RefreshCw } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import { leaveStatuses, leaveTypes } from '../leave'
import type { LeaveEligibility, LeaveEvent, LeaveRequest, LeaveRevision } from '../leave'
import type { Paged } from '../foundation'
import BaseSelect from '../components/BaseSelect.vue'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const route = useRoute(), router = useRouter()
const review = computed(() => route.path.startsWith('/student-affairs'))
const mode = computed(() => review.value ? 'review' : 'mine')
const root = computed(() => '/leave/' + mode.value)
const status = ref(''), loading = ref(true), busy = ref(false), editing = ref(false), error = ref(''), success = ref('')
const calendarView = computed(() => !review.value && !editing.value && route.query.tab === 'calendar')
const historyView = computed(() => route.query.tab === 'history')
const mainNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: mainIndicatorStyle } = useSlidingIndicator(
  mainNavRef,
  () => calendarView.value
)
const detailNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: detailIndicatorStyle } = useSlidingIndicator(
  detailNavRef,
  () => historyView.value
)
const eligibility = ref<LeaveEligibility | null>(null)
const items = ref<Paged<LeaveRequest>>({ items: [], total: 0, page: 1, pageSize: 20 })
const history = ref<Paged<LeaveRevision>>({ items: [], total: 0, page: 1, pageSize: 10 })
const events = ref<LeaveEvent[]>([])
const selected = ref<LeaveRequest | null>(null)
const file = ref<File | null>(null), fileInput = ref<HTMLInputElement | null>(null)
const reviewComment = ref(''), month = ref(new Date().getFullYear() + '-' + String(new Date().getMonth() + 1).padStart(2, '0'))
const form = reactive({ requestKey: '', reasonType: 'PERSONAL', reason: '', contact: '', startsAt: '', endsAt: '', version: 0 })
let baseline = '', sequence = 0
const dirty = computed(() => editing.value && JSON.stringify(form) !== baseline || !!file.value || !!reviewComment.value.trim())
const editable = computed(() => selected.value && ['DRAFT', 'RETURNED'].includes(selected.value.status))
const actions: Record<string, string> = { CREATED: '保存草稿', UPDATED: '修改资料', FILE: '补充材料', SUBMITTED: '提交审核', RETURNED: '退回补交', APPROVED: '通过申请', REJECTED: '拒绝申请', WITHDRAWN: '撤回申请' }
const dialog = ref<HTMLDialogElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null)
const confirmation = ref(''), confirmKind = ref('leave'), dialogComment = ref('')
let resolveConfirmation: ((value: boolean) => void) | null = null
function time(value: string) { return value.slice(0, 16).replace('T', ' ') }
async function ask(message: string, kind = 'leave') {
  confirmation.value = message; confirmKind.value = kind; dialogComment.value = ''
  return new Promise<boolean>(resolve => { resolveConfirmation = resolve; void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() }) })
}
function finish(accept: boolean) { dialog.value?.close(); const resolve = resolveConfirmation; resolveConfirmation = null; resolve?.(accept) }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在提交，请等待完成后再离开。'; return false }
  return !dirty.value || await ask('还有未保存的文字或待上传材料。离开会放弃这些输入，请先保存或确认放弃。')
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
async function readCalendar() {
  const [year, number] = month.value.split('-').map(Number)
  if (!year || !number || number > 12) return
  const end = new Date(year, number, 1)
  events.value = await api<LeaveEvent[]>(`/leave/mine/calendar?from=${month.value}-01&to=${end.getFullYear()}-${String(end.getMonth() + 1).padStart(2, '0')}-01`)
}
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; editing.value = false; file.value = null; reviewComment.value = ''
  try {
    if (!review.value) eligibility.value = await api<LeaveEligibility>('/leave/mine/eligibility')
    status.value = String(route.query.status ?? (review.value ? 'SUBMITTED' : ''))
    const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
    if (status.value) params.set('status', status.value)
    const list = await api<Paged<LeaveRequest>>(root.value + '?' + params)
    items.value = list
    if (!route.query.id || String(selected.value?.id) !== String(route.query.id)) {
      selected.value = null
    }
    if (calendarView.value) { await readCalendar(); return }
    if (route.query.id) {
      const detail = await api<LeaveRequest>(root.value + '/' + String(route.query.id))
      if (request !== sequence) return
      selected.value = detail
      // Show the latest processing opinion on the detail view as well as in the full history.
      const records = await api<Paged<LeaveRevision>>(`${root.value}/${detail.id}/history?page=${historyView.value ? Math.max(1, Number(route.query.historyPage) || 1) : 1}&pageSize=${historyView.value ? 10 : 1}`)
      if (request === sequence) history.value = records
    }
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function refresh() { if (await allowLeave()) { success.value = ''; await load() } }
async function choose(item: LeaveRequest) {
  if (String(item.id) === route.query.id && !calendarView.value && !historyView.value) { if (await allowLeave()) await load() }
  else await router.replace({ query: { ...route.query, id: String(item.id), tab: 'details', historyPage: '1' } })
}
async function view(tab: string) { await router.replace({ query: { ...route.query, tab, historyPage: '1' } }) }
async function search() { await router.replace({ query: { ...route.query, status: status.value, page: '1' } }) }
async function page(number: number, record = false) { await router.replace({ query: { ...route.query, [record ? 'historyPage' : 'page']: String(number) } }) }
async function edit(item: LeaveRequest | null = null) {
  if (!await allowLeave()) return
  selected.value = item; file.value = null; reviewComment.value = ''; error.value = ''; success.value = ''
  Object.assign(form, item ? { requestKey: item.requestKey, reasonType: item.reasonType, reason: item.reason, contact: item.contact, startsAt: item.startsAt.slice(0, 16), endsAt: item.endsAt.slice(0, 16), version: item.version } :
    { requestKey: crypto.randomUUID(), reasonType: 'PERSONAL', reason: '', contact: '', startsAt: '', endsAt: '', version: 0 })
  baseline = JSON.stringify(form); editing.value = true
  await nextTick(); document.getElementById('leave-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function cancelEdit() { if (await allowLeave()) { editing.value = false; file.value = null; reviewComment.value = ''; await load() } }
async function save() {
  busy.value = true; error.value = ''; success.value = ''
  try {
    const result = await api<LeaveRequest>(selected.value ? root.value + '/' + selected.value.id : root.value, { method: selected.value ? 'PUT' : 'POST', body: JSON.stringify(form) })
    editing.value = false; baseline = JSON.stringify(form); busy.value = false
    if (route.query.id === String(result.id)) await load()
    else await router.replace({ query: { ...route.query, id: String(result.id), tab: 'details', historyPage: '1' } })
    success.value = '申请资料已保存。核对时间、材料后再提交审核。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file.value && (file.value.size === 0 || file.value.size > 2 * 1024 * 1024)) { error.value = '附件须为 1 字节至 2 MB。'; file.value = null; if (fileInput.value) fileInput.value.value = '' }
}
async function upload() {
  if (!selected.value || !file.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const data = new FormData(); data.set('file', file.value)
    await api(`${root.value}/${selected.value.id}/file?version=${selected.value.version}`, { method: 'POST', body: data })
    file.value = null; if (fileInput.value) fileInput.value.value = ''; await load(); success.value = '材料已保存，旧版附件保留在办理记录中。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function transition(action: 'submit' | 'withdraw') {
  if (!selected.value) return
  if (file.value) { error.value = '还有待上传材料，请先上传或清除选择后再操作。'; return }
  const item = selected.value
  if (!await ask(action === 'submit' ? `确认提交申请 #${item.id}？时间为 ${time(item.startsAt)} 至 ${time(item.endsAt)}，提交后由负责班级的辅导员处理。` : `确认撤回申请 #${item.id}？原记录保留，重新办理须新建申请。`, action)) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`${root.value}/${item.id}/${action}`, { method: 'POST', body: JSON.stringify({ version: item.version, comment: action === 'submit' ? '本人核对资料后提交' : dialogComment.value }) })
    await load(); success.value = action === 'submit' ? '申请已提交，可在办理记录中查看处理进度。' : '申请已撤回，审核待办已关闭。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function decide(action: string) {
  if (!selected.value || !reviewComment.value.trim()) return
  const item = selected.value, comment = reviewComment.value
  const label = action === 'APPROVE' ? '通过申请并生成请假日程' : action === 'RETURN' ? '退回补交' : '拒绝申请'
  if (!await ask(`确认对 ${item.studentName} 的申请 #${item.id} 执行“${label}”？处理意见将保留在记录中。`, 'decision')) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`${root.value}/${item.id}/decision`, { method: 'POST', body: JSON.stringify({ version: item.version, action, comment }) })
    reviewComment.value = ''; await load(); success.value = '处理结果已保存，学生已收到个人消息，原待办已关闭。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function calendarChange() { loading.value = true; error.value = ''; try { await readCalendar() } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); finish(false) })
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
watch(() => route.fullPath, () => { void load() })
</script>

<template>
  <div class="leave-page">
    <dialog ref="dialog" class="foundation-dialog" aria-labelledby="leave-dialog-heading" @cancel.prevent="finish(false)">
      <h2 id="leave-dialog-heading">{{ confirmKind === 'leave' ? '处理未保存的输入' : '核对本次操作' }}</h2><p>{{ confirmation }}</p>
      <label v-if="confirmKind === 'withdraw'"><div class="label-heading-row"><span>撤回原因</span><small class="muted">必填 · 最多 1000 字</small></div><textarea v-model="dialogComment" rows="2" maxlength="1000" placeholder="请详细说明撤回申请的原因" /></label>
      <div class="form-actions"><button ref="cancelButton" class="button compact" @click="finish(false)">{{ confirmKind === 'leave' ? '继续编辑' : '取消' }}</button><button class="button primary compact" :disabled="confirmKind === 'withdraw' && !dialogComment.trim()" @click="finish(true)">{{ confirmKind === 'leave' ? '放弃修改并继续' : '确认操作' }}</button></div>
    </dialog>
    <div class="page-heading-row"><div><span class="portal-context">{{ review ? '学生事务处理' : '学生办事' }}</span><h1>{{ review ? '班级请假处理' : '我的请假' }}</h1><p class="muted">{{ review ? '核对所负责行政班的申请，记录意见并处理待办。' : '办理本人请假，查看补交要求、处理进度与日程。' }}</p></div><div class="form-actions"><button class="button compact" :disabled="busy || loading" @click="refresh"><RefreshCw :size="15" />刷新记录</button><button v-if="!review" class="button primary compact" :disabled="busy || loading || !eligibility?.classGroupId" @click="edit()"><Plus :size="15" />新建申请</button></div></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="!review && eligibility" class="content-context-note">{{ eligibility.className ?? '行政班未分配' }}<span v-if="eligibility.counselorName"> · 负责辅导员：{{ eligibility.counselorName }}</span>。{{ eligibility.message }}</p>
    <nav v-if="!review" ref="mainNavRef" class="learning-tabs" aria-label="请假办理栏目">
      <button :class="{ selected: !calendarView }" :disabled="busy" @click="view('details')"><FileText :size="15" />申请记录</button>
      <button :class="{ selected: calendarView }" :disabled="busy" @click="view('calendar')"><CalendarDays :size="15" />请假日程</button>
      <div class="learning-tabs-indicator" :style="mainIndicatorStyle" aria-hidden="true"></div>
    </nav>
    <section v-if="calendarView" class="portal-panel" aria-label="本人请假日程"><div class="section-heading"><h2>已通过的请假日程</h2><label>月份<input v-model="month" type="month" :disabled="loading" @change="calendarChange" /></label></div><p class="muted">仅显示该月与本人已通过请假的交集；课程和考勤不会自动改变。</p><p v-if="loading" role="status">正在读取日程…</p><p v-else-if="!events.length" class="empty-state">本月没有已通过的请假日程。</p><article v-for="event in events" :key="event.requestId" class="leave-event"><CalendarDays :size="18" /><div><strong>请假申请 #{{ event.requestId }}</strong><p>{{ time(event.startsAt) }} 至 {{ time(event.endsAt) }}</p></div><button class="text-button" :disabled="busy" @click="router.replace({ query: { ...route.query, id: String(event.requestId), tab: 'details' } })">查看申请</button></article></section>
    <template v-else>
      <form class="foundation-filters leave-filters" @submit.prevent="search"><label>申请状态<BaseSelect v-model="status" :options="[{ value: '', label: '全部记录' }, ...Object.entries(leaveStatuses).filter(([k]) => !review || k !== 'DRAFT').map(([key, label]) => ({ value: key, label }))]" compact /></label><button class="button compact" :disabled="loading || busy">查询</button><span class="muted">{{ review ? '待办来自当前申请状态，处理后自动关闭。' : '草稿未提交；待补交申请仍保留原办理记录。' }}</span></form>
      <section class="portal-panel leave-list" aria-label="请假申请列表"><div class="section-heading"><h2><ClipboardCheck :size="17" />{{ review ? '班级申请' : '本人申请' }}</h2><span class="muted">共 {{ items.total }} 条</span></div><p v-if="loading && !items.items.length" class="loading-state" role="status">正在读取请假记录…</p><p v-else-if="!loading && !items.items.length" class="empty-state">{{ review ? '当前筛选下没有负责班级的申请；未分配班级时也不会显示他人记录。' : '当前筛选下没有申请，可新建请假草稿。' }}</p><div v-if="items.items.length" class="table-scroll" :class="{ 'is-loading': loading }"><table class="campus-table foundation-table leave-table"><thead><tr><th>申请</th><th v-if="review">学生与行政班</th><th>请假时间</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="item in items.items" :key="item.id" :class="{ 'resource-selected': item.id === selected?.id }"><td><strong>{{ leaveTypes[item.reasonType] }}申请 #{{ item.id }}</strong></td><td v-if="review">{{ item.studentName }}<small>{{ item.identityNumber }} · {{ item.className }}</small></td><td>{{ time(item.startsAt) }}<small>至 {{ time(item.endsAt) }}</small></td><td><span class="status-label">{{ leaveStatuses[item.status] }}</span></td><td>{{ time(item.updatedAt) }}</td><td><button class="text-button" :disabled="busy" @click="choose(item)">{{ review ? '查看与处理' : '查看申请' }}</button></td></tr></tbody></table></div><div class="foundation-pagination"><span>第 {{ items.page }} 页</span><div><button class="button compact" :disabled="loading || busy || items.page <= 1" @click="page(items.page - 1)">上一页</button><button class="button compact" :disabled="loading || busy || items.page * items.pageSize >= items.total" @click="page(items.page + 1)">下一页</button></div></div></section>
      <section v-if="editing" class="portal-panel leave-detail" aria-label="请假资料编辑"><h2 id="leave-editor-heading">{{ selected ? '修改申请资料' : '新建请假草稿' }}</h2><p class="muted">先保存资料，再上传材料及提交。演示流程单次最多 30 天，材料按辅导员处理要求补充。</p><form class="editor-form" @submit.prevent="save"><fieldset class="foundation-fields" :disabled="busy"><label>请假类型<BaseSelect v-model="form.reasonType" :options="Object.entries(leaveTypes).map(([key, label]) => ({ value: key, label }))" /></label><div class="form-grid"><label>开始时间<input v-model="form.startsAt" type="datetime-local" required /></label><label>结束时间<input v-model="form.endsAt" type="datetime-local" required /></label></div><label><div class="label-heading-row"><span>请假事由</span><small class="muted">必填 · 最多 2000 字</small></div><textarea v-model="form.reason" required maxlength="2000" rows="4" placeholder="请详细说明请假的具体事由及离返校安排，避免填写与办理无关的个人隐私信息" /></label><label>联系手机或邮箱<input v-model="form.contact" required maxlength="80" placeholder="用于办理联系，仅本人和负责辅导员可查看" /></label></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : '保存申请资料' }}</button><button class="button compact" type="button" :disabled="busy" @click="cancelEdit">取消编辑</button></div></form></section>
      <section v-else-if="selected" class="portal-panel leave-detail" aria-label="请假申请详情">
<div class="section-heading"><div><span class="portal-context">申请 #{{ selected.id }} · {{ selected.className }}</span><h2>{{ leaveTypes[selected.reasonType] }}申请<span v-if="review"> · {{ selected.studentName }}</span></h2></div><span class="status-label">{{ leaveStatuses[selected.status] }} · V{{ selected.version }}</span></div><nav ref="detailNavRef" class="learning-tabs" aria-label="申请详情栏目">
  <button :class="{ selected: !historyView }" :disabled="busy" @click="view('details')"><FileText :size="15" />申请资料</button>
  <button :class="{ selected: historyView }" :disabled="busy" @click="view('history')"><History :size="15" />办理记录</button>
  <div class="learning-tabs-indicator" :style="detailIndicatorStyle" aria-hidden="true"></div>
</nav>
        <template v-if="!historyView">
<dl class="resource-facts"><div><dt>请假时间</dt><dd>{{ time(selected.startsAt) }} 至 {{ time(selected.endsAt) }}</dd></div><div><dt>联系方式</dt><dd>{{ selected.contact }}</dd></div><div class="leave-wide"><dt>请假事由</dt><dd class="preserve-lines">{{ selected.reason }}</dd></div><div><dt>当前材料</dt><dd><a v-if="selected.fileId" class="text-button" :href="`/api/leave/files/${selected.fileId}?view=${mode}`">下载 {{ selected.fileName }}</a><span v-else>尚无附件</span></dd></div><div><dt>最近处理意见</dt><dd class="preserve-lines">{{ selected.processingComment ?? '尚无处理意见' }}</dd></div></dl>
          <template v-if="!review && editable"><form class="leave-upload" @submit.prevent="upload"><label>补充材料（PDF / UTF-8 文本，最多 2 MB）<input ref="fileInput" type="file" accept=".pdf,.txt" :disabled="busy" @change="selectFile" /></label><button class="button compact" :disabled="busy || !file">上传材料</button><button v-if="file" class="text-button" type="button" :disabled="busy" @click="file = null; fileInput && (fileInput.value = '')">清除选择</button></form><div class="form-actions"><button class="button compact" :disabled="busy" @click="edit(selected)">修改申请资料</button><button class="button primary compact" :disabled="busy || !eligibility?.canSubmit" @click="transition('submit')">{{ selected.status === 'RETURNED' ? '补交后重新提交' : '提交审核' }}</button></div></template>
          <button v-if="!review && ['DRAFT', 'SUBMITTED', 'RETURNED'].includes(selected.status)" class="text-button leave-withdraw" :disabled="busy" @click="transition('withdraw')">撤回申请</button>
          <form v-if="review && selected.status === 'SUBMITTED'" class="leave-review-form" @submit.prevent="decide('APPROVE')"><label><div class="label-heading-row"><span>处理意见</span><small class="muted">必填 · 最多 1000 字</small></div><textarea v-model="reviewComment" rows="3" required maxlength="1000" :disabled="busy" placeholder="填写核对结果与审核处理意见；退回时请明确说明需要补充哪些证明材料" /></label><div class="form-actions"><button class="button primary compact" :disabled="busy || !reviewComment.trim()">已核对，通过</button><button class="button compact" type="button" :disabled="busy || !reviewComment.trim()" @click="decide('RETURN')">退回补交</button><button class="button compact" type="button" :disabled="busy || !reviewComment.trim()" @click="decide('REJECT')">拒绝申请</button></div></form><p v-if="selected.status === 'APPROVED'" class="content-context-note">请假已通过，办理完成并生成本人日程；课程及考勤不会自动改变。变更或销假流程尚待接入。</p><p v-if="['REJECTED', 'WITHDRAWN'].includes(selected.status)" class="muted">该记录已关闭，历史保留。需要重新办理时请新建申请。</p>
        </template>
        <template v-else><p class="muted">每次保存、补交和处理均保留当时的资料，共 {{ history.total }} 条。</p><article v-for="record in history.items" :key="record.id" class="resource-history"><div class="section-heading"><h3>{{ actions[record.action] ?? record.action }} · V{{ record.version }}</h3><span class="status-label">{{ leaveStatuses[record.status] }}</span></div><p class="preserve-lines">{{ record.comment }}</p><small class="muted">{{ record.actorName }} · {{ time(record.createdAt) }}</small><details class="leave-revision"><summary>查看当时资料和附件</summary><p>{{ leaveTypes[record.reasonType] }} · {{ time(record.startsAt) }} 至 {{ time(record.endsAt) }}</p><p class="preserve-lines">{{ record.reason }}</p><p>联系方式：{{ record.contact }}</p><a v-if="record.fileId" class="text-button" :href="`/api/leave/files/${record.fileId}?view=${mode}`">下载当时材料：{{ record.fileName }}</a></details></article><div class="foundation-pagination"><span>第 {{ history.page }} 页</span><div><button class="button compact" :disabled="loading || busy || history.page <= 1" @click="page(history.page - 1, true)">上一页</button><button class="button compact" :disabled="loading || busy || history.page * history.pageSize >= history.total" @click="page(history.page + 1, true)">下一页</button></div></div></template>
      </section>
    </template>
    <p class="data-note">毕业设计演示流程，不代表学校实际请假制度。申请原因、联系方式和材料受权限保护，普通任课教师与管理员不能读取。</p>
  </div>
</template>
