import test from 'node:test'
import assert from 'node:assert/strict'
import { assistantContext, assistantContextLabel, assistantSuggestions, safeAssistantDestination } from '../src/lib/assistant.ts'

test('payment context and controlled payment links are supported', () => {
 assert.deepEqual(assistantContext('/portal/payments', '12'), { type: 'PAYMENT_MINE', id: 12 })
 assert.match(assistantContextLabel({ type: 'PAYMENT_MINE', id: 12 }), /账单 #12/)
 for (const path of ['/portal/payments', '/portal/payments?id=12&tab=details', '/finance/payments?tab=reconciliation', '/finance/payments?tab=reconciliation&case=7']) assert.equal(safeAssistantDestination(path), path)
 for (const path of ['/api/payments/mine/12/pay', '/portal/payments?id=12&userId=2', '/finance/payments?tab=reconciliation&case=7&url=https://evil.test']) assert.equal(safeAssistantDestination(path), null)
})
test('payment suggestions follow student and finance tools', () => {
 assert.ok(assistantSuggestions('/portal/payments', ['my_payments', 'my_dorm_utilities'], null).some(x => /缴|账单/.test(x)))
 assert.ok(assistantSuggestions('/finance/payments', ['finance_reconciliation_pending'], null).some(x => /对账/.test(x)))
 assert.deepEqual(assistantSuggestions('/portal/payments', ['payment_detail'], { type: 'PAYMENT_MINE', id: 12 }), ['这笔费用怎么计算的', '这笔账单支付状态'])
})
