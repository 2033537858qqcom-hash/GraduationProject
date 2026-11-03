import test from 'node:test'
import assert from 'node:assert/strict'
import { queryStartedGoal, resumeGoalFromSummaries } from '../src/lib/assistantSessions.ts'

test('a conversation turn that started a goal exposes that goal id', () => {
  assert.equal(queryStartedGoal({ turn: { id: 1 }, answer: null, repeated: false, goal: { id: 9, stage: 'WAITING_INPUT' } }), 9)
  assert.equal(queryStartedGoal({ turn: { id: 1 }, answer: null, repeated: true, goal: { id: 9, stage: 'WAITING_CONFIRMATION' } }), 9)
  assert.equal(queryStartedGoal({ turn: { id: 1 }, answer: { results: [] }, repeated: false, goal: null }), null)
  assert.equal(queryStartedGoal({ turn: { id: 1 }, answer: { results: [] }, repeated: false }), null)
})

test('restoring a conversation resumes an unfinished goal instead of the query box', () => {
  const goals = [
    { id: 3, stage: 'DONE' },
    { id: 8, stage: 'WAITING_CONFIRMATION' },
    { id: 2, stage: 'CANCELLED' },
  ]
  assert.equal(resumeGoalFromSummaries(goals), 8)
  assert.equal(resumeGoalFromSummaries([{ id: 1, stage: 'DONE' }, { id: 2, stage: 'CANCELLED' }]), null)
  assert.equal(resumeGoalFromSummaries([]), null)
})
