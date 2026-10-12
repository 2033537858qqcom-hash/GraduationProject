import test from 'node:test'
import assert from 'node:assert/strict'
import { repairDraft, repairForm, repairOperation, repairSnapshot } from '../src/repairs.ts'

test('an unselected or malformed location remains absent instead of becoming an invented zero id', () => {
  for (const value of ['', '0', '-1', 'nan', '0.4']) assert.equal(repairDraft({ ...repairForm(), locationId: value }).locationId, null)
  assert.equal(repairDraft({ ...repairForm(), locationId: '25' }).locationId, 25)
})
test('resuming descriptive fields does not modify the original request or restore workflow authority', () => {
  const source = { locationId: 5, category: 'NETWORK', title: '故障', description: '现象', contact: 'local@example.test', status: 'COMPLETED', allowedActions: ['confirm'] }
  const form = repairForm(source)
  form.description = '更新后的现象'
  assert.equal(source.description, '现象')
  assert.equal(form.contact, 'local@example.test')
  assert.equal('status' in form, false)
  assert.equal('allowedActions' in form, false)
})
test('a failed request retry retains its key and a changed version or payload receives another key', () => {
  const key = repairOperation(), payload = { version: 1, comment: '本人确认' }
  const first = key('/repairs/mine/2/confirm', payload)
  assert.equal(key('/repairs/mine/2/confirm', { ...payload }), first)
  assert.notEqual(key('/repairs/mine/2/confirm', { ...payload, version: 2 }), first)
  assert.notEqual(key('/repairs/mine/2/reopen', payload), first)
})
test('a malformed history snapshot cannot introduce arbitrary attachment ids or shapes', () => {
  for (const value of ['invalid', 'null', '[]', '5']) assert.equal(repairSnapshot(value), null)
  const result = repairSnapshot(JSON.stringify({ title: 5, description: '<script>text</script>', images: [{ id: 0, name: 'invalid' }, { id: '5', name: 'invalid' }, { id: 5, name: 'historic.png' }], allowedActions: ['confirm'] }))
  assert.equal(result.title, '')
  assert.equal(result.description, '<script>text</script>')
  assert.deepEqual(result.images.map(image => image.id), [5])
  assert.equal('allowedActions' in result, false)
})
test('historical private text stays distinct from the current editable form', () => {
  const snapshot = repairSnapshot(JSON.stringify({ title: '旧版', description: '首次故障', contact: 'previous@example.test', locationDetail: '二楼', images: [] }))
  const form = repairForm()
  form.title = '新版'
  assert.equal(snapshot.title, '旧版')
  assert.equal(snapshot.contact, 'previous@example.test')
  assert.equal(form.contact, '')
})
