import { campusExploreReturn, campusPlaceId, campusServiceOrigin } from '../lib/campusServiceNavigation'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, useTemplateRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser } from '../stores/auth'
import { api, ApiError, errorMessage } from '../lib/api'
import { formatDate as formatIsoDate, formatDateTime } from '../lib/format'
import { createReservationConflictChecker, reservationSearchSelection } from '../lib/reservationSearch'
import type { ReservationSearchSelection } from '../lib/reservationSearch'
import { useEditProtection } from '../composables/useEditProtection'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import {
  type ConflictResult,
  type ReservationDetail,
  type ReservationItem,
  type TimeSlot,
  type VenueSummary,
  type VenueManagementItem,
  type VenueManagerItem,
  type VenueUpdateCommand,
  type VenueCreationCatalog,
  addVenueManager,
  cancelReservation,
  checkConflict,
  getDetail,
  getVenue,
  getReviewerCandidates,
  getVenueSchedule,
  listMine,
  listReview,
  listVenues,
  listVenuesForManagement,
  removeVenueManager,
  reservationStatuses,
  reviewReservation,
  recordReservationUsage,
  saveDraft,
  submitReservation,
  updateVenue,
  withdrawReservation,
} from '../reservations'

/** State and effects for ReservationPage; visual markup lives in the page and its components. */
export function useReservationWorkspace() {
  const route = useRoute()
  const router = useRouter()
  const exploreOrigin = computed(() => campusServiceOrigin(route.query))
  const exploreReturn = computed(() => (exploreOrigin.value ? campusExploreReturn(exploreOrigin.value) : null))
  const venueFilter = computed(() => campusPlaceId(route.query.venue))

  // Review mode determined by URL path (/student-affairs vs /portal)
  const reviewMode = computed(() => route.path.startsWith('/student-affairs'))
  const canApply = computed(() => currentUser.value?.permissions.includes('reservation:apply'))
  const canReview = computed(() => currentUser.value?.permissions.includes('reservation:review'))
  const canManage = computed(() => currentUser.value?.permissions.includes('reservation:manage'))

  // Active Tab: 'venues' (browse), 'mine' (student applications), 'review' (staff approvals), 'manage' (venue management)
  const activeTab = ref<'venues' | 'mine' | 'review' | 'manage'>(
    route.query.tab === 'manage' && canManage.value
      ? 'manage'
      : reviewMode.value
        ? canReview.value
          ? 'review'
          : canManage.value
            ? 'manage'
            : 'review'
        : 'venues',
  )
  const reservationNavRef = useTemplateRef<HTMLElement | null>('reservationNavRef')
  const { indicatorStyle: reservationIndicatorStyle } = useSlidingIndicator(reservationNavRef, () => activeTab.value, {
    activeSelector: '.learning-tabs button.active, .learning-tabs button.selected',
  })
  const categoryPillsRef = useTemplateRef<HTMLElement | null>('categoryPillsRef')
  const venueCategory = ref('')
  const { indicatorStyle: categoryPillStyle } = useSlidingIndicator(categoryPillsRef, () => venueCategory.value)

  // Data states
  const venues = ref<VenueSummary[]>([])
  const managedVenues = ref<VenueManagementItem[]>([])
  const creationCatalog = ref<VenueCreationCatalog | null>(null)
  const showVenueCreate = ref(false)
  const roomLinkVenue = ref<VenueManagementItem | null>(null)
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
  const bookingDialog = useTemplateRef<HTMLDialogElement>('bookingDialog')
  watch(showBookingModal, async open => {
    if (!open) {
      bookingDialog.value?.close()
      return
    }
    await nextTick()
    if (showBookingModal.value && bookingDialog.value && !bookingDialog.value.open) bookingDialog.value.showModal()
  })
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
  const reviewUpdateNotice = ref('')

  // Confirmation dialog
  const dialog = useTemplateRef<HTMLDialogElement | null>('dialog')
  const cancelButton = useTemplateRef<HTMLButtonElement | null>('cancelButton')
  const { confirmMessage, confirmKind, dialogComment, ask, finish, allowLeave } = useEditProtection(
    busy,
    isFormDirty,
    dialog,
    cancelButton,
  )

  // Formatting helpers
  function formatTime(val?: string) {
    return formatDateTime(val)
  }

  function formatHour(val?: string) {
    if (!val) return ''
    return val.slice(11, 16)
  }

  function formatDate(val?: string) {
    return formatIsoDate(val)
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
  function initBookingForm(venue?: VenueSummary, conditions?: ReservationSearchSelection) {
    const tomorrow = new Date()
    tomorrow.setDate(tomorrow.getDate() + 1)
    const dateStr = tomorrow.toISOString().slice(0, 10)

    editingReservationId.value = null
    bookingForm.requestKey = crypto.randomUUID()
    bookingForm.venueId = venue?.id ?? (venues.value[0]?.id || 1)
    bookingForm.activityTheme = ''
    bookingForm.activityType = 'ACADEMIC'
    bookingForm.expectedParticipants = conditions?.participants ?? (venue?.capacity ? Math.min(venue.capacity, 30) : 30)
    bookingForm.borrowEquipment = ''
    bookingForm.startsAt = conditions?.startsAt ?? `${dateStr}T14:30`
    bookingForm.endsAt = conditions?.endsAt ?? `${dateStr}T17:00`
    bookingForm.contactPhone = ''

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

  // Recheck the current form snapshot; stale or failed requests cannot leave an availability claim.
  const checkCurrentConflict = createReservationConflictChecker(checkConflict, state => {
    conflictCheck.value = state.result
    checkingConflict.value = state.checking
  })
  function runConflictCheck() {
    return checkCurrentConflict(
      !bookingForm.venueId || !bookingForm.startsAt || !bookingForm.endsAt
        ? null
        : {
            venueId: bookingForm.venueId,
            startsAt: `${bookingForm.startsAt}:00`,
            endsAt: `${bookingForm.endsAt}:00`,
            excludeId: editingReservationId.value ?? undefined,
          },
    )
  }
  let lastSearchSelection = ''
  async function applySearchSelection() {
    const conditions = reservationSearchSelection(route.fullPath)
    if (!conditions || reviewMode.value || lastSearchSelection === route.fullPath) return
    const path = route.fullPath
    lastSearchSelection = path
    try {
      const venue = await getVenue(conditions.venueId)
      if (route.fullPath !== path) return
      if (!venue.enabled) {
        error.value = '该资源已暂停开放，请刷新后重新选择。'
        return
      }
      const index = venues.value.findIndex(item => item.id === venue.id)
      if (index < 0) venues.value.unshift(venue)
      else venues.value[index] = venue
      activeTab.value = 'venues'
      initBookingForm(venue, conditions)
    } catch (cause) {
      if (route.fullPath === path) error.value = errorMessage(cause)
    }
  }
  async function closeBookingForm() {
    if (busy.value || !(await allowLeave())) return
    showBookingModal.value = false
    lastSearchSelection = ''
    void checkCurrentConflict(null)
    if (route.query.resource) {
      const query = { ...route.query }
      for (const key of ['resource', 'starts', 'ends', 'people']) delete query[key]
      await router.replace({ query })
    }
  }

  // Load Venue Catalog
  let venueRequest = 0
  async function loadVenues() {
    const request = ++venueRequest
    loading.value = true
    error.value = ''
    venues.value = []
    try {
      const result = await listVenues({
        category: venueCategory.value || undefined,
        placeId: venueFilter.value || undefined,
        keyword: venueKeyword.value || undefined,
      })
      if (request === venueRequest && activeTab.value === 'venues') venues.value = result
    } catch (cause) {
      if (request === venueRequest && activeTab.value === 'venues') error.value = errorMessage(cause)
    } finally {
      if (request === venueRequest && activeTab.value === 'venues') loading.value = false
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
  let detailRequest = 0
  function closeReservationDetail() {
    detailRequest++
    selectedReservation.value = null
    void router.replace({ query: { ...route.query, id: undefined } })
  }
  async function chooseReservation(id: number) {
    const request = ++detailRequest
    loading.value = true
    error.value = ''
    selectedReservation.value = null
    reviewUpdateNotice.value = ''
    try {
      const detail = await getDetail(id)
      if (request !== detailRequest) return
      selectedReservation.value = detail
      reviewComment.value = ''
      await router.replace({ query: { ...route.query, id: String(id), tab: activeTab.value } })
    } catch (cause) {
      if (request === detailRequest) error.value = errorMessage(cause)
    } finally {
      if (request === detailRequest) loading.value = false
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
    if (!(await ask(`确认撤回预约申请 #${id}？撤回后原审核待办关闭，历史记录予以留存。`, 'withdraw'))) return
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
    const staffCancel = activeTab.value === 'review' && !isOwnReservation.value
    const reason = staffCancel ? reviewComment.value.trim() : dialogComment.value.trim() || '申请人主动取消'
    if (staffCancel && !reason) {
      error.value = '请填写取消原因'
      return
    }
    if (!(await ask(`确认取消预约 #${id}？取消后释放场地时段，该操作无法撤销。`, 'cancel'))) return
    busy.value = true
    error.value = ''
    success.value = ''
    try {
      await cancelReservation(id, staffCancel ? reason : dialogComment.value || '申请人主动取消')
      if (staffCancel) reviewComment.value = ''
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
    const selected = selectedReservation.value
    if (!selected) return
    const label =
      action === 'APPROVE' ? '同意借用并签发通行凭证' : action === 'RETURN' ? '退回修改材料' : '驳回借用申请'
    if (!reviewComment.value.trim()) {
      error.value = '请填写审核意见或理由'
      return
    }
    const command = { version: selected.version, action, comment: reviewComment.value.trim() }
    if (!(await ask(`确认对申请 #${selected.id} 执行“${label}”？意见将记录在时间线并通知申请人。`, 'decision'))) return
    busy.value = true
    error.value = ''
    success.value = ''
    try {
      await reviewReservation(selected.id, command)
      reviewComment.value = ''
      success.value = `处理已完成：已执行【${label}】。`
      await loadReservations()
      await chooseReservation(selected.id)
    } catch (cause) {
      const message = errorMessage(cause)
      if (cause instanceof ApiError && cause.code === 'RESERVATION_VERSION_CHANGED') {
        reviewComment.value = ''
        await loadReservations()
        await chooseReservation(selected.id)
        reviewUpdateNotice.value = selectedReservation.value
          ? '申请已更新，已重新读取最新资料。本次决定未执行，请核对主题、人数、时段及材料，重新填写审核意见后再作决定。'
          : '申请已更新，本次决定未执行。最新资料暂时无法读取，请重新打开申请后核对。'
      }
      error.value = message
    } finally {
      busy.value = false
    }
  }

  async function handleUsage(action: 'START' | 'COMPLETE') {
    const selected = selectedReservation.value
    if (!selected || !reviewComment.value.trim()) {
      error.value = '请填写现场核对说明'
      return
    }
    const comment = reviewComment.value.trim()
    const label = action === 'START' ? '登记开始使用' : '确认使用结束并核销'
    if (!(await ask(`确认对预约 #${selected.id} 执行“${label}”？请核对现场使用及设备情况。`, 'decision'))) return
    busy.value = true
    error.value = ''
    success.value = ''
    try {
      await recordReservationUsage(selected.id, selected.version, action, comment)
      reviewComment.value = ''
      await loadReservations()
      await chooseReservation(selected.id)
      success.value = `已${label}，申请人已收到消息。`
    } catch (cause) {
      error.value = errorMessage(cause)
    } finally {
      busy.value = false
    }
  }

  // Management Methods
  const filteredManagedVenues = computed(() => {
    const kw = manageKeyword.value.trim().toLowerCase()
    if (!kw) return managedVenues.value
    return managedVenues.value.filter(
      v =>
        v.name.toLowerCase().includes(kw) ||
        v.code.toLowerCase().includes(kw) ||
        (v.buildingName && v.buildingName.toLowerCase().includes(kw)) ||
        (v.locationDetail && v.locationDetail.toLowerCase().includes(kw)),
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
      const [venueList, candidates, catalog] = await Promise.all([
        listVenuesForManagement(),
        getReviewerCandidates(),
        api<VenueCreationCatalog>('/reservations/venues/catalog'),
      ])
      managedVenues.value = venueList
      reviewerCandidates.value = candidates
      creationCatalog.value = catalog
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

  async function venueCreated(venue: VenueSummary) {
    showVenueCreate.value = false
    await loadManagementData()
    manageKeyword.value = venue.code
    success.value = `${venue.campusName} · ${venue.name}已创建${venue.enabled ? '并开放预约' : '，可继续配置后开放'}。`
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
    if (
      !(await ask(
        '确认解除该负责人的指派？待审批、待使用及使用中的预约都需要接任人员继续办理，请先完成交接。',
        'decision',
      ))
    )
      return
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
    if (!(await allowLeave())) return
    activeTab.value = tab
    selectedReservation.value = null
    currentPage.value = 1
    filterStatus.value = tab === 'review' ? 'SUBMITTED' : ''
    await router.replace({
      query: { ...route.query, id: undefined, tab, status: tab === 'review' ? 'SUBMITTED' : undefined },
    })
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
    } else if (route.query.tab === 'mine' || (!reviewMode.value && linkedReservationId() !== null)) {
      activeTab.value = 'mine'
      const status = String(route.query.status ?? '')
      filterStatus.value = status in reservationStatuses ? status : ''
    } else if (route.query.venue) {
      activeTab.value = 'venues'
    }

    await loadVenues()
    if (activeTab.value === 'manage') {
      await loadManagementData()
    } else if (activeTab.value !== 'venues') {
      await loadReservations()
    }

    await applySearchSelection()

    // Pre-load specific reservation if id passed in route query
    if (route.query.id) {
      const id = linkedReservationId()
      if (id !== null) {
        await chooseReservation(id)
        if (!reviewMode.value && activeTab.value !== 'manage') activeTab.value = 'mine'
      }
    }
  })

  watch(
    () => [bookingForm.venueId, bookingForm.startsAt, bookingForm.endsAt],
    () => {
      void runConflictCheck()
    },
  )

  function linkedReservationId(): number | null {
    const value = route.query.id
    return typeof value === 'string' && /^[1-9]\d{0,14}$/.test(value) ? Number(value) : null
  }
  let reservationNavigation = 0
  watch(
    () => [route.query.id, route.query.tab, route.query.status],
    async () => {
      const navigation = ++reservationNavigation
      const id = linkedReservationId()
      if (id !== null && selectedReservation.value?.id !== id) {
        detailRequest++
        if (!reviewMode.value) activeTab.value = 'mine'
        const status = String(route.query.status ?? '')
        filterStatus.value = status in reservationStatuses ? status : reviewMode.value ? 'SUBMITTED' : ''
        await loadReservations()
        if (navigation !== reservationNavigation) return
        await chooseReservation(id)
        return
      }
      if (id === null && !route.query.id) {
        detailRequest++
        selectedReservation.value = null
      }
      if (!reviewMode.value && route.query.tab === 'mine') {
        const status = String(route.query.status ?? '')
        const nextStatus = status in reservationStatuses ? status : ''
        if (activeTab.value !== 'mine' || filterStatus.value !== nextStatus) {
          activeTab.value = 'mine'
          filterStatus.value = nextStatus
          currentPage.value = 1
          await loadReservations()
        }
      }
    },
  )
  onBeforeUnmount(() => {
    venueRequest++
    bookingDialog.value?.close()
    detailRequest++
    reservationNavigation++
    void checkCurrentConflict(null)
  })
  watch(
    () => route.fullPath,
    () => {
      if (!reservationSearchSelection(route.fullPath)) lastSearchSelection = ''
      void applySearchSelection()
    },
  )

  watch(
    () => route.query.venue,
    () => {
      if (activeTab.value === 'venues') void loadVenues()
    },
  )

  return {
    activeTab,
    applyQuickTime,
    bookingForm,
    busy,
    canApply,
    canManage,
    canReview,
    candidateOptions,
    categoryPillStyle,
    checkingConflict,
    chooseReservation,
    closeBookingForm,
    closeReservationDetail,
    confirmKind,
    confirmMessage,
    conflictCheck,
    creationCatalog,
    dialogComment,
    editExistingReservation,
    editReservation,
    editingReservationId,
    editingVenue,
    error,
    exploreOrigin,
    exploreReturn,
    fetchSchedule,
    filterStatus,
    filteredManagedVenues,
    finish,
    formatDate,
    formatHour,
    formatTime,
    handleAddManager,
    handleCancel,
    handleRemoveManager,
    handleReview,
    handleSaveDraft,
    handleSubmit,
    handleUsage,
    handleWithdraw,
    initBookingForm,
    isOwnReservation,
    loadManagementData,
    loadReservations,
    loadVenues,
    loading,
    manageKeyword,
    managingVenue,
    openManagerModal,
    openSchedule,
    openVenueEdit,
    reservationIndicatorStyle,
    reservations,
    reviewComment,
    reviewMode,
    reviewUpdateNotice,
    reviewerCandidates,
    roomLinkVenue,
    route,
    router,
    saveVenueEdit,
    scheduleDate,
    scheduleLoading,
    scheduleSlots,
    scheduleVenue,
    selectedCandidateId,
    selectedReservation,
    showBookingModal,
    showManagerModal,
    showScheduleModal,
    showVenueCreate,
    showVenueEditModal,
    success,
    switchTab,
    toggleVenueEnabled,
    totalItems,
    venueCategory,
    venueCreated,
    venueEditForm,
    venueFilter,
    venueKeyword,
    venues,
  }
}
