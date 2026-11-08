<script setup lang="ts">
import { confirmDialog } from '../composables/useConfirm';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowUpRight, Bot, ChevronDown, RotateCw, Send, Sparkles, X } from '@lucide/vue';
import { api, ApiError, errorMessage } from '../lib/api';
import {
  assistantContext,
  assistantContextLabel,
  assistantSuggestions,
  safeAssistantDestination,
} from '../lib/assistant';
import type { AssistantAnswer, AssistantCapabilities } from '../lib/assistant';
import { currentUser } from '../stores/auth';
import AssistantLeaveDraft from './AssistantLeaveDraft.vue';
import AssistantCertificateDraft from './AssistantCertificateDraft.vue';
import AssistantRepairDraft from './AssistantRepairDraft.vue';
import AssistantHousingDraft from './AssistantHousingDraft.vue';
import AssistantMission from './AssistantMission.vue';
import AssistantGoal from './AssistantGoal.vue';
import BaseSelect from './BaseSelect.vue';
import {
  latestAssistantTask,
  mergeAssistantTurns,
  queryStartedGoal,
  restoredTurnLabel,
  resumeGoalFromSummaries,
} from '../lib/assistantSessions';
import type {
  AssistantSession,
  AssistantSessionDetail,
  AssistantTurn,
  AssistantTaskSummary,
  AssistantTaskView,
  AssistantQueryResult,
} from '../lib/assistantSessions';
import type { GoalSummary } from '../lib/assistantGoals';
import type { AssistantContext } from '../lib/assistant';
import { publicReducedMotion } from '../lib/publicMotion';

const route = useRoute();
const router = useRouter();
const open = ref(false),
  hint = ref(true),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  question = ref('');
const contextEnabled = ref(true),
  consent = ref(false),
  mode = ref<'LOCAL' | 'DEEPSEEK'>('LOCAL');
const capabilities = ref<AssistantCapabilities | null>(null);
const task = ref<'QUERY' | 'LEAVE' | 'CERTIFICATE' | 'REPAIR' | 'HOUSING' | 'AGENT' | 'GOAL' | 'HISTORY'>('QUERY'),
  savingDraft = ref(false),
  preparingDraft = ref(false);
const serviceModeLabel = computed(
  () =>
    ({
      QUERY: '对话查询',
      GOAL: '自主办理',
      AGENT: '单项任务',
      LEAVE: '准备请假',
      CERTIFICATE: '准备证明',
      REPAIR: '准备报修',
      HOUSING: '准备调宿',
      HISTORY: '历史对话',
    })[task.value],
);
const modeOptions = computed(() => [
  { value: 'LOCAL', label: task.value !== 'QUERY' ? '本地填写' : '本地查询' },
  {
    value: 'DEEPSEEK',
    label: `DeepSeek${capabilities.value?.deepseekReady ? '' : ' · 未连接'}`,
    disabled: !capabilities.value?.deepseekReady,
  },
]);
const session = ref<AssistantSessionDetail | null>(null),
  sessionLoading = ref(false);
const sessionList = ref<{ items: AssistantSession[]; total: number; page: number; pageSize: number }>({
  items: [],
  total: 0,
  page: 1,
  pageSize: 10,
});
const turns = ref<AssistantTurn[]>([]),
  requeryContext = ref<AssistantContext | null>(null);
const selectedTask = ref<AssistantTaskSummary | null>(null);
const queryKey = ref(crypto.randomUUID());
const leaveDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null),
  certificateDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null),
  repairDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null),
  housingDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null);
const canPrepareLeave = computed(() => currentUser.value?.permissions.includes('leave:apply'));
const canPrepareCertificate = computed(() => currentUser.value?.permissions.includes('certificate:apply'));
const canPrepareHousing = computed(() => currentUser.value?.permissions.includes('housing:read'));
const canPrepareRepair = computed(() => currentUser.value?.permissions.includes('repair:apply'));
const canRunMission = computed(
  () =>
    !!currentUser.value?.permissions.includes('certificate:apply') ||
    !!currentUser.value?.permissions.includes('leave:apply') ||
    !!currentUser.value?.permissions.includes('repair:apply') ||
    ['portal:read', 'reservation:apply'].every(permission => currentUser.value?.permissions.includes(permission)),
);
const missionDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null);
const goalDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null);
const messages = ref<{ question: string; answer: AssistantAnswer }[]>([]);
type AutoAction = 'none' | 'peek' | 'bounce' | 'sparkle' | 'stretch';
type ScrollMotion = 'none' | 'down' | 'up' | 'settle';
const autoAction = ref<AutoAction>('none');
const scrollMotion = ref<ScrollMotion>('none');
let autoActionTimer: number | null = null,
  autoActionResetTimer: number | null = null;
let scrollEndTimer: number | null = null,
  scrollSettleTimer: number | null = null;
let lastScrollY = 0,
  isScrolling = false;
const input = ref<HTMLTextAreaElement | null>(null),
  launcher = ref<HTMLButtonElement | null>(null),
  history = ref<HTMLElement | null>(null);
let request: AbortController | null = null,
  generation = 0,
  capabilityGeneration = 0,
  sessionGeneration = 0;
const context = computed(() =>
  contextEnabled.value ? (requeryContext.value ?? assistantContext(route.path, route.query.id)) : null,
);
const suggestions = computed(() =>
  assistantSuggestions(route.path, capabilities.value?.tools.map(t => t.name) ?? [], context.value),
);
const contextLabel = computed(() => assistantContextLabel(context.value));
const hintSubject = computed(() => {
  const p = currentUser.value?.permissions ?? [];
  if (route.path === '/campus/explore') return '校园地点与路线';
  if (route.path === '/portal/payments') return '账单和宿舍水电';
  if (route.path === '/finance/payments') return '财务对账待办';
  if (route.path.startsWith('/housing') && p.includes('housing:read')) return '住宿和调宿进度';
  if (route.path.includes('/repairs')) return '报修进度';
  if (route.path.includes('certificates')) return '证明进度';
  if (route.path.endsWith('/leave')) return '请假进度';
  if (p.includes('teaching:manage') && !p.includes('learning:read')) return '授课和消息';
  if (p.includes('leave:review') || p.includes('certificate:review')) return '岗位待办';
  if (route.path.includes('learning')) return '学习安排';
  return p.includes('portal:read') ? '办事和消息' : '办理指南和消息';
});
const canSend = computed(
  () =>
    question.value.trim() &&
    session.value &&
    !sessionLoading.value &&
    !busy.value &&
    !loading.value &&
    capabilities.value &&
    (mode.value === 'LOCAL' || (capabilities.value.deepseekReady && consent.value)),
);
const avatarState = computed(() =>
  busy.value || preparingDraft.value || savingDraft.value
    ? 'working'
    : messages.value.length
      ? 'reply'
      : open.value
        ? 'greeting'
        : 'idle',
);
async function loadCapabilities() {
  const current = ++capabilityGeneration;
  loading.value = true;
  error.value = '';
  try {
    const result = await api<AssistantCapabilities>('/assistant/capabilities');
    if (current !== capabilityGeneration) return;
    capabilities.value = result;
    if (!result.deepseekReady) mode.value = 'LOCAL';
  } catch (cause) {
    if (current === capabilityGeneration) error.value = errorMessage(cause);
  } finally {
    if (current === capabilityGeneration) loading.value = false;
  }
}
async function show() {
  open.value = true;
  hint.value = false;
  await loadCapabilities();
  if (!open.value) return;
  try {
    if (!session.value) await restoreLatest();
    else await refreshSession();
  } catch (cause) {
    if (open.value) error.value = errorMessage(cause);
  }
  await nextTick();
  if (open.value && task.value === 'QUERY') input.value?.focus();
}
function stopWaiting() {
  generation++;
  request?.abort();
  request = null;
  busy.value = false;
}
function close() {
  if (savingDraft.value) return;
  stopWaiting();
  open.value = false;
  question.value = '';
  consent.value = false;
  launcher.value?.focus();
}
async function followResult(event: MouseEvent, value: unknown) {
  const target = safeAssistantDestination(value);
  if (!target || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  if (savingDraft.value || !(await allowSessionChange())) return;
  close();
  await router.push(target);
}
async function refreshSession(before?: number) {
  if (!session.value) return;
  const sessionId = session.value.session.id,
    current = sessionGeneration;
  const result = await api<AssistantSessionDetail>(
    `/assistant/sessions/${sessionId}${before ? `?before=${before}` : ''}`,
  );
  if (current !== sessionGeneration || session.value?.session.id !== sessionId) return;
  session.value = { ...result, hasOlder: before ? result.hasOlder : session.value.hasOlder || result.hasOlder };
  turns.value = before
    ? mergeAssistantTurns(result.turns, turns.value)
    : mergeAssistantTurns(turns.value, result.turns);
}
async function restoreLatest() {
  const current = ++sessionGeneration;
  sessionLoading.value = true;
  error.value = '';
  try {
    const page = await api<typeof sessionList.value>('/assistant/sessions?page=1&pageSize=10');
    let id = page.items[0]?.id;
    if (!id)
      id = (
        await api<AssistantSession>('/assistant/sessions', {
          method: 'POST',
          body: JSON.stringify({ requestKey: crypto.randomUUID() }),
        })
      ).id;
    const result = await api<AssistantSessionDetail>(`/assistant/sessions/${id}`);
    if (current !== sessionGeneration) return;
    session.value = result;
    turns.value = result.turns;
    await resumeSessionGoal(result.session.id);
  } catch (cause) {
    if (current === sessionGeneration) error.value = errorMessage(cause);
  } finally {
    if (current === sessionGeneration) sessionLoading.value = false;
  }
}
async function allowSessionChange() {
  return (
    (!leaveDraft.value || (await leaveDraft.value.allowLeave())) &&
    (!certificateDraft.value || (await certificateDraft.value.allowLeave())) &&
    (!repairDraft.value || (await repairDraft.value.allowLeave())) &&
    (!housingDraft.value || (await housingDraft.value.allowLeave())) &&
    (!missionDraft.value || (await missionDraft.value.allowLeave())) &&
    (!goalDraft.value || (await goalDraft.value.allowLeave()))
  );
}
async function resumeSessionGoal(sessionId: number) {
  if (!canRunMission.value) return;
  try {
    const goals = await api<GoalSummary[]>(`/assistant/sessions/${sessionId}/goals`);
    if (resumeGoalFromSummaries(goals)) task.value = 'GOAL';
  } catch {
    /* Keep the query conversation if the goal list cannot be read. */
  }
}
function taskLabel(kind: string) {
  return kind === 'LEAVE'
    ? '请假准备'
    : kind === 'CERTIFICATE'
      ? '在校证明准备'
      : kind === 'REPAIR'
        ? '报修准备'
        : kind === 'HOUSING'
          ? '调宿准备'
          : '准备任务';
}
async function selectSession(id: number) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !(await allowSessionChange()))
    return;
  const current = ++sessionGeneration;
  sessionLoading.value = true;
  error.value = '';
  try {
    const result = await api<AssistantSessionDetail>(`/assistant/sessions/${id}`);
    if (current !== sessionGeneration) return;
    session.value = result;
    turns.value = result.turns;
    messages.value = [];
    task.value = 'QUERY';
    question.value = '';
    consent.value = false;
    requeryContext.value = null;
    contextEnabled.value = true;
    await resumeSessionGoal(result.session.id);
  } catch (cause) {
    if (current === sessionGeneration) error.value = errorMessage(cause);
  } finally {
    if (current === sessionGeneration) sessionLoading.value = false;
  }
}
async function newSession() {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !(await allowSessionChange()))
    return;
  const current = ++sessionGeneration;
  sessionLoading.value = true;
  error.value = '';
  try {
    const created = await api<AssistantSession>('/assistant/sessions', {
      method: 'POST',
      body: JSON.stringify({ requestKey: crypto.randomUUID() }),
    });
    if (current !== sessionGeneration) return;
    session.value = { session: created, turns: [], tasks: [], hasOlder: false };
    turns.value = [];
    messages.value = [];
    task.value = 'QUERY';
    question.value = '';
    consent.value = false;
    requeryContext.value = null;
  } catch (cause) {
    if (current === sessionGeneration) error.value = errorMessage(cause);
  } finally {
    if (current === sessionGeneration) sessionLoading.value = false;
  }
}
async function deleteSession(item: AssistantSession) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !(await allowSessionChange()))
    return;
  if (
    !(await confirmDialog(
      `删除对话“${item.title}”？历史提问和准备任务将删除，该对话的进度跟踪会停止，已保存的业务申请仍保留。`,
    ))
  )
    return;
  sessionLoading.value = true;
  error.value = '';
  try {
    await api<void>(`/assistant/sessions/${item.id}`, { method: 'DELETE' });
    if (session.value?.session.id === item.id) {
      session.value = null;
      turns.value = [];
      messages.value = [];
      selectedTask.value = null;
    }
    sessionLoading.value = false;
    if (!session.value) await restoreLatest();
    await listSessions(sessionList.value.page);
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    sessionLoading.value = false;
  }
}
async function listSessions(page = 1) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value) return;
  const current = ++sessionGeneration;
  task.value = 'HISTORY';
  sessionLoading.value = true;
  error.value = '';
  try {
    let result = await api<typeof sessionList.value>(`/assistant/sessions?page=${page}&pageSize=10`);
    if (!result.items.length && page > 1) {
      const lastPage = Math.max(1, Math.ceil(result.total / result.pageSize));
      result = await api<typeof sessionList.value>(`/assistant/sessions?page=${lastPage}&pageSize=10`);
    }
    if (current === sessionGeneration) sessionList.value = result;
  } catch (cause) {
    if (current === sessionGeneration) error.value = errorMessage(cause);
  } finally {
    if (current === sessionGeneration) sessionLoading.value = false;
  }
}
async function olderTurns() {
  if (!turns.value[0] || sessionLoading.value) return;
  sessionLoading.value = true;
  try {
    await refreshSession(turns.value[0].id);
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    sessionLoading.value = false;
  }
}
function resumeTaskId(kind: AssistantTaskSummary['kind']) {
  return selectedTask.value?.kind === kind
    ? selectedTask.value.id
    : latestAssistantTask(session.value?.tasks ?? [], kind);
}
async function resumeTask(value: AssistantTaskSummary) {
  if (busy.value || preparingDraft.value || savingDraft.value || sessionLoading.value || !(await allowSessionChange()))
    return;
  selectedTask.value = value;
  task.value = value.kind;
}
function taskUpdated(value: AssistantTaskView) {
  if (!session.value) return;
  session.value.tasks = [value, ...session.value.tasks.filter(old => old.id !== value.id)];
  if (task.value === value.kind) selectedTask.value = value;
}
function requery(turn: AssistantTurn) {
  if (!turn.context && /第[一二三四五六七八九十两\d]+(?:笔|条)|这笔|这条账单/.test(turn.question)) {
    error.value = '这条历史提问缺少明确的业务关联，请先进入具体业务页面选择事项，再重新提问。';
    return;
  }
  error.value = '';
  question.value = turn.question;
  requeryContext.value = turn.context;
  contextEnabled.value = true;
  consent.value = false;
  queryKey.value = crypto.randomUUID();
  input.value?.focus();
}
async function send() {
  if (!canSend.value) return;
  const text = question.value.trim(),
    current = ++generation;
  request = new AbortController();
  const controller = request;
  busy.value = true;
  error.value = '';
  try {
    const result = await api<AssistantQueryResult>(`/assistant/sessions/${session.value!.session.id}/query`, {
      method: 'POST',
      signal: controller.signal,
      body: JSON.stringify({
        requestKey: queryKey.value,
        input: { message: text, mode: mode.value, externalConsent: consent.value, context: context.value },
      }),
    });
    if (current !== generation) return;
    turns.value = mergeAssistantTurns(turns.value, [result.turn]);
    const answer = result.answer;
    if (answer) messages.value = [...messages.value.slice(-5), { question: text, answer }];
    else error.value = '这次请求已记录，未重复调用模型。可从历史提问手动重新查询。';
    question.value = '';
    consent.value = false;
    queryKey.value = crypto.randomUUID();
    await refreshSession();
    if (queryStartedGoal(result)) task.value = 'GOAL';
    await nextTick();
    if (open.value && current === generation) {
      history.value?.scrollTo({
        top: history.value.scrollHeight,
        behavior: publicReducedMotion.value ? 'auto' : 'smooth',
      });
      input.value?.focus();
    }
  } catch (cause) {
    if (current === generation && !controller.signal.aborted) {
      error.value = errorMessage(cause);
      if (!(cause instanceof ApiError && cause.status === 0)) queryKey.value = crypto.randomUUID();
      try {
        await refreshSession();
      } catch {
        /* The original error remains visible. */
      }
    }
  } finally {
    if (current === generation) {
      busy.value = false;
      request = null;
      consent.value = false;
    }
  }
}
async function choose(text: string) {
  question.value = text;
  input.value?.focus();
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}
watch([() => route.path, () => route.query.id, () => currentUser.value?.permissions.join(',')], () => {
  stopWaiting();
  messages.value = [];
  question.value = '';
  consent.value = false;
  contextEnabled.value = true;
  requeryContext.value = null;
  task.value = 'QUERY';
  preparingDraft.value = false;
  savingDraft.value = false;
  hint.value = false;
  if (open.value && currentUser.value) {
    void loadCapabilities();
    void refreshSession().catch(cause => {
      error.value = errorMessage(cause);
    });
  }
});
watch(
  () => currentUser.value?.id,
  () => {
    stopWaiting();
    sessionGeneration++;
    session.value = null;
    turns.value = [];
    messages.value = [];
    question.value = '';
    consent.value = false;
    sessionLoading.value = false;
    open.value = false;
    capabilities.value = null;
    capabilityGeneration++;
    loading.value = false;
    task.value = 'QUERY';
    requeryContext.value = null;
  },
);
watch(mode, () => {
  consent.value = false;
  error.value = '';
});
watch(question, () => {
  queryKey.value = crypto.randomUUID();
});
watch(
  () => session.value?.session.id,
  () => {
    selectedTask.value = null;
  },
);

function triggerRandomAutonomousAction() {
  if (!canAnimate()) {
    clearAvatarMotion();
    return;
  }
  if (open.value || isScrolling || busy.value || preparingDraft.value || savingDraft.value) {
    scheduleNextAutonomousAction();
    return;
  }
  const actions: AutoAction[] = ['peek', 'bounce', 'sparkle', 'stretch'];
  const next = actions[Math.floor(Math.random() * actions.length)] ?? 'peek';
  autoAction.value = next;

  if (autoActionResetTimer) clearTimeout(autoActionResetTimer);
  autoActionResetTimer = window.setTimeout(() => {
    autoAction.value = 'none';
    scheduleNextAutonomousAction();
  }, 1900);
}

function scheduleNextAutonomousAction() {
  if (autoActionTimer) clearTimeout(autoActionTimer);
  autoActionTimer = null;
  if (!canAnimate() || open.value) return;
  const delay = 18000 + Math.random() * 10000;
  autoActionTimer = window.setTimeout(triggerRandomAutonomousAction, delay);
}

function handleScroll() {
  if (open.value || !canAnimate()) return;
  const currentY = window.scrollY || document.documentElement.scrollTop;
  const diff = currentY - lastScrollY;

  if (Math.abs(diff) > 2) {
    isScrolling = true;
    if (autoAction.value !== 'none') autoAction.value = 'none';

    scrollMotion.value = diff > 0 ? 'down' : 'up';

    if (scrollEndTimer) clearTimeout(scrollEndTimer);
    scrollEndTimer = window.setTimeout(() => {
      isScrolling = false;
      scrollMotion.value = 'settle';
      if (scrollSettleTimer) clearTimeout(scrollSettleTimer);
      scrollSettleTimer = window.setTimeout(() => {
        if (!isScrolling) scrollMotion.value = 'none';
      }, 360);
    }, 140);
  }
  lastScrollY = currentY;
}

function canAnimate() {
  return (
    !publicReducedMotion.value &&
    typeof document !== 'undefined' &&
    !document.hidden &&
    !!currentUser.value?.permissions.includes('agent:query')
  );
}
function clearAvatarMotion() {
  for (const timer of [autoActionTimer, autoActionResetTimer, scrollEndTimer, scrollSettleTimer])
    if (timer) clearTimeout(timer);
  autoActionTimer = autoActionResetTimer = scrollEndTimer = scrollSettleTimer = null;
  autoAction.value = 'none';
  scrollMotion.value = 'none';
  isScrolling = false;
}
function visibilityChanged() {
  if (document.hidden) clearAvatarMotion();
  else scheduleNextAutonomousAction();
}
watch(publicReducedMotion, reduced => {
  if (reduced) clearAvatarMotion();
  else scheduleNextAutonomousAction();
});
watch(open, isOpen => {
  if (isOpen) {
    autoAction.value = 'none';
    scrollMotion.value = 'none';
    if (autoActionTimer) clearTimeout(autoActionTimer);
    if (autoActionResetTimer) clearTimeout(autoActionResetTimer);
  } else {
    scheduleNextAutonomousAction();
  }
});

onMounted(() => {
  if (typeof window !== 'undefined') {
    lastScrollY = window.scrollY || document.documentElement.scrollTop;
    window.addEventListener('scroll', handleScroll, { passive: true });
    document.addEventListener('visibilitychange', visibilityChanged);
    scheduleNextAutonomousAction();
  }
});

onBeforeUnmount(() => {
  stopWaiting();
  capabilityGeneration++;
  sessionGeneration++;
  if (typeof window !== 'undefined') {
    window.removeEventListener('scroll', handleScroll);
    document.removeEventListener('visibilitychange', visibilityChanged);
    if (autoActionTimer) clearTimeout(autoActionTimer);
    if (autoActionResetTimer) clearTimeout(autoActionResetTimer);
    if (scrollEndTimer) clearTimeout(scrollEndTimer);
    if (scrollSettleTimer) clearTimeout(scrollSettleTimer);
  }
});
</script>
<template>
  <aside
    v-if="currentUser?.permissions.includes('agent:query')"
    id="campus-assistant"
    class="assistant-dock campus-assistant"
    :class="{ expanded: open, 'is-reduced': publicReducedMotion }"
  >
    <section
      v-show="open"
      id="campus-assistant-panel"
      class="assistant-panel"
      role="dialog"
      aria-labelledby="campus-assistant-heading"
      @keydown.esc.stop="close"
    >
      <header class="assistant-head">
        <div>
          <span class="assistant-eyebrow">CAMPUS COMPANION</span>
          <strong id="campus-assistant-heading">校园助手</strong>
          <small>
            {{
              task === 'HISTORY'
                ? '恢复本人对话与已核对的进度'
                : task === 'GOAL'
                  ? '说明目标，自主组织办理'
                  : task === 'AGENT'
                    ? '单项任务逐步办理'
                    : task !== 'QUERY'
                      ? '整理资料，确认后保存本人草稿'
                      : '学习、生活与办事，陪你理清每一步。'
            }}
          </small>
        </div>
        <button class="assistant-icon" aria-label="收起 AI 对话" :disabled="savingDraft" @click="close">
          <ChevronDown :size="19" />
        </button>
      </header>
      <div class="assistant-session-bar">
        <span>{{ session?.session.title ?? '正在恢复本人对话' }}</span>
        <button :disabled="busy || preparingDraft || savingDraft || sessionLoading" @click="listSessions()">
          历史对话
        </button>
        <button :disabled="busy || preparingDraft || savingDraft || sessionLoading" @click="newSession">新对话</button>
      </div>
      <details
        v-if="canPrepareLeave || canPrepareCertificate || canPrepareRepair || canPrepareHousing || canRunMission"
        class="assistant-service-options"
      >
        <summary>
          <span>{{ serviceModeLabel }}</span>
          切换服务方式
          <ChevronDown :size="13" />
        </summary>
        <nav class="assistant-tasks" aria-label="AI 服务方式">
          <button
            :aria-pressed="task === 'QUERY'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'QUERY'"
          >
            对话
          </button>
          <button
            v-if="canRunMission"
            :aria-pressed="task === 'GOAL'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'GOAL'"
          >
            自主办理
          </button>
          <button
            v-if="canRunMission"
            :aria-pressed="task === 'AGENT'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'AGENT'"
          >
            单项任务
          </button>
          <button
            v-if="canPrepareLeave"
            :aria-pressed="task === 'LEAVE'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'LEAVE'"
          >
            准备请假
          </button>
          <button
            v-if="canPrepareCertificate"
            :aria-pressed="task === 'CERTIFICATE'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'CERTIFICATE'"
          >
            准备证明
          </button>
          <button
            v-if="canPrepareRepair"
            :aria-pressed="task === 'REPAIR'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'REPAIR'"
          >
            准备报修
          </button>
          <button
            v-if="canPrepareHousing"
            :aria-pressed="task === 'HOUSING'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'HOUSING'"
          >
            准备调宿
          </button>
        </nav>
      </details>
      <div v-if="task !== 'HISTORY' && task !== 'AGENT' && task !== 'GOAL'" class="assistant-mode">
        <label>
          回答方式
          <BaseSelect
            v-model="mode"
            :options="modeOptions"
            compact
            :disabled="busy || loading || preparingDraft || savingDraft"
            aria-label="回答方式"
          />
        </label>
        <span>
          {{
            busy || preparingDraft
              ? '正在处理'
              : mode === 'LOCAL'
                ? task !== 'QUERY'
                  ? '表单核对'
                  : '关键词识别'
                : '自然语言识别'
          }}
        </span>
      </div>
      <p v-if="loading" class="assistant-status" role="status">正在读取可用查询…</p>
      <p v-if="sessionLoading" class="assistant-status" role="status">正在恢复对话记录…</p>
      <AssistantGoal
        v-if="canRunMission"
        v-show="task === 'GOAL'"
        ref="goalDraft"
        :active="open && task === 'GOAL' && !sessionLoading"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        @working="preparingDraft = $event"
      />
      <AssistantMission
        v-if="canRunMission"
        v-show="task === 'AGENT'"
        ref="missionDraft"
        :active="open && task === 'AGENT' && !sessionLoading"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        @working="preparingDraft = $event"
      />
      <AssistantLeaveDraft
        v-if="canPrepareLeave"
        v-show="task === 'LEAVE'"
        ref="leaveDraft"
        :active="open && task === 'LEAVE' && !sessionLoading"
        :mode="mode"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        :resume-task-id="resumeTaskId('LEAVE')"
        @updated="taskUpdated"
        @saving="savingDraft = $event"
        @working="preparingDraft = $event"
      />
      <AssistantCertificateDraft
        v-if="canPrepareCertificate"
        v-show="task === 'CERTIFICATE'"
        ref="certificateDraft"
        :active="open && task === 'CERTIFICATE' && !sessionLoading"
        :mode="mode"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        :resume-task-id="resumeTaskId('CERTIFICATE')"
        @updated="taskUpdated"
        @saving="savingDraft = $event"
        @working="preparingDraft = $event"
      />
      <AssistantHousingDraft
        v-if="canPrepareHousing"
        v-show="task === 'HOUSING'"
        ref="housingDraft"
        :active="open && task === 'HOUSING' && !sessionLoading"
        :mode="mode"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        :resume-task-id="resumeTaskId('HOUSING')"
        @updated="taskUpdated"
        @saving="savingDraft = $event"
        @working="preparingDraft = $event"
      />
      <AssistantRepairDraft
        v-if="canPrepareRepair"
        v-show="task === 'REPAIR'"
        ref="repairDraft"
        :active="open && task === 'REPAIR' && !sessionLoading"
        :mode="mode"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        :resume-task-id="resumeTaskId('REPAIR')"
        @updated="taskUpdated"
        @saving="savingDraft = $event"
        @working="preparingDraft = $event"
      />
      <div v-if="task === 'HISTORY'" class="assistant-history assistant-session-list">
        <h2>本人历史对话</h2>
        <p>选择一条继续。恢复时重新核对权限和任务状态。</p>
        <div v-for="item in sessionList.items" :key="item.id" class="assistant-session-item">
          <button :disabled="sessionLoading" @click="selectSession(item.id)">
            <strong>{{ item.title }}</strong>
            <span>
              {{ item.updatedAt.replace('T', ' ').slice(0, 16)
              }}{{ item.id === session?.session.id ? ' · 当前对话' : '' }}
            </span>
          </button>
          <button
            class="assistant-session-delete"
            :disabled="sessionLoading"
            :aria-label="`删除对话 ${item.title}`"
            @click="deleteSession(item)"
          >
            删除
          </button>
        </div>
        <p v-if="!sessionList.items.length && !sessionLoading">暂无历史对话。</p>
        <div class="assistant-session-pages">
          <button :disabled="sessionLoading || sessionList.page <= 1" @click="listSessions(sessionList.page - 1)">
            上一页
          </button>
          <span>第 {{ sessionList.page }} 页</span>
          <button
            :disabled="sessionLoading || sessionList.page * sessionList.pageSize >= sessionList.total"
            @click="listSessions(sessionList.page + 1)"
          >
            下一页
          </button>
        </div>
        <h2 v-if="session?.tasks.length" class="assistant-task-list-heading">当前对话的准备任务</h2>
        <button
          v-for="item in session?.tasks ?? []"
          :key="`task-${item.id}`"
          :disabled="sessionLoading"
          @click="resumeTask(item)"
        >
          <strong>{{ taskLabel(item.kind) }} #{{ item.id }}</strong>
          <span>
            {{
              item.stage === 'LINKED'
                ? '已关联申请 · 恢复后核对最新状态'
                : item.version
                  ? '已暂存预览 · 恢复后重新核对'
                  : '待补齐信息'
            }}
          </span>
        </button>
      </div>
      <div
        v-show="task === 'QUERY'"
        ref="history"
        class="assistant-history"
        aria-live="polite"
        aria-relevant="additions"
      >
        <div v-if="!messages.length && !turns.length" class="assistant-intro">
          <span class="assistant-intro-mark"><Sparkles :size="21" aria-hidden="true" /></span>
          <h2>从一个目标开始</h2>
          <p>
            查安排、问指南、追进度。
            <br />
            把校园里的事，一件件理清。
          </p>
          <div class="assistant-start-actions">
            <button
              v-if="canRunMission"
              class="assistant-start-goal"
              :disabled="sessionLoading || !session"
              @click="task = 'GOAL'"
            >
              <span>
                <strong>帮我办一件事</strong>
                <small>请假、证明、报修与预约</small>
              </span>
              <ArrowUpRight :size="17" />
            </button>
            <button
              v-for="text in suggestions.slice(0, 2)"
              :key="text"
              :disabled="busy || sessionLoading"
              @click="choose(text)"
            >
              <span>{{ text }}</span>
              <ArrowUpRight :size="15" />
            </button>
          </div>
          <small>规则与个人记录均为毕业设计演示。</small>
        </div>
        <button v-if="session?.hasOlder" class="assistant-older" :disabled="sessionLoading" @click="olderTurns">
          更早的提问
        </button>
        <article
          v-for="turn in turns.filter(item => !messages.some(entry => entry.answer.runId === item.runId))"
          :key="turn.id"
          class="assistant-restored"
        >
          <p>{{ turn.question }}</p>
          <small>{{ restoredTurnLabel(turn.status) }} · {{ turn.createdAt.replace('T', ' ').slice(0, 16) }}</small>
          <button :disabled="busy || sessionLoading" @click="requery(turn)">重新查询</button>
        </article>
        <article v-for="entry in messages" :key="entry.answer.runId" class="assistant-turn">
          <p class="assistant-question">{{ entry.question }}</p>
          <p class="assistant-summary">{{ entry.answer.summary }}</p>
          <section
            v-for="result in entry.answer.results"
            :key="result.name"
            class="assistant-result"
            :class="{ 'query-issue': result.status !== 'OK' }"
          >
            <h3>{{ result.title }}</h3>
            <p>{{ result.message }}</p>
            <div
              v-if="
                result.status === 'NEEDS_INPUT' &&
                result.followUps?.length &&
                entry.answer.runId === messages.at(-1)?.answer.runId
              "
              class="assistant-prompts"
              role="group"
              aria-label="补充查询条件"
            >
              <button
                v-for="text in result.followUps"
                :key="text"
                type="button"
                :disabled="busy || sessionLoading"
                @click="choose(text)"
              >
                {{ text }}
              </button>
            </div>
            <template v-for="item in result.items" :key="item.key">
              <RouterLink
                v-if="safeAssistantDestination(item.targetPath)"
                :to="safeAssistantDestination(item.targetPath)!"
                class="assistant-fact"
                @click="followResult($event, item.targetPath)"
              >
                <strong>{{ item.title }}</strong>
                <span>{{ item.description }}</span>
                <ArrowUpRight :size="13" />
              </RouterLink>
              <div v-else class="assistant-fact no-target">
                <strong>{{ item.title }}</strong>
                <span>{{ item.description }}</span>
              </div>
            </template>
            <div v-for="source in result.sources" :key="source.slug" class="assistant-source">
              <strong>{{ source.title }}</strong>
              <p>{{ source.body.slice(0, 220) }}{{ source.body.length > 220 ? '…' : '' }}</p>
              <RouterLink
                v-if="safeAssistantDestination(source.targetPath)"
                :to="source.targetPath"
                @click="followResult($event, source.targetPath)"
              >
                来源 · V{{ source.version }} · 查看完整指南
                <ArrowUpRight :size="12" />
              </RouterLink>
            </div>
            <RouterLink
              v-if="
                safeAssistantDestination(result.targetPath) &&
                (!result.items.length || result.total > result.items.length)
              "
              :to="result.targetPath!"
              class="assistant-more"
              @click="followResult($event, result.targetPath)"
            >
              {{ result.items.length ? '查看完整业务列表' : '查看业务页面' }}
              <ArrowUpRight :size="13" />
            </RouterLink>
          </section>
          <small class="assistant-time">
            {{
              entry.answer.modelUsed
                ? 'DeepSeek 规划 · 本地执行'
                : entry.answer.mode === 'LOCAL'
                  ? '本地查询'
                  : '本地处理 · 未调用模型'
            }}
            · {{ entry.answer.queriedAt.replace('T', ' ').slice(0, 19) }}
          </small>
        </article>
      </div>
      <div v-if="context && task === 'QUERY'" class="assistant-context">
        <span>当前上下文：{{ contextLabel }}</span>
        <button class="assistant-icon" aria-label="清除当前业务上下文" @click="contextEnabled = false">
          <X :size="13" />
        </button>
      </div>
      <div v-show="task === 'QUERY' && (messages.length || turns.length)" class="assistant-prompts">
        <button v-for="text in suggestions.slice(0, 2)" :key="text" :disabled="busy" @click="choose(text)">
          {{ text }}
        </button>
      </div>
      <form v-show="task === 'QUERY'" class="assistant-compose" @submit.prevent="send">
        <label v-if="mode === 'DEEPSEEK'" class="assistant-consent">
          <input v-model="consent" type="checkbox" :disabled="busy" />
          同意将本次问题及页面业务类型发送至
          DeepSeek。查询结果和从页面读取的编号留在本地；若开始办理，公开指南与脱敏执行结果也会发送。
        </label>
        <label class="sr-only" for="assistant-question">输入查询或办理目标</label>
        <div class="assistant-input-row">
          <textarea
            id="assistant-question"
            ref="input"
            v-model="question"
            rows="2"
            maxlength="1000"
            placeholder="输入想查询或要办理的事项…"
            :disabled="busy || loading"
            @keydown="keydown"
          />
          <button type="submit" class="assistant-send" :disabled="!canSend" aria-label="发送">
            <Send :size="17" />
          </button>
        </div>
        <div class="assistant-compose-note">
          <span>Enter 发送 · Shift + Enter 换行</span>
          <button v-if="busy" type="button" @click="stopWaiting">停止等待</button>
        </div>
        <p v-if="error" class="assistant-error" role="alert">
          {{ error }}
          <button v-if="!capabilities" type="button" aria-label="重新读取可用查询" @click="loadCapabilities">
            <RotateCw :size="13" />
            重试
          </button>
        </p>
      </form>
      <p v-if="error && task !== 'QUERY'" class="assistant-error assistant-session-error" role="alert">{{ error }}</p>
      <p v-if="!session && error" class="assistant-session-error">
        <button :disabled="sessionLoading" @click="restoreLatest">重试恢复对话</button>
      </p>
    </section>
    <div v-if="hint && !open" class="assistant-hint">
      <span class="assistant-hint-badge"><Sparkles :size="12" aria-hidden="true" /></span>
      <button class="assistant-hint-btn" @click="show">可以帮你查{{ hintSubject }}，也可以直接办理</button>
      <button class="assistant-hint-close" aria-label="关闭 AI 查询提示" @click="hint = false"><X :size="12" /></button>
    </div>
    <button
      ref="launcher"
      class="assistant-launcher"
      :class="{
        'is-open': open,
        'is-working': busy || preparingDraft || savingDraft,
        [`action-${autoAction}`]: !open && autoAction !== 'none',
        [`motion-scroll-${scrollMotion}`]: !open && scrollMotion !== 'none',
      }"
      :data-state="avatarState"
      :data-action="autoAction"
      :data-motion="scrollMotion"
      :aria-expanded="open"
      aria-controls="campus-assistant-panel"
      :aria-label="open ? '收起 AI 对话' : '打开 AI 对话'"
      :disabled="savingDraft"
      @click="open ? close() : show()"
    >
      <span v-if="busy || preparingDraft || savingDraft" class="assistant-aura-ring" />
      <span v-if="!open && scrollMotion === 'down'" class="assistant-surf-trail" aria-hidden="true" />
      <span v-if="!open && scrollMotion === 'up'" class="assistant-jet-trail" aria-hidden="true" />
      <span v-if="!open && autoAction === 'sparkle'" class="assistant-sparkle-burst" aria-hidden="true" />
      <div class="assistant-launcher-inner">
        <X v-if="open" :size="22" class="launcher-icon-close" />
        <div v-else class="launcher-icon-stack">
          <Bot :size="26" class="launcher-bot-icon" />
          <Sparkles :size="12" class="launcher-sparkle-icon" />
        </div>
      </div>
    </button>
  </aside>
</template>
<style scoped>
.assistant-session-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 16px 9px;
  font-size: var(--text-meta);
  color: #7a899a;
  flex-shrink: 0;
}
.assistant-session-bar span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assistant-session-bar button,
.assistant-older,
.assistant-restored button {
  border: 0;
  background: transparent;
  color: var(--color-brand);
  font: inherit;
  font-size: var(--text-meta);
  padding: 3px;
  cursor: pointer;
  white-space: nowrap;
}
.assistant-session-list h2 {
  font-size: var(--text-body);
  color: var(--color-brand);
  margin: 0 0 8px;
}
.assistant-session-list > p {
  font-size: var(--text-meta);
  line-height: 1.7;
  color: #7a899a;
}
.assistant-session-list > button {
  display: block;
  width: 100%;
  border: 1px solid #e2e9f0;
  border-radius: var(--radius-md);
  background: #fafbfd;
  padding: 10px;
  text-align: left;
  margin: 8px 0;
  color: #405b76;
  cursor: pointer;
}
.assistant-session-item {
  display: flex;
  align-items: stretch;
  gap: 6px;
  margin: 8px 0;
}
.assistant-session-item > button:first-child {
  flex: 1;
  min-width: 0;
  border: 1px solid #e2e9f0;
  border-radius: var(--radius-md);
  background: #fafbfd;
  padding: 10px;
  text-align: left;
  color: #405b76;
  cursor: pointer;
  font: inherit;
}
.assistant-session-delete {
  border: 1px solid #e8d5d5;
  border-radius: var(--radius-md);
  background: #fdf8f8;
  color: #a15353;
  font: inherit;
  font-size: var(--text-meta);
  padding: 0 9px;
  cursor: pointer;
}
.assistant-session-delete:disabled {
  opacity: 0.5;
  cursor: default;
}
.assistant-session-list strong,
.assistant-session-list span {
  display: block;
  overflow-wrap: anywhere;
}
.assistant-session-list strong {
  font-size: var(--text-supporting);
  margin-bottom: 4px;
}
.assistant-session-list span {
  font-size: var(--text-meta);
  color: #8795a7;
}
.assistant-session-pages {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 16px;
}
.assistant-session-pages button {
  border: 1px solid var(--color-border);
  background: white;
  color: var(--color-brand);
  font-size: var(--text-meta);
  padding: 4px 7px;
  border-radius: var(--radius-sm);
}
.assistant-session-bar button:disabled,
.assistant-session-list button:disabled,
.assistant-restored button:disabled {
  opacity: 0.5;
  cursor: default;
}
.assistant-restored {
  border-bottom: 1px solid var(--color-border);
  padding: 10px 0;
}
.assistant-restored p {
  font-size: var(--text-supporting);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: #405b76;
  margin: 0 0 5px;
}
.assistant-restored small {
  display: block;
  font-size: var(--text-meta);
  color: #8795a7;
  line-height: 1.6;
}
.assistant-session-error {
  padding: 5px 12px 9px;
  flex-shrink: 0;
}
.assistant-session-error button {
  border: 0;
  background: none;
  color: var(--color-brand);
  font-size: var(--text-meta);
  cursor: pointer;
}
.assistant-tasks {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding: 0 16px 8px;
  flex-shrink: 0;
}
.assistant-tasks button {
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: #f4f6f9;
  color: #74869b;
  font: inherit;
  font-size: var(--text-meta);
  min-height: 28px;
  padding: 4px 9px;
  cursor: pointer;
}
.assistant-tasks button[aria-pressed='true'] {
  border-color: #cfdae7;
  background: var(--color-surface-soft);
  color: #31577f;
}
.assistant-tasks button:disabled {
  opacity: 0.5;
  cursor: default;
}
.assistant-session-list .assistant-task-list-heading {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #e8edf3;
  font-size: var(--text-supporting);
}
.assistant-dock {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 70;
  color: var(--color-ink);
  font-size: var(--text-supporting);
}
.assistant-launcher {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  margin-left: auto;
  border: 1px solid rgb(255, 255, 255, 0.28);
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-info) 0%, var(--color-info) 45%, #7c3aed 100%);
  color: var(--color-white);
  box-shadow:
    0 10px 24px -4px rgb(37, 99, 235, 0.45),
    0 4px 12px rgb(124, 58, 237, 0.25),
    inset 0 1px 1px rgb(255, 255, 255, 0.35);
  cursor: pointer;
  position: relative;
  transition:
    transform 0.25s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.25s cubic-bezier(0.16, 1, 0.3, 1),
    background 0.3s ease;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.assistant-launcher:hover {
  transform: translateY(-3px) scale(1.05) rotate(-3deg);
  box-shadow:
    0 14px 30px -4px rgb(37, 99, 235, 0.55),
    0 6px 16px rgb(124, 58, 237, 0.35),
    inset 0 1px 2px rgb(255, 255, 255, 0.5);
}
.assistant-launcher:active {
  transform: translateY(0) scale(0.94);
  box-shadow:
    0 6px 16px -2px rgb(37, 99, 235, 0.4),
    inset 0 1px 1px rgb(255, 255, 255, 0.2);
}
.assistant-launcher:focus-visible {
  outline: 3px solid rgb(99, 102, 241, 0.5);
  outline-offset: 3px;
}
.assistant-launcher.is-open {
  background: linear-gradient(135deg, var(--color-ink) 0%, var(--color-ink) 100%);
  border-color: rgb(255, 255, 255, 0.15);
  box-shadow: 0 8px 22px rgb(15, 23, 42, 0.25);
  transform: none !important;
}
.assistant-launcher.is-open:hover {
  background: linear-gradient(135deg, var(--color-text-secondary) 0%, var(--color-ink) 100%);
}
.assistant-launcher-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  position: relative;
  color: var(--color-white);
}
.launcher-icon-stack {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.launcher-bot-icon {
  color: var(--color-white);
  filter: drop-shadow(0 2px 4px rgb(0, 0, 0, 0.2));
  animation: ai-bot-breathe 4s ease-in-out infinite;
  transition: transform 0.2s ease;
}
.launcher-sparkle-icon {
  position: absolute;
  top: -4px;
  right: -5px;
  color: #fde047;
  filter: drop-shadow(0 0 5px rgb(253, 224, 71, 0.9));
  animation: ai-sparkle-twinkle 2.5s ease-in-out infinite;
}
.launcher-icon-close {
  color: var(--color-white);
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.assistant-launcher.is-open:hover .launcher-icon-close {
  transform: rotate(90deg);
}
.assistant-aura-ring {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid rgb(124, 58, 237, 0.65);
  animation: ai-aura-pulse 2s cubic-bezier(0.24, 0, 0.38, 1) infinite;
  pointer-events: none;
}
.assistant-launcher.motion-scroll-down {
  transform: translateY(8px) rotate(14deg) scale(0.96);
  box-shadow:
    0 4px 16px -2px rgb(37, 99, 235, 0.45),
    inset 0 1px 1px rgb(255, 255, 255, 0.4);
  transition:
    transform 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}
.assistant-launcher.motion-scroll-down .launcher-bot-icon {
  transform: rotate(6deg) scale(1.03);
}
.assistant-launcher.motion-scroll-up {
  transform: translateY(-9px) rotate(-10deg) scale(1.05);
  box-shadow:
    0 16px 32px -4px rgb(37, 99, 235, 0.6),
    0 6px 18px rgb(124, 58, 237, 0.35);
  transition:
    transform 0.14s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}
.assistant-launcher.motion-scroll-up .launcher-bot-icon {
  transform: translateY(-2px) rotate(-6deg);
}
.assistant-launcher.motion-scroll-settle {
  animation: ai-scroll-settle 0.36s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.assistant-surf-trail {
  position: absolute;
  top: -6px;
  width: 28px;
  height: 8px;
  background: radial-gradient(ellipse at center, rgb(147, 197, 253, 0.75) 0%, rgb(147, 197, 253, 0) 75%);
  border-radius: 50%;
  filter: blur(1px);
  pointer-events: none;
  animation: surf-trail-wave 0.28s ease-in-out infinite alternate;
}
.assistant-jet-trail {
  position: absolute;
  bottom: -8px;
  width: 32px;
  height: 12px;
  background: radial-gradient(ellipse at center, rgb(192, 132, 252, 0.85) 0%, rgb(192, 132, 252, 0) 75%);
  border-radius: 50%;
  filter: blur(2px);
  pointer-events: none;
  animation: jet-trail-pulse 0.22s ease-in-out infinite alternate;
}
.assistant-launcher.action-peek {
  animation: ai-action-peek 1.8s cubic-bezier(0.25, 1, 0.5, 1);
}
.assistant-launcher.action-peek .launcher-bot-icon {
  animation: ai-bot-peek 1.8s ease-in-out;
}
.assistant-launcher.action-bounce {
  animation: ai-action-bounce 1.5s cubic-bezier(0.28, 0.84, 0.42, 1);
}
.assistant-launcher.action-sparkle {
  animation: ai-action-sparkle 1.8s ease-in-out;
}
.assistant-launcher.action-sparkle .launcher-sparkle-icon {
  animation: ai-action-sparkle-spin 1.8s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.assistant-sparkle-burst {
  position: absolute;
  inset: -12px;
  border-radius: 50%;
  background: radial-gradient(circle, rgb(253, 224, 71, 0.45) 0%, rgb(168, 85, 247, 0.3) 50%, transparent 72%);
  animation: ai-sparkle-burst-ring 1.6s ease-out;
  pointer-events: none;
}
.assistant-launcher.action-stretch {
  animation: ai-action-stretch 1.8s ease-in-out;
}
.assistant-panel {
  position: absolute;
  right: 0;
  bottom: 76px;
  width: min(366px, calc(100vw - 32px));
  height: min(550px, calc(100dvh - 112px));
  display: flex;
  flex-direction: column;
  background: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  box-shadow: 0 14px 48px #142d4e26;
  overflow: hidden;
  animation: assistant-appear 0.2s ease-out;
}
.assistant-head {
  padding: 14px 16px 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
}
.assistant-head strong {
  display: block;
  font-size: var(--text-reading);
}
.assistant-head small {
  display: block;
  color: #7c8999;
  font-size: var(--text-meta);
  margin-top: 3px;
}
.assistant-icon {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: #708398;
  display: grid;
  place-items: center;
  cursor: pointer;
}
.assistant-icon:hover {
  background: #eff4f8;
}
.assistant-mode {
  padding: 9px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f7f9fc;
  font-size: var(--text-meta);
  color: #7a899a;
}
.assistant-mode label {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}
.assistant-mode select {
  max-width: 144px;
  height: 27px;
  min-height: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: white;
  font-size: var(--text-meta);
  color: #355374;
  padding: 2px 6px;
}
.assistant-status {
  padding: 8px 16px;
  margin: 0;
  color: #76879b;
}
.assistant-history {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 14px 16px;
  scrollbar-width: thin;
}
.assistant-intro {
  text-align: center;
  padding: 15px 10px 8px;
  color: #6e8197;
  line-height: 1.7;
}
.assistant-intro h2 {
  font-family: inherit;
  font-size: 16px;
  font-weight: 600;
  color: #334f6e;
  margin: 10px 0 8px;
}
.assistant-intro p {
  font-size: var(--text-supporting);
  margin: 0 0 8px;
}
.assistant-intro small {
  font-size: var(--text-meta);
  color: #8d98a7;
}
.assistant-intro-mark {
  display: inline-grid;
  place-items: center;
  width: 38px;
  height: 38px;
  background: var(--color-surface-soft);
  border-radius: var(--radius-xl);
  color: var(--color-brand);
  font-size: var(--text-body);
  letter-spacing: 2px;
}
.assistant-turn {
  margin-bottom: 20px;
}
.assistant-question {
  padding: 9px 12px;
  border-radius: var(--radius-lg) var(--radius-lg) var(--radius-sm) var(--radius-lg);
  background: var(--color-surface-soft);
  margin: 0 0 10px 25px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: var(--text-supporting);
}
.assistant-summary {
  font-size: var(--text-supporting);
  color: #62758a;
  line-height: 1.6;
  margin: 0 0 9px;
}
.assistant-result {
  padding: 10px;
  border: 1px solid #e2e9f0;
  border-radius: var(--radius-lg);
  margin-top: 8px;
}
.assistant-result h3 {
  font-family: inherit;
  font-size: var(--text-supporting);
  margin: 0 0 6px;
  color: var(--color-brand);
}
.assistant-result > p,
.assistant-source p {
  font-size: var(--text-supporting);
  line-height: 1.7;
  white-space: pre-wrap;
  margin: 0 0 7px;
  color: #6e7f90;
  overflow-wrap: anywhere;
}
.assistant-result.query-issue {
  background: #fbfaf6;
  border-color: #e9e3d4;
}
.assistant-fact {
  position: relative;
  display: block;
  padding: 8px 14px 8px 0;
  border-top: 1px solid #eef1f4;
  font-size: var(--text-meta);
  text-decoration: none;
  color: #405b76;
}
.assistant-fact strong {
  display: block;
  font-size: var(--text-supporting);
  margin-bottom: 4px;
}
.assistant-fact span {
  font-size: var(--text-meta);
  color: #7c8999;
  overflow-wrap: anywhere;
}
.assistant-fact svg {
  position: absolute;
  right: 0;
  top: 9px;
}
.assistant-fact:hover {
  color: #1c4777;
}
.assistant-fact.no-target {
  pointer-events: none;
}
.assistant-source {
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border);
}
.assistant-source strong {
  font-size: var(--text-supporting);
}
.assistant-source a,
.assistant-more {
  font-size: var(--text-meta);
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #335b83;
  text-decoration: none;
  margin-top: 5px;
}
.assistant-time {
  display: block;
  font-size: var(--text-meta);
  color: #95a0ae;
  margin-top: 8px;
}
.assistant-context {
  margin: 0 16px 6px;
  padding: 4px 6px 4px 9px;
  background: #f2f6fa;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 5px;
  font-size: var(--text-meta);
  color: #688099;
}
.assistant-context .assistant-icon {
  width: 24px;
  height: 24px;
}
.assistant-prompts {
  padding: 0 16px 8px;
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.assistant-prompts button {
  border: 1px solid #dfe7ef;
  background: var(--color-white);
  border-radius: var(--radius-xl);
  padding: 4px 8px;
  font-size: var(--text-meta);
  color: #698198;
  cursor: pointer;
}
.assistant-prompts button:hover {
  border-color: #a4bbd1;
  background: #f4f8fb;
}
.assistant-prompts button:disabled {
  opacity: 0.5;
  cursor: default;
}
.assistant-compose {
  padding: 10px 12px;
  border-top: 1px solid var(--color-border);
  margin: 0;
  display: block;
}
.assistant-input-row {
  display: flex;
  gap: 7px;
  align-items: flex-end;
}
.assistant-input-row textarea {
  flex: 1;
  width: 0;
  min-width: 0;
  min-height: 64px;
  max-height: 112px;
  resize: vertical;
  border: 1px solid #d5dfea;
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--color-ink);
  font-family: inherit;
  box-shadow:
    0 1px 2px rgb(15, 23, 42, 0.04),
    inset 0 1px 1px rgb(15, 23, 42, 0.02);
  transition:
    border-color 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.18s ease;
}
.assistant-input-row textarea:focus {
  outline: none;
  background: var(--color-white);
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgb(36, 75, 120, 0.14);
}
.assistant-send {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: var(--radius-lg);
  background: var(--color-brand);
  color: white;
  display: grid;
  place-items: center;
  cursor: pointer;
  margin-bottom: 2px;
  box-shadow: 0 2px 6px rgb(36, 75, 120, 0.22);
  transition: all 0.16s cubic-bezier(0.16, 1, 0.3, 1);
}
.assistant-send:hover:not(:disabled) {
  background: #1c3b60;
  box-shadow: 0 4px 10px rgb(36, 75, 120, 0.3);
}
.assistant-send:active:not(:disabled) {
  transform: scale(0.95);
}
.assistant-send:disabled {
  opacity: 0.35;
  cursor: default;
  box-shadow: none;
}
.assistant-compose-note {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: var(--text-meta);
  color: #929eac;
}
.assistant-compose-note button {
  border: 0;
  background: none;
  color: #6a829b;
  font-size: var(--text-meta);
  padding: 0;
  cursor: pointer;
}
.assistant-consent {
  display: flex;
  flex-direction: row;
  gap: 6px;
  align-items: flex-start;
  font-size: var(--text-meta);
  line-height: 1.6;
  color: #7a8899;
  margin-bottom: 8px;
}
.assistant-consent input {
  width: 14px;
  min-height: 0;
  height: 14px;
  flex-shrink: 0;
  margin-top: 2px;
  accent-color: var(--color-brand);
}
.assistant-error {
  font-size: var(--text-meta);
  color: #98684a;
  margin: 8px 0 0;
  line-height: 1.6;
}
.assistant-error button {
  border: 0;
  background: none;
  padding: 0;
  color: var(--color-brand);
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--text-meta);
  cursor: pointer;
}
.assistant-hint {
  position: absolute;
  bottom: 12px;
  right: 68px;
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
  background: rgb(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgb(226, 232, 240, 0.9);
  border-radius: var(--radius-pill);
  box-shadow:
    0 6px 20px rgb(15, 23, 42, 0.08),
    0 1px 3px rgb(15, 23, 42, 0.04);
  padding: 4px 6px 4px 10px;
  animation: assistant-appear 0.28s cubic-bezier(0.16, 1, 0.3, 1);
}
.assistant-hint-badge {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%);
  color: var(--color-white);
  display: grid;
  place-items: center;
  flex-shrink: 0;
  box-shadow: 0 2px 6px rgb(99, 102, 241, 0.35);
}
.assistant-hint-btn {
  background: transparent;
  border: 0;
  padding: 4px 2px;
  font-size: var(--text-meta);
  font-weight: 500;
  color: var(--color-ink);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  transition: color 0.15s ease;
}
.assistant-hint-btn:hover {
  color: var(--color-info);
}
.assistant-hint-close {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 0;
  background: transparent;
  color: var(--color-text-tertiary);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s ease;
  margin-left: 2px;
}
.assistant-hint-close:hover {
  background: var(--color-surface-soft);
  color: var(--color-text-secondary);
}

@keyframes ai-bot-breathe {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }
  50% {
    transform: translateY(-1.5px) scale(1.03);
  }
}

@keyframes ai-sparkle-twinkle {
  0%,
  100% {
    transform: scale(1) rotate(0deg);
    opacity: 0.95;
  }
  50% {
    transform: scale(1.2) rotate(12deg);
    opacity: 1;
    filter: drop-shadow(0 0 8px rgb(253, 224, 71, 1));
  }
}

@keyframes ai-aura-pulse {
  0% {
    transform: scale(0.95);
    opacity: 0.75;
    box-shadow: 0 0 0 0 rgb(124, 58, 237, 0.5);
  }
  70% {
    transform: scale(1.18);
    opacity: 0.15;
    box-shadow: 0 0 0 10px rgb(124, 58, 237, 0);
  }
  100% {
    transform: scale(1.25);
    opacity: 0;
    box-shadow: 0 0 0 14px rgb(124, 58, 237, 0);
  }
}

@keyframes assistant-appear {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes ai-scroll-settle {
  0% {
    transform: translateY(0) rotate(0) scale(1);
  }
  35% {
    transform: translateY(-5px) rotate(-3deg) scale(1.03);
  }
  65% {
    transform: translateY(2px) rotate(1.5deg) scale(0.98);
  }
  100% {
    transform: translateY(0) rotate(0) scale(1);
  }
}

@keyframes surf-trail-wave {
  0% {
    transform: scaleX(0.8) translateY(0);
    opacity: 0.6;
  }
  100% {
    transform: scaleX(1.15) translateY(-2px);
    opacity: 0.95;
  }
}

@keyframes jet-trail-pulse {
  0% {
    transform: scale(0.85);
    opacity: 0.7;
  }
  100% {
    transform: scale(1.2);
    opacity: 1;
  }
}

@keyframes ai-action-peek {
  0%,
  100% {
    transform: translateY(0) rotate(0deg);
  }
  20% {
    transform: translateY(-3px) rotate(-15deg) scale(1.04);
  }
  50% {
    transform: translateY(-3px) rotate(14deg) scale(1.04);
  }
  75% {
    transform: translateY(-1px) rotate(-4deg) scale(1.01);
  }
}

@keyframes ai-bot-peek {
  0%,
  100% {
    transform: rotate(0);
  }
  20% {
    transform: rotate(-8deg) translateX(-2px);
  }
  50% {
    transform: rotate(8deg) translateX(2px);
  }
  75% {
    transform: rotate(-2deg);
  }
}

@keyframes ai-action-bounce {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }
  18% {
    transform: translateY(-12px) scale(1.06, 0.94);
  }
  36% {
    transform: translateY(0) scale(0.94, 1.06);
  }
  54% {
    transform: translateY(-6px) scale(1.03, 0.97);
  }
  72% {
    transform: translateY(0) scale(0.98, 1.02);
  }
}

@keyframes ai-action-sparkle {
  0%,
  100% {
    filter: brightness(1);
  }
  35% {
    filter: brightness(1.25) drop-shadow(0 0 14px rgb(168, 85, 247, 0.7));
    transform: scale(1.08);
  }
  70% {
    filter: brightness(1.1);
    transform: scale(1.03);
  }
}

@keyframes ai-action-sparkle-spin {
  0% {
    transform: scale(1) rotate(0deg);
  }
  35% {
    transform: scale(1.8) rotate(180deg);
    color: var(--color-white);
    filter: drop-shadow(0 0 10px #fde047);
  }
  70% {
    transform: scale(1.25) rotate(360deg);
    color: #fde047;
  }
  100% {
    transform: scale(1) rotate(360deg);
  }
}

@keyframes ai-sparkle-burst-ring {
  0% {
    transform: scale(0.8);
    opacity: 0;
  }
  30% {
    transform: scale(1.15);
    opacity: 0.9;
  }
  100% {
    transform: scale(1.45);
    opacity: 0;
  }
}

@keyframes ai-action-stretch {
  0%,
  100% {
    transform: scale(1) translateY(0);
  }
  30% {
    transform: scale(1.08, 0.92) translateY(3px);
  }
  60% {
    transform: scale(0.93, 1.09) translateY(-5px);
  }
  80% {
    transform: scale(1.03, 0.98) translateY(0);
  }
}

@media (max-width: 600px) {
  .assistant-dock {
    right: 14px;
    bottom: 14px;
  }
  .assistant-launcher {
    width: 50px;
    height: 50px;
  }
  .launcher-bot-icon {
    width: 22px;
    height: 22px;
  }
  .assistant-panel {
    position: fixed;
    left: 12px;
    right: 12px;
    bottom: 74px;
    width: auto;
    height: min(540px, calc(100dvh - 96px));
  }
  .assistant-hint {
    right: 60px;
    font-size: var(--text-meta);
  }
  .assistant-head {
    padding: 12px 14px 9px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .assistant-launcher,
  .assistant-launcher *,
  .launcher-bot-icon,
  .launcher-sparkle-icon,
  .assistant-aura-ring,
  .assistant-surf-trail,
  .assistant-jet-trail,
  .assistant-sparkle-burst,
  .assistant-panel,
  .assistant-hint {
    animation: none !important;
    transition: none !important;
  }
  .assistant-history {
    scroll-behavior: auto;
  }
}
</style>
