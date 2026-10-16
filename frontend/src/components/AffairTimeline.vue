<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { Clock3, RefreshCw } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { affairTimelinePath, type AffairTimeline } from '../lib/affairTimeline'
import { affairStatus } from '../lib/affairs'
const props = defineProps<{ kind: string; requestId: number }>()
const data = ref<AffairTimeline | null>(null), loading = ref(true), error = ref('')
let sequence = 0
let controller: AbortController | null = null
function when(value: string) { return value.replace('T', ' ').slice(0, 19) }
async function load(page = 1) {
  const request = ++sequence, version = page === 1 ? undefined : data.value?.version
  controller?.abort(); controller = new AbortController()
  loading.value = true; error.value = ''; data.value = null
  try {
    const result = await api<AffairTimeline>(affairTimelinePath(props.kind, props.requestId, page, version), { signal: controller.signal })
    if (request === sequence) data.value = result
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) { loading.value = false; controller = null } }
}
watch(() => [props.kind, props.requestId], () => { void load() }, { immediate: true })
onBeforeUnmount(() => { sequence++; controller?.abort() })
</script>
<template>
  <section class="affair-timeline" :aria-label="'申请 #' + requestId + ' 办理时间线'" :aria-busy="loading">
    <div class="timeline-heading"><h3><Clock3 :size="16" />办理时间线 <small>申请 #{{ requestId }}</small></h3><button type="button" class="text-button" :disabled="loading" @click="load()"><RefreshCw :size="13" />刷新进度</button></div>
    <p v-if="loading" role="status" class="muted">正在读取办理进度…</p>
    <div v-else-if="error" class="alert" role="alert"><p>{{ error }}</p><button type="button" class="text-button" @click="load()">重新读取最新进度</button></div>
    <template v-else-if="data">
      <div class="timeline-current"><span class="status-label">{{ affairStatus(data.kind, data.status) }}</span><p>{{ data.nextStep }}</p><small>当前 V{{ data.version }} · 最近更新 {{ when(data.updatedAt) }}</small></div>
      <p v-if="!data.items.length" class="muted">暂无留存节点，请在原申请核对当前资料和状态。</p>
      <ol v-else class="timeline-events"><li v-for="item in data.items" :key="item.key"><div class="timeline-event-heading"><strong>{{ item.title }}</strong><time :datetime="item.createdAt">{{ when(item.createdAt) }}</time></div><p class="timeline-event-meta">{{ item.actor }}<template v-if="item.version !== null"> · V{{ item.version }}</template><template v-if="item.status"> · {{ affairStatus(data.kind, item.status) }}</template></p><p v-if="item.comment" class="timeline-comment">{{ item.comment }}</p></li></ol>
      <div v-if="data.total > data.pageSize" class="timeline-pagination"><span>共 {{ data.total }} 条 · 第 {{ data.page }} 页</span><div><button type="button" class="text-button" :disabled="loading || data.page <= 1" @click="load(data.page - 1)">较新记录</button><button type="button" class="text-button" :disabled="loading || data.page * data.pageSize >= data.total" @click="load(data.page + 1)">更早记录</button></div></div>
      <p class="timeline-note">{{ data.historyNote }}</p>
    </template>
  </section>
</template>
<style scoped>
.affair-timeline { padding: 22px; background: #f8fafc; border-block: 1px solid var(--line); min-width: 0; }
.timeline-heading, .timeline-event-heading { display: flex; align-items: flex-start; gap: 12px; justify-content: space-between; }
.timeline-heading h3 { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; font-size: 14px; margin: 0; color: var(--text); }
.timeline-heading h3 small { font-size: 11px; color: var(--muted); font-weight: 400; }
.timeline-heading button { flex-shrink: 0; }
.timeline-current { border-left: 2px solid var(--brand); padding-left: 12px; margin: 20px 0; }
.timeline-current p { margin: 6px 0; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.timeline-current > small { font-size: 11px; color: var(--muted); }
.timeline-events { list-style: none; padding: 0; margin: 22px 0 8px 5px; }
.timeline-events li { position: relative; padding: 0 0 22px 20px; border-left: 1px solid #d5dfe9; min-width: 0; }
.timeline-events li::before { position: absolute; content: ''; width: 7px; height: 7px; background: #8199af; border: 2px solid #f8fafc; border-radius: 50%; top: 5px; left: -6px; }
.timeline-events li:last-child { padding-bottom: 5px; border-color: transparent; }
.timeline-event-heading strong { font-size: 13px; line-height: 1.8; font-weight: 500; }
.timeline-event-heading time { font-size: 11px; color: var(--muted); flex-shrink: 0; line-height: 1.8; }
.timeline-event-meta { margin: 3px 0; font-size: 11px; color: var(--muted); }
.timeline-comment { margin: 6px 0 0; font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.timeline-pagination { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 12px; }
.timeline-pagination > div { display: flex; gap: 14px; }
.timeline-note { color: var(--muted); font-size: 11px; line-height: 1.8; margin: 14px 0 0; overflow-wrap: anywhere; }
@media (max-width: 640px) { .affair-timeline { padding: 16px 12px; } .timeline-event-heading { display: block; } .timeline-event-heading time { display: block; margin-top: 2px; } }
</style>
