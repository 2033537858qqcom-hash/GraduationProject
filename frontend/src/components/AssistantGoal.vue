<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, errorMessage } from '../lib/api'
import { goalKindLabels, goalStageLabels, goalTargetLabels, goalToolLabels } from '../lib/assistantGoals'
import type { GoalSummary, GoalView } from '../lib/assistantGoals'
import AssistantMission from './AssistantMission.vue'

const props = defineProps<{ sessionId: number | null; active: boolean; modelReady: boolean }>()
const emit = defineEmits<{ working: [value: boolean] }>()
const current = ref<GoalView | null>(null), summaries = ref<GoalSummary[]>([])
const goalText = ref(''), consent = ref(false), revision = ref(''), revisionConsent = ref(false), chosenTask = ref('')
const loading = ref(false), busy = ref(false), childBusy = ref(false), error = ref(''), createKey = ref(crypto.randomUUID())
const pending = ref<{ path: string; body: Record<string, unknown>; session: number } | null>(null)
const child = ref<{ allowLeave: () => Promise<boolean> } | null>(null)
const working = computed(() => loading.value || busy.value || childBusy.value)
const running = computed(() => current.value?.stage === 'RUNNING')
const terminal = computed(() => ['DONE', 'CANCELLED'].includes(current.value?.stage ?? ''))
const activeTask = computed(() => current.value?.tasks.find(task => task.key === chosenTask.value)
 ?? current.value?.tasks.find(task => task.key === current.value?.focusTaskKey)
 ?? current.value?.tasks.find(task => !task.cancelled && task.mission?.stage !== 'DONE') ?? current.value?.tasks[0])
let generation = 0, timer: ReturnType<typeof setTimeout> | null = null, controller: AbortController | null = null
watch(working, value => emit('working', value))
function base(session = props.sessionId) { return `/assistant/sessions/${session}/goals` }
function stopPolling() { if (timer) clearTimeout(timer); timer = null }
function schedulePolling() { stopPolling(); if (props.active && (running.value || busy.value)) timer = setTimeout(() => { void poll() }, 1500) }
function apply(view: GoalView) { current.value = view; if (!view.tasks.some(task => task.key === chosenTask.value)) chosenTask.value = ''; schedulePolling() }
async function poll() {
 const session = props.sessionId, epoch = generation
 if (!props.active || !session) return
 try {
  const list = await api<GoalSummary[]>(base(session))
  if (epoch !== generation || session !== props.sessionId) return
  summaries.value = list
  const id = current.value?.id ?? list.find(item => item.requestKey === pending.value?.body.requestKey)?.id
  if (id) { const view = await api<GoalView>(`${base(session)}/${id}`); if (epoch === generation && session === props.sessionId) apply(view) }
 } catch (cause) { if (epoch === generation) error.value = errorMessage(cause) }
 finally { if (epoch === generation) schedulePolling() }
}
async function load(id?: number) {
 if (!props.sessionId || id === undefined && (!current.value && goalText.value.trim() || revision.value.trim())) return
 const session = props.sessionId, epoch = ++generation; loading.value = true; error.value = ''; stopPolling()
 try {
  const list = await api<GoalSummary[]>(base(session)); const chosen = id ?? current.value?.id ?? list[0]?.id
  const view = chosen ? await api<GoalView>(`${base(session)}/${chosen}`) : null
  if (epoch !== generation || session !== props.sessionId) return
  summaries.value = list; if (view) apply(view)
 } catch (cause) { if (epoch === generation) error.value = errorMessage(cause) }
 finally { if (epoch === generation) { loading.value = false; schedulePolling() } }
}
async function mutate(path: string, body: Record<string, unknown>) {
 if (!props.sessionId) return
 const session = props.sessionId, epoch = ++generation; busy.value = true; error.value = ''; pending.value = { path, body, session }; controller = new AbortController(); schedulePolling()
 try {
  const view = await api<GoalView>(path, { method: 'POST', body: JSON.stringify(body), signal: controller.signal })
  if (epoch !== generation || session !== props.sessionId) return
  apply(view); pending.value = null
  if (body.action === 'REVISE') { revision.value = ''; revisionConsent.value = false }
  summaries.value = await api<GoalSummary[]>(base(session))
 } catch (cause) {
  if (epoch !== generation) return
  error.value = errorMessage(cause)
  if (!(cause instanceof ApiError && cause.status === 0)) pending.value = null
 } finally { if (epoch === generation) { busy.value = false; controller = null; schedulePolling() } }
}
async function create() {
 if (working.value || pending.value || !props.modelReady || !consent.value || !goalText.value.trim()) return
 await mutate(base(), { requestKey: createKey.value, goal: goalText.value.trim(), externalConsent: true })
}
async function action(action: string) {
 if (!current.value || working.value || pending.value) return
 await mutate(`${base()}/${current.value.id}/actions`, { requestKey: crypto.randomUUID(), version: current.value.version, action })
}
async function revise() {
 if (!current.value || working.value || pending.value || !revision.value.trim() || !revisionConsent.value) return
 await mutate(`${base()}/${current.value.id}/actions`, { requestKey: crypto.randomUUID(), version: current.value.version, action: 'REVISE', message: revision.value.trim(), externalConsent: true })
}
async function retry() { const request = pending.value; if (request && request.session === props.sessionId) await mutate(request.path, request.body) }
async function cancel() {
 if (!current.value || current.value.stage === 'CANCELLED') return
 const session = props.sessionId, id = current.value.id, version = current.value.version
 generation++; controller?.abort(); controller = null; busy.value = false; stopPolling(); pending.value = null
 await mutate(`${base(session)}/${id}/actions`, { requestKey: crypto.randomUUID(), version, action: 'CANCEL' })
}
async function childUpdated() { chosenTask.value = ''; if (current.value?.canContinue && !busy.value && !pending.value) { childBusy.value = false; await action('CONTINUE') } }
async function allowLeave() {
 if (child.value && !await child.value.allowLeave()) return false
 return !(!current.value && goalText.value.trim() || revision.value.trim()) || window.confirm('目标或补充文字尚未发送，是否放弃这些编辑？')
}
async function newGoal() {
 if (working.value || running.value || pending.value || !await allowLeave()) return
 current.value = null; goalText.value = ''; consent.value = false; revision.value = ''; chosenTask.value = ''; createKey.value = crypto.randomUUID(); error.value = ''
}
async function selectGoal(id: number) { if (!working.value && await allowLeave()) { revision.value = ''; await load(id) } }
async function selectTask(key: string) { if (!working.value && (!child.value || await child.value.allowLeave())) chosenTask.value = key }
function beforeUnload(event: BeforeUnloadEvent) { if (!current.value && goalText.value.trim() || revision.value.trim()) { event.preventDefault(); event.returnValue = '' } }
watch(goalText, () => { consent.value = false; if (!current.value && !pending.value) createKey.value = crypto.randomUUID() })
watch(revision, () => { revisionConsent.value = false })
watch(() => [props.active, props.sessionId] as const, ([active, session], previous) => {
 if (session !== previous?.[1]) { generation++; controller?.abort(); busy.value = false; childBusy.value = false; current.value = null; summaries.value = []; goalText.value = ''; revision.value = ''; consent.value = false; pending.value = null; chosenTask.value = ''; createKey.value = crypto.randomUUID() }
 if (active && session) void load(); else stopPolling()
}, { immediate: true })
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { generation++; controller?.abort(); stopPolling(); window.removeEventListener('beforeunload', beforeUnload); emit('working', false) })
defineExpose({ allowLeave })
</script>

<template>
 <section class="goal" aria-label="自主目标Agent">
  <div class="goal-heading"><div><h2>自主目标 Agent</h2><p>说明要办成什么，Agent 会组织任务、选择工具，并根据结果调整方案。</p></div><button type="button" :disabled="working || running || !!pending" @click="newGoal">新目标</button></div>
  <label v-if="summaries.length" class="goal-field">恢复本次对话的目标<select :value="current?.id ?? ''" :disabled="working" @change="selectGoal(Number(($event.target as HTMLSelectElement).value))"><option value="" disabled>选择目标</option><option v-for="item in summaries" :key="item.id" :value="item.id">#{{ item.id }} · {{ goalStageLabels[item.stage] ?? item.stage }} · {{ item.goal.slice(0, 22) }}</option></select></label>
  <p v-if="loading" role="status">正在恢复目标和原申请结果…</p>
  <template v-if="!current">
   <label class="goal-field">要完成的目标<textarea v-model="goalText" rows="4" maxlength="1000" :disabled="working || !!pending" placeholder="例如：先准备实习单位核验身份用的在校证明，再帮我准备请假申请。" /></label>
   <label class="goal-check"><input v-model="consent" type="checkbox" :disabled="working || !!pending" />同意将本次目标、有效办理指南、公开场地候选和脱敏执行结果发送至 DeepSeek。身份、联系方式及本地表单正文不会自动发送。</label>
   <p v-if="!modelReady" class="goal-note">自主规划需要连接 DeepSeek。当前可在“Agent任务”使用本地单项草稿流程。</p>
   <button class="goal-primary" type="button" :disabled="working || !!pending || !modelReady || !consent || !goalText.trim()" @click="create">开始自主办理</button>
  </template>
  <template v-else>
   <div class="goal-state" role="status"><strong>{{ goalStageLabels[current.stage] ?? current.stage }}</strong><span>{{ current.events.length }} 次工具决策</span></div>
   <p class="goal-objective">{{ current.goal }}</p><p>{{ current.message }}</p>
   <details v-if="current.instructions.length"><summary>已补充的要求</summary><ol><li v-for="(instruction, index) in current.instructions" :key="index">{{ instruction }}</li></ol></details>
   <div v-if="current.tasks.length" class="goal-plan"><h3>任务计划</h3><ol><li v-for="task in current.tasks" :key="task.key"><button type="button" :disabled="working || running" :aria-pressed="activeTask?.key === task.key" @click="selectTask(task.key)"><strong>{{ task.label }}</strong><span>{{ goalKindLabels[task.kind] ?? task.kind }} · {{ goalTargetLabels[task.target] }}</span><small>{{ task.cancelled ? '已取消' : task.mission ? task.mission.business?.status === 'DRAFT' && task.mission.stage === 'DONE' ? '草稿已保存并核验' : task.mission.message : '尚未准备' }}</small></button><p v-if="task.dependsOn.length" class="goal-note">前置：{{ task.dependsOn.map(key => current?.tasks.find(parent => parent.key === key)?.label ?? key).join('、') }}</p></li></ol></div>
   <details v-if="current.events.length" class="goal-trace"><summary>自主执行记录 · {{ current.events.length }} 步</summary><ol><li v-for="event in current.events" :key="event.number"><strong>{{ goalToolLabels[event.tool] ?? event.tool }}</strong><span>{{ event.status === 'FAILED' ? '已记录失败，核对后调整' : event.status === 'RUNNING' ? '执行中' : '已记录结果' }}</span><p>{{ event.message }}</p></li></ol></details>
   <AssistantMission v-if="activeTask?.mission && !activeTask.cancelled" :key="activeTask.key" ref="child" embedded :mission-id="activeTask.mission.id" :session-id="sessionId" :active="active && !running && !busy" :model-ready="modelReady" @working="childBusy = $event" @updated="childUpdated" />
   <template v-if="!terminal">
    <button v-if="current.canContinue" type="button" class="goal-primary" :disabled="working || !!pending" @click="action('CONTINUE')">读取结果并继续规划</button>
    <details class="goal-revision"><summary>补充条件或调整目标</summary><label class="goal-field">本次补充<textarea v-model="revision" rows="3" maxlength="1000" :disabled="working || running || !!pending" placeholder="例如：这个时段冲突，可以再看看晚一小时的场地。" /></label><label class="goal-check"><input v-model="revisionConsent" type="checkbox" :disabled="working || running || !!pending" />同意将这次补充文字发送至 DeepSeek 重新规划。</label><button type="button" :disabled="working || running || !!pending || !revision.trim() || !revisionConsent" @click="revise">补充并重新规划</button></details>
    <button type="button" :disabled="loading" @click="cancel">取消后续办理</button>
   </template>
   <button type="button" :disabled="working || !!pending" @click="load(current.id)">刷新真实状态</button>
  </template>
  <p v-if="error" class="goal-error" role="alert">{{ error }}</p><button v-if="pending && !busy" type="button" :disabled="working" @click="retry">重试原操作</button>
 </section>
</template>

<style scoped>
.goal{font-size:12px;line-height:1.65;color:#52677e}.goal h2{margin:0;font-size:15px;color:#294c71}.goal h3{font-size:13px;color:#355a80}.goal p{margin:8px 0;overflow-wrap:anywhere}.goal-heading{display:flex;align-items:start;gap:12px}.goal-heading>div{min-width:0;flex:1}.goal-heading>button{flex-shrink:0;white-space:nowrap}.goal-heading p,.goal-note{font-size:11px;color:#7b8999}.goal button{font:inherit;padding:6px 10px;border:1px solid #d5dfe9;background:#fff;border-radius:6px;color:#355a80;cursor:pointer;margin:5px 6px 5px 0}.goal button:disabled{opacity:.45;cursor:default}.goal button:focus-visible,.goal input:focus-visible,.goal textarea:focus-visible,.goal select:focus-visible{outline:2px solid #6d91b9;outline-offset:2px}.goal .goal-primary{background:#244b78;border-color:#244b78;color:#fff}.goal-field{display:flex;flex-direction:column;gap:6px;margin:12px 0}.goal textarea,.goal select{font:inherit;box-sizing:border-box;min-width:0;width:100%;padding:8px;border:1px solid #d9e2ec;border-radius:5px;color:#355374;background:#fff}.goal textarea{resize:vertical}.goal-check{display:flex;align-items:start;gap:7px;font-size:11px;margin:10px 0}.goal-check input{flex-shrink:0;margin-top:4px}.goal-state{display:flex;justify-content:space-between;gap:10px;background:#edf3f9;border-radius:6px;padding:8px 10px;margin-top:12px}.goal-state span{font-size:10px}.goal-objective{font-weight:600;color:#355374}.goal-plan ol{padding:0;list-style:none}.goal-plan li{margin:9px 0}.goal-plan li>button{width:100%;text-align:left;margin:0;padding:10px;background:#f7fafc;overflow-wrap:anywhere}.goal-plan button[aria-pressed=true]{border-color:#6d91b9;background:#edf3f9}.goal-plan strong,.goal-plan span,.goal-plan small{display:block}.goal-plan span{font-size:10px;color:#7b8999}.goal-plan small{font-size:11px;margin-top:5px}.goal details{border-top:1px solid #e4eaf1;padding-top:9px;margin:12px 0}.goal summary{cursor:pointer;font-weight:600;color:#3c5978}.goal-trace ol{padding-left:22px}.goal-trace li{margin:9px 0}.goal-trace li>span{font-size:10px;margin-left:8px}.goal-trace p{font-size:11px}.goal-error{color:#9a5948}
</style>
