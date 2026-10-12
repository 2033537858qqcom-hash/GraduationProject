export interface CertificateIdentity {
  departmentId: number; classGroupId: number; studentName: string; identityNumber: string
  departmentName: string; majorName: string; className: string; campusName: string
}
export interface CertificateEligibility { identity: CertificateIdentity; canSubmit: boolean; message: string }
export interface CertificateRequest {
  id: number; requestKey: string; studentId: number; identity: CertificateIdentity; purpose: string
  deliveryMethod: 'DIGITAL'; identityConfirmed: boolean; status: string; processingComment: string | null
  version: number; createdAt: string; updatedAt: string; documentNumber: string | null; issuedAt: string | null
}
export interface CertificateRevision {
  id: number; version: number; actorName: string; action: string; comment: string; status: string
  studentName: string; identityNumber: string; departmentName: string; majorName: string; className: string
  campusName: string; purpose: string; deliveryMethod: string; createdAt: string
}
export const certificateStatuses: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '待审核', RETURNED: '待补交', COMPLETED: '办理完成', REJECTED: '已拒绝', WITHDRAWN: '已撤回',
}
export const certificateActions: Record<string, string> = {
  CREATED: '保存草稿', UPDATED: '修改资料', SUBMITTED: '提交审核', RETURNED: '退回补交', COMPLETED: '生成证明', REJECTED: '拒绝申请', WITHDRAWN: '撤回申请',
}
