<script setup lang="ts">
import { watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '../lib/api';
import { formatDateTime } from '../lib/format';
import { useRequest } from '../composables/useRequest';
import type { AssistantGuide } from '../lib/assistant';
const route = useRoute();
const {
  data: guide,
  error,
  loading,
  run: fetchGuide,
} = useRequest(
  (signal, slug: string) => api<AssistantGuide>('/assistant/guides/' + encodeURIComponent(slug), { signal }),
  { immediate: false, keepPrevious: false },
);
const load = () => fetchGuide(String(route.params.slug));
watch(() => route.params.slug, load, { immediate: true });
</script>
<template>
  <article class="assistant-guide-page">
    <p class="portal-context">平台办理指南 · 毕业设计演示</p>
    <p v-if="loading" role="status">正在读取当前有效指南…</p>
    <p v-if="error" class="alert" role="alert">
      {{ error }}
      <button class="text-button" @click="load">重试</button>
    </p>
    <template v-if="guide">
      <h1>{{ guide.title }}</h1>
      <p class="muted">版本 V{{ guide.version }} · 更新 {{ formatDateTime(guide.updatedAt) }}</p>
      <div class="assistant-guide-body">{{ guide.body }}</div>
    </template>
  </article>
</template>
<style scoped>
.assistant-guide-page {
  max-width: 820px;
  padding-bottom: 40px;
}
.assistant-guide-body {
  white-space: pre-wrap;
  line-height: 2.1;
  font-size: var(--text-reading);
  color: var(--color-ink);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  padding: 28px 32px;
  margin-top: 24px;
  border-radius: var(--radius-md);
  overflow-wrap: anywhere;
}
.assistant-guide-page h1 {
  font-size: 28px;
  color: var(--color-heading);
}

@media (max-width: 600px) {
  .assistant-guide-body {
    font-size: var(--text-supporting);
    padding: 18px 16px;
  }
  .assistant-guide-page h1 {
    font-size: 23px;
  }
}
</style>
