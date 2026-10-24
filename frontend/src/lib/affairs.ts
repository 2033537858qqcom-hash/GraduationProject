import { leaveStatuses } from '../leave.ts'
import { repairStatuses } from '../repairs.ts'
import { transferStatus } from '../housing.ts'
import { reservationStatuses } from '../reservations.ts'

export function affairStatuses(kind: string): Record<string, string> {
  if (kind === 'HOUSING') return transferStatus
  if (kind === 'REPAIR') return repairStatuses
  if (kind === 'LEAVE') return leaveStatuses
  if (kind === 'CERTIFICATE') return { ...leaveStatuses, COMPLETED: '办理完成' }
  if (kind === 'RESERVATION') return reservationStatuses
  return { ...leaveStatuses, ...repairStatuses, ...transferStatus, ...reservationStatuses, SUBMITTED: '待处理', RETURNED: '待补充资料', COMPLETED: '办理完成' }
}
export function affairStatus(kind: string, status: string) { return affairStatuses(kind)[status] ?? status }
export function affairAction(kind: string, status: string) {
  if (status === 'RETURNED') return kind === 'RESERVATION' ? '修改预约材料' : '查看补交要求'
  if (status === 'DRAFT') return '继续办理'
  if (status === 'APPROVED' && kind === 'RESERVATION') return '查看预约凭证'
  if (kind === 'LEAVE' && status === 'APPROVED') return '申报返校销假'
  if (kind === 'LEAVE' && status === 'RETURN_PENDING') return '查看销假进度'
  if (kind === 'LEAVE' && status === 'COMPLETED') return '查看销假记录'
  if (kind === 'RESERVATION' && status === 'COMPLETED') return '查看使用记录'
  if (status === 'COMPLETED') return kind === 'REPAIR' ? '查看报修结果' : kind === 'HOUSING' ? '查看调宿结果' : '查看证明结果'
  return '查看办理详情'
}
