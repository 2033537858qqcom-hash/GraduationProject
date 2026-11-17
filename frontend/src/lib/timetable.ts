import type { TeachingLesson } from '../types'

export type TimetablePeriodKey = 'morning' | 'afternoon' | 'evening'

export interface TimetablePeriod {
  key: TimetablePeriodKey
  label: string
  range: string
}

export const TIMETABLE_PERIODS: readonly TimetablePeriod[] = [
  { key: 'morning', label: '上午', range: '08:00 — 12:00' },
  { key: 'afternoon', label: '下午', range: '12:00 — 18:00' },
  { key: 'evening', label: '晚上', range: '18:00 — 22:00' },
]

export const TIMETABLE_WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const

export function timetablePeriodOf(startsAt: string): TimetablePeriodKey {
  const hour = Number(startsAt.slice(11, 13))
  if (Number.isNaN(hour) || hour < 12) return 'morning'
  if (hour < 18) return 'afternoon'
  return 'evening'
}

export function lessonsInSlot(lessons: readonly TeachingLesson[], day: string, period: TimetablePeriodKey) {
  return lessons
    .filter(lesson => lesson.startsAt.slice(0, 10) === day && timetablePeriodOf(lesson.startsAt) === period)
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.id - b.id)
}
