import test from 'node:test'
import assert from 'node:assert/strict'
import { missionFieldsPayload, repairMissionFieldsPayload, leaveMissionFieldsPayload, canConfirmMission, missionConfirmationPayload, missionStageLabel, missionNeedsPolling } from '../src/lib/assistantMissions.ts'

const fields = { campus: 'EAST', startsAt: '2026-11-05T09:00', endsAt: '2026-11-05T11:00', participants: 30, keyword: null, activityTheme: '学习交流', activityType: 'ACADEMIC', contactPhone: '' }
const mission = { id: 7, version: 4, stage: 'WAITING_CONFIRMATION', fields, proposalHash: 'a'.repeat(64) }
test('leave confirmation covers all local dates, times, reason and contact',()=>{
 const leaveFields={reasonType:'PERSONAL',reason:'实际事由',contact:'owner@example.test',startDate:'2026-11-08',startTime:'09:00',endDate:'2026-11-08',endTime:'17:00'}
 const leave={...mission,kind:'LEAVE',fields:null,leaveFields}
 assert.equal(canConfirmMission(leave,leaveFields,true),true)
 for(const key of Object.keys(leaveFields))assert.equal(canConfirmMission(leave,{...leaveFields,[key]:'changed'},true),false)
 assert.equal(leaveMissionFieldsPayload({...leaveFields,reason:' 实际事由 '}).reason,'实际事由')
})
test('repair confirmation includes every local repair field and does not depend on reservation fields', () => {
 const repairFields={category:'ELECTRICITY',title:'灯不亮',description:'无法开启',locationId:5,locationDetail:'东侧',contact:'user@example.test',contactWindow:'白天'}
 const repair={...mission,kind:'REPAIR',fields:null,repairFields}
 assert.equal(canConfirmMission(repair,repairFields,true),true)
 assert.equal(canConfirmMission(repair,{...repairFields,contact:'another@example.test'},true),false)
 assert.equal(canConfirmMission(repair,{...repairFields,locationId:6},true),false)
 assert.equal(repairMissionFieldsPayload({...repairFields,locationId:''}).locationId,null)
 assert.equal(repairMissionFieldsPayload({...repairFields,locationId:'5.5'}).locationId,null)
})
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
 assert.equal(missionFieldsPayload(fields).startsAt, '2026-11-05T09:00')
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
