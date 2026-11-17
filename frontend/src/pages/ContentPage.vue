<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowLeft, ArrowUpRight } from '@lucide/vue';
import { api } from '../lib/api';
import { useRequest } from '../composables/useRequest';
import type { PublicContent } from '../types';
import { contentOrigin, contentParagraphs, formatPublicDate, publicSource } from '../lib/publicReading';
const route = useRoute();
const {
  data: content,
  error,
  loading,
  run: fetchContent,
} = useRequest(
  (signal, slug: string) =>
    api<PublicContent>('/public/content/' + encodeURIComponent(slug), { signal, cacheTtl: 30_000 }),
  { immediate: false, keepPrevious: false },
);
const origin = computed(() => contentOrigin(route.params.slug, route.query.from));
const source = computed(() => publicSource(content.value?.sourceUrl));
const paragraphs = computed(() => contentParagraphs(content.value?.body ?? ''));
const date = computed(() => formatPublicDate(content.value?.publishedAt));
const load = () => fetchContent(String(route.params.slug));
watch(() => route.params.slug, load, { immediate: true });
</script>
<template>
  <article class="public-article campus-article page-width">
    <RouterLink class="article-back" :to="origin.target">
      <ArrowLeft :size="14" />
      返回{{ origin.name }}
    </RouterLink>
    <p v-if="loading" class="article-load-state" role="status">正在加载校园信息…</p>
    <div v-if="error" class="alert" role="alert">
      {{ error }}
      <button class="text-button" @click="load">重试</button>
    </div>
    <template v-if="content">
      <header class="article-heading">
        <span class="article-eyebrow">{{ origin.name }} · 公开信息</span>
        <h1>{{ content.title }}</h1>
        <div class="article-meta">
          <span>{{ content.sourceType === 'DEMO' ? '毕业设计演示内容' : '学校公开资料' }}</span>
          <time v-if="date" :datetime="content.publishedAt ?? undefined">发布于 {{ date }}</time>
        </div>
      </header>
      <div class="article-layout">
        <div class="article-copy">
          <p class="article-summary">{{ content.summary }}</p>
          <div class="article-body">
            <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
          </div>
        </div>
        <aside class="article-context" aria-label="资料来源">
          <h2>资料来源</h2>
          <dl class="article-context-list">
            <div>
              <dt>所属栏目</dt>
              <dd>{{ origin.name }}</dd>
            </div>
            <div>
              <dt>资料性质</dt>
              <dd>{{ content.sourceType === 'DEMO' ? '平台演示说明' : '学校公开资料' }}</dd>
            </div>
            <div v-if="date">
              <dt>发布日期</dt>
              <dd>{{ date }}</dd>
            </div>
            <div v-if="source">
              <dt>来源站点</dt>
              <dd>{{ source.host }}</dd>
            </div>
          </dl>
          <a v-if="source" :href="source.href" target="_blank" rel="noopener noreferrer">
            查看原始来源
            <ArrowUpRight :size="14" />
          </a>
        </aside>
      </div>
      <nav class="article-bottom" aria-label="继续浏览">
        <RouterLink :to="origin.target">
          <ArrowLeft :size="14" />
          返回{{ origin.name }}
        </RouterLink>
        <RouterLink to="/campus/explore?view=model">
          继续探索校园
          <ArrowUpRight :size="14" />
        </RouterLink>
      </nav>
    </template>
  </article>
</template>
