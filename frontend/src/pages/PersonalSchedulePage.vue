<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  ArrowUpRight,
  BookOpen,
  CalendarDays,
  CalendarCheck,
  ChevronLeft,
  ChevronRight,
  ClipboardList,
  MapPin,
  RefreshCw,
  TriangleAlert,
} from '@lucide/vue';
import { api, errorMessage } from '../lib/api';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import SkeletonBlock from '../components/SkeletonBlock.vue';
import {
  addScheduleDays,
  safeScheduleDestination,
  scheduleSources,
  shanghaiDay,
  splitScheduleDays,
  weekOf,
} from '../lib/personalSchedule';
import type { PersonalSchedule, ScheduleEvent, ScheduleSource } from '../lib/personalSchedule';

const route = useRoute(),
  router = useRouter();
const today = shanghaiDay();
const firstWeek = '2000-01-03',
  lastWeek = weekOf('2099-12-31');
const icons = { LESSON: BookOpen, EXAM: ClipboardList, LEAVE: CalendarDays, RESERVATION: CalendarCheck };
const sources = Object.keys(scheduleSources) as ScheduleSource[];
const data = ref<PersonalSchedule | null>(null),
  loading = ref(true),
  error = ref('');
let sequence = 0;
const range = computed(() => {
  try {
    const raw = typeof route.query.week === 'string' ? route.query.week : today;
    const monday = weekOf(raw);
    if (monday < firstWeek || monday > lastWeek) throw new Error('日期超出范围');
    return { monday, invalid: false };
  } catch {
    return { monday: weekOf(today), invalid: true };
  }
});
const monday = computed(() => range.value.monday);
const source = computed(() =>
  sources.includes(route.query.source as ScheduleSource) ? (route.query.source as ScheduleSource) : 'ALL',
);
const selectedDay = computed(() =>
  typeof route.query.day === 'string' &&
  Array.from({ length: 7 }, (_, n) => addScheduleDays(monday.value, n)).includes(route.query.day)
    ? route.query.day
    : '',
);
const filtered = computed(
  () => data.value?.events?.filter(e => source.value === 'ALL' || e.source === source.value) ?? [],
);
const days = computed(() => splitScheduleDays(filtered.value, monday.value));
const shownDays = computed(() =>
  days.value.filter(d => (selectedDay.value ? d.day === selectedDay.value : d.events.length > 0)),
);
const visibleCount = computed(() =>
  selectedDay.value ? new Set(shownDays.value.flatMap(d => d.events.map(e => e.key))).size : filtered.value.length,
);
const overlapCount = computed(() => data.value?.events?.filter(e => (e.overlapKeys?.length ?? 0) > 0).length ?? 0);
const pendingCount = computed(() => data.value?.events?.filter(e => e.tentative).length ?? 0);
const unavailableLabels = computed(
  () => data.value?.unavailableSources?.map(key => scheduleSources[key]).join('、') ?? '',
);
const weekday = (day: string) =>
  new Intl.DateTimeFormat('zh-CN', { weekday: 'short', timeZone: 'UTC' }).format(new Date(day + 'T12:00:00Z'));
const dateLabel = (day: string) =>
  new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', timeZone: 'UTC' }).format(
    new Date(day + 'T12:00:00Z'),
  );
const sourceCount = (key: ScheduleSource) => data.value?.events?.filter(e => e.source === key).length ?? 0;
function stateLabel(event: ScheduleEvent) {
  if (event.source === 'LESSON') return '课程安排';
  if (event.source === 'EXAM') return '已发布';
  if (event.source === 'LEAVE')
    return event.status === 'COMPLETED' ? '已销假' : event.status === 'RETURN_PENDING' ? '待确认销假' : '已批准';
  return (
    ({ SUBMITTED: '待审批', APPROVED: '已批准', IN_USE: '使用中', COMPLETED: '已核销' } as Record<string, string>)[
      event.status
    ] ?? '预约安排'
  );
}
function overlapText(event: ScheduleEvent) {
  const matches = data.value?.events?.filter(e => event.overlapKeys?.includes(e.key)) ?? [];
  return `与${[...new Set(matches.map(e => scheduleSources[e.source]))].join('、')}时间重叠${event.tentative || matches.some(e => e.tentative) ? '（含待审预约）' : ''}`;
}
async function changeWeek(day: string) {
  await router.push({
    path: '/portal/schedule',
    query: { week: weekOf(day), ...(source.value !== 'ALL' ? { source: source.value } : {}) },
  });
}
async function filterBy(key: string, day = selectedDay.value) {
  await router.replace({
    query: { week: monday.value, ...(key !== 'ALL' ? { source: key } : {}), ...(day ? { day } : {}) },
  });
}
async function load() {
  const request = ++sequence;
  loading.value = true;
  error.value = '';
  data.value = null;
  try {
    const result = await api<PersonalSchedule>(
      `/schedule/mine?${new URLSearchParams({ from: monday.value, to: addScheduleDays(monday.value, 7) })}`,
    );
    if (request === sequence) data.value = result;
  } catch (cause) {
    if (request === sequence) error.value = errorMessage(cause);
  } finally {
    if (request === sequence) loading.value = false;
  }
}
function pickDate(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  if (value) void changeWeek(value);
}
watch(monday, load, { immediate: true });
onBeforeUnmount(() => {
  sequence++;
});
</script>

<template>
  <div class="personal-schedule service-workflow" :aria-busy="loading">
    <ServicePageHeader
      context="学生服务中心 · 时间安排"
      title="我的日程"
      description="把学习与校园事务放在同一周，提前看见时间上的交集。"
    >
      <template #actions>
        <RouterLink to="/portal/learning/timetable" class="text-button">
          每周课程表
          <ArrowUpRight :size="14" />
        </RouterLink>
        <button class="button compact" :disabled="loading" @click="load">
          <RefreshCw :size="14" />
          刷新日程
        </button>
      </template>
    </ServicePageHeader>
    <p v-if="range.invalid" class="content-context-note">日期参数无效，已显示本周。可重新选择日期。</p>
    <section class="portal-panel schedule-overview" aria-labelledby="schedule-week-heading">
      <div class="schedule-range-row">
        <div>
          <span class="schedule-period">WEEKLY AGENDA</span>
          <h2 id="schedule-week-heading">
            {{ dateLabel(monday) }} — {{ dateLabel(addScheduleDays(monday, 6)) }}
            <small>{{ monday.slice(0, 4) }}</small>
          </h2>
          <p class="muted">
            北京时间 ·
            {{
              loading
                ? '正在同步安排…'
                : error
                  ? '本周日程尚未读取'
                  : `共 ${data?.events?.length ?? 0} 项安排 · ${pendingCount} 项待审批`
            }}
          </p>
        </div>
        <div class="schedule-week-controls">
          <button
            class="button compact"
            aria-label="上一周"
            :disabled="monday <= firstWeek"
            @click="changeWeek(addScheduleDays(monday, -7))"
          >
            <ChevronLeft :size="16" />
          </button>
          <button class="button compact" @click="changeWeek(today)">本周</button>
          <button
            class="button compact"
            aria-label="下一周"
            :disabled="monday >= lastWeek"
            @click="changeWeek(addScheduleDays(monday, 7))"
          >
            <ChevronRight :size="16" />
          </button>
          <label class="schedule-date-picker">
            选择日期
            <input type="date" :value="monday" min="2000-01-03" max="2099-12-31" @change="pickDate" />
          </label>
        </div>
      </div>
      <nav class="schedule-source-filters" aria-label="日程类型筛选">
        <button :aria-pressed="source === 'ALL'" @click="filterBy('ALL')">
          全部
          <span>{{ loading || error ? '—' : (data?.events?.length ?? 0) }}</span>
        </button>
        <button
          v-for="key in sources"
          :key="key"
          :aria-pressed="source === key"
          :data-source="key"
          @click="filterBy(key)"
        >
          <component :is="icons[key]" :size="15" />
          {{ scheduleSources[key] }}
          <span>{{ loading || error ? '—' : sourceCount(key) }}</span>
        </button>
      </nav>
      <nav class="schedule-days" aria-label="按日期查看日程">
        <button
          v-for="day in days"
          :key="day.day"
          :aria-pressed="selectedDay === day.day"
          :class="{ 'is-today': day.day === today }"
          @click="filterBy(source, selectedDay === day.day ? '' : day.day)"
        >
          <span>
            {{ weekday(day.day) }}
            <small v-if="day.day === today">今天</small>
          </span>
          <strong>{{ day.day.slice(8) }}</strong>
          <small>{{ loading || error ? '—' : `${day.events.length} 项` }}</small>
        </button>
      </nav>
    </section>
    <SkeletonBlock v-if="loading" variant="schedule" :rows="4" label="正在同步你的课程、考试、请假与预约…" />
    <section v-else-if="error" class="portal-panel schedule-error" role="alert">
      <h2>暂时无法读取日程</h2>
      <p>{{ error }}</p>
      <button class="button compact" @click="load">重新读取</button>
    </section>
    <template v-else-if="data">
      <p v-if="data.unavailableSources?.length" class="content-context-note">
        当前账号尚无{{ unavailableLabels }}的查询权限，这些来源未汇总。
      </p>
      <div v-if="overlapCount" class="schedule-overlap-note" role="status">
        <TriangleAlert :size="17" />
        <span>本周 {{ overlapCount }} 项安排存在时间重叠。待审预约尚未确定，请回到原业务核对或调整。</span>
      </div>
      <div class="schedule-agenda-heading">
        <h2>
          {{ selectedDay ? dateLabel(selectedDay) : '本周安排' }}
          <small>{{ visibleCount }} 项</small>
        </h2>
        <button v-if="selectedDay || source !== 'ALL'" class="text-button" @click="filterBy('ALL', '')">
          查看整周全部安排
        </button>
        <span v-else class="muted">更新于 {{ data.generatedAt?.slice(11, 16) ?? '' }}</span>
      </div>
      <section v-if="!visibleCount" class="portal-panel schedule-empty">
        <CalendarDays :size="28" />
        <h3>
          {{
            selectedDay || source !== 'ALL'
              ? '当前筛选下没有日程'
              : data.availableSources?.length
                ? '这一周暂无已汇总的安排'
                : '当前没有可读取的日程来源'
          }}
        </h3>
        <p class="muted">
          {{
            source !== 'ALL' || selectedDay
              ? '试试其他日期或查看整周全部安排。'
              : '新课程、已发布考试、已批准请假及有效预约会在刷新后同步。'
          }}
        </p>
      </section>
      <section
        v-for="day in shownDays.filter(d => d.events.length)"
        :key="day.day"
        class="schedule-day-group"
        :aria-label="dateLabel(day.day) + '的安排'"
      >
        <header class="schedule-day-label">
          <strong>{{ day.day.slice(8) }}</strong>
          <div>
            <h3>{{ weekday(day.day) }}</h3>
            <span>{{ dateLabel(day.day) }}</span>
          </div>
        </header>
        <div class="schedule-event-list">
          <article
            v-for="event in day.events"
            :key="event.key"
            class="schedule-event"
            :data-source="event.source"
            :class="{ tentative: event.tentative }"
          >
            <div class="schedule-event-time">
              <strong>{{ event.startLabel }}</strong>
              <span>{{ event.endLabel }}</span>
              <small v-if="event.crossDay">跨日安排</small>
            </div>
            <div class="schedule-event-body">
              <div class="schedule-event-kicker">
                <span>
                  <component :is="icons[event.source]" :size="14" />
                  {{ scheduleSources[event.source] }}
                </span>
                <span :class="{ 'is-pending': event.tentative }">{{ stateLabel(event) }}</span>
              </div>
              <h3>{{ event.title }}</h3>
              <p class="schedule-event-location">
                <MapPin :size="13" />
                {{ event.location }}
              </p>
              <p v-if="event.overlapKeys?.length" class="schedule-event-overlap">
                <TriangleAlert :size="13" />
                {{ overlapText(event) }}
              </p>
            </div>
            <RouterLink
              v-if="safeScheduleDestination(event)"
              :to="safeScheduleDestination(event)!"
              class="text-button schedule-event-link"
              :aria-label="`查看${scheduleSources[event.source]}：${event.title}`"
            >
              查看详情
              <ArrowUpRight :size="14" />
            </RouterLink>
          </article>
        </div>
      </section>
      <p class="schedule-footnote">
        日程同步原业务记录，不改变审批结果。跨日安排会分日显示；使用中的预约按计划时段展示，实际归还请到借用申请确认。
      </p>
    </template>
  </div>
</template>

<style scoped>
.personal-schedule {
  max-width: 1320px;
  margin: 0 auto;
  padding-bottom: 28px;
}
.schedule-range-row,
.schedule-week-controls,
.schedule-agenda-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.schedule-overview {
  padding: 24px;
}
.schedule-period {
  color: var(--color-text-tertiary);
  font-size: var(--text-meta);
  letter-spacing: 0.15em;
}
.schedule-range-row h2 {
  font-size: 23px;
  margin: 7px 0;
}
.schedule-range-row h2 small {
  margin-left: 12px;
  color: var(--color-text-tertiary);
  font-family: inherit;
  font-size: var(--text-body);
  font-weight: 400;
}
.schedule-range-row p {
  font-size: var(--text-supporting);
  margin: 0;
}
.schedule-week-controls {
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 7px;
}
.schedule-date-picker {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  font-size: var(--text-supporting);
  font-weight: 400;
  color: var(--color-text-secondary);
}
.schedule-date-picker input {
  width: 148px;
  min-height: 34px;
  padding: 6px 9px;
  font-size: var(--text-supporting);
}
.schedule-source-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 24px 0 18px;
}
.schedule-source-filters button {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 12px;
  border: 1px solid var(--color-border-subtle);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  border-radius: var(--radius-md);
  font-size: var(--text-supporting);
}
.schedule-source-filters button span {
  color: var(--color-text-tertiary);
  padding-left: 4px;
  font-size: var(--text-supporting);
}
.schedule-source-filters button[aria-pressed='true'] {
  background: var(--color-surface-soft);
  border-color: var(--color-border-strong);
  color: var(--color-brand);
  font-weight: 600;
}
.schedule-days {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.schedule-days button {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 7px;
  border: 0;
  border-right: 1px solid var(--color-border-subtle);
  border-radius: 0;
  background: var(--color-surface);
  padding: 13px 16px;
  text-align: left;
  color: var(--color-text-secondary);
}
.schedule-days button:last-child {
  border-right: 0;
}
.schedule-days button > span {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: var(--text-supporting);
}
.schedule-days strong {
  font-size: 23px;
  font-weight: 500;
  color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}
.schedule-days small {
  font-size: var(--text-meta);
  font-weight: 400;
}
.schedule-days .is-today strong {
  color: var(--color-brand);
}
.schedule-days .is-today > span small {
  color: var(--color-heading);
}
.schedule-days button[aria-pressed='true'] {
  background: var(--color-surface-active);
  box-shadow: inset 0 -3px var(--color-brand);
}
.schedule-source-filters button:hover,
.schedule-days button:hover {
  background: var(--color-surface-hover);
}
.schedule-overlap-note {
  display: flex;
  gap: 10px;
  align-items: center;
  border: 1px solid var(--color-warning-border);
  background: var(--color-warning-soft);
  color: var(--color-warning);
  padding: 13px 16px;
  border-radius: var(--radius-lg);
  margin: 18px 0;
  font-size: var(--text-supporting);
}
.schedule-overlap-note svg {
  flex-shrink: 0;
}
.schedule-agenda-heading {
  margin: 28px 0 18px;
}
.schedule-agenda-heading h2 {
  font-size: 19px;
  margin: 0;
}
.schedule-agenda-heading h2 small {
  margin-left: 12px;
  font-size: var(--text-meta);
  color: var(--color-text-secondary);
  font-weight: 400;
}
.schedule-agenda-heading > span {
  font-size: var(--text-supporting);
}
.schedule-day-group {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr);
  gap: 22px;
  margin-bottom: 22px;
}
.schedule-day-label {
  display: flex;
  gap: 10px;
  padding-top: 18px;
  align-items: flex-start;
}
.schedule-day-label > strong {
  font-size: 30px;
  color: var(--color-heading);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.schedule-day-label h3 {
  margin: 0 0 4px;
  font-size: var(--text-supporting);
}
.schedule-day-label span {
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
  white-space: nowrap;
}
.schedule-event-list {
  display: grid;
  gap: 10px;
}
.schedule-event {
  --event-accent: var(--color-brand);
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr) auto;
  gap: 20px;
  align-items: center;
  border: 1px solid var(--color-border);
  border-top: 2px solid var(--event-accent);
  border-radius: var(--radius-xs);
  background: var(--color-white);
  padding: 19px 22px;
  transition:
    border-color 0.16s ease,
    box-shadow 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}
.schedule-event:hover {
  border-color: var(--color-border-hover);
  box-shadow: 0 4px 12px rgb(41 62 52 / 6%);
  transform: translateY(-1px);
}
.schedule-event[data-source='EXAM'] {
  --event-accent: var(--color-warning);
}
.schedule-event[data-source='LEAVE'] {
  --event-accent: rgb(70 128 119);
}
.schedule-event[data-source='RESERVATION'] {
  --event-accent: var(--color-gold);
}
.schedule-event.tentative {
  border-style: dashed;
}
.schedule-event-time {
  display: grid;
  gap: 5px;
  align-self: flex-start;
  font-variant-numeric: tabular-nums;
}
.schedule-event-time strong {
  font-size: var(--text-title-sm);
  font-weight: 600;
  color: var(--color-ink);
}
.schedule-event-time span,
.schedule-event-time small {
  color: var(--color-text-tertiary);
  font-size: var(--text-meta);
}
.schedule-event-kicker {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: var(--text-meta);
  color: var(--color-text-tertiary);
}
.schedule-event-kicker > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--event-accent);
}
.schedule-event-kicker .is-pending {
  color: var(--color-warning);
}
.schedule-event-body {
  min-width: 0;
}
.schedule-event-body h3 {
  margin: 7px 0 8px;
  font-size: 16px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.schedule-event-location,
.schedule-event-overlap {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: 5px 0 0;
  font-size: var(--text-supporting);
  color: var(--color-text-secondary);
}
.schedule-event-location svg,
.schedule-event-overlap svg {
  margin-top: 2px;
  flex-shrink: 0;
}
.schedule-event-overlap {
  color: var(--color-warning);
}
.schedule-event-link {
  white-space: nowrap;
  font-size: var(--text-supporting);
}
.schedule-empty,
.schedule-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 44px 20px;
  color: var(--color-text-secondary);
  text-align: center;
}
.schedule-empty h3,
.schedule-empty p {
  margin: 0;
}
.schedule-footnote {
  color: var(--color-text-tertiary);
  font-size: var(--text-meta);
  line-height: 1.8;
  margin-top: 26px;
}
.personal-schedule button:focus-visible,
.personal-schedule a:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 3px;
}

@media (max-width: 900px) {
  .schedule-range-row {
    align-items: flex-start;
    flex-direction: column;
  }
  .schedule-week-controls {
    justify-content: flex-start;
  }
  .schedule-day-group {
    grid-template-columns: 88px minmax(0, 1fr);
    gap: 12px;
  }
  .schedule-days button {
    padding: 12px 10px;
  }
}

@media (max-width: 600px) {
  .schedule-overview {
    padding: 18px 14px;
  }
  .schedule-range-row h2 {
    font-size: 19px;
  }
  .schedule-week-controls {
    width: 100%;
    flex-wrap: wrap;
  }
  .schedule-date-picker {
    flex: 1 0 100%;
    width: 100%;
    white-space: nowrap;
  }
  .schedule-date-picker input {
    flex: 1;
    width: auto;
    min-width: 0;
  }
  .schedule-days {
    grid-template-columns: repeat(7, minmax(56px, 1fr));
    gap: 6px;
    overflow-x: auto;
    padding-bottom: 4px;
  }
  .schedule-days button {
    padding: 10px 6px;
    align-items: center;
    min-width: 56px;
  }
  .schedule-days button > span {
    font-size: var(--text-meta);
    flex-direction: column;
    gap: 0;
  }
  .schedule-days strong {
    font-size: 20px;
  }
  .schedule-days small {
    font-size: var(--text-meta);
  }
  .schedule-source-filters {
    gap: 5px;
  }
  .schedule-source-filters button {
    padding: 7px 8px;
    font-size: var(--text-supporting);
  }
  .schedule-source-filters button svg {
    display: none;
  }
  .schedule-day-group {
    display: block;
  }
  .schedule-day-label {
    padding: 0;
    margin: 20px 0 12px;
    align-items: center;
  }
  .schedule-day-label > strong {
    font-size: 24px;
  }
  .schedule-day-label > div {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .schedule-day-label h3 {
    margin: 0;
  }
  .schedule-event {
    grid-template-columns: 64px minmax(0, 1fr);
    padding: 16px 13px;
    gap: 10px;
  }
  .schedule-event-link {
    grid-column: 2;
    justify-self: start;
  }
  .schedule-event-time strong {
    font-size: 16px;
  }
  .schedule-event-body h3 {
    font-size: var(--text-reading);
  }
  .schedule-agenda-heading {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .schedule-event {
    transition-duration: 0.01ms;
  }
  .schedule-event:hover {
    transform: none;
  }
}
</style>
