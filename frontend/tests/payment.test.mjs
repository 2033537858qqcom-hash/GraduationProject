import test from 'node:test'
import assert from 'node:assert/strict'
import { centsFromInput, safeAlipayQrCode, safeSandboxPaymentUrl } from '../src/lib/payment.ts'

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
