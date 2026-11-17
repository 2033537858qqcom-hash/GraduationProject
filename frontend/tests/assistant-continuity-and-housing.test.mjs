import test from 'node:test'
import assert from 'node:assert/strict'
import {
  canConfirmMission,
  housingMissionFieldsPayload,
  missionActionLabels,
  missionBusinessStatusLabel,
} from '../src/lib/assistantMissions.ts'
import { goalKindLabels } from '../src/lib/assistantGoals.ts'
import { safeAssistantDestination } from '../src/lib/assistant.ts'
import { streamPostJson } from '../src/lib/api.ts'

test('housing mission confirmation binds trimmed housing fields and recognizes status/action labels', () => {
  const hash = 'a'.repeat(64)
  const proposal = {
    version: 2,
    stage: 'WAITING_CONFIRMATION',
    proposalHash: hash,
    kind: 'HOUSING',
    fields: null,
    housingFields: {
      reason: '通勤时间较长',
      preferredArea: '西校区四号楼',
      materialNote: '附课表与培养方案',
    },
  }
  assert.equal(
    canConfirmMission(
      proposal,
      { reason: '  通勤时间较长 ', preferredArea: '西校区四号楼', materialNote: '附课表与培养方案' },
      true,
    ),
    true,
  )
  assert.equal(
    canConfirmMission(
      proposal,
      { reason: '更换原因', preferredArea: '西校区四号楼', materialNote: '附课表与培养方案' },
      true,
    ),
    false,
  )
  assert.deepEqual(housingMissionFieldsPayload({ reason: ' 原因 ', preferredArea: '', materialNote: null }), {
    reason: '原因',
    preferredArea: null,
    materialNote: null,
  })
  assert.equal(goalKindLabels.HOUSING, '调宿')
  assert.equal(missionBusinessStatusLabel('AWAITING_ASSIGNMENT'), '待排床')
  assert.equal(missionBusinessStatusLabel('AWAITING_CHECKIN'), '待入住')
  assert.equal(missionActionLabels.prepare_housing, '核对调宿预览')
})

test('dorm recharge prefill link passes strict destination whitelist and rejects arbitrary params', () => {
  assert.equal(
    safeAssistantDestination('/portal/payments?tab=dorm&dormCategory=ELECTRICITY&dormMode=AMOUNT&dormValue=20'),
    '/portal/payments?tab=dorm&dormCategory=ELECTRICITY&dormMode=AMOUNT&dormValue=20',
  )
  assert.equal(safeAssistantDestination('/portal/payments?tab=dorm&evil=1'), null)
})

test('streamPostJson dispatches live SSE delta tokens alongside steps', async () => {
  const originalFetch = globalThis.fetch
  const deltas = []
  const steps = []
  globalThis.fetch = async url => {
    if (String(url) === '/api/auth/csrf') {
      return new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' }), { status: 200 })
    }
    const payload = [
      'event: step\ndata: {"message":"正在整理"}\n\n',
      'event: delta\ndata: {"text":"共查询到"}\n\n',
      'event: delta\ndata: {"text":" 2 项记录。"}\n\n',
      'event: result\ndata: {"ok":true}\n\n',
    ].join('')
    return new Response(payload, { status: 200, headers: { 'content-type': 'text/event-stream' } })
  }
  try {
    const result = await streamPostJson(
      '/assistant/sessions/1/query/stream',
      { a: 1 },
      {
        onStep: m => steps.push(m),
        onDelta: d => deltas.push(d),
      },
    )
    assert.deepEqual(result, { ok: true })
    assert.deepEqual(steps, ['正在整理'])
    assert.deepEqual(deltas, ['共查询到', ' 2 项记录。'])
  } finally {
    globalThis.fetch = originalFetch
  }
})
