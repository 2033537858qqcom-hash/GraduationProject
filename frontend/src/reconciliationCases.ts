export interface ReconciliationCase {
  id: number; batchId: number; line: number; result: string; orderNo: string; amount: string; tradeNo: string
  statementStatus: string; billId: number | null; localAmountCents: number | null; localStatus: string; explanation: string
  status: 'OPEN' | 'IN_PROGRESS' | 'PENDING_REVIEW' | 'CLOSED'; assigneeId: number | null; assigneeName: string | null
  lastNote: string; version: number; createdAt: string; updatedAt: string
}
export interface ReconciliationCasePage { items: ReconciliationCase[]; total: number; page: number; pageSize: number }
export interface ReconciliationHistory { action: string; actorName: string | null; reason: string; createdAt: string }
export const reconStatuses: Record<ReconciliationCase['status'], string> = { OPEN: '待核查', IN_PROGRESS: '核查中', PENDING_REVIEW: '待复核', CLOSED: '已关闭' }
export const reconActions: Record<string, string> = { CREATE: '建立事项', START: '开始核查', TAKE_OVER: '接手核查', SUBMIT: '提交复核', CLOSE: '复核关闭', RETURN: '退回核查', REOPEN: '重新打开' }
export const reconResults: Record<string, string> = { DIFFERENCE: '存在差异', NOT_FOUND: '本地无订单', INVALID: '格式异常', DUPLICATE: '重复订单' }
