import test from 'node:test'
import assert from 'node:assert/strict'
import { TIMETABLE_PERIODS, lessonsInSlot, timetablePeriodOf } from '../src/lib/timetable.ts'

test('timetable periods split lessons across morning, afternoon, and evening in chronological order', () => {
  assert.deepEqual(
    TIMETABLE_PERIODS.map(p => p.key),
    ['morning', 'afternoon', 'evening'],
  )

  assert.equal(timetablePeriodOf('2026-11-24T08:00:00'), 'morning')
  assert.equal(timetablePeriodOf('2026-11-24T11:55:00'), 'morning')
  assert.equal(timetablePeriodOf('2026-11-24T12:00:00'), 'afternoon')
  assert.equal(timetablePeriodOf('2026-11-24T17:30:00'), 'afternoon')
  assert.equal(timetablePeriodOf('2026-11-24T18:00:00'), 'evening')
  assert.equal(timetablePeriodOf('2026-11-24T20:10:00'), 'evening')

  const sampleLessons = [
    {
      id: 4,
      classId: 1,
      className: '软件工程 01',
      courseName: '计算机视觉导论',
      teacherName: '陈老师',
      classroom: 'C-105',
      campusName: '东校区',
      startsAt: '2026-11-24T19:00:00',
      endsAt: '2026-11-24T20:40:00',
    },
    {
      id: 2,
      classId: 2,
      className: '软件工程 01',
      courseName: '数据库原理',
      teacherName: '赵老师',
      classroom: 'A-202',
      campusName: '东校区',
      startsAt: '2026-11-24T10:00:00',
      endsAt: '2026-11-24T11:40:00',
    },
    {
      id: 1,
      classId: 3,
      className: '软件工程 01',
      courseName: '数据结构与算法',
      teacherName: '李老师',
      classroom: 'A-101',
      campusName: '东校区',
      startsAt: '2026-11-24T08:00:00',
      endsAt: '2026-11-24T09:40:00',
    },
    {
      id: 3,
      classId: 4,
      className: '软件工程 01',
      courseName: '软件工程',
      teacherName: '王老师',
      classroom: 'B-301',
      campusName: '东校区',
      startsAt: '2026-11-24T14:00:00',
      endsAt: '2026-11-24T15:40:00',
    },
  ]

  assert.deepEqual(
    lessonsInSlot(sampleLessons, '2026-11-24', 'morning').map(l => l.courseName),
    ['数据结构与算法', '数据库原理'],
  )
  assert.deepEqual(
    lessonsInSlot(sampleLessons, '2026-11-24', 'afternoon').map(l => l.courseName),
    ['软件工程'],
  )
  assert.deepEqual(
    lessonsInSlot(sampleLessons, '2026-11-24', 'evening').map(l => l.courseName),
    ['计算机视觉导论'],
  )
  assert.deepEqual(lessonsInSlot(sampleLessons, '2026-11-25', 'evening'), [])
})
