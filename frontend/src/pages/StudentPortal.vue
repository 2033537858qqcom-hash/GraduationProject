<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowUpRight, Bell, CalendarDays, MapPin, BookOpen, Check, FileCheck2, ClipboardList, GraduationCap, Wrench } from '@lucide/vue'
import ServiceEntry from '../components/ServiceEntry.vue'
import { api, errorMessage } from '../lib/api'
import type { Overview, Notice } from '../types'
import { safeAffairDestination } from '../lib/navigation'
const route = useRoute()
const overview = ref<Overview | null>(null)
const loading = ref(true)
const error = ref('')
const reading = ref<number | null>(null)
const view = computed(() => route.path.endsWith('timetable') ? 'timetable' : route.path.endsWith('notices') ? 'notices' : 'home')
const date = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const services = [
  { to: '/repairs/mine', title: '校园设施报修与进度', description: '报告设施故障，跟进维修并确认处理结果', icon: Wrench },
  { to: '/portal/learning', title: '课程与学习资料', description: '查看本学期课程、教师资料和班级通知', icon: BookOpen },
  { to: '/portal/learning/timetable', title: '每周课程表', description: '按周查看上课时间、教室和任课教师', icon: CalendarDays },
  { to: '/portal/learning/grades', title: '课程成绩查询', description: '查询本人已发布的课程成绩与学分', icon: GraduationCap },
  { to: '/portal/learning/exams', title: '考试时间与地点', description: '查看本人考试日期、时间和考场', icon: ClipboardList },
  { to: '/portal/notices', title: '个人消息通知', description: '阅读发给你的教学通知与个人消息', icon: Bell },
  { to: '/portal/affairs', title: '我的办事与办理进度', description: '办理请假和证明，统一查看补交要求与处理结果', icon: ClipboardList },
  { to: '/portal/certificates', title: '在校证明申请与下载', description: '核对本人登记信息，申请并下载受控演示证明', icon: FileCheck2 },
  { to: '/portal/leave?tab=calendar', title: '我的请假日程', description: '按月查看已通过的请假时间，核对来源申请', icon: CalendarDays },
  { to: '/campus/explore', title: '三维校园导览', description: '探索校园建筑，查找校内地点', icon: MapPin },
]
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
  } catch (cause) { error.value = errorMessage(cause) }
  finally { reading.value = null }
}
onMounted(load)
</script>
<template>
  <div>
    <p v-if="loading" class="loading-state" role="status">正在读取你的校园信息…</p>
    <div v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" @click="load">重新加载</button></div>
    <template v-if="overview">
      <section v-if="view === 'home'" class="student-welcome">
        <div><span class="portal-context">学生服务中心<span>{{ date }}</span></span><h1>{{ overview.profile.fullName }}，你好。</h1><p>{{ overview.profile.departmentName }} · {{ overview.profile.className }}</p></div>
        <span class="welcome-campus">{{ overview.profile.campusName }}<small>今日校园，从容开启</small></span>
      </section>
      <section v-if="view === 'home'" class="portal-services" aria-labelledby="student-services-heading">
        <div class="service-section-heading"><h2 id="student-services-heading">常用学生服务</h2><p>查看学业信息、办理校园事务和阅读个人消息</p></div>
        <div class="service-entry-grid"><ServiceEntry v-for="service in services" :key="service.to" v-bind="service" /></div>
      </section>
      <div class="portal-content-grid" :class="{ single: view !== 'home' }">
        <section v-if="view !== 'notices'" class="portal-panel">
          <div class="section-heading"><div><span class="eyebrow">课程安排</span><h2>{{ view === 'home' ? '近期课程' : '每周课程表' }}</h2></div><RouterLink v-if="view === 'home'" to="/portal/learning/timetable">查看完整课表 <ArrowUpRight :size="14" /></RouterLink></div>
          <p v-if="!overview.timetable.length" class="empty-state">当前没有已导入的课程安排。</p>
          <article v-for="lesson in overview.timetable" :key="lesson.id" class="lesson-row">
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
    </template>
  </div>
</template>
