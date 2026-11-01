import test from 'node:test'
import assert from 'node:assert/strict'
import { PORTAL_PAYMENT_ROUTE, portalPaymentServiceEntry } from '../src/lib/payment.ts'

test('学生首页缴费入口只面向持 portal:read 的账号', () => {
  assert.equal(portalPaymentServiceEntry([]), null)
  assert.equal(portalPaymentServiceEntry(['workbench:read', 'payment:manage']), null)
  assert.equal(portalPaymentServiceEntry(['learning:read']), null)
  const entry = portalPaymentServiceEntry(['portal:read', 'learning:read'])
  assert.ok(entry)
  assert.equal(entry.to, PORTAL_PAYMENT_ROUTE)
  assert.equal(entry.to, '/portal/payments')
  assert.equal(entry.title, '缴费大厅')
  assert.equal(entry.categoryKey, 'AFFAIR')
  assert.equal(entry.category, '办事消息')
  assert.equal(entry.badge, '在线缴费')
  // 服务卡样式复用 ServiceEntry 的徽标/主题枚举
  assert.ok(['primary', 'warning', 'success', 'neutral', 'danger'].includes(entry.badgeType))
  assert.equal(typeof entry.theme, 'string')
  assert.ok(entry.description.includes('退款'))
})
