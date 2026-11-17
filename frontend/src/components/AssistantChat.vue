<script setup lang="ts">
import { confirmDialog } from '../composables/useConfirm';
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronDown, History, Maximize2, Mic, MicOff, Minimize2, Plus, RotateCw, Send, X } from '@lucide/vue';
import { AI_REQUEST_TIMEOUT, api, ApiError, errorMessage, streamPostJson } from '../lib/api';
import {
  assistantContext,
  assistantContextLabel,
  assistantSuggestions,
  safeAssistantDestination,
} from '../lib/assistant';
import type { AssistantAnswer, AssistantBriefing, AssistantCapabilities } from '../lib/assistant';
import { currentUser } from '../stores/auth';
import AssistantAvatar from './AssistantAvatar.vue';
const AssistantLeaveDraft = defineAsyncComponent(() => import('./AssistantLeaveDraft.vue'));
const AssistantCertificateDraft = defineAsyncComponent(() => import('./AssistantCertificateDraft.vue'));
const AssistantRepairDraft = defineAsyncComponent(() => import('./AssistantRepairDraft.vue'));
const AssistantHousingDraft = defineAsyncComponent(() => import('./AssistantHousingDraft.vue'));
const AssistantMission = defineAsyncComponent(() => import('./AssistantMission.vue'));
const AssistantGoal = defineAsyncComponent(() => import('./AssistantGoal.vue'));
import AssistantHistoryPanel from './assistant/AssistantHistoryPanel.vue';
import AssistantQueryResults from './assistant/AssistantQueryResults.vue';
import {
  latestAssistantTask,
  mergeAssistantTurns,
  queryStartedGoal,
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
import { goalStageLabels } from '../lib/assistantGoals';
import type { GoalSummary } from '../lib/assistantGoals';
import type { AssistantContext } from '../lib/assistant';
import { publicReducedMotion } from '../lib/publicMotion';
import '../styles/campus-assistant.css';

const route = useRoute();
const router = useRouter();
const open = ref(false),
  wideView = ref(false),
  manualStandardWidth = ref(false),
  showFormMenu = ref(false),
  hasTrackingGoal = ref(false),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  question = ref('');
const briefing = ref<AssistantBriefing | null>(null);
const streamingText = ref('');
const listening = ref(false);
const speechSupported =
  typeof window !== 'undefined' && ('SpeechRecognition' in window || 'webkitSpeechRecognition' in window);
interface SpeechRecognizer {
  lang: string;
  interimResults: boolean;
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
  start: () => void;
  stop: () => void;
}
let recognition: SpeechRecognizer | null = null;
const contextEnabled = ref(true),
  consent = ref(false),
  rememberConsent = ref(false),
  mode = ref<'LOCAL' | 'DEEPSEEK'>('LOCAL');
provide('assistantSessionConsent', rememberConsent);
const capabilities = ref<AssistantCapabilities | null>(null);
const task = ref<'QUERY' | 'LEAVE' | 'CERTIFICATE' | 'REPAIR' | 'HOUSING' | 'AGENT' | 'GOAL' | 'HISTORY'>('QUERY'),
  savingDraft = ref(false),
  preparingDraft = ref(false);
const mountedTasks = ref<Set<string>>(new Set());
watch(
  task,
  next => {
    if (!mountedTasks.value.has(next)) {
      mountedTasks.value = new Set(mountedTasks.value).add(next);
    }
  },
  { immediate: true },
);
const isFormTask = computed(() => ['AGENT', 'LEAVE', 'CERTIFICATE', 'REPAIR', 'HOUSING'].includes(task.value));
const session = ref<AssistantSessionDetail | null>(null),
  sessionLoading = ref(false),
  thinkingSteps = ref<string[]>([]);
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
    !!currentUser.value?.permissions.includes('reservation:apply') ||
    !!currentUser.value?.permissions.includes('housing:read'),
);
const missionDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null);
const goalDraft = ref<{ allowLeave: () => Promise<boolean> } | null>(null);
const messages = ref<{ question: string; answer: AssistantAnswer }[]>([]);
const activeGoalSummary = ref<GoalSummary | null>(null);
function turnCacheKey(userId: number, sessionId: number) {
  return `assistant-ui-turns-${userId}-${sessionId}`;
}
function readTurnCache(userId: number | undefined, sessionId: number) {
  if (!userId) return [];
  try {
    const raw = window.sessionStorage.getItem(turnCacheKey(userId, sessionId));
    const parsed = raw ? (JSON.parse(raw) as { question: string; answer: AssistantAnswer }[]) : [];
    return Array.isArray(parsed) ? parsed.slice(-12) : [];
  } catch {
    return [];
  }
}
function writeTurnCache(
  userId: number | undefined,
  sessionId: number,
  list: { question: string; answer: AssistantAnswer }[],
) {
  if (!userId) return;
  try {
    window.sessionStorage.setItem(turnCacheKey(userId, sessionId), JSON.stringify(list.slice(-12)));
  } catch {
    /* Ignore sessionStorage quota restrictions in private mode. */
  }
}
function clearTurnCache(userId: number | undefined, sessionId?: number) {
  if (!userId) return;
  try {
    if (sessionId !== undefined) {
      window.sessionStorage.removeItem(turnCacheKey(userId, sessionId));
      return;
    }
    const prefix = `assistant-ui-turns-${userId}-`;
    for (let i = window.sessionStorage.length - 1; i >= 0; i--) {
      const key = window.sessionStorage.key(i);
      if (key?.startsWith(prefix)) window.sessionStorage.removeItem(key);
    }
  } catch {
    /* Ignore storage access errors. */
  }
}
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
    const result = await api<AssistantCapabilities>('/assistant/capabilities', { cacheTtl: 30_000 });
    if (current !== capabilityGeneration) return;
    capabilities.value = result;
    if (!result.deepseekReady) mode.value = 'LOCAL';
  } catch (cause) {
    if (current === capabilityGeneration) error.value = errorMessage(cause);
  } finally {
    if (current === capabilityGeneration) loading.value = false;
  }
}
async function loadBriefing() {
  try {
    briefing.value = await api<AssistantBriefing>('/assistant/briefing', { cacheTtl: 20_000 });
  } catch {
    /* Non-blocking briefing banner. */
  }
}
async function show() {
  open.value = true;
  await loadCapabilities();
  if (!open.value) return;
  void loadBriefing();
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
  streamingText.value = '';
}
function close() {
  if (savingDraft.value) return;
  stopWaiting();
  open.value = false;
  question.value = '';
  if (!rememberConsent.value) consent.value = false;
  launcher.value?.focus();
}
async function followResult(event: MouseEvent, value: unknown) {
  const target = safeAssistantDestination(value);
  if (!target || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  if (savingDraft.value || !(await allowSessionChange())) return;
  if (target.startsWith('/campus/explore') || typeof window === 'undefined' || window.innerWidth <= 900) {
    close();
  }
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
    messages.value = readTurnCache(currentUser.value?.id, result.session.id);
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
  if (!canRunMission.value) {
    hasTrackingGoal.value = false;
    activeGoalSummary.value = null;
    return;
  }
  try {
    const goals = await api<GoalSummary[]>(`/assistant/sessions/${sessionId}/goals`);
    activeGoalSummary.value =
      goals.find(g => ['RUNNING', 'WAITING_INPUT', 'WAITING_CONFIRMATION', 'MONITORING'].includes(g.stage)) ?? null;
    hasTrackingGoal.value = goals.some(g => ['MONITORING', 'WAITING_CONFIRMATION', 'RUNNING'].includes(g.stage));
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
    messages.value = readTurnCache(currentUser.value?.id, result.session.id);
    activeGoalSummary.value = null;
    task.value = 'QUERY';
    question.value = '';
    consent.value = false;
    rememberConsent.value = false;
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
    activeGoalSummary.value = null;
    task.value = 'QUERY';
    question.value = '';
    consent.value = false;
    rememberConsent.value = false;
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
    clearTurnCache(currentUser.value?.id, item.id);
    if (session.value?.session.id === item.id) {
      session.value = null;
      turns.value = [];
      messages.value = [];
      activeGoalSummary.value = null;
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
  streamingText.value = '';
  thinkingSteps.value = ['正在发起会话查询…'];
  const payload = {
    requestKey: queryKey.value,
    input: { message: text, mode: mode.value, externalConsent: consent.value, context: context.value },
  };
  try {
    let result: AssistantQueryResult;
    try {
      result = await streamPostJson<AssistantQueryResult>(
        `/assistant/sessions/${session.value!.session.id}/query/stream`,
        payload,
        {
          signal: controller.signal,
          timeout: AI_REQUEST_TIMEOUT,
          onStep: msg => {
            if (current !== generation) return;
            if (!thinkingSteps.value.includes(msg)) thinkingSteps.value = [...thinkingSteps.value, msg];
            void nextTick().then(() => {
              if (open.value && current === generation && history.value) {
                history.value.scrollTop = history.value.scrollHeight;
              }
            });
          },
          onDelta: chunk => {
            if (current !== generation) return;
            streamingText.value += chunk;
            void nextTick().then(() => {
              if (open.value && current === generation && history.value) {
                history.value.scrollTop = history.value.scrollHeight;
              }
            });
          },
        },
      );
    } catch (streamCause) {
      if (streamCause instanceof ApiError && [404, 405].includes(streamCause.status)) {
        result = await api<AssistantQueryResult>(`/assistant/sessions/${session.value!.session.id}/query`, {
          method: 'POST',
          signal: controller.signal,
          timeout: AI_REQUEST_TIMEOUT,
          body: JSON.stringify(payload),
        });
      } else {
        throw streamCause;
      }
    }
    if (current !== generation) return;
    turns.value = mergeAssistantTurns(turns.value, [result.turn]);
    const answer = result.answer;
    if (answer) {
      messages.value = [...messages.value.slice(-11), { question: text, answer }];
      writeTurnCache(currentUser.value?.id, session.value!.session.id, messages.value);
    } else {
      error.value = '这次请求已记录，未重复调用模型。可从历史提问手动重新查询。';
    }
    question.value = '';
    if (!rememberConsent.value) consent.value = false;
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
      thinkingSteps.value = [];
      streamingText.value = '';
      request = null;
      if (!rememberConsent.value) consent.value = false;
    }
  }
}
async function choose(text: string, isTemplate = false) {
  if (isTemplate && question.value.trim() && !quickMentions.value.some(m => m.prompt === question.value.trim())) {
    question.value = `${question.value.trim()} ${text}`;
  } else {
    question.value = text;
  }
  await nextTick();
  const el = input.value;
  el?.focus();
  if (isTemplate && el) {
    const slotMatch = text.match(/明天东校区 20人 09:00-11:00|图书馆到西校区明德楼|20元/);
    if (slotMatch && slotMatch.index !== undefined) {
      const baseOffset = question.value.length - text.length;
      el.setSelectionRange(baseOffset + slotMatch.index, baseOffset + slotMatch.index + slotMatch[0].length);
    }
  }
}
function toggleEngine() {
  if (!capabilities.value?.deepseekReady) return;
  mode.value = mode.value === 'LOCAL' ? 'DEEPSEEK' : 'LOCAL';
}
function toggleWideView() {
  wideView.value = !wideView.value;
  manualStandardWidth.value = !wideView.value;
}
async function retryWithDeepSeek(text: string) {
  if (!capabilities.value?.deepseekReady || busy.value) return;
  mode.value = 'DEEPSEEK';
  question.value = text;
  if (rememberConsent.value) {
    consent.value = true;
    await nextTick();
    void send();
  } else {
    await nextTick();
    input.value?.focus();
  }
}
function handleGlobalShortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    if (open.value) close();
    else void show();
  }
}
onMounted(() => {
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('keydown', handleGlobalShortcut);
  }
});
function toggleSpeech() {
  if (!speechSupported) return;
  if (listening.value && recognition) {
    recognition.stop();
    listening.value = false;
    return;
  }
  const win = window as unknown as {
    SpeechRecognition?: new () => SpeechRecognizer;
    webkitSpeechRecognition?: new () => SpeechRecognizer;
  };
  const Ctor = win.SpeechRecognition ?? win.webkitSpeechRecognition;
  if (!Ctor) return;
  recognition = new Ctor();
  recognition.lang = 'zh-CN';
  recognition.interimResults = false;
  recognition.onresult = event => {
    const transcript = event.results?.[0]?.[0]?.transcript ?? '';
    if (transcript) {
      question.value = (question.value ? question.value + ' ' : '') + transcript;
    }
  };
  recognition.onend = () => {
    listening.value = false;
  };
  recognition.onerror = () => {
    listening.value = false;
  };
  listening.value = true;
  recognition.start();
}
const quickMentions = computed(() => {
  const names = capabilities.value?.tools.map(t => t.name) ?? [];
  const items: { label: string; prompt: string }[] = [];
  if (names.includes('my_timetable')) items.push({ label: '@课表', prompt: '查询本周课表' });
  if (names.includes('find_available_venues'))
    items.push({ label: '@空闲场地', prompt: '查找明天东校区 20人 09:00-11:00 空闲场地' });
  if (names.includes('quote_dorm_recharge'))
    items.push({ label: '@宿舍水电', prompt: '我们宿舍电费单价与充值20元试算' });
  if (names.includes('campus_navigation')) items.push({ label: '@校园导航', prompt: '从图书馆到西校区明德楼怎么走' });
  if (names.includes('staff_triage_advice')) items.push({ label: '@岗位建议', prompt: '查看岗位分诊与审核建议' });
  if (names.includes('platform_digest')) items.push({ label: '@运行简报', prompt: '生成岗位运行简报' });
  return items.slice(0, 4);
});
function keydown(event: KeyboardEvent) {
  if (event.key === 'ArrowUp' && !question.value.trim()) {
    const prevQuestion = messages.value.at(-1)?.question ?? turns.value.at(-1)?.question;
    if (prevQuestion) {
      event.preventDefault();
      question.value = prevQuestion;
      return;
    }
  }
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}
watch([() => route.path, () => route.query.id, () => currentUser.value?.permissions.join(',')], () => {
  requeryContext.value = null;
  contextEnabled.value = true;
  if (open.value && currentUser.value) {
    void loadCapabilities();
    void refreshSession().catch(cause => {
      error.value = errorMessage(cause);
    });
  }
});
watch(
  () => currentUser.value?.id,
  (_next, prev) => {
    clearTurnCache(prev);
    stopWaiting();
    sessionGeneration++;
    session.value = null;
    turns.value = [];
    messages.value = [];
    activeGoalSummary.value = null;
    question.value = '';
    consent.value = false;
    rememberConsent.value = false;
    hasTrackingGoal.value = false;
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
  consent.value = mode.value === 'DEEPSEEK' && rememberConsent.value;
  error.value = '';
});
watch(task, next => {
  showFormMenu.value = false;
  if (
    ['GOAL', 'AGENT'].includes(next) &&
    !manualStandardWidth.value &&
    typeof window !== 'undefined' &&
    window.innerWidth >= 900
  ) {
    wideView.value = true;
  }
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

onBeforeUnmount(() => {
  if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
    window.removeEventListener('keydown', handleGlobalShortcut);
  }
  stopWaiting();
  capabilityGeneration++;
  sessionGeneration++;
});
</script>
<template>
  <aside
    v-if="currentUser?.permissions.includes('agent:query')"
    id="campus-assistant"
    class="assistant-dock campus-assistant"
    :class="{ expanded: open, 'is-wide': wideView, 'is-reduced': publicReducedMotion }"
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
        <div class="assistant-head-title">
          <div class="assistant-head-row">
            <strong id="campus-assistant-heading">校园助手</strong>
            <button
              v-if="task !== 'HISTORY' && task !== 'AGENT' && task !== 'GOAL'"
              type="button"
              class="assistant-engine-toggle"
              :class="{ active: mode === 'DEEPSEEK' }"
              :disabled="busy || loading || preparingDraft || savingDraft || !capabilities?.deepseekReady"
              :title="capabilities?.deepseekReady ? '点击切换本地规则与 DeepSeek' : 'DeepSeek 未连接，当前使用本地规则'"
              @click="toggleEngine"
            >
              <span>{{ mode === 'DEEPSEEK' ? 'DeepSeek' : '本地规则' }}</span>
              <small v-if="capabilities?.deepseekReady">切换</small>
            </button>
          </div>
          <small v-if="!messages.length && !turns.length && task === 'QUERY'">
            查课表、导航、账单试算，或用 DeepSeek 自主办理申请（Ctrl+K）
          </small>
        </div>
        <div class="assistant-head-actions">
          <button
            type="button"
            class="assistant-icon"
            :class="{ active: task === 'HISTORY' }"
            aria-label="历史对话"
            title="历史对话"
            :disabled="busy || preparingDraft || savingDraft || sessionLoading"
            @click="task === 'HISTORY' ? (task = 'QUERY') : listSessions()"
          >
            <History :size="16" />
          </button>
          <button
            type="button"
            class="assistant-icon"
            aria-label="新对话"
            title="新对话"
            :disabled="busy || preparingDraft || savingDraft || sessionLoading"
            @click="newSession"
          >
            <Plus :size="17" />
          </button>
          <button
            type="button"
            class="assistant-icon"
            :aria-label="wideView ? '切换标准宽度' : '展开宽屏视图'"
            :title="wideView ? '标准宽度' : '宽屏视图'"
            @click="toggleWideView"
          >
            <Minimize2 v-if="wideView" :size="16" />
            <Maximize2 v-else :size="16" />
          </button>
          <button class="assistant-icon" aria-label="收起校园助手" :disabled="savingDraft" @click="close">
            <ChevronDown :size="19" />
          </button>
        </div>
      </header>
      <div
        v-if="canPrepareLeave || canPrepareCertificate || canPrepareRepair || canPrepareHousing || canRunMission"
        class="assistant-toolbar"
      >
        <nav class="assistant-primary-tabs" aria-label="助手模式">
          <button
            type="button"
            :aria-pressed="task === 'QUERY'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'QUERY'"
          >
            问与查
          </button>
          <button
            v-if="canRunMission"
            type="button"
            :aria-pressed="task === 'GOAL'"
            :disabled="busy || preparingDraft || savingDraft"
            @click="task = 'GOAL'"
          >
            帮我办
          </button>
          <button
            type="button"
            :aria-pressed="isFormTask || showFormMenu"
            :disabled="busy || preparingDraft || savingDraft"
            @click="showFormMenu = !showFormMenu"
          >
            快捷填表
            <ChevronDown :size="12" />
          </button>
        </nav>
        <span class="assistant-session-chip">
          {{ session?.session.title ?? (sessionLoading ? '载入中…' : '当前对话') }}
        </span>
      </div>
      <nav v-if="showFormMenu" class="assistant-form-menu" aria-label="快捷表单与分步任务">
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
      <p v-if="loading" class="assistant-status" role="status">正在读取可用查询…</p>
      <p v-if="sessionLoading" class="assistant-status" role="status">正在恢复对话记录…</p>
      <AssistantGoal
        v-if="canRunMission && mountedTasks.has('GOAL')"
        v-show="task === 'GOAL'"
        ref="goalDraft"
        :active="open && task === 'GOAL' && !sessionLoading"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        :session-consent="rememberConsent"
        @working="preparingDraft = $event"
      />
      <AssistantMission
        v-if="canRunMission && mountedTasks.has('AGENT')"
        v-show="task === 'AGENT'"
        ref="missionDraft"
        :active="open && task === 'AGENT' && !sessionLoading"
        :model-ready="!!capabilities?.deepseekReady"
        :session-id="session?.session.id ?? null"
        :session-consent="rememberConsent"
        @working="preparingDraft = $event"
      />
      <AssistantLeaveDraft
        v-if="canPrepareLeave && mountedTasks.has('LEAVE')"
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
        v-if="canPrepareCertificate && mountedTasks.has('CERTIFICATE')"
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
        v-if="canPrepareHousing && mountedTasks.has('HOUSING')"
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
        v-if="canPrepareRepair && mountedTasks.has('REPAIR')"
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
      <AssistantHistoryPanel
        v-if="task === 'HISTORY'"
        :session="session"
        :session-list="sessionList"
        :session-loading="sessionLoading"
        :task-label="taskLabel"
        @select-session="selectSession"
        @delete-session="deleteSession"
        @list-sessions="listSessions"
        @resume-task="resumeTask"
      />
      <div
        v-show="task === 'QUERY'"
        ref="history"
        class="assistant-history"
        aria-live="polite"
        aria-relevant="additions"
      >
        <div
          v-if="briefing && (briefing.unreadNotices > 0 || briefing.pendingReviews > 0 || briefing.housingExpiringNote)"
          class="assistant-briefing"
          role="status"
        >
          <p>
            <strong>今日简报：</strong>
            <span v-if="briefing.unreadNotices > 0">未读消息 {{ briefing.unreadNotices }} 条</span>
            <span v-if="briefing.pendingReviews > 0">
              {{ briefing.unreadNotices > 0 ? ' · ' : '' }}岗位待办 {{ briefing.pendingReviews }} 项
            </span>
            <span v-if="briefing.housingExpiringNote">· {{ briefing.housingExpiringNote }}</span>
          </p>
        </div>
        <div v-if="activeGoalSummary && canRunMission" class="assistant-goal-banner" role="status">
          <span>
            正在办理：{{ activeGoalSummary.goal.slice(0, 22) }}{{ activeGoalSummary.goal.length > 22 ? '…' : '' }} ·
            {{ goalStageLabels[activeGoalSummary.stage] ?? activeGoalSummary.stage }}
          </span>
          <button type="button" @click="task = 'GOAL'">查看进度</button>
        </div>
        <AssistantQueryResults
          :messages="messages"
          :turns="turns"
          :session="session"
          :suggestions="suggestions"
          :can-run-mission="!!canRunMission"
          :deepseek-ready="!!capabilities?.deepseekReady"
          :busy="busy"
          :session-loading="sessionLoading"
          @start-goal="task = 'GOAL'"
          @choose="choose"
          @retry-deep-seek="retryWithDeepSeek"
          @older-turns="olderTurns"
          @requery="requery"
          @follow-result="followResult"
        />
        <div v-if="busy && streamingText" class="assistant-streaming" role="status" aria-live="polite">
          <strong>AI 正在总结归纳</strong>
          <p>{{ streamingText }}</p>
        </div>
        <div v-if="busy && thinkingSteps.length" class="assistant-thinking" role="status" aria-live="polite">
          <div class="assistant-thinking-head">
            <span class="assistant-thinking-dot" aria-hidden="true"></span>
            <span>{{ mode === 'DEEPSEEK' ? 'DeepSeek 规划与检索中' : '本地智能检索中' }}</span>
          </div>
          <ul class="assistant-thinking-steps">
            <li
              v-for="(stepText, idx) in thinkingSteps"
              :key="idx"
              :class="{ active: idx === thinkingSteps.length - 1 }"
            >
              {{ stepText }}
            </li>
          </ul>
        </div>
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
        <div v-if="mode === 'DEEPSEEK' && rememberConsent && consent" class="assistant-consent-pill">
          <span>已授权本对话发送问题至 DeepSeek</span>
          <button
            type="button"
            :disabled="busy"
            @click="
              rememberConsent = false;
              consent = false;
            "
          >
            撤销
          </button>
        </div>
        <template v-else-if="mode === 'DEEPSEEK'">
          <label class="assistant-consent">
            <input v-model="consent" type="checkbox" :disabled="busy" />
            同意将本次问题及页面业务类型发送至 DeepSeek（查询结果和个人资料保留本地）。
          </label>
          <label v-if="consent" class="assistant-consent">
            <input v-model="rememberConsent" type="checkbox" :disabled="busy" />
            本对话内记住授权（切会话或退出重置）
          </label>
        </template>
        <label class="sr-only" for="assistant-question">输入查询或办理目标</label>
        <div v-if="quickMentions.length" class="assistant-quick-mentions" role="group" aria-label="快捷业务模板">
          <button
            v-for="item in quickMentions"
            :key="item.label"
            type="button"
            :disabled="busy || loading"
            @click="choose(item.prompt, true)"
          >
            {{ item.label }}
          </button>
        </div>
        <div class="assistant-input-row">
          <textarea
            id="assistant-question"
            ref="input"
            v-model="question"
            rows="2"
            maxlength="1000"
            placeholder="输入问题或办理目标（Enter 发送 · ↑ 回溯上问）"
            :disabled="busy || loading"
            @keydown="keydown"
          />
          <button
            v-if="speechSupported"
            type="button"
            class="assistant-mic-btn"
            :class="{ listening }"
            :aria-label="listening ? '停止语音输入' : '语音输入'"
            :disabled="busy || loading"
            @click="toggleSpeech"
          >
            <MicOff v-if="listening" :size="16" />
            <Mic v-else :size="16" />
          </button>
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
    <button
      ref="launcher"
      class="assistant-launcher"
      :class="{
        'is-open': open,
        'is-working': busy || preparingDraft || savingDraft,
      }"
      :data-state="avatarState"
      :aria-expanded="open"
      aria-controls="campus-assistant-panel"
      :aria-label="open ? '收起校园助手' : '打开校园助手'"
      :disabled="savingDraft"
      @click="open ? close() : show()"
    >
      <span v-if="busy || preparingDraft || savingDraft" class="assistant-aura-ring" />
      <span v-if="!open && hasTrackingGoal" class="launcher-status-dot" aria-hidden="true" />
      <div class="assistant-launcher-inner">
        <X v-if="open" :size="19" class="launcher-icon-close" />
        <template v-else>
          <AssistantAvatar :mood="avatarState" size="sm" variant="on-dark" :reduced-motion="publicReducedMotion" />
          <span class="launcher-label">
            {{ busy || preparingDraft || savingDraft ? '思考中…' : '校园助手' }}
          </span>
        </template>
      </div>
    </button>
  </aside>
</template>
<style scoped src="../styles/assistant-chat.css"></style>
