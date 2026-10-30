import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import * as THREE from 'three'
import { amapProxy, amapProxyGuard, amapProxyPath } from '../server/amapProxy.ts'

let server, geo, buildCampus, places
before(async () => {
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), cacheDir: fileURLToPath(new URL('../node_modules/.vite-tests/amap-integration', import.meta.url)), configFile: false, logLevel: 'error', server: { middlewareMode: true, hmr: false, ws: false } })
  geo = await server.ssrLoadModule('/src/campus/georeference.ts')
  ;({ buildCampus } = await server.ssrLoadModule('/src/campus/buildCampus.ts'))
  ;({ campusPlaces: places } = await server.ssrLoadModule('/src/campus/manifest.ts'))
})
after(async () => { await server?.close() })

test('map proxy overrides a client jscode and keeps the upstream fixed', () => {
  const path = amapProxyPath('/_AMapService/v4/map/styles?key=public&jscode=attacker&jscode=other&style=normal', 'server-only')
  const url = new URL(path, 'https://webapi.amap.com')
  assert.deepEqual(url.searchParams.getAll('jscode'), ['server-only'])
  assert.equal(url.searchParams.get('key'), 'public')
  assert.equal(url.pathname, '/v4/map/styles')
  assert.throws(() => amapProxyPath('/_AMapService//evil.example/x', 'secret'))
  assert.throws(() => amapProxyPath('https://evil.example/', 'secret'))
  const proxies = amapProxy('dummy')
  assert.equal(proxies['/_AMapService/v4/map/styles'].target, 'https://webapi.amap.com')
  assert.equal(proxies['/_AMapService'].target, 'https://restapi.amap.com')
})

test('map proxy never forwards campus login credentials or third-party cookies', () => {
  const hooks = new Map()
  amapProxy('dummy')['/_AMapService'].configure({ on: (name, fn) => hooks.set(name, fn) })
  const removed = []
  hooks.get('proxyReq')({ removeHeader: name => removed.push(name) })
  assert.ok(removed.includes('cookie') && removed.includes('authorization') && removed.includes('x-csrf-token'))
  const response = { headers: { 'set-cookie': ['third-party=value'], 'content-type': 'image/png' } }
  hooks.get('proxyRes')(response)
  assert.equal(response.headers['set-cookie'], undefined)
  assert.equal(response.headers['content-type'], 'image/png')
})

test('map proxy rejects writes and missing server configuration', () => {
  function run(secret, method, path = '/_AMapService/v4/map/styles') {
    let handler, status, passed = false
    amapProxyGuard(secret).configureServer({ middlewares: { use: fn => { handler = fn } } })
    handler({ method, url: path }, { writeHead: code => { status = code }, end() {} }, () => { passed = true })
    return { status, passed }
  }
  assert.equal(run('dummy', 'POST').status, 405)
  assert.equal(run('', 'GET').status, 503)
  assert.equal(run('dummy', 'GET', '/_AMapService//evil.example').status, 400)
  assert.equal(run('dummy', 'HEAD').passed, true)
  assert.equal(run('', 'POST', '/api/profile').passed, true)
})

test('affine registration handles north/south orientation, translation and scale', () => {
  const fit = geo.fitAffine([[0, 0], [10, 0], [0, 10]], [[100, 200], [140, 190], [110, 150]])
  assert.deepEqual(fit.apply([3, 7]), [119, 162])
  assert.ok(fit.scale > 0)
  assert.throws(() => geo.fitAffine([[0, 0], [1, 1], [2, 2]], [[0, 0], [1, 0], [0, 1]]))
  assert.throws(() => geo.fitAffine([[0, 0], [1, 0], [0, NaN]], [[0, 0], [1, 0], [0, 1]]))
  assert.throws(() => geo.fitAffine([[0, 0], [1, 0], [0, 1]], [[0, 0], [0, 0], [0, 0]]))
})

test('satellite registration checks an independent courtyard and separates the two north wings', () => {
  const check = geo.campusGeoEvidence.validation
  const predicted = geo.campusLngLat(check.model)
  const metres = Math.hypot((predicted[0] - check.lnglat[0]) * 111320 * Math.cos(check.lnglat[1] * Math.PI / 180), (predicted[1] - check.lnglat[1]) * 111320)
  assert.ok(metres < 12, `Independent courtyard discrepancy is ${metres.toFixed(1)} m; this is a visual check, not survey accuracy`)
  const dining = places.find(p => p.id === 'east-jinxiu-dining'), jingyi = places.find(p => p.id === 'east-jingyi')
  assert.ok(geo.campusLngLat([dining.x, dining.z])[0] < geo.campusLngLat([jingyi.x, jingyi.z])[0])
})

test('campus overview adapts to mobile width without changing its geographic center', () => {
  const desktop = geo.campusMapFrame(980, 535), mobile = geo.campusMapFrame(330, 330)
  assert.ok(mobile.zoom < desktop.zoom)
  assert.deepEqual(mobile.center, desktop.center)
  assert.ok(mobile.zoom >= 14 && desktop.zoom <= 17)
})

test('map model overlay cannot cover the satellite with a support plane or invented streets', () => {
  const root = new THREE.Group()
  const model = buildCampus(root, { includeContext: false })
  const meshes = root.children.filter(child => child instanceof THREE.Mesh)
  assert.ok(meshes.length > 10)
  const allowed = new Set(places.filter(p => p.locationType === 'outline' && p.kind === 'building' || p.locationType === 'point').map(p => p.id))
  assert.ok(meshes.every(mesh => !mesh.userData.placeId || allowed.has(mesh.userData.placeId)))
  for (const mesh of meshes) {
    mesh.geometry.computeBoundingBox()
    const box = mesh.geometry.boundingBox
    assert.ok(box.min.y >= 0, 'The below-ground support block cannot enter the map overlay')
    assert.ok(!['92a490', 'bac6c3', '438ca3', 'd9d1bd', 'ae7466', '6e947c', 'ad8c80'].includes(mesh.material.color.getHexString()), 'Ground, road and water fills must come from the live map')
    mesh.geometry.dispose(); mesh.material.dispose()
  }
  model.dispose()
})

test('SDK loader configures the proxy before loading, deduplicates scripts and permits retry', async t => {
  const previousWindow = globalThis.window, previousDocument = globalThis.document
  t.after(() => {
    if (previousWindow === undefined) delete globalThis.window; else globalThis.window = previousWindow
    if (previousDocument === undefined) delete globalThis.document; else globalThis.document = previousDocument
  })
  const scripts = [], timers = []
  globalThis.window = { location: { origin: 'https://campus.example' }, setTimeout: fn => timers.push(fn), clearTimeout() {} }
  globalThis.document = {
    createElement: () => ({ remove() { this.removed = true } }),
    head: { appendChild: script => { assert.equal(globalThis.window._AMapSecurityConfig.serviceHost, 'https://campus.example/_AMapService'); scripts.push(script) } },
  }
  const { loadAMap } = await import('../src/campus/amapSdk.ts')
  await assert.rejects(loadAMap(''), /Key/)
  const pending = loadAMap('1'.repeat(32))
  assert.equal(loadAMap('1'.repeat(32)), pending)
  assert.equal(scripts.length, 1)
  scripts[0].onerror()
  await assert.rejects(pending, /未能加载/)
  assert.equal(scripts[0].removed, true)
  const retry = loadAMap('1'.repeat(32))
  assert.equal(scripts.length, 2)
  globalThis.window.AMap = { Map: 'test-double' }
  scripts[1].onload()
  assert.equal(await retry, globalThis.window.AMap)
  assert.deepEqual(Object.keys(globalThis.window._AMapSecurityConfig), ['serviceHost'])
})
