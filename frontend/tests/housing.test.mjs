import test from 'node:test'
import assert from 'node:assert/strict'
import { bedCanAssign, housingBedStatus, housingRequestKey, housingWhen } from '../src/housing.ts'

test('only an open vacant bed can be assigned', () => {
  assert.equal(bedCanAssign({ enabled: true, status: 'VACANT', buildingEnabled: true, roomEnabled: true }), true)
  for (const bed of [
    { enabled: false, status: 'VACANT', buildingEnabled: true, roomEnabled: true },
    { enabled: true, status: 'OCCUPIED', buildingEnabled: true, roomEnabled: true },
    { enabled: true, status: 'MAINTENANCE', buildingEnabled: true, roomEnabled: true },
    { enabled: true, status: 'RESERVED', buildingEnabled: true, roomEnabled: true },
    { enabled: true, status: 'VACANT', buildingEnabled: false, roomEnabled: true },
    { enabled: true, status: 'VACANT', buildingEnabled: true, roomEnabled: false },
  ]) assert.equal(bedCanAssign(bed), false)
})
test('bed status and stay time stay readable', () => {
  assert.equal(housingBedStatus.OCCUPIED, '已入住')
  assert.equal(housingWhen('2026-10-13T08:30:00'), '2026-10-13 08:30')
  assert.equal(housingWhen(null), '')
  assert.match(housingRequestKey(), /^HK[a-f0-9]{32}$/)
})
