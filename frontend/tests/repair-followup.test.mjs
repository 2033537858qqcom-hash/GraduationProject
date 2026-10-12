import test from 'node:test'
import assert from 'node:assert/strict'
import { repairCancellationMutation, repairInclusiveEnd, repairStatisticsRange } from '../src/repairs.ts'
import { safeLoginDestination, safeAffairDestination } from '../src/lib/navigation.ts'

test('an initial cancellation cannot reuse a previous cancellation identity', () => {
  const value = repairCancellationMutation(5, 'request-cancellation', 8, 3, '时间调整')
  assert.equal(value.endpoint, '/repairs/mine/5/cancellations')
  assert.equal(value.body.cancellationId, null)
  assert.equal(value.body.decision, null)
})
test('withdrawal and dispatcher decisions preserve the selected request and version', () => {
  const withdraw = repairCancellationMutation(5, 'withdraw-cancellation', 12, 8, '继续维修')
  assert.equal(withdraw.endpoint, '/repairs/mine/5/cancellations/current/withdraw')
  assert.equal(withdraw.body.cancellationId, 12)
  assert.equal(withdraw.body.version, 8)
  assert.equal(repairCancellationMutation(5, 'approve-cancellation', 12, 8, '同意').body.decision, 'APPROVE')
  assert.equal(repairCancellationMutation(5, 'reject-cancellation', 12, 8, '继续办理').body.decision, 'REJECT')
  assert.throws(() => repairCancellationMutation(5, 'approve-cancellation', null, 8, '同意'), /核对/)
  assert.equal(repairCancellationMutation(5, 'confirm', null, 8, '完成'), null)
})
test('inclusive date input becomes an exclusive boundary across month and year ends', () => {
  assert.deepEqual(repairStatisticsRange('2026-12-30', '2026-12-31'), { from: '2026-12-30', to: '2027-01-01' })
  assert.equal(repairInclusiveEnd('2027-01-01'), '2026-12-31')
  assert.deepEqual(repairStatisticsRange('2028-02-28', '2028-02-29'), { from: '2028-02-28', to: '2028-03-01' })
})
test('invalid, reversed, and excessive date ranges are rejected', () => {
  for (const values of [['2026-02-30', '2026-03-01'], ['2026-10-13', '2026-10-12'], ['2025-01-01', '2026-12-31'], ['', '2026-10-12']]) assert.throws(() => repairStatisticsRange(...values))
  assert.deepEqual(repairStatisticsRange('2028-01-01', '2028-12-31'), { from: '2028-01-01', to: '2029-01-01' })
})
test('statistics login uses a known route while message links remain request details', () => {
  assert.equal(safeLoginDestination('/logistics/repairs/statistics?from=2026-01-01'), '/logistics/repairs/statistics?from=2026-01-01')
  assert.equal(safeAffairDestination('/logistics/repairs/statistics?id=1&tab=details'), null)
  assert.equal(safeLoginDestination('/logistics/repairs/unknown'), null)
})
