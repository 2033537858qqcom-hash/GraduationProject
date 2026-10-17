<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  BedDouble, BookOpen, Building2, CalendarCheck, CalendarDays, ChartColumn,
  ClipboardCheck, FileCheck2, NotebookPen, Newspaper, UserRound, Users, UsersRound, Wrench
} from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import ServiceEntry from '../components/ServiceEntry.vue'

interface AggregatedTask {
  id: string
  domain: string
  categoryName: string
  title: string
  status: string
  statusText: string
  applicantName: string
  scopeDetail: string
  targetPath: string
  createdAt: string
  urgency: string
}

interface WorkspaceInfo {
  code: string
  name: string
  roleTitle: string
  scopeNote: string
  entryPaths: string[]
}

interface WorkbenchOverviewResponse {
  profile: any
  workspaces: WorkspaceInfo[]
  tasks: AggregatedTask[]
  totalPending: number
  taskCounts: Record<string, number>
  leavePending: number
  certificatePending: number
  message: string
}

const error = ref('')
const loading = ref(true)
const workspaces = ref<WorkspaceInfo[]>([])
const activeWorkspaceCode = ref(localStorage.getItem('campus_active_workspace') || '')
const tasks = ref<AggregatedTask[]>([])
const totalPending = ref(0)
const taskCounts = ref<Record<string, number>>({})
const taskFilter = ref('ALL')
const showAllServices = ref(false)

const activeWorkspace = computed(() => {
  return workspaces.value.find(w => w.code === activeWorkspaceCode.value) || workspaces.value[0] || null
})

function selectWorkspace(code: string) {
  activeWorkspaceCode.value = code
  localStorage.setItem('campus_active_workspace', code)
}

const filteredTasks = computed(() => {
  if (taskFilter.value === 'ALL') return tasks.value
  if (taskFilter.value === 'LEAVE') return tasks.value.filter(t => t.domain === 'LEAVE')
  if (taskFilter.value === 'CERTIFICATE') return tasks.value.filter(t => t.domain === 'CERTIFICATE')
  if (taskFilter.value === 'RESERVATION') return tasks.value.filter(t => t.domain === 'RESERVATION')
  if (taskFilter.value === 'HOUSING') return tasks.value.filter(t => t.domain === 'HOUSING_REVIEW' || t.domain === 'HOUSING_ASSIGN')
  if (taskFilter.value === 'REPAIR') return tasks.value.filter(t => t.domain === 'REPAIR_DISPATCH' || t.domain === 'REPAIR_WORK')
  if (taskFilter.value === 'CONTENT') return tasks.value.filter(t => t.domain === 'CONTENT')
  return tasks.value
})

function entryVisible(pathPrefix: string) {
  if (showAllServices.value) return true
  if (!activeWorkspace.value) return true
  const code = activeWorkspace.value.code
  if (code === 'TEACHING') return pathPrefix.startsWith('/teaching')
  if (code === 'COUNSELOR') return pathPrefix.startsWith('/student-affairs/leave') || pathPrefix.startsWith('/admin/counselors')
  if (code === 'AFFAIRS') return pathPrefix.startsWith('/student-affairs')
  if (code === 'HOUSING') return pathPrefix.startsWith('/housing/assignments') || pathPrefix.startsWith('/admin/housing')
  if (code === 'LOGISTICS') return pathPrefix.startsWith('/logistics')
  if (code === 'ACADEMIC') return pathPrefix.startsWith('/academic')
  if (code === 'ADMIN') return pathPrefix.startsWith('/admin')
  if (code === 'PERSONAL') return pathPrefix.startsWith('/profile') || pathPrefix.startsWith('/repairs/mine') || pathPrefix.startsWith('/portal/reservations')
  return true
}

function formatTime(isoStr: string) {
  if (!isoStr) return ''
  try {
    const d = new Date(isoStr)
    return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  } catch {
    return isoStr
  }
}

// 权限判断快捷计算属性
const teaching = computed(() => currentUser.value?.permissions.includes('teaching:manage'))
const academic = computed(() => currentUser.value?.permissions.includes('academic:manage'))
const content = computed(() => currentUser.value?.permissions.some(p => p.startsWith('content:')))
const foundation = computed(() => currentUser.value?.permissions.includes('foundation:manage'))
const counselor = computed(() => currentUser.value?.permissions.includes('leave:review'))
const affairs = computed(() => currentUser.value?.permissions.includes('certificate:review'))
const reservationReviewer = computed(() => currentUser.value?.permissions.includes('reservation:review'))
const repairConfiguration = computed(() => currentUser.value?.permissions.includes('repair:configure'))
const repairResponsibilities = computed(() => currentUser.value?.permissions.some(p => p === 'repair:dispatch' || p === 'repair:work'))
const housingConfiguration = computed(() => currentUser.value?.permissions.includes('housing:configure'))
const housingDesk = computed(() => currentUser.value?.permissions.includes('housing:assign'))

onMounted(async () => {
  loading.value = true
  try {
    const res = await api<WorkbenchOverviewResponse>('/workbench/overview')
    workspaces.value = res.workspaces || []
    tasks.value = res.tasks || []
    totalPending.value = res.totalPending || 0
    taskCounts.value = res.taskCounts || {}

    // 若本地无存储或存储的工作空间不在当前权限列表，则默认选择首个专业工作空间（非 PERSONAL）
    const exists = workspaces.value.some(w => w.code === activeWorkspaceCode.value)
    if (!exists) {
      const preferred = workspaces.value.find(w => w.code !== 'PERSONAL') || workspaces.value[0]
      if (preferred) selectWorkspace(preferred.code)
    }
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="workbench-page">
    <!-- 1. 工作空间切换栏 (R03: 多岗位显式切换) -->
    <div v-if="workspaces.length > 1" class="workspace-switch-wrap">
      <div class="workspace-switcher-header">
        <span class="switcher-label">当前工作空间：</span>
        <div class="service-segmented-control" role="tablist" aria-label="工作空间切换">
          <button
            v-for="ws in workspaces"
            :key="ws.code"
            type="button"
            class="service-segment-btn"
            :class="{ active: activeWorkspaceCode === ws.code }"
            role="tab"
            :aria-selected="activeWorkspaceCode === ws.code"
            @click="selectWorkspace(ws.code)"
          >
            {{ ws.name }}
          </button>
        </div>
      </div>
    </div>

    <!-- 2. 岗位身份与负责范围头部 -->
    <section class="student-welcome workbench-welcome">
      <div>
        <span class="portal-context">{{ activeWorkspace?.roleTitle ?? '岗位工作台' }} · {{ activeWorkspace?.name ?? '工作空间' }}</span>
        <h1>{{ currentUser?.fullName }}，你好。</h1>
        <p>
          <span v-if="currentUser?.departmentName">{{ currentUser.departmentName }} · </span>
          <span>{{ activeWorkspace?.scopeNote }}</span>
        </p>
      </div>
      <span v-if="currentUser?.campusName" class="welcome-campus">
        {{ currentUser.campusName }}
        <small>{{ activeWorkspace?.roleTitle }}</small>
      </span>
    </section>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>

    <!-- 3. 统一待办任务中心 (R04: 跨域任务聚合) -->
    <section class="portal-panel workbench-tasks-panel" aria-labelledby="workbench-tasks-heading">
      <div class="section-heading">
        <div class="heading-with-badge">
          <h2 id="workbench-tasks-heading">
            <ClipboardCheck :size="18" />
            待办业务与审核
          </h2>
          <span v-if="totalPending > 0" class="service-badge badge-warning">
            共 {{ totalPending }} 项待处理
          </span>
          <span v-else class="service-badge badge-success">
            全部已处理完成
          </span>
        </div>
        <div v-if="totalPending > 0" class="task-filter-pills" role="tablist" aria-label="待办任务分类筛选">
          <button
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'ALL' }"
            @click="taskFilter = 'ALL'"
          >
            全部 ({{ totalPending }})
          </button>
          <button
            v-if="taskCounts.leave"
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'LEAVE' }"
            @click="taskFilter = 'LEAVE'"
          >
            请假 ({{ taskCounts.leave }})
          </button>
          <button
            v-if="taskCounts.certificate"
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'CERTIFICATE' }"
            @click="taskFilter = 'CERTIFICATE'"
          >
            证明 ({{ taskCounts.certificate }})
          </button>
          <button
            v-if="taskCounts.reservation"
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'RESERVATION' }"
            @click="taskFilter = 'RESERVATION'"
          >
            借用 ({{ taskCounts.reservation }})
          </button>
          <button
            v-if="taskCounts.housingReview || taskCounts.housingAssign"
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'HOUSING' }"
            @click="taskFilter = 'HOUSING'"
          >
            调宿 ({{ (taskCounts.housingReview || 0) + (taskCounts.housingAssign || 0) }})
          </button>
          <button
            v-if="taskCounts.repairDispatch || taskCounts.repairWork"
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'REPAIR' }"
            @click="taskFilter = 'REPAIR'"
          >
            报修 ({{ (taskCounts.repairDispatch || 0) + (taskCounts.repairWork || 0) }})
          </button>
          <button
            v-if="taskCounts.content"
            type="button"
            class="task-pill-btn"
            :class="{ active: taskFilter === 'CONTENT' }"
            @click="taskFilter = 'CONTENT'"
          >
            官网 ({{ taskCounts.content }})
          </button>
        </div>
      </div>

      <!-- 任务列表 -->
      <div v-if="filteredTasks.length > 0" class="workbench-task-grid">
        <article
          v-for="task in filteredTasks"
          :key="task.id"
          class="workbench-task-card"
          :class="{ 'urgent-task': task.urgency === 'URGENT' }"
        >
          <div class="task-card-header">
            <span class="task-category-pill">
              {{ task.categoryName }}
            </span>
            <span v-if="task.urgency === 'URGENT'" class="task-urgency-badge">
              紧急
            </span>
            <span class="task-status-pill">{{ task.statusText }}</span>
          </div>

          <h3 class="task-title">{{ task.title }}</h3>

          <div class="task-meta">
            <span v-if="task.applicantName" class="meta-item">
              <strong>提报人：</strong>{{ task.applicantName }}
            </span>
            <span v-if="task.scopeDetail" class="meta-item">
              <strong>范围：</strong>{{ task.scopeDetail }}
            </span>
          </div>

          <div class="task-card-footer">
            <span class="task-time">{{ formatTime(task.createdAt) }}</span>
            <RouterLink :to="task.targetPath" class="task-action-btn">
              去处理
            </RouterLink>
          </div>
        </article>
      </div>

      <!-- 空状态 -->
      <div v-else class="workbench-tasks-empty">
        <p>当前没有待处理的审核或业务待办，工作已全部就绪。</p>
      </div>
    </section>

    <!-- 4. 岗位服务入口 (按工作空间过滤或查看全部) -->
    <section class="portal-services" aria-labelledby="workbench-services-heading">
      <div class="service-section-heading">
        <div class="heading-with-toggle">
          <div>
            <h2 id="workbench-services-heading">{{ activeWorkspace?.name ?? '岗位服务' }} · 服务入口</h2>
            <p>每个入口都对应你当前工作空间与账号可使用的功能</p>
          </div>
          <button
            v-if="workspaces.length > 1"
            type="button"
            class="text-button toggle-all-btn"
            @click="showAllServices = !showAllServices"
          >
            {{ showAllServices ? '仅看当前空间服务' : '查看全部已授权服务' }}
          </button>
        </div>
      </div>

      <div class="service-entry-grid">
        <ServiceEntry
          v-if="currentUser?.permissions.includes('repair:apply') && entryVisible('/repairs/mine')"
          to="/repairs/mine"
          title="校园设施报修与进度"
          description="填写故障，上传图片，查询维修进度并验收评价"
          :icon="Wrench"
          category="设施后勤"
          theme="amber"
        />
        <ServiceEntry
          v-if="currentUser?.permissions.includes('repair:dispatch') && entryVisible('/logistics/repairs/statistics')"
          to="/logistics/repairs/statistics"
          title="校园报修统计"
          description="按首次提交日期查看当前负责范围的类别、状态和处理时长"
          :icon="ChartColumn"
          category="后勤数据"
          theme="amber"
        />
        <ServiceEntry
          v-if="currentUser?.permissions.includes('repair:dispatch') && entryVisible('/logistics/repairs/dispatch')"
          to="/logistics/repairs/dispatch"
          title="后勤分诊与派单"
          description="核对负责范围内的报修，补充资料并分配维修人员"
          :icon="Wrench"
          category="工单处理"
          theme="amber"
        />
        <ServiceEntry
          v-if="currentUser?.permissions.includes('repair:work') && entryVisible('/logistics/repairs/tasks')"
          to="/logistics/repairs/tasks"
          title="我的维修任务"
          description="接收本人任务，记录维修进展并提交结果"
          :icon="Wrench"
          category="工单执行"
          theme="amber"
        />
        <ServiceEntry
          v-if="foundation && entryVisible('/admin/foundation')"
          to="/admin/foundation"
          title="账号与组织管理"
          description="维护人员账号、组织归属和岗位，查询修改记录"
          :icon="Users"
          category="系统底座"
          theme="indigo"
        />
        <ServiceEntry
          v-if="foundation && entryVisible('/admin/resources')"
          to="/admin/resources"
          title="教室资源目录"
          description="维护楼栋、容量和启停状态，核对课程考试占用"
          :icon="Building2"
          category="资源配置"
          theme="blue"
        />
        <ServiceEntry
          v-if="foundation && entryVisible('/admin/counselors')"
          to="/admin/counselors"
          title="辅导员负责班级"
          description="配置行政班负责人员，移交请假待办与材料权限"
          :icon="UsersRound"
          category="组织配置"
          theme="teal"
        />
        <ServiceEntry
          v-if="repairConfiguration && entryVisible('/admin/repair-settings')"
          to="/admin/repair-settings"
          title="报修地点与人员范围"
          description="配置开放地点、后勤派单与维修人员的负责范围"
          :icon="Wrench"
          category="设施配置"
          theme="amber"
        />
        <ServiceEntry
          v-if="housingConfiguration && entryVisible('/admin/housing')"
          to="/admin/housing"
          title="宿舍资源"
          description="维护宿舍楼、房间、床位，并指定楼栋负责人"
          :icon="BedDouble"
          category="住宿配置"
          theme="teal"
        />
        <ServiceEntry
          v-if="housingDesk && entryVisible('/housing/assignments')"
          to="/housing/assignments"
          title="住宿办理"
          description="在负责楼栋登记初始入住和退宿"
          :icon="BedDouble"
          category="住宿办理"
          theme="emerald"
        />
        <ServiceEntry
          v-if="repairResponsibilities && entryVisible('/logistics/repair-responsibilities')"
          to="/logistics/repair-responsibilities"
          title="我的后勤负责范围"
          description="核对本人当前负责的校区、故障类别与具体地点"
          :icon="Wrench"
          category="岗位职责"
          theme="amber"
        />
        <ServiceEntry
          v-if="counselor && entryVisible('/student-affairs/leave')"
          to="/student-affairs/leave"
          title="班级请假申请处理"
          description="查看负责班级的待办，核对资料并通过、拒绝或退回"
          :icon="ClipboardCheck"
          category="学生事务"
          theme="teal"
        />
        <ServiceEntry
          v-if="affairs && entryVisible('/student-affairs/certificates')"
          to="/student-affairs/certificates"
          title="学院证明申请处理"
          description="核对学生登记信息与用途，退回补交或生成演示证明"
          :icon="FileCheck2"
          category="学生事务"
          theme="indigo"
        />
        <ServiceEntry
          v-if="reservationReviewer && entryVisible('/student-affairs/reservations')"
          to="/student-affairs/reservations"
          title="场地与设备借用审批"
          description="审核公共场地与器材借用申请，核对时段冲突，同意或退回"
          :icon="CalendarCheck"
          category="空间审批"
          theme="sky"
        />
        <ServiceEntry
          v-if="currentUser?.permissions.includes('reservation:apply') && entryVisible('/portal/reservations')"
          to="/portal/reservations"
          title="公共场地与设备借用"
          description="申请报告厅、研讨室、体育馆及影音设备借用，查询审核凭证"
          :icon="CalendarCheck"
          category="空间借用"
          theme="sky"
        />
        <ServiceEntry
          v-if="currentUser?.permissions.includes('housing:review') && entryVisible('/student-affairs/housing')"
          to="/student-affairs/housing"
          title="学院调宿初审"
          description="审核本学院调宿申请，通过后交给宿管安排床位"
          :icon="BedDouble"
          category="住宿初审"
          theme="emerald"
        />
        <ServiceEntry
          v-if="teaching && entryVisible('/teaching')"
          to="/teaching"
          title="教学班与课程资料"
          description="查看学生名单，发布课程资料与班级通知"
          :icon="BookOpen"
          category="教学工作"
          theme="blue"
        />
        <ServiceEntry
          v-if="teaching && entryVisible('/teaching/timetable')"
          to="/teaching/timetable"
          title="每周授课安排"
          description="查看本人授课时间、教学班和上课地点"
          :icon="CalendarDays"
          category="教学工作"
          theme="purple"
        />
        <ServiceEntry
          v-if="academic && entryVisible('/academic')"
          to="/academic"
          title="学院教务信息维护"
          description="维护教学关系，导入成绩并发布考试安排"
          :icon="NotebookPen"
          category="教务管理"
          theme="indigo"
        />
        <ServiceEntry
          v-if="content && entryVisible('/admin/content')"
          to="/admin/content"
          title="官网内容管理"
          description="维护官网文章，按岗位权限提交或审核内容"
          :icon="Newspaper"
          category="校园发布"
          theme="coral"
        />
        <ServiceEntry
          v-if="entryVisible('/profile')"
          to="/profile"
          title="个人资料与账号信息"
          description="查看本人学工号、所属学院和身份角色"
          :icon="UserRound"
          category="个人中心"
          theme="slate"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.workspace-switch-wrap {
  margin-bottom: 20px;
}
.workspace-switcher-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.switcher-label {
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
}
.workbench-welcome {
  margin-bottom: 24px;
}
.heading-with-badge {
  display: flex;
  align-items: center;
  gap: 12px;
}
.heading-with-badge h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.task-filter-pills {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.task-pill-btn {
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.task-pill-btn:hover {
  background: #f8fafc;
  color: #1e293b;
  border-color: #cbd5e1;
}
.task-pill-btn.active {
  background: #244b78;
  color: #ffffff;
  border-color: #244b78;
  font-weight: 600;
}
.workbench-task-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 16px;
}
.workbench-task-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 10px;
  padding: 16px 18px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.workbench-task-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
}
.workbench-task-card.urgent-task {
  border-left: 4px solid #ef4444;
}
.task-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.task-category-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #475569;
  border: 1px solid #e2e8f0;
}
.task-urgency-badge {
  font-size: 10.5px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 6px;
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}
.task-status-pill {
  margin-left: auto;
  font-size: 11px;
  font-weight: 500;
  color: #2563eb;
  background: #eff6ff;
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid #dbeafe;
}
.task-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  margin: 0;
  line-height: 1.45;
}
.task-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #64748b;
}
.task-meta strong {
  font-weight: 500;
  color: #475569;
}
.task-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 10px;
  border-top: 1px dashed #f1f5f9;
}
.task-time {
  font-size: 11.5px;
  color: #94a3b8;
}
.task-action-btn {
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
  background: #244b78;
  padding: 4px 12px;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}
.task-action-btn:hover {
  background: #1b385a;
}
.workbench-tasks-empty {
  padding: 24px 16px;
  text-align: center;
  color: #64748b;
  font-size: 13px;
  background: #f8fafc;
  border-radius: 10px;
  border: 1px dashed #e2e8f0;
  margin-top: 14px;
}
.heading-with-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.toggle-all-btn {
  font-size: 12.5px;
}
@media (max-width: 768px) {
  .workbench-task-grid {
    grid-template-columns: 1fr;
  }
}
</style>
