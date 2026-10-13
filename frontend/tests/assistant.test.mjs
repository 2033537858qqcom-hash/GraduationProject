import test from 'node:test'
import assert from 'node:assert/strict'
import { assistantContext, assistantContextLabel, assistantSuggestions, safeAssistantDestination } from '../src/lib/assistant.ts'
import { safeLoginDestination } from '../src/lib/navigation.ts'
import { learningLinkSelection } from '../src/lib/learningLink.ts'
test('only explicit application routes and valid IDs become context', () => {
  assert.deepEqual(assistantContext('/portal/certificates', '12'), { type: 'CERTIFICATE_MINE', id: 12 })
  assert.deepEqual(assistantContext('/student-affairs/leave', '3'), { type: 'LEAVE_REVIEW', id: 3 })
  assert.deepEqual(assistantContext('/repairs/mine', '8'), { type: 'REPAIR_MINE', id: 8 })
  assert.deepEqual(assistantContext('/logistics/repairs/dispatch', '8'), { type: 'REPAIR_DISPATCH', id: 8 })
  assert.deepEqual(assistantContext('/logistics/repairs/tasks', '8'), { type: 'REPAIR_WORK', id: 8 })
  assert.equal(assistantContext('/logistics/repairs/statistics', '8'), null)
  for (const id of ['0', '-1', '1.5', '1x', ['1'], '9007199254740993', undefined]) assert.equal(assistantContext('/portal/leave', id), null)
  assert.equal(assistantContext('/admin/foundation', '2'), null)
})
test('model or source URLs cannot create arbitrary navigation targets', () => {
  for (const path of ['https://evil.test', '//evil.test', '/assistant/guides/../../admin', '/assistant/guides/%2e%2e', '/api/assistant/query', '/portal/certificates?id=2&tab=details&userId=3', '/admin/foundation', '/portal/learning\\evil']) assert.equal(safeAssistantDestination(path), null)
  for (const path of ['/assistant/guides/leave', '/portal/learning/grades', '/portal/certificates?id=2&tab=details', '/student-affairs/leave', '/portal/learning/timetable?term=1&week=2026-10-12', '/portal/learning/grades?term=1', '/repairs/mine', '/repairs/mine?id=8&tab=details', '/logistics/repairs/dispatch?id=8&tab=details', '/logistics/repairs/tasks']) assert.equal(safeAssistantDestination(path), path)
  assert.equal(safeAssistantDestination('/repairs/mine?id=8&tab=details&contact=1'), null)
  assert.equal(safeAssistantDestination('/logistics/repairs/statistics'), null)
})
test('suggestions follow permissions and current application context', () => {
  assert.deepEqual(assistantSuggestions('/portal/learning/grades', ['my_grades'], null), ['我的已发布成绩', '成绩怎么查询'])
  assert.deepEqual(assistantSuggestions('/portal/certificates', ['application_status'], { type: 'CERTIFICATE_MINE', id: 2 }), ['当前申请的处理进度', '证明怎么领取'])
  assert.equal(assistantSuggestions('/workbench', ['search_guides', 'my_notice_summary'], null).includes('岗位待办'), false)
  assert.deepEqual(assistantSuggestions('/workbench', ['my_timetable', 'my_notice_summary'], null), ['本周课表', '我有多少未读消息'])
  assert.deepEqual(assistantSuggestions('/repairs/mine', ['my_repairs'], null), ['我的报修进度', '报修怎么办理'])
  assert.deepEqual(assistantSuggestions('/logistics/repairs/tasks', ['repair_work_queue'], null), ['我的维修任务', '报修怎么办理'])
  assert.deepEqual(assistantSuggestions('/logistics/repairs/tasks', ['repair_status', 'repair_work_queue'], { type: 'REPAIR_WORK', id: 4 }), ['当前报修的处理进度', '报修怎么办理'])
  assert.equal(assistantContextLabel({ type: 'REPAIR_DISPATCH', id: 4 }), '报修 #4 · 派单范围')
})
test('guide deep links can restore login without allowing path traversal', () => {
  assert.equal(safeLoginDestination('/assistant/guides/certificate'), '/assistant/guides/certificate')
  assert.equal(safeLoginDestination('/assistant/guides/../admin'), null)
})
test('learning links retain the selected term and queried week including a partial first week', () => {
  const terms = [{ id: 1, startsOn: '2026-09-01', endsOn: '2027-01-31' }, { id: 2, startsOn: '2027-02-01', endsOn: '2027-06-30' }]
  assert.deepEqual(learningLinkSelection(terms, '1', '2026-10-12'), { termId: 1, date: '2026-10-12' })
  assert.deepEqual(learningLinkSelection(terms, '1', '2026-08-31'), { termId: 1, date: '2026-08-31' })
  assert.deepEqual(learningLinkSelection(terms, '2', undefined), { termId: 2, date: null })
})
test('unknown terms and malformed or out-of-term dates cannot become learning selections', () => {
  const terms = [{ id: 1, startsOn: '2026-09-01', endsOn: '2027-01-31' }]
  for (const term of ['0', '3', ['1'], '1x', undefined]) assert.equal(learningLinkSelection(terms, term, '2026-10-12'), null)
  for (const week of ['2026-02-30', '2026-12-99', '2026-08-24', '2027-02-01', 'https://evil.test', ['2026-10-12']]) assert.deepEqual(learningLinkSelection(terms, '1', week), { termId: 1, date: null })
})
