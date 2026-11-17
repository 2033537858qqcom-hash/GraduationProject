<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api } from '../../lib/api';
import type { AssistantRunMetrics } from '../../lib/assistant';
import { formatDateTime } from '../../lib/format';
import type { AssistantSession, AssistantSessionDetail, AssistantTaskSummary } from '../../lib/assistantSessions';

defineProps<{
  session: AssistantSessionDetail | null;
  sessionList: { items: AssistantSession[]; total: number; page: number; pageSize: number };
  sessionLoading: boolean;
  taskLabel: (kind: string) => string;
}>();

const emit = defineEmits<{
  selectSession: [id: number];
  deleteSession: [item: AssistantSession];
  listSessions: [page: number];
  resumeTask: [item: AssistantTaskSummary];
}>();

const metrics = ref<AssistantRunMetrics | null>(null);
onMounted(async () => {
  try {
    metrics.value = await api<AssistantRunMetrics>('/assistant/metrics', { cacheTtl: 15_000 });
  } catch {
    /* Non-blocking summary card. */
  }
});
</script>

<template>
  <div class="assistant-history assistant-session-list">
    <section v-if="metrics && metrics.totalRuns > 0" class="assistant-metrics-card" aria-label="本人助手统计">
      <h3>本人助手使用统计</h3>
      <div class="assistant-metrics-grid">
        <span>累计查询 {{ metrics.totalRuns }} 次</span>
        <span>成功 {{ metrics.successRuns }} 次</span>
        <span>平均响应 {{ metrics.avgDurationMs }} ms</span>
        <span>反馈 {{ metrics.upFeedbacks }} 赞 / {{ metrics.downFeedbacks }} 踩</span>
      </div>
      <small v-if="metrics.topTools.length">高频能力：{{ metrics.topTools.join(' · ') }}</small>
    </section>
    <h2>本人历史对话</h2>
    <p>选择一条继续。恢复时重新核对权限和任务状态。</p>
    <div v-for="item in sessionList.items" :key="item.id" class="assistant-session-item">
      <button :disabled="sessionLoading" @click="emit('selectSession', item.id)">
        <strong>{{ item.title }}</strong>
        <span>{{ formatDateTime(item.updatedAt) }}{{ item.id === session?.session.id ? ' · 当前对话' : '' }}</span>
      </button>
      <button
        class="assistant-session-delete"
        :disabled="sessionLoading"
        :aria-label="`删除对话 ${item.title}`"
        @click="emit('deleteSession', item)"
      >
        删除
      </button>
    </div>
    <p v-if="!sessionList.items.length && !sessionLoading">暂无历史对话。</p>
    <div class="assistant-session-pages">
      <button :disabled="sessionLoading || sessionList.page <= 1" @click="emit('listSessions', sessionList.page - 1)">
        上一页
      </button>
      <span>第 {{ sessionList.page }} 页</span>
      <button
        :disabled="sessionLoading || sessionList.page * sessionList.pageSize >= sessionList.total"
        @click="emit('listSessions', sessionList.page + 1)"
      >
        下一页
      </button>
    </div>
    <h2 v-if="session?.tasks.length" class="assistant-task-list-heading">当前对话的准备任务</h2>
    <button
      v-for="item in session?.tasks ?? []"
      :key="`task-${item.id}`"
      :disabled="sessionLoading"
      @click="emit('resumeTask', item)"
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
</template>

<style scoped>
.assistant-session-list h2 {
  font-size: var(--text-body);
  color: var(--color-brand);
  margin: 0 0 8px;
}
.assistant-session-list > p {
  font-size: var(--text-meta);
  line-height: 1.7;
  color: var(--color-text-tertiary);
}
.assistant-session-list > button {
  display: block;
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  padding: 10px;
  text-align: left;
  margin: 8px 0;
  color: var(--color-heading);
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
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  padding: 10px;
  text-align: left;
  color: var(--color-heading);
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
  color: var(--color-text-tertiary);
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
.assistant-session-list button:disabled {
  opacity: 0.5;
  cursor: default;
}
.assistant-session-list .assistant-task-list-heading {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border-subtle);
  font-size: var(--text-supporting);
}
.assistant-metrics-card {
  margin-bottom: 16px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}
.assistant-metrics-card h3 {
  margin: 0 0 6px;
  font-size: var(--text-supporting);
  color: var(--color-brand);
}
.assistant-metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 8px;
}
.assistant-metrics-card span,
.assistant-metrics-card small {
  font-size: var(--text-meta);
  color: var(--color-text-secondary);
}
.assistant-metrics-card small {
  display: block;
  margin-top: 6px;
  color: var(--color-text-tertiary);
}
</style>
