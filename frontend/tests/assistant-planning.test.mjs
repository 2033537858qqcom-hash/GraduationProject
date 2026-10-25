import test from 'node:test'
import assert from 'node:assert/strict'
import { assistantContext, assistantContextLabel, assistantSuggestions, safeAssistantDestination } from '../src/lib/assistant.ts'
import { reservationSearchSelection } from '../src/lib/reservationSearch.ts'
const path='/portal/reservations?resource=12&starts=2029-04-02T12:00&ends=2029-04-02T14:00&people=20'
test('assistant venue conditions remain a safe business deep link and preserve explicit fields',()=>{
 assert.equal(safeAssistantDestination(path),path)
 assert.deepEqual(reservationSearchSelection(path),{venueId:12,startsAt:'2029-04-02T12:00',endsAt:'2029-04-02T14:00',participants:20})
})
test('invalid dates, duplicate fields, external links and injected arguments cannot prefill a booking',()=>{
 for(const value of ['https://evil.test'+path,path+'&userId=1',path+'&resource=13',path.replace('2029-04-02T12:00','2029-02-30T12:00'),path.replace('people=20','people=0'),path.replace('14:00','11:00'),path.replace('resource=12','resource=NaN')]){assert.equal(reservationSearchSelection(value),null);assert.equal(safeAssistantDestination(value),null)}
})
test('schedule links and booking context follow their actual permission capabilities',()=>{
 assert.equal(safeAssistantDestination('/portal/schedule?week=2026-10-26&day=2026-10-26'),'/portal/schedule?week=2026-10-26&day=2026-10-26')
 assert.equal(safeAssistantDestination('/portal/schedule?week=2026-10-26&userId=2'),null)
 assert.deepEqual(assistantContext('/portal/reservations','8'),{type:'RESERVATION_MINE',id:8})
 assert.match(assistantContextLabel({type:'RESERVATION_MINE',id:8}),/预约.*8/)
 assert.deepEqual(assistantSuggestions('/portal/schedule',['my_schedule'],null),['本周日程','下周日程'])
 assert.deepEqual(assistantSuggestions('/portal/reservations',['my_reservations','find_available_venues'],null),['我的预约进度','查找空闲场地'])
})
