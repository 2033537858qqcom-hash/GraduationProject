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

test('workbench initializes its task queue before starting filter watchers', async () => {
  const errors = await checkPageSetup('/workbench')
  assert.ifError(errors[0])
})

for (const path of ['/campus/explore', '/portal', '/portal/learning', '/portal/leave', '/portal/certificates', '/housing/mine',
  '/repairs/mine', '/academic', '/admin/content', '/admin/foundation', '/admin/resources']) {
  test(`page setup smoke: ${path}`, async () => {
    const errors = await checkPageSetup(path)
    assert.ifError(errors[0])
  })
}

test('personal schedule catches unavailable sources during initial load without setup errors', async () => {
 const errors = await checkPageSetup('/portal/schedule')
 assert.ifError(errors[0])
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
