import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formatDate,
  formatDateTime,
  formatDateTimeSeconds,
  formatMoney,
  formatShortDateTime,
  shanghaiToday,
} from '../src/lib/format.ts'
import { buildRepairStepperOverview, buildRepairWorkflowSteps } from '../src/lib/repairWorkflowSteps.ts'
import { currentUser } from '../src/stores/auth.ts'
import {
  decrementUnreadNoticeCount,
  refreshUnreadNoticeCount,
  setUnreadNoticeCount,
  unreadNoticeCount,
} from '../src/stores/notices.ts'

test('date and money formatters normalize ISO timestamps and fallbacks cleanly', () => {
  const iso = '2026-03-15T14:30:45.000Z'
  assert.equal(formatDateTime(iso), '2026-03-15 14:30')
  assert.equal(formatDateTime(null, '尚未发生'), '尚未发生')
  assert.equal(formatDateTimeSeconds(iso), '2026-03-15 14:30:45')
  assert.equal(formatDateTimeSeconds(undefined, '-'), '-')
  assert.equal(formatShortDateTime(iso), '03-15 14:30')
  assert.equal(formatShortDateTime('', '—'), '—')
  assert.equal(formatDate(iso), '2026-03-15')
  assert.equal(formatDate(iso, '/'), '2026/03/15')
  assert.equal(formatDate('', '-', '未设置'), '未设置')
  assert.match(shanghaiToday(new Date('2026-03-15T20:00:00Z')), /^2026-03-16$/)
  assert.equal(formatMoney(0), '¥0.00')
  assert.equal(formatMoney(1250), '¥12.50')
  assert.equal(formatMoney(99), '¥0.99')
})

test('notice store clamps unread count, decrements safely, and refreshes from /api/notices', async () => {
  const previousUser = currentUser.value
  const previousFetch = globalThis.fetch
  try {
    setUnreadNoticeCount(4.8)
    assert.equal(unreadNoticeCount.value, 4)
    decrementUnreadNoticeCount()
    assert.equal(unreadNoticeCount.value, 3)
    setUnreadNoticeCount(-5)
    assert.equal(unreadNoticeCount.value, 0)
    decrementUnreadNoticeCount()
    assert.equal(unreadNoticeCount.value, 0)

    currentUser.value = null
    setUnreadNoticeCount(2)
    await refreshUnreadNoticeCount()
    assert.equal(unreadNoticeCount.value, 0)

    currentUser.value = { id: 1 }
    globalThis.fetch = async url => {
      assert.ok(String(url).includes('/api/notices?page=1&pageSize=1'))
      return Response.json({ unreadCount: 6 })
    }
    await refreshUnreadNoticeCount()
    assert.equal(unreadNoticeCount.value, 6)

    globalThis.fetch = async () => {
      throw new Error('offline')
    }
    await refreshUnreadNoticeCount()
    assert.equal(unreadNoticeCount.value, 6)
  } finally {
    currentUser.value = previousUser
    globalThis.fetch = previousFetch
    setUnreadNoticeCount(0)
  }
})

test('buildRepairWorkflowSteps and buildRepairStepperOverview reflect work order lifecycle states', () => {
  assert.deepEqual(buildRepairWorkflowSteps(null), [])
  assert.equal(buildRepairStepperOverview(null, 'mine').class, 'info')

  const awaitingOrder = {
    id: 12,
    status: 'AWAITING_CONFIRMATION',
    createdAt: '2026-03-15T08:00:00',
    submittedAt: '2026-03-15T08:05:00',
    technicianName: '王师傅',
    completionRound: 2,
    confirmationOverdue: true,
    assignments: [{ assignedAt: '2026-03-15T08:30:00' }],
    completions: [{ createdAt: '2026-03-15T10:15:00' }],
    cancellation: null,
  }
  const steps = buildRepairWorkflowSteps(awaitingOrder)
  assert.equal(steps.length, 4)
  assert.equal(steps[0].state, 'completed')
  assert.equal(steps[1].subtitle, '已指派 王师傅')
  assert.equal(steps[2].subtitle, '维修完成 (第2轮)')
  assert.equal(steps[3].state, 'active')
  assert.equal(steps[3].subtitle, '待你验收 · 演示提醒')

  const overview = buildRepairStepperOverview(awaitingOrder, 'mine')
  assert.equal(overview.class, 'highlight')
  assert.equal(overview.text, '维修结果已提交，等待你验收')

  const cancelling = { ...awaitingOrder, cancellation: { status: 'PENDING' } }
  assert.equal(buildRepairStepperOverview(cancelling, 'mine').class, 'warning')
})
