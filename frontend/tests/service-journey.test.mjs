import test from 'node:test'
import assert from 'node:assert/strict'
import { serviceJourney } from '../src/lib/serviceJourney.ts'
import { affairStatuses } from '../src/lib/affairs.ts'
import { certificateStatuses } from '../src/certificates.ts'

test('every existing business status keeps its domain label and stays within its journey', () => {
  for (const kind of ['LEAVE', 'CERTIFICATE', 'REPAIR', 'HOUSING', 'RESERVATION']) {
    for (const [status, label] of Object.entries(kind === 'CERTIFICATE' ? certificateStatuses : affairStatuses(kind))) {
      const result = serviceJourney(kind, status)
      assert.equal(result.label, label)
      assert.ok(result.index === null || result.index >= 0 && result.index < result.steps.length)
      assert.ok(result.note)
    }
  }
})
test('approval, pending return, awaiting inspection and reserved beds never imply completion', () => {
  for (const [kind, status] of [['LEAVE', 'APPROVED'], ['LEAVE', 'RETURN_PENDING'], ['REPAIR', 'AWAITING_CONFIRMATION'], ['HOUSING', 'AWAITING_CHECKIN'], ['RESERVATION', 'APPROVED'], ['RESERVATION', 'IN_USE']]) {
    assert.notEqual(serviceJourney(kind, status).tone, 'complete')
  }
  assert.match(serviceJourney('HOUSING', 'AWAITING_CHECKIN').note, /尚未完成入住/)
  assert.match(serviceJourney('LEAVE', 'RETURN_PENDING').note, /尚未确认销假/)
})
test('returned applications distinguish applicant supplementation from reviewer waiting', () => {
  for (const kind of ['LEAVE', 'CERTIFICATE', 'REPAIR', 'HOUSING', 'RESERVATION']) {
    assert.match(serviceJourney(kind, 'RETURNED').note, /补充资料和材料后重新提交/)
    assert.match(serviceJourney(kind, 'RETURNED', { reviewer: true }).note, /等待申请人/)
    assert.equal(serviceJourney(kind, 'RETURNED').tone, 'attention')
  }
})
test('closed and expired records do not mark all intermediate stages as successful', () => {
  for (const [kind, status] of [['LEAVE', 'REJECTED'], ['CERTIFICATE', 'WITHDRAWN'], ['REPAIR', 'CANCELLED'], ['HOUSING', 'EXPIRED'], ['RESERVATION', 'EXPIRED']]) {
    assert.equal(serviceJourney(kind, status).index, null)
    assert.equal(serviceJourney(kind, status).tone, 'closed')
  }
})
test('unknown statuses and inherited property names never manufacture a current stage', () => {
  for (const status of ['UNKNOWN', 'constructor', '__proto__', 'APPROVED']) {
    const result = serviceJourney('CERTIFICATE', status)
    assert.equal(result.index, null)
    assert.equal(result.tone, 'neutral')
    assert.equal(result.label, '状态待核对')
  }
})
test('pending cancellation remains distinct from cancellation and completed check-in', () => {
  const result = serviceJourney('HOUSING', 'AWAITING_CHECKIN', { cancellationPending: true })
  assert.equal(result.tone, 'attention')
  assert.equal(result.index, null)
  assert.equal(result.label, '取消待处理')
})
test('an empty selection does not create a draft; only an open editor marks preparation', () => {
  assert.equal(serviceJourney('LEAVE').index, null)
  assert.equal(serviceJourney('LEAVE').label, '办理说明')
  assert.equal(serviceJourney('LEAVE', undefined, { preparing: true }).index, 0)
  assert.equal(serviceJourney('LEAVE', undefined, { preparing: true }).label, '正在填写')
})
