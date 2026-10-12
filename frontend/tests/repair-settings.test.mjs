import test from 'node:test'
import assert from 'node:assert/strict'
import { repairPeopleForCapability, repairSourceIds } from '../src/repairSettings.ts'

test('dispatch selectors exclude inactive and technician-only accounts', () => {
  const people = [
    { id: 1, enabled: true, capabilities: ['DISPATCH'] }, { id: 2, enabled: true, capabilities: ['WORK'] },
    { id: 3, enabled: false, capabilities: ['DISPATCH'] }, { id: 4, enabled: true, capabilities: ['DISPATCH', 'WORK'] },
  ]
  assert.deepEqual(repairPeopleForCapability(people, 'DISPATCH').map(p => p.id), [1, 4])
  assert.deepEqual(repairPeopleForCapability(people, 'WORK').map(p => p.id), [2, 4])
})
test('changing to a public area cannot submit stale classroom or building references', () => {
  assert.deepEqual(repairSourceIds('CAMPUS_PUBLIC', '12', '5'), { classroomResourceId: null, buildingId: null })
})
test('building-common locations cannot carry a hidden classroom reference', () => {
  assert.deepEqual(repairSourceIds('BUILDING_COMMON', '12', '5'), { classroomResourceId: null, buildingId: 5 })
})
test('classroom source is explicit and an unselected optional building remains null', () => {
  assert.deepEqual(repairSourceIds('CLASSROOM', '12', ''), { classroomResourceId: 12, buildingId: null })
})
