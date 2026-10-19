<script setup lang="ts">
import DownloadLink from '../components/DownloadLink.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { Component } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { AlertCircle, AlertTriangle, ArrowLeft, ArrowRight, Award, Camera, Check, CheckCircle2, ClipboardCheck, Clock, FileText, History, MapPin, Plus, RefreshCw, SlidersHorizontal, User, UserCheck, Wrench, X, ZoomIn } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { repairLocationQuery, type RepairPublicLocation } from '../repairSettings'
import { currentUser } from '../stores/auth'
import type { Paged } from '../foundation'
import { cancellationStatuses, repairActions, repairCancellationMutation, repairCategories, repairDraft, repairForm, repairOperation, repairSnapshot, repairStatuses } from '../repairs'
import type { RepairDetail, RepairHistory, RepairImage, RepairItem, RepairSummary } from '../repairs'
import BaseSelect from '../components/BaseSelect.vue'
import ContactTimeSelect from '../components/ContactTimeSelect.vue'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import { useToast } from '../composables/useToast'

const route = useRoute(), router = useRouter()
const toast = useToast()
const mode = computed(() => route.path.endsWith('/dispatch') ? 'dispatch' : route.path.endsWith('/tasks') ? 'work' : 'mine')
const root = computed(() => '/repairs/' + mode.value)
const title = computed(() => mode.value === 'mine' ? '我的校园报修' : mode.value === 'dispatch' ? '后勤分诊与派单' : '我的维修任务')
const intro = computed(() => mode.value === 'mine' ? '记录校园设施故障，跟进实际处理进度，维修后由你验收。' : mode.value === 'dispatch' ? '核对负责范围内的故障，补充资料、分配人员并跟进处理。' : '查看当前分配给你的任务，记录进展并提交维修结果。')
const list = ref<Paged<RepairItem>>({ items: [], total: 0, page: 1, pageSize: 20 })
const records = ref<Paged<RepairHistory>>({ items: [], total: 0, page: 1, pageSize: 10 })
const selected = ref<RepairDetail | null>(null), summary = ref<RepairSummary | null>(null)
const loading = ref(true), busy = ref(false), editing = ref(false), error = ref(''), success = ref(''), status = ref('')
const cancellationFilter = ref('')
const filtersOpen = ref(false)
const mobileDetailOpen = ref(!!route.query.id || route.query.new === '1')
const operationPanel = ref<HTMLElement | null>(null)
let listScrollPosition = 0
const quickFilters = computed(() => mode.value === 'mine'
  ? [{ value: '', label: '全部工单' }, { value: 'RETURNED', label: '待补充' }, { value: 'AWAITING_CONFIRMATION', label: '待验收' }, { value: 'COMPLETED', label: '已完成' }]
  : mode.value === 'dispatch'
    ? [{ value: '', label: '全部工单' }, { value: 'SUBMITTED', label: '待分诊' }, { value: 'ASSIGNED', label: '待接单' }, { value: 'IN_PROGRESS', label: '维修中' }]
    : [{ value: '', label: '全部任务' }, { value: 'ASSIGNED', label: '待接单' }, { value: 'IN_PROGRESS', label: '维修中' }, { value: 'AWAITING_CONFIRMATION', label: '待验收' }])
const queueItems = computed(() => {
  const s = summary.value
  if (!s) return []
  const items = mode.value === 'mine'
    ? [{ label: '待补充资料', count: s.mineReturned, status: 'RETURNED', cancellation: '' }, { label: '待你验收', count: s.mineAwaitingConfirmation, status: 'AWAITING_CONFIRMATION', cancellation: '' }, { label: '取消待处理', count: s.mineCancellationPending, status: '', cancellation: 'true' }]
    : mode.value === 'dispatch'
      ? [{ label: '待分诊', count: s.dispatchPending, status: 'SUBMITTED', cancellation: '' }, { label: '取消待处理', count: s.dispatchCancellationPending, status: '', cancellation: 'true' }]
      : [{ label: '待你接单', count: s.workAssigned, status: 'ASSIGNED', cancellation: '' }, { label: '维修中', count: s.workInProgress, status: 'IN_PROGRESS', cancellation: '' }]
  return items.filter(item => item.count > 0)
})
const isClosed = computed(() => !!selected.value && ['COMPLETED', 'REJECTED', 'WITHDRAWN', 'CANCELLED'].includes(selected.value.status))
const operationActions = computed(() => selected.value?.allowedActions.filter(a => !a.startsWith('upload-') && !a.startsWith('remove-') && a !== 'edit') ?? [])
const primaryAction = computed(() => ['submit', 'assign', 'accept', 'complete', 'confirm', 'evaluation', 'approve-cancellation', 'reassign', 'progress'].find(action => operationActions.value.includes(action)))
const currentTask = computed(() => {
  if (can('edit')) return { label: selected.value?.status === 'RETURNED' ? '补充报修资料' : '编辑报修资料', edit: true }
  if (can('confirm')) return { label: '办理维修验收', edit: false }
  if (can('assign') || can('reassign')) return { label: can('assign') ? '分配维修人员' : '调整派工', edit: false }
  if (can('accept')) return { label: '办理接单', edit: false }
  if (can('complete')) return { label: '记录维修与结果', edit: false }
  if (can('evaluation')) return { label: '评价本次服务', edit: false }
  if (can('approve-cancellation')) return { label: '处理取消申请', edit: false }
  if (can('withdraw-cancellation')) return { label: '查看取消申请', edit: false }
  return null
})
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
const selectedLocation = computed(() => locations.value.items.find(l => String(l.id) === form.locationId))
const selectedLocationHint = computed(() => selectedLocation.value?.detailHint || prefilledLocation.value?.detailHint || '')
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
interface PendingFaultFile { file: File; preview: string }
const pendingFaultFiles = ref<PendingFaultFile[]>([])
const previewModalUrl = ref<string | null>(null)
const imagePreviewDialog = ref<HTMLDialogElement | null>(null)
function previewImage(url: string) {
  previewModalUrl.value = url
  void nextTick(() => imagePreviewDialog.value?.showModal?.())
}
function closePreview() {
  imagePreviewDialog.value?.close?.()
  previewModalUrl.value = null
}
const existingFaultImages = computed(() => selected.value?.images?.filter(img => img.purpose === 'FAULT') ?? [])
const faultImages = existingFaultImages
const maintenanceImages = computed(() => selected.value?.images?.filter(img => img.purpose === 'PROGRESS' || img.purpose === 'RESULT') ?? [])

function historyActorRole(record: RepairHistory): { label: string; class: 'reporter' | 'technician' | 'dispatcher' | 'other' } {
  if (!selected.value) return { label: '经办人', class: 'other' }
  if (record.actorName === selected.value.reporterName) {
    return { label: '报修人', class: 'reporter' }
  }
  if (selected.value.technicianName && record.actorName === selected.value.technicianName) {
    return { label: '维修人员', class: 'technician' }
  }
  if (['assign', 'reassign', 'return', 'reject', 'approve-cancellation', 'reject-cancellation'].includes(record.action)) {
    return { label: '后勤调度', class: 'dispatcher' }
  }
  return { label: '经办人', class: 'other' }
}
const currentFaultImageCount = computed(() => existingFaultImages.value.length + pendingFaultFiles.value.length)
function onSelectFormFiles(event: Event) {
  const target = event.target as HTMLInputElement
  const files = target.files ? Array.from(target.files) : []
  if (!files.length) return
  error.value = ''
  for (const f of files) {
    if (!['image/jpeg', 'image/png'].includes(f.type)) {
      promptAlert(`文件「${f.name}」格式不支持，请选择 JPG 或 PNG 图片。`, '格式不支持', 'warning')
      continue
    }
    if (f.size < 1 || f.size > 2 * 1024 * 1024) {
      promptAlert(`文件「${f.name}」大小超过 2 MB，请压缩后再选择。`, '文件过大', 'warning')
      continue
    }
    if (currentFaultImageCount.value >= 6) {
      promptAlert('故障现场图片最多可添加 6 张，已达到数量上限。', '数量上限提示', 'warning')
      break
    }
    const preview = typeof URL.createObjectURL === 'function' ? URL.createObjectURL(f) : ''
    pendingFaultFiles.value.push({ file: f, preview })
  }
  if (target) target.value = ''
}
function removePendingFile(index: number) {
  const item = pendingFaultFiles.value[index]
  if (item) {
    if (item.preview && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(item.preview)
    pendingFaultFiles.value.splice(index, 1)
  }
}
function clearPendingFaultFiles() {
  for (const item of pendingFaultFiles.value) {
    if (item.preview && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(item.preview)
  }
  pendingFaultFiles.value = []
}
const operationKey = repairOperation()
let baseline = '', sequence = 0, locationSequence = 0
const dirty = computed(() => editing.value && JSON.stringify(form) !== baseline || pendingFaultFiles.value.length > 0 || !!comment.value.trim() || !!file.value || !!technicianId.value || !!selected.value && priority.value !== selected.value.priority)
const historyView = computed(() => route.query.tab === 'history')
const repairNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: repairIndicatorStyle } = useSlidingIndicator(
  repairNavRef,
  () => historyView.value
)
const dialog = ref<HTMLDialogElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null), confirmation = ref('')
let resolveConfirmation: ((accept: boolean) => void) | null = null
const alertDialog = ref<HTMLDialogElement | null>(null)
const alertOkButton = ref<HTMLButtonElement | null>(null)
const commentTextareaRef = ref<HTMLTextAreaElement | null>(null)
const alertState = reactive({
  title: '操作提示',
  message: '',
  type: 'warning' as 'warning' | 'info' | 'error',
})
let onAlertDismiss: (() => void) | null = null

function promptAlert(
  message: string,
  title = '操作提示',
  type: 'warning' | 'info' | 'error' = 'warning',
  onDismiss?: () => void
) {
  error.value = message
  alertState.title = title
  alertState.message = message
  alertState.type = type
  onAlertDismiss = onDismiss ?? null

  if (type === 'error') toast.error(message)
  else if (type === 'warning') toast.warning(message)
  else toast.info(message)

  void nextTick(() => {
    alertDialog.value?.showModal?.()
    alertOkButton.value?.focus?.()
  })
}

function closeAlert() {
  alertDialog.value?.close?.()
  const cb = onAlertDismiss
  onAlertDismiss = null
  cb?.()
}
function can(action: string) { return !!selected.value?.allowedActions.includes(action) }
function time(value: string | null) { return value ? value.slice(0, 16).replace('T', ' ') : '尚未发生' }
function stepTime(value: string | null | undefined): string | null {
  if (!value) return null
  return value.slice(5, 16).replace('T', ' ')
}

const workflowSteps = computed(() => {
  const d = selected.value
  if (!d) return []

  // Step 1: 提交报修
  let s1State: 'completed' | 'active' | 'warning' | 'rejected' = 'completed'
  let s1Subtitle = '已提交报修资料'
  let s1Time = stepTime(d.submittedAt || d.createdAt)
  if (d.status === 'DRAFT') {
    s1State = 'active'
    s1Subtitle = '草稿尚未提交'
  } else if (d.status === 'RETURNED') {
    s1State = 'warning'
    s1Subtitle = '需补充资料'
  } else if (d.status === 'WITHDRAWN') {
    s1State = 'rejected'
    s1Subtitle = '报修人已撤回'
  }

  // Step 2: 后勤派工
  let s2State: 'completed' | 'active' | 'pending' | 'warning' | 'rejected'
  let s2Subtitle = '等待后勤分派'
  let s2Time = d.assignments?.[0]?.assignedAt ? stepTime(d.assignments[0].assignedAt) : null

  if (['DRAFT', 'WITHDRAWN'].includes(d.status)) {
    s2State = 'pending'
    s2Subtitle = '待提交后分派'
  } else if (d.status === 'REJECTED') {
    s2State = 'rejected'
    s2Subtitle = '后勤拒绝受理'
  } else if (d.status === 'RETURNED') {
    s2State = 'pending'
    s2Subtitle = '待补充后分派'
  } else if (d.status === 'SUBMITTED') {
    s2State = 'active'
    s2Subtitle = '后勤分诊派单中'
  } else if (d.technicianName || ['ASSIGNED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION', 'COMPLETED'].includes(d.status)) {
    s2State = 'completed'
    s2Subtitle = d.technicianName ? `已指派 ${d.technicianName}` : '已指派维修人员'
  } else {
    s2State = 'active'
  }

  // Step 3: 上门维修
  let s3State: 'completed' | 'active' | 'pending' | 'warning' | 'rejected' = 'pending'
  let s3Subtitle = '待师傅接单处理'
  let s3Time = d.completions?.[0]?.createdAt ? stepTime(d.completions[0].createdAt) : null

  if (['DRAFT', 'SUBMITTED', 'RETURNED', 'WITHDRAWN', 'REJECTED'].includes(d.status)) {
    s3State = 'pending'
    s3Subtitle = '待后勤派工'
  } else if (d.status === 'CANCELLED') {
    s3State = 'rejected'
    s3Subtitle = '工单已取消'
  } else if (d.status === 'ASSIGNED') {
    s3State = 'active'
    s3Subtitle = d.technicianName ? `${d.technicianName} 待接单` : '师傅待接单'
  } else if (d.status === 'IN_PROGRESS') {
    s3State = 'active'
    s3Subtitle = '师傅已接单，维修中'
  } else if (['AWAITING_CONFIRMATION', 'COMPLETED'].includes(d.status)) {
    s3State = 'completed'
    s3Subtitle = d.completions?.length ? `维修完成 (第${d.completionRound}轮)` : '已完成维修'
  }

  // Step 4: 验收办结
  let s4State: 'completed' | 'active' | 'pending' | 'warning' | 'rejected' = 'pending'
  let s4Subtitle = '维修后本人验收'
  let s4Time = d.confirmedAt ? stepTime(d.confirmedAt) : null

  if (d.status === 'COMPLETED') {
    s4State = 'completed'
    s4Subtitle = d.evaluation ? `已验收 · ${d.evaluation.stars} 星` : '本人已确认解决'
  } else if (d.status === 'AWAITING_CONFIRMATION') {
    s4State = 'active'
    s4Subtitle = d.confirmationOverdue ? '待你验收 · 演示提醒' : '待你核验确认'
  } else if (['WITHDRAWN', 'REJECTED', 'CANCELLED'].includes(d.status)) {
    s4State = 'rejected'
    s4Subtitle = '工单已终止'
  }

  return [
    { id: 1, title: '提交报修', subtitle: s1Subtitle, time: s1Time, state: s1State },
    { id: 2, title: '后勤派工', subtitle: s2Subtitle, time: s2Time, state: s2State },
    { id: 3, title: '维修处理', subtitle: s3Subtitle, time: s3Time, state: s3State },
    { id: 4, title: '验收办结', subtitle: s4Subtitle, time: s4Time, state: s4State },
  ]
})

const stepperOverview = computed<{ text: string; hint: string; class: string; icon: Component }>(() => {
  const d = selected.value
  if (!d) return { text: '', hint: '', class: 'info', icon: FileText }

  if (d.cancellation?.status === 'PENDING') {
    return {
      text: '工单暂停办理：取消申请等待后勤处理',
      hint: '报修人可撤销取消申请，或等待后勤审核决定',
      class: 'warning',
      icon: AlertTriangle,
    }
  }
  if (d.status === 'DRAFT') {
    return {
      text: '工单处于草稿阶段，尚未正式提交',
      hint: '完善资料并保存后，核对无误再点击“提交报修”',
      class: 'info',
      icon: FileText,
    }
  }
  if (d.status === 'SUBMITTED') {
    return {
      text: '已提交报修，等待后勤分诊与指派人员',
      hint: '后勤核对地点与故障情况后，将分配维修人员',
      class: 'active',
      icon: Clock,
    }
  }
  if (d.status === 'RETURNED') {
    return {
      text: '后勤要求补充资料',
      hint: d.processingComment || '请根据后勤说明补充具体位置或故障现象细节',
      class: 'warning',
      icon: AlertCircle,
    }
  }
  if (d.status === 'ASSIGNED') {
    return {
      text: `已指派${d.technicianName || '维修人员'}，等待接单`,
      hint: mode.value === 'mine' ? '接单后将联系报修人或现场排查，请保持联系方式畅通' : '维修人员接单后将联系报修人，记录排查与处理进展',
      class: 'active',
      icon: UserCheck,
    }
  }
  if (d.status === 'IN_PROGRESS') {
    return {
      text: `${d.technicianName || '维修人员'}已接单，正在处理`,
      hint: d.processingComment || '维修人员已排查故障并记录进展',
      class: 'active',
      icon: Wrench,
    }
  }
  if (d.status === 'AWAITING_CONFIRMATION') {
    return {
      text: mode.value === 'mine' ? '维修结果已提交，等待你验收' : '维修结果已提交，等待报修人验收',
      hint: mode.value === 'mine' ? '请现场核验设施是否恢复正常，再确认修复或反馈问题' : '由报修人核验设施是否恢复正常，确认后完成工单',
      class: 'highlight',
      icon: Award,
    }
  }
  if (d.status === 'COMPLETED') {
    return {
      text: '本次报修已完成并结单',
      hint: `${d.confirmedAt ? time(d.confirmedAt) + ' 验收' : '办理记录已归档'}${d.evaluation ? ` · 服务评价 ${d.evaluation.stars} 星` : ''}`,
      class: 'success',
      icon: CheckCircle2,
    }
  }
  if (d.status === 'REJECTED') {
    return {
      text: '工单已拒绝受理',
      hint: d.processingComment || '非服务范围或资料不符，详情见处理说明',
      class: 'rejected',
      icon: AlertCircle,
    }
  }
  if (d.status === 'WITHDRAWN') {
    return {
      text: '报修人已撤回报修',
      hint: '该工单流转已终止',
      class: 'info',
      icon: AlertCircle,
    }
  }
  if (d.status === 'CANCELLED') {
    return {
      text: '工单已同意取消',
      hint: '维修任务已关闭',
      class: 'info',
      icon: CheckCircle2,
    }
  }
  return {
    text: repairStatuses[d.status] || d.status,
    hint: '',
    class: 'info',
    icon: Clock,
  }
})

function imageUrl(image: RepairImage) { return `/api${root.value}/${selected.value?.id}/attachments/${image.id}` }
async function ask(message: string) {
  confirmation.value = message
  return new Promise<boolean>(resolve => { resolveConfirmation = resolve; void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() }) })
}
function finish(accept: boolean) { dialog.value?.close(); const resolve = resolveConfirmation; resolveConfirmation = null; resolve?.(accept) }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { promptAlert('正在保存，请等待完成后再离开。', '操作提示', 'warning'); return false }
  return !dirty.value || await ask('还有未保存的资料、处理说明或待上传图片。离开将放弃这些输入，是否继续？')
}
function clearInputs() { editing.value = false; comment.value = ''; technicianId.value = ''; file.value = null; if (fileInput.value) fileInput.value.value = ''; clearPendingFaultFiles() }
async function readLocations(page = 1) {
  const request = ++locationSequence
  const params = new URLSearchParams({ page: String(page), pageSize: '100' }); if (campusId.value) params.set('campusId', campusId.value)
  const result = await api<typeof locations.value>('/repairs/locations?' + params)
  if (request === locationSequence) locations.value = result
}
async function locationPage(page: number) { try { await readLocations(page) } catch (cause) { error.value = errorMessage(cause) } }
async function changeCampus() { form.locationId = ''; await locationPage(1) }
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; clearInputs()
  if (!route.query.id || String(selected.value?.id) !== String(route.query.id)) {
    selected.value = null
    prefilledLocation.value = null
  }
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
    const queryId = typeof route.query.id === 'string' ? route.query.id : (Array.isArray(route.query.id) ? String(route.query.id[0] ?? '') : '')
    const effectiveId = queryId || (!route.query.new && list.value.items.length ? String(list.value.items[0].id) : '')
    if (effectiveId) {
      if (!queryId) {
        await navigate({ ...route.query as Record<string, string>, id: effectiveId, tab: 'details', new: undefined })
        return
      }
      const detail = await api<RepairDetail>(root.value + '/' + effectiveId)
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
async function choose(item: RepairItem) {
  listScrollPosition = window.scrollY
  const previousId = route.query.id
  await navigate({ ...route.query as Record<string, string>, id: String(item.id), tab: 'details', new: undefined })
  if (route.query.id !== String(item.id)) return
  mobileDetailOpen.value = true
  if (window.matchMedia('(max-width: 760px)').matches) {
    await nextTick()
    window.scrollTo({ top: 0 })
    if (previousId === route.query.id) document.querySelector<HTMLElement>('.repair-detail-title')?.focus({ preventScroll: true })
  }
}
async function newDraft() { await navigate({ new: '1' }); if (route.query.new === '1') mobileDetailOpen.value = true }
async function showList() {
  if (!await allowLeave()) return
  clearInputs()
  if (route.query.new) await navigate({ status: status.value || undefined, cancellationPending: cancellationFilter.value || undefined })
  mobileDetailOpen.value = false
  await nextTick()
  if (window.matchMedia('(max-width: 760px)').matches) window.scrollTo({ top: listScrollPosition })
}
async function filterQueue(value: string, cancellation = '') {
  await navigate({ status: value, cancellationPending: cancellation || undefined, page: '1' })
  if (String(route.query.status ?? '') === value && String(route.query.cancellationPending ?? '') === cancellation) mobileDetailOpen.value = false
}
async function handleCurrentTask() {
  if (currentTask.value?.edit) { await edit(); return }
  if (historyView.value) await navigate({ ...route.query as Record<string, string>, tab: 'details' })
  await nextTick()
  operationPanel.value?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' })
  operationPanel.value?.focus({ preventScroll: true })
}
async function search() { await navigate({ status: status.value, cancellationPending: cancellationFilter.value || undefined, page: '1' }) }
async function changeCancellationFilter() { if (cancellationFilter.value === 'true') status.value = ''; await search() }
async function page(number: number, history = false) { await navigate({ ...route.query as Record<string, string>, [history ? 'historyPage' : 'page']: String(number) }) }
async function edit() {
  if (!await allowLeave()) return
  clearPendingFaultFiles()
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
  let currentDetail: RepairDetail | null = null
  try {
    currentDetail = await api<RepairDetail>(endpoint, { method: selected.value ? 'PUT' : 'POST', body: JSON.stringify({ ...fields, requestKey: operationKey(endpoint, fields) }) })
    if (pendingFaultFiles.value.length > 0) {
      while (pendingFaultFiles.value.length > 0) {
        const item = pendingFaultFiles.value[0]
        const uploadEndpoint: string = `${root.value}/${currentDetail.id}/attachments`
        const fingerprint = { version: currentDetail.version, purpose: 'FAULT', name: item.file.name, size: item.file.size, modified: item.file.lastModified }
        const data = new FormData()
        data.set('file', item.file)
        data.set('version', String(currentDetail.version))
        data.set('purpose', 'FAULT')
        data.set('requestKey', operationKey(uploadEndpoint, fingerprint))
        currentDetail = await api<RepairDetail>(uploadEndpoint, { method: 'POST', body: data })
        removePendingFile(0)
      }
      await saved(currentDetail, '报修资料与现场图片已保存。核对地点、说明及图片后再提交报修。')
    } else {
      await saved(currentDetail, '资料已保存。核对地点、说明及图片后再提交报修。')
    }
  } catch (cause) {
    if (currentDetail) selected.value = currentDetail
    const msg = errorMessage(cause)
    promptAlert(msg, '保存失败', 'error')
  } finally {
    busy.value = false
  }
}
async function transition(action: string) {
  if (!selected.value || busy.value) return
  if (file.value) { promptAlert('还有待上传图片，请先上传或清除选择。', '操作提示', 'warning'); return }
  if (['assign', 'reassign'].includes(action) && !technicianId.value) { promptAlert('请选择当前负责范围匹配的维修人员。', '操作提示', 'warning'); return }
  const fallback: Record<string, string> = { submit: '本人核对资料后提交', accept: '维修人员确认接单', confirm: '本人确认问题已解决' }
  const note = comment.value.trim() || fallback[action] || ''
  if (action !== 'evaluation' && !note) {
    promptAlert('请填写处理说明后再操作。', '操作提示', 'warning', () => {
      commentTextareaRef.value?.focus?.()
      commentTextareaRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
    })
    return
  }
  let cancelMutation: ReturnType<typeof repairCancellationMutation>
  try { cancelMutation = repairCancellationMutation(selected.value.id, action, selected.value.cancellation?.id ?? null, selected.value.version, note) }
  catch (cause) { const msg = errorMessage(cause); promptAlert(msg, '操作提示', 'warning'); return }
  const selectedTechnician = technicians.value.find(t => t.id === Number(technicianId.value))
  const cancelPrompts: Record<string, string> = { 'request-cancellation': '申请取消后，维修和验收将暂停，等待后勤决定。确认申请？', 'withdraw-cancellation': '撤销取消申请后，继续当前维修或验收流程。确认撤销？', 'approve-cancellation': '同意后关闭工单，结束当前维修任务并保留历史。确认同意取消？', 'reject-cancellation': '拒绝后继续原维修或验收流程，请核对处理说明。确认拒绝取消？' }
  const prompt = cancelPrompts[action] ?? (action === 'submit' ? '提交后由后勤分诊，地点和类别固定。确认提交这份报修吗？' : action === 'confirm' ? '确认这次维修已经解决问题吗？确认后工单完成。' : `${repairActions[action]}${selectedTechnician && ['assign', 'reassign'].includes(action) ? '：' + selectedTechnician.name : ''}，确认继续吗？`)
  if (!await ask(prompt)) return
  busy.value = true; error.value = ''; success.value = ''
  const endpoint = cancelMutation?.endpoint ?? `${root.value}/${selected.value.id}/${action}`
  const body = cancelMutation?.body ?? { version: selected.value.version, comment: note, technicianId: ['assign', 'reassign'].includes(action) ? Number(technicianId.value) : null, resultRound: ['confirm', 'reopen', 'evaluation'].includes(action) ? selected.value.completionRound : null, stars: action === 'evaluation' ? Number(stars.value) : null, priority: action === 'priority' ? priority.value : null }
  try { const result = await api<RepairDetail>(endpoint, { method: 'POST', body: JSON.stringify({ ...body, requestKey: operationKey(endpoint, body) }) }); await saved(result, repairActions[action] + '已记录。') }
  catch (cause) { const msg = errorMessage(cause); promptAlert(msg, '操作未完成', 'error') }
  finally { busy.value = false }
}
function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file.value && (!['image/jpeg', 'image/png'].includes(file.value.type) || file.value.size < 1 || file.value.size > 2 * 1024 * 1024)) { promptAlert('请选择 2 MB 以内的 JPEG 或 PNG 图片。', '图片格式提示', 'warning'); clearFile() }
}
function clearFile() { file.value = null; if (fileInput.value) fileInput.value.value = '' }
async function upload() {
  if (!selected.value || !file.value || busy.value) return
  busy.value = true; error.value = ''
  const endpoint = `${root.value}/${selected.value.id}/attachments`, fingerprint = { version: selected.value.version, purpose: purpose.value, name: file.value.name, size: file.value.size, modified: file.value.lastModified }
  const data = new FormData(); data.set('file', file.value); data.set('version', String(selected.value.version)); data.set('purpose', purpose.value); data.set('requestKey', operationKey(endpoint, fingerprint))
  try { await saved(await api<RepairDetail>(endpoint, { method: 'POST', body: data }), '图片已保存，历史版本保留。') }
  catch (cause) { const msg = errorMessage(cause); promptAlert(msg, '图片保存失败', 'error') }
  finally { busy.value = false }
}
async function removeImage(image: RepairImage) {
  if (!selected.value || !await ask('从当前资料移除这张图片？已保存的历史记录仍保留。')) return
  busy.value = true; error.value = ''
  const endpoint = `${root.value}/${selected.value.id}/attachments/${image.id}/remove`, body = { version: selected.value.version, comment: '移除当前图片' }
  try {
    const updated = await api<RepairDetail>(endpoint, { method: 'POST', body: JSON.stringify({ ...body, requestKey: operationKey(endpoint, body) }) })
    if (editing.value) {
      selected.value = updated
      success.value = '图片已从当前资料移除。'
    } else {
      await saved(updated, '图片已从当前资料移除。')
    }
  } catch (cause) { const msg = errorMessage(cause); promptAlert(msg, '移除图片失败', 'error') }
  finally { busy.value = false }
}
function removable(image: RepairImage) { return image.purpose === 'FAULT' && can('remove-fault') || image.purpose === 'RESULT' && image.resultRound === (selected.value?.completionRound ?? 0) + 1 && can('remove-result') }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
watch(() => route.fullPath, () => { void load() })
onBeforeUnmount(() => { sequence++; locationSequence++; clearPendingFaultFiles(); finish(false); closeAlert(); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="repair-workflow" :class="{ 'is-detail-open': mobileDetailOpen, 'is-editing': editing }">
    <header class="repair-page-heading">
      <div class="repair-page-title"><span class="repair-page-symbol"><Wrench :size="22" /></span><div><span class="eyebrow">校园设施服务</span><h1>{{ title }}</h1><p>{{ intro }}</p></div></div>
      <div class="repair-inline-actions"><RouterLink v-if="mode === 'dispatch'" class="button compact" to="/logistics/repairs/statistics">报修统计</RouterLink><button class="button compact repair-refresh" :disabled="busy || loading" @click="refresh"><RefreshCw :size="15" :class="{ 'is-refreshing': loading }" />刷新</button><button v-if="mode === 'mine' && !editing" class="button primary compact" :disabled="busy" @click="newDraft"><Plus :size="15" />新建报修</button></div>
    </header>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success" role="status">{{ success }}</p>
    <div v-if="queueItems.length && !editing" class="repair-queue-note"><ClipboardCheck :size="16" /><span>待办提醒</span><button v-for="item in queueItems" :key="item.label" :disabled="busy || loading" @click="filterQueue(item.status, item.cancellation)">{{ item.label }} <strong>{{ item.count }}</strong><ArrowRight :size="13" /></button></div>
    <div v-if="!editing" class="repair-queue-toolbar">
      <nav class="repair-queue-tabs" aria-label="工单快捷筛选"><button v-for="filter in quickFilters" :key="filter.value" :aria-current="status === filter.value && !cancellationFilter ? 'true' : undefined" :class="{ selected: status === filter.value && !cancellationFilter }" :disabled="busy || loading" @click="filterQueue(filter.value)">{{ filter.label }}</button></nav>
      <button class="repair-filter-toggle" :class="{ active: filtersOpen || cancellationFilter || status && !quickFilters.some(filter => filter.value === status) }" :aria-expanded="filtersOpen" aria-controls="repair-advanced-filters" :disabled="busy || loading" @click="filtersOpen = !filtersOpen"><SlidersHorizontal :size="15" />更多筛选<span v-if="cancellationFilter || status && !quickFilters.some(filter => filter.value === status)" class="repair-filter-dot" aria-label="有筛选条件" /></button>
    </div>
    <div v-if="filtersOpen && !editing" id="repair-advanced-filters" class="repair-list-filter"><label>工单状态<BaseSelect v-model="status" :options="statusOptions" :disabled="busy || loading" compact @change="search" /></label><label>取消申请<BaseSelect v-model="cancellationFilter" :options="cancellationOptions" :disabled="busy || loading" compact @change="changeCancellationFilter" /></label><button class="text-button" :disabled="busy || loading" @click="filterQueue('')">清除筛选</button></div>
    <div class="repair-workflow-grid">
      <section class="portal-panel repair-request-list" aria-label="报修记录列表" :aria-busy="loading">
        <div class="repair-list-heading"><h2>{{ mode === 'work' ? '任务列表' : '报修记录' }}</h2><span>{{ loading ? '更新中…' : `共 ${list.total} 条` }}</span></div>
        <p v-if="loading && !list.items.length" role="status" class="loading-state">正在读取报修记录…</p>
        <div v-else-if="!loading && !list.items.length" class="repair-list-empty"><ClipboardCheck :size="26" /><strong>暂无相关工单</strong><p>{{ mode === 'mine' ? '试试其他筛选，或新建一份报修。' : '当前筛选下没有你负责的工单。' }}</p><button class="text-button" :disabled="busy" @click="filterQueue('')">查看全部工单</button></div>
        <div v-else class="repair-cards-scroll" :class="{ 'is-loading': loading }">
          <button v-for="item in list.items" :key="item.id" class="repair-request-card" :class="{ selected: selected?.id === item.id }" :aria-current="selected?.id === item.id ? 'true' : undefined" :disabled="busy || loading" @click="choose(item)">
            <span class="repair-card-top"><strong>{{ item.title || '待补充故障标题' }}</strong><span class="repair-status" :class="`status-${item.status.toLowerCase()}`">{{ repairStatuses[item.status] }}</span></span>
            <span class="repair-card-location"><MapPin :size="13" /><span>{{ item.campusName }} · {{ item.locationName || '待选择地点' }}</span></span>
            <span v-if="item.cancellationPending" class="repair-card-attention">取消申请待处理 · 办理已暂停</span><span v-else-if="item.confirmationOverdue" class="repair-card-attention">等待验收，请及时核对维修结果</span>
            <span class="repair-card-person"><User v-if="mode !== 'mine'" :size="12" /><span v-if="mode !== 'mine'">{{ item.reporterName }}{{ mode === 'dispatch' ? ' · ' : '' }}</span><Wrench v-if="mode === 'mine'" :size="12" /><span v-if="mode !== 'work'">{{ item.technicianName || '等待派工' }}</span></span>
            <span class="repair-card-footer"><span>#{{ item.id }} · {{ repairCategories[item.category] || '待分类' }}</span><time :datetime="item.updatedAt">{{ stepTime(item.updatedAt) }} 更新</time></span>
          </button>
        </div>
        <div v-if="list.total > list.pageSize" class="pagination"><span>第 {{ list.page }} 页</span><button class="button compact" :disabled="busy || loading || list.page <= 1" @click="page(list.page - 1)">上一页</button><button class="button compact" :disabled="busy || loading || list.page * list.pageSize >= list.total" @click="page(list.page + 1)">下一页</button></div>
      </section>
      <section class="portal-panel repair-request-detail" aria-label="报修资料与处理" :aria-busy="loading">
        <button v-if="!editing" class="text-button repair-mobile-back" :disabled="busy" @click="showList"><ArrowLeft :size="16" />工单列表</button>
        <form v-if="editing" class="repair-workflow-form" @submit.prevent="save">
          <div class="section-heading"><h2>{{ selected ? '编辑报修资料' : '新建校园报修' }}</h2><button type="button" class="text-button" :disabled="busy" @click="showList"><ArrowLeft :size="14" />工单列表</button></div>
          <p class="muted">先保存资料，核对后再提交。提交后地点与类别固定，联系方式仅对相关经办人员可见。</p>
          <fieldset :disabled="busy">
            <div class="repair-form-group-heading"><span>01</span><h3>报修地点</h3></div>
            <div class="form-grid"><label>所在校区<BaseSelect v-model="campusId" :options="campusOptions" :disabled="!!selected?.submittedAt" @change="changeCampus" /></label><label>报修地点<BaseSelect v-model="form.locationId" :options="locationOptions" :disabled="!!selected?.submittedAt" /></label></div>
            <p v-if="selectedLocationHint" class="data-note">地点指引：{{ selectedLocationHint }}</p><p v-else-if="!loading && !locations.total" class="data-note">当前校区还没有开放地点，可先保存故障描述，联系管理员配置后再提交。</p>
            <label>具体位置补充<input v-model="form.locationDetail" maxlength="300" :placeholder="selectedLocationHint ? `例如：${selectedLocationHint}` : '例如：三楼 302 教室，进门右侧第二盏灯'" /></label>
            <div class="repair-form-group-heading"><span>02</span><h3>故障描述与照片</h3></div>
            <div class="form-grid"><label>故障类别<BaseSelect v-model="form.category" :options="categoryOptions" :disabled="!!selected?.submittedAt" /></label><label>故障标题<input v-model="form.title" maxlength="100" placeholder="例如：教室右侧照明灯不亮" /></label></div>
            <label><span class="label-heading-row"><span>故障现象与影响</span><small class="muted">最多 2000 字</small></span><textarea v-model="form.description" maxlength="2000" rows="4" placeholder="描述故障现象、出现时间及影响，方便维修人员提前准备" /></label>
            <div class="repair-form-images-section">
              <div class="label-heading-row">
                <span>故障现场图片</span>
                <small class="muted">{{ currentFaultImageCount }} / 6 张（单张 2 MB 以内 JPG/PNG）</small>
              </div>
              <div class="repair-image-thumbnails-grid">
                <div v-for="image in existingFaultImages" :key="image.id" class="repair-thumb-card">
                  <img :src="imageUrl(image)" :alt="image.name" @click="previewImage(imageUrl(image))" />
                  <button type="button" class="repair-thumb-remove" title="从工单移除此图片" :disabled="busy" @click="removeImage(image)">
                    <X :size="12" />
                  </button>
                  <span class="repair-thumb-badge">已保存</span>
                </div>
                <div v-for="(item, idx) in pendingFaultFiles" :key="idx" class="repair-thumb-card">
                  <img :src="item.preview" :alt="item.file.name" @click="previewImage(item.preview)" />
                  <button type="button" class="repair-thumb-remove" title="取消添加此图片" :disabled="busy" @click="removePendingFile(idx)">
                    <X :size="12" />
                  </button>
                  <span class="repair-thumb-badge pending">待上传</span>
                </div>
                <label v-if="currentFaultImageCount < 6" class="repair-upload-picker" :class="{ disabled: busy }">
                  <Camera :size="24" />
                  <span>选择图片</span>
                  <small>支持多选</small>
                  <input
                    type="file"
                    multiple
                    accept="image/jpeg,image/png"
                    :disabled="busy"
                    @change="onSelectFormFiles"
                  />
                </label>
              </div>
              <p class="repair-image-tip">提示：添加现场照片有助于维修人员快速判断故障类型与备件需求。所选图片将在保存报修资料时一并上传。</p>
            </div>
            <div class="repair-form-group-heading"><span>03</span><h3>联系安排</h3></div>
            <div class="form-grid"><label>联系手机或邮箱<input v-model="form.contact" maxlength="80" autocomplete="off" placeholder="填写可联系到你的手机或邮箱" /></label><label>方便联系的时间<ContactTimeSelect v-model="form.contactWindow" :disabled="busy" /></label></div>
          </fieldset>
          <div class="repair-form-footer"><span>保存后可继续补充，核对无误再提交。</span><button class="button primary compact" :disabled="busy" type="submit">{{ busy ? '正在保存…' : selected?.submittedAt ? '保存补充资料' : '保存报修草稿' }}</button></div>
        </form>
        <template v-else-if="selected">
          <p v-if="!selected.locationEligible" class="data-note" role="status">{{ selected.locationEligibilityMessage }}</p>
          <div class="repair-detail-heading"><div><div class="repair-detail-identity"><span>报修 #{{ selected.id }}</span><span>{{ repairCategories[selected.category] || '待分类' }}</span></div><h2 class="repair-detail-title" tabindex="-1">{{ selected.title || '待填写故障标题' }}</h2></div><span class="repair-status" :class="`status-${selected.status.toLowerCase()}`">{{ repairStatuses[selected.status] }}</span></div>
          <p class="repair-detail-location"><MapPin :size="15" /><span>{{ selected.campusName }} · {{ selected.locationName || '待填写地点' }}<strong v-if="selected.locationDetail">{{ selected.locationDetail }}</strong></span></p>
          <section class="repair-stepper-container" :class="{ 'is-closed': isClosed, 'is-paused': selected.cancellation?.status === 'PENDING' }" aria-label="当前办理进展">
            <div class="repair-stepper-header">
              <div class="repair-current-message">
                <component :is="stepperOverview.icon" :size="18" :class="`repair-current-icon ${stepperOverview.class}`" />
                <div>
                  <strong class="stepper-overview-tag" :class="stepperOverview.class">{{ stepperOverview.text }}</strong>
                  <p v-if="stepperOverview.hint" class="stepper-overview-hint">{{ stepperOverview.hint }}</p>
                  <time v-if="!isClosed" class="repair-current-time">最近更新 {{ time(selected.updatedAt) }}</time>
                </div>
              </div>
              <button v-if="currentTask" class="button compact" :disabled="busy || loading" @click="handleCurrentTask">{{ currentTask.label }}<ArrowRight :size="14" /></button>
            </div>
            <ol v-if="!isClosed && selected.cancellation?.status !== 'PENDING'" class="repair-stepper">
              <li
                v-for="(step, index) in workflowSteps"
                :key="step.id"
                class="repair-step-item"
                :class="`is-${step.state}`"
              >
                <div class="repair-step-marker">
                  <div class="repair-step-circle">
                    <Check v-if="step.state === 'completed'" :size="12" />
                    <AlertTriangle v-else-if="step.state === 'warning'" :size="14" />
                    <AlertCircle v-else-if="step.state === 'rejected'" :size="14" />
                    <span v-else class="step-num">{{ index + 1 }}</span>
                  </div>
                  <div v-if="index < workflowSteps.length - 1" class="repair-step-line">
                    <span class="repair-step-line-fill" :class="{ filled: step.state === 'completed' }"></span>
                  </div>
                </div>
                <div class="repair-step-content">
                  <strong class="repair-step-title">{{ step.title }}</strong>
                  <span class="repair-step-subtitle" :title="step.subtitle">{{ step.subtitle }}</span>
                  <time v-if="step.time" class="repair-step-time">{{ step.time }}</time>
                </div>
              </li>
            </ol>
          </section>
          <nav ref="repairNavRef" class="repair-detail-tabs" aria-label="报修详情栏目">
            <button class="text-button" :class="{ selected: !historyView }" :aria-current="!historyView ? 'true' : undefined" :disabled="busy || loading" @click="navigate({ ...route.query as Record<string, string>, tab: 'details' })"><FileText :size="15" />报修详情</button>
            <button class="text-button" :class="{ selected: historyView }" :aria-current="historyView ? 'true' : undefined" :disabled="busy || loading" @click="navigate({ ...route.query as Record<string, string>, tab: 'history', historyPage: '1' })"><History :size="15" />办理记录</button>
            <div class="learning-tabs-indicator" :style="repairIndicatorStyle" aria-hidden="true"></div>
          </nav>
          <template v-if="historyView">
            <article v-for="record in records.items" :key="record.id" class="repair-history-card">
              <div class="history-card-top">
                <strong>{{ repairActions[record.action] || record.action }} · {{ repairStatuses[record.status] }}</strong>
                <span class="history-role-badge" :class="historyActorRole(record).class">
                  {{ historyActorRole(record).label }} · {{ record.actorName }}
                </span>
              </div>
              <small>{{ time(record.createdAt) }}</small>
              <p v-if="record.comment" class="history-comment-text">{{ record.comment }}</p>
              <details v-if="repairSnapshot(record.snapshot)">
                <summary>查看当时保存的资料与图片</summary>
                <p>{{ repairSnapshot(record.snapshot)?.title }}</p>
                <p class="preserve-lines">{{ repairSnapshot(record.snapshot)?.description }}</p>
                <p>具体位置：{{ repairSnapshot(record.snapshot)?.locationDetail || '未填写' }}</p>
                <p>联系方式：{{ repairSnapshot(record.snapshot)?.contact || '未填写' }}</p>
                <DownloadLink v-for="image in repairSnapshot(record.snapshot)?.images" :key="image.id" class="text-button" :href="imageUrl(image)">下载当时图片：{{ image.name }}</DownloadLink>
              </details>
            </article>
            <div class="pagination">
              <span>共 {{ records.total }} 条记录</span>
              <button class="button compact" :disabled="records.page <= 1 || busy" @click="page(records.page - 1, true)">上一页记录</button>
              <button class="button compact" :disabled="records.page * records.pageSize >= records.total || busy" @click="page(records.page + 1, true)">下一页记录</button>
            </div>
          </template>
          <template v-else>
            <section v-if="selected.cancellation" class="repair-cancellation" :class="{ pending: selected.cancellation.status === 'PENDING' }">
              <h3>{{ cancellationStatuses[selected.cancellation.status] }}</h3>
              <small>{{ selected.cancellation.requesterName }} · {{ time(selected.cancellation.createdAt) }}</small>
              <p>申请原因：{{ selected.cancellation.reason }}</p>
              <p v-if="selected.cancellation.status === 'PENDING'">取消等待后勤处理，维修、资料更新和本人验收已暂停。报修人可撤销此申请。</p>
              <template v-else>
                <p>{{ selected.cancellation.decisionActorName }} · {{ time(selected.cancellation.resolvedAt) }}</p>
                <p>处理说明：{{ selected.cancellation.decisionComment }}</p>
              </template>
            </section>
            <p v-if="selected.confirmationOverdue" class="repair-overdue-note">本次维修结果已等待验收超过演示提醒时间，请核对结果后确认或反馈问题。提醒不会自动完成工单。</p>

            <section class="repair-role-section applicant-role-card">
              <div class="role-section-header">
                <h3>故障信息</h3><span class="repair-section-caption">提交于 {{ time(selected.submittedAt || selected.createdAt) }}</span>
              </div>
              <div class="role-section-body">
                <div class="repair-bubble-box fault-bubble">
                  <p class="preserve-lines">{{ selected.description || '申报人暂未补充故障描述。' }}</p>
                </div>

                <div class="repair-role-images-block">
                  <div class="images-block-header">
                    <div class="images-block-title">
                      <Camera :size="14" />
                      <strong>现场照片 <span>{{ faultImages.length }}</span></strong>
                    </div>
                    <span v-if="faultImages.length" class="muted-note">点击查看原图</span>
                  </div>
                  <div v-if="faultImages.length" class="repair-gallery-grid">
                    <div v-for="image in faultImages" :key="image.id" class="repair-gallery-card">
                      <button class="repair-gallery-media" :aria-label="`查看现场照片：${image.name}`" @click="previewImage(imageUrl(image))">
                        <img :src="imageUrl(image)" :alt="image.name" loading="lazy" />
                        <div class="repair-gallery-hover-overlay">
                          <ZoomIn :size="20" />
                          <span>查看大图</span>
                        </div>
                      </button>
                      <div class="repair-gallery-info">
                        <span class="repair-gallery-name" :title="image.name">{{ image.name }}</span>
                        <div class="repair-gallery-actions">
                          <DownloadLink class="text-button" :href="imageUrl(image)">下载原图</DownloadLink>
                          <button v-if="removable(image)" class="text-button danger" :disabled="busy" @click="removeImage(image)">移除</button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <p v-else class="empty-media-note">申报人未上传现场照片。</p>
                </div>
                <details class="repair-metadata" :open="mode !== 'mine'">
                  <summary><User :size="14" />联系信息与报修资料</summary>
                  <dl class="repair-facts-grid">
                    <div class="fact-item"><dt>报修人</dt><dd>{{ selected.reporterName }}</dd></div>
                    <div class="fact-item"><dt>联系方式</dt><dd>{{ selected.contact || '未填写' }}</dd></div>
                    <div class="fact-item"><dt>方便联系的时间</dt><dd>{{ selected.contactWindow || '未填写' }}</dd></div>
                    <div class="fact-item"><dt>具体位置</dt><dd>{{ selected.locationDetail || '未补充具体位置' }}</dd></div>
                  </dl>
                </details>
              </div>
            </section>

            <section class="repair-role-section technician-role-card">
              <div class="role-section-header">
                <h3>维修进展与结果</h3><span class="repair-section-caption">{{ selected.technicianName ? `维修人员：${selected.technicianName}` : '尚未指派维修人员' }}</span>
              </div>
              <div class="role-section-body">
                <dl v-if="mode !== 'mine'" class="repair-facts-grid maintenance-facts">
                  <div class="fact-item">
                    <dt>当前维修人员</dt>
                    <dd>
                      <strong v-if="selected.technicianName" class="technician-highlight">
                        <UserCheck :size="14" /> {{ selected.technicianName }}
                      </strong>
                      <span v-else class="muted">尚未派工，等待后勤分诊指派</span>
                    </dd>
                  </div>
                  <div class="fact-item">
                    <dt>工单优先级</dt>
                    <dd>
                      <span class="priority-pill" :class="selected.priority.toLowerCase()">
                        {{ { LOW: '低优先级', NORMAL: '普通优先级', HIGH: '高优先级 (紧急)' }[selected.priority] }}
                      </span>
                    </dd>
                  </div>
                  <div class="fact-item">
                    <dt>维修完成轮次</dt>
                    <dd>{{ selected.completionRound > 0 ? `第 ${selected.completionRound} 轮维修` : '尚未完工' }}</dd>
                  </div>
                  <div class="fact-item">
                    <dt>派工指派记录</dt>
                    <dd>{{ selected.assignments?.length ? `共派单/改派 ${selected.assignments.length} 次` : '暂无派单记录' }}</dd>
                  </div>
                </dl>

                <div v-if="selected.processingComment" class="repair-bubble-box processing-bubble">
                  <div class="bubble-title">
                    <Clock :size="14" />
                    <strong>最新处理说明</strong>
                  </div>
                  <p class="preserve-lines">{{ selected.processingComment }}</p>
                </div>

                <details v-if="selected.assignments?.length" class="repair-assignments-subblock repair-metadata">
                  <summary><ClipboardCheck :size="14" />派工记录 <span>{{ selected.assignments.length }} 次</span></summary>
                  <div class="repair-assignment-list">
                    <article v-for="(asn, idx) in selected.assignments" :key="asn.id" class="repair-assignment-card" :class="{ current: !asn.endedAt }">
                      <div class="assignment-header">
                        <span class="assignment-index">第 {{ idx + 1 }} 次派工</span>
                        <span class="assignment-badge" :class="asn.endedAt ? 'ended' : 'active'">
                          {{ asn.endedAt ? '已结束' : '当前指派' }}
                        </span>
                      </div>
                      <div class="assignment-body">
                        <div class="assignment-row">
                          <span><strong>维修人员：</strong>{{ asn.technicianName }}</span>
                          <span style="margin-left: 12px"><strong>调度人员：</strong>{{ asn.actorName }}</span>
                        </div>
                        <div class="assignment-row">
                          <span><strong>派单时间：</strong><small>{{ time(asn.assignedAt) }}</small></span>
                          <span v-if="asn.endedAt" style="margin-left: 12px"><strong>结束时间：</strong><small>{{ time(asn.endedAt) }}</small></span>
                        </div>
                        <p v-if="asn.comment" class="assignment-comment">
                          <strong>派工／改派说明：</strong>{{ asn.comment }}
                        </p>
                      </div>
                    </article>
                  </div>
                </details>

                <div v-if="selected.completions?.length" class="repair-completions-subblock">
                  <article v-for="completion in selected.completions" :key="completion.resultRound" class="repair-completion-item">
                    <div class="completion-header">
                      <strong>第 {{ completion.resultRound }} 次维修结果</strong>
                      <small>{{ completion.technicianName }} · {{ time(completion.createdAt) }}</small>
                    </div>
                    <p class="preserve-lines">{{ completion.comment }}</p>
                    <div v-if="completion.images?.length" class="completion-images-links">
                      <DownloadLink v-for="image in completion.images" :key="image.id" class="text-button" :href="imageUrl(image)">
                        {{ image.name }} · 下载完工图
                      </DownloadLink>
                    </div>
                  </article>
                </div>

                <div class="repair-role-images-block">
                  <div class="images-block-header">
                    <div class="images-block-title">
                      <Camera :size="14" />
                      <strong>维修照片 <span>{{ maintenanceImages.length }}</span></strong>
                    </div>
                    <span v-if="maintenanceImages.length" class="muted-note">维修过程与完工凭证</span>
                  </div>
                  <div v-if="maintenanceImages.length" class="repair-gallery-grid">
                    <div v-for="image in maintenanceImages" :key="image.id" class="repair-gallery-card">
                      <button class="repair-gallery-media" :aria-label="`查看维修照片：${image.name}`" @click="previewImage(imageUrl(image))">
                        <span class="repair-gallery-tag" :class="image.purpose.toLowerCase()">
                          {{ image.purpose === 'PROGRESS' ? '维修进展' : `第 ${image.resultRound} 轮完工凭证` }}
                        </span>
                        <img :src="imageUrl(image)" :alt="image.name" loading="lazy" />
                        <div class="repair-gallery-hover-overlay">
                          <ZoomIn :size="20" />
                          <span>查看大图</span>
                        </div>
                      </button>
                      <div class="repair-gallery-info">
                        <span class="repair-gallery-name" :title="image.name">{{ image.name }}</span>
                        <div class="repair-gallery-actions">
                          <DownloadLink class="text-button" :href="imageUrl(image)">下载原图</DownloadLink>
                          <button v-if="removable(image)" class="text-button danger" :disabled="busy" @click="removeImage(image)">移除</button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <p v-else class="empty-media-note">
                    {{ selected.status === 'COMPLETED' || selected.status === 'AWAITING_CONFIRMATION' ? '维修人员未上传完工照片。' : selected.status === 'IN_PROGRESS' ? '暂无维修照片，上传后将在这里展示。' : '暂无维修照片。' }}
                  </p>
                </div>
              </div>
            </section>

            <!-- 补充图片 -->
            <section v-if="can('upload-fault') || can('upload-result')" class="repair-upload">
              <h3>{{ mode === 'mine' ? '补充现场故障照片' : '上传维修进展／完工照片' }}</h3>
              <label v-if="mode === 'work'">图片用途<BaseSelect v-model="purpose" :options="purposeOptions" :disabled="busy" /></label>
              <label>选择 JPEG 或 PNG 图片<input ref="fileInput" type="file" accept="image/jpeg,image/png" :disabled="busy" @change="selectFile" /></label>
              <small>单张最多 2 MB；故障最多 6 张，进展与本轮结果各最多 3 张。刷新后待上传图片须重新选择。</small>
              <div class="repair-inline-actions">
                <button class="button compact" :disabled="busy || !file" @click="upload">上传并保存图片</button>
                <button v-if="file" class="text-button" :disabled="busy" @click="clearFile">清除选择</button>
              </div>
            </section>

            <!-- 板块 3：办结核验与服务评价（由报修人验收） -->
            <section v-if="selected.evaluation || selected.status === 'AWAITING_CONFIRMATION' || selected.confirmedAt" class="repair-role-section evaluation-role-card">
              <div class="role-section-header">
                <div class="role-section-title">
                  <div class="role-avatar-badge evaluation">
                    <CheckCircle2 :size="16" />
                  </div>
                  <div>
                    <h3>验收与服务评价</h3>
                  </div>
                </div>
              </div>
              <div class="role-section-body">
                <div v-if="selected.status === 'AWAITING_CONFIRMATION'" class="awaiting-confirmation-alert">
                  <Award :size="20" />
                  <div>
                    <strong>请核对维修结果</strong>
                    <p>{{ mode === 'mine' ? '现场确认设施恢复正常后即可验收；若仍有问题，请填写说明并反馈。' : '等待报修人核验设施是否恢复正常。' }}</p>
                  </div>
                </div>
                <div v-if="selected.evaluation" class="evaluation-display-box">
                  <div class="evaluation-stars-row">
                    <span class="stars-label">服务满意度评分：</span>
                    <span class="stars-visual">
                      <span v-for="s in 5" :key="s" class="star" :class="{ filled: s <= selected.evaluation.stars }">★</span>
                    </span>
                    <strong class="stars-number">{{ selected.evaluation.stars }} 星</strong>
                    <small class="evaluation-time">{{ time(selected.evaluation.createdAt) }}</small>
                  </div>
                  <p v-if="selected.evaluation.comment" class="evaluation-comment">{{ selected.evaluation.comment }}</p>
                  <p v-else class="muted-note">（报修人未填写文字评价）</p>
                </div>
                <div class="repair-timestamps">
                  <span>报修首次提交：{{ time(selected.submittedAt) }}</span>
                  <span v-if="selected.confirmedAt">本人确认办结：{{ time(selected.confirmedAt) }}</span>
                </div>
              </div>
            </section>

            <!-- 操作面板 -->
            <section v-if="operationActions.length || can('edit')" ref="operationPanel" class="repair-operation-panel" tabindex="-1" aria-labelledby="repair-operation-title">
              <div class="repair-operation-heading"><Wrench :size="16" /><h3 id="repair-operation-title">{{ can('confirm') ? '维修验收与反馈' : can('evaluation') ? '评价本次维修' : mode === 'mine' ? '办理此工单' : '处理此工单' }}</h3></div>
              <button v-if="can('edit')" class="button compact" :disabled="busy" @click="edit">修改报修资料</button>
              <label v-if="can('assign') || can('reassign')">维修人员<BaseSelect v-model="technicianId" :options="technicianOptions" :disabled="busy" /></label>
              <p v-if="(can('assign') || can('reassign')) && !technicians.length" class="data-note">尚无当前范围匹配的有效维修人员，请联系管理员配置。</p>
              <label v-if="can('priority')">处理优先级<BaseSelect v-model="priority" :options="priorityOptions" :disabled="busy" /></label>
              <label v-if="can('evaluation')">服务评分<BaseSelect v-model="stars" :options="starOptions" :disabled="busy" /></label>
              <label>
                <div class="label-heading-row">
                  <span>处理说明{{ can('evaluation') ? '／评价（可选）' : '' }}</span>
                  <small class="muted">最多 {{ can('evaluation') ? 500 : 2000 }} 字</small>
                </div>
                <textarea ref="commentTextareaRef" v-model="comment" :maxlength="can('evaluation') ? 500 : 2000" rows="3" :disabled="busy" :placeholder="can('evaluation') ? '说说本次维修体验（选填）' : can('confirm') ? '若问题仍未解决，请说明具体情况；确认修复可直接办理' : mode === 'mine' ? '撤回或取消报修时，请说明原因' : '请填写派工、维修进展、处理结果或退回原因'" />
              </label>
              <div class="repair-inline-actions">
                <button v-for="action in operationActions" :key="action" class="button compact" :class="{ primary: action === primaryAction }" :disabled="busy || (['assign', 'reassign'].includes(action) && !technicians.length)" @click="transition(action)">{{ repairActions[action] }}</button>
              </div>
            </section>
          </template>
        </template>
        <div v-else-if="loading" class="repair-empty-detail" role="status"><Clock :size="28" /><p>正在读取工单详情…</p></div>
        <div v-else class="repair-empty-detail"><Wrench :size="32" /><h2>{{ mode === 'mine' ? '每一次报修，都有可追踪的进度' : '选择一条工单查看资料' }}</h2><p>{{ mode === 'mine' ? '从左侧查看已有记录，或新建报修描述故障。' : '处理操作根据当前状态、负责范围与分配人员显示。' }}</p></div>
      </section>
    </div>
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
    <dialog ref="alertDialog" class="confirmation-dialog alert-modal-dialog" aria-labelledby="repair-alert-title" @cancel.prevent="closeAlert">
      <div class="dialog-header">
        <div class="dialog-icon-badge" :class="alertState.type">
          <AlertTriangle v-if="alertState.type === 'warning'" :size="20" />
          <AlertCircle v-else :size="20" />
        </div>
        <h2 id="repair-alert-title">{{ alertState.title }}</h2>
      </div>
      <p>{{ alertState.message }}</p>
      <div class="dialog-actions">
        <button ref="alertOkButton" type="button" class="button primary compact dialog-btn-confirm" @click="closeAlert">我知道了</button>
      </div>
    </dialog>
    <dialog ref="imagePreviewDialog" class="repair-image-modal" @click.self="closePreview" @cancel.prevent="closePreview">
      <div v-if="previewModalUrl" class="repair-image-modal-content">
        <button type="button" class="repair-image-modal-close" title="关闭预览" @click="closePreview">
          <X :size="20" />
        </button>
        <img :src="previewModalUrl" class="repair-image-modal-full" alt="图片放大预览" />
      </div>
    </dialog>
  </div>
</template>

<style src="../styles/repair-workspace.css"></style>
