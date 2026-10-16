import { computed, nextTick, onBeforeUnmount, reactive, ref, useTemplateRef, watch } from 'vue'
import type { Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { api, ApiError, errorMessage } from './api'
import type { AssistantBusiness, AssistantTaskView } from './assistantSessions'
import type { AssistantLeavePreview } from './assistantLeave'
import type { AssistantCertificatePreview } from './assistantCertificate'
import type { AssistantRepairPreview } from './assistantRepair'
import { certificateIdentityFingerprint } from './assistantCertificate'
import { sameHousingStay, type AssistantHousingPreview } from './assistantHousing'
import { currentUser } from '../stores/auth'

type Preview = AssistantLeavePreview | AssistantCertificatePreview | AssistantRepairPreview | AssistantHousingPreview
interface Props { active: boolean; mode: 'LOCAL' | 'DEEPSEEK'; modelReady: boolean; sessionId: number | null; resumeTaskId: number | null }
export function useAssistantDraft<F extends object, P extends Preview>(options: {
  props: Props; kind: 'LEAVE' | 'CERTIFICATE' | 'REPAIR' | 'HOUSING'; empty: () => F; fingerprint: (fields: F) => string
  emitSaving: (value: boolean) => void; emitWorking: (value: boolean) => void; emitUpdated: (value: AssistantTaskView) => void
}) {
  const { props, kind } = options, route = useRoute()
  const fields = reactive(options.empty()) as F
  const preview = ref(null) as Ref<P | null>, saved = ref<AssistantBusiness | null>(null), record = ref<AssistantTaskView | null>(null)
  const question = ref(''), latest = ref(''), consent = ref(false), confirmed = ref(false), busy = ref(false), saving = ref(false), error = ref(''), uncertain = ref(false)
  const showComposer = ref(true), createKey = ref(crypto.randomUUID())
  const scrollArea = useTemplateRef<HTMLElement>('scrollArea'), input = useTemplateRef<HTMLTextAreaElement>('input')
  const dialog = useTemplateRef<HTMLDialogElement>('dialog'), cancelButton = useTemplateRef<HTMLButtonElement>('cancelButton')
  let request: AbortController | null = null, generation = 0, resolveLeave: ((value: boolean) => void) | null = null
  const fingerprint = computed(() => options.fingerprint(fields))
  const checked = computed(() => preview.value && fingerprint.value === options.fingerprint(preview.value.fields as F))
  const baseline = computed(() => options.fingerprint((preview.value?.fields ?? options.empty()) as F))
  const dirty = computed(() => !saved.value && (fingerprint.value !== baseline.value || !!question.value.trim()))
  const canSend = computed(() => question.value.trim() && props.modelReady && consent.value && !busy.value && !saving.value && !saved.value && !uncertain.value)
  const canSave = computed(() => !!record.value && !saved.value && preview.value?.ready && checked.value && confirmed.value && !busy.value && !saving.value)
  function root() { if (!props.sessionId) throw new Error('请先恢复或新建对话'); return `/assistant/sessions/${props.sessionId}/tasks` }
  function apply(result: AssistantTaskView) {
    record.value = result; preview.value = (kind === 'LEAVE' ? result.leavePreview : kind === 'CERTIFICATE' ? result.certificatePreview : kind === 'HOUSING' ? result.housingPreview : result.repairPreview) as P | null
    Object.assign(fields, preview.value?.fields ?? options.empty()); saved.value = result.business; confirmed.value = false; consent.value = false
    if (preview.value?.ready) showComposer.value = false
    options.emitUpdated(result)
  }
  function clear() {
    generation++; request?.abort(); request = null; busy.value = false
    Object.assign(fields, options.empty()); preview.value = null; record.value = null; saved.value = null
    question.value = ''; latest.value = ''; error.value = ''; uncertain.value = false; consent.value = false; confirmed.value = false
    createKey.value = crypto.randomUUID(); showComposer.value = true
  }
  async function obtain(controller: AbortController, forceNew = false) {
    if (!forceNew && record.value) return record.value
    return !forceNew && props.resumeTaskId
      ? api<AssistantTaskView>(`${root()}/${props.resumeTaskId}`, { signal: controller.signal })
      : api<AssistantTaskView>(root(), { method: 'POST', signal: controller.signal, body: JSON.stringify({ requestKey: createKey.value, kind }) })
  }
  async function restore(forceNew = false) {
    if (!props.sessionId || busy.value || saving.value) return
    if (dirty.value && !await allowLeave()) return
    if (forceNew) { clear(); createKey.value = crypto.randomUUID() }
    const current = ++generation, controller = new AbortController(); request = controller; busy.value = true; error.value = ''
    try {
      const result = !forceNew && record.value ? await api<AssistantTaskView>(`${root()}/${record.value.id}`, { signal: controller.signal }) : await obtain(controller, forceNew)
      if (current !== generation) return
      apply(result); question.value = ''; latest.value = ''; uncertain.value = false
    } catch (cause) { if (current === generation && !controller.signal.aborted) error.value = errorMessage(cause) }
    finally { if (current === generation) { busy.value = false; request = null; consent.value = false } }
  }
  async function prepare(withModel: boolean) {
    if (!props.sessionId || busy.value || saving.value || saved.value || uncertain.value || (withModel && !canSend.value)) return
    const text = withModel ? question.value.trim() : '核对并暂存当前预览', current = ++generation
    const controller = new AbortController(); request = controller; busy.value = true; error.value = ''
    try {
      const task = await obtain(controller)
      const result = await api<AssistantTaskView>(`${root()}/${task.id}/${kind.toLowerCase()}`, { method: 'POST', signal: controller.signal,
        body: JSON.stringify({ version: task.version, input: { message: text, mode: withModel ? 'DEEPSEEK' : 'LOCAL', externalConsent: withModel && consent.value, fields: { ...fields } } }) })
      if (current !== generation) return
      apply(result)
      if (withModel) { latest.value = text; question.value = '' }
      await nextTick(); scrollArea.value?.scrollTo({ top: 0, behavior: 'smooth' }); if (withModel && showComposer.value) input.value?.focus()
    } catch (cause) { if (current === generation && !controller.signal.aborted) error.value = errorMessage(cause) }
    finally { if (current === generation) { busy.value = false; request = null; consent.value = false } }
  }
  async function save() {
    if (!canSave.value || !record.value || !preview.value) return
    const reviewed = preview.value, reviewedVersion = record.value.version
    saving.value = true; error.value = ''; options.emitSaving(true)
    let saveRequested = false
    try {
      const fresh = await api<AssistantTaskView>(`${root()}/${record.value.id}`)
      if (fresh.business) { apply(fresh); uncertain.value = false; return }
      const checkedNow = (kind === 'LEAVE' ? fresh.leavePreview : kind === 'CERTIFICATE' ? fresh.certificatePreview : kind === 'HOUSING' ? fresh.housingPreview : fresh.repairPreview) as P
      if (fresh.version !== reviewedVersion || options.fingerprint(checkedNow.fields as F) !== fingerprint.value) {
        confirmed.value = false; error.value = '任务已在其他窗口更新，请点击“恢复最新进度”后重新核对。'; return
      }
      preview.value = checkedNow
      if (!checkedNow.ready) { confirmed.value = false; return }
      const identity = kind === 'CERTIFICATE' ? (reviewed as AssistantCertificatePreview).identity : undefined
      if (kind === 'CERTIFICATE' && certificateIdentityFingerprint((checkedNow as AssistantCertificatePreview).identity) !== certificateIdentityFingerprint(identity ?? null)) {
        confirmed.value = false; error.value = '登记资料已更新，请重新核对本人资料并确认。'; return
      }
      if (kind === 'HOUSING' && !sameHousingStay(reviewed as AssistantHousingPreview, checkedNow as AssistantHousingPreview)) {
        confirmed.value = false; error.value = '当前住宿或办理指南已变化，请重新核对后确认。'; return
      }
      saveRequested = true
      apply(await api<AssistantTaskView>(`${root()}/${record.value.id}/save`, { method: 'POST', body: JSON.stringify({ version: reviewedVersion, confirmed: true, expectedIdentity: identity, expectedStayToken: kind === 'HOUSING' ? (reviewed as AssistantHousingPreview).stayToken : undefined }) }))
      uncertain.value = false
      await nextTick(); scrollArea.value?.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (cause) {
      error.value = errorMessage(cause); uncertain.value = saveRequested && cause instanceof ApiError && (cause.status === 0 || cause.status >= 500)
      if (!uncertain.value) confirmed.value = false
    } finally { saving.value = false; options.emitSaving(false) }
  }
  function finishLeave(value: boolean) { dialog.value?.close(); const resolve = resolveLeave; resolveLeave = null; resolve?.(value) }
  async function allowLeave() {
    if (!currentUser.value) return true
    if (busy.value || saving.value) { error.value = '正在核对或保存，请等待完成后再离开。'; return false }
    if (!dirty.value) return true
    return new Promise<boolean>(resolve => { resolveLeave = resolve; void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() }) })
  }
  function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || saving.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
  function keydown(event: KeyboardEvent) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void prepare(true) } }
  watch(fingerprint, () => { confirmed.value = false })
  watch(() => props.mode, () => { consent.value = false })
  watch([() => route.fullPath, () => currentUser.value?.id, () => props.sessionId], () => { clear(); if (props.active) void restore() })
  watch(() => props.active, active => { confirmed.value = false; consent.value = false; if (active && !dirty.value) void restore() })
  watch(() => props.resumeTaskId, id => { if (props.active && id !== record.value?.id && !dirty.value) { clear(); void restore() } })
  watch(busy, options.emitWorking)
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
  onBeforeUnmount(() => { generation++; request?.abort(); window.removeEventListener('beforeunload', beforeUnload); finishLeave(false) })
  return { fields, preview, saved, record, question, latest, consent, confirmed, busy, saving, error, uncertain, showComposer, checked, canSend, canSave, clear, prepare, restore, save, finishLeave, allowLeave, keydown }
}
