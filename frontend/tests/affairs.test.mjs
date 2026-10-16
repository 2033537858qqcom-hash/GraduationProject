import test from 'node:test'
import assert from 'node:assert/strict'
import { affairStatuses, affairStatus, affairAction } from '../src/lib/affairs.ts'
import { safeAffairDestination } from '../src/lib/navigation.ts'

test('affairs preserve each business meaning for shared status codes', () => {
  assert.equal(affairStatus('HOUSING', 'SUBMITTED'), '待初审')
  assert.equal(affairStatus('LEAVE', 'SUBMITTED'), '待审核')
  assert.equal(affairStatus('REPAIR', 'SUBMITTED'), '待后勤分诊')
  assert.equal(affairStatuses('').SUBMITTED, '待处理')
  assert.equal(affairStatus('HOUSING', 'AWAITING_CHECKIN'), '待入住')
  assert.equal(affairStatus('HOUSING', 'EXPIRED'), '已过期')
  assert.equal(affairStatus('HOUSING', 'FUTURE_STATUS'), 'FUTURE_STATUS')
  assert.equal(affairStatus('RESERVATION', 'APPROVED'), '已同意预约')
  assert.equal(affairStatus('RESERVATION', 'RETURNED'), '待补充材料')
  assert.equal(affairStatuses('LEAVE').AWAITING_CHECKIN, undefined)
})
test('completion links describe the actual business result', () => {
  assert.equal(affairAction('HOUSING', 'COMPLETED'), '查看调宿结果')
  assert.equal(affairAction('REPAIR', 'COMPLETED'), '查看报修结果')
  assert.equal(affairAction('CERTIFICATE', 'COMPLETED'), '查看证明结果')
  assert.equal(affairAction('RESERVATION', 'APPROVED'), '查看预约凭证')
  assert.equal(affairAction('RESERVATION', 'RETURNED'), '修改预约材料')
  assert.equal(affairAction('HOUSING', 'DRAFT'), '继续办理')
  assert.equal(affairAction('HOUSING', 'RETURNED'), '查看补交要求')
})
test('housing notifications allow only the three fixed detail destinations', () => {
  for (const path of ['/housing/transfer', '/student-affairs/housing', '/housing/assignments', '/portal/reservations', '/student-affairs/reservations']) {
    assert.equal(safeAffairDestination(path + '?id=18&tab=details'), path + '?id=18&tab=details')
    for (const query of ['?id=0&tab=details', '?id=-1&tab=details', '?id=18&tab=details&action=check-in', '?id=18', '?id=18&tab=history']) assert.equal(safeAffairDestination(path + query), null)
  }
  assert.equal(safeAffairDestination('/housing/mine?id=18&tab=details'), null)
})
