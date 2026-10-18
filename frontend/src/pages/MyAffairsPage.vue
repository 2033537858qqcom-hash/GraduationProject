<script setup lang="ts">
import { computed, onMounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, BedDouble, CalendarCheck, ClipboardCheck, ClipboardList, FileCheck2, CalendarDays, FileText, RefreshCw, Search, Wrench, ChevronDown, ChevronLeft, ChevronRight } from '@lucide/vue'
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
const servicesPanel = ref<HTMLElement | null>(null)
const list = ref<Paged<Item> & { summary: Summary }>({ items: [], total: 0, page: 1, pageSize: 20, summary: { total: 0, drafts: 0, pending: 0, returned: 0, finished: 0 } })
const statuses = computed(() => affairStatuses(kind.value))
const can = (permission: string) => currentUser.value?.permissions.includes(permission)
const services = computed(() => [
  { permission: 'leave:apply', to: '/portal/leave', title: '请假申请', description: '填写请假时间、事由与材料', icon: ClipboardCheck },
  { permission: 'certificate:apply', to: '/portal/certificates', title: '在校证明', description: '核对登记信息，申领电子证明', icon: FileCheck2 },
  { permission: 'reservation:apply', to: '/portal/reservations', title: '场地与设备借用', description: '预约校内场地与影音设备', icon: CalendarCheck },
  { permission: 'repair:apply', to: '/repairs/mine', title: '校园设施报修', description: '提交故障，跟进维修与验收', icon: Wrench },
  { permission: 'housing:read', to: '/housing/transfer', title: '调宿申请', description: '提交申请，查询安排与入住进度', icon: BedDouble },
].filter(service => can(service.permission)))
const kindIcons: Record<string, Component> = { LEAVE: ClipboardCheck, CERTIFICATE: FileCheck2, RESERVATION: CalendarCheck, REPAIR: Wrench, HOUSING: BedDouble }
const overviewItems = computed(() => [
  { key: 'total', label: '全部申请', value: list.value.summary.total },
  { key: 'pending', label: '正在办理', value: list.value.summary.pending },
  { key: 'returned', label: '需要补充', value: list.value.summary.returned },
  { key: 'drafts', label: '未提交草稿', value: list.value.summary.drafts },
  { key: 'finished', label: '已结束办理', value: list.value.summary.finished },
])
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
function focusServices() {
  servicesPanel.value?.scrollIntoView({ block: 'start' })
  servicesPanel.value?.focus({ preventScroll: true })
}
onMounted(() => { void load() }); watch(() => route.fullPath, () => { void load() })
</script>
<template>
  <div class="affairs-page">
    <header class="affairs-heading">
      <div>
        <span class="affairs-eyebrow">学生服务中心</span>
        <h1>我的办事</h1>
        <p>查看申请进度，处理补充材料，查收办理结果。</p>
      </div>
      <div class="affairs-heading-actions">
        <button type="button" class="button compact" :disabled="loading" @click="load">
          <RefreshCw :size="14" aria-hidden="true" />刷新记录
        </button>
        <a href="#affairs-services" class="button compact primary affairs-mobile-new" @click.prevent="focusServices">
          办理新事项<ChevronRight :size="14" aria-hidden="true" />
        </a>
      </div>
    </header>

    <dl class="affairs-overview" aria-label="本人办事概况" :aria-busy="loading">
      <div v-for="item in overviewItems" :key="item.key" :class="['overview-' + item.key, { 'needs-attention': item.key === 'returned' && item.value > 0 && !loading && !error }]">
        <dt>{{ item.label }}</dt>
        <dd>{{ loading || error ? '—' : item.value }}<span v-if="!loading && !error">项</span></dd>
      </div>
    </dl>

    <div class="affairs-layout">
      <section class="portal-panel affairs-records" aria-labelledby="affairs-records-heading" :aria-busy="loading">
        <div class="affairs-records-heading">
          <div>
            <h2 id="affairs-records-heading">办理记录</h2>
            <p>按事项与状态查找申请记录。</p>
          </div>
          <span v-if="!loading && !error" class="affairs-result-count">共 {{ list.total }} 条</span>
        </div>

        <form class="affairs-filters" aria-label="筛选办理记录" @submit.prevent="search">
          <div class="affairs-filter-field">
            <label for="affairs-kind">办事种类</label>
            <BaseSelect id="affairs-kind" v-model="kind" :options="kindOptions" aria-label="办事种类" :disabled="loading" compact @change="status = ''" />
          </div>
          <div class="affairs-filter-field">
            <label for="affairs-status">办理状态</label>
            <BaseSelect id="affairs-status" v-model="status" :options="statusOptions" aria-label="办理状态" :disabled="loading" compact />
          </div>
          <div class="affairs-filter-actions">
            <button class="button compact primary" :disabled="loading"><Search :size="14" aria-hidden="true" />查询</button>
            <RouterLink v-if="route.query.kind || route.query.status" class="affairs-clear-filter" to="/portal/affairs">清除筛选</RouterLink>
          </div>
        </form>

        <div v-if="loading && !list.items.length" class="affairs-state" role="status">
          <RefreshCw :size="25" aria-hidden="true" />
          <h3>正在读取办理记录</h3>
          <p>请稍候，你的申请进度即将显示。</p>
        </div>
        <div v-else-if="error" class="affairs-state affairs-state-error" role="alert">
          <ClipboardList :size="27" aria-hidden="true" />
          <h3>暂时无法读取记录</h3>
          <p>{{ error }}</p>
          <button type="button" class="button compact" @click="load">重新读取</button>
        </div>
        <div v-else-if="!list.items.length" class="affairs-state">
          <ClipboardList :size="29" aria-hidden="true" />
          <h3>当前没有匹配的办理记录</h3>
          <p v-if="route.query.kind || route.query.status">可以调整办事种类或办理状态，再次查询。</p>
          <p v-else>选择办事入口，开始你的第一项申请。</p>
        </div>
        <div v-else class="affairs-table-wrap" :class="{ 'is-loading': loading }">
          <table class="affairs-table">
            <thead>
              <tr><th scope="col">申请事项</th><th scope="col">办理状态</th><th scope="col">最近更新</th><th scope="col">操作</th></tr>
            </thead>
            <tbody>
              <template v-for="item in list.items" :key="item.kind + item.id">
                <tr :class="{ 'affair-row-expanded': expanded === item.kind + item.id }">
                  <td>
                    <div class="affair-name">
                      <span class="affair-kind-icon"><component :is="kindIcons[item.kind] || FileText" :size="18" aria-hidden="true" /></span>
                      <div><strong>{{ item.name }}</strong><small>申请编号 #{{ item.id }}</small></div>
                    </div>
                  </td>
                  <td><span class="affair-status" :data-status="item.status">{{ affairStatus(item.kind, item.status) }}</span></td>
                  <td class="affair-updated"><time :datetime="item.updatedAt"><span>{{ item.updatedAt.slice(0, 10) }}</span><small>{{ item.updatedAt.slice(11, 16) }}</small></time></td>
                  <td>
                    <div class="affair-row-actions">
                      <RouterLink v-if="safeAffairDestination(item.targetPath)" class="affair-detail-link" :to="item.targetPath">
                        {{ affairAction(item.kind, item.status) }}<ArrowUpRight :size="13" aria-hidden="true" />
                      </RouterLink>
                      <button type="button" class="affair-progress-toggle" :aria-expanded="expanded === item.kind + item.id" :aria-controls="'timeline-' + item.kind + item.id" @click="expanded = expanded === item.kind + item.id ? null : item.kind + item.id">
                        {{ expanded === item.kind + item.id ? '收起进度' : '查看进度' }}<ChevronDown :size="13" aria-hidden="true" :class="{ rotated: expanded === item.kind + item.id }" />
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="expanded === item.kind + item.id" class="affair-timeline-row">
                  <td :id="'timeline-' + item.kind + item.id" colspan="4" class="timeline-cell">
                    <AffairTimeline :kind="item.kind" :request-id="item.id" />
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>

        <div v-if="!error" class="affairs-pagination">
          <span>第 {{ list.page }} / {{ Math.max(1, Math.ceil(list.total / list.pageSize)) }} 页<span class="affairs-pagination-total"> · 共 {{ loading ? '—' : list.total }} 条</span></span>
          <div>
            <button type="button" class="button compact" :disabled="loading || list.page <= 1" @click="page(list.page - 1)"><ChevronLeft :size="14" aria-hidden="true" />上一页</button>
            <button type="button" class="button compact" :disabled="loading || list.page * list.pageSize >= list.total" @click="page(list.page + 1)">下一页<ChevronRight :size="14" aria-hidden="true" /></button>
          </div>
        </div>
      </section>

      <aside id="affairs-services" ref="servicesPanel" class="affairs-services" aria-labelledby="affairs-services-heading" tabindex="-1">
        <div class="affairs-services-heading">
          <span class="affairs-eyebrow">校园办事</span>
          <h2 id="affairs-services-heading">办理新事项</h2>
          <p>选择服务，查看要求并填写申请。</p>
        </div>
        <div class="affairs-service-list">
          <RouterLink v-for="service in services" :key="service.to" :to="service.to" class="affairs-service-link">
            <span class="affairs-service-icon"><component :is="service.icon" :size="18" aria-hidden="true" /></span>
            <span class="affairs-service-copy"><strong>{{ service.title }}</strong><span>{{ service.description }}</span></span>
            <ChevronRight :size="14" class="affairs-service-arrow" aria-hidden="true" />
          </RouterLink>
        </div>
        <div v-if="can('leave:apply')" class="affairs-calendar">
          <RouterLink to="/portal/leave?tab=calendar"><CalendarDays :size="17" aria-hidden="true" /><span>我的请假日程</span><ArrowUpRight :size="14" aria-hidden="true" /></RouterLink>
          <p>核对已通过的请假时间与来源申请。</p>
        </div>
        <p class="affairs-services-note">提交前请核对资料；需要补充时，可进入对应申请查看具体要求。</p>
      </aside>
    </div>

    <details class="affairs-data-note">
      <summary>关于办理记录</summary>
      <p>这里只显示本人有权查看的申请；调宿的待安排、待入住计入正在办理，过期计入已结束。请假日程不自动改变课程或考勤，证明结果仅用于毕业设计演示。</p>
    </details>
  </div>
</template>

<style scoped>
.affairs-page { color: #2b3e52; }
.affairs-heading { display: flex; justify-content: space-between; align-items: center; gap: 24px; margin-bottom: 23px; }
.affairs-eyebrow { color: #526d88; font-size: 11px; line-height: 1.8; letter-spacing: .08em; }
.affairs-heading h1 { margin: 5px 0 7px; font-family: 'Songti SC', 'STSong', 'SimSun', serif; font-size: 29px; font-weight: 600; line-height: 1.35; letter-spacing: .04em; color: #243c55; }
.affairs-heading p { margin: 0; font-size: 13px; color: #66778a; line-height: 1.8; }
.affairs-heading-actions { display: flex; gap: 8px; flex-shrink: 0; }
.affairs-page .button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 34px; padding: 6px 12px; font-size: 12px; line-height: 1.5; border-radius: 5px; border-color: #d4dee8; color: #415a73; background: #fff; box-shadow: none; transition: background-color .15s, border-color .15s; }
.affairs-page .button:hover:not(:disabled) { background: #f3f6fa; border-color: #91a7bf; box-shadow: none; transform: none; filter: none; }
.affairs-page .button.primary { color: #fff; background: #244b78; border-color: #244b78; }
.affairs-page .button.primary:hover:not(:disabled) { background: #1d3f66; border-color: #1d3f66; }
.affairs-page .affairs-mobile-new { display: none; }
.affairs-overview { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); padding: 0; margin: 0 0 24px; border: 1px solid #dfe5ec; border-radius: 8px; background: #fff; overflow: hidden; }
.affairs-overview > div { padding: 16px 24px 15px; border-right: 1px solid #e7ecf1; }
.affairs-overview > div:last-child { border-right: 0; }
.affairs-overview dt { color: #64768a; font-size: 12px; line-height: 1.7; }
.affairs-overview dd { display: flex; align-items: baseline; gap: 7px; margin: 5px 0 0; font-size: 27px; line-height: 1.15; font-weight: 500; font-variant-numeric: tabular-nums; color: #304963; }
.affairs-overview dd span { font-size: 11px; font-weight: 400; color: #768698; }
.affairs-overview .overview-total { background: #edf2f8; }
.affairs-overview .overview-total dd { color: #244b78; }
.affairs-overview .needs-attention { background: #fff8ec; }
.affairs-overview .needs-attention dd { color: #94641e; }
.affairs-layout { display: grid; grid-template-columns: minmax(0, 1fr) 288px; align-items: start; gap: 22px; }
.affairs-page .affairs-records { padding: 0; overflow: visible; min-width: 0; border-radius: 8px; border: 1px solid #dfe5ec; }
.affairs-records-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 21px 24px 18px; }
.affairs-records-heading h2 { font-size: 18px; line-height: 1.5; margin: 0 0 5px; font-weight: 600; color: #263e57; }
.affairs-records-heading p { margin: 0; font-size: 12px; line-height: 1.6; color: #697b8d; }
.affairs-result-count { flex-shrink: 0; color: #526d88; font-size: 12px; font-variant-numeric: tabular-nums; }
.affairs-filters { display: flex; align-items: flex-end; gap: 12px; margin: 0; padding: 16px 24px; background: #f8fafc; border-block: 1px solid #e8edf2; }
.affairs-filter-field { flex: 1 1 170px; max-width: 215px; min-width: 0; }
.affairs-filter-field label { display: block; font-size: 11px; color: #576d82; margin-bottom: 7px; }
.affairs-filters :deep(.apple-select-trigger) { height: 34px; font-size: 12px; border-radius: 5px; box-shadow: none; }
.affairs-filter-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; flex-shrink: 0; }
.affairs-clear-filter { font-size: 12px; color: #586e83; text-decoration: underline; text-underline-offset: 3px; }
.affairs-table-wrap { min-width: 0; }
.affairs-table { width: 100%; border-collapse: collapse; table-layout: auto; text-align: left; }
.affairs-table th { padding: 12px 16px; background: #fcfdfe; border-bottom: 1px solid #e8edf2; color: #6d7c8e; font-size: 11px; font-weight: 500; white-space: nowrap; }
.affairs-table th:first-child, .affairs-table td:first-child { padding-left: 24px; }
.affairs-table th:last-child, .affairs-table td:last-child { padding-right: 24px; }
.affairs-table td { padding: 15px 16px; border-bottom: 1px solid #edf0f4; vertical-align: middle; font-size: 12px; }
.affairs-table tbody > tr:last-child > td { border-bottom: 0; }
.affairs-table tbody > tr:not(.affair-timeline-row):hover, .affair-row-expanded { background: #f8fafd; }
.affair-name { display: flex; align-items: center; gap: 11px; min-width: 0; }
.affair-name > div { min-width: 0; }
.affair-kind-icon { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 32px; width: 32px; height: 34px; border: 1px solid #e6ecf3; border-radius: 6px; background: #f5f8fb; color: #577595; }
.affair-name strong { display: block; font-size: 13px; color: #2d435b; font-weight: 500; line-height: 1.7; overflow-wrap: anywhere; }
.affair-name small { display: block; font-size: 11px; color: #708195; margin-top: 3px; line-height: 1.6; }
.affair-status { display: inline-flex; align-items: center; gap: 6px; padding: 3px 7px; border-radius: 4px; font-size: 11px; line-height: 1.6; white-space: nowrap; color: #68788a; background: #f0f3f6; }
.affair-status::before { content: ''; width: 4px; height: 4px; border-radius: 50%; background: currentColor; flex-shrink: 0; }
.affair-status:is([data-status="SUBMITTED"], [data-status="ASSIGNED"], [data-status="IN_PROGRESS"], [data-status="AWAITING_CONFIRMATION"], [data-status="AWAITING_ASSIGNMENT"], [data-status="AWAITING_CHECKIN"]) { color: #315f8d; background: #edf3f9; }
.affair-status[data-status="RETURNED"] { color: #8e601d; background: #fbf2e4; }
.affair-status:is([data-status="APPROVED"], [data-status="COMPLETED"]) { color: #3d7161; background: #edf5f0; }
.affair-status[data-status="REJECTED"] { color: #9d5252; background: #faeeee; }
.affair-updated time { display: block; color: #586e84; font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; line-height: 1.7; }
.affair-updated small { display: block; color: #7b8898; font-size: 11px; margin-top: 2px; }
.affair-row-actions { display: flex; flex-direction: column; align-items: flex-start; gap: 7px; }
.affair-detail-link, .affair-progress-toggle { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; line-height: 1.7; white-space: nowrap; }
.affair-detail-link { color: #315e8c; font-weight: 500; }
.affair-progress-toggle { padding: 0; background: transparent; border: 0; border-radius: 2px; color: #6a7d90; cursor: pointer; }
.affair-detail-link:hover, .affair-progress-toggle:hover { color: #183c65; text-decoration: underline; text-underline-offset: 3px; }
.affair-progress-toggle svg { transition: transform .15s; }
.affair-progress-toggle .rotated { transform: rotate(180deg); }
.affairs-page :is(a, button):focus-visible { outline: 2px solid #244b78; outline-offset: 3px; }
.affairs-table .timeline-cell { padding: 0; }
.affairs-page :deep(.affair-timeline) { padding: 22px 24px; background: #f4f7fb; border-color: #e2e9f1; }
.affairs-page :deep(.timeline-current) { border-left-color: #6a87a6; }
.affairs-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 16px 24px; border-top: 1px solid #e8edf2; font-size: 12px; color: #64768a; font-variant-numeric: tabular-nums; }
.affairs-pagination > div { display: flex; gap: 7px; }
.affairs-pagination .button { min-height: 32px; height: 32px; padding: 5px 9px; }
.affairs-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; min-height: 225px; padding: 30px 24px; text-align: center; color: #7790a8; }
.affairs-state h3 { font-size: 14px; font-weight: 500; color: #49627b; margin: 3px 0 0; }
.affairs-state p { margin: 0; color: #687d91; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.affairs-state-error h3 { color: #8b5555; }
.affairs-services { position: sticky; top: 20px; scroll-margin-top: 20px; min-width: 0; background: #fff; border: 1px solid #dfe5ec; border-radius: 8px; }
.affairs-services-heading { padding: 20px 20px 17px; border-bottom: 1px solid #e8edf2; }
.affairs-services-heading h2 { font-family: 'Songti SC', 'STSong', 'SimSun', serif; font-size: 21px; line-height: 1.4; font-weight: 600; margin: 5px 0 8px; color: #2a4561; }
.affairs-services-heading p { margin: 0; font-size: 12px; color: #697c90; line-height: 1.7; }
.affairs-service-list { padding: 0 16px; }
.affairs-service-link { display: flex; align-items: center; gap: 11px; padding: 17px 3px; border-bottom: 1px solid #edf0f4; }
.affairs-service-link:last-child { border-bottom: 0; }
.affairs-service-icon { flex: 0 0 32px; width: 32px; height: 34px; display: inline-flex; align-items: center; justify-content: center; border-radius: 5px; background: #f0f4f8; color: #416589; }
.affairs-service-copy { display: block; min-width: 0; flex: 1; }
.affairs-service-copy strong { display: block; color: #304b66; font-size: 13px; font-weight: 500; line-height: 1.6; }
.affairs-service-copy > span { display: block; color: #6b7f92; font-size: 11px; margin-top: 5px; line-height: 1.7; }
.affairs-service-arrow { color: #8b9bac; flex-shrink: 0; transition: transform .15s; }
.affairs-service-link:hover .affairs-service-copy strong { color: #1e5286; }
.affairs-service-link:hover .affairs-service-arrow { color: #315f8b; transform: translateX(2px); }
.affairs-calendar { margin: 0 19px; padding: 16px 0; border-top: 1px solid #e8edf2; }
.affairs-calendar a { display: flex; align-items: center; gap: 9px; color: #41668b; font-size: 12px; }
.affairs-calendar a span { flex: 1; }
.affairs-calendar p { margin: 8px 0 0; font-size: 11px; color: #738397; line-height: 1.8; }
.affairs-services-note { margin: 0; padding: 15px 20px; border-radius: 0 0 8px 8px; border-top: 1px solid #e8edf2; background: #f8fafc; font-size: 11px; line-height: 1.9; color: #657a8e; }
.affairs-data-note { padding: 18px 2px 0; color: #6b7f92; font-size: 11px; line-height: 1.9; }
.affairs-data-note summary { width: fit-content; cursor: pointer; }
.affairs-data-note p { max-width: 830px; margin: 8px 0 0; }
@media (max-width: 1150px) {
  .affairs-layout { grid-template-columns: minmax(0, 1fr) 258px; gap: 18px; }
  .affairs-records-heading, .affairs-filters { padding-inline: 18px; }
  .affairs-table th, .affairs-table td { padding-inline: 12px; }
  .affairs-table th:first-child, .affairs-table td:first-child { padding-left: 18px; }
  .affairs-table th:last-child, .affairs-table td:last-child { padding-right: 18px; }
  .affair-kind-icon { display: none; }
  .affairs-filters { gap: 10px; }
  .affairs-filter-actions { gap: 8px; }
}
@media (max-width: 1000px) {
  .affairs-layout { grid-template-columns: minmax(0, 1fr); }
  .affairs-services { position: static; }
  .affairs-service-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; }
  .affairs-service-link:last-child { border-bottom: 1px solid #edf0f4; }
  .affair-kind-icon { display: inline-flex; }
  .affairs-page .affairs-mobile-new { display: inline-flex; }
  .affairs-overview > div { padding-inline: 18px; }
}
@media (max-width: 640px) {
  .affairs-heading { align-items: flex-start; flex-direction: column; gap: 14px; margin-bottom: 18px; }
  .affairs-heading h1 { font-size: 27px; }
  .affairs-heading p { font-size: 12px; }
  .affairs-heading-actions { width: 100%; justify-content: space-between; }
  .affairs-overview { grid-template-columns: 1.15fr 1fr 1fr; margin-bottom: 18px; }
  .affairs-overview > div { padding: 12px 14px; }
  .affairs-overview .overview-total { grid-row: span 2; display: flex; flex-direction: column; justify-content: center; }
  .affairs-overview .overview-returned { border-right: 0; }
  .affairs-overview .overview-drafts, .affairs-overview .overview-finished { border-top: 1px solid #e7ecf1; }
  .affairs-overview dd { font-size: 23px; }
  .affairs-overview dt { font-size: 11px; }
  .affairs-overview .overview-total dd { font-size: 31px; margin-top: 10px; }
  .affairs-records-heading { padding: 18px 16px 15px; }
  .affairs-records-heading h2 { font-size: 17px; }
  .affairs-records-heading p { font-size: 11px; }
  .affairs-filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 14px 16px; }
  .affairs-filter-field { max-width: none; }
  .affairs-filter-actions { grid-column: 1 / -1; justify-content: flex-end; }
  .affairs-clear-filter { margin-right: auto; order: -1; }
  .affairs-table, .affairs-table tbody { display: block; width: 100%; }
  .affairs-table thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
  .affairs-table tbody > tr { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px 8px; padding: 17px 16px; border-bottom: 1px solid #e8edf2; }
  .affairs-table tbody > tr:last-child { border-bottom: 0; }
  .affairs-table td, .affairs-table td:first-child, .affairs-table td:last-child { padding: 0; border: 0; min-width: 0; }
  .affairs-table td:first-child { grid-column: 1; grid-row: 1; }
  .affairs-table td:nth-child(2) { grid-column: 2; grid-row: 1; align-self: start; padding-top: 2px; }
  .affairs-table td:nth-child(3) { grid-column: 1 / -1; grid-row: 2; }
  .affairs-table td:last-child { grid-column: 1 / -1; grid-row: 3; }
  .affair-name { gap: 9px; }
  .affair-kind-icon { flex-basis: 29px; width: 29px; height: 32px; }
  .affair-name strong { font-size: 13px; }
  .affair-name small { font-size: 10px; margin-top: 2px; }
  .affair-updated time { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px; color: #6d7f91; font-size: 11px; }
  .affair-updated time::before { content: '最近更新'; margin-right: 3px; color: #7a8999; font-size: 10px; }
  .affair-updated small { display: inline; margin: 0; }
  .affair-row-actions { flex-direction: row; flex-wrap: wrap; justify-content: space-between; gap: 10px; margin-top: 3px; }
  .affair-detail-link, .affair-progress-toggle { min-height: 28px; }
  .affairs-table tbody > .affair-timeline-row { display: block; padding: 0; }
  .affairs-table .timeline-cell { display: block; }
  .affairs-page :deep(.affair-timeline) { padding: 18px 16px; }
  .affairs-pagination { padding: 15px 16px; font-size: 11px; gap: 10px; }
  .affairs-pagination-total { display: none; }
  .affairs-pagination .button { padding-inline: 8px; font-size: 11px; }
  .affairs-service-list { display: block; }
  .affairs-service-link:last-child { border-bottom: 0; }
  .affairs-services-heading { padding: 18px 20px; }
}
@media (prefers-reduced-motion: reduce) {
  .affairs-page .button, .affair-progress-toggle svg, .affairs-service-arrow { transition: none; }
}
</style>
