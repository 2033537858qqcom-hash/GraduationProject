<script setup lang="ts">
import { ref } from 'vue';
import { ArrowUpRight, Check, Copy, Sparkles, ThumbsDown, ThumbsUp } from '@lucide/vue';
import AssistantAvatar from '../AssistantAvatar.vue';
import { api, errorMessage } from '../../lib/api';
import { formatDateTime, formatDateTimeSeconds } from '../../lib/format';
import { safeAssistantDestination } from '../../lib/assistant';
import type { AssistantAnswer, AssistantFeedbackSummary } from '../../lib/assistant';
import { restoredTurnLabel } from '../../lib/assistantSessions';
import type { AssistantSessionDetail, AssistantTurn } from '../../lib/assistantSessions';

defineProps<{
  messages: { question: string; answer: AssistantAnswer }[];
  turns: AssistantTurn[];
  session: AssistantSessionDetail | null;
  suggestions: string[];
  canRunMission: boolean;
  deepseekReady?: boolean;
  busy: boolean;
  sessionLoading: boolean;
}>();

const emit = defineEmits<{
  startGoal: [];
  choose: [text: string];
  retryDeepSeek: [question: string];
  olderTurns: [];
  requery: [turn: AssistantTurn];
  followResult: [event: MouseEvent, targetPath?: string | null];
}>();

const copiedRunId = ref<string | null>(null);
const collapsedDetails = ref<Record<string, boolean>>({});
async function copySummary(runId: string, text: string) {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      copiedRunId.value = runId;
      setTimeout(() => {
        if (copiedRunId.value === runId) copiedRunId.value = null;
      }, 1800);
    }
  } catch {
    /* Ignore clipboard permission denials cleanly. */
  }
}
function toggleDetails(runId: string) {
  collapsedDetails.value = { ...collapsedDetails.value, [runId]: !collapsedDetails.value[runId] };
}
function needsDeepSeekFallback(answer: AssistantAnswer) {
  return (
    answer.mode === 'LOCAL' &&
    (!answer.results.length || answer.results.some(r => r.status === 'NEEDS_INPUT' || r.name === 'start_goal'))
  );
}

const ratings = ref<Record<string, 'UP' | 'DOWN'>>({});
const ratingBusy = ref<Record<string, boolean>>({});
const ratingComments = ref<Record<string, string>>({});
const openCommentFor = ref<string | null>(null);
const ratingError = ref('');
const downReasonPresets = ['回答不准确', '未理解意图', '结果不完整', '响应太慢'];

async function rate(runId: string, rating: 'UP' | 'DOWN', comment?: string) {
  if (!runId || ratingBusy.value[runId]) return;
  ratingBusy.value = { ...ratingBusy.value, [runId]: true };
  ratingError.value = '';
  try {
    const res = await api<AssistantFeedbackSummary>(`/assistant/runs/${runId}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ rating, comment: comment?.trim() || undefined }),
    });
    ratings.value = { ...ratings.value, [runId]: res.rating };
    if (rating === 'UP') {
      openCommentFor.value = null;
    } else if (!comment) {
      openCommentFor.value = runId;
    } else {
      openCommentFor.value = null;
    }
  } catch (cause) {
    ratingError.value = errorMessage(cause);
  } finally {
    ratingBusy.value = { ...ratingBusy.value, [runId]: false };
  }
}
</script>

<template>
  <div>
    <div v-if="!messages.length && !turns.length" class="assistant-intro">
      <span class="assistant-intro-mark">
        <AssistantAvatar mood="idle" size="md" variant="on-light" />
      </span>
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
          @click="emit('startGoal')"
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
          @click="emit('choose', text)"
        >
          <span>{{ text }}</span>
          <ArrowUpRight :size="15" />
        </button>
      </div>
      <small>规则与个人记录均为毕业设计演示。</small>
    </div>
    <button v-if="session?.hasOlder" class="assistant-older" :disabled="sessionLoading" @click="emit('olderTurns')">
      更早的提问
    </button>
    <article
      v-for="turn in turns.filter(item => !messages.some(entry => entry.answer.runId === item.runId))"
      :key="turn.id"
      class="assistant-restored"
    >
      <div class="assistant-bubble-row user">
        <p class="assistant-question-bubble">{{ turn.question }}</p>
      </div>
      <div class="assistant-restored-body">
        <p v-if="turn.answerSummary" class="assistant-restored-summary">{{ turn.answerSummary }}</p>
        <div v-if="turn.links?.length" class="assistant-restored-links">
          <RouterLink
            v-for="link in turn.links.filter(item => safeAssistantDestination(item.path))"
            :key="link.path"
            :to="link.path"
            @click="emit('followResult', $event, link.path)"
          >
            {{ link.label }}
            <ArrowUpRight :size="12" />
          </RouterLink>
        </div>
        <div class="assistant-restored-meta">
          <small>{{ restoredTurnLabel(turn.status) }} · {{ formatDateTime(turn.createdAt) }}</small>
          <button :disabled="busy || sessionLoading" @click="emit('requery', turn)">重新查询</button>
        </div>
      </div>
    </article>
    <article v-for="entry in messages" :key="entry.answer.runId" class="assistant-turn">
      <div class="assistant-bubble-row user">
        <p class="assistant-question-bubble">{{ entry.question }}</p>
      </div>
      <div class="assistant-answer-card">
        <p class="assistant-summary">{{ entry.answer.summary }}</p>
        <div v-if="deepseekReady && needsDeepSeekFallback(entry.answer)" class="assistant-fallback-bar">
          <button
            type="button"
            class="assistant-fallback-btn"
            :disabled="busy || sessionLoading"
            @click="emit('retryDeepSeek', entry.question)"
          >
            <Sparkles :size="13" />
            改用 DeepSeek 继续识别
          </button>
        </div>
        <button
          v-if="entry.answer.results.length > 1"
          type="button"
          class="assistant-details-toggle"
          @click="toggleDetails(entry.answer.runId)"
        >
          {{ collapsedDetails[entry.answer.runId] ? `展开 ${entry.answer.results.length} 项工具明细` : '收起工具明细' }}
        </button>
        <template v-if="!collapsedDetails[entry.answer.runId]">
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
                @click="emit('choose', text)"
              >
                {{ text }}
              </button>
            </div>
            <template v-for="item in result.items" :key="item.key">
              <RouterLink
                v-if="safeAssistantDestination(item.targetPath)"
                :to="safeAssistantDestination(item.targetPath)!"
                class="assistant-fact"
                @click="emit('followResult', $event, item.targetPath)"
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
                @click="emit('followResult', $event, source.targetPath)"
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
              @click="emit('followResult', $event, result.targetPath)"
            >
              {{ result.items.length ? '查看完整业务列表' : '查看业务页面' }}
              <ArrowUpRight :size="13" />
            </RouterLink>
          </section>
        </template>
      </div>
      <div class="assistant-turn-footer">
        <small class="assistant-time">
          {{
            entry.answer.modelUsed
              ? 'DeepSeek 规划 · 本地执行'
              : entry.answer.mode === 'LOCAL'
                ? '本地查询'
                : '本地处理 · 未调用模型'
          }}
          · {{ formatDateTimeSeconds(entry.answer.queriedAt) }}
        </small>
        <div class="assistant-feedback" role="group" aria-label="回答操作与评价">
          <button
            type="button"
            class="assistant-feedback-btn"
            :title="copiedRunId === entry.answer.runId ? '已复制' : '复制回答'"
            @click="copySummary(entry.answer.runId, entry.answer.summary)"
          >
            <Check v-if="copiedRunId === entry.answer.runId" :size="12" />
            <Copy v-else :size="12" />
            <span>{{ copiedRunId === entry.answer.runId ? '已复制' : '' }}</span>
          </button>
          <button
            type="button"
            class="assistant-feedback-btn"
            :class="{ active: ratings[entry.answer.runId] === 'UP' }"
            :aria-pressed="ratings[entry.answer.runId] === 'UP'"
            :disabled="!!ratingBusy[entry.answer.runId]"
            title="有帮助"
            @click="rate(entry.answer.runId, 'UP')"
          >
            <ThumbsUp :size="12" />
            <span>{{ ratings[entry.answer.runId] === 'UP' ? '有帮助' : '' }}</span>
          </button>
          <button
            type="button"
            class="assistant-feedback-btn"
            :class="{ active: ratings[entry.answer.runId] === 'DOWN' }"
            :aria-pressed="ratings[entry.answer.runId] === 'DOWN'"
            :disabled="!!ratingBusy[entry.answer.runId]"
            title="待改进"
            @click="rate(entry.answer.runId, 'DOWN')"
          >
            <ThumbsDown :size="12" />
            <span>{{ ratings[entry.answer.runId] === 'DOWN' ? '待改进' : '' }}</span>
          </button>
        </div>
      </div>
      <div
        v-if="openCommentFor === entry.answer.runId"
        class="assistant-feedback-detail"
        role="group"
        aria-label="补充待改进原因"
      >
        <div class="assistant-feedback-tags">
          <button
            v-for="reason in downReasonPresets"
            :key="reason"
            type="button"
            class="assistant-feedback-tag"
            :disabled="!!ratingBusy[entry.answer.runId]"
            @click="rate(entry.answer.runId, 'DOWN', reason)"
          >
            {{ reason }}
          </button>
        </div>
        <div class="assistant-feedback-input-row">
          <input
            v-model="ratingComments[entry.answer.runId]"
            type="text"
            maxlength="300"
            placeholder="补充具体待改进点（可选）"
          />
          <button
            type="button"
            class="assistant-feedback-submit"
            :disabled="!!ratingBusy[entry.answer.runId] || !(ratingComments[entry.answer.runId] ?? '').trim()"
            @click="rate(entry.answer.runId, 'DOWN', ratingComments[entry.answer.runId])"
          >
            提交
          </button>
        </div>
      </div>
      <small v-if="ratingError" class="assistant-feedback-error">{{ ratingError }}</small>
    </article>
  </div>
</template>

<style scoped>
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
  color: var(--color-heading);
  margin: 0 0 5px;
}
.assistant-restored small {
  display: block;
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
  line-height: 1.6;
}
.assistant-intro {
  text-align: center;
  padding: 15px 10px 8px;
  color: var(--color-text-secondary);
  line-height: 1.7;
}
.assistant-intro h2 {
  font-family: inherit;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-ink);
  margin: 10px 0 8px;
}
.assistant-intro p {
  font-size: var(--text-supporting);
  margin: 0 0 8px;
}
.assistant-intro small {
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
}
.assistant-intro-mark {
  display: inline-grid;
  place-items: center;
  border-radius: 50%;
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
  color: var(--color-text-secondary);
  line-height: 1.6;
  margin: 0 0 9px;
}
.assistant-result {
  padding: 10px;
  border: 1px solid var(--color-border-subtle);
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
  color: var(--color-text-secondary);
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
  border-top: 1px solid var(--color-border-subtle);
  font-size: var(--text-meta);
  text-decoration: none;
  color: var(--color-heading);
}
.assistant-fact strong {
  display: block;
  font-size: var(--text-supporting);
  margin-bottom: 4px;
}
.assistant-fact span {
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
  overflow-wrap: anywhere;
}
.assistant-fact svg {
  position: absolute;
  right: 0;
  top: 9px;
}
.assistant-fact:hover {
  color: var(--color-brand);
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
  color: var(--color-brand);
  text-decoration: none;
  margin-top: 5px;
}
.assistant-turn-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}
.assistant-time {
  display: block;
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
}
.assistant-feedback {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.assistant-feedback-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 1px solid var(--color-border-subtle);
  background: var(--color-surface);
  color: var(--color-text-tertiary);
  border-radius: var(--radius-sm);
  padding: 2px 6px;
  font-size: var(--text-meta);
  cursor: pointer;
}
.assistant-feedback-btn:hover:not(:disabled),
.assistant-feedback-btn.active {
  border-color: var(--color-brand);
  color: var(--color-brand);
  background: var(--color-surface-active);
}
.assistant-feedback-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.assistant-feedback-error {
  display: block;
  margin-top: 4px;
  font-size: var(--text-meta);
  color: var(--color-danger);
}
.assistant-restored-summary {
  font-size: var(--text-meta);
  color: var(--color-text-secondary);
  line-height: 1.6;
  margin: 4px 0 6px;
}
.assistant-restored-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}
.assistant-restored-links a {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--text-meta);
  color: var(--color-brand);
  text-decoration: none;
  padding: 2px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}
.assistant-feedback-detail {
  margin-top: 8px;
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  display: grid;
  gap: 6px;
}
.assistant-feedback-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.assistant-feedback-tag {
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  background: var(--color-white);
  color: var(--color-text-secondary);
  font-size: var(--text-meta);
  padding: 2px 8px;
  cursor: pointer;
}
.assistant-feedback-tag:hover:not(:disabled) {
  border-color: var(--color-brand);
  color: var(--color-brand);
}
.assistant-feedback-input-row {
  display: flex;
  gap: 6px;
}
.assistant-feedback-input-row input {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  font-size: var(--text-meta);
  color: var(--color-ink);
  background: var(--color-white);
}
.assistant-feedback-submit {
  border: 0;
  border-radius: var(--radius-sm);
  padding: 4px 10px;
  background: var(--color-brand);
  color: var(--color-white);
  font-size: var(--text-meta);
  cursor: pointer;
}
.assistant-bubble-row.user {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 6px;
}
.assistant-question-bubble {
  margin: 0;
  max-width: 86%;
  padding: 7px 12px;
  border-radius: var(--radius-lg) var(--radius-lg) var(--radius-xs) var(--radius-lg);
  background: var(--color-surface-active);
  border: 1px solid var(--color-border-strong);
  color: var(--color-ink);
  font-size: var(--text-supporting);
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.assistant-restored-body,
.assistant-answer-card {
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm) var(--radius-lg) var(--radius-lg) var(--radius-lg);
  background: var(--color-surface);
}
.assistant-restored-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
}
.assistant-fallback-bar {
  margin: 6px 0 4px;
}
.assistant-fallback-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--color-brand);
  border-radius: var(--radius-md);
  background: var(--color-surface-active);
  color: var(--color-brand);
  font-size: var(--text-meta);
  font-weight: 500;
  padding: 4px 10px;
  cursor: pointer;
}
.assistant-details-toggle {
  border: 0;
  background: transparent;
  color: var(--color-brand);
  font-size: var(--text-meta);
  padding: 4px 0;
  cursor: pointer;
}
</style>
