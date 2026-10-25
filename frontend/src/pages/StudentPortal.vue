<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowUpRight,
  BedDouble,
  Bell,
  BookOpen,
  CalendarCheck,
  CalendarDays,
  Check,
  ClipboardList,
  Compass,
  FileCheck2,
  GraduationCap,
  MapPin,
  Search,
  Wrench,
  X,
} from '@lucide/vue'
import type { Component } from 'vue'
import ServiceEntry from '../components/ServiceEntry.vue'
import { api, errorMessage } from '../lib/api'
import type { Overview, Notice } from '../types'
import { safeAffairDestination } from '../lib/navigation'
import { useToast } from '../composables/useToast'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import { schoolMedia } from '../content/schoolMedia'

interface PortalServiceItem {
  to: string
  title: string
  description: string
  icon: Component
  categoryKey: string
  category: string
  badge: string
  badgeType: 'primary' | 'warning' | 'success' | 'neutral' | 'danger'
  theme: string
}

const route = useRoute()
const toast = useToast()
const overview = ref<Overview | null>(null)
const loading = ref(true)
const error = ref('')
const reading = ref<number | null>(null)
const activeCategory = ref('ALL')
const serviceSearch = ref('')
const categoryRef = ref<HTMLElement | null>(null)
const { indicatorStyle: categoryIndicatorStyle } = useSlidingIndicator(
  categoryRef,
  () => activeCategory.value,
  { activeSelector: '.service-segment-btn.active' }
)

const view = computed(() => route.path.endsWith('timetable') ? 'timetable' : route.path.endsWith('notices') ? 'notices' : 'home')
const date = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())

const categories = [
  { id: 'ALL', label: '全部服务' },
  { id: 'ACADEMIC', label: '学业教学' },
  { id: 'FACILITY', label: '生活设施' },
  { id: 'AFFAIR', label: '办事消息' },
  { id: 'CAMPUS', label: '校园导览' },
]

const services = computed<PortalServiceItem[]>(() => [
  { to: '/portal/schedule', title: '我的校园日程', description: '汇总课程、考试、请假与预约，核对一周安排和时间重叠', icon: CalendarDays, categoryKey: 'ACADEMIC', category: '学业教学', badge: '统一日程', badgeType: 'primary', theme: 'blue' },
  {
    to: '/portal/reservations',
    title: '公共场地与设备借用',
    description: '借用多功能报告厅、研讨室、体育场馆与音响设备，在线防冲突',
    icon: CalendarCheck,
    categoryKey: 'FACILITY',
    category: '生活设施',
    badge: '在线预约',
    badgeType: 'primary' as const,
    theme: 'sky',
  },
  {
    to: '/housing/mine',
    title: '我的住宿与调宿申请',
    description: '查看床位档案与楼栋宿管，在线申请调宿并跟进办理进度',
    icon: BedDouble,
    categoryKey: 'FACILITY',
    category: '生活设施',
    badge: '床位·调宿',
    badgeType: 'primary' as const,
    theme: 'teal',
  },
  {
    to: '/repairs/mine',
    title: '校园设施报修与进度',
    description: '报告设施故障，跟进维修并确认处理结果',
    icon: Wrench,
    categoryKey: 'FACILITY',
    category: '生活设施',
    badge: '进度追踪',
    badgeType: 'warning' as const,
    theme: 'amber',
  },
  {
    to: '/portal/learning',
    title: '课程与学习资料',
    description: '查看本学期课程、教师资料和班级通知',
    icon: BookOpen,
    categoryKey: 'ACADEMIC',
    category: '学业教学',
    badge: '课程资料',
    badgeType: 'neutral' as const,
    theme: 'blue',
  },
  {
    to: '/portal/learning/timetable',
    title: '每周课程表',
    description: '按周查看上课时间、教室和任课教师',
    icon: CalendarDays,
    categoryKey: 'ACADEMIC',
    category: '学业教学',
    badge: '周历视图',
    badgeType: 'primary' as const,
    theme: 'purple',
  },
  {
    to: '/portal/learning/grades',
    title: '课程成绩查询',
    description: '查询本人已发布的课程成绩与学分',
    icon: GraduationCap,
    categoryKey: 'ACADEMIC',
    category: '学业教学',
    badge: '学分绩点',
    badgeType: 'neutral' as const,
    theme: 'indigo',
  },
  {
    to: '/portal/learning/exams',
    title: '考试时间与地点',
    description: '查看本人考试日期、时间和考场',
    icon: ClipboardList,
    categoryKey: 'ACADEMIC',
    category: '学业教学',
    badge: '考试日程',
    badgeType: 'danger' as const,
    theme: 'rose',
  },
  {
    to: '/portal/notices',
    title: '个人消息通知',
    description: '阅读发给你的教学通知与个人消息',
    icon: Bell,
    categoryKey: 'AFFAIR',
    category: '办事消息',
    badge: overview.value?.unreadCount ? `${overview.value.unreadCount} 条未读` : '全部已读',
    badgeType: overview.value?.unreadCount ? 'danger' : 'neutral',
    theme: 'coral',
  },
  {
    to: '/portal/affairs',
    title: '我的办事与办理进度',
    description: '办理请假、证明、报修、调宿与场地借用，统一查看处理进度',
    icon: ClipboardList,
    categoryKey: 'AFFAIR',
    category: '办事消息',
    badge: '一网通办',
    badgeType: 'primary' as const,
    theme: 'slate',
  },
  {
    to: '/portal/certificates',
    title: '在校证明申请与下载',
    description: '核对本人登记信息，申请并下载受控演示证明',
    icon: FileCheck2,
    categoryKey: 'AFFAIR',
    category: '办事消息',
    badge: '电子证明',
    badgeType: 'neutral' as const,
    theme: 'indigo',
  },
  {
    to: '/portal/leave?tab=calendar',
    title: '我的请假日程',
    description: '按月查看已通过的请假时间，核对来源申请',
    icon: CalendarDays,
    categoryKey: 'AFFAIR',
    category: '办事消息',
    badge: '请假台账',
    badgeType: 'neutral' as const,
    theme: 'teal',
  },
  {
    to: '/campus/explore',
    title: '三维校园导览',
    description: '探索校园建筑，查找校内地点与可借用场地',
    icon: MapPin,
    categoryKey: 'CAMPUS',
    category: '校园探索',
    badge: '3D 导览',
    badgeType: 'success' as const,
    theme: 'emerald',
  },
])

const filteredServices = computed(() => {
  const q = serviceSearch.value.trim().toLowerCase()
  return services.value.filter(s => {
    const matchCat = activeCategory.value === 'ALL' || s.categoryKey === activeCategory.value
    const matchSearch = !q || s.title.toLowerCase().includes(q) || s.description.toLowerCase().includes(q) || s.badge.toLowerCase().includes(q) || s.category.toLowerCase().includes(q)
    return matchCat && matchSearch
  })
})

function categoryCount(catId: string) {
  if (catId === 'ALL') return services.value.length
  return services.value.filter(s => s.categoryKey === catId).length
}

function clearFilters() {
  serviceSearch.value = ''
  activeCategory.value = 'ALL'
}

function lessonDate(value: string) { return value.slice(5, 10).replace('-', '月') + '日' }

async function load() {
  loading.value = true
  error.value = ''
  try { overview.value = await api<Overview>('/portal/overview') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}

async function markRead(notice: Notice) {
  reading.value = notice.id
  error.value = ''
  try {
    await api('/notices/' + notice.id + '/read', { method: 'POST' })
    notice.read = true
    if (overview.value) overview.value.unreadCount = Math.max(0, overview.value.unreadCount - 1)
    toast.success('已将该消息标记为已读')
  } catch (cause) { error.value = errorMessage(cause) }
  finally { reading.value = null }
}

onMounted(load)
</script>

<template>
  <div class="student-portal">
    <!-- Skeleton Loading Block -->
    <div v-if="loading" class="portal-skeleton" role="status" aria-label="正在读取你的校园信息…">
      <div class="skeleton-box" style="height: 80px; margin-bottom: 25px; border-radius: 12px;"></div>
      <div class="service-entry-grid" style="margin-bottom: 25px;">
        <div v-for="n in 6" :key="n" class="skeleton-box" style="height: 126px; border-radius: 14px;"></div>
      </div>
      <div class="portal-content-grid">
        <div class="skeleton-box" style="height: 280px; border-radius: 12px;"></div>
        <div class="skeleton-box" style="height: 280px; border-radius: 12px;"></div>
      </div>
    </div>
    <div v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" @click="load">重新加载</button></div>
    <template v-if="overview && !loading">
      <section v-if="view === 'home'" class="student-welcome portal-welcome-feature">
        <div class="portal-welcome-copy">
          <span class="portal-context">我的校园 <span>{{ date }}</span></span>
          <h1>{{ overview.profile.fullName }}，你好。</h1>
          <p>{{ overview.profile.departmentName }} · {{ overview.profile.className }}</p>
          <div class="portal-welcome-actions">
            <RouterLink to="/portal/schedule" class="button primary compact"><CalendarDays :size="15" />我的日程</RouterLink>
            <RouterLink to="/portal/affairs" class="text-button">查看我的办事 <ArrowUpRight :size="14" /></RouterLink>
          </div>
        </div>
        <RouterLink to="/campus/explore" class="portal-campus-feature" aria-label="探索校园">
          <img :src="schoolMedia.architecture.url" :alt="schoolMedia.architecture.alt" width="800" height="518" />
          <span><small>校园一隅</small>在熟悉的校园，开启新的一天 <ArrowUpRight :size="16" /></span>
        </RouterLink>
      </section>
      <div class="portal-content-grid" :class="{ single: view !== 'home' }">
        <section v-if="view !== 'notices'" class="portal-panel">
          <div class="section-heading"><div><span class="eyebrow">课程安排</span><h2>{{ view === 'home' ? '近期课程' : '每周课程表' }}</h2></div><RouterLink v-if="view === 'home'" to="/portal/learning/timetable">查看完整课表 <ArrowUpRight :size="14" /></RouterLink></div>
          <p v-if="!overview.timetable.length" class="empty-state">当前没有已导入的课程安排。</p>
          <article v-for="lesson in view === 'home' ? overview.timetable.slice(0,3) : overview.timetable" :key="lesson.id" class="lesson-row">
            <div class="lesson-time"><strong>{{ lessonDate(lesson.startsAt) }}</strong><span>{{ lesson.startsAt.slice(11,16) }}—{{ lesson.endsAt.slice(11,16) }}</span></div>
            <div><h3>{{ lesson.courseName }}</h3><p>{{ lesson.teacherName }} · {{ lesson.classroom }}</p></div>
            <span class="lesson-campus">{{ lesson.campusName }}</span>
          </article>
          <p class="data-note">课表为当前账号的演示安排，不代表学校实际教学计划。</p>
        </section>
        <section v-if="view !== 'timetable'" class="portal-panel">
          <div class="section-heading"><div><span class="eyebrow">消息通知 · 未读 {{ overview.unreadCount }} 条</span><h2>{{ view === 'home' ? '最新个人消息' : '个人消息通知' }}</h2></div><RouterLink v-if="view === 'home'" to="/portal/notices">查看全部消息 <ArrowUpRight :size="14" /></RouterLink></div>
          <p v-if="!overview.notices.length" class="empty-state">暂时没有个人消息。</p>
          <article v-for="notice in overview.notices" :key="notice.id" class="notice-row">
            <div class="notice-heading"><h3>{{ notice.title }}</h3><span class="status-label" :class="{ read: notice.read }">{{ notice.read ? '已读' : '未读' }}</span></div>
            <p>{{ notice.body }}</p>
            <RouterLink v-if="safeAffairDestination(notice.targetPath)" class="text-button" :to="notice.targetPath!">查看对应申请 <ArrowUpRight :size="14" /></RouterLink>
            <button v-if="!notice.read" class="text-button" :disabled="reading === notice.id" @click="markRead(notice)"><Check :size="14" />{{ reading === notice.id ? '正在更新…' : '标为已读' }}</button>
          </article>
        </section>
      </div>
      <section v-if="view === 'home'" class="portal-services" aria-labelledby="student-services-heading">
        <div class="service-hub-header">
          <div class="service-section-heading">
            <div class="service-title-wrap">
              <div class="service-title-row">
                <h2 id="student-services-heading">校园服务目录</h2>
                <span class="service-count-pill">共 {{ services.length }} 项服务</span>
              </div>
              <p>按分类查找学习、生活与办事服务</p>
            </div>
          </div>
          <div class="service-toolbar">
            <div ref="categoryRef" class="service-segmented-control" role="tablist" aria-label="服务分类筛选">
              <div class="service-segment-pill" :style="categoryIndicatorStyle" aria-hidden="true"></div>
              <button
                v-for="cat in categories"
                :key="cat.id"
                type="button"
                role="tab"
                class="service-segment-btn"
                :class="{ active: activeCategory === cat.id }"
                :aria-selected="activeCategory === cat.id"
                @click="activeCategory = cat.id"
              >
                {{ cat.label }} ({{ categoryCount(cat.id) }})
              </button>
            </div>
            <div class="service-search-box">
              <Search :size="15" aria-hidden="true" />
              <input
                v-model="serviceSearch"
                type="search"
                placeholder="快速检索服务（如：课表、请假、成绩…）"
                maxlength="30"
                aria-label="检索学生服务"
              />
              <button
                v-if="serviceSearch"
                type="button"
                class="service-search-clear"
                aria-label="清空搜索"
                @click="serviceSearch = ''"
              >
                <X :size="12" />
              </button>
            </div>
          </div>
        </div>

        <div v-if="filteredServices.length" class="service-entry-grid">
          <ServiceEntry
            v-for="service in filteredServices"
            :key="service.to"
            v-bind="service"
          />
        </div>
        <div v-else class="service-empty-state">
          <Compass :size="32" />
          <p>未找到与“{{ serviceSearch }}”相关的学生服务</p>
          <button type="button" class="button compact" @click="clearFilters">清空检索条件</button>
        </div>
      </section>
    </template>
  </div>
</template>
