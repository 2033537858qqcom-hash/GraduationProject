import test from 'node:test'
import assert from 'node:assert/strict'
import { portalNavigation } from '../src/lib/portalNavigation.ts'
import { safeAffairDestination, safeLoginDestination } from '../src/lib/navigation.ts'
import { paymentWorkflowText, paymentSettlementText } from '../src/lib/payment.ts'

test('finance and source duties have their own destinations while system administration grants no financial write access', () => {
  const links = permissions => portalNavigation(permissions).flatMap(group => group.items.map(item => item.to))
  assert.ok(links(['workbench:read', 'payment:manage']).includes('/finance/payments'))
  assert.ok(links(['workbench:read', 'payment:review']).includes('/finance/payments'))
  assert.ok(links(['workbench:read', 'payment:source']).includes('/payment-bases'))
  assert.ok(!links(['foundation:manage']).includes('/finance/payments'))
  assert.ok(!links(['foundation:manage']).includes('/admin/payments'))
})

test('finance and student bill links survive login and notification navigation without accepting external destinations', () => {
  for (const path of ['/finance/payments', '/finance/payments?id=12&tab=details', '/payment-bases', '/portal/payments']) assert.equal(safeLoginDestination(path), path)
  for (const path of ['/finance/payments?id=12&tab=details', '/portal/payments?id=12&tab=details']) assert.equal(safeAffairDestination(path), path)
  assert.equal(safeLoginDestination('//external.example/finance/payments'), null)
  assert.equal(safeAffairDestination('/finance/payments?id=12&tab=details&next=https://external.example'), null)
})

test('billing workflow and actual settlement remain distinct, including refunded and unconfirmed states', () => {
  assert.equal(paymentWorkflowText('PENDING_REVIEW'), '待财务复核')
  assert.equal(paymentWorkflowText('VOIDED'), '已作废')
  assert.equal(paymentWorkflowText('WAIVED'), '已减免')
  assert.equal(paymentSettlementText({ status: 'PAID', refundStatus: 'REFUNDED' }), '已退款')
  assert.equal(paymentSettlementText({ status: 'PAID', refundStatus: 'NONE' }), '已支付')
  assert.equal(paymentSettlementText({ status: 'UNPAID', refundStatus: 'NONE' }), '待支付')
  assert.equal(paymentSettlementText({ status: 'UNKNOWN', refundStatus: 'NONE' }), '待核实')
})
