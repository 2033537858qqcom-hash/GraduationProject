<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ClipboardCheck, FileCheck2, CalendarDays, RefreshCw, Wrench } from '@lucide/vue'
import ServiceEntry from '../components/ServiceEntry.vue'
import { api, errorMessage } from '../lib/api'
import { leaveStatuses } from '../leave'
import { repairStatuses } from '../repairs'
import { safeAffairDestination } from '../lib/navigation'
import type { Paged } from '../foundation'
interface Item { kind: string; id: number; name: string; status: string; version: number; updatedAt: string; targetPath: string }
interface Summary { total: number; drafts: number; pending: number; returned: number; finished: number }
const route = useRoute(), router = useRouter(), kind = ref(''), status = ref(''), loading = ref(true), error = ref('')
const list = ref<Paged<Item> & { summary: Summary }>({ items: [], total: 0, page: 1, pageSize: 20, summary: { total: 0, drafts: 0, pending: 0, returned: 0, finished: 0 } })
const statuses: Record<string, string> = { ...leaveStatuses, ...repairStatuses, SUBMITTED: '待处理', COMPLETED: '办理完成' }
let sequence = 0
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''
  kind.value = String(route.query.kind ?? ''); status.value = String(route.query.status ?? '')
  const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
  if (kind.value) params.set('kind', kind.value)
  if (status.value) params.set('status', status.value)
  try { const result = await api<typeof list.value>('/affairs/mine?' + params); if (request === sequence) list.value = result }
  catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function search() { await router.replace({ query: { kind: kind.value, status: status.value, page: '1' } }) }
async function page(number: number) { await router.replace({ query: { ...route.query, page: String(number) } }) }
onMounted(() => { void load() }); watch(() => route.fullPath, () => { void load() })
</script>
<template>
  <div>
<div class="page-heading-row"><div><span class="portal-context">学生服务中心</span><h1>我的办事</h1><p class="muted">申请、补交与结果，在这里统一查看。</p></div><button class="button compact" :disabled="loading" @click="load"><RefreshCw :size="15" />刷新记录</button></div><p v-if="error" class="alert" role="alert">{{ error }}</p>
    <div class="affairs-summary" aria-label="本人办事概况"><div><strong>{{ list.summary.total }}</strong><span>全部申请</span></div><div><strong>{{ list.summary.drafts }}</strong><span>未提交草稿</span></div><div><strong>{{ list.summary.pending }}</strong><span>正在办理</span></div><div :class="{ attention: list.summary.returned > 0 }"><strong>{{ list.summary.returned }}</strong><span>需要补充</span></div><div><strong>{{ list.summary.finished }}</strong><span>已结束办理</span></div></div>
    <section class="portal-services" aria-labelledby="affairs-services-heading"><div class="service-section-heading"><h2 id="affairs-services-heading">可办理事项</h2><p>选择事项查看要求，先保存草稿再提交</p></div><div class="service-entry-grid"><ServiceEntry to="/repairs/mine" title="校园设施报修与验收" description="填写设施故障，跟进维修并确认处理结果" :icon="Wrench" /><ServiceEntry to="/portal/leave" title="请假申请与办理记录" description="填写请假时间与事由，补充材料并查询处理结果" :icon="ClipboardCheck" /><ServiceEntry to="/portal/certificates" title="在校证明申请与下载" description="确认本人登记信息，申请并下载受控演示证明" :icon="FileCheck2" /><ServiceEntry to="/portal/leave?tab=calendar" title="我的请假日程" description="核对本人已通过的请假时间与来源申请" :icon="CalendarDays" /></div></section>
    <form class="foundation-filters leave-filters" @submit.prevent="search"><label>办事种类<select v-model="kind"><option value="">全部事项</option><option value="LEAVE">请假申请</option><option value="CERTIFICATE">在校证明申请</option><option value="REPAIR">校园报修</option></select></label><label>办理状态<select v-model="status"><option value="">全部状态</option><option v-for="(label,key) in statuses" :key="key" :value="key">{{ label }}</option></select></label><button class="button compact" :disabled="loading">查询</button></form>
    <section class="portal-panel leave-list" aria-label="本人办事列表"><div class="section-heading"><h2>办理记录</h2><span class="muted">当前筛选共 {{ list.total }} 条</span></div><p v-if="loading" role="status">正在读取本人办事记录…</p><p v-else-if="!list.items.length" class="empty-state">当前筛选下没有记录，可从上方选择事项开始办理。</p><div v-else class="table-scroll"><table class="campus-table foundation-table"><thead><tr><th>事项</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="item in list.items" :key="item.kind + item.id"><td><strong>{{ item.name }}</strong><small>申请 #{{ item.id }} · V{{ item.version }}</small></td><td><span class="status-label">{{ statuses[item.status] }}</span></td><td>{{ item.updatedAt.slice(0,16).replace('T',' ') }}</td><td><RouterLink v-if="safeAffairDestination(item.targetPath)" class="text-button" :to="item.targetPath">{{ item.status === 'RETURNED' ? '查看补交要求' : item.status === 'DRAFT' ? '继续办理' : item.status === 'COMPLETED' ? (item.kind === 'REPAIR' ? '查看报修结果' : '查看证明结果') : '查看办理详情' }}</RouterLink></td></tr></tbody></table></div><div class="pagination"><span>第 {{ list.page }} 页</span><button class="button compact" :disabled="loading || list.page <= 1" @click="page(list.page - 1)">上一页</button><button class="button compact" :disabled="loading || list.page * list.pageSize >= list.total" @click="page(list.page + 1)">下一页</button></div></section><p class="data-note">这里只显示本人申请。请假日程不自动改变课程或考勤，证明结果仅用于毕业设计演示。</p>
  </div>
</template>
