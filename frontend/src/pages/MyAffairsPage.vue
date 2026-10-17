<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BedDouble, CalendarCheck, ClipboardCheck, FileCheck2, CalendarDays, RefreshCw, Wrench, ChevronDown } from '@lucide/vue'
import ServiceEntry from '../components/ServiceEntry.vue'
import AffairTimeline from '../components/AffairTimeline.vue'
import BaseSelect from '../components/BaseSelect.vue'
import { api, errorMessage } from '../lib/api'
import { affairAction, affairStatus, affairStatuses } from '../lib/affairs'
import { currentUser } from '../stores/auth'
import { safeAffairDestination } from '../lib/navigation'
import type { Paged } from '../foundation'
interface Item { kind: string; id: number; name: string; status: string; version: number; updatedAt: string; targetPath: string }
interface Summary { total: number; drafts: number; pending: number; returned: number; finished: number }
const route = useRoute(), router = useRouter(), kind = ref(''), status = ref(''), loading = ref(true), error = ref('')
const expanded = ref<string | null>(null)
const list = ref<Paged<Item> & { summary: Summary }>({ items: [], total: 0, page: 1, pageSize: 20, summary: { total: 0, drafts: 0, pending: 0, returned: 0, finished: 0 } })
const statuses = computed(() => affairStatuses(kind.value))
const can = (permission: string) => currentUser.value?.permissions.includes(permission)
const kindOptions = computed(() => [
  { value: '', label: '全部事项' },
  ...(can('reservation:apply') ? [{ value: 'RESERVATION', label: '场地与设备借用' }] : []),
  ...(can('leave:apply') ? [{ value: 'LEAVE', label: '请假申请' }] : []),
  ...(can('certificate:apply') ? [{ value: 'CERTIFICATE', label: '在校证明申请' }] : []),
  ...(can('repair:apply') ? [{ value: 'REPAIR', label: '校园报修' }] : []),
  ...(can('housing:read') ? [{ value: 'HOUSING', label: '调宿申请' }] : []),
])
const statusOptions = computed(() => [
  { value: '', label: '全部状态' },
  ...Object.entries(statuses.value).map(([key, label]) => ({ value: key, label }))
])
let sequence = 0
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; expanded.value = null
  list.value = { items: [], total: 0, page: 1, pageSize: 20, summary: { total: 0, drafts: 0, pending: 0, returned: 0, finished: 0 } }
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
    <section class="portal-services" aria-labelledby="affairs-services-heading"><div class="service-section-heading"><h2 id="affairs-services-heading">可办理事项</h2><p>选择事项查看要求，先保存草稿再提交</p></div><div class="service-entry-grid"><ServiceEntry v-if="can('reservation:apply')" to="/portal/reservations" title="场地与设备借用" description="申请报告厅、研讨室、体育馆及影音设备借用，查询审核凭证" :icon="CalendarCheck" category="场地借用" badge="在线预约" badge-type="primary" theme="sky" /><ServiceEntry v-if="can('repair:apply')" to="/repairs/mine" title="校园设施报修与验收" description="填写设施故障，跟进维修并确认处理结果" :icon="Wrench" category="设施后勤" badge="维修追踪" badge-type="warning" theme="amber" /><ServiceEntry v-if="can('leave:apply')" to="/portal/leave" title="请假申请与办理记录" description="填写请假时间与事由，补充材料并查询处理结果" :icon="ClipboardCheck" category="学生事务" badge="在线审批" badge-type="neutral" theme="teal" /><ServiceEntry v-if="can('certificate:apply')" to="/portal/certificates" title="在校证明申请与下载" description="确认本人登记信息，申请并下载受控演示证明" :icon="FileCheck2" category="证明申领" badge="电子PDF" badge-type="neutral" theme="indigo" /><ServiceEntry v-if="can('housing:read')" to="/housing/transfer" title="调宿申请与入住进度" description="提交调宿资料，查询初审、床位预留与入住结果" :icon="BedDouble" category="住宿服务" badge="初审跟进" badge-type="success" theme="emerald" /><ServiceEntry v-if="can('leave:apply')" to="/portal/leave?tab=calendar" title="我的请假日程" description="核对本人已通过的请假时间与来源申请" :icon="CalendarDays" category="事务日程" badge="日历视图" badge-type="neutral" theme="purple" /></div></section>
    <form class="foundation-filters leave-filters" @submit.prevent="search"><label>办事种类<BaseSelect v-model="kind" :options="kindOptions" compact @change="status = ''" /></label><label>办理状态<BaseSelect v-model="status" :options="statusOptions" compact /></label><button class="button compact" :disabled="loading">查询</button></form>
    <section class="portal-panel leave-list" aria-label="本人办事列表"><div class="section-heading"><h2>办理记录</h2><span class="muted">当前筛选共 {{ list.total }} 条</span></div><p v-if="loading" role="status">正在读取本人办事记录…</p><p v-else-if="!list.items.length" class="empty-state">当前筛选下没有记录，可从上方选择事项开始办理。</p><div v-else class="table-scroll"><table class="campus-table foundation-table affairs-table"><thead><tr><th>事项</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead><tbody><template v-for="item in list.items" :key="item.kind + item.id"><tr><td><strong>{{ item.name }}</strong><small>申请 #{{ item.id }} · V{{ item.version }}</small></td><td><span class="status-label">{{ affairStatus(item.kind, item.status) }}</span></td><td>{{ item.updatedAt.slice(0,16).replace('T',' ') }}</td><td><div class="affair-row-actions"><button type="button" class="text-button" :aria-expanded="expanded === item.kind + item.id" :aria-controls="'timeline-' + item.kind + item.id" @click="expanded = expanded === item.kind + item.id ? null : item.kind + item.id">{{ expanded === item.kind + item.id ? '收起进度' : '查看进度' }}<ChevronDown :size="13" :class="{ rotated: expanded === item.kind + item.id }" /></button><RouterLink v-if="safeAffairDestination(item.targetPath)" class="text-button" :to="item.targetPath">{{ affairAction(item.kind, item.status) }}</RouterLink></div></td></tr><tr v-if="expanded === item.kind + item.id" class="affair-timeline-row"><td :id="'timeline-' + item.kind + item.id" colspan="4" class="timeline-cell"><AffairTimeline :kind="item.kind" :request-id="item.id" /></td></tr></template></tbody></table></div><div class="pagination"><span>第 {{ list.page }} 页</span><button class="button compact" :disabled="loading || list.page <= 1" @click="page(list.page - 1)">上一页</button><button class="button compact" :disabled="loading || list.page * list.pageSize >= list.total" @click="page(list.page + 1)">下一页</button></div></section><p class="data-note">这里只显示本人有权查看的申请；调宿的待安排、待入住计入正在办理，过期计入已结束。请假日程不自动改变课程或考勤，证明结果仅用于毕业设计演示。</p>
  </div>
</template>
<style scoped>
.affair-row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 16px; }
.affair-row-actions button, .affair-row-actions a { white-space: nowrap; }
.affair-row-actions .rotated { transform: rotate(180deg); }
.affairs-table .timeline-cell { padding: 0; min-width: 0; }
@media (max-width: 640px) {
  .affairs-table, .affairs-table tbody { display: block; width: 100%; }
  .affairs-table thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
  .affairs-table tr { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); padding: 12px 0; border-bottom: 1px solid var(--line); }
  .affairs-table td, .affairs-table td:first-child { display: block; min-width: 0; padding: 6px 10px; border-bottom: 0; }
  .affairs-table td:first-child, .affairs-table td:last-child { grid-column: 1 / -1; }
  .affairs-table .affair-timeline-row { display: block; padding: 0; }
  .affairs-table .timeline-cell { padding: 0; }
}
</style>
