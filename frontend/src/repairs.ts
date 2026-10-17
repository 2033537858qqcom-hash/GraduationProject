export const repairStatuses: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '待后勤分诊', RETURNED: '待补充资料', ASSIGNED: '待维修接单', IN_PROGRESS: '维修处理中', AWAITING_CONFIRMATION: '待本人验收', COMPLETED: '已完成', REJECTED: '已拒绝', WITHDRAWN: '已撤回', CANCELLED: '已取消' }
export const repairCategories: Record<string, string> = { WATER: '给排水', ELECTRICITY: '电气照明', NETWORK: '网络', FURNITURE: '家具', DOOR_WINDOW: '门窗', EQUIPMENT: '设备', OTHER: '其他' }
export const repairActions: Record<string, string> = { SAVE: '保存资料', UPLOAD: '上传图片', REMOVE_IMAGE: '移除当前图片', submit: '提交报修', withdraw: '撤回报修', return: '要求补充资料', reject: '拒绝报修', assign: '派给维修人员', reassign: '改派维修人员', accept: '确认接单', progress: '记录维修进展', unable: '交回后勤处理', complete: '提交维修结果', confirm: '确认修好了', reopen: '问题仍未解决', evaluation: '提交服务评价', priority: '调整优先级' }
export interface RepairImage { id: number; name: string; type: string; purpose: string; resultRound: number; createdAt: string }
export interface RepairItem { id: number; reporterName: string; title: string; campusId: number | null; campusName: string; locationId: number | null; locationName: string; category: string; priority: string; status: string; technicianName: string | null; version: number; updatedAt: string; cancellationPending: boolean; confirmationOverdue: boolean }
export interface RepairAssignment { id: number; technicianId: number; technicianName: string; actorId: number; actorName: string; comment: string; assignedAt: string; endedAt: string | null }
export interface RepairDetail extends RepairItem { locationEligible: boolean; locationEligibilityMessage: string; cancellation: RepairCancellation | null; description: string; locationDetail: string; contact: string; contactWindow: string; technicianId: number | null; completionRound: number; processingComment: string; allowedActions: string[]; images: RepairImage[]; completions: { resultRound: number; technicianName: string; comment: string; images: RepairImage[]; createdAt: string }[]; assignments: RepairAssignment[]; evaluation: { stars: number; comment: string; createdAt: string } | null; submittedAt: string | null; confirmedAt: string | null; createdAt: string }
export interface RepairHistory { id: number; actorName: string; action: string; comment: string; status: string; version: number; snapshot: string; createdAt: string }
export interface RepairSummary { mineAwaitingConfirmation: number; mineReturned: number; dispatchPending: number; workAssigned: number; workInProgress: number; mineCancellationPending: number; dispatchCancellationPending: number }
export interface RepairCancellation { id: number; reason: string; status: string; requesterName: string; decisionActorName: string | null; decisionComment: string; createdAt: string; resolvedAt: string | null }
export const cancellationStatuses: Record<string, string> = { PENDING: '取消待后勤处理', APPROVED: '已同意取消', REJECTED: '未同意取消', WITHDRAWN: '已撤销取消申请' }
Object.assign(repairActions, { 'request-cancellation': '申请取消报修', 'withdraw-cancellation': '撤销取消申请', 'approve-cancellation': '同意取消', 'reject-cancellation': '拒绝取消', CANCEL_REQUEST: '申请取消', CANCEL_WITHDRAW: '撤销取消申请', CANCEL_APPROVE: '同意取消', CANCEL_REJECT: '拒绝取消' })
export interface RepairStatistics { from: string; to: string; total: number; completed: number; cancelled: number; cancellationPending: number; averageHours: number | null; averageSampleCount: number; statuses: { code: string; count: number }[]; categories: { code: string; count: number }[]; queriedAt: string }
export function repairCancellationMutation(id: number, action: string, cancellationId: number | null, version: number, comment: string) {
  if (!['request-cancellation', 'withdraw-cancellation', 'approve-cancellation', 'reject-cancellation'].includes(action)) return null
  if (action !== 'request-cancellation' && (!Number.isSafeInteger(cancellationId) || !cancellationId || cancellationId < 1)) throw new Error('请先刷新并核对当前取消申请')
  const endpoint = action === 'request-cancellation' ? `/repairs/mine/${id}/cancellations` : action === 'withdraw-cancellation' ? `/repairs/mine/${id}/cancellations/current/withdraw` : `/repairs/dispatch/${id}/cancellation/decide`
  return { endpoint, body: { version, cancellationId: action === 'request-cancellation' ? null : cancellationId, decision: action === 'approve-cancellation' ? 'APPROVE' : action === 'reject-cancellation' ? 'REJECT' : null, comment } }
}
function repairDate(value: string) {
  const date = new Date(value + 'T00:00:00.000Z')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) throw new Error('请选择有效日期')
  return date
}
export function repairStatisticsRange(from: string, through: string) {
  const start = repairDate(from), end = repairDate(through)
  const days = (end.getTime() - start.getTime()) / 86400000 + 1
  if (days < 1 || days > 366) throw new Error('请选择 1—366 天的统计区间')
  end.setUTCDate(end.getUTCDate() + 1)
  return { from, to: end.toISOString().slice(0, 10) }
}
export function repairInclusiveEnd(exclusive: string) {
  const date = repairDate(exclusive); date.setUTCDate(date.getUTCDate() - 1); return date.toISOString().slice(0, 10)
}
export interface RepairForm { locationId: string; category: string; title: string; description: string; locationDetail: string; contact: string; contactWindow: string }
export function repairForm(detail?: RepairDetail | null): RepairForm {
  return { locationId: detail?.locationId ? String(detail.locationId) : '', category: detail?.category ?? '', title: detail?.title ?? '', description: detail?.description ?? '', locationDetail: detail?.locationDetail ?? '', contact: detail?.contact ?? '', contactWindow: detail?.contactWindow ?? '' }
}
export function repairDraft(form: RepairForm) {
  const id = Number(form.locationId)
  return { ...form, locationId: Number.isSafeInteger(id) && id > 0 ? id : null }
}
/** A retry keeps the same operation key until the endpoint or payload changes. Never persists private form contents. */
export function repairOperation() {
  let signature = '', key = ''
  return (endpoint: string, payload: unknown) => {
    const next = JSON.stringify([endpoint, payload])
    if (signature !== next) { signature = next; key = crypto.randomUUID() }
    return key
  }
}
export function repairSnapshot(raw: string): { title: string; description: string; contact: string; locationDetail: string; images: RepairImage[] } | null {
  try {
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    const fields = value as Record<string, unknown>
    return { title: typeof fields.title === 'string' ? fields.title : '', description: typeof fields.description === 'string' ? fields.description : '', contact: typeof fields.contact === 'string' ? fields.contact : '', locationDetail: typeof fields.locationDetail === 'string' ? fields.locationDetail : '', images: Array.isArray(fields.images) ? fields.images.filter((item: unknown): item is RepairImage => !!item && typeof item === 'object' && Number.isSafeInteger((item as RepairImage).id) && (item as RepairImage).id > 0 && typeof (item as RepairImage).name === 'string') : [] }
  } catch { return null }
}
