import test from 'node:test'
import assert from 'node:assert/strict'
import {
  reservationStatuses,
  reservationActions,
  activityTypes,
  reservationPassUrl,
} from '../src/reservations.ts'
import { safeLoginDestination, safeAffairDestination } from '../src/lib/navigation.ts'
import { affairTimelinePath } from '../src/lib/affairTimeline.ts'
import { affairStatus, affairAction } from '../src/lib/affairs.ts'

test('pass downloads and inline preview use the authenticated PDF endpoint', () => {
  assert.equal(reservationPassUrl(12), '/api/reservations/12/pass')
  assert.equal(reservationPassUrl(12, true), '/api/reservations/12/pass?inline=true')
  for (const id of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => reservationPassUrl(id))
})

test('reservation status codes map to friendly localized labels', () => {
  assert.equal(reservationStatuses.DRAFT, '未提交草稿')
  assert.equal(reservationStatuses.SUBMITTED, '待审核')
  assert.equal(reservationStatuses.RETURNED, '待补充材料')
  assert.equal(reservationStatuses.APPROVED, '已同意预约')
  assert.equal(reservationStatuses.REJECTED, '已驳回')
  assert.equal(reservationStatuses.CANCELLED, '已取消')
  assert.equal(reservationStatuses.WITHDRAWN, '已撤回')
})

test('reservation actions map correctly', () => {
  assert.equal(reservationActions.APPROVE, '审批通过')
  assert.equal(reservationActions.RETURN, '退回修改')
  assert.equal(reservationActions.REJECT, '驳回借用')
  assert.equal(reservationActions.CANCEL, '取消预约')
  assert.equal(reservationActions.WITHDRAW, '撤回申请')
})

test('activity types contain academic, sports and cultural', () => {
  assert.equal(activityTypes.ACADEMIC, '学术研讨/讲座')
  assert.equal(activityTypes.SPORTS, '体育赛事/锻炼')
  assert.equal(activityTypes.CULTURAL, '文艺展演/排练')
  assert.equal(activityTypes.CLASS_MEETING, '班级会议/团日活动')
})

test('affair unified mapping handles RESERVATION properly', () => {
  assert.equal(affairStatus('RESERVATION', 'APPROVED'), '已同意预约')
  assert.equal(affairStatus('RESERVATION', 'RETURNED'), '待补充材料')
  assert.equal(affairAction('RESERVATION', 'APPROVED'), '查看预约凭证')
  assert.equal(affairAction('RESERVATION', 'RETURNED'), '修改预约材料')
  assert.equal(affairAction('RESERVATION', 'SUBMITTED'), '查看办理详情')
})

test('reservation affair timeline path is strictly safe', () => {
  assert.equal(
    affairTimelinePath('RESERVATION', 12, 1),
    '/affairs/mine/RESERVATION/12/timeline?page=1&pageSize=10',
  )
  assert.equal(
    affairTimelinePath('RESERVATION', 12, 2, 3),
    '/affairs/mine/RESERVATION/12/timeline?page=2&pageSize=10&expectedVersion=3',
  )
})

test('navigation safe destination accepts portal and student-affairs reservation paths', () => {
  assert.equal(safeLoginDestination('/portal/reservations'), '/portal/reservations')
  assert.equal(safeLoginDestination('/student-affairs/reservations'), '/student-affairs/reservations')
  assert.equal(safeAffairDestination('/portal/reservations?id=10&tab=details'), '/portal/reservations?id=10&tab=details')
  assert.equal(safeAffairDestination('/student-affairs/reservations?id=10&tab=details'), '/student-affairs/reservations?id=10&tab=details')
})
