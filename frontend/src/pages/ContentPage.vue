<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, ArrowUpRight } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import type { PublicContent } from '../types'
import { contentOrigin, contentParagraphs, formatPublicDate, publicSource } from '../lib/publicReading'
const route = useRoute()
const content = ref<PublicContent | null>(null)
const error = ref('')
const loading = ref(false)
const origin = computed(() => contentOrigin(route.params.slug, route.query.from))
const source = computed(() => publicSource(content.value?.sourceUrl))
const paragraphs = computed(() => contentParagraphs(content.value?.body ?? ''))
const date = computed(() => formatPublicDate(content.value?.publishedAt))
let generation = 0
let controller: AbortController | undefined
async function load() {
  const ticket = ++generation
  controller?.abort()
  const request = new AbortController()
  controller = request
  content.value = null
  error.value = ''
  loading.value = true
  try {
    const result = await api<PublicContent>('/public/content/' + encodeURIComponent(String(route.params.slug)), { signal: request.signal })
    if (ticket === generation) content.value = result
  } catch (cause) { if (ticket === generation && !request.signal.aborted) error.value = errorMessage(cause) }
  finally { if (ticket === generation) loading.value = false }
}
watch(() => route.params.slug, load, { immediate: true })
onUnmounted(() => { generation++; controller?.abort() })
</script>
<template>
  <article class="public-article campus-article page-width">
    <RouterLink class="article-back" :to="origin.target"><ArrowLeft :size="14" />返回{{ origin.name }}</RouterLink>
    <p v-if="loading" class="article-load-state" role="status">正在加载校园信息…</p>
    <div v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" @click="load">重试</button></div>
    <template v-if="content">
      <header class="article-heading"><span class="article-eyebrow">{{ origin.name }} / PUBLIC INFORMATION</span><h1>{{ content.title }}</h1><div class="article-meta"><span>{{ content.sourceType === 'DEMO' ? '毕业设计演示内容' : '学校公开资料' }}</span><time v-if="date" :datetime="content.publishedAt ?? undefined">发布于 {{ date }}</time></div></header>
      <div class="article-layout">
        <div class="article-copy"><p class="article-summary">{{ content.summary }}</p><div class="article-body"><p v-for="(paragraph,index) in paragraphs" :key="index">{{ paragraph }}</p></div></div>
        <aside class="article-context" aria-label="资料来源"><h2>资料来源</h2><p>{{ content.sourceType === 'DEMO' ? '平台演示说明' : '学校公开资料' }}<template v-if="source"><br>{{ source.host }}</template></p><a v-if="source" :href="source.href" target="_blank" rel="noopener noreferrer">查看原始来源 <ArrowUpRight :size="14" /></a></aside>
      </div>
      <nav class="article-bottom" aria-label="继续浏览"><RouterLink :to="origin.target"><ArrowLeft :size="14" />返回{{ origin.name }}</RouterLink><RouterLink to="/campus/explore?view=model">继续探索校园 <ArrowUpRight :size="14" /></RouterLink></nav>
    </template>
  </article>
</template>
