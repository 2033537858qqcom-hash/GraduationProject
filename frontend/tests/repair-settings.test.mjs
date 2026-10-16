import test from 'node:test'
import assert from 'node:assert/strict'
import { repairPeopleForCapability, repairSourceIds, repairLocationQuery } from '../src/repairSettings.ts'

test('dispatch selectors exclude inactive and technician-only accounts', () => {
  const people = [
    { id: 1, enabled: true, capabilities: ['DISPATCH'] }, { id: 2, enabled: true, capabilities: ['WORK'] },
    { id: 3, enabled: false, capabilities: ['DISPATCH'] }, { id: 4, enabled: true, capabilities: ['DISPATCH', 'WORK'] },
  ]
  assert.deepEqual(repairPeopleForCapability(people, 'DISPATCH').map(p => p.id), [1, 4])
  assert.deepEqual(repairPeopleForCapability(people, 'WORK').map(p => p.id), [2, 4])
})
test('changing to a public area cannot submit stale classroom or building references', () => {
  assert.deepEqual(repairSourceIds('CAMPUS_PUBLIC', '12', '5'), { classroomResourceId: null, buildingId: null, dormRoomId: null })
})
test('building-common locations cannot carry a hidden classroom reference', () => {
  assert.deepEqual(repairSourceIds('BUILDING_COMMON', '12', '5'), { classroomResourceId: null, buildingId: 5, dormRoomId: null })
})
test('classroom source is explicit and an unselected optional building remains null', () => {
  assert.deepEqual(repairSourceIds('CLASSROOM', '12', ''), { classroomResourceId: 12, buildingId: null, dormRoomId: null })
})

test('dorm sources clear unrelated ids and other kinds clear stale dorm sources', () => {
  assert.deepEqual(repairSourceIds('DORM_ROOM', '12', '5', '9'), { classroomResourceId: null, buildingId: null, dormRoomId: 9 })
  assert.equal(repairSourceIds('CAMPUS_PUBLIC', '', '', '9').dormRoomId, null)
  assert.equal(repairSourceIds('CLASSROOM', '12', '', '9').dormRoomId, null)
})
test('prefill accepts only an exact positive safe integer before server verification', () => {
  assert.equal(repairLocationQuery('125'), 125)
  for (const value of [undefined, null, ['1'], '0', '-1', '1.0', '1e2', '12x', ' 12', '9007199254740992']) assert.equal(repairLocationQuery(value), null)
})
