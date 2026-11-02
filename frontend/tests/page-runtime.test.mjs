import test, { before, after } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

let server
let checkPageSetup
const storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const windowDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'window')
const originalFetch = globalThis.fetch

before(async () => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true, value: { getItem: () => null },
  })
  Object.defineProperty(globalThis, 'window', {
    configurable: true, value: { location: { search: '' }, addEventListener() {}, removeEventListener() {} },
  })
  globalThis.fetch = async () => { throw new Error('Network disabled in page setup regression') }
  server = await createServer({
    configFile: false,
    cacheDir: fileURLToPath(new URL('../node_modules/.vite-tests/page-runtime', import.meta.url)),
    root: fileURLToPath(new URL('../', import.meta.url)),
    plugins: [vue()],
    server: { middlewareMode: true, hmr: false, watch: null },
    appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] },
  })
  ;({ checkPageSetup } = await server.ssrLoadModule('/tests/fixtures/page-runtime.ts'))
})

after(async () => {
  await server?.close()
  if (storageDescriptor) Object.defineProperty(globalThis, 'localStorage', storageDescriptor)
  else delete globalThis.localStorage
  if (windowDescriptor) Object.defineProperty(globalThis, 'window', windowDescriptor)
  else delete globalThis.window
  globalThis.fetch = originalFetch
})

test('reservation page initializes its filter state before starting navigation watchers', async () => {
  const errors = await checkPageSetup('/portal/reservations')
  assert.ifError(errors[0])
})

test('agent mission retains the exact request after a lost response and prevents edited confirmation', async () => {
 const oldFetch = globalThis.fetch, commands = []
 const fields = { campus: 'EAST', startsAt: '2026-11-05T09:00', endsAt: '2026-11-05T11:00', participants: 30, keyword: null, activityTheme: '学习交流', activityType: 'ACADEMIC', contactPhone: null }
 const view = { id: 7, sessionId: 1, goal: '准备预约草稿', mode: 'LOCAL', stage: 'WAITING_CONFIRMATION', version: 4, fields, missing: [], options: [], selectedKey: 'option', proposalHash: 'a'.repeat(64), conflicts: [], message: '请确认', steps: [], business: null, canContinue: false }
 let attempts = 0
 globalThis.fetch = async (path, options = {}) => {
  if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
  if (options.method === 'POST') {
   commands.push(JSON.parse(options.body))
   if (++attempts === 1) throw Error('response lost')
   return Response.json(view)
  }
  return Response.json([])
 }
 try {
  const errors = await checkPageSetup('/assistant-mission-test', undefined, async state => {
   state.goal.value = '准备预约草稿'
   state.form.value = fields
   await Promise.resolve()
   await state.create()
   assert.equal(state.pending.value.body.requestKey, commands[0].requestKey)
   assert.equal(state.mission.value, null)
   await state.retry()
   assert.equal(commands[1].requestKey, commands[0].requestKey)
   assert.deepEqual(commands[1], commands[0])
   state.confirmed.value = true
   state.form.value.participants = 31
   await state.confirm()
   assert.equal(commands.length, 2)
   assert.equal(state.readyToConfirm.value, false)
  }, { sessionId: 1, active: false, modelReady: false })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = oldFetch }
})

test('reopening the agent panel preserves unsaved fields instead of restoring over them', async () => {
 const oldFetch = globalThis.fetch
 globalThis.fetch = async () => Response.json([{ id: 9 }])
 try {
  const errors = await checkPageSetup('/assistant-mission-test', undefined, async state => {
   state.goal.value = '尚未开始的预约目标'
   state.form.value.activityTheme = '没有保存的主题'
   await state.load()
   assert.equal(state.mission.value, null)
   assert.equal(state.form.value.activityTheme, '没有保存的主题')
  }, { sessionId: 1, active: false, modelReady: false })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = oldFetch }
})

test('a goal-owned task restores the exact bound mission instead of the latest unrelated task',async()=>{
 const oldFetch=globalThis.fetch,requests=[]
 const fields={campus:null,startsAt:null,endsAt:null,participants:null,keyword:null,activityTheme:null,activityType:null,contactPhone:null}
 globalThis.fetch=async path=>{requests.push(path);return Response.json(path.endsWith('/missions')?[{id:99}]:{id:42,kind:'CERTIFICATE',fields,certificateFields:{purpose:'目标中的用途'},options:[],missing:[],conflicts:[],stage:'WAITING_INPUT'})}
 try{
  const errors=await checkPageSetup('/assistant-mission-test',undefined,async state=>{
   await state.load();assert.ok(requests.some(path=>path.endsWith('/missions/42')));assert.ok(!requests.some(path=>path.endsWith('/missions/99')))
   assert.equal(state.mission.value.id,42)
  },{sessionId:1,missionId:42,embedded:true,active:false,modelReady:false})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch}
})

test('autonomous goals require consent and retry the identical request after a lost response',async()=>{
 const oldFetch=globalThis.fetch,commands=[]
 let attempts=0
 const view={id:8,sessionId:1,goal:'准备证明和请假',stage:'WAITING_INPUT',version:2,instructions:[],tasks:[],message:'请补充实际用途',focusTaskKey:null,events:[],canContinue:true}
 globalThis.fetch=async(path,options={})=>{
  if(path==='/api/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:'test'})
  if(options.method==='POST'){commands.push(JSON.parse(options.body));if(++attempts===1)throw Error('response lost');return Response.json(view)}
  return Response.json([])
 }
 try{
  const errors=await checkPageSetup('/assistant-goal-test',undefined,async state=>{
   state.goalText.value='准备证明和请假';await state.create();assert.equal(commands.length,0)
   state.consent.value=true;await state.create();assert.equal(state.pending.value.body.requestKey,commands[0].requestKey)
   await state.retry();assert.deepEqual(commands[1],commands[0]);assert.equal(state.current.value.id,8)
   state.revision.value='证明用于实习核验';await state.revise();assert.equal(commands.length,2)
   state.revisionConsent.value=true;await state.revise();assert.equal(commands[2].message,'证明用于实习核验');assert.equal(commands[2].externalConsent,true)
  },{sessionId:1,active:false,modelReady:true})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch}
})

test('goal submission requires checking the exact current business snapshot',async()=>{
 const oldFetch=globalThis.fetch,commands=[]
 const view={id:8,sessionId:1,goal:'提交证明',stage:'WAITING_CONFIRMATION',version:5,instructions:[],tasks:[{key:'proof',kind:'CERTIFICATE',label:'提交证明',target:'SUBMIT',dependsOn:[],cancelled:false,mission:null,submissionPreview:{proposalHash:'b'.repeat(64),canSubmit:true,fields:[{label:'实际用途',value:'实习核验'}],issues:[],status:'DRAFT',version:0}}],message:'请确认提交',focusTaskKey:'proof',events:[],canContinue:true}
 globalThis.fetch=async(path,options={})=>{
  if(path==='/api/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:'test'})
  if(options.method==='POST'){commands.push(JSON.parse(options.body));return Response.json(view)}
  return Response.json([])
 }
 try{
  const errors=await checkPageSetup('/assistant-goal-test',undefined,async state=>{
   state.current.value=view;await state.authorizeSubmission();assert.equal(commands.length,0)
   state.submitConfirmed.value=true;await state.authorizeSubmission();assert.equal(commands.length,1)
   assert.equal(commands[0].action,'AUTHORIZE_SUBMIT');assert.equal(commands[0].taskKey,'proof');assert.equal(commands[0].proposalHash,'b'.repeat(64))
   state.submitConfirmed.value=true;state.current.value={...view,tasks:[{...view.tasks[0],submissionPreview:{...view.tasks[0].submissionPreview,proposalHash:'c'.repeat(64)}}]}
   await new Promise(resolve=>setImmediate(resolve));assert.equal(state.submitConfirmed.value,false)
  },{sessionId:1,active:false,modelReady:true})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch}
})

test('stopping one goal task requires a local decision and preserves the other planned task',async()=>{
 const oldFetch=globalThis.fetch,oldConfirm=globalThis.window.confirm,commands=[]
 const view={id:8,sessionId:1,goal:'准备证明和请假',stage:'WAITING_INPUT',version:5,instructions:[],tasks:[{key:'proof',kind:'CERTIFICATE',label:'准备证明',target:'DRAFT',dependsOn:[],cancelled:false,mission:null},{key:'leave',kind:'LEAVE',label:'准备请假',target:'DRAFT',dependsOn:[],cancelled:false,mission:null}],message:'请补充',focusTaskKey:'proof',events:[],canContinue:true}
 globalThis.fetch=async(path,options={})=>{
  if(path==='/api/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:'test'})
  if(options.method==='POST'){commands.push(JSON.parse(options.body));return Response.json({...view,focusTaskKey:'leave',tasks:[{...view.tasks[0],cancelled:true},view.tasks[1]]})}
  return Response.json([])
 }
 try{
  const errors=await checkPageSetup('/assistant-goal-test',undefined,async state=>{
   state.current.value=view;globalThis.window.confirm=()=>false;await state.cancelTask('proof');assert.equal(commands.length,0)
   globalThis.window.confirm=()=>true;await state.cancelTask('proof');assert.equal(commands.length,1)
   assert.equal(commands[0].action,'CANCEL_TASK');assert.equal(commands[0].taskKey,'proof');assert.equal(commands[0].confirmed,true);assert.equal(state.activeTask.value.key,'leave')
  },{sessionId:1,active:false,modelReady:true})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch;globalThis.window.confirm=oldConfirm}
})

test('repair mission local field edits require a new server preview before confirmation', async () => {
 const oldFetch=globalThis.fetch,commands=[]
 const repairFields={category:'ELECTRICITY',title:'灯具故障',description:'灯具无法开启',locationId:1,locationDetail:'走廊',contact:'owner@example.test',contactWindow:'白天'}
 const fields={campus:null,startsAt:null,endsAt:null,participants:null,keyword:null,activityTheme:null,activityType:null,contactPhone:null}
 const view={id:1,sessionId:1,kind:'REPAIR',stage:'WAITING_CONFIRMATION',version:3,fields,repairFields,options:[],conflicts:[],missing:[],proposalHash:'a'.repeat(64),repairLocations:[],business:null}
 globalThis.fetch=async(path,options={})=>{
  if(path==='/api/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:'test'})
  if(options.method==='POST'){commands.push(JSON.parse(options.body));return Response.json(view)}
  return Response.json([])
 }
 try {
  const errors=await checkPageSetup('/assistant-mission-test',undefined,async state=>{
   state.goal.value='帮我准备报修草稿'
   state.repairForm.value=repairFields
   await state.create()
   assert.equal(commands[0].fields,null)
   assert.equal(commands[0].repairFields.contact,'owner@example.test')
   assert.equal(state.isRepair.value,true)
   assert.equal(state.dirty.value,false)
   state.confirmed.value=true
   state.repairForm.value.description='修改后的故障现象'
   await state.confirm()
   assert.equal(commands.length,1)
   await state.action('REVISE')
   assert.equal(commands[1].repairFields.description,'修改后的故障现象')
   assert.equal(commands[1].action,'REVISE')
  },{sessionId:1,active:false,modelReady:false})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch}
})

test('leave mission sends only its domain fields and editing contact requires a fresh preview',async()=>{
 const oldFetch=globalThis.fetch,commands=[]
 const leaveFields={reasonType:'PERSONAL',reason:'本地实际事由',contact:'owner@example.test',startDate:'2026-11-08',startTime:'09:00',endDate:'2026-11-08',endTime:'17:00'}
 const fields={campus:null,startsAt:null,endsAt:null,participants:null,keyword:null,activityTheme:null,activityType:null,contactPhone:null}
 const view={id:1,sessionId:1,kind:'LEAVE',stage:'WAITING_CONFIRMATION',version:3,fields,leaveFields,options:[],conflicts:[],missing:[],proposalHash:'a'.repeat(64),business:null}
 globalThis.fetch=async(path,options={})=>{
  if(path==='/api/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:'test'})
  if(options.method==='POST'){commands.push(JSON.parse(options.body));return Response.json(view)}
  return Response.json([])
 }
 try{
  const errors=await checkPageSetup('/assistant-mission-test',undefined,async state=>{
   state.goal.value='帮我准备请假草稿';state.leaveForm.value=leaveFields;await state.create()
   assert.equal(commands[0].fields,null);assert.equal(commands[0].repairFields,null);assert.equal(commands[0].leaveFields.contact,'owner@example.test');assert.equal(state.isLeave.value,true);assert.equal(state.dirty.value,false)
   state.confirmed.value=true;state.leaveForm.value.contact='changed@example.test';await state.confirm();assert.equal(commands.length,1)
   await state.action('REVISE');assert.equal(commands[1].leaveFields.contact,'changed@example.test');assert.equal(commands[1].action,'REVISE')
  },{sessionId:1,active:false,modelReady:false})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch}
})

test('certificate mission sends only purpose and rejects confirmation after editing it',async()=>{
 const oldFetch=globalThis.fetch,commands=[]
 const certificateFields={purpose:'本地实际证明用途'}
 const fields={campus:null,startsAt:null,endsAt:null,participants:null,keyword:null,activityTheme:null,activityType:null,contactPhone:null}
 const view={id:1,sessionId:1,kind:'CERTIFICATE',stage:'WAITING_CONFIRMATION',version:3,fields,certificateFields,options:[],conflicts:[],missing:[],proposalHash:'a'.repeat(64),business:null}
 globalThis.fetch=async(path,options={})=>{
  if(path==='/api/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:'test'})
  if(options.method==='POST'){commands.push(JSON.parse(options.body));return Response.json(view)}
  return Response.json([])
 }
 try{
  const errors=await checkPageSetup('/assistant-mission-test',undefined,async state=>{
   state.goal.value='帮我准备在校证明草稿';state.certificateForm.value=certificateFields;await state.create()
   assert.equal(commands[0].fields,null);assert.equal(commands[0].repairFields,null);assert.equal(commands[0].leaveFields,null)
   assert.deepEqual(commands[0].certificateFields,certificateFields);assert.equal(state.isCertificate.value,true);assert.equal(state.dirty.value,false)
   state.confirmed.value=true;state.certificateForm.value.purpose='修改后的实际用途';await state.confirm();assert.equal(commands.length,1)
   await state.action('REVISE');assert.equal(commands[1].certificateFields.purpose,'修改后的实际用途');assert.equal(commands[1].action,'REVISE')
  },{sessionId:1,active:false,modelReady:false})
  assert.ifError(errors[0])
 }finally{globalThis.fetch=oldFetch}
})

test('certificate permission exposes the shared agent mission entry',async()=>{
 const {currentUser}=await server.ssrLoadModule('/src/stores/auth.ts')
 const prior=currentUser.value,oldDocument=Object.getOwnPropertyDescriptor(globalThis,'document')
 Object.defineProperty(globalThis,'document',{configurable:true,value:{hidden:true,documentElement:{scrollTop:0},addEventListener(){},removeEventListener(){}}})
 try{
  currentUser.value={id:1,permissions:['agent:query','certificate:apply']}
  const errors=await checkPageSetup('/assistant-test',undefined,async state=>{
   assert.equal(state.canRunMission.value,true)
  })
  assert.ifError(errors[0])
 }finally{currentUser.value=prior;if(oldDocument)Object.defineProperty(globalThis,'document',oldDocument);else delete globalThis.document}
})

test('workbench initializes its task queue before starting filter watchers', async () => {
  const errors = await checkPageSetup('/workbench')
  assert.ifError(errors[0])
})

for (const path of ['/campus/explore', '/portal', '/portal/learning', '/portal/leave', '/portal/certificates', '/housing/mine',
  '/repairs/mine', '/academic', '/admin/content', '/admin/foundation', '/admin/resources',
  '/finance/payments', '/payment-bases', '/portal/payments']) {
  test(`page setup smoke: ${path}`, async () => {
    const errors = await checkPageSetup(path)
    assert.ifError(errors[0])
  })
}

test('personal schedule catches unavailable sources during initial load without setup errors', async () => {
 const errors = await checkPageSetup('/portal/schedule')
 assert.ifError(errors[0])
})

test('dorm recharge requires a server quote and retains the request identity after a network retry', async () => {
 const oldFetch = globalThis.fetch
 const commands = [], meter = { id: 7, roomId: 4, building: '1号楼', room: '101', meterCode: 'E101', category: 'ELECTRICITY', standardName: '电价', unitPriceCents: 61, unit: '度', balance: 0, enabled: true, standardStatus: 'ACTIVE', standardAvailable: true, version: 0, standardId: 3 }
 let attempts = 0
 globalThis.fetch = async (path, options = {}) => {
  if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
  if (path === '/api/payments/mine/dorm-recharge' && !options.method) return Response.json({ meters: [meter], history: [] })
  const payload = JSON.parse(options.body)
  commands.push({ path, payload })
  if (path.endsWith('/quote')) return Response.json({ amountCents: 1000, quantity: 16.393442622 })
  if (path === '/api/payments/mine/dorm-recharge' && ++attempts === 1) throw new Error('network')
  return Response.json({ id: 12 })
 }
 try {
  const errors = await checkPageSetup('/dorm-recharge-test', undefined, async state => {
   await state.refresh()
   state.value.value = '10.00'
   await state.create()
   assert.equal(commands.length, 0)
   await state.preview()
   assert.equal(state.quote.value.amountCents, 1000)
   await state.create()
   assert.equal(state.value.value, '10.00')
   await state.create()
   const orders = commands.filter(command => !command.path.endsWith('/quote'))
   assert.equal(orders.length, 2)
   assert.equal(orders[0].payload.requestKey, orders[1].payload.requestKey)
   assert.equal(orders[0].payload.expectedUnitPriceCents, 61)
   assert.equal(state.value.value, '')
  })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = oldFetch }
})

test('fee category creation preserves input and request identity across a retry', async () => {
 const oldFetch = globalThis.fetch, commands = []
 let attempts = 0
 globalThis.fetch = async (path, options = {}) => {
  if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
  commands.push(JSON.parse(options.body))
  if (++attempts === 1) throw new Error('network')
  return Response.json({ code: 'FEE_001', name: '校服费', enabled: true, version: 0 })
 }
 try {
  const errors = await checkPageSetup('/fee-category-test', undefined, async state => {
   state.form.value = { name: '校服费', description: '校服费用', reason: '建立学校核定的演示收费项目' }
   await Promise.resolve()
   await state.create()
   assert.equal(state.form.value.name, '校服费')
   await state.create()
   assert.equal(commands.length, 2)
   assert.equal(commands[0].requestKey, commands[1].requestKey)
   assert.equal(state.form.value.name, '')
  })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = oldFetch }
})

test('saving reconciliation differences reuses the request identity after a network failure', async () => {
 const oldFetch = globalThis.fetch, requests = []
 let attempts = 0
 globalThis.fetch = async (path, options = {}) => {
  if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
  if (String(path).includes('/cases?page=')) return Response.json({ items: [], total: 0, page: 1, pageSize: 20 })
  if (path === '/api/finance/payments/reconciliation/cases' && options.method === 'POST') {
   requests.push(JSON.parse(options.body))
   if (++attempts === 1) throw new Error('network')
   return Response.json({ batchId: 1, items: [{ id: 1 }] })
  }
  throw Error(`unexpected ${path}`)
 }
 try {
  const preview={ items: [], matchedCount: 0, differenceCount: 1, notFoundCount: 0, invalidCount: 0, duplicateCount: 0 }
  const errors = await checkPageSetup('/reconciliation-cases-test', undefined, async state => {
   await state.save()
   assert.equal(requests.length, 1)
   assert.ok(state.error.value)
   await state.save()
   assert.equal(requests.length, 2)
   assert.equal(requests[0].requestKey, requests[1].requestKey)
   assert.match(state.message.value, /1 项/)
  }, { csv: '订单号,金额（元）,交易号,支付状态\nTEST-ORDER,1.00,,UNPAID', preview, canManage: true, canReview: false })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = oldFetch }
})

test('batch billing requires a valid preview and invalidates it after editing', async () => {
 const oldFetch = globalThis.fetch, commands = []
 globalThis.fetch = async (path, options = {}) => {
  if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
  if (options.method === 'POST') {
   commands.push(path)
   if (path.endsWith('/batch/preview')) return Response.json({ items: [], validCount: 2, totalAmountCents: 2500, canCreate: true })
  }
  throw Error('network disabled')
 }
 try {
  const errors = await checkPageSetup('/finance/payments', undefined, async state => {
   state.form.value = { ...state.form.value, usernames: '20260001,20260002', categoryCode: 'TUITION', title: '学费', billingPeriod: '测试', dueDate: '2099-01-01', amount: '12.50', basis: '按已核定标准进行收费测试' }
   await Promise.resolve()
   await state.saveBill()
   assert.deepEqual(commands, ['/api/finance/payments/batch/preview'])
   assert.equal(state.batchPreview.value.canCreate, true)
   state.form.value.title = '修改后的学费'
   await Promise.resolve()
   assert.equal(state.batchPreview.value, null)
   await state.saveBill()
   assert.equal(commands.length, 2)
   assert.equal(commands[1], '/api/finance/payments/batch/preview')
   await state.saveBill()
   assert.equal(commands[2], '/api/finance/payments/batch')
  })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = oldFetch }
})

test('disabled fee projects stay available for historical bill filters but not new bill creation', async () => {
 const categories = [{ code: 'TUITION', name: '学费', description: '', enabled: true, version: 0 }, { code: 'FEE_OLD', name: '旧项目', description: '', enabled: false, version: 1 }]
 const first = await checkPageSetup('/portal/payments', undefined, async state => {
  state.categories.value = categories
  state.bills.value = []
  assert.deepEqual(state.visibleCategories.value.map(item => item.code), ['TUITION'])
  state.bills.value = [{ categoryCode: 'FEE_OLD', workflowStatus: 'PUBLISHED', status: 'PAID', amountCents: 100 }]
  assert.deepEqual(state.visibleCategories.value.map(item => item.code), ['TUITION', 'FEE_OLD'])
 })
 assert.ifError(first[0])
 const second = await checkPageSetup('/finance/payments', undefined, async state => {
  state.catalog.value = { categories, standards: [{ id: 1, categoryCode: 'TUITION', status: 'ACTIVE', available: false }, { id: 2, categoryCode: 'TUITION', status: 'ACTIVE', available: true }] }
  state.form.value.categoryCode = 'TUITION'
  assert.deepEqual(state.activeCategories.value.map(item => item.code), ['TUITION'])
  assert.deepEqual(state.standardsForForm.value.map(item => item.id), [2])
 })
 assert.ifError(second[0])
})

test('switching bills preserves an unsubmitted refund until the user confirms', async () => {
 const oldConfirm = globalThis.window.confirm
 let confirmations = 0
 globalThis.window.confirm = () => { confirmations++; return false }
 try {
  const errors = await checkPageSetup('/portal/payments?id=4&tab=details', undefined, async state => {
   state.selected.value = { id: 4 }
   state.showRefund.value = true
   state.refundReason.value = '未提交的退款说明'
   await state.openBill({ id: 4 })
   assert.equal(confirmations, 0)
   assert.equal(state.refundReason.value, '未提交的退款说明')
   await state.openBill({ id: 5 })
   assert.equal(confirmations, 1)
   assert.equal(state.route.query.id, '4')
   assert.equal(state.showRefund.value, true)
   assert.equal(state.refundReason.value, '未提交的退款说明')
   globalThis.window.confirm = () => true
   await state.openBill({ id: 5 })
   assert.equal(state.route.query.id, '5')
   assert.equal(state.showRefund.value, false)
   assert.equal(state.refundReason.value, '')
  })
  assert.ifError(errors[0])
 } finally { globalThis.window.confirm = oldConfirm }
})

test('housing settings does not treat the default provenance as unsaved input', async () => {
 const errors = await checkPageSetup('/admin/housing', undefined, async state => {
  assert.equal(state.unsaved.value, false)
  state.buildingForm.value.name = '新楼栋'
  assert.equal(state.unsaved.value, true)
 })
 assert.ifError(errors[0])
})

test('switching class preserves the unsaved state of other academic forms', async () => {
 const old = globalThis.fetch
 globalThis.fetch = async path => {
  if (String(path) === '/api/academic/catalog') return Response.json({ terms: [{ id: 1 }], courses: [{ id: 1 }], classes: [{ id: 1 }, { id: 2 }], teachers: [], students: [], campuses: [], rooms: [] })
  if (String(path).startsWith('/api/academic/classes/')) return Response.json({ exams: [], instructors: [{ id: String(path).endsWith('/1') ? 5 : 6 }], info: { termId: 1, memberCount: 0 } })
  throw Error(`unexpected ${path}`)
 }
 try {
  const errors = await checkPageSetup('/academic', undefined, async state => {
   for (let i = 0; i < 30; i++) await Promise.resolve()
   state.courseForm.name = '未保存课程'
   state.selected.value = 2
   await state.loadDetail()
   assert.equal(state.unsaved.value, true)
   await state.mutate(async () => {}, '已保存其他表单', 'term')
   assert.equal(state.unsaved.value, true)
  })
  assert.ifError(errors[0])
 } finally { globalThis.fetch = old }
})

test('deleting the current assistant session refreshes the history list', async () => {
 const oldFetch = globalThis.fetch, oldDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
 const oldConfirm = globalThis.window.confirm
 globalThis.window.confirm = () => true
 Object.defineProperty(globalThis, 'document', { configurable: true, value: { hidden: true, documentElement: { scrollTop: 0 }, addEventListener() {}, removeEventListener() {} } })
 const item = id => ({ id, title: `session ${id}`, updatedAt: '2026-10-31T10:00:00' })
 const detail = id => ({ session: item(id), turns: [], tasks: [], hasOlder: false })
 globalThis.fetch = async (path, options = {}) => {
  if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
  if (path === '/api/assistant/sessions/1' && options.method === 'DELETE') return new Response(null, { status: 204 })
  if (String(path).startsWith('/api/assistant/sessions?page=')) return Response.json({ items: [item(2)], total: 1, page: 1, pageSize: 10 })
  if (path === '/api/assistant/sessions/2') return Response.json(detail(2))
  throw Error(`unexpected ${path}`)
 }
 try {
  const errors = await checkPageSetup('/assistant-test', undefined, async state => {
   state.session.value = detail(1)
   state.sessionList.value = { items: [item(1), item(2)], total: 2, page: 1, pageSize: 10 }
   state.task.value = 'HISTORY'
   await state.deleteSession(item(1))
   assert.deepEqual(state.sessionList.value.items.map(value => value.id), [2])
   assert.equal(state.session.value.session.id, 2)
  })
  assert.ifError(errors[0])
 } finally {
  globalThis.fetch = oldFetch
  globalThis.window.confirm = oldConfirm
  if (oldDocument) Object.defineProperty(globalThis, 'document', oldDocument)
  else delete globalThis.document
 }
})

async function reservationNavigationRequests(destination) {
 const old = globalThis.fetch, paths=[]
 globalThis.fetch = async path => {
  paths.push(String(path))
  const body=String(path).includes('/reservations/mine')?{items:[],total:0,page:1,pageSize:20}:String(path)==='/api/reservations/3'?{id:3,status:'SUBMITTED',version:0,actions:[]}:[]
  return Response.json(body)
 }
 try {const errors=await checkPageSetup('/portal/reservations',destination);assert.ifError(errors[0]);return paths}
 finally {globalThis.fetch=old}
}
test('a detail link on the current reservation page fetches the selected application', async()=>{
 const paths=await reservationNavigationRequests('/portal/reservations?id=3&tab=details')
 assert.ok(paths.includes('/api/reservations/3'),JSON.stringify(paths))
})
test('a same-page mine list link applies the requested reservation status',async()=>{
 const paths=await reservationNavigationRequests('/portal/reservations?tab=mine&status=APPROVED')
 assert.ok(paths.some(path=>path.includes('/reservations/mine?')&&path.includes('status=APPROVED')),JSON.stringify(paths))
})

test('campus origin filters the venue API by its explicit place identity', async () => {
 const paths = await reservationNavigationRequests('/portal/reservations?venue=east-library&origin=campus&place=east-library&campus=EAST&view=model&placeName=Library')
 assert.ok(paths.some(path => path.startsWith('/api/reservations/venues?') && path.includes('placeId=east-library')), JSON.stringify(paths))
})

test('clearing the campus filter requests the full catalog while preserving the return origin', async () => {
 const paths = await reservationNavigationRequests('/portal/reservations?origin=campus&place=east-library&campus=EAST&view=model')
 assert.ok(paths.some(path => path.startsWith('/api/reservations/venues?') && !path.includes('placeId=')), JSON.stringify(paths))
})

test('repeated campus place query values cannot become a combined venue filter', async () => {
 const paths = await reservationNavigationRequests('/portal/reservations?venue=east-library&venue=west-library')
 assert.ok(paths.every(path => !path.includes('placeId=')), JSON.stringify(paths))
})

test('campus return restores the east library selection without starting a walking route', async () => {
 let inspected = false
 const errors = await checkPageSetup('/campus/explore?campus=EAST&view=model&focus=east-library', undefined, async state => {
  inspected = true
  assert.equal(state.currentPlace.value?.id, 'east-library')
  assert.equal(state.mode.value, 'model')
  assert.equal(state.routeActive.value, false)
  const target = new URL(state.reservationTarget.value, 'https://campus.invalid')
  assert.equal(target.searchParams.get('venue'), 'east-library')
 })
 assert.ifError(errors[0])
 assert.equal(inspected, true)
})

test('west campus return keeps the independent sandbox and selected west library', async () => {
 let inspected = false
 const errors = await checkPageSetup('/campus/explore?campus=WEST&view=model&focus=west-library', undefined, async state => {
  inspected = true
  assert.equal(state.currentCampus.value, 'WEST')
  assert.equal(state.currentPlace.value?.id, 'west-library')
  assert.equal(state.mode.value, 'model')
  assert.equal(state.routeActive.value, false)
  const target = new URL(state.reservationTarget.value, 'https://campus.invalid')
  assert.equal(target.searchParams.get('venue'), 'west-library')
 })
 assert.ifError(errors[0])
 assert.equal(inspected, true)
})
