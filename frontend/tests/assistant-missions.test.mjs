import test from 'node:test'
import assert from 'node:assert/strict'
import { missionFieldsPayload, canConfirmMission, missionConfirmationPayload, missionStageLabel, missionNeedsPolling } from '../src/lib/assistantMissions.ts'

const fields = { campus: 'EAST', startsAt: '2026-11-05T09:00', endsAt: '2026-11-05T11:00', participants: 30, keyword: null, activityTheme: '学习交流', activityType: 'ACADEMIC', contactPhone: '' }
const mission = { id: 7, version: 4, stage: 'WAITING_CONFIRMATION', fields, proposalHash: 'a'.repeat(64) }
test('saving requires explicit confirmation of the unchanged proposal', () => {
 assert.equal(canConfirmMission(mission, fields, false), false)
 assert.equal(canConfirmMission(mission, fields, true), true)
 assert.equal(canConfirmMission({ ...mission, stage: 'RUNNING' }, fields, true), false)
 assert.equal(canConfirmMission(mission, { ...fields, participants: 31 }, true), false)
 assert.equal(canConfirmMission(mission, { ...fields, contactPhone: '13800138000' }, true), false)
 assert.throws(() => missionConfirmationPayload(mission, { ...fields, activityTheme: '其他活动' }, true, 'request-key'))
 assert.deepEqual(missionConfirmationPayload(mission, fields, true, 'request-key'), { requestKey: 'request-key', version: 4, action: 'CONFIRM', confirmed: true, proposalHash: 'a'.repeat(64) })
})
test('blank or non-integer participants cannot silently become a valid count', () => {
 assert.equal(missionFieldsPayload({ ...fields, participants: '' }).participants, null)
 assert.equal(missionFieldsPayload({ ...fields, participants: '2.5' }).participants, null)
 assert.equal(missionFieldsPayload({ ...fields, participants: '30' }).participants, 30)
 assert.equal(missionFieldsPayload({ ...fields, startsAt: '2026-11-05T09:00:30' }).startsAt, '2026-11-05T09:00:30')
})
test('a persisted interrupted task offers explicit recovery instead of endless polling', () => {
 assert.equal(missionNeedsPolling({ stage: 'RUNNING' }), true)
 assert.equal(missionNeedsPolling({ stage: 'INTERRUPTED' }), false)
 assert.equal(missionNeedsPolling({ stage: 'WAITING_SELECTION' }), false)
 assert.match(missionStageLabel('WAITING_CONFIRMATION'), /确认/)
 assert.match(missionStageLabel('INTERRUPTED'), /中断/)
})
