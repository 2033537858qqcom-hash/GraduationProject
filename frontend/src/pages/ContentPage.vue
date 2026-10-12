<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowUpRight } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import type { PublicContent } from '../types'
const route = useRoute()
const content = ref<PublicContent | null>(null)
const error = ref('')
const loading = ref(false)
async function load() {
  content.value = null
  error.value = ''
  loading.value = true
  try { content.value = await api<PublicContent>('/public/content/' + encodeURIComponent(String(route.params.slug))) }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
watch(() => route.params.slug, load, { immediate: true })
</script>
<template>
  <article class="public-article page-width">
    <p v-if="loading" role="status">正在加载校园信息…</p>
    <div v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" @click="load">重试</button></div>
    <template v-if="content">
      <span class="eyebrow">{{ content.sourceType === 'DEMO' ? '毕业设计演示内容' : '学校公开资料' }}</span>
      <h1>{{ content.title }}</h1><p class="article-summary">{{ content.summary }}</p>
      <div class="article-body">{{ content.body }}</div>
      <a v-if="content.sourceUrl" class="quiet-link" :href="content.sourceUrl" target="_blank" rel="noopener noreferrer">公开资料来源 <ArrowUpRight :size="15" /></a>
    </template>
  </article>
</template>
