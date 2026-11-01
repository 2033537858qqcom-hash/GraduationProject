<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, errorMessage } from '../lib/api'
import { safeAssistantDestination } from '../lib/assistant'
import { canConfirmMission, missionActionLabels, missionBusinessStatusLabel, missionConfirmationPayload, missionFieldsPayload, missionNeedsPolling, missionStageLabel, missionStepStatusLabel } from '../lib/assistantMissions'
import type { MissionFields, MissionSummary, MissionView } from '../lib/assistantMissions'
import BaseSelect from './BaseSelect.vue'

const props = defineProps<{ sessionId: number | null; active: boolean; modelReady: boolean }>()
const emit = defineEmits<{ working: [value: boolean] }>()
const mission = ref<MissionView | null>(null), summaries = ref<MissionSummary[]>([])
const goal = ref(''), mode = ref<'LOCAL' | 'DEEPSEEK'>('LOCAL'), consent = ref(false), confirmed = ref(false), selectedKey = ref('')
const loading = ref(false), busy = ref(false), cancelling = ref(false), error = ref('')
const createKey = ref(crypto.randomUUID())
const empty = () => ({ campus: '', startsAt: '', endsAt: '', participants: '' as string | number | null, keyword: '', activityTheme: '', activityType: 'ACADEMIC', contactPhone: '' })
const form = ref(empty())
const dirty = computed(() => mission.value ? JSON.stringify(missionFieldsPayload(form.value)) !== JSON.stringify(missionFieldsPayload(mission.value.fields)) : !!goal.value.trim() || JSON.stringify(form.value) !== JSON.stringify(empty()))
const working = computed(() => loading.value || busy.value || cancelling.value)
watch(working, value => emit('working', value))
const running = computed(() => missionNeedsPolling(mission.value))
const locked = computed(() => working.value || running.value || !!pending.value || ['DONE', 'CANCELLED'].includes(mission.value?.stage ?? '') || !!mission.value?.business)
const readyToConfirm = computed(() => !working.value && !pending.value && canConfirmMission(mission.value, form.value, confirmed.value))
const modeOptions = computed(() => [{ value: 'LOCAL', label: '本地任务流程' }, { value: 'DEEPSEEK', label: 'DeepSeek动态规划', disabled: !props.modelReady }])
const campusOptions = [{ value: '', label: '请选择校区' }, { value: 'EAST', label: '东校区' }, { value: 'WEST', label: '西校区' }, { value: 'ALL', label: '两个校区均可' }]
const activityOptions = [{ value: 'ACADEMIC', label: '学术与学习' }, { value: 'SPORTS', label: '体育活动' }, { value: 'CULTURE', label: '文化活动' }, { value: 'OTHER', label: '其他活动' }]
const missingLabels: Record<string, string> = { campus: '校区', participants: '人数', startsAt: '开始时间', endsAt: '结束时间', timeRange: '有效的未来时段（30分钟至14小时）', activityTheme: '活动主题', activityType: '活动类型', keyword: '场地关键词', calendarAccess: '日程访问范围' }
let generation = 0, timer: ReturnType<typeof setTimeout> | null = null, controller: AbortController | null = null
const pending = ref<{ path: string; body: Record<string, unknown>; session: number } | null>(null)
function base(session = props.sessionId) { return `/assistant/sessions/${session}/missions` }
function fill(fields: MissionFields) { form.value = { campus: fields.campus ?? '', startsAt: fields.startsAt ?? '', endsAt: fields.endsAt ?? '', participants: fields.participants ?? '', keyword: fields.keyword ?? '', activityTheme: fields.activityTheme ?? '', activityType: fields.activityType ?? 'ACADEMIC', contactPhone: fields.contactPhone ?? '' } }
function apply(view: MissionView, replaceFields = true) { mission.value = view; selectedKey.value = view.selectedKey ?? ''; confirmed.value = false; if (replaceFields) fill(view.fields) }
function stopPolling() { if (timer) clearTimeout(timer); timer = null }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
function schedulePolling() {
 stopPolling()
 if (props.active && (busy.value || running.value)) timer = setTimeout(() => { void poll() }, 1500)
}
async function poll() {
 const session = props.sessionId, epoch = generation
 if (!props.active || !session) return
 try {
  const list = await api<MissionSummary[]>(base(session))
  if (epoch !== generation || session !== props.sessionId) return
  summaries.value = list
  const id = mission.value?.id ?? list.find(item => item.requestKey === pending.value?.body.requestKey)?.id
  if (id) { const view = await api<MissionView>(`${base(session)}/${id}`); if (epoch === generation && session === props.sessionId) apply(view) }
 } catch (cause) { if (epoch === generation) error.value = errorMessage(cause) }
 finally { if (epoch === generation) schedulePolling() }
}
async function load(id?: number) {
 if (!props.sessionId || id === undefined && dirty.value) return
 const session = props.sessionId, epoch = ++generation; loading.value = true; error.value = ''; stopPolling()
 try {
  const list = await api<MissionSummary[]>(base(session)); const chosen = id ?? mission.value?.id ?? list[0]?.id
  const view = chosen ? await api<MissionView>(`${base(session)}/${chosen}`) : null
  if (epoch !== generation || session !== props.sessionId) return
  summaries.value = list; if (view) apply(view)
 } catch (cause) { if (epoch === generation) error.value = errorMessage(cause) }
 finally { if (epoch === generation) { loading.value = false; schedulePolling() } }
}
async function mutate(path: string, body: Record<string, unknown>) {
 if (!props.sessionId) return
 const session = props.sessionId, epoch = ++generation; busy.value = true; error.value = ''; pending.value = { path, body, session }; controller = new AbortController(); schedulePolling()
 try {
  const result = await api<MissionView>(path, { method: 'POST', body: JSON.stringify(body), signal: controller.signal })
  if (epoch !== generation || session !== props.sessionId) return
  apply(result); pending.value = null; summaries.value = await api<MissionSummary[]>(base(session))
 } catch (cause) {
  if (epoch !== generation) return
  error.value = errorMessage(cause)
  if (!(cause instanceof ApiError && cause.status === 0)) pending.value = null
 } finally { if (epoch === generation) { busy.value = false; controller = null; schedulePolling() } }
}
async function create() {
 if (working.value || !goal.value.trim() || mode.value === 'DEEPSEEK' && (!consent.value || !props.modelReady)) return
 await mutate(base(), { requestKey: createKey.value, goal: goal.value.trim(), mode: mode.value, externalConsent: consent.value, fields: missionFieldsPayload(form.value) })
}
async function action(action: string) {
 if (!mission.value || working.value || pending.value) return
 const body: Record<string, unknown> = { requestKey: crypto.randomUUID(), version: mission.value.version, action }
 if (action === 'REVISE') body.fields = missionFieldsPayload(form.value)
 if (action === 'SELECT') body.optionKey = selectedKey.value
 await mutate(`${base()}/${mission.value.id}/actions`, body)
}
async function confirm() {
 if (!mission.value || !readyToConfirm.value) return
 const body = missionConfirmationPayload(mission.value, form.value, confirmed.value, crypto.randomUUID())
 await mutate(`${base()}/${mission.value.id}/actions`, body)
}
async function retry() { const request = pending.value; if (request && request.session === props.sessionId) await mutate(request.path, request.body) }
async function cancel() {
 if (!mission.value || cancelling.value) return
 const session = props.sessionId, id = mission.value.id, version = mission.value.version, epoch = ++generation
 controller?.abort(); controller = null; busy.value = false; cancelling.value = true; stopPolling(); error.value = ''
 try {
  const view = await api<MissionView>(`${base(session)}/${id}/actions`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID(), version, action: 'CANCEL' }) })
  if (epoch === generation && session === props.sessionId) { apply(view); pending.value = null }
 } catch (cause) { if (epoch === generation) error.value = errorMessage(cause) }
 finally { if (epoch === generation) { cancelling.value = false; schedulePolling() } }
}
async function allowLeave() { return !dirty.value || window.confirm('任务条件尚未保存，是否放弃这些编辑？') }
async function newTask() { if (!await allowLeave() || working.value || running.value || pending.value) return; mission.value = null; goal.value = ''; form.value = empty(); consent.value = false; confirmed.value = false; pending.value = null; createKey.value = crypto.randomUUID(); error.value = '' }
async function selectTask(id: number) { if (!working.value && await allowLeave()) await load(id) }
watch([goal, form, mode], () => { confirmed.value = false; if (!mission.value && !pending.value) createKey.value = crypto.randomUUID() }, { deep: true })
watch(() => [props.active, props.sessionId] as const, ([active, session], previous) => {
 if (session !== previous?.[1]) { generation++; controller?.abort(); busy.value = false; cancelling.value = false; mission.value = null; summaries.value = []; form.value = empty(); goal.value = ''; pending.value = null; consent.value = false; createKey.value = crypto.randomUUID() }
 if (active && session) void load(); else stopPolling()
}, { immediate: true })
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { generation++; stopPolling(); controller?.abort(); window.removeEventListener('beforeunload', beforeUnload); emit('working', false) })
defineExpose({ allowLeave })
</script>

<template>
 <section class="assistant-history mission" aria-label="预约任务Agent">
  <div class="mission-heading"><div><h2>预约任务 Agent</h2><p>提出目标，查看执行过程，选择场地后核对草稿。</p></div><button type="button" :disabled="working || running || !!pending" @click="newTask">新任务</button></div>
  <label v-if="summaries.length" class="mission-label">恢复本次对话的任务<select :value="mission?.id ?? ''" :disabled="working" @change="selectTask(Number(($event.target as HTMLSelectElement).value))"><option value="" disabled>选择任务</option><option v-for="item in summaries" :key="item.id" :value="item.id">#{{ item.id }} · {{ missionStageLabel(item.stage) }} · {{ item.goal.slice(0, 24) }}</option></select></label>
  <p v-if="loading" role="status">正在恢复任务…</p>
  <template v-if="!mission">
   <label class="mission-label">预约目标<textarea v-model="goal" rows="3" maxlength="1000" :disabled="working || !!pending" placeholder="例如：下周二东校区30人09:00–11:00，帮我准备教室预约草稿" /></label>
   <label class="mission-label">执行方式<BaseSelect v-model="mode" :options="modeOptions" compact :disabled="working || !!pending" aria-label="Agent执行方式" /></label>
   <label v-if="mode === 'DEEPSEEK'" class="mission-check"><input v-model="consent" type="checkbox" :disabled="working || !!pending" />同意将本次目标及后续脱敏执行观察（工具状态、数量、缺失条件）发送至 DeepSeek 完成本任务。业务正文、联系方式和历史对话保留本地。</label>
   <p class="mission-note">目标中的条件会被核对；未明确的校区、日期或时段会要求补充。联系方式请在下面的本地表单填写。</p>
  </template>
  <template v-else>
   <div class="mission-state" role="status"><strong>{{ missionStageLabel(mission.stage) }}</strong><span>{{ mission.mode === 'DEEPSEEK' ? 'DeepSeek逐步规划' : '本地任务流程' }}</span></div>
   <p class="mission-goal">{{ mission.goal }}</p><p>{{ mission.message }}</p>
   <p v-if="mission.missing.length" class="mission-note">待核对：{{ mission.missing.map(key => missingLabels[key] ?? key).join('、') }}</p>
   <button v-if="!['DONE', 'CANCELLED'].includes(mission.stage)" type="button" :disabled="cancelling" @click="cancel">{{ cancelling ? '正在取消…' : '取消后续执行' }}</button>
   <details v-if="mission.steps.length" class="mission-trace"><summary>执行过程 · {{ mission.steps.length }} 步</summary><ol><li v-for="step in mission.steps" :key="step.number"><strong>{{ missionActionLabels[step.action] ?? step.action }}</strong><span>{{ missionStepStatusLabel(step.status) }}</span><p>{{ step.message }}</p></li></ol></details>
   <div v-if="mission.conflicts.length" class="mission-conflicts"><h3>本人日程重叠</h3><p v-for="item in mission.conflicts" :key="item.key">{{ item.title }} · {{ item.description }}</p></div>
  </template>
  <details v-if="!mission || !mission.business" :open="!mission || mission.stage === 'WAITING_INPUT'" class="mission-fields">
<summary>本地任务条件</summary>
   <fieldset :disabled="locked">
<div class="mission-grid">
    <label>校区<BaseSelect v-model="form.campus" :options="campusOptions" compact aria-label="任务校区" /></label><label>预计人数<input v-model.number="form.participants" type="number" min="1" max="10000" step="1" placeholder="例如30" /></label>
    <label class="mission-wide">开始时间<input v-model="form.startsAt" type="datetime-local" /></label><label class="mission-wide">结束时间<input v-model="form.endsAt" type="datetime-local" /></label>
    <label class="mission-wide">场地名称关键词（可选）<input v-model="form.keyword" maxlength="40" placeholder="例如研讨室" /></label>
    <label class="mission-wide">活动主题<input v-model="form.activityTheme" maxlength="200" placeholder="由你填写活动的实际主题" /></label><label class="mission-wide">活动类型<BaseSelect v-model="form.activityType" :options="activityOptions" compact aria-label="任务活动类型" /></label>
    <label class="mission-wide">联系方式（仅本地保存，可稍后补充）<input v-model="form.contactPhone" maxlength="30" autocomplete="tel" /></label>
   </div>
</fieldset>
  </details>
  <button v-if="!mission" class="mission-primary" type="button" :disabled="working || !!pending || !goal.trim() || mode === 'DEEPSEEK' && (!consent || !modelReady)" @click="create">{{ busy ? 'Agent正在执行…' : '开始任务' }}</button>
  <button v-if="mission && !mission.business && !['DONE', 'CANCELLED', 'RUNNING'].includes(mission.stage)" type="button" :disabled="locked || !dirty && mission.stage !== 'WAITING_INPUT'" @click="action('REVISE')">更新条件并重新规划</button>
  <template v-if="mission?.stage === 'WAITING_SELECTION'">
   <fieldset class="mission-options" :disabled="working || !!pending || dirty"><legend>选择一个候选场地</legend><label v-for="option in mission.options" :key="option.key"><input v-model="selectedKey" :name="`mission-option-${mission.id}`" type="radio" :value="option.key" /><span><strong>{{ option.name }}</strong><small>{{ option.location }} · 容量 {{ option.capacity }} 人</small></span></label></fieldset>
   <button type="button" class="mission-primary" :disabled="working || !!pending || dirty || !selectedKey" @click="action('SELECT')">核对所选场地与草稿</button>
  </template>
  <template v-if="mission?.stage === 'WAITING_CONFIRMATION'">
   <div class="mission-proposal"><h3>预约草稿预览</h3><p>{{ mission.options.find(item => item.key === mission?.selectedKey)?.name }}</p><p>{{ mission.fields.startsAt?.replace('T', ' ') }}—{{ mission.fields.endsAt?.replace('T', ' ') }}</p><p>{{ mission.fields.participants }} 人 · {{ mission.fields.activityTheme }}</p><p>{{ activityOptions.find(item => item.value === mission?.fields.activityType)?.label }} · 联系方式：{{ mission.fields.contactPhone || '尚未填写' }}</p></div>
   <p v-if="dirty" class="mission-note">条件有修改，请先更新并重新核对预览。</p>
   <label class="mission-check"><input v-model="confirmed" type="checkbox" :disabled="working || !!pending || dirty" />我已核对场地、时间、人数及活动信息，确认保存本人预约草稿。</label>
   <button type="button" class="mission-primary" :disabled="!readyToConfirm" @click="confirm">确认保存并核验</button>
  </template>
  <button v-if="mission?.canContinue" type="button" :disabled="working || !!pending || dirty" @click="action('CONTINUE')">继续执行</button>
  <RouterLink v-if="mission?.business && safeAssistantDestination(mission.business.targetPath)" :to="mission.business.targetPath" class="mission-business">打开原预约申请 · {{ missionBusinessStatusLabel(mission.business.status) }}</RouterLink>
  <p v-if="mission?.business" class="mission-note">草稿需要在原页面补充检查并手动提交，正式预约仍须负责人审核。</p>
  <p v-if="error" class="mission-error" role="alert">{{ error }}</p><button v-if="pending && !busy" type="button" :disabled="working" @click="retry">重试原操作</button>
  <button v-if="mission && !busy && !pending" type="button" :disabled="working" @click="selectTask(mission.id)">刷新任务状态</button>
 </section>
</template>

<style scoped>
.mission{font-size:12px;line-height:1.65;color:#52677e}.mission h2{font-size:15px;color:#294c71;margin:0}.mission h3{font-size:13px;margin:8px 0 4px}.mission p{margin:7px 0}.mission-heading{display:flex;justify-content:space-between;align-items:start;gap:10px}.mission-heading p{font-size:11px;color:#7b8b9b}.mission button{font:inherit;padding:6px 10px;border:1px solid #d5dfe9;background:#fff;border-radius:6px;color:#355a80;cursor:pointer;margin:5px 6px 5px 0}.mission button:disabled{opacity:.45;cursor:default}.mission button:focus-visible,.mission input:focus-visible,.mission textarea:focus-visible,.mission select:focus-visible{outline:2px solid #6d91b9;outline-offset:2px}.mission .mission-primary{background:#244b78;color:#fff;border-color:#244b78}.mission-label,.mission-grid label{display:flex;flex-direction:column;gap:5px;margin:9px 0}.mission textarea,.mission input:not([type=checkbox]):not([type=radio]),.mission select{font:inherit;width:100%;min-width:0;box-sizing:border-box;padding:7px 8px;border:1px solid #d9e2ec;border-radius:5px;background:#fff;color:#355374}.mission textarea{resize:vertical}.mission-check{display:flex;align-items:start;gap:7px;margin:10px 0;font-size:11px}.mission-check input{margin-top:4px;flex-shrink:0}.mission-note{font-size:11px;color:#7b8999}.mission-state{display:flex;justify-content:space-between;gap:8px;background:#edf3f9;border-radius:6px;padding:8px 10px;margin:10px 0}.mission-state span{font-size:10px}.mission-goal{overflow-wrap:anywhere;color:#355374}.mission-fields,.mission-trace{border-top:1px solid #e4eaf1;margin:12px 0;padding-top:8px}.mission summary{cursor:pointer;font-weight:600;color:#3c5978}.mission fieldset{border:0;padding:0;margin:8px 0;min-width:0}.mission-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 10px}.mission-wide{grid-column:1/-1}.mission-trace ol{margin:7px 0;padding-left:22px}.mission-trace li{padding:4px 0}.mission-trace li>span{font-size:9px;margin-left:8px;color:#8392a1}.mission-trace li p{font-size:11px;margin:2px 0 6px}.mission-options label{display:flex;align-items:start;gap:8px;padding:9px;border:1px solid #dce5ef;border-radius:6px;margin:7px 0}.mission-options input{margin-top:5px}.mission-options small{display:block;color:#8190a1}.mission-proposal,.mission-conflicts{padding:8px 10px;border:1px solid #dce5ef;background:#f7fafc;border-radius:6px;margin:10px 0}.mission-error{color:#9a5948}.mission-business{display:block;color:#2b527e;text-decoration:underline;margin:12px 0}
</style>
