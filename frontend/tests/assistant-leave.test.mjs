import test from 'node:test'
import assert from 'node:assert/strict'
import { canSaveLeavePreview, emptyLeavePreview, leaveDraftBody, leavePreviewFingerprint } from '../src/lib/assistantLeave.ts'

const complete = () => ({ reasonType: 'PERSONAL', reason: '演示事由', contact: 'demo@example.test', startDate: '2026-12-01', startTime: '09:00', endDate: '2026-12-01', endTime: '17:00' })
const checked = fields => ({ ready: true, fields: { ...fields } })
test('missing data, unconfirmed previews and in-flight writes cannot be saved', () => {
  const fields = complete()
  assert.equal(canSaveLeavePreview(null, fields, true, false), false)
  assert.equal(canSaveLeavePreview({ ...checked(fields), ready: false }, fields, true, false), false)
  assert.equal(canSaveLeavePreview(checked(fields), fields, false, false), false)
  assert.equal(canSaveLeavePreview(checked(fields), fields, true, true), false)
  assert.equal(canSaveLeavePreview(checked(fields), fields, true, false), true)
})
test('changing every collected field invalidates a previously checked preview', () => {
  const fields = complete(), preview = checked(fields)
  for (const field of Object.keys(fields)) assert.equal(canSaveLeavePreview(preview, { ...fields, [field]: fields[field] + 'x' }, true, false), false, field)
})
test('draft serialization preserves reviewed local dates and excludes actors, approval and files', () => {
  const body = leaveDraftBody(complete(), 'request-key-123')
  assert.deepEqual(body, { requestKey: 'request-key-123', reasonType: 'PERSONAL', reason: '演示事由', contact: 'demo@example.test', startsAt: '2026-12-01T09:00:00', endsAt: '2026-12-01T17:00:00', version: 0 })
  for (const field of ['studentId', 'status', 'action', 'fileId', 'identityNumber']) assert.equal(field in body, false)
})
test('clearing a preview does not invent defaults or reuse another form object', () => {
  const first = emptyLeavePreview(), second = emptyLeavePreview()
  first.startTime = '09:00'
  assert.equal(second.startTime, '')
  assert.ok(Object.values(second).every(value => value === ''))
})
test('fingerprint and saved text use the same trimming rules', () => {
  const fields = complete(), spaced = { ...fields, reason: ' 演示事由 ', contact: ' demo@example.test ' }
  assert.equal(leavePreviewFingerprint(fields), leavePreviewFingerprint(spaced))
  assert.deepEqual(leaveDraftBody(fields, 'same-key'), leaveDraftBody(spaced, 'same-key'))
})
