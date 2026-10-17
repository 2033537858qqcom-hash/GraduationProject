<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowUpRight, ChevronDown, RotateCw, Send, X } from '@lucide/vue'
import { api, ApiError, errorMessage } from '../lib/api'
import { assistantContext, assistantContextLabel, assistantSuggestions, safeAssistantDestination } from '../lib/assistant'
import type { AssistantAnswer, AssistantCapabilities } from '../lib/assistant'
import { currentUser } from '../stores/auth'
import AssistantLeaveDraft from './AssistantLeaveDraft.vue'
import AssistantCertificateDraft from './AssistantCertificateDraft.vue'
import AssistantRepairDraft from './AssistantRepairDraft.vue'
import AssistantHousingDraft from './AssistantHousingDraft.vue'
import BaseSelect from './BaseSelect.vue'
import { latestAssistantTask, mergeAssistantTurns, restoredTurnLabel } from '../lib/assistantSessions'
import type { AssistantSession, AssistantSessionDetail, AssistantTurn, AssistantTaskSummary, AssistantTaskView, AssistantQueryResult } from '../lib/assistantSessions'
import type { AssistantContext } from '../lib/assistant'

const route = useRoute()
const open = ref(false), hint = ref(true), loading = ref(false), busy = ref(false), error = ref(''), question = ref('')
const contextEnabled = ref(true), consent = ref(false), mode = ref<'LOCAL' | 'DEEPSEEK'>('LOCAL')
const capabilities = ref<AssistantCapabilities | null>(null)
const task = ref<'QUERY' | 'LEAVE' | 'CERTIFICATE' | 'REPAIR' | 'HOUSING' | 'HISTORY'>('QUERY'), savingDraft = ref(false), preparingDraft = ref(false)
const modeOptions = computed(() => [
  { value: 'LOCAL', label: task.value !== 'QUERY' ? '本地填写' : '本地查询' },
  { value: 'DEEPSEEK', label: `DeepSeek${capabilities.value?.deepseekReady ? '' : ' · 未连接'}`, disabled: !capabilities.value?.deepseekReady },
])
const session = ref<AssistantSessionDetail | null>(null), sessionLoading = ref(false)
const sessionList = ref<{ items: AssistantSession[]; total: number; page: number; pageSize: number }>({ items: [], total: 0, page: 1, pageSize: 10 })
const turns = ref<AssistantTurn[]>([]), requeryContext = ref<AssistantContext | null>(null)
const selectedTask = ref<AssistantTaskSummary | null>(null)
const queryKey = ref(crypto.randomUUID())
const leaveDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null), certificateDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null), repairDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null), housingDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null)
const canPrepareLeave = computed(() => currentUser.value?.permissions.includes('leave:apply'))
const canPrepareCertificate = computed(() => currentUser.value?.permissions.includes('certificate:apply'))
const canPrepareHousing = computed(() => currentUser.value?.permissions.includes('housing:read'))
const canPrepareRepair = computed(() => currentUser.value?.permissions.includes('repair:apply'))
const messages = ref<{ question: string; answer: AssistantAnswer }[]>([])
const input = ref<HTMLTextAreaElement | null>(null), launcher = ref<HTMLButtonElement | null>(null), history = ref<HTMLElement | null>(null)
let request: AbortController | null = null, generation = 0, capabilityGeneration = 0, sessionGeneration = 0
const context = computed(() => contextEnabled.value ? requeryContext.value ?? assistantContext(route.path, route.query.id) : null)
const suggestions = computed(() => assistantSuggestions(route.path, capabilities.value?.tools.map(t => t.name) ?? [], context.value))
const contextLabel = computed(() => assistantContextLabel(context.value))
const hintSubject = computed(() => {
  const p = currentUser.value?.permissions ?? []
  if (route.path.startsWith('/housing') && p.includes('housing:read')) return '住宿和调宿进度'
  if (route.path.includes('/repairs')) return '报修进度'
  if (route.path.includes('certificates')) return '证明进度'
  if (route.path.endsWith('/leave')) return '请假进度'
  if (p.includes('teaching:manage') && !p.includes('learning:read')) return '授课和消息'
  if (p.includes('leave:review') || p.includes('certificate:review')) return '岗位待办'
  if (route.path.includes('learning')) return '学习安排'
  return p.includes('portal:read') ? '办事和消息' : '办理指南和消息'
})
const canSend = computed(() => question.value.trim() && session.value && !sessionLoading.value && !busy.value && !loading.value && capabilities.value && (mode.value === 'LOCAL' || (capabilities.value.deepseekReady && consent.value)))
const avatarState = computed(() => busy.value || preparingDraft.value || savingDraft.value ? 'working' : messages.value.length ? 'reply' : open.value ? 'greeting' : 'idle')
async function loadCapabilities() {
  const current = ++capabilityGeneration
  loading.value = true; error.value = ''
  try {
    const result = await api<AssistantCapabilities>('/assistant/capabilities')
    if (current !== capabilityGeneration) return
    capabilities.value = result
    if (!result.deepseekReady) mode.value = 'LOCAL'
  } catch (cause) { if (current === capabilityGeneration) error.value = errorMessage(cause) }
  finally { if (current === capabilityGeneration) loading.value = false }
}
async function show() {
  open.value = true; hint.value = false
  await loadCapabilities()
  if (!session.value) await restoreLatest()
  else await refreshSession()
  await nextTick(); if (task.value === 'QUERY') input.value?.focus()
}
function stopWaiting() { generation++; request?.abort(); request = null; busy.value = false }
function close() { if (savingDraft.value) return; stopWaiting(); open.value = false; question.value = ''; consent.value = false; launcher.value?.focus() }
async function refreshSession(before?: number) {
  if (!session.value) return
  const sessionId = session.value.session.id, current = sessionGeneration
  const result = await api<AssistantSessionDetail>(`/assistant/sessions/${sessionId}${before ? `?before=${before}` : ''}`)
  if (current !== sessionGeneration || session.value?.session.id !== sessionId) return
  session.value = { ...result, hasOlder: before ? result.hasOlder : session.value.hasOlder || result.hasOlder }
  turns.value = before ? mergeAssistantTurns(result.turns, turns.value) : mergeAssistantTurns(turns.value, result.turns)
}
async function restoreLatest() {
  const current = ++sessionGeneration; sessionLoading.value = true; error.value = ''
  try {
    const page = await api<typeof sessionList.value>('/assistant/sessions?page=1&pageSize=10')
    let id = page.items[0]?.id
    if (!id) id = (await api<AssistantSession>('/assistant/sessions', { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID() }) })).id
    const result = await api<AssistantSessionDetail>(`/assistant/sessions/${id}`)
    if (current !== sessionGeneration) return
    session.value = result; turns.value = result.turns
  } catch (cause) { if (current === sessionGeneration) error.value = errorMessage(cause) }
  finally { if (current === sessionGeneration) sessionLoading.value = false }
}
async function allowSessionChange() { return (!leaveDraft.value || await leaveDraft.value.allowLeave()) && (!certificateDraft.value || await certificateDraft.value.allowLeave()) && (!repairDraft.value || await repairDraft.value.allowLeave()) && (!housingDraft.value || await housingDraft.value.allowLeave()) }
function taskLabel(kind: string) { return kind === 'LEAVE' ? '请假准备' : kind === 'CERTIFICATE' ? '在校证明准备' : kind === 'REPAIR' ? '报修准备' : kind === 'HOUSING' ? '调宿准备' : '准备任务' }
async function selectSession(id: number) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !await allowSessionChange()) return
  const current = ++sessionGeneration; sessionLoading.value = true; error.value = ''
  try {
    const result = await api<AssistantSessionDetail>(`/assistant/sessions/${id}`)
    if (current !== sessionGeneration) return
    session.value = result; turns.value = result.turns; messages.value = []; task.value = 'QUERY'; question.value = ''; consent.value = false; requeryContext.value = null; contextEnabled.value = true
  } catch (cause) { if (current === sessionGeneration) error.value = errorMessage(cause) }
  finally { if (current === sessionGeneration) sessionLoading.value = false }
}
async function newSession() {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !await allowSessionChange()) return
  const current = ++sessionGeneration; sessionLoading.value = true; error.value = ''
  try {
    const created = await api<AssistantSession>('/assistant/sessions', { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID() }) })
    if (current !== sessionGeneration) return
    session.value = { session: created, turns: [], tasks: [], hasOlder: false }; turns.value = []; messages.value = []; task.value = 'QUERY'; question.value = ''; consent.value = false; requeryContext.value = null
  } catch (cause) { if (current === sessionGeneration) error.value = errorMessage(cause) }
  finally { if (current === sessionGeneration) sessionLoading.value = false }
}
async function listSessions(page = 1) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value) return
  const current = ++sessionGeneration; task.value = 'HISTORY'; sessionLoading.value = true; error.value = ''
  try { const result = await api<typeof sessionList.value>(`/assistant/sessions?page=${page}&pageSize=10`); if (current === sessionGeneration) sessionList.value = result }
  catch (cause) { if (current === sessionGeneration) error.value = errorMessage(cause) }
  finally { if (current === sessionGeneration) sessionLoading.value = false }
}
async function olderTurns() {
  if (!turns.value[0] || sessionLoading.value) return
  sessionLoading.value = true
  try { await refreshSession(turns.value[0].id) } catch (cause) { error.value = errorMessage(cause) }
  finally { sessionLoading.value = false }
}
function resumeTaskId(kind: AssistantTaskSummary['kind']) {
  return selectedTask.value?.kind === kind ? selectedTask.value.id : latestAssistantTask(session.value?.tasks ?? [], kind)
}
async function resumeTask(value: AssistantTaskSummary) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !await allowSessionChange()) return
  selectedTask.value = value; task.value = value.kind
}
function taskUpdated(value: AssistantTaskView) {
  if (!session.value) return
  session.value.tasks = [value, ...session.value.tasks.filter(old => old.id !== value.id)]
  if (task.value === value.kind) selectedTask.value = value
}
function requery(turn: AssistantTurn) { question.value = turn.question; requeryContext.value = turn.context; contextEnabled.value = true; consent.value = false; queryKey.value = crypto.randomUUID(); input.value?.focus() }
async function send() {
  if (!canSend.value) return
  const text = question.value.trim(), current = ++generation
  request = new AbortController(); const controller = request
  busy.value = true; error.value = ''
  try {
    const result = await api<AssistantQueryResult>(`/assistant/sessions/${session.value!.session.id}/query`, { method: 'POST', signal: controller.signal, body: JSON.stringify({ requestKey: queryKey.value, input: { message: text, mode: mode.value, externalConsent: consent.value, context: context.value } }) })
    if (current !== generation) return
    turns.value = mergeAssistantTurns(turns.value, [result.turn]); const answer = result.answer
    if (answer) messages.value = [...messages.value.slice(-5), { question: text, answer }]
    else error.value = '这次请求已记录，未重复调用模型。可从历史提问手动重新查询。'
    question.value = ''; consent.value = false; queryKey.value = crypto.randomUUID(); await refreshSession()
    await nextTick(); history.value?.scrollTo({ top: history.value.scrollHeight, behavior: 'smooth' })
    input.value?.focus()
  } catch (cause) { if (current === generation && !controller.signal.aborted) { error.value = errorMessage(cause); if (!(cause instanceof ApiError && cause.status === 0)) queryKey.value = crypto.randomUUID(); try { await refreshSession() } catch { /* The original error remains visible. */ } } }
  finally { if (current === generation) { busy.value = false; request = null; consent.value = false } }
}
function choose(text: string) { question.value = text; input.value?.focus() }
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void send() }
}
watch([() => route.fullPath, () => currentUser.value?.permissions.join(',')], () => {
  stopWaiting(); messages.value = []; question.value = ''; consent.value = false; contextEnabled.value = true; requeryContext.value = null
  task.value = 'QUERY'; preparingDraft.value = false; savingDraft.value = false
  hint.value = true
  if (open.value && currentUser.value) { void loadCapabilities(); void refreshSession().catch(cause => { error.value = errorMessage(cause) }) }
})
watch(() => currentUser.value?.id, () => {
  stopWaiting(); sessionGeneration++; session.value = null; turns.value = []; messages.value = []; question.value = ''; consent.value = false; sessionLoading.value = false; open.value = false
  capabilities.value = null; capabilityGeneration++; loading.value = false; task.value = 'QUERY'; requeryContext.value = null
})
watch(mode, () => { consent.value = false; error.value = '' })
watch(question, () => { queryKey.value = crypto.randomUUID() })
watch(() => session.value?.session.id, () => { selectedTask.value = null })
onBeforeUnmount(() => { stopWaiting(); capabilityGeneration++; sessionGeneration++ })
</script>
<template>
  <aside v-if="currentUser?.permissions.includes('agent:query')" class="assistant-dock" :class="{ expanded: open }">
    <section v-show="open" class="assistant-panel" role="dialog" aria-label="AI 对话" @keydown.esc.stop="close">
      <header class="assistant-head"><div><strong>AI 对话</strong><small>{{ task === 'HISTORY' ? '恢复本人对话与已核对的进度' : task !== 'QUERY' ? '整理资料，确认后保存本人草稿' : '查询当前账号可见的信息' }}</small></div><button class="assistant-icon" aria-label="收起 AI 对话" :disabled="savingDraft" @click="close"><ChevronDown :size="19" /></button></header>
      <div class="assistant-session-bar"><span>{{ session?.session.title ?? '正在恢复本人对话' }}</span><button :disabled="busy || preparingDraft || savingDraft || sessionLoading" @click="listSessions()">历史对话</button><button :disabled="busy || preparingDraft || savingDraft || sessionLoading" @click="newSession">新对话</button></div>
      <nav v-if="canPrepareLeave || canPrepareCertificate || canPrepareRepair || canPrepareHousing" class="assistant-tasks" aria-label="AI 服务方式"><button :aria-pressed="task === 'QUERY'" :disabled="busy || preparingDraft || savingDraft" @click="task = 'QUERY'">查询信息</button><button v-if="canPrepareLeave" :aria-pressed="task === 'LEAVE'" :disabled="busy || preparingDraft || savingDraft" @click="task = 'LEAVE'">准备请假</button><button v-if="canPrepareCertificate" :aria-pressed="task === 'CERTIFICATE'" :disabled="busy || preparingDraft || savingDraft" @click="task = 'CERTIFICATE'">准备证明</button><button v-if="canPrepareRepair" :aria-pressed="task === 'REPAIR'" :disabled="busy || preparingDraft || savingDraft" @click="task = 'REPAIR'">准备报修</button><button v-if="canPrepareHousing" :aria-pressed="task === 'HOUSING'" :disabled="busy || preparingDraft || savingDraft" @click="task = 'HOUSING'">准备调宿</button></nav>
      <div v-if="task !== 'HISTORY'" class="assistant-mode"><label>回答方式<BaseSelect v-model="mode" :options="modeOptions" compact :disabled="busy || loading || preparingDraft || savingDraft" aria-label="回答方式" /></label><span>{{ busy || preparingDraft ? '正在处理' : mode === 'LOCAL' ? task !== 'QUERY' ? '表单核对' : '关键词识别' : '自然语言识别' }}</span></div>
      <p v-if="loading" class="assistant-status" role="status">正在读取可用查询…</p>
      <p v-if="sessionLoading" class="assistant-status" role="status">正在恢复对话记录…</p>
      <AssistantLeaveDraft v-if="canPrepareLeave" v-show="task === 'LEAVE'" ref="leaveDraft" :active="open && task === 'LEAVE' && !sessionLoading" :mode="mode" :model-ready="!!capabilities?.deepseekReady" :session-id="session?.session.id ?? null" :resume-task-id="resumeTaskId('LEAVE')" @updated="taskUpdated" @saving="savingDraft = $event" @working="preparingDraft = $event" />
      <AssistantCertificateDraft v-if="canPrepareCertificate" v-show="task === 'CERTIFICATE'" ref="certificateDraft" :active="open && task === 'CERTIFICATE' && !sessionLoading" :mode="mode" :model-ready="!!capabilities?.deepseekReady" :session-id="session?.session.id ?? null" :resume-task-id="resumeTaskId('CERTIFICATE')" @updated="taskUpdated" @saving="savingDraft = $event" @working="preparingDraft = $event" />
      <AssistantHousingDraft v-if="canPrepareHousing" v-show="task === 'HOUSING'" ref="housingDraft" :active="open && task === 'HOUSING' && !sessionLoading" :mode="mode" :model-ready="!!capabilities?.deepseekReady" :session-id="session?.session.id ?? null" :resume-task-id="resumeTaskId('HOUSING')" @updated="taskUpdated" @saving="savingDraft = $event" @working="preparingDraft = $event" />
      <AssistantRepairDraft v-if="canPrepareRepair" v-show="task === 'REPAIR'" ref="repairDraft" :active="open && task === 'REPAIR' && !sessionLoading" :mode="mode" :model-ready="!!capabilities?.deepseekReady" :session-id="session?.session.id ?? null" :resume-task-id="resumeTaskId('REPAIR')" @updated="taskUpdated" @saving="savingDraft = $event" @working="preparingDraft = $event" />
      <div v-if="task === 'HISTORY'" class="assistant-history assistant-session-list">
        <h2>本人历史对话</h2><p>选择一条继续。恢复时重新核对权限和任务状态。</p>
        <button v-for="item in sessionList.items" :key="item.id" :disabled="sessionLoading" @click="selectSession(item.id)"><strong>{{ item.title }}</strong><span>{{ item.updatedAt.replace('T', ' ').slice(0,16) }}{{ item.id === session?.session.id ? ' · 当前对话' : '' }}</span></button>
        <p v-if="!sessionList.items.length && !sessionLoading">暂无历史对话。</p>
        <div class="assistant-session-pages"><button :disabled="sessionLoading || sessionList.page <= 1" @click="listSessions(sessionList.page - 1)">上一页</button><span>第 {{ sessionList.page }} 页</span><button :disabled="sessionLoading || sessionList.page * sessionList.pageSize >= sessionList.total" @click="listSessions(sessionList.page + 1)">下一页</button></div>
        <h2 v-if="session?.tasks.length" class="assistant-task-list-heading">当前对话的准备任务</h2>
        <button v-for="item in session?.tasks ?? []" :key="`task-${item.id}`" :disabled="sessionLoading" @click="resumeTask(item)"><strong>{{ taskLabel(item.kind) }} #{{ item.id }}</strong><span>{{ item.stage === 'LINKED' ? '已关联申请 · 恢复后核对最新状态' : item.version ? '已暂存预览 · 恢复后重新核对' : '待补齐信息' }}</span></button>
      </div>
      <div v-show="task === 'QUERY'" ref="history" class="assistant-history" aria-live="polite" aria-relevant="additions">
        <div v-if="!messages.length && !turns.length" class="assistant-intro"><span class="assistant-intro-mark">AI</span><h2>从一个问题开始</h2><p>可以查办理指南、学习安排和办事进度。{{ canPrepareLeave || canPrepareCertificate || canPrepareRepair || canPrepareHousing ? '也可以准备申请，核对后保存本人草稿。' : '办理请在原业务页面确认。' }}</p><small>规则与个人记录均为毕业设计演示。</small></div>
        <button v-if="session?.hasOlder" class="assistant-older" :disabled="sessionLoading" @click="olderTurns">更早的提问</button>
        <article v-for="turn in turns.filter(item => !messages.some(entry => entry.answer.runId === item.runId))" :key="turn.id" class="assistant-restored"><p>{{ turn.question }}</p><small>{{ restoredTurnLabel(turn.status) }} · {{ turn.createdAt.replace('T',' ').slice(0,16) }}</small><button :disabled="busy || sessionLoading" @click="requery(turn)">重新查询</button></article>
        <article v-for="entry in messages" :key="entry.answer.runId" class="assistant-turn">
          <p class="assistant-question">{{ entry.question }}</p>
          <p class="assistant-summary">{{ entry.answer.summary }}</p>
          <section v-for="result in entry.answer.results" :key="result.name" class="assistant-result" :class="{ 'query-issue': result.status !== 'OK' }">
            <h3>{{ result.title }}</h3><p>{{ result.message }}</p>
            <RouterLink v-for="item in result.items" :key="item.key" :to="safeAssistantDestination(item.targetPath) ?? '#'" class="assistant-fact" :class="{ 'no-target': !safeAssistantDestination(item.targetPath) }"><strong>{{ item.title }}</strong><span>{{ item.description }}</span><ArrowUpRight v-if="safeAssistantDestination(item.targetPath)" :size="13" /></RouterLink>
            <div v-for="source in result.sources" :key="source.slug" class="assistant-source"><strong>{{ source.title }}</strong><p>{{ source.body.slice(0, 220) }}{{ source.body.length > 220 ? '…' : '' }}</p><RouterLink v-if="safeAssistantDestination(source.targetPath)" :to="source.targetPath">来源 · V{{ source.version }} · 查看完整指南 <ArrowUpRight :size="12" /></RouterLink></div>
            <RouterLink v-if="safeAssistantDestination(result.targetPath) && !result.items.length" :to="result.targetPath!" class="assistant-more">查看业务页面 <ArrowUpRight :size="13" /></RouterLink>
          </section>
          <small class="assistant-time">{{ entry.answer.modelUsed ? 'DeepSeek 规划 · 本地执行' : entry.answer.mode === 'LOCAL' ? '本地查询' : '本地边界检查 · 未调用模型' }} · {{ entry.answer.queriedAt.replace('T', ' ').slice(0, 19) }}</small>
        </article>
      </div>
      <div v-if="context && task === 'QUERY'" class="assistant-context"><span>当前上下文：{{ contextLabel }}</span><button class="assistant-icon" aria-label="清除当前业务上下文" @click="contextEnabled = false"><X :size="13" /></button></div>
      <div v-show="task === 'QUERY'" class="assistant-prompts"><button v-for="text in suggestions.slice(0, 2)" :key="text" :disabled="busy" @click="choose(text)">{{ text }}</button></div>
      <form v-show="task === 'QUERY'" class="assistant-compose" @submit.prevent="send">
        <label v-if="mode === 'DEEPSEEK'" class="assistant-consent"><input v-model="consent" type="checkbox" :disabled="busy" />同意将本次问题及页面业务类型发送至 DeepSeek。查询结果和从页面读取的编号留在本地。</label>
        <label class="sr-only" for="assistant-question">输入查询问题</label><div class="assistant-input-row"><textarea id="assistant-question" ref="input" v-model="question" rows="2" maxlength="1000" placeholder="输入你想查询的事项…" :disabled="busy || loading" @keydown="keydown" /><button type="submit" class="assistant-send" :disabled="!canSend" aria-label="发送查询"><Send :size="17" /></button></div>
        <div class="assistant-compose-note"><span>Enter 发送 · Shift + Enter 换行</span><button v-if="busy" type="button" @click="stopWaiting">停止等待</button></div>
        <p v-if="error" class="assistant-error" role="alert">{{ error }} <button v-if="!capabilities" type="button" aria-label="重新读取可用查询" @click="loadCapabilities"><RotateCw :size="13" />重试</button></p>
      </form>
      <p v-if="error && task !== 'QUERY'" class="assistant-error assistant-session-error" role="alert">{{ error }}</p>
      <p v-if="!session && error" class="assistant-session-error"><button :disabled="sessionLoading" @click="restoreLatest">重试恢复对话</button></p>
    </section>
    <div v-if="hint && !open" class="assistant-hint"><button @click="show">可以帮你查{{ hintSubject }}</button><button aria-label="关闭 AI 查询提示" @click="hint = false"><X :size="11" /></button></div>
    <button ref="launcher" class="assistant-launcher" :data-state="avatarState" :aria-expanded="open" :aria-label="open ? '收起 AI 对话' : '打开 AI 对话'" :disabled="savingDraft" @click="open ? close() : show()">
      <svg viewBox="0 0 64 64" aria-hidden="true" class="assistant-avatar">
        <ellipse cx="32" cy="55" rx="14" ry="3" class="avatar-shadow" />
        <g class="avatar-body"><path d="M22 39Q32 35 42 39L44 50Q32 57 20 50Z" fill="#e7eef7" stroke="#2b527e" stroke-width="1.5" /><path d="M26 46h12m-10 4h8" stroke="#99adc5" stroke-width="1.6" stroke-linecap="round" /><path class="avatar-arm" d="M43 41q10 2 10-5" fill="none" stroke="#2b527e" stroke-width="3" stroke-linecap="round" /><path d="M21 42q-8 4-10 0" fill="none" stroke="#2b527e" stroke-width="3" stroke-linecap="round" />
          <g class="avatar-head"><path d="M32 12V7" stroke="#2b527e" stroke-width="2" /><circle cx="32" cy="6" r="2.5" fill="#b6cfdd" /><rect x="12" y="15" width="40" height="27" rx="12" fill="#f7fbff" stroke="#2b527e" stroke-width="1.7" /><rect x="18" y="22" width="28" height="12" rx="6" fill="#2b527e" /><g class="avatar-eyes"><circle cx="25" cy="28" r="2.2" fill="#dbeaf5" /><circle cx="39" cy="28" r="2.2" fill="#dbeaf5" /></g><path d="M28 36q4 3 8 0" fill="none" stroke="#6c89a8" stroke-width="1.5" stroke-linecap="round" /></g>
        </g>
      </svg>
      <span v-if="busy" class="assistant-working-dot" />
    </button>
  </aside>
</template>
<style scoped>
.assistant-session-bar{display:flex;align-items:center;gap:8px;padding:5px 16px 9px;font-size:10px;color:#7a899a;flex-shrink:0}.assistant-session-bar span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.assistant-session-bar button,.assistant-older,.assistant-restored button{border:0;background:transparent;color:#355a80;font:inherit;font-size:10px;padding:3px;cursor:pointer;white-space:nowrap}.assistant-session-list h2{font-size:14px;color:#355a80;margin:0 0 8px}.assistant-session-list>p{font-size:11px;line-height:1.7;color:#7a899a}.assistant-session-list>button{display:block;width:100%;border:1px solid #e2e9f0;border-radius:7px;background:#fafbfd;padding:10px;text-align:left;margin:8px 0;color:#405b76;cursor:pointer}.assistant-session-list strong,.assistant-session-list span{display:block;overflow-wrap:anywhere}.assistant-session-list strong{font-size:12px;margin-bottom:4px}.assistant-session-list span{font-size:10px;color:#8795a7}.assistant-session-pages{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:16px}.assistant-session-pages button{border:1px solid #dce4ed;background:white;color:#355a80;font-size:10px;padding:4px 7px;border-radius:4px}.assistant-session-bar button:disabled,.assistant-session-list button:disabled,.assistant-restored button:disabled{opacity:.5;cursor:default}.assistant-restored{border-bottom:1px solid #edf0f4;padding:10px 0}.assistant-restored p{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere;color:#405b76;margin:0 0 5px}.assistant-restored small{display:block;font-size:10px;color:#8795a7;line-height:1.6}.assistant-session-error{padding:5px 12px 9px;flex-shrink:0}.assistant-session-error button{border:0;background:none;color:#355a80;font-size:11px;cursor:pointer}
.assistant-tasks{display:flex;flex-wrap:wrap;gap:5px;padding:0 16px 8px;flex-shrink:0}.assistant-tasks button{border:1px solid transparent;border-radius:5px;background:#f4f6f9;color:#74869b;font:inherit;font-size:11px;min-height:28px;padding:4px 9px;cursor:pointer}.assistant-tasks button[aria-pressed=true]{border-color:#cfdae7;background:#edf3f9;color:#31577f}.assistant-tasks button:disabled{opacity:.5;cursor:default}
.assistant-session-list .assistant-task-list-heading{margin-top:24px;padding-top:16px;border-top:1px solid #e8edf3;font-size:12px}
.assistant-dock{position:fixed;right:22px;bottom:20px;z-index:70;color:#293e55;font-size:13px}
.assistant-launcher{width:60px;height:60px;display:grid;place-items:center;margin-left:auto;border:1px solid #d4dfec;border-radius:50%;background:#fff;box-shadow:0 6px 22px #213d5e1a;cursor:pointer;position:relative;transition:box-shadow .2s,transform .2s}
.assistant-launcher:hover{transform:translateY(-2px);box-shadow:0 8px 26px #213d5e28}.assistant-launcher:focus-visible{outline:3px solid #8aabc9;outline-offset:3px}.assistant-avatar{width:54px;height:54px;overflow:visible}.avatar-body{animation:avatar-float 4s ease-in-out infinite;transform-origin:32px 36px}.avatar-eyes{animation:avatar-blink 6s infinite;transform-origin:32px 28px}.avatar-arm{transform-origin:43px 41px}.assistant-launcher:hover .avatar-arm,[data-state=greeting] .avatar-arm{animation:avatar-wave .65s ease-in-out 3}.assistant-launcher[data-state=working] .avatar-head{animation:avatar-think 1.2s ease-in-out infinite;transform-origin:32px 30px}.assistant-launcher[data-state=reply] .avatar-body{animation:avatar-float 3s ease-in-out infinite}.avatar-shadow{fill:#cad6e2;opacity:.6}.assistant-working-dot{position:absolute;right:3px;top:4px;width:8px;height:8px;background:#2b527e;border:2px solid white;border-radius:50%}
.assistant-panel{position:absolute;right:0;bottom:76px;width:min(366px,calc(100vw - 32px));height:min(550px,calc(100dvh - 112px));display:flex;flex-direction:column;background:#fff;border:1px solid #dce4ed;border-radius:14px;box-shadow:0 14px 48px #142d4e26;overflow:hidden;animation:assistant-appear .2s ease-out}
.assistant-head{padding:14px 16px 10px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #edf0f4}.assistant-head strong{display:block;font-size:15px}.assistant-head small{display:block;color:#7c8999;font-size:11px;margin-top:3px}.assistant-icon{width:30px;height:30px;border:0;border-radius:7px;background:transparent;color:#708398;display:grid;place-items:center;cursor:pointer}.assistant-icon:hover{background:#eff4f8}
.assistant-mode{padding:9px 16px;display:flex;align-items:center;justify-content:space-between;background:#f7f9fc;font-size:11px;color:#7a899a}.assistant-mode label{display:flex;flex-direction:row;align-items:center;gap:7px;white-space:nowrap}.assistant-mode select{max-width:144px;height:27px;min-height:0;border:1px solid #dce4ed;border-radius:5px;background:white;font-size:11px;color:#355374;padding:2px 6px}.assistant-status{padding:8px 16px;margin:0;color:#76879b}
.assistant-history{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;padding:14px 16px;scrollbar-width:thin}.assistant-intro{text-align:center;padding:15px 10px 8px;color:#6e8197;line-height:1.7}.assistant-intro h2{font-family:inherit;font-size:16px;font-weight:600;color:#334f6e;margin:10px 0 8px}.assistant-intro p{font-size:12px;margin:0 0 8px}.assistant-intro small{font-size:10px;color:#8d98a7}.assistant-intro-mark{display:inline-grid;place-items:center;width:38px;height:38px;background:#edf3f9;border-radius:12px;color:#2b527e;font-size:14px;letter-spacing:2px}
.assistant-turn{margin-bottom:20px}.assistant-question{padding:9px 12px;border-radius:10px 10px 3px 10px;background:#edf3f9;margin:0 0 10px 25px;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.assistant-summary{font-size:12px;color:#62758a;line-height:1.6;margin:0 0 9px}.assistant-result{padding:10px;border:1px solid #e2e9f0;border-radius:8px;margin-top:8px}.assistant-result h3{font-family:inherit;font-size:12px;margin:0 0 6px;color:#2b527e}.assistant-result>p,.assistant-source p{font-size:12px;line-height:1.7;white-space:pre-wrap;margin:0 0 7px;color:#6e7f90;overflow-wrap:anywhere}.assistant-result.query-issue{background:#fbfaf6;border-color:#e9e3d4}
.assistant-fact{position:relative;display:block;padding:8px 14px 8px 0;border-top:1px solid #eef1f4;font-size:11px;text-decoration:none;color:#405b76}.assistant-fact strong{display:block;font-size:12px;margin-bottom:4px}.assistant-fact span{font-size:11px;color:#7c8999;overflow-wrap:anywhere}.assistant-fact svg{position:absolute;right:0;top:9px}.assistant-fact:hover{color:#1c4777}.assistant-fact.no-target{pointer-events:none}
.assistant-source{margin-top:9px;padding-top:8px;border-top:1px solid #edf0f4}.assistant-source strong{font-size:12px}.assistant-source a,.assistant-more{font-size:10px;display:inline-flex;align-items:center;gap:3px;color:#335b83;text-decoration:none;margin-top:5px}.assistant-time{display:block;font-size:9px;color:#95a0ae;margin-top:8px}
.assistant-context{margin:0 16px 6px;padding:4px 6px 4px 9px;background:#f2f6fa;border-radius:5px;display:flex;align-items:center;justify-content:space-between;gap:5px;font-size:10px;color:#688099}.assistant-context .assistant-icon{width:24px;height:24px}
.assistant-prompts{padding:0 16px 8px;display:flex;gap:5px;flex-wrap:wrap}.assistant-prompts button{border:1px solid #dfe7ef;background:#fff;border-radius:14px;padding:4px 8px;font-size:10px;color:#698198;cursor:pointer}.assistant-prompts button:hover{border-color:#a4bbd1;background:#f4f8fb}.assistant-prompts button:disabled{opacity:.5;cursor:default}
.assistant-compose{padding:10px 12px;border-top:1px solid #edf0f4;margin:0;display:block}.assistant-input-row{display:flex;gap:7px;align-items:flex-end}.assistant-input-row textarea{flex:1;width:0;min-width:0;min-height:64px;max-height:112px;resize:vertical;border:1px solid #dce4ed;border-radius:7px;background:#f8fafc;padding:9px 10px;font-size:12px;line-height:1.5;color:#354e69;font-family:inherit}.assistant-input-row textarea:focus{outline:2px solid #b7cde1;outline-offset:0}.assistant-send{width:35px;height:35px;border:0;border-radius:7px;background:#2b527e;color:white;display:grid;place-items:center;cursor:pointer;margin-bottom:2px}.assistant-send:disabled{opacity:.35;cursor:default}.assistant-compose-note{display:flex;justify-content:space-between;margin-top:6px;font-size:9px;color:#929eac}.assistant-compose-note button{border:0;background:none;color:#6a829b;font-size:10px;padding:0;cursor:pointer}.assistant-consent{display:flex;flex-direction:row;gap:6px;align-items:flex-start;font-size:10px;line-height:1.6;color:#7a8899;margin-bottom:8px}.assistant-consent input{width:14px;min-height:0;height:14px;flex-shrink:0;margin-top:2px;accent-color:#2b527e}
.assistant-error{font-size:11px;color:#98684a;margin:8px 0 0;line-height:1.6}.assistant-error button{border:0;background:none;padding:0;color:#2b527e;display:inline-flex;align-items:center;gap:3px;font-size:11px;cursor:pointer}
.assistant-hint{position:absolute;bottom:14px;right:71px;display:flex;align-items:center;white-space:nowrap;background:#fff;border:1px solid #dce4ed;border-radius:8px;box-shadow:0 4px 14px #213d5e0d;padding:2px 4px 2px 8px;animation:assistant-appear .3s}.assistant-hint button{background:transparent;border:0;padding:6px 3px;font-size:11px;color:#698198;cursor:pointer;display:inline-flex;align-items:center}
@keyframes avatar-float{0%,100%{transform:translateY(0)}50%{transform:translateY(-2px)}}@keyframes avatar-blink{0%,44%,48%,100%{transform:scaleY(1)}46%{transform:scaleY(.12)}}@keyframes avatar-wave{0%,100%{transform:rotate(0)}50%{transform:rotate(-22deg)}}@keyframes avatar-think{0%,100%{transform:rotate(-3deg)}50%{transform:rotate(3deg)}}@keyframes assistant-appear{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:translateY(0)}}
@media(max-width:600px){.assistant-dock{right:12px;bottom:12px}.assistant-launcher{width:54px;height:54px}.assistant-avatar{width:48px;height:48px}.assistant-panel{position:fixed;left:12px;right:12px;bottom:78px;width:auto;height:min(540px,calc(100dvh - 100px))}.assistant-hint{right:64px;font-size:10px}.assistant-head{padding:12px 14px 9px}}
@media(prefers-reduced-motion:reduce){.assistant-avatar *,.assistant-panel,.assistant-hint{animation:none!important}.assistant-launcher{transition:none}.assistant-history{scroll-behavior:auto}}
</style>
