import type { CheckConflictCommand, ConflictResult } from '../reservations'

export interface ReservationSearchSelection { venueId: number; startsAt: string; endsAt: string; participants: number }
function validMinute(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return false
  const date = new Date(value + 'Z')
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 16) === value
}
export function reservationSearchSelection(value: string): ReservationSearchSelection | null {
  if (!value.startsWith('/portal/reservations?') || /[\\\r\n]/.test(value)) return null
  const url = new URL(value, 'https://campus.invalid')
  const fields = ['resource', 'starts', 'ends', 'people']
  if (url.hash || [...url.searchParams.keys()].some(key => !fields.includes(key)) || fields.some(key => url.searchParams.getAll(key).length !== 1)) return null
  const id = url.searchParams.get('resource')!, startsAt = url.searchParams.get('starts')!, endsAt = url.searchParams.get('ends')!, people = url.searchParams.get('people')!
  if (!/^[1-9]\d{0,14}$/.test(id) || !/^[1-9]\d{0,4}$/.test(people) || Number(people) > 10000 || !validMinute(startsAt) || !validMinute(endsAt)) return null
  const duration = (Date.parse(endsAt + 'Z') - Date.parse(startsAt + 'Z')) / 60000
  if (duration < 30 || duration > 840) return null
  return { venueId: Number(id), startsAt, endsAt, participants: Number(people) }
}
export interface ReservationCheckState { checking: boolean; result: ConflictResult | null }
/** Only the newest form snapshot can publish a result; failed checks never retain a success. */
export function createReservationConflictChecker(check: (command: CheckConflictCommand) => Promise<ConflictResult>, publish: (state: ReservationCheckState) => void) {
  let revision = 0
  return async (command: CheckConflictCommand | null) => {
    const request = ++revision
    if (!command) { publish({ checking: false, result: null }); return }
    if (command.startsAt >= command.endsAt) { publish({ checking: false, result: { hasConflict: true, message: '预约结束时间必须晚于开始时间', conflictingSlots: [] } }); return }
    publish({ checking: true, result: null })
    try {
      const result = await check(command)
      if (request === revision) publish({ checking: false, result })
    } catch {
      if (request === revision) publish({ checking: false, result: { hasConflict: true, message: '暂时无法核对时段，请稍后重试，核对成功前不能正式提交。', conflictingSlots: [] } })
    }
  }
}
