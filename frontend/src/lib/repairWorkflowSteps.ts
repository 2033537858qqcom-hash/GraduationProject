import type { Component } from 'vue'
import { AlertCircle, AlertTriangle, Award, CheckCircle2, Clock, FileText, UserCheck, Wrench } from '@lucide/vue'
import { formatDateTime, formatShortDateTime } from './format.ts'
import { repairStatuses, type RepairDetail } from '../repairs.ts'

export type RepairStepState = 'completed' | 'active' | 'pending' | 'warning' | 'rejected'

export interface RepairWorkflowStep {
  id: number
  title: string
  subtitle: string
  time: string | null
  state: RepairStepState
}

export interface RepairStepperOverview {
  text: string
  hint: string
  class: string
  icon: Component
}

export function repairStepTime(value: string | null | undefined): string | null {
  return value ? formatShortDateTime(value) : null
}

/** Build the 4-stage repair progress model from the current work order detail. */
export function buildRepairWorkflowSteps(d: RepairDetail | null): RepairWorkflowStep[] {
  if (!d) return []

  // Step 1: 提交报修
  let s1State: 'completed' | 'active' | 'warning' | 'rejected' = 'completed'
  let s1Subtitle = '已提交报修资料'
  const s1Time = repairStepTime(d.submittedAt || d.createdAt)
  if (d.status === 'DRAFT') {
    s1State = 'active'
    s1Subtitle = '草稿尚未提交'
  } else if (d.status === 'RETURNED') {
    s1State = 'warning'
    s1Subtitle = '需补充资料'
  } else if (d.status === 'WITHDRAWN') {
    s1State = 'rejected'
    s1Subtitle = '报修人已撤回'
  }

  // Step 2: 后勤派工
  let s2State: RepairStepState
  let s2Subtitle = '等待后勤分派'
  const s2Time = d.assignments?.[0]?.assignedAt ? repairStepTime(d.assignments[0].assignedAt) : null

  if (['DRAFT', 'WITHDRAWN'].includes(d.status)) {
    s2State = 'pending'
    s2Subtitle = '待提交后分派'
  } else if (d.status === 'REJECTED') {
    s2State = 'rejected'
    s2Subtitle = '后勤拒绝受理'
  } else if (d.status === 'RETURNED') {
    s2State = 'pending'
    s2Subtitle = '待补充后分派'
  } else if (d.status === 'SUBMITTED') {
    s2State = 'active'
    s2Subtitle = '后勤分诊派单中'
  } else if (d.technicianName || ['ASSIGNED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION', 'COMPLETED'].includes(d.status)) {
    s2State = 'completed'
    s2Subtitle = d.technicianName ? `已指派 ${d.technicianName}` : '已指派维修人员'
  } else {
    s2State = 'active'
  }

  // Step 3: 上门维修
  let s3State: RepairStepState = 'pending'
  let s3Subtitle = '待师傅接单处理'
  const s3Time = d.completions?.[0]?.createdAt ? repairStepTime(d.completions[0].createdAt) : null

  if (['DRAFT', 'SUBMITTED', 'RETURNED', 'WITHDRAWN', 'REJECTED'].includes(d.status)) {
    s3State = 'pending'
    s3Subtitle = '待后勤派工'
  } else if (d.status === 'CANCELLED') {
    s3State = 'rejected'
    s3Subtitle = '工单已取消'
  } else if (d.status === 'ASSIGNED') {
    s3State = 'active'
    s3Subtitle = d.technicianName ? `${d.technicianName} 待接单` : '师傅待接单'
  } else if (d.status === 'IN_PROGRESS') {
    s3State = 'active'
    s3Subtitle = '师傅已接单，维修中'
  } else if (['AWAITING_CONFIRMATION', 'COMPLETED'].includes(d.status)) {
    s3State = 'completed'
    s3Subtitle = d.completions?.length ? `维修完成 (第${d.completionRound}轮)` : '已完成维修'
  }

  // Step 4: 验收办结
  let s4State: RepairStepState = 'pending'
  let s4Subtitle = '维修后本人验收'
  const s4Time = d.confirmedAt ? repairStepTime(d.confirmedAt) : null

  if (d.status === 'COMPLETED') {
    s4State = 'completed'
    s4Subtitle = d.evaluation ? `已验收 · ${d.evaluation.stars} 星` : '本人已确认解决'
  } else if (d.status === 'AWAITING_CONFIRMATION') {
    s4State = 'active'
    s4Subtitle = d.confirmationOverdue ? '待你验收 · 演示提醒' : '待你核验确认'
  } else if (['WITHDRAWN', 'REJECTED', 'CANCELLED'].includes(d.status)) {
    s4State = 'rejected'
    s4Subtitle = '工单已终止'
  }

  return [
    { id: 1, title: '提交报修', subtitle: s1Subtitle, time: s1Time, state: s1State },
    { id: 2, title: '后勤派工', subtitle: s2Subtitle, time: s2Time, state: s2State },
    { id: 3, title: '维修处理', subtitle: s3Subtitle, time: s3Time, state: s3State },
    { id: 4, title: '验收办结', subtitle: s4Subtitle, time: s4Time, state: s4State },
  ]
}

/** Build the headline status banner summary for the active repair order. */
export function buildRepairStepperOverview(d: RepairDetail | null, mode: string): RepairStepperOverview {
  if (!d) return { text: '', hint: '', class: 'info', icon: FileText }

  if (d.cancellation?.status === 'PENDING') {
    return {
      text: '工单暂停办理：取消申请等待后勤处理',
      hint: '报修人可撤销取消申请，或等待后勤审核决定',
      class: 'warning',
      icon: AlertTriangle,
    }
  }
  if (d.status === 'DRAFT') {
    return {
      text: '工单处于草稿阶段，尚未正式提交',
      hint: '完善资料并保存后，核对无误再点击“提交报修”',
      class: 'info',
      icon: FileText,
    }
  }
  if (d.status === 'SUBMITTED') {
    return {
      text: '已提交报修，等待后勤分诊与指派人员',
      hint: '后勤核对地点与故障情况后，将分配维修人员',
      class: 'active',
      icon: Clock,
    }
  }
  if (d.status === 'RETURNED') {
    return {
      text: '后勤要求补充资料',
      hint: d.processingComment || '请根据后勤说明补充具体位置或故障现象细节',
      class: 'warning',
      icon: AlertCircle,
    }
  }
  if (d.status === 'ASSIGNED') {
    return {
      text: `已指派${d.technicianName || '维修人员'}，等待接单`,
      hint:
        mode === 'mine'
          ? '接单后将联系报修人或现场排查，请保持联系方式畅通'
          : '维修人员接单后将联系报修人，记录排查与处理进展',
      class: 'active',
      icon: UserCheck,
    }
  }
  if (d.status === 'IN_PROGRESS') {
    return {
      text: `${d.technicianName || '维修人员'}已接单，正在处理`,
      hint: d.processingComment || '维修人员已排查故障并记录进展',
      class: 'active',
      icon: Wrench,
    }
  }
  if (d.status === 'AWAITING_CONFIRMATION') {
    return {
      text: mode === 'mine' ? '维修结果已提交，等待你验收' : '维修结果已提交，等待报修人验收',
      hint:
        mode === 'mine'
          ? '请现场核验设施是否恢复正常，再确认修复或反馈问题'
          : '由报修人核验设施是否恢复正常，确认后完成工单',
      class: 'highlight',
      icon: Award,
    }
  }
  if (d.status === 'COMPLETED') {
    return {
      text: '本次报修已完成并结单',
      hint: `${d.confirmedAt ? formatDateTime(d.confirmedAt, '尚未发生') + ' 验收' : '办理记录已归档'}${d.evaluation ? ` · 服务评价 ${d.evaluation.stars} 星` : ''}`,
      class: 'success',
      icon: CheckCircle2,
    }
  }
  if (d.status === 'REJECTED') {
    return {
      text: '工单已拒绝受理',
      hint: d.processingComment || '非服务范围或资料不符，详情见处理说明',
      class: 'rejected',
      icon: AlertCircle,
    }
  }
  if (d.status === 'WITHDRAWN') {
    return {
      text: '报修人已撤回报修',
      hint: '该工单流转已终止',
      class: 'info',
      icon: AlertCircle,
    }
  }
  if (d.status === 'CANCELLED') {
    return {
      text: '工单已同意取消',
      hint: '维修任务已关闭',
      class: 'info',
      icon: CheckCircle2,
    }
  }
  return {
    text: repairStatuses[d.status] || d.status,
    hint: '',
    class: 'info',
    icon: Clock,
  }
}
