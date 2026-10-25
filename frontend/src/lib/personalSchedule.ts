export type ScheduleSource = 'LESSON' | 'EXAM' | 'LEAVE' | 'RESERVATION'
export interface ScheduleEvent {
  key: string; source: ScheduleSource; sourceId: number; title: string; startsAt: string; endsAt: string
  location: string; status: string; tentative: boolean; href: string; overlapKeys: string[]
}
export interface PersonalSchedule {
  from: string; to: string; timeZone: string; generatedAt: string
  availableSources: ScheduleSource[]; unavailableSources: ScheduleSource[]; events: ScheduleEvent[]
}
export const scheduleSources: Record<ScheduleSource, string> = { LESSON: '课程', EXAM: '考试', LEAVE: '请假', RESERVATION: '预约' }
export function shanghaiDay(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  return ['year', 'month', 'day'].map(type => parts.find(p => p.type === type)!.value).join('-')
}
function dayDate(day: string): Date {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error('日期格式不正确')
  const date = new Date(day + 'T12:00:00Z')
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== day) throw new Error('日期不存在')
  return date
}
export function addScheduleDays(day: string, count: number): string {
  const date = dayDate(day); date.setUTCDate(date.getUTCDate() + count); return date.toISOString().slice(0, 10)
}
export function weekOf(day: string): string { const date = dayDate(day); return addScheduleDays(day, -((date.getUTCDay() + 6) % 7)) }
export function safeScheduleDestination(event: Pick<ScheduleEvent, 'source' | 'sourceId' | 'href'>): string | null {
  if (!Number.isSafeInteger(event.sourceId) || event.sourceId < 1) return null
  const { href, source, sourceId } = event
  if (source === 'LEAVE') return href === `/portal/leave?id=${sourceId}&tab=details` ? href : null
  if (source === 'RESERVATION') return href === `/portal/reservations?id=${sourceId}&tab=details` ? href : null
  if (source === 'EXAM') return /^\/portal\/learning\/exams\?term=[1-9]\d*$/.test(href) ? href : null
  return source === 'LESSON' && /^\/portal\/learning\/timetable\?term=[1-9]\d*&week=\d{4}-\d{2}-\d{2}$/.test(href) ? href : null
}
export interface ScheduleSegment extends ScheduleEvent { startLabel: string; endLabel: string; crossDay: boolean }
export function splitScheduleDays(events: ScheduleEvent[], monday: string) {
  return Array.from({ length: 7 }, (_, n) => {
    const day = addScheduleDays(monday, n), next = addScheduleDays(day, 1)
    const start = Date.parse(day + 'T00:00:00Z'), end = Date.parse(next + 'T00:00:00Z')
    const segments: ScheduleSegment[] = events.filter(e => Date.parse(e.startsAt + 'Z') < end && Date.parse(e.endsAt + 'Z') > start).map(e => {
      const from = Date.parse(e.startsAt + 'Z'), to = Date.parse(e.endsAt + 'Z')
      return { ...e, startLabel: from <= start ? '00:00' : e.startsAt.slice(11, 16), endLabel: to >= end ? '24:00' : e.endsAt.slice(11, 16), crossDay: from < start || to > end }
    }).sort((a, b) => a.startLabel.localeCompare(b.startLabel) || a.endsAt.localeCompare(b.endsAt) || a.key.localeCompare(b.key))
    return { day, events: segments }
  })
}
