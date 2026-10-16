<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { BedDouble, BookOpen, Building2, CalendarDays, ChartColumn, ClipboardCheck, FileCheck2, NotebookPen, Newspaper, UserRound, Users, UsersRound, Wrench } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import ServiceEntry from '../components/ServiceEntry.vue'
import type { RepairSummary } from '../repairs'
const message = ref('')
const error = ref('')
const teaching = computed(() => currentUser.value?.permissions.includes('teaching:manage'))
const academic = computed(() => currentUser.value?.permissions.includes('academic:manage'))
const content = computed(() => currentUser.value?.permissions.some(p => p.startsWith('content:')))
const foundation = computed(() => currentUser.value?.permissions.includes('foundation:manage'))
const counselor = computed(() => currentUser.value?.permissions.includes('leave:review'))
const affairs = computed(() => currentUser.value?.permissions.includes('certificate:review'))
const repairConfiguration = computed(() => currentUser.value?.permissions.includes('repair:configure'))
const repairResponsibilities = computed(() => currentUser.value?.permissions.some(p => p === 'repair:dispatch' || p === 'repair:work'))
const housingConfiguration = computed(() => currentUser.value?.permissions.includes('housing:configure'))
const housingDesk = computed(() => currentUser.value?.permissions.includes('housing:assign'))
const pending = ref(0), certificatePending = ref(0)
const repairSummary = ref<RepairSummary | null>(null)
const title = computed(() => foundation.value ? '管理员工作台' : counselor.value ? '辅导员工作台' : affairs.value ? '学生事务工作台' : teaching.value ? '教师工作台' : academic.value ? '教务工作台' : repairResponsibilities.value ? '后勤服务工作台' : housingDesk.value ? '宿管工作台' : '岗位工作台')
const introduction = computed(() => foundation.value ? '维护校园人员、组织目录和岗位分配，查看管理操作记录。' : counselor.value ? '处理负责行政班的请假待办，核对学生资料并记录处理意见。' : affairs.value ? '核对所属学院的证明申请，记录处理意见并生成演示结果。' : teaching.value ? '管理本人教学班的课程资料与通知，查看每周授课安排。' : academic.value ? '维护学院教学信息，核对并发布学生成绩与考试安排。' : repairResponsibilities.value ? '核对本人当前负责的校区、故障类别和具体地点，查看已授权的后勤服务。' : housingDesk.value ? '在负责楼栋登记初始入住和退宿，核对当前床位。' : '从下方入口查看个人信息和你有权限使用的岗位服务。')
onMounted(async () => {
  try { const result = await api<{ message: string; leavePending: number; certificatePending: number }>('/workbench/overview'); message.value = result.message; pending.value = result.leavePending; certificatePending.value = result.certificatePending }
  catch (cause) { error.value = errorMessage(cause) }
})
onMounted(async () => {
  if (!currentUser.value?.permissions.some(p => ['repair:apply', 'repair:dispatch', 'repair:work'].includes(p))) return
  try { repairSummary.value = await api<RepairSummary>('/repairs/summary') } catch (cause) { error.value = errorMessage(cause) }
})
</script>
<template>
  <div class="workbench-page">
    <section class="student-welcome">
      <div><span class="portal-context">{{ title }}</span><h1>{{ currentUser?.fullName }}，你好。</h1><p><span v-if="currentUser?.departmentName">{{ currentUser.departmentName }} · </span>{{ introduction }}</p></div>
      <span v-if="currentUser?.campusName" class="welcome-campus">{{ currentUser.campusName }}<small>{{ title }}</small></span>
    </section>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <div v-if="repairSummary && currentUser?.permissions.includes('repair:dispatch')" class="leave-workbench-note"><Wrench :size="20" /><div><strong>待分诊 {{ repairSummary.dispatchPending }} 条 · 取消待处理 {{ repairSummary.dispatchCancellationPending }} 条</strong><p>根据当前校区、类别和地点负责范围统计</p></div><RouterLink class="text-button" to="/logistics/repairs/dispatch">查看报修待办</RouterLink></div>
    <div v-if="repairSummary && currentUser?.permissions.includes('repair:work')" class="leave-workbench-note"><Wrench :size="20" /><div><strong>待接单 {{ repairSummary.workAssigned }} 条 · 处理中 {{ repairSummary.workInProgress }} 条</strong><p>仅统计当前有效范围内分配给本人的任务</p></div><RouterLink class="text-button" to="/logistics/repairs/tasks">查看维修任务</RouterLink></div>
    <div v-if="counselor && message" class="leave-workbench-note"><ClipboardCheck :size="20" /><div><strong>当前请假待办 · {{ pending }} 条</strong><p>仅统计所负责行政班的待审核申请</p></div><RouterLink class="text-button" to="/student-affairs/leave">查看待办</RouterLink></div>
    <div v-if="affairs && message" class="leave-workbench-note"><FileCheck2 :size="20" /><div><strong>当前证明待办 · {{ certificatePending }} 条</strong><p>仅统计所属学院当前可处理的待审核申请</p></div><RouterLink class="text-button" to="/student-affairs/certificates">查看证明待办</RouterLink></div>
    <section class="portal-services" aria-labelledby="workbench-services-heading">
      <div class="service-section-heading"><h2 id="workbench-services-heading">{{ teaching ? '常用教学服务' : academic ? '常用教务服务' : '岗位服务入口' }}</h2><p>每个入口都对应你当前账号可使用的功能</p></div>
      <div class="service-entry-grid">
        <ServiceEntry v-if="currentUser?.permissions.includes('repair:apply')" to="/repairs/mine" title="校园设施报修与进度" description="填写故障，上传图片，查询维修进度并验收评价" :icon="Wrench" />
        <ServiceEntry v-if="currentUser?.permissions.includes('repair:dispatch')" to="/logistics/repairs/statistics" title="校园报修统计" description="按首次提交日期查看当前负责范围的类别、状态和处理时长" :icon="ChartColumn" /><ServiceEntry v-if="currentUser?.permissions.includes('repair:dispatch')" to="/logistics/repairs/dispatch" title="后勤分诊与派单" description="核对负责范围内的报修，补充资料并分配维修人员" :icon="Wrench" />
        <ServiceEntry v-if="currentUser?.permissions.includes('repair:work')" to="/logistics/repairs/tasks" title="我的维修任务" description="接收本人任务，记录维修进展并提交结果" :icon="Wrench" />
        <ServiceEntry v-if="foundation" to="/admin/foundation" title="账号与组织管理" description="维护人员账号、组织归属和岗位，查询修改记录" :icon="Users" />
        <ServiceEntry v-if="foundation" to="/admin/resources" title="教室资源目录" description="维护楼栋、容量和启停状态，核对课程考试占用" :icon="Building2" />
        <ServiceEntry v-if="foundation" to="/admin/counselors" title="辅导员负责班级" description="配置行政班负责人员，移交请假待办与材料权限" :icon="UsersRound" />
        <ServiceEntry v-if="repairConfiguration" to="/admin/repair-settings" title="报修地点与人员范围" description="配置开放地点、后勤派单与维修人员的负责范围" :icon="Wrench" />
        <ServiceEntry v-if="housingConfiguration" to="/admin/housing" title="宿舍资源" description="维护宿舍楼、房间、床位，并指定楼栋负责人" :icon="BedDouble" />
        <ServiceEntry v-if="housingDesk" to="/housing/assignments" title="住宿办理" description="在负责楼栋登记初始入住和退宿" :icon="BedDouble" />
        <ServiceEntry v-if="repairResponsibilities" to="/logistics/repair-responsibilities" title="我的后勤负责范围" description="核对本人当前负责的校区、故障类别与具体地点" :icon="Wrench" />
        <ServiceEntry v-if="counselor" to="/student-affairs/leave" title="班级请假申请处理" description="查看负责班级的待办，核对资料并通过、拒绝或退回" :icon="ClipboardCheck" />
        <ServiceEntry v-if="affairs" to="/student-affairs/certificates" title="学院证明申请处理" description="核对学生登记信息与用途，退回补交或生成演示证明" :icon="FileCheck2" />
        <ServiceEntry v-if="currentUser?.permissions.includes('housing:review')" to="/student-affairs/housing" title="学院调宿初审" description="审核本学院调宿申请，通过后交给宿管安排床位" :icon="BedDouble" />
        <ServiceEntry v-if="teaching" to="/teaching" title="教学班与课程资料" description="查看学生名单，发布课程资料与班级通知" :icon="BookOpen" />
        <ServiceEntry v-if="teaching" to="/teaching/timetable" title="每周授课安排" description="查看本人授课时间、教学班和上课地点" :icon="CalendarDays" />
        <ServiceEntry v-if="academic" to="/academic" title="学院教务信息维护" description="维护教学关系，导入成绩并发布考试安排" :icon="NotebookPen" />
        <ServiceEntry v-if="content" to="/admin/content" title="官网内容管理" description="维护官网文章，按岗位权限提交或审核内容" :icon="Newspaper" />
        <ServiceEntry to="/profile" title="个人资料与账号信息" description="查看本人学工号、所属学院和身份角色" :icon="UserRound" />
      </div>
    </section>
    <p v-if="message && !teaching && !academic && !foundation && !counselor && !affairs" class="workbench-note">{{ message }}</p>
    <p v-else-if="!message && !error" class="muted" role="status">正在读取岗位信息…</p>
  </div>
</template>
