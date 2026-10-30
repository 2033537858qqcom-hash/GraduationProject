<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ArrowUpRight, RefreshCw, BedDouble, BookOpen, Building2, CalendarCheck, CalendarDays, ChartColumn,
  ClipboardCheck, FileCheck2, NotebookPen, Newspaper, UserRound, Users, UsersRound, Wrench
} from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import ServiceEntry from '../components/ServiceEntry.vue'
import { useTaskQueue } from '../composables/useTaskQueue'
import type { TaskDomain, WorkbenchOverview, WorkspaceInfo } from '../workbench'
import { schoolMedia } from '../content/schoolMedia'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const error = ref('')
const loading = ref(true)
const workspaces = ref<WorkspaceInfo[]>([])
const activeWorkspaceCode = ref(localStorage.getItem('campus_active_workspace') || '')
const workspaceControlRef = ref<HTMLElement | null>(null)
const { indicatorStyle: workspacePillStyle } = useSlidingIndicator(
  workspaceControlRef,
  () => activeWorkspaceCode.value,
  { activeSelector: '.service-segment-btn.active' }
)
const taskFilterPillsRef = ref<HTMLElement | null>(null)
const { tasks, totalPending, taskCounts, taskFilter, currentPage, total, pageCount, complete, sourceErrors,
  loading: taskLoading, error: taskError, load: loadTasks, initialize: initializeTasks, invalidate } = useTaskQueue()
const { indicatorStyle: taskFilterPillStyle } = useSlidingIndicator(
  taskFilterPillsRef,
  () => taskFilter.value,
  { activeSelector: '.task-pill-btn.active' }
)
const showAllServices = ref(false)

const activeWorkspace = computed(() => {
  return workspaces.value.find(w => w.code === activeWorkspaceCode.value) || workspaces.value[0] || null
})

function selectWorkspace(code: string) {
  activeWorkspaceCode.value = code
  localStorage.setItem('campus_active_workspace', code)
}

const categoryDefinitions: { domain: TaskDomain; label: string; sources: string[] }[] = [
  { domain: 'ALL', label: '全部', sources: [] },
  { domain: 'LEAVE', label: '请假', sources: ['leave'] },
  { domain: 'CERTIFICATE', label: '证明', sources: ['certificate'] },
  { domain: 'RESERVATION', label: '借用', sources: ['reservation'] },
  { domain: 'HOUSING', label: '调宿', sources: ['housingReview', 'housingAssign'] },
  { domain: 'REPAIR', label: '报修', sources: ['repairDispatch', 'repairWork'] },
  { domain: 'CONTENT', label: '官网', sources: ['content'] },
]
const taskCategories = computed(() => categoryDefinitions
  .filter(category => category.domain === 'ALL' || category.domain === taskFilter.value || category.sources.some(source =>
    (taskCounts.value[source] ?? 0) > 0 || sourceErrors.value.some(error => error.domain === source)))
  .map(category => ({ ...category, count: category.domain === 'ALL' ? totalPending.value :
    category.sources.some(source => sourceErrors.value.some(error => error.domain === source)) ? '待重试' :
      category.sources.reduce((sum, source) => sum + (taskCounts.value[source] ?? 0), 0) })))

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

let overviewRequest = 0
async function loadOverview() {
  const request = ++overviewRequest
  loading.value = true
  error.value = ''
  try {
    const res = await api<WorkbenchOverview>('/workbench/overview')
    if (request !== overviewRequest) return
    workspaces.value = res.workspaces || []
    initializeTasks(res)

    // 若本地无存储或存储的工作空间不在当前权限列表，则默认选择首个专业工作空间（非 PERSONAL）
    const exists = workspaces.value.some(w => w.code === activeWorkspaceCode.value)
    if (!exists) {
      const preferred = workspaces.value.find(w => w.code !== 'PERSONAL') || workspaces.value[0]
      if (preferred) selectWorkspace(preferred.code)
    }
  } catch (cause) {
    if (request === overviewRequest) error.value = errorMessage(cause)
  } finally {
    if (request === overviewRequest) loading.value = false
  }
}
onMounted(() => { void loadOverview() })
onBeforeUnmount(() => { overviewRequest++; invalidate() })
</script>

<template>
  <div class="workbench-page campus-workbench">
    <div class="workbench-toolbar"><span>WORKSPACE / 校园事务办理</span><button class="button compact" :disabled="loading || taskLoading" @click="loadOverview"><RefreshCw :size="14" />刷新工作台</button></div>
    <!-- 1. 工作空间切换栏 (R03: 多岗位显式切换) -->
    <div v-if="workspaces.length > 1" class="workspace-switch-wrap">
      <div class="workspace-switcher-header">
        <span class="switcher-label">当前工作空间：</span>
        <div ref="workspaceControlRef" class="service-segmented-control" role="group" aria-label="工作空间切换">
          <div class="service-segment-pill" :style="workspacePillStyle" aria-hidden="true"></div>
          <button
            v-for="ws in workspaces"
            :key="ws.code"
            type="button"
            class="service-segment-btn"
            :class="{ active: activeWorkspaceCode === ws.code }"
            :aria-pressed="activeWorkspaceCode === ws.code"
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
        <h1>{{ activeWorkspace?.name ?? '岗位工作台' }}</h1>
        <p>
          <span v-if="currentUser?.departmentName && !activeWorkspace?.scopeNote.includes(currentUser.departmentName)">{{ currentUser.departmentName }} · </span>
          <span>{{ activeWorkspace?.scopeNote }}</span>
        </p>
      </div>
      <div class="workbench-pending-summary" aria-label="已读取的待办数量">
        <span>{{ loading || taskLoading ? '正在读取' : !complete || taskError || error ? '待办待核实' : '待处理事项' }}</span>
        <strong>{{ loading || error || taskError ? '—' : totalPending.toString().padStart(2,'0') }}</strong>
        <small>{{ !complete ? '以成功读取的业务为准' : '当前账号全部已授权岗位' }}</small>
      </div>
    </section>

    <p v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" :disabled="loading" @click="loadOverview">重新读取工作台</button></p>

    <div class="workbench-dashboard">
    <div class="workbench-primary">
    <!-- 3. 统一待办任务中心 (R04: 跨域任务聚合) -->
    <section class="portal-panel workbench-tasks-panel" aria-labelledby="workbench-tasks-heading">
      <div class="section-heading">
        <div class="heading-with-badge">
          <h2 id="workbench-tasks-heading">
            <ClipboardCheck :size="18" />
            全部岗位待办
          </h2>
          <span v-if="loading || taskLoading" class="service-badge">读取中</span>
          <span v-else-if="error || taskError" class="service-badge badge-warning">读取失败，请重试</span>
          <span v-else-if="!complete" class="service-badge badge-warning">部分读取失败 · 已读取 {{ totalPending }} 项</span>
          <span v-else-if="totalPending > 0" class="service-badge badge-warning">共 {{ totalPending }} 项待处理</span>
          <span v-else class="service-badge badge-success">暂无待处理事项</span>
        </div>
        <div v-if="!loading && !error && (totalPending > 0 || sourceErrors.length)" ref="taskFilterPillsRef" class="task-filter-pills" role="group" aria-label="待办任务分类筛选">
          <button v-for="category in taskCategories" :key="category.domain" type="button" class="task-pill-btn"
            :aria-pressed="taskFilter === category.domain" :class="{ active: taskFilter === category.domain }"
            :disabled="taskLoading" @click="loadTasks(category.domain)">
            {{ category.label }} ({{ category.count }})
          </button>
          <div class="task-filter-pill-indicator" :style="taskFilterPillStyle" aria-hidden="true"></div>
        </div>
      </div>
      <p class="muted">汇总当前账号全部已授权岗位的待处理事项。</p>
      <div v-if="sourceErrors.length" class="task-source-warning" role="status">
        <p>部分待办暂时无法读取：{{ sourceErrors.map(source => source.label).join('、') }}。当前数量仅包含已读取的业务。</p>
        <button class="text-button" :disabled="taskLoading" @click="loadTasks(taskFilter, currentPage)">重新读取</button>
      </div>
      <p v-if="loading || (taskLoading && !tasks.length)" class="loading-state" role="status">正在读取待办…</p>
      <div v-else-if="taskError" class="alert" role="alert">
        {{ taskError }}
        <button class="text-button" @click="loadTasks(taskFilter, currentPage)">重试</button>
      </div>

      <!-- 任务列表 -->
      <div v-else-if="!error && tasks.length > 0" class="workbench-task-grid" :class="{ 'is-loading': taskLoading }">
        <article
          v-for="task in tasks"
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
            <span class="task-status-pill" :data-status="task.status">{{ task.statusText }}</span>
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
            <time class="task-time" :datetime="task.createdAt">{{ formatTime(task.createdAt) }}</time>
            <RouterLink :to="task.targetPath" class="task-action-btn">
              查看并处理 <ArrowUpRight :size="13" />
            </RouterLink>
          </div>
        </article>
      </div>

      <!-- 空状态 -->
      <div v-else-if="!error" class="workbench-tasks-empty">
        <p>{{ !complete ? '部分业务尚未读取成功，请重试后确认待办。' : '当前分类暂无待处理事项。' }}</p>
      </div>
      <nav v-if="!loading && !taskError && !error && total > 0" class="pagination" aria-label="待办分页">
        <span>{{ !complete ? '已读取' : '当前分类共' }} {{ total }} 项 · 第 {{ currentPage }} / {{ pageCount }} 页</span>
        <div>
          <button class="button compact" :disabled="currentPage <= 1 || taskLoading" @click="loadTasks(taskFilter, currentPage - 1)">上一页</button>
          <button class="button compact" :disabled="currentPage >= pageCount || taskLoading" @click="loadTasks(taskFilter, currentPage + 1)">下一页</button>
        </div>
      </nav>
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
      <aside class="workbench-scope-panel" aria-labelledby="workspace-scope-heading">
        <div class="workspace-campus-image">
          <img :src="schoolMedia.overview.url" :alt="schoolMedia.overview.alt" width="1920" height="810" loading="lazy" />
          <span>郑州工业应用技术学院</span>
        </div>
        <div class="workspace-scope-copy">
          <span class="eyebrow">当前工作空间</span>
          <h2 id="workspace-scope-heading">{{ activeWorkspace?.name ?? '岗位信息' }}</h2>
          <dl>
            <div><dt>工作岗位</dt><dd>{{ activeWorkspace?.roleTitle ?? '—' }}</dd></div>
            <div><dt>所属单位</dt><dd>{{ currentUser?.departmentName ?? '暂未登记' }}</dd></div>
            <div><dt>所在校区</dt><dd>{{ currentUser?.campusName ?? '暂未登记' }}</dd></div>
          </dl>
          <p class="workspace-scope-note">{{ activeWorkspace?.scopeNote }}</p>
          <RouterLink to="/profile" class="text-button">核对个人与岗位信息 <UserRound :size="14" /></RouterLink>
        </div>
      </aside>
    </div>
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
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.task-pill-btn {
  position: relative;
  z-index: 1;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 999px;
  cursor: pointer;
  transition: color 0.15s ease;
  outline: none !important;
}
.task-pill-btn:hover:not(.active) {
  background: #f8fafc;
  color: #1e293b;
  border-color: #cbd5e1;
}
.task-pill-btn:focus:not(:focus-visible) {
  outline: none !important;
  box-shadow: none !important;
}
.task-pill-btn.active {
  background: transparent !important;
  color: #ffffff;
  border-color: transparent !important;
  font-weight: 600;
}
.task-filter-pill-indicator {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  background: #244b78;
  border-radius: 999px;
  pointer-events: none;
  box-shadow: 0 1px 3px rgba(36, 75, 120, 0.28);
  transition: transform 0.28s cubic-bezier(0.2, 0.85, 0.32, 1.05),
              width 0.28s cubic-bezier(0.2, 0.85, 0.32, 1.05);
  will-change: transform, width;
  z-index: 0;
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
.task-source-warning {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 12px 14px;
  margin-top: 12px;
  border: 1px solid #f2d59e;
  border-radius: 8px;
  background: #fffbef;
  color: #835c24;
  font-size: 13px;
}
.task-source-warning p { margin: 0; }
.task-pill-btn:disabled { cursor: wait; opacity: .65; }
@media (max-width: 768px) {
  .workbench-task-grid {
    grid-template-columns: 1fr;
  }
}
</style>
