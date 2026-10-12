<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import type { AssistantGuide } from '../lib/assistant'
const route = useRoute(), guide = ref<AssistantGuide | null>(null), error = ref(''), loading = ref(false)
let sequence = 0
async function load() {
  const current = ++sequence; guide.value = null; error.value = ''; loading.value = true
  try { const result = await api<AssistantGuide>('/assistant/guides/' + encodeURIComponent(String(route.params.slug))); if (current === sequence) guide.value = result }
  catch (cause) { if (current === sequence) error.value = errorMessage(cause) }
  finally { if (current === sequence) loading.value = false }
}
watch(() => route.params.slug, load, { immediate: true })
</script>
<template>
  <article class="assistant-guide-page">
    <p class="portal-context">平台办理指南 · 毕业设计演示</p><p v-if="loading" role="status">正在读取当前有效指南…</p><p v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" @click="load">重试</button></p>
    <template v-if="guide"><h1>{{ guide.title }}</h1><p class="muted">版本 V{{ guide.version }} · 更新 {{ guide.updatedAt.replace('T', ' ').slice(0, 16) }}</p><div class="assistant-guide-body">{{ guide.body }}</div></template>
  </article>
</template>
<style scoped>
.assistant-guide-page{max-width:820px;padding-bottom:40px}.assistant-guide-body{white-space:pre-wrap;line-height:2.1;font-size:15px;color:#425c77;background:white;border:1px solid #e0e7ef;padding:28px 32px;margin-top:24px;border-radius:5px;overflow-wrap:anywhere}.assistant-guide-page h1{font-size:28px;color:#294c73}
@media(max-width:600px){.assistant-guide-body{font-size:13px;padding:18px 16px}.assistant-guide-page h1{font-size:23px}}
</style>
