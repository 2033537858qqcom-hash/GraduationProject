<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BedDouble, Bell, BookOpen, Building2, CalendarCheck, ChartColumn, ClipboardCheck, ClipboardList, FileCheck2, House, LogOut, NotebookPen, Newspaper, UserRound, Users, UsersRound, Wrench } from '@lucide/vue'
import { currentUser, logout } from '../stores/auth'
import { errorMessage } from '../lib/api'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import CulturalFrame from '../components/CulturalFrame.vue'
import CulturalOrnament from '../components/CulturalOrnament.vue'
import AssistantChat from '../components/AssistantChat.vue'
const router = useRouter()
const route = useRoute()
const error = ref('')
const leaving = ref(false)
const student = computed(() => currentUser.value?.permissions.includes('portal:read'))
const content = computed(() => currentUser.value?.permissions.some(p => p.startsWith('content:')))
const foundation = computed(() => currentUser.value?.permissions.includes('foundation:manage'))
const counselor = computed(() => currentUser.value?.permissions.includes('leave:review'))
const affairs = computed(() => currentUser.value?.permissions.includes('certificate:review'))
const reservationReviewer = computed(() => currentUser.value?.permissions.includes('reservation:review'))
const repairConfiguration = computed(() => currentUser.value?.permissions.includes('repair:configure'))
const repairResponsibilities = computed(() => currentUser.value?.permissions.some(p => p === 'repair:dispatch' || p === 'repair:work'))
const housingConfiguration = computed(() => currentUser.value?.permissions.includes('housing:configure'))
const housingDesk = computed(() => currentUser.value?.permissions.includes('housing:assign'))
const hasStaffCapabilities = computed(() => currentUser.value?.permissions.some(p => 
  ['teaching:manage', 'academic:manage', 'leave:review', 'certificate:review', 'reservation:review',
   'housing:review', 'housing:assign', 'housing:configure', 'repair:dispatch', 'repair:work',
   'repair:configure', 'foundation:manage', 'content:edit', 'content:review'].includes(p)
))
const workspace = computed(() => foundation.value ? '管理员工作台' : counselor.value ? '辅导员工作台' : affairs.value ? '学生事务工作台' : currentUser.value?.permissions.includes('teaching:manage') ? '教师工作台' : currentUser.value?.permissions.includes('academic:manage') ? '教务工作台' : currentUser.value?.permissions.includes('repair:dispatch') ? '后勤工作台' : currentUser.value?.permissions.includes('repair:work') ? '维修工作台' : housingDesk.value ? '宿管工作台' : '岗位工作台')
const identity = computed(() => (student.value && hasStaffCapabilities.value) ? '多岗位协同' : foundation.value ? '管理员入口' : student.value ? '学生入口' : counselor.value ? '辅导员入口' : affairs.value ? '学生事务入口' : currentUser.value?.permissions.includes('teaching:manage') ? '教师入口' : currentUser.value?.permissions.includes('academic:manage') ? '教务入口' : currentUser.value?.permissions.includes('repair:dispatch') ? '后勤入口' : currentUser.value?.permissions.includes('repair:work') ? '维修入口' : housingDesk.value ? '宿管入口' : '岗位入口')
async function leave() {
  leaving.value = true
  error.value = ''
  try { await logout(); await router.push('/login') } catch (cause) { error.value = errorMessage(cause) }
  finally { leaving.value = false }
}
</script>
<template>
  <div class="portal-shell">
    <header class="portal-header">
      <CulturalFrame class="brand-frame" />
      <CulturalOrnament motif="cloud" class="portal-brand-cloud" />
      <div class="page-width portal-top">
        <RouterLink to="/" class="portal-school" aria-label="郑州工业应用技术学院官网"><SchoolIdentity /><small>校园综合服务平台 · {{ identity }}</small></RouterLink>
        <div class="account-controls">
          <RouterLink :to="student ? '/portal/notices' : '/notices'" class="account-action"><Bell :size="17" aria-hidden="true" /><span>消息通知</span></RouterLink>
          <RouterLink to="/profile" class="account-action"><UserRound :size="17" aria-hidden="true" /><span class="account-name">{{ currentUser?.fullName }}</span><span>个人资料</span></RouterLink>
          <button class="account-action" :disabled="leaving" @click="leave"><LogOut :size="17" aria-hidden="true" /><span>{{ leaving ? '正在退出…' : '退出登录' }}</span></button>
        </div>
      </div>
      <nav class="portal-nav page-width" aria-label="校园服务导航">
        <template v-if="student">
          <RouterLink to="/portal" exact-active-class="selected"><House :size="16" aria-hidden="true" />学生首页</RouterLink>
          <RouterLink to="/portal/learning" :class="{ selected: route.path.startsWith('/portal/learning') }"><BookOpen :size="16" aria-hidden="true" />学习与课表</RouterLink>
          <RouterLink to="/portal/affairs" :class="{ selected: ['/portal/affairs','/portal/leave','/portal/certificates','/portal/reservations'].includes(route.path) }"><ClipboardList :size="16" aria-hidden="true" />我的办事</RouterLink>
          <RouterLink v-if="currentUser?.permissions.includes('reservation:apply')" to="/portal/reservations" exact-active-class="selected"><CalendarCheck :size="16" aria-hidden="true" />场地借用</RouterLink>
          <RouterLink
            v-if="currentUser?.permissions.includes('housing:read')"
            to="/housing/mine"
            :class="{ selected: route.path.startsWith('/housing/mine') || route.path.startsWith('/housing/transfer') }"
          >
            <BedDouble :size="16" aria-hidden="true" />我的住宿与调宿
          </RouterLink>
          <RouterLink v-if="hasStaffCapabilities" to="/workbench" :class="{ selected: route.path.startsWith('/workbench') }">
            <House :size="16" aria-hidden="true" />岗位工作台
          </RouterLink>
        </template>
        <RouterLink v-else to="/workbench" exact-active-class="selected"><House :size="16" aria-hidden="true" />{{ workspace }}</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('teaching:manage')" to="/teaching" :class="{ selected: route.path.startsWith('/teaching') }"><BookOpen :size="16" aria-hidden="true" />教学班与资料</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('academic:manage')" to="/academic" active-class="selected"><NotebookPen :size="16" aria-hidden="true" />教务信息维护</RouterLink>
        <RouterLink v-if="counselor" to="/student-affairs/leave" exact-active-class="selected"><ClipboardCheck :size="16" aria-hidden="true" />班级请假处理</RouterLink>
        <RouterLink v-if="affairs" to="/student-affairs/certificates" exact-active-class="selected"><FileCheck2 :size="16" aria-hidden="true" />学院证明申请处理</RouterLink>
        <RouterLink v-if="reservationReviewer" to="/student-affairs/reservations" exact-active-class="selected"><CalendarCheck :size="16" aria-hidden="true" />借用审批</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('housing:review')" to="/student-affairs/housing" exact-active-class="selected"><BedDouble :size="16" aria-hidden="true" />学院调宿初审</RouterLink>
        <RouterLink v-if="content" to="/admin/content" exact-active-class="selected"><Newspaper :size="16" aria-hidden="true" />官网内容管理</RouterLink>
        <RouterLink v-if="foundation" to="/admin/foundation" exact-active-class="selected"><Users :size="16" aria-hidden="true" />账号与组织管理</RouterLink>
        <RouterLink v-if="foundation" to="/admin/resources" exact-active-class="selected"><Building2 :size="16" aria-hidden="true" />教室资源目录</RouterLink>
        <RouterLink v-if="foundation" to="/admin/counselors" exact-active-class="selected"><UsersRound :size="16" aria-hidden="true" />辅导员负责班级</RouterLink>
        <RouterLink v-if="repairConfiguration" to="/admin/repair-settings" exact-active-class="selected"><Wrench :size="16" aria-hidden="true" />报修配置</RouterLink>
        <RouterLink v-if="housingConfiguration" to="/admin/housing" exact-active-class="selected"><BedDouble :size="16" aria-hidden="true" />宿舍资源</RouterLink>
        <RouterLink v-if="housingDesk" to="/housing/assignments" exact-active-class="selected"><BedDouble :size="16" aria-hidden="true" />住宿办理</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('repair:apply')" to="/repairs/mine" exact-active-class="selected"><Wrench :size="16" aria-hidden="true" />我的校园报修</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('repair:dispatch')" to="/logistics/repairs/dispatch" exact-active-class="selected"><Wrench :size="16" aria-hidden="true" />后勤分诊派单</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('repair:dispatch')" to="/logistics/repairs/statistics" exact-active-class="selected"><ChartColumn :size="16" aria-hidden="true" />报修统计</RouterLink>
        <RouterLink v-if="currentUser?.permissions.includes('repair:work')" to="/logistics/repairs/tasks" exact-active-class="selected"><Wrench :size="16" aria-hidden="true" />我的维修任务</RouterLink>
        <RouterLink v-if="repairResponsibilities" to="/logistics/repair-responsibilities" exact-active-class="selected"><Wrench :size="16" aria-hidden="true" />我的负责范围</RouterLink>
        <RouterLink to="/profile" exact-active-class="selected"><UserRound :size="16" aria-hidden="true" />个人资料</RouterLink>
      </nav>
    </header>
    <p v-if="error" class="alert page-width" role="alert">{{ error }}</p>
    <div class="portal-content-wrap">
      <CulturalFrame class="portal-frame" />
      <CulturalOrnament motif="cloud" class="portal-edge-cloud" />
      <main class="portal-main page-width"><RouterView /></main>
    </div>
    <footer class="portal-footer page-width">毕业设计演示 · 个人记录为演示数据 <RouterLink to="/">学校官网</RouterLink></footer>
    <AssistantChat />
  </div>
</template>
