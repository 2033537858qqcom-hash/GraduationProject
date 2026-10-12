<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Check, RefreshCw, ArrowUpRight } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { safeAffairDestination } from '../lib/navigation'
import type { Notice } from '../types'
import type { Paged } from '../foundation'
const route = useRoute(), router = useRouter()
const list = ref<Paged<Notice> & { unreadCount: number }>({ items: [], total: 0, page: 1, pageSize: 20, unreadCount: 0 })
const filter = ref(''), loading = ref(true), reading = ref<number | null>(null), error = ref('')
let sequence = 0
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; filter.value = String(route.query.read ?? '')
  const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
  if (filter.value) params.set('read', filter.value)
  try { const result = await api<typeof list.value>('/notices?' + params); if (request === sequence) list.value = result }
  catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function mark(notice: Notice, open = false) {
  reading.value = notice.id; error.value = ''
  try {
    if (!notice.read) await api('/notices/' + notice.id + '/read', { method: 'POST' })
    const target = safeAffairDestination(notice.targetPath)
    if (open && target) await router.push(target)
    else await load()
  } catch (cause) { error.value = errorMessage(cause) }
  finally { reading.value = null }
}
async function search() { await router.replace({ query: { read: filter.value, page: '1' } }) }
async function page(number: number) { await router.replace({ query: { ...route.query, page: String(number) } }) }
onMounted(() => { void load() }); watch(() => route.fullPath, () => { void load() })
</script>
<template>
  <div>
<div class="page-heading-row"><div><span class="portal-context">个人消息</span><h1>消息通知</h1><p class="muted">阅读发给你的消息，查看对应申请的办理进度。</p></div><button class="button compact" :disabled="loading || reading !== null" @click="load"><RefreshCw :size="15" />刷新消息</button></div><p v-if="error" class="alert" role="alert">{{ error }}</p><p class="notice-summary"><Bell :size="18" /><strong>未读 {{ list.unreadCount }} 条</strong><span>当前筛选共 {{ list.total }} 条</span></p>
    <form class="foundation-filters leave-filters" @submit.prevent="search"><label>阅读状态<select v-model="filter" :disabled="reading !== null"><option value="">全部消息</option><option value="false">未读消息</option><option value="true">已读消息</option></select></label><button class="button compact" :disabled="loading || reading !== null">查询</button></form>
    <section class="portal-panel" aria-label="个人消息列表"><p v-if="loading" role="status">正在读取个人消息…</p><p v-else-if="!list.items.length" class="empty-state">当前筛选下没有消息。</p><article v-for="notice in loading ? [] : list.items" :key="notice.id" class="notice-row"><div class="notice-heading"><h3>{{ notice.title }}</h3><span class="status-label" :class="{ read: notice.read }">{{ notice.read ? '已读' : '未读' }}</span></div><p>{{ notice.body }}</p><div class="notice-actions"><time>{{ notice.createdAt.slice(0,16).replace('T',' ') }}</time><button v-if="safeAffairDestination(notice.targetPath)" class="text-button" :disabled="reading !== null" @click="mark(notice, true)">查看对应申请<ArrowUpRight :size="14" /></button><button v-if="!notice.read" class="text-button" :disabled="reading !== null" @click="mark(notice)"><Check :size="14" />标为已读</button></div></article><div class="pagination"><span>第 {{ list.page }} 页</span><button class="button compact" :disabled="loading || reading !== null || list.page <= 1" @click="page(list.page - 1)">上一页</button><button class="button compact" :disabled="loading || reading !== null || list.page * list.pageSize >= list.total" @click="page(list.page + 1)">下一页</button></div></section><p class="data-note">仅查询当前账号的消息。历史普通消息保留原文；有业务来源且当前仍有权限的消息提供申请入口。</p>
  </div>
</template>
