import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import { writeTeachingDraft, readTeachingDraft, removeTeachingDraft, clearTeachingDrafts } from '../src/lib/teachingDrafts.ts'

const entries = new Map()
const storage = {
  getItem: key => entries.get(key) ?? null,
  setItem: (key, value) => entries.set(key, value),
  removeItem: key => entries.delete(key),
  key: index => [...entries.keys()][index] ?? null,
  get length() { return entries.size },
}
const draft = title => ({ notice: { title, body: '未发布正文', requestKey: 'request-1' } })
beforeEach(() => { globalThis.sessionStorage = storage; for (const id of [1, 2, 11]) clearTeachingDrafts(id); entries.clear() })

test('drafts are isolated by account and teaching class', () => {
  writeTeachingDraft(1, 1, draft('Java'), null)
  writeTeachingDraft(1, 2, draft('软件工程'), null)
  writeTeachingDraft(2, 1, draft('其他教师'), null)
  assert.equal(readTeachingDraft(1, 1).draft.notice.title, 'Java')
  assert.equal(readTeachingDraft(1, 2).draft.notice.title, '软件工程')
  assert.equal(readTeachingDraft(2, 1).draft.notice.title, '其他教师')
})

test('changing the input or restored object does not mutate the saved draft', () => {
  const input = draft('原标题')
  writeTeachingDraft(1, 1, input, null)
  input.notice.title = '改变输入'
  const restored = readTeachingDraft(1, 1)
  restored.draft.notice.title = '改变恢复值'
  assert.equal(readTeachingDraft(1, 1).draft.notice.title, '原标题')
})

test('clearing a submitted class preserves other classes and independent notice drafts', () => {
  writeTeachingDraft(1, 1, { ...draft('通知'), material: { source: null, title: '资料', body: '正文', attachmentName: '' } }, null)
  writeTeachingDraft(1, 2, draft('另一班'), null)
  writeTeachingDraft(1, 1, draft('通知'), null)
  assert.equal(readTeachingDraft(1, 1).draft.material, undefined)
  assert.equal(readTeachingDraft(1, 1).draft.notice.title, '通知')
  removeTeachingDraft(1, 1)
  assert.equal(readTeachingDraft(1, 1), null)
  assert.equal(readTeachingDraft(1, 2).draft.notice.title, '另一班')
})

test('logout clears only the current account, including similarly prefixed IDs', () => {
  for (const id of [1, 2, 11]) writeTeachingDraft(id, 1, draft(`用户${id}`), null)
  clearTeachingDrafts(1)
  assert.equal(readTeachingDraft(1, 1), null)
  assert.equal(readTeachingDraft(2, 1).draft.notice.title, '用户2')
  assert.equal(readTeachingDraft(11, 1).draft.notice.title, '用户11')
  clearTeachingDrafts() // An expired session restored on a fresh page has no known user ID.
  assert.equal(readTeachingDraft(2, 1), null)
  assert.equal(readTeachingDraft(11, 1), null)
})

test('expired and structurally invalid stored drafts are rejected', () => {
  writeTeachingDraft(1, 1, draft('过期'), null)
  const id = [...entries.keys()][0]
  const data = JSON.parse(entries.get(id))
  data.savedAt -= 25 * 60 * 60 * 1000
  entries.set(id, JSON.stringify(data))
  assert.equal(readTeachingDraft(1, 1), null)
  entries.set(id, JSON.stringify({ savedAt: Date.now(), draft: { notice: { title: 123 } } }))
  assert.equal(readTeachingDraft(1, 1), null)
})

test('disabled storage falls back to memory and reports that refresh persistence failed', () => {
  globalThis.sessionStorage = { getItem() { throw Error('disabled') }, setItem() { throw Error('disabled') }, removeItem() { throw Error('disabled') } }
  assert.equal(writeTeachingDraft(1, 1, draft('仍可切换'), null), false)
  assert.equal(readTeachingDraft(1, 1).draft.notice.title, '仍可切换')
})

test('file objects are held in memory, while only their name is serialized', () => {
  const file = new File(['附件正文'], '说明.txt', { type: 'text/plain' })
  writeTeachingDraft(1, 1, { material: { source: null, title: '资料', body: '正文', attachmentName: file.name } }, file)
  assert.equal(readTeachingDraft(1, 1).file, file)
  assert.ok([...entries.values()][0].includes('说明.txt'))
  assert.ok(![...entries.values()][0].includes('附件正文'))
})

test('a full storage quota does not replace newer in-memory text with an older stored draft', () => {
  writeTeachingDraft(1, 1, draft('旧文字'), null)
  globalThis.sessionStorage = { ...storage, setItem() { throw Error('quota') } }
  assert.equal(writeTeachingDraft(1, 1, draft('新文字'), null), false)
  assert.equal(readTeachingDraft(1, 1).draft.notice.title, '新文字')
})
