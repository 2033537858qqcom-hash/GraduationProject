import test from 'node:test'
import assert from 'node:assert/strict'
import { classroomOptions, occupancyStatus, reservationStatuses } from '../src/reservations.ts'
test('only same-campus classrooms are selectable, preserving the current disabled link', () => {
 const rooms = [ { id: 1, campusId: 1, name: '甲', buildingName: '楼', roomNumber: '101', capacity: 30, enabled: true }, { id: 2, campusId: 2, name: '乙', buildingName: '楼', roomNumber: '102', capacity: 20, enabled: true }, { id: 3, campusId: 1, name: '丙', buildingName: '楼', roomNumber: '103', capacity: 10, enabled: false } ]
 assert.deepEqual(classroomOptions(rooms, 1).map(r => [r.value, !!r.disabled]), [[0,false],[1,false],[3,true]])
 assert.equal(classroomOptions(rooms, 1, 3).find(r => r.value === 3).disabled, false)
 assert.match(classroomOptions(rooms, 1)[1].label, /101.*30/)
})
test('teaching labels do not become booking lifecycle filter options', () => {
 assert.equal(occupancyStatus('LESSON'), '教学课程')
 assert.equal(occupancyStatus('EXAM'), '已发布考试')
 assert.equal(occupancyStatus('IN_USE'), '使用中')
 assert.equal(reservationStatuses.LESSON, undefined)
})
