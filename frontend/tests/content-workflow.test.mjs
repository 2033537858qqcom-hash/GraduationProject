import { test } from 'node:test'
import assert from 'node:assert/strict'
import { contentActions } from '../src/lib/contentWorkflow.ts'
const editor = { id: 1, permissions: ['content:edit'] }
const reviewer = { id: 2, permissions: ['content:review'] }
const admin = { id: 1, permissions: ['content:edit', 'content:review'] }
const item = (status, createdBy = 1, visible = null) => ({ status, createdBy, live: visible === null ? null : { visible } })
test('a draft or rejected work is editable only by its author', () => {
  for (const status of ['DRAFT', 'REJECTED']) {
    assert.equal(contentActions(item(status), editor).edit, true)
    assert.equal(contentActions(item(status, 2), editor).edit, false)
    assert.equal(contentActions(item(status), reviewer).submit, false)
  }
})
test('an author with both duties can recall but cannot review their own pending work', () => {
  const actions = contentActions(item('IN_REVIEW'), admin)
  assert.equal(actions.recall, true); assert.equal(actions.publish, false); assert.equal(actions.reject, false)
  assert.equal(contentActions(item('IN_REVIEW'), reviewer).publish, true)
})
test('a correction keeps the public link while only the working version can be recalled', () => {
  const actions = contentActions(item('IN_REVIEW', 1, true), editor)
  assert.equal(actions.publicLink, true); assert.equal(actions.recall, true); assert.equal(actions.revise, false)
})
test('published seed content can be revised without claiming someone elses draft', () => {
  assert.equal(contentActions(item('PUBLISHED', null, true), editor).revise, true)
  assert.equal(contentActions(item('DRAFT', null), editor).edit, false)
  assert.equal(contentActions(item('PUBLISHED', null, true), reviewer).revise, false)
})
test('withdrawn work needs a new revision and cannot be directly published or read publicly', () => {
  const actions = contentActions(item('WITHDRAWN', 1, false), admin)
  assert.equal(actions.revise, true); assert.equal(actions.publish, false); assert.equal(actions.unpublish, false); assert.equal(actions.publicLink, false)
})
test('reviewers can take a live article offline even when a correction is pending', () => {
  assert.equal(contentActions(item('DRAFT', 1, true), reviewer).unpublish, true)
  assert.equal(contentActions(item('DRAFT', 1, true), editor).unpublish, false)
  assert.equal(contentActions(item('PUBLISHED', 1, true), null).unpublish, false)
})
