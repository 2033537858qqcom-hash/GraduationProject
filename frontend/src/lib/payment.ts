export interface PaymentCategory { code: string; name: string; description: string }
export interface PaymentBill {
  id: number; orderNo: string; studentId: number; studentUsername: string; studentName: string
  title: string; categoryCode: string; categoryName: string; amountCents: number; status: string; tradeNo: string | null
  workflowStatus: string; billingPeriod: string; dueDate: string | null; basis: string; standardId: number | null
  standardName: string | null; unitPriceCents: number | null; quantity: string; sourceBasisId: number | null
  createdBy: number; creatorName: string; createdAt: string; paidAt: string | null; refundStatus: string
  pendingRequestId: number | null; paymentStartedAt: string | null; version: number; lastSyncError: string; lastCheckedAt: string | null
}
export interface PaymentHistory { id: number; action: string; actorName: string; reason: string; createdAt: string }
export function paymentWorkflowText(state: string): string {
  return ({ DRAFT: '草稿', PENDING_REVIEW: '待财务复核', RETURNED: '已退回', PUBLISHED: '已发布', VOIDED: '已作废', WAIVED: '已减免' } as Record<string, string>)[state] ?? '待核实'
}
export function paymentSettlementText(bill: { status: string; refundStatus?: string }): string {
  if (bill.refundStatus === 'REFUNDED') return '已退款'
  if (bill.refundStatus === 'REQUESTED') return '退款待复核'
  if (bill.refundStatus === 'PROCESSING') return '退款待确认'
  return bill.status === 'PAID' ? '已支付' : bill.status === 'UNPAID' ? '待支付' : '待核实'
}
export const paymentActionLabels: Record<string, string> = {
  CREATE: '创建草稿', UPDATE: '修改草稿', SUBMIT: '提交复核', APPROVE: '复核通过', RETURN: '退回补充', VOID_DRAFT: '作废草稿',
  REMIND: '发送缴费提醒', RECONCILE: '核验支付结果', REQUEST_VOID: '申请作废', REQUEST_WAIVER: '申请减免', REQUEST_REFUND: '申请退款',
  SETTLE: '支付确认', VOID_REQUESTED: '申请作废', WAIVER_REQUESTED: '申请减免', REFUND_REQUESTED: '申请退款',
  VOID_APPROVE: '作废复核通过', WAIVER_APPROVE: '减免复核通过', REFUND_APPROVE: '退款复核通过',
  VOID_COMPLETED: '作废完成', WAIVER_COMPLETED: '减免完成', REFUND_COMPLETED: '退款完成',
  VOID_UNCONFIRMED: '关单结果待核实', WAIVER_UNCONFIRMED: '关单结果待核实', REFUND_UNCONFIRMED: '退款结果待核实',
  RETIRE: '停用标准', REJECT: '不予通过', RETRY: '核对并重试原申请', EDIT: '修改草稿',
}

export function filterPaymentBills<T extends { categoryCode?: string | null }>(bills: T[], category: string): T[] {
  return category ? bills.filter(bill => (bill.categoryCode || 'OTHER') === category) : bills
}

export function paymentTitleForCategory(title: string, previousName: string, nextName: string): string {
  return !title.trim() || title === previousName ? nextName : title
}

export function centsFromInput(value: string): number | null {
  if (!/^\d{1,7}(?:\.\d{1,2})?$/.test(value)) return null
  const [yuan, decimals = ''] = value.split('.')
  const cents = Number(yuan) * 100 + Number(decimals.padEnd(2, '0'))
  return cents > 0 && cents <= 100000000 ? cents : null
}

export function safeSandboxPaymentUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'openapi-sandbox.dl.alipaydev.com'
      && url.pathname === '/gateway.do' && !url.username && !url.password && !url.port
  } catch { return false }
}

/** 支付宝当面付二维码内容只允许 https://qr.alipay.com/ 开头。 */
export function safeAlipayQrCode(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'qr.alipay.com' && !url.username && !url.password && !url.port
  } catch { return false }
}

export const PAYMENT_POLL_MS = 3000
