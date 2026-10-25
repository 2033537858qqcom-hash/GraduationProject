import { createRouter, createWebHistory } from 'vue-router'
import { currentUser, landingPage, restoreSession } from './stores/auth'
import { safeLoginDestination } from './lib/navigation'

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (to, _from, savedPosition) => {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
  routes: [
    {
      path: '/', component: () => import('./layouts/PublicLayout.vue'),
      children: [
        { path: '', component: () => import('./pages/PublicHome.vue'), meta: { title: '学校官网' } },
        { path: 'content', redirect: '/' },
        { path: 'content/:slug', component: () => import('./pages/ContentPage.vue'), meta: { title: '校园信息' } },
        { path: 'campus', redirect: '/campus/explore' },
        { path: 'campus/explore', component: () => import('./pages/CampusExplore.vue'), meta: { title: '校园探索' } },
        { path: 'verify/certificate', component: () => import('./pages/CertificateVerifyPage.vue'), meta: { title: '在校证明防伪验真' } },
      ],
    },
    { path: '/login', component: () => import('./pages/LoginPage.vue'), meta: { title: '校园服务登录' } },
    { path: '/account/password', component: () => import('./pages/PasswordRequiredPage.vue'), meta: { title: '设置登录密码', authenticated: true } },
    {
      path: '/', component: () => import('./layouts/PortalLayout.vue'), meta: { authenticated: true },
      children: [
        { path: 'portal', component: () => import('./pages/StudentPortal.vue'), meta: { title: '学生服务中心', permission: 'portal:read' } },
        { path: 'portal/timetable', redirect: '/portal/learning/timetable' },
        { path: 'portal/learning', component: () => import('./pages/LearningPage.vue'), meta: { title: '课程与学习资料', permission: 'learning:read' } },
        { path: 'portal/learning/timetable', component: () => import('./pages/LearningPage.vue'), meta: { title: '每周课程表', permission: 'learning:read' } },
        { path: 'portal/learning/grades', component: () => import('./pages/LearningPage.vue'), meta: { title: '课程成绩查询', permission: 'learning:read' } },
        { path: 'portal/learning/exams', component: () => import('./pages/LearningPage.vue'), meta: { title: '考试时间与地点', permission: 'learning:read' } },
        { path: 'teaching', component: () => import('./pages/LearningPage.vue'), meta: { title: '教学班与课程资料', permission: 'teaching:manage' } },
        { path: 'teaching/timetable', component: () => import('./pages/LearningPage.vue'), meta: { title: '每周授课安排', permission: 'teaching:manage' } },
        { path: 'academic', component: () => import('./pages/AcademicPage.vue'), meta: { title: '教务信息维护', permission: 'academic:manage' } },
        { path: 'portal/notices', component: () => import('./pages/NoticesPage.vue'), meta: { title: '个人消息通知', permission: 'portal:read' } },
        { path: 'notices', component: () => import('./pages/NoticesPage.vue'), meta: { title: '个人消息通知' } },
        { path: 'portal/affairs', component: () => import('./pages/MyAffairsPage.vue'), meta: { title: '我的办事', permission: 'portal:read' } },
        { path: 'portal/certificates', component: () => import('./pages/CertificatePage.vue'), meta: { title: '在校证明申请', permission: 'certificate:apply' } },
        { path: 'student-affairs/certificates', component: () => import('./pages/CertificatePage.vue'), meta: { title: '学院证明申请处理', permission: 'certificate:review' } },
        { path: 'portal/leave', component: () => import('./pages/LeavePage.vue'), meta: { title: '我的请假', permission: 'leave:apply' } },
        { path: 'student-affairs/leave', component: () => import('./pages/LeavePage.vue'), meta: { title: '班级请假处理', permission: 'leave:review' } },
        { path: 'portal/reservations', component: () => import('./pages/ReservationPage.vue'), meta: { title: '场地与设备借用', permission: 'reservation:apply' } },
        { path: 'student-affairs/reservations', component: () => import('./pages/ReservationPage.vue'), meta: { title: '场地与设备借用审批', permission: 'reservation:review' } },
        { path: 'profile', component: () => import('./pages/ProfilePage.vue'), meta: { title: '个人资料' } },
        { path: 'assistant/guides/:slug', component: () => import('./pages/AssistantGuidePage.vue'), meta: { title: '办理指南', permission: 'agent:query' } },
        { path: 'workbench', component: () => import('./pages/WorkbenchPage.vue'), meta: { title: '岗位工作台', permission: 'workbench:read' } },
        { path: 'admin/content', component: () => import('./pages/ContentManager.vue'), meta: { title: '官网内容', contentPermission: true } },
        { path: 'admin/foundation', component: () => import('./pages/FoundationPage.vue'), meta: { title: '账号与组织管理', permission: 'foundation:manage' } },
        { path: 'admin/resources', component: () => import('./pages/ResourcesPage.vue'), meta: { title: '教室资源目录', permission: 'foundation:manage' } },
        { path: 'admin/counselors', component: () => import('./pages/CounselorAssignmentsPage.vue'), meta: { title: '辅导员负责班级', permission: 'foundation:manage' } },
        { path: 'admin/repair-settings', component: () => import('./pages/RepairSettingsPage.vue'), meta: { title: '报修地点与人员范围', permission: 'repair:configure' } },
        { path: 'admin/housing', component: () => import('./pages/HousingSettingsPage.vue'), meta: { title: '宿舍资源', permission: 'housing:configure' } },
        { path: 'housing', redirect: '/housing/mine' },
        { path: 'housing/mine', component: () => import('./pages/HousingMinePage.vue'), meta: { title: '我的住宿与调宿', permission: 'housing:read' } },
        { path: 'housing/transfer', component: () => import('./pages/HousingMinePage.vue'), meta: { title: '调宿申请与住宿', permission: 'housing:read' } },
        { path: 'student-affairs/housing', component: () => import('./pages/HousingTransferPage.vue'), meta: { title: '学院调宿初审', permission: 'housing:review' } },
        { path: 'housing/assignments', component: () => import('./pages/HousingDeskPage.vue'), meta: { title: '住宿办理', permission: 'housing:assign' } },
        { path: 'repairs', redirect: '/repairs/mine' },
        { path: 'repairs/mine', component: () => import('./pages/RepairPage.vue'), meta: { title: '我的校园报修', permission: 'repair:apply' } },
        { path: 'logistics/repairs/dispatch', component: () => import('./pages/RepairPage.vue'), meta: { title: '后勤分诊与派单', permission: 'repair:dispatch' } },
        { path: 'logistics/repairs/statistics', component: () => import('./pages/RepairStatisticsPage.vue'), meta: { title: '校园报修统计', permission: 'repair:dispatch' } },
        { path: 'logistics/repairs/tasks', component: () => import('./pages/RepairPage.vue'), meta: { title: '我的维修任务', permission: 'repair:work' } },
        { path: 'logistics/repair-responsibilities', component: () => import('./pages/RepairResponsibilitiesPage.vue'), meta: { title: '我的后勤负责范围', repairResponsibility: true } },
      ],
    },
    { path: '/forbidden', component: () => import('./pages/StatusPage.vue'), meta: { title: '无访问权限' } },
    { path: '/unavailable', component: () => import('./pages/StatusPage.vue'), meta: { title: '服务暂不可用' } },
    { path: '/:pathMatch(.*)*', component: () => import('./pages/StatusPage.vue'), meta: { title: '页面未找到' } },
  ],
})

router.beforeEach(async to => {
  // The recovery page must remain reachable while the backend is starting or unavailable.
  if (to.path === '/unavailable') return
  if (to.meta.authenticated || to.path === '/login') {
    try { await restoreSession() } catch { return { path: '/unavailable', query: { redirect: to.fullPath } } }
  } else {
    try { await restoreSession() } catch { /* Ignore network errors for public site */ }
  }
  if (to.meta.authenticated && !currentUser.value) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.authenticated && currentUser.value?.passwordChangeRequired && to.path !== '/account/password') return '/account/password'
  if (to.path === '/login' && currentUser.value) return safeLoginDestination(to.query.redirect) ?? landingPage()
  if (to.meta.permission && !currentUser.value?.permissions.includes(String(to.meta.permission))) return '/forbidden'
  if (to.meta.contentPermission && !currentUser.value?.permissions.some(p => p.startsWith('content:'))) return '/forbidden'
  if (to.meta.repairResponsibility && !currentUser.value?.permissions.some(p => p === 'repair:dispatch' || p === 'repair:work')) return '/forbidden'
})

router.afterEach((to, _from, failure) => {
  if (!failure) document.title = String(to.meta.title ?? '校园服务') + ' · 郑州工业应用技术学院'
})
