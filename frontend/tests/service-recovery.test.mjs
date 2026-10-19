import test from 'node:test'
import assert from 'node:assert/strict'
import { safeRecoveryDestination } from '../src/lib/navigation.ts'

test('reconnect preserves the original business detail, filters and login destination', () => {
  for (const path of ['/repairs/mine?id=2&tab=history', '/logistics/repairs/dispatch?status=ASSIGNED&page=2', '/login?redirect=%2Frepairs%2Fmine', '/portal', '/']) {
    assert.equal(safeRecoveryDestination(path), path)
  }
})

test('reconnect rejects external targets and recovery loops', () => {
  for (const path of ['https://example.test', '//example.test', '/\\example.test', '/unavailable', '/unavailable?redirect=/portal', '/forbidden', '/login/../unavailable', '/login\n?redirect=/portal', '/admin/unknown', ['/portal'], null]) {
    assert.equal(safeRecoveryDestination(path), null)
  }
})
