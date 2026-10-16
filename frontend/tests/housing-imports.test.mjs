import test from 'node:test'
import assert from 'node:assert/strict'
import { decodeHousingImport, housingImportCanConfirm, housingImportFileIssue } from '../src/lib/housingImports.ts'

test('import file feedback handles UTF-8 bytes, empty input and limits', () => {
  assert.equal(housingImportFileIssue({ name: '入住.CSV', size: 102400 }), '')
  for (const file of [{ name: '入住.xlsx', size: 10 }, { name: '入住.csv', size: 0 }, { name: '入住.csv', size: 102401 }]) assert.ok(housingImportFileIssue(file))
  assert.equal(decodeHousingImport(new TextEncoder().encode('\uFEFF学号,房间编号')), '学号,房间编号')
  assert.throws(() => decodeHousingImport(new Uint8Array([0xff, 0xfe, 0x41])), /UTF-8/)
  assert.throws(() => decodeHousingImport(new Uint8Array(102401)), /100 KB/)
})
test('confirmation needs unchanged input, current building and unexpired server preview', () => {
  const now = Date.parse('2026-10-16T10:00:00+08:00')
  const batch = { status: 'PREVIEW', token: 'server-token', canConfirm: true, invalid: 0, total: 2, buildingId: 1, expiresAt: '2026-10-16T10:15:00' }
  assert.equal(housingImportCanConfirm(batch, 1, true, now), true)
  assert.equal(housingImportCanConfirm(batch, 2, true, now), false)
  assert.equal(housingImportCanConfirm(batch, 1, false, now), false)
  assert.equal(housingImportCanConfirm(batch, 1, true, now + 15 * 60000), false)
  for (const patch of [{ status: 'COMPLETED' }, { status: 'INVALID' }, { canConfirm: false }, { token: null }, { invalid: 1 }, { total: 0 }, { expiresAt: null }]) assert.equal(housingImportCanConfirm({ ...batch, ...patch }, 1, true, now), false)
})
