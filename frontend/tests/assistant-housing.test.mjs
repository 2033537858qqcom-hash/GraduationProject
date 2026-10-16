import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyHousingPreview, housingPreviewFingerprint, sameHousingStay } from '../src/lib/assistantHousing.ts'
import { assistantContext, assistantContextLabel, assistantSuggestions, safeAssistantDestination } from '../src/lib/assistant.ts'
import { latestAssistantTask } from '../src/lib/assistantSessions.ts'

test('housing changes invalidate previously reviewed fields without storing occupancy in the form', () => {
  const fields = { reason: '安静环境', preferredArea: '东校区', materialNote: '已准备说明' }
  assert.notEqual(housingPreviewFingerprint(fields), housingPreviewFingerprint({ ...fields, reason: '低楼层' }))
  assert.deepEqual(Object.keys(emptyHousingPreview()), ['reason', 'preferredArea', 'materialNote'])
})
test('a new stay or changed displayed place cannot reuse old occupancy confirmation', () => {
  assert.equal(sameHousingStay({ stayToken: 'old', currentPlace: '101' }, { stayToken: 'old', currentPlace: '101' }), true)
  for (const fresh of [{ stayToken: 'new', currentPlace: '101' }, { stayToken: 'old', currentPlace: '102' }, { stayToken: null, currentPlace: null }]) assert.equal(sameHousingStay({ stayToken: 'old', currentPlace: '101' }, fresh), false)
  assert.equal(sameHousingStay({ stayToken: null }, { stayToken: null }), false)
})
test('housing queries use only the student detail context and whitelisted destinations', () => {
  assert.deepEqual(assistantContext('/housing/transfer', '8'), { type: 'HOUSING_MINE', id: 8 })
  assert.equal(assistantContext('/housing/assignments', '8'), null)
  assert.equal(assistantContext('/housing/transfer', ['8']), null)
  assert.match(assistantContextLabel({ type: 'HOUSING_MINE', id: 8 }), /本人范围/)
  for (const path of ['/housing/mine', '/housing/transfer', '/housing/transfer?id=8&tab=details']) assert.equal(safeAssistantDestination(path), path)
  for (const path of ['/housing/assignments', '/housing/transfer?id=8&studentId=9', '//evil.test/housing/mine', '/housing/../admin']) assert.equal(safeAssistantDestination(path), null)
})
test('housing suggestions and restored tasks follow capability and task kind', () => {
  assert.deepEqual(assistantSuggestions('/housing/mine', ['my_housing'], null), ['我的住宿', '我的调宿进度', '调宿怎么办理'])
  assert.equal(assistantSuggestions('/workbench', ['search_guides'], null).includes('我的住宿'), false)
  assert.equal(latestAssistantTask([{ id: 7, kind: 'HOUSING' }, { id: 9, kind: 'REPAIR' }, { id: 4, kind: 'HOUSING' }], 'HOUSING'), 7)
})
