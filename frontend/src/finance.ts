import type { PaymentBill, PaymentCategory, PaymentHistory } from './lib/payment'
export interface FeeStandard {
  id: number; name: string; categoryCode: string; categoryName: string; unit: string; unitPriceCents: number
  campusId: number | null; majorId: number | null; status: string; createdBy: number; creatorName: string; version: number; reason: string
}
export interface FeeBasis {
  id: number; username: string; studentName: string; categoryCode: string; categoryName: string; billingPeriod: string
  quantity: string; basis: string; sourceReference: string; createdBy: number; creatorName: string; status: string
  billId: number | null; reviewComment: string; version: number; createdAt: string
}
export interface FinanceRequest {
  id: number; billId: number; type: string; reason: string; status: string; requestedBy: number; requesterName: string
  reviewedBy: number | null; lastError: string; version: number; createdAt: string
}
export interface FinanceCatalog {
  categories: PaymentCategory[]; standards: FeeStandard[]; operators: { id: number; name: string }[]; reviewers: { id: number; name: string }[]
  campuses: { id: number; name: string }[]; majors: { id: number; name: string }[]; permissions: string[]
}
export interface FinanceSummary { total: number; unpaidCount: number; paidCount: number; overdueCount: number; pendingReviewCount: number; unpaidAmountCents: number; paidAmountCents: number; refundedAmountCents: number }
export interface FinancePage { items: PaymentBill[]; total: number; page: number; pageSize: number; summary: FinanceSummary }
export interface FinanceDetail { bill: PaymentBill; history: PaymentHistory[]; requests: FinanceRequest[]; allowedActions: string[] }
export interface StatementRow { line: number; orderNo: string; amount: string; tradeNo: string; statementStatus: string; billId: number | null; localAmountCents: number | null; localStatus: string; result: string; explanation: string }
export interface StatementPreview { items: StatementRow[]; matchedCount: number; differenceCount: number; notFoundCount: number; invalidCount: number; duplicateCount: number }
export const financeRequestTypes: Record<string, string> = { VOID: '账单作废', WAIVER: '费用减免', REFUND: '全额退款' }
export function financeRecordText(status: string): string {
  return ({ PENDING_REVIEW: '待复核', ACTIVE: '已生效', RETIRED: '已停用', RETURNED: '已退回', SUBMITTED: '待处理', ACCEPTED: '已建单', PROCESSING: '平台处理中', COMPLETED: '已完成', FAILED: '结果待核实', REJECTED: '未通过', REPLACED: '已有修正版' } as Record<string, string>)[status] ?? status
}
