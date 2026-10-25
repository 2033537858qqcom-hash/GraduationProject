<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, BookOpen, CalendarDays, CalendarCheck, ChevronLeft, ChevronRight, ClipboardList, MapPin, RefreshCw, TriangleAlert } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { addScheduleDays, safeScheduleDestination, scheduleSources, shanghaiDay, splitScheduleDays, weekOf } from '../lib/personalSchedule'
import type { PersonalSchedule, ScheduleEvent, ScheduleSource } from '../lib/personalSchedule'

const route = useRoute(), router = useRouter()
const today = shanghaiDay()
const firstWeek = '2000-01-03', lastWeek = weekOf('2099-12-31')
const icons = { LESSON: BookOpen, EXAM: ClipboardList, LEAVE: CalendarDays, RESERVATION: CalendarCheck }
const sources = Object.keys(scheduleSources) as ScheduleSource[]
const data = ref<PersonalSchedule | null>(null), loading = ref(true), error = ref('')
let sequence = 0
const range = computed(() => {
  try {
    const raw = typeof route.query.week === 'string' ? route.query.week : today
    const monday = weekOf(raw)
    if (monday < firstWeek || monday > lastWeek) throw new Error('日期超出范围')
    return { monday, invalid: false }
  } catch { return { monday: weekOf(today), invalid: true } }
})
const monday = computed(() => range.value.monday)
const source = computed(() => sources.includes(route.query.source as ScheduleSource) ? route.query.source as ScheduleSource : 'ALL')
const selectedDay = computed(() => typeof route.query.day === 'string' && Array.from({ length: 7 }, (_, n) => addScheduleDays(monday.value, n)).includes(route.query.day) ? route.query.day : '')
const filtered = computed(() => data.value?.events.filter(e => source.value === 'ALL' || e.source === source.value) ?? [])
const days = computed(() => splitScheduleDays(filtered.value, monday.value))
const shownDays = computed(() => days.value.filter(d => selectedDay.value ? d.day === selectedDay.value : d.events.length > 0))
const visibleCount = computed(() => selectedDay.value ? new Set(shownDays.value.flatMap(d => d.events.map(e => e.key))).size : filtered.value.length)
const overlapCount = computed(() => data.value?.events.filter(e => e.overlapKeys.length > 0).length ?? 0)
const pendingCount = computed(() => data.value?.events.filter(e => e.tentative).length ?? 0)
const unavailableLabels = computed(() => data.value?.unavailableSources.map(key => scheduleSources[key]).join('、') ?? '')
const weekday = (day: string) => new Intl.DateTimeFormat('zh-CN', { weekday: 'short', timeZone: 'UTC' }).format(new Date(day + 'T12:00:00Z'))
const dateLabel = (day: string) => new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', timeZone: 'UTC' }).format(new Date(day + 'T12:00:00Z'))
const sourceCount = (key: ScheduleSource) => data.value?.events.filter(e => e.source === key).length ?? 0
function stateLabel(event: ScheduleEvent) {
  if (event.source === 'LESSON') return '课程安排'
  if (event.source === 'EXAM') return '已发布'
  if (event.source === 'LEAVE') return event.status === 'COMPLETED' ? '已销假' : event.status === 'RETURN_PENDING' ? '待确认销假' : '已批准'
  return ({ SUBMITTED: '待审批', APPROVED: '已批准', IN_USE: '使用中', COMPLETED: '已核销' } as Record<string, string>)[event.status] ?? '预约安排'
}
function overlapText(event: ScheduleEvent) {
  const matches = data.value?.events.filter(e => event.overlapKeys.includes(e.key)) ?? []
  return `与${[...new Set(matches.map(e => scheduleSources[e.source]))].join('、')}时间重叠${event.tentative || matches.some(e => e.tentative) ? '（含待审预约）' : ''}`
}
async function changeWeek(day: string) { await router.push({ path: '/portal/schedule', query: { week: weekOf(day), ...(source.value !== 'ALL' ? { source: source.value } : {}) } }) }
async function filterBy(key: string, day = selectedDay.value) {
  await router.replace({ query: { week: monday.value, ...(key !== 'ALL' ? { source: key } : {}), ...(day ? { day } : {}) } })
}
async function load() {
  const request = ++sequence
  loading.value = true; error.value = ''; data.value = null
  try {
    const result = await api<PersonalSchedule>(`/schedule/mine?${new URLSearchParams({ from: monday.value, to: addScheduleDays(monday.value, 7) })}`)
    if (request === sequence) data.value = result
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
function pickDate(event: Event) { const value = (event.target as HTMLInputElement).value; if (value) void changeWeek(value) }
watch(monday, load, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <div class="personal-schedule" :aria-busy="loading">
    <header class="schedule-page-heading">
      <div><span class="portal-context">学生服务中心 · 时间安排</span><h1>我的日程</h1><p class="muted">把学习与校园事务放在同一周，提前看见时间上的交集。</p></div>
      <div class="schedule-heading-actions"><RouterLink to="/portal/learning/timetable" class="text-button">每周课程表<ArrowUpRight :size="14" /></RouterLink><button class="button compact" :disabled="loading" @click="load"><RefreshCw :size="14" />刷新日程</button></div>
    </header>
    <p v-if="range.invalid" class="content-context-note">日期参数无效，已显示本周。可重新选择日期。</p>
    <section class="portal-panel schedule-overview" aria-labelledby="schedule-week-heading">
      <div class="schedule-range-row">
        <div><span class="schedule-period">WEEKLY AGENDA</span><h2 id="schedule-week-heading">{{ dateLabel(monday) }} — {{ dateLabel(addScheduleDays(monday, 6)) }}<small>{{ monday.slice(0, 4) }}</small></h2><p class="muted">北京时间 · {{ loading ? '正在同步安排…' : error ? '本周日程尚未读取' : `共 ${data?.events.length ?? 0} 项安排 · ${pendingCount} 项待审批` }}</p></div>
        <div class="schedule-week-controls"><button class="button compact" aria-label="上一周" :disabled="monday <= firstWeek" @click="changeWeek(addScheduleDays(monday, -7))"><ChevronLeft :size="16" /></button><button class="button compact" @click="changeWeek(today)">本周</button><button class="button compact" aria-label="下一周" :disabled="monday >= lastWeek" @click="changeWeek(addScheduleDays(monday, 7))"><ChevronRight :size="16" /></button><label class="schedule-date-picker">选择日期<input type="date" :value="monday" min="2000-01-03" max="2099-12-31" @change="pickDate" /></label></div>
      </div>
      <nav class="schedule-source-filters" aria-label="日程类型筛选"><button :aria-pressed="source === 'ALL'" @click="filterBy('ALL')">全部<span>{{ loading || error ? '—' : data?.events.length ?? 0 }}</span></button><button v-for="key in sources" :key="key" :aria-pressed="source === key" :data-source="key" @click="filterBy(key)"><component :is="icons[key]" :size="15" />{{ scheduleSources[key] }}<span>{{ loading || error ? '—' : sourceCount(key) }}</span></button></nav>
      <nav class="schedule-days" aria-label="按日期查看日程"><button v-for="day in days" :key="day.day" :aria-pressed="selectedDay === day.day" :class="{ 'is-today': day.day === today }" @click="filterBy(source, selectedDay === day.day ? '' : day.day)"><span>{{ weekday(day.day) }}<small v-if="day.day === today">今天</small></span><strong>{{ day.day.slice(8) }}</strong><small>{{ loading || error ? '—' : `${day.events.length} 项` }}</small></button></nav>
    </section>
    <div v-if="loading" class="portal-panel schedule-loading" role="status"><CalendarDays :size="24" /><span>正在同步你的课程、考试、请假与预约…</span></div>
    <section v-else-if="error" class="portal-panel schedule-error" role="alert"><h2>暂时无法读取日程</h2><p>{{ error }}</p><button class="button compact" @click="load">重新读取</button></section>
    <template v-else-if="data">
      <p v-if="data.unavailableSources.length" class="content-context-note">当前账号尚无{{ unavailableLabels }}的查询权限，这些来源未汇总。</p>
      <div v-if="overlapCount" class="schedule-overlap-note" role="status"><TriangleAlert :size="17" /><span>本周 {{ overlapCount }} 项安排存在时间重叠。待审预约尚未确定，请回到原业务核对或调整。</span></div>
      <div class="schedule-agenda-heading"><h2>{{ selectedDay ? dateLabel(selectedDay) : '本周安排' }}<small>{{ visibleCount }} 项</small></h2><button v-if="selectedDay || source !== 'ALL'" class="text-button" @click="filterBy('ALL', '')">查看整周全部安排</button><span v-else class="muted">更新于 {{ data.generatedAt.slice(11, 16) }}</span></div>
      <section v-if="!visibleCount" class="portal-panel schedule-empty"><CalendarDays :size="28" /><h3>{{ selectedDay || source !== 'ALL' ? '当前筛选下没有日程' : data.availableSources.length ? '这一周暂无已汇总的安排' : '当前没有可读取的日程来源' }}</h3><p class="muted">{{ source !== 'ALL' || selectedDay ? '试试其他日期或查看整周全部安排。' : '新课程、已发布考试、已批准请假及有效预约会在刷新后同步。' }}</p></section>
      <section v-for="day in shownDays.filter(d => d.events.length)" :key="day.day" class="schedule-day-group" :aria-label="dateLabel(day.day) + '的安排'">
        <header class="schedule-day-label"><strong>{{ day.day.slice(8) }}</strong><div><h3>{{ weekday(day.day) }}</h3><span>{{ dateLabel(day.day) }}</span></div></header>
        <div class="schedule-event-list">
<article v-for="event in day.events" :key="event.key" class="schedule-event" :data-source="event.source" :class="{ tentative: event.tentative }">
          <div class="schedule-event-time"><strong>{{ event.startLabel }}</strong><span>{{ event.endLabel }}</span><small v-if="event.crossDay">跨日安排</small></div>
          <div class="schedule-event-body"><div class="schedule-event-kicker"><span><component :is="icons[event.source]" :size="14" />{{ scheduleSources[event.source] }}</span><span :class="{ 'is-pending': event.tentative }">{{ stateLabel(event) }}</span></div><h3>{{ event.title }}</h3><p class="schedule-event-location"><MapPin :size="13" />{{ event.location }}</p><p v-if="event.overlapKeys.length" class="schedule-event-overlap"><TriangleAlert :size="13" />{{ overlapText(event) }}</p></div>
          <RouterLink v-if="safeScheduleDestination(event)" :to="safeScheduleDestination(event)!" class="text-button schedule-event-link" :aria-label="`查看${scheduleSources[event.source]}：${event.title}`">查看详情<ArrowUpRight :size="14" /></RouterLink>
        </article>
</div>
      </section>
      <p class="schedule-footnote">日程同步原业务记录，不改变审批结果。跨日安排会分日显示；使用中的预约按计划时段展示，实际归还请到借用申请确认。</p>
    </template>
  </div>
</template>

<style scoped>
.personal-schedule { max-width: 1320px; margin: 0 auto; padding-bottom: 28px; }
.schedule-page-heading, .schedule-range-row, .schedule-heading-actions, .schedule-week-controls, .schedule-agenda-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.schedule-page-heading { align-items: flex-start; margin-bottom: 28px; flex-wrap: wrap; }
.schedule-page-heading h1 { margin: 8px 0 12px; }
.schedule-heading-actions { flex-wrap: wrap; padding-top: 8px; }
.schedule-overview { padding: 24px; }
.schedule-period { color: #60778e; font-size: 11px; letter-spacing: .15em; }
.schedule-range-row h2 { font-size: 23px; margin: 7px 0; }
.schedule-range-row h2 small { margin-left: 12px; color: #7a8997; font-family: inherit; font-size: 14px; font-weight: 400; }
.schedule-range-row p { font-size: 13px; margin: 0; }
.schedule-week-controls { justify-content: flex-end; flex-wrap: wrap; gap: 7px; }
.schedule-date-picker { display: flex; flex-direction: row; align-items: center; gap: 8px; font-size: 12px; font-weight: 400; color: #65788a; }
.schedule-date-picker input { width: 148px; min-height: 34px; padding: 6px 9px; font-size: 13px; }
.schedule-source-filters { display: flex; flex-wrap: wrap; gap: 8px; margin: 24px 0 18px; }
.schedule-source-filters button { display: flex; align-items: center; gap: 7px; padding: 7px 12px; border: 1px solid #e3e8ee; background: #fff; color: #51687d; border-radius: 6px; font-size: 13px; }
.schedule-source-filters button span { color: #6d7e8f; padding-left: 4px; font-size: 12px; }
.schedule-source-filters button[aria-pressed='true'] { background: #edf3fa; border-color: #b6c9df; color: #244b78; font-weight: 600; }
.schedule-days { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); border: 1px solid #e5eaf0; border-radius: 8px; overflow: hidden; }
.schedule-days button { display: flex; flex-direction: column; align-items: flex-start; gap: 7px; border: 0; border-right: 1px solid #e5eaf0; border-radius: 0; background: #fafbfd; padding: 13px 16px; text-align: left; color: #62768a; }
.schedule-days button:last-child { border-right: 0; }
.schedule-days button > span { display: flex; gap: 6px; align-items: center; font-size: 12px; }
.schedule-days strong { font-size: 23px; font-weight: 500; color: #243e58; font-variant-numeric: tabular-nums; }
.schedule-days small { font-size: 11px; font-weight: 400; }
.schedule-days .is-today strong { color: #244f82; }
.schedule-days .is-today > span small { color: #365c83; }
.schedule-days button[aria-pressed='true'] { background: #edf3fa; box-shadow: inset 0 -3px #315c89; }
.schedule-source-filters button:hover, .schedule-days button:hover { background: #f0f5fa; }
.schedule-overlap-note { display: flex; gap: 10px; align-items: center; border: 1px solid #e8d9b8; background: #fffcf5; color: #876427; padding: 13px 16px; border-radius: 8px; margin: 18px 0; font-size: 13px; }
.schedule-overlap-note svg { flex-shrink: 0; }
.schedule-agenda-heading { margin: 28px 0 18px; }
.schedule-agenda-heading h2 { font-size: 19px; margin: 0; }
.schedule-agenda-heading h2 small { margin-left: 12px; font-size: 12px; color: #6a7c8c; font-weight: 400; }
.schedule-agenda-heading > span { font-size: 12px; }
.schedule-day-group { display: grid; grid-template-columns: 108px minmax(0, 1fr); gap: 22px; margin-bottom: 22px; }
.schedule-day-label { display: flex; gap: 10px; padding-top: 18px; align-items: flex-start; }
.schedule-day-label > strong { font-size: 30px; color: #294b6a; font-weight: 400; line-height: 1; }
.schedule-day-label h3 { margin: 0 0 4px; font-size: 13px; }
.schedule-day-label span { font-size: 11px; color: #758495; white-space: nowrap; }
.schedule-event-list { display: grid; gap: 10px; }
.schedule-event { --event-accent: #315c89; display: grid; grid-template-columns: 86px minmax(0, 1fr) auto; gap: 20px; align-items: center; border: 1px solid #e2e8ee; border-left: 3px solid var(--event-accent); border-radius: 8px; background: #fff; padding: 19px 22px; }
.schedule-event[data-source='EXAM'] { --event-accent: #9c6d36; }
.schedule-event[data-source='LEAVE'] { --event-accent: #468077; }
.schedule-event[data-source='RESERVATION'] { --event-accent: #69749d; }
.schedule-event.tentative { border-left-style: dashed; }
.schedule-event-time { display: grid; gap: 5px; align-self: flex-start; font-variant-numeric: tabular-nums; }
.schedule-event-time strong { font-size: 18px; font-weight: 600; color: #263e56; }
.schedule-event-time span, .schedule-event-time small { color: #758494; font-size: 12px; }
.schedule-event-kicker { display: flex; gap: 12px; align-items: center; font-size: 11px; color: #718191; }
.schedule-event-kicker > span:first-child { display: inline-flex; align-items: center; gap: 5px; color: var(--event-accent); }
.schedule-event-kicker .is-pending { color: #946920; }
.schedule-event-body { min-width: 0; }
.schedule-event-body h3 { margin: 7px 0 8px; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.schedule-event-location, .schedule-event-overlap { display: flex; align-items: flex-start; gap: 5px; margin: 5px 0 0; font-size: 12px; color: #697e91; }
.schedule-event-location svg, .schedule-event-overlap svg { margin-top: 2px; flex-shrink: 0; }
.schedule-event-overlap { color: #946a2e; }
.schedule-event-link { white-space: nowrap; font-size: 12px; }
.schedule-empty, .schedule-loading { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 44px 20px; color: #657c91; text-align: center; }
.schedule-empty h3, .schedule-empty p { margin: 0; }
.schedule-footnote { color: #7a8995; font-size: 12px; line-height: 1.8; margin-top: 26px; }
.personal-schedule button:focus-visible, .personal-schedule a:focus-visible { outline: 2px solid #315c89; outline-offset: 3px; }
@media(max-width: 900px) { .schedule-range-row { align-items: flex-start; flex-direction: column; } .schedule-week-controls { justify-content: flex-start; } .schedule-day-group { grid-template-columns: 88px minmax(0, 1fr); gap: 12px; } .schedule-days button { padding: 12px 10px; } }
@media(max-width: 600px) { .schedule-page-heading { margin-bottom: 20px; gap: 8px; } .schedule-heading-actions { padding-top: 0; width: 100%; } .schedule-overview { padding: 18px 14px; } .schedule-range-row h2 { font-size: 19px; } .schedule-days { overflow-x: auto; } .schedule-days button { padding: 10px 6px; align-items: center; min-width: 0; } .schedule-days button > span { font-size: 11px; flex-direction: column; gap: 0; } .schedule-days strong { font-size: 20px; } .schedule-days small { font-size: 10px; } .schedule-source-filters { gap: 5px; } .schedule-source-filters button { padding: 7px 8px; font-size: 12px; } .schedule-source-filters button svg { display: none; } .schedule-day-group { display: block; } .schedule-day-label { padding: 0; margin: 20px 0 12px; align-items: center; } .schedule-day-label > strong { font-size: 24px; } .schedule-day-label > div { display: flex; align-items: center; gap: 8px; } .schedule-day-label h3 { margin: 0; } .schedule-event { grid-template-columns: 64px minmax(0, 1fr); padding: 16px 13px; gap: 10px; } .schedule-event-link { grid-column: 2; justify-self: start; } .schedule-event-time strong { font-size: 16px; } .schedule-event-body h3 { font-size: 15px; } .schedule-agenda-heading { flex-wrap: wrap; gap: 8px; } }
</style>
