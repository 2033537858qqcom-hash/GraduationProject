import test from 'node:test'
import assert from 'node:assert/strict'
import { centsFromInput, filterPaymentBills, paymentTitleForCategory, safeAlipayQrCode, safeSandboxPaymentUrl } from '../src/lib/payment.ts'

test('category filters show matching bills and preserve legacy bills under other fees', () => {
  const bills = [{ id: 1, categoryCode: 'TUITION' }, { id: 2, categoryCode: 'WATER' }, { id: 3 }, { id: 4, categoryCode: 'OTHER' }]
  assert.deepEqual(filterPaymentBills(bills, ''), bills)
  assert.deepEqual(filterPaymentBills(bills, 'TUITION').map(bill => bill.id), [1])
  assert.deepEqual(filterPaymentBills(bills, 'OTHER').map(bill => bill.id), [3, 4])
  assert.deepEqual(filterPaymentBills(bills, 'ACCOMMODATION'), [])
  assert.equal(bills.length, 4)
})

test('category changes suggest a title while preserving an administrator custom description', () => {
  assert.equal(paymentTitleForCategory('', '', '学费'), '学费')
  assert.equal(paymentTitleForCategory('学费', '学费', '住宿费'), '住宿费')
  assert.equal(paymentTitleForCategory('2026 学年学费', '学费', '住宿费'), '2026 学年学费')
  assert.equal(paymentTitleForCategory('   ', '学费', '水费'), '水费')
  assert.equal(paymentTitleForCategory('手动备注', '学费', ''), '手动备注')
})

test('money input uses exact decimal cents and rejects malformed amounts', () => {
  assert.equal(centsFromInput('12.30'), 1230)
  assert.equal(centsFromInput('0.01'), 1)
  for (const value of ['0', '-1', '1.001', '1e3', '1000000.01', 'abc', '']) assert.equal(centsFromInput(value), null)
})

test('payment redirect accepts only the configured sandbox gateway', () => {
  assert.equal(safeSandboxPaymentUrl('https://openapi-sandbox.dl.alipaydev.com/gateway.do?x=1'), true)
  for (const value of ['http://openapi-sandbox.dl.alipaydev.com/gateway.do', 'https://openapi-sandbox.dl.alipaydev.com.evil.test/gateway.do', 'javascript:alert(1)', 'https://openapi-sandbox.dl.alipaydev.com/other']) assert.equal(safeSandboxPaymentUrl(value), false)
})

test('qr code content accepts only alipay qr host', () => {
  assert.equal(safeAlipayQrCode('https://qr.alipay.com/bax01234'), true)
  for (const value of ['http://qr.alipay.com/x', 'https://qr.alipay.com.evil.test/x', 'javascript:alert(1)', '']) assert.equal(safeAlipayQrCode(value), false)
})
