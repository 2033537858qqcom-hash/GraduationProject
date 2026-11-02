import type { MissionView } from './assistantMissions'

export interface GoalTask {
 key: string; kind: string; label: string; target: 'DRAFT' | 'SUBMIT' | 'TRACK'; dependsOn: string[]; cancelled: boolean
 mission: MissionView | null; submissionHash: string | null; submissionVerified: boolean; watching: boolean
 submissionPreview?: { kind: string; businessId: number; status: string; version: number; fields: {label: string; value: string}[]; proposalHash: string; canSubmit: boolean; issues: string[]; targetPath: string } | null
 lastBusinessStatus?: string | null; trackingComplete?: boolean; lastCheckedAt?: string | null
}
export interface GoalSummary { id: number; requestKey: string; goal: string; stage: string; version: number; updatedAt: string }
export interface GoalEvent { number: number; tool: string; taskKey: string | null; status: string; message: string; promptTokens: number | null; completionTokens: number | null; createdAt: string }
export interface GoalView extends Omit<GoalSummary, 'requestKey'> {
 sessionId: number; instructions: string[]; tasks: GoalTask[]; message: string; focusTaskKey: string | null; events: GoalEvent[]; canContinue: boolean
}
export const goalStageLabels: Record<string, string> = {
 RUNNING: '自主执行中', WAITING_INPUT: '需要你补充', WAITING_CONFIRMATION: '请核对并确认', PAUSED: '进度已保存，可继续', INTERRUPTED: '执行中断，可恢复',
 MONITORING: '正在跟踪办理进度', DONE: '目标已完成并核验', CANCELLED: '后续执行已取消', FAILED: '需要核对执行记录',
}
export const goalToolLabels: Record<string, string> = {
 planning: '选择下一工具', set_plan: '组织任务与依赖', read_guide: '读取有效办理指南', set_task_fields: '整理明确条件', prepare_task: '准备业务预览',
 refresh_task: '核对当前真实结果', select_venue: '比较并选择场地', revise_reservation: '查询替代预约方案', await_user: '等待本人补充或确认',
 submit_task: '提交已授权申请', verify_submission: '读取原申请核验提交', watch_task: '跟踪后续办理', finish: '核验目标完成',
}
export const goalKindLabels: Record<string, string> = { RESERVATION: '场地预约', LEAVE: '请假', REPAIR: '报修', CERTIFICATE: '在校证明' }
export const goalTargetLabels = { DRAFT: '保存草稿', SUBMIT: '提交审核', TRACK: '提交并跟踪' }
