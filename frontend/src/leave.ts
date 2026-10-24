export type LeaveStatus = 'DRAFT' | 'SUBMITTED' | 'RETURNED' | 'APPROVED' | 'RETURN_PENDING' | 'COMPLETED' | 'REJECTED' | 'WITHDRAWN'
export const leaveStatuses: Record<LeaveStatus, string> = { DRAFT: '草稿', SUBMITTED: '待审核', RETURNED: '待补交', APPROVED: '已通过', RETURN_PENDING: '待确认销假', COMPLETED: '已销假', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }
export const leaveTypes = { PERSONAL: '事假', SICK: '病假', OTHER: '其他' }
export interface LeaveRequest {
  id: number; requestKey: string; studentId: number; studentName: string; identityNumber: string; classGroupId: number; className: string
  reasonType: keyof typeof leaveTypes; reason: string; contact: string; startsAt: string; endsAt: string; status: LeaveStatus; processingComment: string | null; version: number
  fileId: number | null; fileName: string | null; createdAt: string; updatedAt: string
}
export interface LeaveRevision {
  id: number; version: number; actorName: string; action: string; comment: string; status: LeaveStatus
  reasonType: keyof typeof leaveTypes; reason: string; contact: string; startsAt: string; endsAt: string; fileId: number | null; fileName: string | null; createdAt: string
}
export interface LeaveEligibility { classGroupId: number | null; className: string | null; counselorName: string | null; canSubmit: boolean; message: string; maxDays: number }
export interface LeaveEvent { requestId: number; startsAt: string; endsAt: string }
export interface CounselorAssignment { classGroupId: number; className: string; departmentName: string; counselorId: number | null; counselorName: string | null; version: number }
export interface AssignmentCatalog { assignments: CounselorAssignment[]; counselors: { id: number; name: string; departmentName: string; departmentId: number }[] }
