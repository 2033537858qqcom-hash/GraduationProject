import test from 'node:test'
import assert from 'node:assert/strict'
import { weekOf, addScheduleDays, splitScheduleDays, safeScheduleDestination, shanghaiDay } from '../src/lib/personalSchedule.ts'
const event = (start, end, extra={}) => ({ key:'LEAVE:1',source:'LEAVE',sourceId:1,title:'已批准请假',startsAt:start,endsAt:end,location:'离校期间',status:'APPROVED',tentative:false,href:'/portal/leave?id=1&tab=details',overlapKeys:[],...extra })
test('week navigation is Monday based across year, leap day and Sunday', () => {
 assert.equal(weekOf('2026-10-25'),'2026-10-19')
 assert.equal(weekOf('2027-01-01'),'2026-12-28')
 assert.equal(addScheduleDays('2028-02-28',1),'2028-02-29')
 assert.throws(() => weekOf('2026-02-30'))
 assert.equal(shanghaiDay(new Date('2026-10-24T17:00:00Z')),'2026-10-25')
})
test('cross-day events use exclusive midnight and preserve actual source timing', () => {
 const days=splitScheduleDays([event('2026-10-18T20:00:00','2026-10-21T00:00:00')],'2026-10-19')
 assert.equal(days.length,7)
 assert.equal(days[0].events.length,1);assert.equal(days[0].events[0].startLabel,'00:00');assert.equal(days[0].events[0].endLabel,'24:00')
 assert.equal(days[1].events.length,1);assert.equal(days[2].events.length,0)
 assert.equal(days.flatMap(d=>d.events).length,2)
})
test('events are sorted and outside-week events are excluded', () => {
 const events=[event('2026-10-19T11:00:00','2026-10-19T12:00:00'),event('2026-10-19T09:00:00','2026-10-19T10:00:00',{key:'LESSON:2'}),event('2026-11-01T09:00:00','2026-11-01T10:00:00')]
 assert.deepEqual(splitScheduleDays(events,'2026-10-19')[0].events.map(e=>e.key),['LESSON:2','LEAVE:1'])
})
test('deep links return to the correct source and reject injected or mismatched targets', () => {
 assert.equal(safeScheduleDestination(event('','')), '/portal/leave?id=1&tab=details')
 assert.equal(safeScheduleDestination(event('','',{source:'EXAM',href:'/portal/learning/exams?term=1'})), '/portal/learning/exams?term=1')
 for (const href of ['javascript:alert(1)','https://evil.test','//evil.test','/admin/foundation','/portal/reservations?id=1&tab=details','/portal/leave?id=2&tab=details']) assert.equal(safeScheduleDestination(event('','',{href})),null)
 assert.equal(safeScheduleDestination(event('','',{source:'LESSON',href:'/portal/learning/timetable?term=1&week=2026-10-19'})), '/portal/learning/timetable?term=1&week=2026-10-19')
})
