import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, useTemplateRef } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { formatDateTime } from '../lib/format'
import {
  buildRepairStepperOverview,
  buildRepairWorkflowSteps,
  repairStepTime as stepTime,
} from '../lib/repairWorkflowSteps'
import { repairLocationQuery, type RepairPublicLocation } from '../repairSettings'
import { currentUser } from '../stores/auth'
import type { Paged } from '../foundation'
import {
  repairActions,
  repairCancellationMutation,
  repairCategories,
  repairDraft,
  repairForm,
  repairOperation,
  repairStatuses,
} from '../repairs'
import type { RepairDetail, RepairHistory, RepairImage, RepairItem, RepairSummary } from '../repairs'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import { useToast } from '../composables/useToast'

/** State and effects for RepairPage; visual markup lives in the page and its components. */
export function useRepairWorkspace() {
  const route = useRoute(),
    router = useRouter()
  const toast = useToast()
  const mode = computed(() =>
    route.path.endsWith('/dispatch') ? 'dispatch' : route.path.endsWith('/tasks') ? 'work' : 'mine',
  )
  const root = computed(() => '/repairs/' + mode.value)
  const title = computed(() =>
    mode.value === 'mine' ? '我的校园报修' : mode.value === 'dispatch' ? '后勤分诊与派单' : '我的维修任务',
  )
  const intro = computed(() =>
    mode.value === 'mine'
      ? '记录校园设施故障，跟进实际处理进度，维修后由你验收。'
      : mode.value === 'dispatch'
        ? '核对负责范围内的故障，补充资料、分配人员并跟进处理。'
        : '查看当前分配给你的任务，记录进展并提交维修结果。',
  )
  const list = ref<Paged<RepairItem>>({ items: [], total: 0, page: 1, pageSize: 20 })
  const records = ref<Paged<RepairHistory>>({ items: [], total: 0, page: 1, pageSize: 10 })
  const selected = ref<RepairDetail | null>(null),
    summary = ref<RepairSummary | null>(null)
  const loading = ref(true),
    busy = ref(false),
    editing = ref(false),
    error = ref(''),
    success = ref(''),
    status = ref('')
  const cancellationFilter = ref('')
  const filtersOpen = ref(false)
  const mobileDetailOpen = ref(!!route.query.id || route.query.new === '1')
  const operationPanel = useTemplateRef<HTMLElement | null>('operationPanel')
  let listScrollPosition = 0
  const quickFilters = computed(() =>
    mode.value === 'mine'
      ? [
          { value: '', label: '全部工单' },
          { value: 'RETURNED', label: '待补充' },
          { value: 'AWAITING_CONFIRMATION', label: '待验收' },
          { value: 'COMPLETED', label: '已完成' },
        ]
      : mode.value === 'dispatch'
        ? [
            { value: '', label: '全部工单' },
            { value: 'SUBMITTED', label: '待分诊' },
            { value: 'ASSIGNED', label: '待接单' },
            { value: 'IN_PROGRESS', label: '维修中' },
          ]
        : [
            { value: '', label: '全部任务' },
            { value: 'ASSIGNED', label: '待接单' },
            { value: 'IN_PROGRESS', label: '维修中' },
            { value: 'AWAITING_CONFIRMATION', label: '待验收' },
          ],
  )
  const queueItems = computed(() => {
    const s = summary.value
    if (!s) return []
    const items =
      mode.value === 'mine'
        ? [
            { label: '待补充资料', count: s.mineReturned, status: 'RETURNED', cancellation: '' },
            { label: '待你验收', count: s.mineAwaitingConfirmation, status: 'AWAITING_CONFIRMATION', cancellation: '' },
            { label: '取消待处理', count: s.mineCancellationPending, status: '', cancellation: 'true' },
          ]
        : mode.value === 'dispatch'
          ? [
              { label: '待分诊', count: s.dispatchPending, status: 'SUBMITTED', cancellation: '' },
              { label: '取消待处理', count: s.dispatchCancellationPending, status: '', cancellation: 'true' },
            ]
          : [
              { label: '待你接单', count: s.workAssigned, status: 'ASSIGNED', cancellation: '' },
              { label: '维修中', count: s.workInProgress, status: 'IN_PROGRESS', cancellation: '' },
            ]
    return items.filter(item => item.count > 0)
  })
  const isClosed = computed(
    () => !!selected.value && ['COMPLETED', 'REJECTED', 'WITHDRAWN', 'CANCELLED'].includes(selected.value.status),
  )
  const operationActions = computed(
    () =>
      selected.value?.allowedActions.filter(
        a => !a.startsWith('upload-') && !a.startsWith('remove-') && a !== 'edit',
      ) ?? [],
  )
  const primaryAction = computed(() =>
    [
      'submit',
      'assign',
      'accept',
      'complete',
      'confirm',
      'evaluation',
      'approve-cancellation',
      'reassign',
      'progress',
    ].find(action => operationActions.value.includes(action)),
  )
  const currentTask = computed(() => {
    if (can('edit'))
      return { label: selected.value?.status === 'RETURNED' ? '补充报修资料' : '编辑报修资料', edit: true }
    if (can('confirm')) return { label: '办理维修验收', edit: false }
    if (can('assign') || can('reassign')) return { label: can('assign') ? '分配维修人员' : '调整派工', edit: false }
    if (can('accept')) return { label: '办理接单', edit: false }
    if (can('complete')) return { label: '记录维修与结果', edit: false }
    if (can('evaluation')) return { label: '评价本次服务', edit: false }
    if (can('approve-cancellation')) return { label: '处理取消申请', edit: false }
    if (can('withdraw-cancellation')) return { label: '查看取消申请', edit: false }
    return null
  })
  const campuses = ref<{ id: number; name: string }[]>([]),
    campusId = ref(''),
    locations = ref<Paged<{ id: number; name: string; campusName: string; detailHint: string; provenance: string }>>({
      items: [],
      total: 0,
      page: 1,
      pageSize: 100,
    })
  const technicians = ref<{ id: number; name: string; departmentName: string | null }[]>([]),
    technicianId = ref(''),
    comment = ref(''),
    stars = ref('5'),
    priority = ref('NORMAL')
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
    {
      value: '',
      label: locations.value.items.length ? '请选择开放地点' : loading.value ? '正在读取地点…' : '当前校区暂无可选地点',
    },
    ...(form.locationId && !locations.value.items.some(l => String(l.id) === form.locationId)
      ? [
          {
            value: form.locationId,
            label: `${selected.value?.locationName || prefilledLocation.value?.name || '当前选中地点'}（当前选择）`,
          },
        ]
      : []),
    ...locations.value.items.map(l => ({
      value: String(l.id),
      label: `${l.name}${l.provenance === 'DEMO' ? ' · 演示' : ''}`,
    })),
  ])
  const selectedLocation = computed(() => (locations.value?.items ?? []).find(l => String(l.id) === form.locationId))
  const selectedLocationHint = computed(
    () => selectedLocation.value?.detailHint || prefilledLocation.value?.detailHint || '',
  )
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
  const file = ref<File | null>(null),
    fileInput = useTemplateRef<HTMLInputElement | null>('fileInput'),
    purpose = ref('FAULT')
  interface PendingFaultFile {
    file: File
    preview: string
  }
  const pendingFaultFiles = ref<PendingFaultFile[]>([])
  const previewModalUrl = ref<string | null>(null)
  const imagePreviewDialog = useTemplateRef<HTMLDialogElement | null>('imagePreviewDialog')
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
  const maintenanceImages = computed(
    () => selected.value?.images?.filter(img => img.purpose === 'PROGRESS' || img.purpose === 'RESULT') ?? [],
  )

  function historyActorRole(record: RepairHistory): {
    label: string
    class: 'reporter' | 'technician' | 'dispatcher' | 'other'
  } {
    if (!selected.value) return { label: '经办人', class: 'other' }
    if (record.actorName === selected.value.reporterName) {
      return { label: '报修人', class: 'reporter' }
    }
    if (selected.value.technicianName && record.actorName === selected.value.technicianName) {
      return { label: '维修人员', class: 'technician' }
    }
    if (
      ['assign', 'reassign', 'return', 'reject', 'approve-cancellation', 'reject-cancellation'].includes(record.action)
    ) {
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
  let baseline = '',
    sequence = 0,
    locationSequence = 0
  const dirty = computed(
    () =>
      (editing.value && JSON.stringify(form) !== baseline) ||
      pendingFaultFiles.value.length > 0 ||
      !!comment.value.trim() ||
      !!file.value ||
      !!technicianId.value ||
      (!!selected.value && priority.value !== selected.value.priority),
  )
  const historyView = computed(() => route.query.tab === 'history')
  const repairNavRef = useTemplateRef<HTMLElement | null>('repairNavRef')
  const { indicatorStyle: repairIndicatorStyle } = useSlidingIndicator(repairNavRef, () => historyView.value)
  const dialog = useTemplateRef<HTMLDialogElement | null>('dialog'),
    cancelButton = useTemplateRef<HTMLButtonElement | null>('cancelButton'),
    confirmation = ref('')
  let resolveConfirmation: ((accept: boolean) => void) | null = null
  const alertDialog = useTemplateRef<HTMLDialogElement | null>('alertDialog')
  const alertOkButton = useTemplateRef<HTMLButtonElement | null>('alertOkButton')
  const commentTextareaRef = useTemplateRef<HTMLTextAreaElement | null>('commentTextareaRef')
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
    onDismiss?: () => void,
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
  function can(action: string) {
    return !!selected.value?.allowedActions.includes(action)
  }
  function time(value: string | null) {
    return formatDateTime(value, '尚未发生')
  }

  const workflowSteps = computed(() => buildRepairWorkflowSteps(selected.value))
  const stepperOverview = computed(() => buildRepairStepperOverview(selected.value, mode.value))

  function imageUrl(image: RepairImage) {
    return `/api${root.value}/${selected.value?.id}/attachments/${image.id}`
  }
  async function ask(message: string) {
    confirmation.value = message
    return new Promise<boolean>(resolve => {
      resolveConfirmation = resolve
      void nextTick(() => {
        dialog.value?.showModal()
        cancelButton.value?.focus()
      })
    })
  }
  function finish(accept: boolean) {
    dialog.value?.close()
    const resolve = resolveConfirmation
    resolveConfirmation = null
    resolve?.(accept)
  }
  async function allowLeave() {
    if (!currentUser.value) return true
    if (busy.value) {
      promptAlert('正在保存，请等待完成后再离开。', '操作提示', 'warning')
      return false
    }
    return !dirty.value || (await ask('还有未保存的资料、处理说明或待上传图片。离开将放弃这些输入，是否继续？'))
  }
  function clearInputs() {
    editing.value = false
    comment.value = ''
    technicianId.value = ''
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    clearPendingFaultFiles()
  }
  async function readLocations(page = 1) {
    const request = ++locationSequence
    const params = new URLSearchParams({ page: String(page), pageSize: '100' })
    if (campusId.value) params.set('campusId', campusId.value)
    const result = await api<typeof locations.value>('/repairs/locations?' + params)
    if (request === locationSequence) locations.value = result
  }
  async function locationPage(page: number) {
    try {
      await readLocations(page)
    } catch (cause) {
      error.value = errorMessage(cause)
    }
  }
  async function changeCampus() {
    form.locationId = ''
    await locationPage(1)
  }
  async function load() {
    const request = ++sequence
    loading.value = true
    error.value = ''
    clearInputs()
    if (!route.query.id || String(selected.value?.id) !== String(route.query.id)) {
      selected.value = null
      prefilledLocation.value = null
    }
    try {
      cancellationFilter.value = String(route.query.cancellationPending ?? '')
      status.value = String(
        route.query.status ?? (mode.value === 'dispatch' && cancellationFilter.value !== 'true' ? 'SUBMITTED' : ''),
      )
      const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
      if (status.value) params.set('status', status.value)
      if (cancellationFilter.value) params.set('cancellationPending', cancellationFilter.value)
      const [items, counts] = await Promise.all([
        api<typeof list.value>(root.value + '?' + params),
        api<RepairSummary>('/repairs/summary'),
      ])
      if (request !== sequence) return
      list.value = items
      summary.value = counts
      if (mode.value === 'mine' && !campuses.value.length) {
        const catalog = await api<{ campuses: typeof campuses.value }>('/repairs/catalog')
        if (request !== sequence) return
        campuses.value = catalog?.campuses ?? []
        campusId.value = String(
          campuses.value?.find(c => c.name === currentUser.value?.campusName)?.id ?? campuses.value[0]?.id ?? '',
        )
      }
      const queryId =
        typeof route.query.id === 'string'
          ? route.query.id
          : Array.isArray(route.query.id)
            ? String(route.query.id[0] ?? '')
            : ''
      const effectiveId = queryId || (!route.query.new && list.value.items.length ? String(list.value.items[0].id) : '')
      if (effectiveId) {
        if (!queryId) {
          await navigate({
            ...(route.query as Record<string, string>),
            id: effectiveId,
            tab: 'details',
            new: undefined,
          })
          return
        }
        const detail = await api<RepairDetail>(root.value + '/' + effectiveId)
        if (request !== sequence) return
        selected.value = detail
        priority.value = detail.priority
        purpose.value = mode.value === 'mine' ? 'FAULT' : 'RESULT'
        if (historyView.value) {
          const history = await api<typeof records.value>(
            `${root.value}/${detail.id}/history?page=${Math.max(1, Number(route.query.historyPage) || 1)}&pageSize=10`,
          )
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
        if (route.query.location !== undefined && locationId === null)
          throw new Error('地点链接无效，请从当前住宿重新进入。')
        if (locationId !== null) {
          const place = await api<RepairPublicLocation>('/repairs/locations/' + locationId)
          if (request !== sequence) return
          prefilledLocation.value = place
          campusId.value = String(place.campusId)
          form.locationId = String(place.id)
        }
        baseline = JSON.stringify(form)
        editing.value = true
        await readLocations()
      }
    } catch (cause) {
      if (request === sequence) error.value = errorMessage(cause)
    } finally {
      if (request === sequence) loading.value = false
    }
  }
  async function refresh() {
    if (await allowLeave()) {
      success.value = ''
      await load()
    }
  }
  async function navigate(query: Record<string, string | undefined>) {
    await router.replace({ query })
  }
  async function choose(item: RepairItem) {
    listScrollPosition = window.scrollY
    const previousId = route.query.id
    await navigate({ ...(route.query as Record<string, string>), id: String(item.id), tab: 'details', new: undefined })
    if (route.query.id !== String(item.id)) return
    mobileDetailOpen.value = true
    if (window.matchMedia('(max-width: 760px)').matches) {
      await nextTick()
      window.scrollTo({ top: 0 })
      if (previousId === route.query.id)
        document.querySelector<HTMLElement>('.repair-detail-title')?.focus({ preventScroll: true })
    }
  }
  async function newDraft() {
    await navigate({ new: '1' })
    if (route.query.new === '1') mobileDetailOpen.value = true
  }
  async function showList() {
    if (!(await allowLeave())) return
    clearInputs()
    if (route.query.new)
      await navigate({ status: status.value || undefined, cancellationPending: cancellationFilter.value || undefined })
    mobileDetailOpen.value = false
    await nextTick()
    if (window.matchMedia('(max-width: 760px)').matches) window.scrollTo({ top: listScrollPosition })
  }
  async function filterQueue(value: string, cancellation = '') {
    await navigate({ status: value, cancellationPending: cancellation || undefined, page: '1' })
    if (String(route.query.status ?? '') === value && String(route.query.cancellationPending ?? '') === cancellation)
      mobileDetailOpen.value = false
  }
  async function handleCurrentTask() {
    if (currentTask.value?.edit) {
      await edit()
      return
    }
    if (historyView.value) await navigate({ ...(route.query as Record<string, string>), tab: 'details' })
    await nextTick()
    operationPanel.value?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
      block: 'center',
    })
    operationPanel.value?.focus({ preventScroll: true })
  }
  async function search() {
    await navigate({ status: status.value, cancellationPending: cancellationFilter.value || undefined, page: '1' })
  }
  async function changeCancellationFilter() {
    if (cancellationFilter.value === 'true') status.value = ''
    await search()
  }
  async function page(number: number, history = false) {
    await navigate({ ...(route.query as Record<string, string>), [history ? 'historyPage' : 'page']: String(number) })
  }
  async function edit() {
    if (!(await allowLeave())) return
    clearPendingFaultFiles()
    Object.assign(form, repairForm(selected.value))
    baseline = JSON.stringify(form)
    editing.value = true
    campusId.value = String(selected.value?.campusId ?? campusId.value)
    try {
      await readLocations()
    } catch (cause) {
      error.value = errorMessage(cause)
    }
  }
  async function saved(result: RepairDetail, message: string) {
    clearInputs()
    baseline = JSON.stringify(form)
    busy.value = false
    if (mode.value === 'work' && !result.technicianId) await navigate({ status: 'ASSIGNED' })
    else if (route.query.id === String(result.id) && !route.query.new) await load()
    else await navigate({ id: String(result.id), tab: 'details', status: status.value })
    success.value = message
  }
  async function save() {
    busy.value = true
    error.value = ''
    success.value = ''
    const endpoint = selected.value ? root.value + '/' + selected.value.id : root.value
    const fields = { ...repairDraft(form), version: selected.value?.version ?? 0 }
    let currentDetail: RepairDetail | null = null
    try {
      currentDetail = await api<RepairDetail>(endpoint, {
        method: selected.value ? 'PUT' : 'POST',
        body: JSON.stringify({ ...fields, requestKey: operationKey(endpoint, fields) }),
      })
      if (pendingFaultFiles.value.length > 0) {
        while (pendingFaultFiles.value.length > 0) {
          const item = pendingFaultFiles.value[0]
          const uploadEndpoint: string = `${root.value}/${currentDetail.id}/attachments`
          const fingerprint = {
            version: currentDetail.version,
            purpose: 'FAULT',
            name: item.file.name,
            size: item.file.size,
            modified: item.file.lastModified,
          }
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
    if (file.value) {
      promptAlert('还有待上传图片，请先上传或清除选择。', '操作提示', 'warning')
      return
    }
    if (['assign', 'reassign'].includes(action) && !technicianId.value) {
      promptAlert('请选择当前负责范围匹配的维修人员。', '操作提示', 'warning')
      return
    }
    const fallback: Record<string, string> = {
      submit: '本人核对资料后提交',
      accept: '维修人员确认接单',
      confirm: '本人确认问题已解决',
    }
    const note = comment.value.trim() || fallback[action] || ''
    if (action !== 'evaluation' && !note) {
      promptAlert('请填写处理说明后再操作。', '操作提示', 'warning', () => {
        commentTextareaRef.value?.focus?.()
        commentTextareaRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
      })
      return
    }
    let cancelMutation: ReturnType<typeof repairCancellationMutation>
    try {
      cancelMutation = repairCancellationMutation(
        selected.value.id,
        action,
        selected.value.cancellation?.id ?? null,
        selected.value.version,
        note,
      )
    } catch (cause) {
      const msg = errorMessage(cause)
      promptAlert(msg, '操作提示', 'warning')
      return
    }
    const selectedTechnician = technicians.value.find(t => t.id === Number(technicianId.value))
    const cancelPrompts: Record<string, string> = {
      'request-cancellation': '申请取消后，维修和验收将暂停，等待后勤决定。确认申请？',
      'withdraw-cancellation': '撤销取消申请后，继续当前维修或验收流程。确认撤销？',
      'approve-cancellation': '同意后关闭工单，结束当前维修任务并保留历史。确认同意取消？',
      'reject-cancellation': '拒绝后继续原维修或验收流程，请核对处理说明。确认拒绝取消？',
    }
    const prompt =
      cancelPrompts[action] ??
      (action === 'submit'
        ? '提交后由后勤分诊，地点和类别固定。确认提交这份报修吗？'
        : action === 'confirm'
          ? '确认这次维修已经解决问题吗？确认后工单完成。'
          : `${repairActions[action]}${selectedTechnician && ['assign', 'reassign'].includes(action) ? '：' + selectedTechnician.name : ''}，确认继续吗？`)
    if (!(await ask(prompt))) return
    busy.value = true
    error.value = ''
    success.value = ''
    const endpoint = cancelMutation?.endpoint ?? `${root.value}/${selected.value.id}/${action}`
    const body = cancelMutation?.body ?? {
      version: selected.value.version,
      comment: note,
      technicianId: ['assign', 'reassign'].includes(action) ? Number(technicianId.value) : null,
      resultRound: ['confirm', 'reopen', 'evaluation'].includes(action) ? selected.value.completionRound : null,
      stars: action === 'evaluation' ? Number(stars.value) : null,
      priority: action === 'priority' ? priority.value : null,
    }
    try {
      const result = await api<RepairDetail>(endpoint, {
        method: 'POST',
        body: JSON.stringify({ ...body, requestKey: operationKey(endpoint, body) }),
      })
      await saved(result, repairActions[action] + '已记录。')
    } catch (cause) {
      const msg = errorMessage(cause)
      promptAlert(msg, '操作未完成', 'error')
    } finally {
      busy.value = false
    }
  }
  function selectFile(event: Event) {
    file.value = (event.target as HTMLInputElement).files?.[0] ?? null
    if (
      file.value &&
      (!['image/jpeg', 'image/png'].includes(file.value.type) ||
        file.value.size < 1 ||
        file.value.size > 2 * 1024 * 1024)
    ) {
      promptAlert('请选择 2 MB 以内的 JPEG 或 PNG 图片。', '图片格式提示', 'warning')
      clearFile()
    }
  }
  function clearFile() {
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
  }
  async function upload() {
    if (!selected.value || !file.value || busy.value) return
    busy.value = true
    error.value = ''
    const endpoint = `${root.value}/${selected.value.id}/attachments`,
      fingerprint = {
        version: selected.value.version,
        purpose: purpose.value,
        name: file.value.name,
        size: file.value.size,
        modified: file.value.lastModified,
      }
    const data = new FormData()
    data.set('file', file.value)
    data.set('version', String(selected.value.version))
    data.set('purpose', purpose.value)
    data.set('requestKey', operationKey(endpoint, fingerprint))
    try {
      await saved(await api<RepairDetail>(endpoint, { method: 'POST', body: data }), '图片已保存，历史版本保留。')
    } catch (cause) {
      const msg = errorMessage(cause)
      promptAlert(msg, '图片保存失败', 'error')
    } finally {
      busy.value = false
    }
  }
  async function removeImage(image: RepairImage) {
    if (!selected.value || !(await ask('从当前资料移除这张图片？已保存的历史记录仍保留。'))) return
    busy.value = true
    error.value = ''
    const endpoint = `${root.value}/${selected.value.id}/attachments/${image.id}/remove`,
      body = { version: selected.value.version, comment: '移除当前图片' }
    try {
      const updated = await api<RepairDetail>(endpoint, {
        method: 'POST',
        body: JSON.stringify({ ...body, requestKey: operationKey(endpoint, body) }),
      })
      if (editing.value) {
        selected.value = updated
        success.value = '图片已从当前资料移除。'
      } else {
        await saved(updated, '图片已从当前资料移除。')
      }
    } catch (cause) {
      const msg = errorMessage(cause)
      promptAlert(msg, '移除图片失败', 'error')
    } finally {
      busy.value = false
    }
  }
  function removable(image: RepairImage) {
    return (
      (image.purpose === 'FAULT' && can('remove-fault')) ||
      (image.purpose === 'RESULT' &&
        image.resultRound === (selected.value?.completionRound ?? 0) + 1 &&
        can('remove-result'))
    )
  }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (dirty.value || busy.value) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  onBeforeRouteLeave(allowLeave)
  onBeforeRouteUpdate(allowLeave)
  onMounted(() => {
    window.addEventListener('beforeunload', beforeUnload)
    void load()
  })
  watch(
    () => route.fullPath,
    () => {
      void load()
    },
  )
  onBeforeUnmount(() => {
    sequence++
    locationSequence++
    clearPendingFaultFiles()
    finish(false)
    closeAlert()
    window.removeEventListener('beforeunload', beforeUnload)
  })

  return {
    alertState,
    busy,
    campusId,
    campusOptions,
    can,
    cancellationFilter,
    cancellationOptions,
    categoryOptions,
    changeCampus,
    changeCancellationFilter,
    choose,
    clearFile,
    closeAlert,
    closePreview,
    comment,
    confirmation,
    currentFaultImageCount,
    currentTask,
    edit,
    editing,
    error,
    existingFaultImages,
    faultImages,
    file,
    filterQueue,
    filtersOpen,
    finish,
    form,
    handleCurrentTask,
    historyActorRole,
    historyView,
    imageUrl,
    intro,
    isClosed,
    list,
    loading,
    locationOptions,
    locations,
    maintenanceImages,
    mobileDetailOpen,
    mode,
    navigate,
    newDraft,
    onSelectFormFiles,
    operationActions,
    page,
    pendingFaultFiles,
    previewImage,
    previewModalUrl,
    primaryAction,
    priority,
    priorityOptions,
    purpose,
    purposeOptions,
    queueItems,
    quickFilters,
    records,
    refresh,
    removable,
    removeImage,
    removePendingFile,
    repairIndicatorStyle,
    route,
    save,
    search,
    selectFile,
    selected,
    selectedLocationHint,
    showList,
    starOptions,
    stars,
    status,
    statusOptions,
    stepTime,
    stepperOverview,
    success,
    technicianId,
    technicianOptions,
    technicians,
    time,
    title,
    transition,
    upload,
    workflowSteps,
  }
}
