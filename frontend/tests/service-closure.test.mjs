import test from 'node:test'
import assert from 'node:assert/strict'
import { leaveStatuses } from '../src/leave.ts'
import { reservationStatuses, reservationActions } from '../src/reservations.ts'
import { affairStatus, affairAction } from '../src/lib/affairs.ts'

test('leave return and closure remain recognizable in the unified affairs list', () => {
  assert.equal(leaveStatuses.RETURN_PENDING, '待确认销假')
  assert.equal(affairStatus('LEAVE', 'RETURN_PENDING'), '待确认销假')
  assert.equal(affairAction('LEAVE', 'APPROVED'), '申报返校销假')
  assert.equal(affairAction('LEAVE', 'COMPLETED'), '查看销假记录')
})

test('reservation use, completion and expiry have domain-specific labels and actions', () => {
  assert.equal(reservationStatuses.IN_USE, '使用中')
  assert.equal(reservationStatuses.COMPLETED, '已结束核销')
  assert.equal(reservationStatuses.EXPIRED, '已过期未使用')
  assert.equal(reservationActions.START, '登记开始使用')
  assert.equal(reservationActions.COMPLETE, '确认使用结束')
  assert.equal(affairAction('RESERVATION', 'COMPLETED'), '查看使用记录')
})
