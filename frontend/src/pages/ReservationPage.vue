<script setup lang="ts">
import DownloadLink from '../components/DownloadLink.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertTriangle,
  Building2,
  CalendarCheck,
  CalendarDays,
  CheckCircle2,
  ClipboardCheck,
  Clock,
  Download,
  Edit3,
  MapPin,
  Plus,
  Printer,
  RefreshCw,
  Search,
  Settings,
  ShieldAlert,
  ShieldCheck,
  ToggleLeft,
  ToggleRight,
  Undo2,
  UserCheck,
  Users,
  X,
  XCircle,
} from '@lucide/vue'
import { currentUser } from '../stores/auth'
import { errorMessage } from '../lib/api'
import { useEditProtection } from '../composables/useEditProtection'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import AffairTimeline from '../components/AffairTimeline.vue'
import BaseSelect from '../components/BaseSelect.vue'
import {
  type ConflictResult,
  type ReservationDetail,
  type ReservationItem,
  type TimeSlot,
  type VenueSummary,
  type VenueManagementItem,
  type VenueManagerItem,
  type VenueUpdateCommand,
  activityTypes,
  addVenueManager,
  cancelReservation,
  checkConflict,
  getDetail,
  getReviewerCandidates,
  getVenueSchedule,
  listMine,
  listReview,
  listVenues,
  listVenuesForManagement,
  removeVenueManager,
  reservationStatuses,
  reservationActions,
  reservationPassUrl,
  reviewReservation,
  recordReservationUsage,
  saveDraft,
  submitReservation,
  updateVenue,
  withdrawReservation,
} from '../reservations'

const route = useRoute()
const router = useRouter()

// Review mode determined by URL path (/student-affairs vs /portal)
const reviewMode = computed(() => route.path.startsWith('/student-affairs'))
const canApply = computed(() => currentUser.value?.permissions.includes('reservation:apply'))
const canReview = computed(() => currentUser.value?.permissions.includes('reservation:review'))
const canManage = computed(() => currentUser.value?.permissions.includes('reservation:manage'))

// Active Tab: 'venues' (browse), 'mine' (student applications), 'review' (staff approvals), 'manage' (venue management)
const activeTab = ref<'venues' | 'mine' | 'review' | 'manage'>(
  (route.query.tab === 'manage' && canManage.value) ? 'manage' : reviewMode.value ? 'review' : 'venues'
)
const reservationNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: reservationIndicatorStyle } = useSlidingIndicator(
  reservationNavRef,
  () => activeTab.value,
  { activeSelector: '.learning-tabs button.active, .learning-tabs button.selected' }
)
const categoryPillsRef = ref<HTMLElement | null>(null)
const venueCategory = ref('')
const { indicatorStyle: categoryPillStyle } = useSlidingIndicator(
  categoryPillsRef,
  () => venueCategory.value
)

// Data states
const venues = ref<VenueSummary[]>([])
const managedVenues = ref<VenueManagementItem[]>([])
const reviewerCandidates = ref<VenueManagerItem[]>([])
const manageKeyword = ref('')
const showVenueEditModal = ref(false)
const editingVenue = ref<VenueManagementItem | null>(null)
const venueEditForm = reactive<VenueUpdateCommand>({
  name: '',
  capacity: 0,
  availableEquipment: '',
  rules: '',
  enabled: true,
})
const showManagerModal = ref(false)
const managingVenue = ref<VenueManagementItem | null>(null)
const selectedCandidateId = ref<string>('')
const reservations = ref<ReservationItem[]>([])
const selectedReservation = ref<ReservationDetail | null>(null)
const isOwnReservation = computed(() => selectedReservation.value?.applicantId === currentUser.value?.id)
const totalItems = ref(0)
const currentPage = ref(1)
const pageSize = 20

// Filters
const venueKeyword = ref('')
const filterStatus = ref('')

// Loading / notification states
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const success = ref('')

// Schedule viewer modal
const showScheduleModal = ref(false)
const scheduleVenue = ref<VenueSummary | null>(null)
const scheduleDate = ref(new Date().toISOString().slice(0, 10))
const scheduleSlots = ref<TimeSlot[]>([])
const scheduleLoading = ref(false)

// Booking form modal & state
const showBookingModal = ref(false)
const editingReservationId = ref<number | null>(null)
const conflictCheck = ref<ConflictResult | null>(null)
const checkingConflict = ref(false)

const bookingForm = reactive({
  requestKey: '',
  venueId: 0,
  activityTheme: '',
  activityType: 'ACADEMIC',
  expectedParticipants: 20,
  borrowEquipment: '',
  startsAt: '',
  endsAt: '',
  contactPhone: '',
})

let baselineForm = ''
const isFormDirty = computed(() => showBookingModal.value && JSON.stringify(bookingForm) !== baselineForm)

// Review comment input
const reviewComment = ref('')

// Confirmation dialog
const dialog = ref<HTMLDialogElement | null>(null)
const cancelButton = ref<HTMLButtonElement | null>(null)
const { confirmMessage, confirmKind, dialogComment, ask, finish, allowLeave } = useEditProtection(
  busy,
  isFormDirty,
  dialog,
  cancelButton,
)

// Formatting helpers
function formatTime(val?: string) {
  if (!val) return ''
  return val.slice(0, 16).replace('T', ' ')
}

function formatHour(val?: string) {
  if (!val) return ''
  return val.slice(11, 16)
}

function formatDate(val?: string) {
  if (!val) return ''
  return val.slice(0, 10)
}

// Quick time slot generator
function applyQuickTime(slot: 'tomorrow-am' | 'tomorrow-pm' | 'afternoon' | 'weekend') {
  const now = new Date()
  const target = new Date(now)

  if (slot === 'tomorrow-am') {
    target.setDate(target.getDate() + 1)
    bookingForm.startsAt = `${target.toISOString().slice(0, 10)}T09:00`
    bookingForm.endsAt = `${target.toISOString().slice(0, 10)}T11:30`
  } else if (slot === 'tomorrow-pm') {
    target.setDate(target.getDate() + 1)
    bookingForm.startsAt = `${target.toISOString().slice(0, 10)}T14:30`
    bookingForm.endsAt = `${target.toISOString().slice(0, 10)}T17:00`
  } else if (slot === 'afternoon') {
    target.setDate(target.getDate() + 2)
    bookingForm.startsAt = `${target.toISOString().slice(0, 10)}T14:00`
    bookingForm.endsAt = `${target.toISOString().slice(0, 10)}T17:30`
  } else if (slot === 'weekend') {
    const day = target.getDay()
    const diff = (6 - day + 7) % 7 || 7
    target.setDate(target.getDate() + diff)
    bookingForm.startsAt = `${target.toISOString().slice(0, 10)}T09:00`
    bookingForm.endsAt = `${target.toISOString().slice(0, 10)}T12:00`
  }
  void runConflictCheck()
}

// Initial default dates for booking form
function initBookingForm(venue?: VenueSummary) {
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  const dateStr = tomorrow.toISOString().slice(0, 10)

  editingReservationId.value = null
  bookingForm.requestKey = crypto.randomUUID()
  bookingForm.venueId = venue?.id ?? (venues.value[0]?.id || 1)
  bookingForm.activityTheme = ''
  bookingForm.activityType = 'ACADEMIC'
  bookingForm.expectedParticipants = venue?.capacity ? Math.min(venue.capacity, 30) : 30
  bookingForm.borrowEquipment = ''
  bookingForm.startsAt = `${dateStr}T14:30`
  bookingForm.endsAt = `${dateStr}T17:00`
  bookingForm.contactPhone = '13800000000'

  conflictCheck.value = null
  baselineForm = JSON.stringify(bookingForm)
  showBookingModal.value = true
  void runConflictCheck()
}

// Edit existing reservation
function editExistingReservation(res: ReservationDetail) {
  editingReservationId.value = res.id
  bookingForm.requestKey = res.requestKey
  bookingForm.venueId = res.venueId
  bookingForm.activityTheme = res.activityTheme
  bookingForm.activityType = res.activityType
  bookingForm.expectedParticipants = res.expectedParticipants
  bookingForm.borrowEquipment = res.borrowEquipment ?? ''
  bookingForm.startsAt = res.startsAt.slice(0, 16)
  bookingForm.endsAt = res.endsAt.slice(0, 16)
  bookingForm.contactPhone = res.contactPhone

  conflictCheck.value = null
  baselineForm = JSON.stringify(bookingForm)
  showBookingModal.value = true
  void runConflictCheck()
}

// Real-time conflict checking
async function runConflictCheck() {
  if (!bookingForm.venueId || !bookingForm.startsAt || !bookingForm.endsAt) {
    conflictCheck.value = null
    return
  }
  if (bookingForm.startsAt >= bookingForm.endsAt) {
    conflictCheck.value = {
      hasConflict: true,
      message: '预约结束时间必须晚于开始时间',
      conflictingSlots: [],
    }
    return
  }
  checkingConflict.value = true
  try {
    conflictCheck.value = await checkConflict({
      venueId: bookingForm.venueId,
      startsAt: `${bookingForm.startsAt}:00`,
      endsAt: `${bookingForm.endsAt}:00`,
      excludeId: editingReservationId.value ?? undefined,
    })
  } catch {
    // Ignore transient check failure
  } finally {
    checkingConflict.value = false
  }
}

// Load Venue Catalog
async function loadVenues() {
  loading.value = true
  error.value = ''
  try {
    venues.value = await listVenues({
      category: venueCategory.value || undefined,
      placeId: (route.query.venue as string) || undefined,
      keyword: venueKeyword.value || undefined,
    })
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

// Load Reservations List (Mine or Review)
async function loadReservations() {
  loading.value = true
  error.value = ''
  try {
    if (reviewMode.value || activeTab.value === 'review') {
      const res = await listReview({
        status: filterStatus.value || undefined,
        page: currentPage.value,
        pageSize,
      })
      reservations.value = res.items
      totalItems.value = res.total
    } else {
      const res = await listMine({
        status: filterStatus.value || undefined,
        page: currentPage.value,
        pageSize,
      })
      reservations.value = res.items
      totalItems.value = res.total
    }
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

// View Reservation Detail
async function chooseReservation(id: number) {
  loading.value = true
  error.value = ''
  selectedReservation.value = null
  try {
    selectedReservation.value = await getDetail(id)
    reviewComment.value = ''
    await router.replace({ query: { ...route.query, id: String(id), tab: activeTab.value } })
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

// Check schedule for a venue
async function editReservation(id: number) {
  await chooseReservation(id)
  if (selectedReservation.value?.id === id && isOwnReservation.value) {
    editExistingReservation(selectedReservation.value)
  }
}

async function openSchedule(venue: VenueSummary) {
  scheduleVenue.value = venue
  showScheduleModal.value = true
  await fetchSchedule()
}

async function fetchSchedule() {
  if (!scheduleVenue.value) return
  scheduleLoading.value = true
  try {
    scheduleSlots.value = await getVenueSchedule(scheduleVenue.value.id, scheduleDate.value)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    scheduleLoading.value = false
  }
}

// Save Draft
async function handleSaveDraft() {
  if (!bookingForm.activityTheme.trim()) {
    error.value = '请填写活动主题'
    return
  }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const res = await saveDraft({
      requestKey: bookingForm.requestKey,
      venueId: bookingForm.venueId,
      activityTheme: bookingForm.activityTheme.trim(),
      activityType: bookingForm.activityType,
      expectedParticipants: Number(bookingForm.expectedParticipants),
      borrowEquipment: bookingForm.borrowEquipment.trim() || undefined,
      startsAt: `${bookingForm.startsAt}:00`,
      endsAt: `${bookingForm.endsAt}:00`,
      contactPhone: bookingForm.contactPhone.trim(),
    })
    showBookingModal.value = false
    success.value = '借用草稿已保存，可核对信息后提交审核。'
    activeTab.value = 'mine'
    await loadReservations()
    await chooseReservation(res.id)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

// Submit Application
async function handleSubmit() {
  if (!bookingForm.activityTheme.trim()) {
    error.value = '请填写活动主题'
    return
  }
  if (!bookingForm.contactPhone.trim() || !/^\d{11}$/.test(bookingForm.contactPhone.trim())) {
    error.value = '请提供11位有效联系电话，方便现场管理人员确认'
    return
  }
  const venue = venues.value.find(v => v.id === bookingForm.venueId)
  if (venue?.category === 'VENUE' && venue.capacity && Number(bookingForm.expectedParticipants) > venue.capacity) {
    error.value = `预计人数超过场地容量（${venue.capacity} 人），请调整人数或选择其他场地`
    return
  }
  if (conflictCheck.value?.hasConflict) {
    error.value = '当前所选时段已被占用或与现有预约冲突，请先调整时段'
    return
  }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const res = await submitReservation({
      requestKey: bookingForm.requestKey,
      venueId: bookingForm.venueId,
      activityTheme: bookingForm.activityTheme.trim(),
      activityType: bookingForm.activityType,
      expectedParticipants: Number(bookingForm.expectedParticipants),
      borrowEquipment: bookingForm.borrowEquipment.trim() || undefined,
      startsAt: `${bookingForm.startsAt}:00`,
      endsAt: `${bookingForm.endsAt}:00`,
      contactPhone: bookingForm.contactPhone.trim(),
    })
    showBookingModal.value = false
    success.value = '借用申请已成功提交审核！可在“我的借用”与“我的办事”中跟进进度。'
    activeTab.value = 'mine'
    await loadReservations()
    await chooseReservation(res.id)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

// Withdraw application
async function handleWithdraw(id: number) {
  if (!await ask(`确认撤回预约申请 #${id}？撤回后原审核待办关闭，历史记录予以留存。`, 'withdraw')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await withdrawReservation(id)
    success.value = '申请已撤回。'
    await loadReservations()
    if (selectedReservation.value?.id === id) await chooseReservation(id)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

// Cancel reservation
async function handleCancel(id: number) {
  if (!await ask(`确认取消预约 #${id}？取消后释放场地时段，该操作无法撤销。`, 'cancel')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await cancelReservation(id, dialogComment.value || '申请人主动取消')
    success.value = '预约已取消，场地时段已释放。'
    await loadReservations()
    if (selectedReservation.value?.id === id) await chooseReservation(id)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

// Staff Review Decision
async function handleReview(action: 'APPROVE' | 'RETURN' | 'REJECT') {
  if (!selectedReservation.value) return
  const label = action === 'APPROVE' ? '同意借用并签发通行凭证' : action === 'RETURN' ? '退回修改材料' : '驳回借用申请'
  if (!reviewComment.value.trim()) {
    error.value = '请填写审核意见或理由'
    return
  }
  if (!await ask(`确认对申请 #${selectedReservation.value.id} 执行“${label}”？意见将记录在时间线并通知申请人。`, 'decision')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await reviewReservation(selectedReservation.value.id, {
      action,
      comment: reviewComment.value.trim(),
    })
    reviewComment.value = ''
    success.value = `处理已完成：已执行【${label}】。`
    await loadReservations()
    await chooseReservation(selectedReservation.value.id)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

async function handleUsage(action: 'START' | 'COMPLETE') {
  const selected = selectedReservation.value
  if (!selected || !reviewComment.value.trim()) { error.value = '请填写现场核对说明'; return }
  const comment = reviewComment.value.trim()
  const label = action === 'START' ? '登记开始使用' : '确认使用结束并核销'
  if (!await ask(`确认对预约 #${selected.id} 执行“${label}”？请核对现场使用及设备情况。`, 'decision')) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await recordReservationUsage(selected.id, selected.version, action, comment)
    reviewComment.value = ''; await loadReservations(); await chooseReservation(selected.id)
    success.value = `已${label}，申请人已收到消息。`
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}

// Management Methods
const filteredManagedVenues = computed(() => {
  const kw = manageKeyword.value.trim().toLowerCase()
  if (!kw) return managedVenues.value
  return managedVenues.value.filter(v =>
    v.name.toLowerCase().includes(kw) ||
    v.code.toLowerCase().includes(kw) ||
    (v.buildingName && v.buildingName.toLowerCase().includes(kw)) ||
    (v.locationDetail && v.locationDetail.toLowerCase().includes(kw))
  )
})

const candidateOptions = computed(() => {
  if (!managingVenue.value) return []
  const currentIds = new Set(managingVenue.value.managers.map(m => m.userId))
  return reviewerCandidates.value
    .filter(c => !currentIds.has(c.userId))
    .map(c => ({
      value: String(c.userId),
      label: `${c.fullName} (${c.username} · ${c.departmentName})`,
    }))
})

async function loadManagementData() {
  loading.value = true
  error.value = ''
  try {
    const [venueList, candidates] = await Promise.all([
      listVenuesForManagement(),
      getReviewerCandidates(),
    ])
    managedVenues.value = venueList
    reviewerCandidates.value = candidates
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openVenueEdit(venue: VenueManagementItem) {
  editingVenue.value = venue
  venueEditForm.name = venue.name
  venueEditForm.capacity = venue.capacity ?? 0
  venueEditForm.availableEquipment = venue.availableEquipment ?? ''
  venueEditForm.rules = venue.rules ?? ''
  venueEditForm.enabled = venue.enabled
  showVenueEditModal.value = true
}

async function saveVenueEdit() {
  if (!editingVenue.value) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await updateVenue(editingVenue.value.id, {
      name: venueEditForm.name?.trim(),
      capacity: Number(venueEditForm.capacity),
      availableEquipment: venueEditForm.availableEquipment?.trim(),
      rules: venueEditForm.rules?.trim(),
      enabled: venueEditForm.enabled,
    })
    showVenueEditModal.value = false
    success.value = `场地【${editingVenue.value.name}】配置已成功保存！`
    await loadManagementData()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

async function toggleVenueEnabled(venue: VenueManagementItem) {
  busy.value = true
  error.value = ''
  success.value = ''
  const nextState = !venue.enabled
  try {
    await updateVenue(venue.id, { enabled: nextState })
    venue.enabled = nextState
    success.value = `已${nextState ? '开启' : '暂停'}【${venue.name}】的借用对外开放。`
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

function openManagerModal(venue: VenueManagementItem) {
  error.value = ''
  success.value = ''
  managingVenue.value = venue
  selectedCandidateId.value = ''
  showManagerModal.value = true
}

async function handleAddManager() {
  if (!managingVenue.value || !selectedCandidateId.value) return
  const uid = Number(selectedCandidateId.value)
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await addVenueManager(managingVenue.value.id, uid)
    success.value = '已成功指定责任审批人！'
    await loadManagementData()
    managingVenue.value = managedVenues.value.find(v => v.id === managingVenue.value?.id) || null
    selectedCandidateId.value = ''
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

async function handleRemoveManager(userId: number) {
  if (!managingVenue.value) return
  if (!await ask('确认解除该负责人的审批指派？有待审申请时，请先添加接任人员。', 'decision')) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await removeVenueManager(managingVenue.value.id, userId)
    success.value = '已解除责任审批人关联。'
    await loadManagementData()
    managingVenue.value = managedVenues.value.find(v => v.id === managingVenue.value?.id) || null
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

// Switch tabs
async function switchTab(tab: 'venues' | 'mine' | 'review' | 'manage') {
  if (!await allowLeave()) return
  activeTab.value = tab
  selectedReservation.value = null
  currentPage.value = 1
  filterStatus.value = tab === 'review' ? 'SUBMITTED' : ''
  await router.replace({ query: { ...route.query, tab } })
  if (tab === 'venues') await loadVenues()
  else if (tab === 'manage') await loadManagementData()
  else await loadReservations()
}

// Initial setup
onMounted(async () => {
  if (route.query.tab === 'manage' && canManage.value) {
    activeTab.value = 'manage'
  } else if (reviewMode.value) {
    activeTab.value = 'review'
    filterStatus.value = String(route.query.status || 'SUBMITTED')
  } else if (route.query.tab === 'mine') {
    activeTab.value = 'mine'
  } else if (route.query.venue) {
    activeTab.value = 'venues'
  }

  await loadVenues()
  if (activeTab.value === 'manage') {
    await loadManagementData()
  } else if (activeTab.value !== 'venues') {
    await loadReservations()
  }

  // Pre-load specific reservation if id passed in route query
  if (route.query.id) {
    const id = Number(route.query.id)
    if (id > 0) {
      await chooseReservation(id)
      if (!reviewMode.value && activeTab.value !== 'manage') activeTab.value = 'mine'
    }
  }
})

watch(() => [bookingForm.venueId, bookingForm.startsAt, bookingForm.endsAt], () => {
  void runConflictCheck()
})

watch(() => route.query.venue, () => {
  if (activeTab.value === 'venues') void loadVenues()
})
</script>

<template>
  <div class="reservation-page">
    <!-- Confirmation Dialog -->
    <dialog ref="dialog" class="foundation-dialog" aria-labelledby="res-dialog-heading" @cancel.prevent="finish(false)">
      <h2 id="res-dialog-heading">{{ confirmKind === 'leave' ? '处理未保存的内容' : '操作确认' }}</h2>
      <p>{{ confirmMessage }}</p>
      <label v-if="confirmKind === 'cancel'"><div class="label-heading-row"><span>取消原因说明</span><small class="muted">选填 · 最多 300 字</small></div><textarea v-model="dialogComment" rows="2" maxlength="300" placeholder="说明取消预约的原因" /></label>
      <div class="form-actions">
        <button ref="cancelButton" class="button compact" @click="finish(false)">{{ confirmKind === 'leave' ? '继续编辑' : '取消' }}</button>
        <button class="button primary compact" @click="finish(true)">{{ confirmKind === 'leave' ? '放弃并离开' : '确认执行' }}</button>
      </div>
    </dialog>

    <!-- Page Header -->
    <div class="page-heading-row">
      <div>
        <span class="portal-context">{{ reviewMode ? '学生事务中心 · 空间与资源审核' : '学生服务中心 · 资源借用' }}</span>
        <h1>{{ reviewMode ? '公共场地与设备借用审批' : '公共场地与设备借用' }}</h1>
        <p class="muted">
          {{ reviewMode ? '办理本人负责场地与设备的借用申请，核验活动安排及使用时段；本人的申请由其他负责人审批。' : '在线查询场地与设备开放情况，提交借用申请并查看办理结果。' }}
        </p>
      </div>
      <div class="form-actions">
        <RouterLink to="/campus/explore" class="button compact">
          <MapPin :size="15" />
          三维沙盘实景查看
        </RouterLink>
        <button v-if="!reviewMode && canApply" class="button primary compact" :disabled="busy || loading" @click="initBookingForm()">
          <Plus :size="15" />
          新建借用申请
        </button>
        <button class="button compact" :disabled="busy || loading" @click="activeTab === 'venues' ? loadVenues() : activeTab === 'manage' ? loadManagementData() : loadReservations()">
          <RefreshCw :size="15" />
          刷新数据
        </button>
      </div>
    </div>

    <!-- Alert / Success Messages -->
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>

    <!-- Navigation Tabs -->
    <nav ref="reservationNavRef" class="learning-tabs reservation-nav-tabs" aria-label="场地借用栏目切换">
      <button v-if="!reviewMode" :class="{ active: activeTab === 'venues' }" @click="switchTab('venues')">
        <Building2 :size="15" />
        场地与设备目录
      </button>
      <button v-if="!reviewMode" :class="{ active: activeTab === 'mine' }" @click="switchTab('mine')">
        <CalendarCheck :size="15" />
        我的借用申请
      </button>
      <button v-if="reviewMode || canReview" :class="{ active: activeTab === 'review' }" @click="switchTab('review')">
        <Clock :size="15" />
        借用审批待办
      </button>
      <button v-if="canManage" :class="{ active: activeTab === 'manage' }" @click="switchTab('manage')">
        <Settings :size="15" />
        场地资源配置
      </button>
      <div class="learning-tabs-indicator" :style="reservationIndicatorStyle" aria-hidden="true"></div>
    </nav>

    <!-- ==================== TAB 1: VENUE & EQUIPMENT CATALOG ==================== -->
    <section v-if="activeTab === 'venues'" class="portal-panel venue-catalog-section" aria-label="场地与设备目录">
      <div class="foundation-filters venue-filters">
        <div ref="categoryPillsRef" class="category-pills">
          <button :class="{ active: venueCategory === '' }" @click="venueCategory = ''; loadVenues()">全部资源</button>
          <button :class="{ active: venueCategory === 'VENUE' }" @click="venueCategory = 'VENUE'; loadVenues()">活动场地与场馆</button>
          <button :class="{ active: venueCategory === 'EQUIPMENT' }" @click="venueCategory = 'EQUIPMENT'; loadVenues()">专用器材与设备</button>
          <div class="category-pill-indicator" :style="categoryPillStyle" aria-hidden="true"></div>
        </div>
        <div class="search-input-group">
          <Search :size="15" class="search-icon" />
          <input v-model="venueKeyword" placeholder="搜索场地名称、所在建筑或配套设备" @keyup.enter="loadVenues" />
          <button class="button compact" @click="loadVenues">搜索</button>
        </div>
      </div>

      <div v-if="route.query.venue" class="filter-tag-tip">
        <span>当前筛选三维校园地点：<strong>{{ route.query.venue }}</strong></span>
        <button class="text-button" @click="router.replace({ query: { ...route.query, venue: undefined } }); loadVenues()">清除地点筛选</button>
      </div>

      <p v-if="loading && !venues.length" class="loading-state" role="status">正在读取场地与设备资源…</p>
      <p v-else-if="!loading && !venues.length" class="empty-state">未查询到符合条件的场地或设备资源。</p>

      <div v-else class="venue-cards-grid" :class="{ 'is-loading': loading }">
        <article v-for="venue in venues" :key="venue.id" class="venue-card">
          <div class="venue-card-header">
            <span class="venue-category-badge" :class="venue.category.toLowerCase()">
              {{ venue.category === 'VENUE' ? '场地空间' : '器材设备' }}
            </span>
            <span class="venue-code">{{ venue.code }}</span>
          </div>

          <h3 class="venue-title">{{ venue.name }}</h3>

          <div class="venue-meta">
            <span class="meta-item">
              <MapPin :size="14" />
              {{ venue.buildingName || '校园公共区' }} · {{ venue.locationDetail }}
            </span>
            <span v-if="venue.capacity" class="meta-item">
              <Users :size="14" />
              容纳人数：约 {{ venue.capacity }} 人
            </span>
          </div>

          <div v-if="venue.availableEquipment" class="venue-equipment-box">
            <span class="box-label">包含配套：</span>
            <p>{{ venue.availableEquipment }}</p>
          </div>

          <div v-if="venue.rules" class="venue-rules-box">
            <span class="box-label">借用守则：</span>
            <p>{{ venue.rules }}</p>
          </div>

          <div class="venue-card-actions">
            <button class="button compact" @click="openSchedule(venue)">
              <CalendarDays :size="14" />
              查看排期占用
            </button>
            <button class="button primary compact" :disabled="!venue.enabled" @click="initBookingForm(venue)">
              <Plus :size="14" />
              申请借用
            </button>
          </div>
        </article>
      </div>
    </section>

    <!-- ==================== TAB 4: VENUE MANAGEMENT (RESOURCE MANAGERS) ==================== -->
    <section v-else-if="activeTab === 'manage'" class="portal-panel venue-manage-section" aria-label="场地资源配置">
      <div class="foundation-filters venue-filters">
        <div class="manage-header-desc">
          <p class="muted">
            管理全校公共场地与设备的开放状态、容纳人数、配套设施、使用守则及<strong>指定责任审批人</strong>。
            提交借用时将仅路由给本场地配置的责任人，防止审批无序扩散。
          </p>
        </div>
        <div class="search-input-group">
          <Search :size="15" class="search-icon" />
          <input v-model="manageKeyword" placeholder="搜索场地名称、编号或所在位置" />
        </div>
      </div>

      <p v-if="loading && !filteredManagedVenues.length" class="loading-state" role="status">正在读取场地资源配置与责任人信息…</p>
      <p v-else-if="!loading && !filteredManagedVenues.length" class="empty-state">没有符合搜索条件的场地资源。</p>

      <div v-else class="venue-cards-grid manage-cards-grid" :class="{ 'is-loading': loading }">
        <article v-for="venue in filteredManagedVenues" :key="venue.id" class="venue-card manage-card" :class="{ disabled: !venue.enabled }">
          <div class="venue-card-header">
            <div class="badge-group">
              <span class="venue-category-badge" :class="venue.category.toLowerCase()">
                {{ venue.category === 'VENUE' ? '场地空间' : '器材设备' }}
              </span>
              <span class="venue-status-tag" :class="venue.enabled ? 'enabled' : 'disabled'">
                {{ venue.enabled ? '对外开放中' : '暂停借用' }}
              </span>
            </div>
            <span class="venue-code">{{ venue.code }}</span>
          </div>

          <h3 class="venue-title">{{ venue.name }}</h3>

          <div class="venue-meta">
            <span class="meta-item">
              <MapPin :size="14" />
              {{ venue.buildingName || '校园公共区' }} · {{ venue.locationDetail }}
            </span>
            <span v-if="venue.capacity" class="meta-item">
              <Users :size="14" />
              容纳人数：约 {{ venue.capacity }} 人
            </span>
          </div>

          <div v-if="venue.availableEquipment" class="venue-equipment-box">
            <span class="box-label">包含配套：</span>
            <p>{{ venue.availableEquipment }}</p>
          </div>

          <div v-if="venue.rules" class="venue-rules-box">
            <span class="box-label">借用守则：</span>
            <p>{{ venue.rules }}</p>
          </div>

          <!-- Managers Box -->
          <div class="venue-managers-box">
            <div class="managers-box-header">
              <span class="box-label">
                <ShieldCheck :size="14" />
                有效审批人员 {{ venue.activeReviewerCount }} / 已配置 {{ venue.managers.length }}
              </span>
              <button class="text-button compact" :disabled="busy" @click="openManagerModal(venue)">
                <UserCheck :size="14" />
                配置人员
              </button>
            </div>
            <div v-if="venue.activeReviewerCount === 0" class="no-manager-alert">
              <ShieldAlert :size="14" />
              <span>暂无有效审批人，请配置已启用且具备审批权限的人员。</span>
            </div>
            <div v-if="venue.unattendedReviewCount > 0" class="no-manager-alert" role="status">
              <ShieldAlert :size="14" />
              <span>{{ venue.unattendedReviewCount }} 条待审申请无人可审，请配置接任人员。</span>
            </div>
            <p class="muted">待审申请 {{ venue.pendingReviewCount }} 条</p>
            <div v-if="venue.managers.length" class="manager-tags-list">
              <span v-for="m in venue.managers" :key="m.userId" class="manager-tag" :class="{ 'is-inactive': !m.eligible }">
                <strong>{{ m.fullName }}</strong>
                <small v-if="!m.eligible">已失效</small>
                <small>({{ m.departmentName }})</small>
              </span>
            </div>
          </div>

          <!-- Actions -->
          <div class="venue-card-actions manage-actions">
            <button class="button compact" :disabled="busy" @click="openVenueEdit(venue)">
              <Edit3 :size="14" />
              编辑配置
            </button>
            <button
              class="button compact"
              :class="{ 'warning-btn': venue.enabled }"
              :disabled="busy"
              @click="toggleVenueEnabled(venue)"
            >
              <ToggleRight v-if="venue.enabled" :size="15" />
              <ToggleLeft v-else :size="15" />
              {{ venue.enabled ? '暂停开放' : '恢复开放' }}
            </button>
          </div>
        </article>
      </div>
    </section>

    <!-- ==================== TAB 2 & 3: RESERVATIONS LIST (MINE / REVIEW) ==================== -->
    <section v-else class="portal-panel reservation-list-section" aria-label="借用记录列表">
      <div class="foundation-filters">
        <label>
          申请状态
          <BaseSelect
            v-model="filterStatus"
            :options="[
              { value: '', label: '全部状态' },
              ...Object.entries(reservationStatuses).map(([key, label]) => ({ value: key, label }))
            ]"
            @change="loadReservations"
          />
        </label>
        <button class="button compact" :disabled="loading" @click="loadReservations">查询</button>
        <span class="muted" style="margin-left: auto;">共 {{ totalItems }} 条申请</span>
      </div>

      <p v-if="loading && !reservations.length" class="loading-state" role="status">正在读取借用记录…</p>
      <p v-else-if="!loading && !reservations.length" class="empty-state">当前没有符合筛选条件的借用申请。</p>

      <div v-else class="table-scroll" :class="{ 'is-loading': loading }">
        <table class="campus-table foundation-table">
          <thead>
            <tr>
              <th>申请编号</th>
              <th>借用场地 / 设备</th>
              <th>活动主题与类别</th>
              <th>使用时段</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in reservations" :key="item.id" :class="{ 'row-selected': selectedReservation?.id === item.id }">
              <td>
                <strong>#{{ item.id }}</strong>
                <small>V{{ item.version }}</small>
              </td>
              <td>
                <strong>{{ item.venueName }}</strong>
                <small>{{ item.locationDetail }}</small>
              </td>
              <td>
                <span>{{ item.activityTheme }}</span>
                <small>{{ activityTypes[item.activityType] || item.activityType }} · 约 {{ item.expectedParticipants }} 人</small>
              </td>
              <td>
                <div class="time-range-cell">
                  <span>{{ formatDate(item.startsAt) }}</span>
                  <small>{{ formatHour(item.startsAt) }} ~ {{ formatHour(item.endsAt) }}</small>
                </div>
              </td>
              <td>
                <span class="status-label" :class="item.status.toLowerCase()">
                  {{ reservationStatuses[item.status] || item.status }}
                </span>
              </td>
              <td>
                <div class="table-action-btns">
                  <button class="text-button" @click="chooseReservation(item.id)">
                    {{ item.status === 'APPROVED' ? '查看凭证与详情' : '查看详情' }}
                  </button>
                  <button v-if="activeTab === 'mine' && ['DRAFT', 'RETURNED'].includes(item.status)" class="text-button" :disabled="loading || busy" @click="editReservation(item.id)">
                    修改资料
                  </button>
                  <button v-if="activeTab === 'mine' && item.status === 'SUBMITTED'" class="text-button" @click="handleWithdraw(item.id)">
                    撤回
                  </button>
                  <button v-if="activeTab === 'mine' && item.status === 'APPROVED'" class="text-button cancel-text-btn" @click="handleCancel(item.id)">
                    取消预约
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ==================== SELECTED RESERVATION DETAIL DRAWER/CARD ==================== -->
    <section v-if="selectedReservation" class="portal-panel reservation-detail-panel" aria-label="预约详细信息">
      <div class="section-heading">
        <div>
          <span class="eyebrow">借用申请 #{{ selectedReservation.id }} · {{ selectedReservation.venueName }}</span>
          <h2>{{ selectedReservation.activityTheme }}</h2>
        </div>
        <div class="detail-header-status">
          <span class="status-label" :class="selectedReservation.status.toLowerCase()">
            {{ reservationStatuses[selectedReservation.status] }} · V{{ selectedReservation.version }}
          </span>
          <button class="text-button" @click="selectedReservation = null">
            <X :size="16" />
            关闭详情
          </button>
        </div>
      </div>

      <!-- APPROVED: ELECTRONIC PASS (入场通行电子凭证) -->
      <div v-if="['APPROVED', 'IN_USE'].includes(selectedReservation.status) && new Date(selectedReservation.endsAt).getTime() > Date.now()" id="venue-access-pass" class="access-pass-card">
        <div class="pass-watermark">郑州工业应用技术学院</div>
        <div class="pass-header">
          <div class="pass-badge">
            <CheckCircle2 :size="24" />
            <div>
              <h3>公共场地借用电子通行凭证</h3>
              <small>郑州工业应用技术学院 · 数字化空间服务</small>
            </div>
          </div>
          <div class="pass-number">
            <span>凭证编号</span>
            <strong>PASS-{{ selectedReservation.id }}-{{ selectedReservation.requestKey.slice(0, 8).toUpperCase() }}</strong>
          </div>
        </div>

        <div class="pass-grid">
          <div class="pass-grid-item">
            <span class="label">借用场地/设备</span>
            <strong class="val">{{ selectedReservation.venueName }}</strong>
            <small>{{ selectedReservation.locationDetail }}</small>
          </div>
          <div class="pass-grid-item">
            <span class="label">准入使用时段</span>
            <strong class="val time-val">{{ formatTime(selectedReservation.startsAt) }} 至 {{ formatTime(selectedReservation.endsAt) }}</strong>
          </div>
          <div class="pass-grid-item">
            <span class="label">活动申请人</span>
            <span class="val">{{ selectedReservation.applicantName }} ({{ selectedReservation.identityNumber }})</span>
            <small>{{ selectedReservation.departmentName }} · {{ selectedReservation.className || '教师/部门' }}</small>
          </div>
          <div class="pass-grid-item">
            <span class="label">联系电话 / 预计人数</span>
            <span class="val">{{ selectedReservation.contactPhone }} · 约 {{ selectedReservation.expectedParticipants }} 人</span>
          </div>
          <div v-if="selectedReservation.borrowEquipment" class="pass-grid-item pass-wide">
            <span class="label">核准借用配套设备</span>
            <span class="val">{{ selectedReservation.borrowEquipment }}</span>
          </div>
        </div>

        <div class="pass-footer">
          <div class="pass-instructions">
            <p><strong>使用提示：</strong>活动开始前15分钟请向场地楼管/管理员出示此凭证开启场地门锁；遵守校内声环境与消防安全管理制度，结束时清理卫生并切断电源。</p>
            <p class="pass-export-note no-print">可下载 PDF 后打开打印；凭证仅适用于所载时段，取消或变更以系统最新状态为准。</p>
          </div>
          <div class="pass-actions no-print">
            <a class="button compact" :href="reservationPassUrl(selectedReservation.id, true)" target="_blank" rel="noopener">
              <Printer :size="14" />
              预览 / 打印
            </a>
            <DownloadLink class="button primary compact" :href="reservationPassUrl(selectedReservation.id)">
              <Download :size="14" />下载 PDF
            </DownloadLink>
          </div>
        </div>
      </div>

      <!-- FACTS GRID -->
      <dl class="resource-facts reservation-facts">
        <div>
          <dt>借用场地</dt>
          <dd>{{ selectedReservation.venueName }} ({{ selectedReservation.locationDetail }})</dd>
        </div>
        <div>
          <dt>活动类别</dt>
          <dd>{{ activityTypes[selectedReservation.activityType] || selectedReservation.activityType }}</dd>
        </div>
        <div>
          <dt>使用时段</dt>
          <dd>{{ formatTime(selectedReservation.startsAt) }} 至 {{ formatTime(selectedReservation.endsAt) }}</dd>
        </div>
        <div>
          <dt>预计参与人数</dt>
          <dd>{{ selectedReservation.expectedParticipants }} 人</dd>
        </div>
        <div>
          <dt>借用申请人</dt>
          <dd>{{ selectedReservation.applicantName }} ({{ selectedReservation.departmentName }})</dd>
        </div>
        <div>
          <dt>现场联系人电话</dt>
          <dd>{{ selectedReservation.contactPhone }}</dd>
        </div>
        <div v-if="selectedReservation.borrowEquipment" class="leave-wide">
          <dt>配套设备申请</dt>
          <dd>{{ selectedReservation.borrowEquipment }}</dd>
        </div>
        <div v-if="selectedReservation.processingComment" class="leave-wide">
          <dt>最近审批意见</dt>
          <dd class="preserve-lines">{{ selectedReservation.processingComment }}</dd>
        </div>
      </dl>

      <!-- STAFF REVIEW ACTION FORM -->
      <div v-if="canReview && !isOwnReservation && activeTab === 'review' && selectedReservation.status === 'SUBMITTED'" class="leave-review-form reservation-review-box">
        <h3><Clock :size="16" /> 审核意见与决定</h3>
        <label>
          <div class="label-heading-row">
            <span>审批处理意见 / 附言</span>
            <small class="muted">必填 · 最多 1000 字</small>
          </div>
          <textarea
            v-model="reviewComment"
            rows="3"
            maxlength="1000"
            placeholder="同意时可注明场地安全与钥匙借用指引；退回或驳回时请详述原因或修改建议"
          />
        </label>
        <div class="form-actions">
          <button class="button primary compact" :disabled="busy || !reviewComment.trim()" @click="handleReview('APPROVE')">
            <CheckCircle2 :size="15" />
            同意预约并签发通行凭证
          </button>
          <button class="button compact" :disabled="busy || !reviewComment.trim()" @click="handleReview('RETURN')">
            <Undo2 :size="15" />
            退回补充材料
          </button>
          <button class="button compact danger" :disabled="busy || !reviewComment.trim()" @click="handleReview('REJECT')">
            <XCircle :size="15" />
            驳回借用申请
          </button>
        </div>
      </div>

      <section v-if="canReview && !isOwnReservation && activeTab === 'review' && ['APPROVED', 'IN_USE'].includes(selectedReservation.status)" class="leave-review-form reservation-review-box" aria-label="使用登记与结束核销">
        <h3>{{ selectedReservation.status === 'IN_USE' ? '使用结束核销' : '现场使用登记' }}</h3>
        <p class="muted">{{ selectedReservation.status === 'IN_USE' ? '请核实场地、设施及借用设备已交接后结束本次使用；逾时仍需在此核销。' : '请在批准的使用时段内核对申请人及现场人数后登记开始使用。' }}</p>
        <label>现场核对说明<textarea v-model="reviewComment" rows="3" maxlength="1000" :disabled="busy" placeholder="填写现场核对、设施或设备交接情况" /></label>
        <div class="form-actions"><button class="button primary compact" :disabled="busy || !reviewComment.trim()" @click="handleUsage(selectedReservation.status === 'IN_USE' ? 'COMPLETE' : 'START')"><ClipboardCheck :size="15" />{{ selectedReservation.status === 'IN_USE' ? '确认使用结束并核销' : '登记开始使用' }}</button></div>
      </section>
      <p v-if="selectedReservation.status === 'COMPLETED'" class="content-context-note">本次使用已结束核销。使用登记和现场核对说明已保留，原入场凭证已失效。</p>
      <p v-if="selectedReservation.status === 'EXPIRED'" class="content-context-note">批准时段已结束且未登记使用，本次预约已过期。如仍需使用，请重新申请。</p>

      <!-- STUDENT ACTIONS (Edit, Withdraw, Cancel) -->
      <div v-if="canApply && isOwnReservation" class="form-actions reservation-detail-actions">
        <button
          v-if="['DRAFT', 'RETURNED'].includes(selectedReservation.status)"
          class="button primary compact"
          :disabled="busy"
          @click="editExistingReservation(selectedReservation)"
        >
          修改预约资料并提交
        </button>
        <button
          v-if="selectedReservation.status === 'SUBMITTED'"
          class="button compact"
          :disabled="busy"
          @click="handleWithdraw(selectedReservation.id)"
        >
          撤回申请
        </button>
        <button
          v-if="selectedReservation.status === 'APPROVED'"
          class="button compact danger"
          :disabled="busy"
          @click="handleCancel(selectedReservation.id)"
        >
          取消该预约
        </button>
      </div>

      <!-- TIMELINE INTEGRATION -->
      <div class="reservation-timeline-wrap">
        <AffairTimeline v-if="isOwnReservation" kind="RESERVATION" :request-id="selectedReservation.id" />
        <section v-else aria-label="预约办理记录">
          <h3>办理记录</h3>
          <article v-for="record in selectedReservation.actions" :key="record.id" class="resource-history">
            <div class="section-heading"><strong>{{ reservationActions[record.action] || record.action }}</strong><span class="status-label">{{ reservationStatuses[record.status] || record.status }}</span></div>
            <p class="preserve-lines">{{ record.comment }}</p>
            <small class="muted">{{ record.actorName }} · {{ formatTime(record.createdAt) }}</small>
          </article>
        </section>
      </div>
    </section>

    <!-- ==================== BOOKING MODAL (DRAWER/POPUP) ==================== -->
    <div v-if="showBookingModal" class="modal-overlay" @click.self="showBookingModal = false">
      <div class="modal-card booking-modal-card" role="dialog" aria-labelledby="booking-modal-title">
        <div class="modal-header">
          <h2 id="booking-modal-title">
            <CalendarCheck :size="18" />
            {{ editingReservationId ? `修改借用申请 #${editingReservationId}` : '新建公共场地与设备借用' }}
          </h2>
          <button class="close-icon-btn" @click="showBookingModal = false">
            <X :size="18" />
          </button>
        </div>

        <form class="editor-form booking-form" @submit.prevent="handleSubmit">
          <div class="form-grid-two">
            <label>
              目标场地 / 设备
              <BaseSelect
                v-model="bookingForm.venueId"
                :options="venues.map(v => ({
                  value: v.id,
                  label: `${v.name} (${v.buildingName || '公共区'} · ${v.category === 'VENUE' ? `容纳${v.capacity}人` : '设备'})`
                }))"
                :disabled="busy"
                placeholder="请选择目标场地或设备"
              />
            </label>

            <label>
              活动类型
              <BaseSelect
                v-model="bookingForm.activityType"
                :options="Object.entries(activityTypes).map(([val, name]) => ({ value: val, label: name }))"
                :disabled="busy"
                placeholder="请选择活动类型"
              />
            </label>
          </div>

          <label>
            活动主题
            <input
              v-model="bookingForm.activityTheme"
              required
              maxlength="150"
              placeholder="例：2026届计算机学院毕业设计开题答辩研讨会"
              :disabled="busy"
            />
          </label>

          <div class="form-grid-two">
            <label>
              预计参与人数
              <input
                v-model.number="bookingForm.expectedParticipants"
                type="number"
                min="1"
                max="2000"
                required
                :disabled="busy"
              />
            </label>

            <label>
              现场负责人员电话
              <input
                v-model="bookingForm.contactPhone"
                type="tel"
                required
                maxlength="15"
                placeholder="11位手机号码"
                :disabled="busy"
              />
            </label>
          </div>

          <!-- TIME SELECTOR -->
          <div class="time-selection-section">
            <div class="time-inputs-row">
              <label>
                借用开始时间
                <input
                  v-model="bookingForm.startsAt"
                  type="datetime-local"
                  required
                  :disabled="busy"
                />
              </label>

              <label>
                借用结束时间
                <input
                  v-model="bookingForm.endsAt"
                  type="datetime-local"
                  required
                  :disabled="busy"
                />
              </label>
            </div>

            <!-- QUICK TIME CHIPS -->
            <div class="quick-chips-row">
              <span class="chips-label">快速填充：</span>
              <button type="button" class="chip-btn" @click="applyQuickTime('tomorrow-am')">明天上午 09:00~11:30</button>
              <button type="button" class="chip-btn" @click="applyQuickTime('tomorrow-pm')">明天下午 14:30~17:00</button>
              <button type="button" class="chip-btn" @click="applyQuickTime('afternoon')">后天下午 14:00~17:30</button>
              <button type="button" class="chip-btn" @click="applyQuickTime('weekend')">本周末上午 09:00~12:00</button>
            </div>

            <!-- CONFLICT CHECK STATUS -->
            <div class="conflict-check-banner">
              <div v-if="checkingConflict" class="checking-state">
                <RefreshCw :size="14" class="spinning" />
                <span>正在校验全校场地排期冲突…</span>
              </div>
              <div v-else-if="conflictCheck?.hasConflict" class="conflict-alert">
                <AlertTriangle :size="16" />
                <div>
                  <strong>时段冲突警告：{{ conflictCheck.message }}</strong>
                  <ul v-if="conflictCheck.conflictingSlots.length" class="conflict-slot-list">
                    <li v-for="slot in conflictCheck.conflictingSlots" :key="slot.startsAt">
                      已占用：{{ formatTime(slot.startsAt) }} ~ {{ formatHour(slot.endsAt) }}（{{ slot.activityTheme }}）
                    </li>
                  </ul>
                </div>
              </div>
              <div v-else-if="conflictCheck && !conflictCheck.hasConflict" class="conflict-clear">
                <CheckCircle2 :size="15" />
                <span>该场地此时段完全空闲，可放心提交申请。</span>
              </div>
            </div>
          </div>

          <label>
            <div class="label-heading-row">
              <span>需要借用的配套器材 / 附加需求 (选填)</span>
              <small class="muted">选填 · 最多 400 字</small>
            </div>
            <textarea
              v-model="bookingForm.borrowEquipment"
              rows="3"
              maxlength="400"
              placeholder="例：无线手持麦克风2支、激光翻页笔1支、活动展架2套"
              :disabled="busy"
            />
          </label>

          <p class="data-note">
            请确认活动符合校规校纪。审批通过后系统将即时生成入场通行电子凭证，请于使用前联系管理员准入。
          </p>

          <div class="form-actions modal-actions">
            <button type="button" class="button compact" :disabled="busy" @click="handleSaveDraft">
              保存为草稿
            </button>
            <button type="submit" class="button primary compact" :disabled="busy || conflictCheck?.hasConflict">
              正式提交预约
            </button>
            <button type="button" class="button compact" :disabled="busy" @click="showBookingModal = false">
              取消
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- ==================== SCHEDULE VIEWER MODAL ==================== -->
    <div v-if="showScheduleModal && scheduleVenue" class="modal-overlay" @click.self="showScheduleModal = false">
      <div class="modal-card schedule-modal-card" role="dialog" aria-labelledby="schedule-modal-title">
        <div class="modal-header">
          <h2 id="schedule-modal-title">
            <CalendarDays :size="18" />
            {{ scheduleVenue.name }} · 排期占用情况
          </h2>
          <button class="close-icon-btn" @click="showScheduleModal = false">
            <X :size="18" />
          </button>
        </div>

        <div class="schedule-controls">
          <label>
            查询日期
            <input v-model="scheduleDate" type="date" @change="fetchSchedule" />
          </label>
          <button class="button compact" :disabled="scheduleLoading" @click="fetchSchedule">
            <RefreshCw :size="14" />
            刷新排期
          </button>
        </div>

        <div class="schedule-content">
          <p v-if="scheduleLoading" class="loading-state">正在查询当天排期…</p>
          <div v-else-if="!scheduleSlots.length" class="empty-state">
            <CheckCircle2 :size="24" style="color: #10b981; margin-bottom: 8px;" />
            <p>该日期目前全天无任何预约占用，时段充裕！</p>
          </div>
          <ul v-else class="schedule-slots-timeline">
            <li v-for="slot in scheduleSlots" :key="slot.startsAt" class="schedule-slot-item">
              <div class="slot-time">
                <strong>{{ formatHour(slot.startsAt) }} - {{ formatHour(slot.endsAt) }}</strong>
                <span class="slot-status-badge">{{ slot.status === 'IN_USE' ? '使用中' : slot.status === 'APPROVED' ? '已同意占用' : '审核中' }}</span>
              </div>
              <div class="slot-desc">
                <h4>{{ slot.activityTheme }}</h4>
              </div>
            </li>
          </ul>
        </div>

        <div class="modal-footer">
          <button class="button primary compact" @click="showScheduleModal = false; initBookingForm(scheduleVenue)">
            预约该场地
          </button>
          <button class="button compact" @click="showScheduleModal = false">
            关闭
          </button>
        </div>
      </div>
    </div>

    <!-- ==================== MODAL: EDIT VENUE CONFIGURATION ==================== -->
    <div v-if="showVenueEditModal && editingVenue" class="modal-overlay" @click.self="showVenueEditModal = false">
      <div class="modal-card venue-edit-modal" role="dialog" aria-labelledby="venue-edit-title">
        <div class="modal-header">
          <h2 id="venue-edit-title">
            <Edit3 :size="18" />
            修改场地资源配置 · {{ editingVenue.name }}
          </h2>
          <button class="close-icon-btn" @click="showVenueEditModal = false">
            <X :size="18" />
          </button>
        </div>

        <form class="booking-form" @submit.prevent="saveVenueEdit">
          <div class="form-grid-two">
            <label>
              场地/设备名称
              <input v-model="venueEditForm.name" required maxlength="100" />
            </label>
            <label>
              容纳人数（人）
              <input v-model.number="venueEditForm.capacity" type="number" min="1" max="10000" />
            </label>
          </div>

          <label>
            可用配套设备与技术规格
            <input v-model="venueEditForm.availableEquipment" maxlength="500" placeholder="例如：投影仪、无线麦克风*2、调音台" />
          </label>

          <label>
            <div class="label-heading-row">
              <span>借用守则与场地公约</span>
              <small class="muted">最多 1000 字</small>
            </div>
            <textarea v-model="venueEditForm.rules" rows="3" maxlength="1000" placeholder="填写使用要求、保洁守则、禁止携带外卖等约定" />
          </label>

          <label class="checkbox-label" style="display: flex; align-items: center; gap: 8px; margin-top: 8px; cursor: pointer;">
            <input v-model="venueEditForm.enabled" type="checkbox" />
            <span>开启该场地/设备的对外预约开放（取消勾选将暂停开放）</span>
          </label>
          <p v-if="!venueEditForm.enabled" class="alert" role="status">停用会取消尚未开始的待审和已批准预约，并通知申请人。正在使用的场地须先完成结束核销，才能停用。</p>

          <div class="form-actions modal-actions" style="margin-top: 18px;">
            <button type="submit" class="button primary compact" :disabled="busy">
              保存配置
            </button>
            <button type="button" class="button compact" @click="showVenueEditModal = false">
              取消
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- ==================== MODAL: MANAGE VENUE REVIEWERS ==================== -->
    <div v-if="showManagerModal && managingVenue" class="modal-overlay" @click.self="showManagerModal = false">
      <div class="modal-card manager-modal-card" role="dialog" aria-labelledby="manager-modal-title">
        <div class="modal-header">
          <h2 id="manager-modal-title">
            <ShieldCheck :size="18" />
            配置责任审批人 · {{ managingVenue.name }}
          </h2>
          <button class="close-icon-btn" @click="showManagerModal = false">
            <X :size="18" />
          </button>
        </div>

        <div class="modal-body-content" style="display: flex; flex-direction: column; gap: 16px;">
          <p class="muted" style="font-size: 13px; line-height: 1.6;">
            申请需要至少一位有效且非申请人本人的审批人。添加负责人会通知其接手已有待审申请；请先添加接任人员，再解除原负责人。
          </p>
          <p v-if="error" class="alert" role="alert">{{ error }}</p>
          <p v-if="success" class="success-message" role="status">{{ success }}</p>
          <p v-if="managingVenue.unattendedReviewCount > 0" class="alert" role="status">
            当前 {{ managingVenue.unattendedReviewCount }} 条待审申请无人可审，请添加接任人员。
          </p>

          <!-- Current Assigned Reviewers -->
          <div class="assigned-managers-list">
            <h4 style="font-size: 14px; margin-bottom: 8px; font-weight: 600;">已指定责任审批人 ({{ managingVenue.managers.length }})</h4>
            <div v-if="!managingVenue.managers.length" class="empty-state" style="padding: 16px; background: #fff1f2; border: 1px dashed #f43f5e; border-radius: 6px;">
              <span style="color: #e11d48; font-size: 13px;">目前尚未指定任何审批责任人！未配置责任人前该场地无法受理申请。</span>
            </div>
            <ul v-else class="manager-item-list" style="list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px;">
              <li v-for="m in managingVenue.managers" :key="m.userId" class="manager-row-item" style="display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px;">
                <div style="display: flex; flex-direction: column; gap: 2px;">
                  <strong style="font-size: 14px; color: #1e293b;">{{ m.fullName }} · {{ m.eligible ? '可审批' : '已失效：账号停用或缺少审批权限' }}</strong>
                  <span style="font-size: 12px; color: #64748b;">账号：{{ m.username }} · 部门：{{ m.departmentName }} · 工号：{{ m.identityNumber }}</span>
                </div>
                <button class="text-button" style="color: #ef4444;" :disabled="busy" @click="handleRemoveManager(m.userId)">
                  解除指定
                </button>
              </li>
            </ul>
          </div>

          <!-- Add Reviewer -->
          <div class="add-manager-box" style="padding: 14px; background: #f1f5f9; border-radius: 6px; display: flex; flex-direction: column; gap: 10px;">
            <h4 style="font-size: 13px; font-weight: 600; color: #334155;">添加新的责任审批人</h4>
            <div style="display: flex; gap: 10px; align-items: center;">
              <div style="flex: 1;">
                <BaseSelect
                  v-model="selectedCandidateId"
                  :options="[
                    { value: '', label: '从具备借用审批权限的人员中选择…' },
                    ...candidateOptions
                  ]"
                />
              </div>
              <button class="button primary compact" :disabled="!selectedCandidateId || busy" @click="handleAddManager">
                添加指定
              </button>
            </div>
          </div>
        </div>

        <div class="modal-footer" style="margin-top: 16px;">
          <button class="button compact" @click="showManagerModal = false">
            完成并关闭
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reservation-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.reservation-nav-tabs {
  margin-top: -6px;
  margin-bottom: 6px;
}

/* Venue Catalog */
.venue-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.category-pills {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #f1f5f9;
  padding: 3px;
  border-radius: 9999px;
  border: 1px solid #e2e8f0;
}

.category-pills button {
  position: relative;
  z-index: 1;
  padding: 6px 15px;
  border-radius: 9999px;
  border: 0;
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  transition: color 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  outline: none !important;
}

.category-pills button:hover:not(.active) {
  color: #0f172a;
}

.category-pills button:focus:not(:focus-visible) {
  outline: none !important;
  box-shadow: none !important;
}

.category-pills button.active {
  color: #ffffff;
  font-weight: 600;
  background: transparent !important;
}

.category-pill-indicator {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 0;
  background: var(--brand, #244b78);
  border-radius: 9999px;
  box-shadow: 0 1px 3px rgba(36, 75, 120, 0.28);
  pointer-events: none;
  transition: transform 0.28s cubic-bezier(0.2, 0.85, 0.32, 1.05),
              width 0.28s cubic-bezier(0.2, 0.85, 0.32, 1.05);
  will-change: transform, width;
  z-index: 0;
}

.venue-cards-grid.is-loading,
.table-scroll.is-loading {
  opacity: 0.65;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.search-input-group {
  display: flex;
  align-items: center;
  position: relative;
  gap: 8px;
}

.search-input-group input {
  padding-left: 32px;
  min-width: 260px;
}

.search-icon {
  position: absolute;
  left: 10px;
  color: #94a3b8;
}

.filter-tag-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f1f5f9;
  padding: 8px 14px;
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 16px;
}

.venue-cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 18px;
}

.venue-card {
  background: #ffffff;
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 8px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.venue-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}

.venue-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.venue-category-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
}

.venue-category-badge.venue {
  background: #eff6ff;
  color: #2563eb;
}

.venue-category-badge.equipment {
  background: #fef3c7;
  color: #d97706;
}

.venue-code {
  font-size: 11px;
  color: #94a3b8;
  font-family: monospace;
}

.venue-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text, #1e293b);
}

.venue-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #64748b;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.venue-equipment-box,
.venue-rules-box {
  background: #f8fafc;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
}

.box-label {
  font-weight: 600;
  color: #475569;
  display: inline-block;
  margin-bottom: 2px;
}

.venue-equipment-box p,
.venue-rules-box p {
  margin: 0;
  color: #64748b;
  line-height: 1.5;
}

.venue-card-actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px dashed var(--line, #e2e8f0);
}

/* Reservation Table */
.time-range-cell {
  display: flex;
  flex-direction: column;
}

.table-action-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.cancel-text-btn {
  color: #ef4444;
}

/* Reservation Details Drawer */
.reservation-detail-panel {
  border-top: 3px solid var(--brand, #1e3a8a);
}

.detail-header-status {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* Access Pass (入场凭证) */
.access-pass-card {
  position: relative;
  background: linear-gradient(135deg, #f0fdf4 0%, #ffffff 100%);
  border: 2px solid #86efac;
  border-radius: 12px;
  padding: 22px;
  margin: 16px 0 24px;
  box-shadow: 0 4px 16px rgba(16, 185, 129, 0.08);
  overflow: hidden;
}

.pass-watermark {
  position: absolute;
  right: -20px;
  bottom: -20px;
  font-size: 54px;
  font-weight: 900;
  color: rgba(16, 185, 129, 0.05);
  pointer-events: none;
  user-select: none;
  white-space: nowrap;
}

.pass-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #bbf7d0;
  padding-bottom: 14px;
  margin-bottom: 16px;
}

.pass-badge {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #15803d;
}

.pass-badge h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.pass-badge small {
  font-size: 11px;
  color: #166534;
}

.pass-number {
  text-align: right;
  display: flex;
  flex-direction: column;
}

.pass-number span {
  font-size: 10px;
  color: #65a30d;
}

.pass-number strong {
  font-family: monospace;
  font-size: 13px;
  color: #15803d;
}

.pass-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 20px;
}

.pass-grid-item {
  display: flex;
  flex-direction: column;
}

.pass-wide {
  grid-column: 1 / -1;
}

.pass-grid-item .label {
  font-size: 11px;
  color: #4b5563;
  margin-bottom: 2px;
}

.pass-grid-item .val {
  font-size: 14px;
  color: #111827;
  font-weight: 600;
}

.pass-grid-item .time-val {
  color: #15803d;
}

.pass-footer {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed #bbf7d0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.pass-instructions p {
  margin: 0;
  font-size: 12px;
  color: #374151;
  line-height: 1.6;
}

/* Review Box */
.reservation-review-box {
  background: #f8fafc;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 16px;
  margin: 18px 0;
}

.reservation-review-box h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 0;
  margin-bottom: 12px;
  font-size: 15px;
}

.reservation-review-box .danger {
  color: #dc2626;
  border-color: #fca5a5;
}

.reservation-timeline-wrap {
  margin-top: 24px;
}

/* Modals */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(2px);
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.modal-card {
  background: white;
  border-radius: 12px;
  width: 100%;
  max-width: 680px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 12px;
}

.modal-header h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 17px;
}

.close-icon-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  color: #64748b;
  padding: 4px;
  border-radius: 4px;
}

.close-icon-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.form-grid-two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.time-selection-section {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.time-inputs-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.quick-chips-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.chips-label {
  color: #64748b;
}

.chip-btn {
  background: white;
  border: 1px solid #cbd5e1;
  border-radius: 14px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
  color: #334155;
  transition: all 0.15s ease;
}

.chip-btn:hover {
  border-color: var(--brand, #1e3a8a);
  color: var(--brand, #1e3a8a);
}

.conflict-check-banner {
  margin-top: 4px;
}

.checking-state {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}

.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.conflict-alert {
  display: flex;
  gap: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 12px;
}

.conflict-slot-list {
  margin: 4px 0 0;
  padding-left: 18px;
  list-style: disc;
}

.conflict-clear {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #15803d;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
}

/* Schedule Modal */
.schedule-controls {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.schedule-slots-timeline {
  list-style: none;
  padding: 0;
  margin: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.schedule-slot-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8fafc;
  border-left: 3px solid #f59e0b;
  padding: 10px 14px;
  border-radius: 0 6px 6px 0;
}

.slot-time {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.slot-status-badge {
  font-size: 11px;
  color: #d97706;
}

.slot-desc h4 {
  margin: 0;
  font-size: 14px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  border-top: 1px solid #e2e8f0;
  padding-top: 14px;
}

.pass-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; flex-shrink: 0; }
.pass-actions .button { display: inline-flex; align-items: center; gap: 6px; text-decoration: none; white-space: nowrap; }
.pass-instructions .pass-export-note { color: #627b6b; font-size: 11px; margin-top: 8px; }

@media (max-width: 640px) {
  .pass-footer { flex-direction: column; align-items: stretch; gap: 14px; }
  .pass-actions { justify-content: flex-end; }
  .form-grid-two,
  .time-inputs-row,
  .pass-grid {
    grid-template-columns: 1fr;
  }
  .category-pills {
    flex-wrap: wrap;
  }
  .search-input-group input {
    min-width: 160px;
  }
}

/* Venue Management Tab Styles */
.manage-header-desc {
  flex: 1;
  min-width: 280px;
}
.manage-header-desc p {
  font-size: 13px;
  line-height: 1.6;
}
.badge-group {
  display: flex;
  align-items: center;
  gap: 6px;
}
.venue-status-tag {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 4px;
  font-weight: 500;
}
.venue-status-tag.enabled {
  background: #dcfce7;
  color: #15803d;
}
.venue-status-tag.disabled {
  background: #fee2e2;
  color: #b91c1c;
}
.manage-card.disabled {
  opacity: 0.85;
  background: #fafafa;
}
.venue-managers-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.managers-box-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.no-manager-alert {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #b91c1c;
  font-size: 12px;
  background: #fef2f2;
  padding: 6px 8px;
  border-radius: 4px;
}
.manager-tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.manager-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  background: #e0e7ff;
  color: #3730a3;
  padding: 3px 8px;
  border-radius: 4px;
}
.manager-tag small {
  color: #4f46e5;
  font-size: 11px;
}
.manage-actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
}
.manager-tag.is-inactive {
  background: #f1f5f9;
  color: #64748b;
}
.manager-tag.is-inactive small {
  color: inherit;
}
.warning-btn {
  color: #b45309 !important;
  border-color: #fde68a !important;
  background: #fffbeb !important;
}
.warning-btn:hover {
  background: #fef3c7 !important;
}
.venue-edit-modal {
  max-width: 580px;
}
.manager-modal-card {
  max-width: 640px;
}
</style>

<style>
/* Print rules must escape Vue's scoped selectors and remove other content from pagination. */
@media print {
  body:has(#venue-access-pass) #app *:not(:has(#venue-access-pass)):not(#venue-access-pass):not(#venue-access-pass *) {
    display: none !important;
  }
  body:has(#venue-access-pass) :is(#app, .portal-shell, .portal-main, .reservation-page, .reservation-detail-panel) {
    display: block !important;
    position: static !important;
    width: auto !important;
    max-width: none !important;
    padding: 0 !important;
    margin: 0 !important;
    min-height: 0 !important;
    overflow: visible !important;
  }
  #venue-access-pass {
    position: static !important;
    width: auto !important;
    margin: 0 !important;
    padding: 8mm !important;
    border: 1px solid #829b8d !important;
    box-shadow: none !important;
    background: white !important;
    color: #172b25 !important;
    overflow: visible !important;
  }
  #venue-access-pass .pass-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  #venue-access-pass :is(.pass-header, .pass-grid-item, .pass-footer) { break-inside: avoid; }
  #venue-access-pass :is(.no-print, .pass-watermark) { display: none !important; }
}
</style>
