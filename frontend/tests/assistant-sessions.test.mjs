import test from 'node:test'
import assert from 'node:assert/strict'
import { latestAssistantTask, mergeAssistantTurns, restoredTurnLabel } from '../src/lib/assistantSessions.ts'

test('latest task is chosen by kind and ID without mutating server ordering', () => {
  const tasks = [{ id: 2, kind: 'LEAVE' }, { id: 7, kind: 'CERTIFICATE' }, { id: 4, kind: 'LEAVE' }]
  assert.equal(latestAssistantTask(tasks, 'LEAVE'), 4)
  assert.equal(latestAssistantTask(tasks, 'CERTIFICATE'), 7)
  assert.deepEqual(tasks.map(x => x.id), [2, 7, 4])
})
test('accounts with no authorized matching task do not get an invented ID', () => {
  assert.equal(latestAssistantTask([], 'LEAVE'), null)
  assert.equal(latestAssistantTask([{ id: 3, kind: 'LEAVE' }], 'CERTIFICATE'), null)
  assert.equal(latestAssistantTask([{ id: 9, kind: 'REPAIR' }, { id: 3, kind: 'LEAVE' }], 'REPAIR'), 9)
})
test('recovered and live turns merge without duplicating the same request', () => {
  assert.deepEqual(mergeAssistantTurns([{ id: 1, status: 'RUNNING' }, { id: 3, status: 'SUCCESS' }], [{ id: 1, status: 'SUCCESS' }, { id: 2, status: 'FAILED' }]),
    [{ id: 1, status: 'SUCCESS' }, { id: 2, status: 'FAILED' }, { id: 3, status: 'SUCCESS' }])
})
test('restored history clearly requires a new query and does not claim stale facts', () => {
  assert.match(restoredTurnLabel('SUCCESS'), /结果需重新查询/)
  assert.match(restoredTurnLabel('RUNNING'), /仍在处理/)
  for (const status of ['FAILED', 'INTERRUPTED']) assert.match(restoredTurnLabel(status), /可手动重试/)
})
