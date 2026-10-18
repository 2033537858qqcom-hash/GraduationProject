import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

let server, westData, manifest

before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)),
    configFile: false,
    logLevel: 'error',
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  westData = await server.ssrLoadModule('/src/campus/westCampusData.ts')
  manifest = await server.ssrLoadModule('/src/campus/manifest.ts')
})

after(async () => {
  await server?.close()
})

test('west campus metadata matches official registry', () => {
  assert.equal(westData.westCampusMeta.code, 'WEST')
  assert.equal(westData.westCampusMeta.name, '西校区')
  assert.equal(westData.westCampusMeta.address, '河南省郑州市新郑市新村镇107国道路东')
  assert.ok(westData.westCampusMeta.center[0] > 113.7 && westData.westCampusMeta.center[0] < 113.72)
  assert.ok(westData.westCampusMeta.center[1] > 34.42 && westData.westCampusMeta.center[1] < 34.44)
})

test('west campus places have unique ids, valid coordinates and valid categories', () => {
  const { westCampusPlaces } = westData
  assert.ok(westCampusPlaces.length >= 10, 'West campus must contain at least 10 core POIs')
  const ids = new Set()
  for (const place of westCampusPlaces) {
    assert.ok(place.id.startsWith('west-'), `Place id must start with west-: ${place.id}`)
    assert.ok(!ids.has(place.id), `Duplicate place id: ${place.id}`)
    ids.add(place.id)

    assert.ok(place.name.length > 0, `Place name required: ${place.id}`)
    assert.ok(manifest.campusCategories.some(c => c.id === place.category), `Invalid category: ${place.category}`)
    assert.ok(place.lnglat, `Place must have GCJ-02 lnglat: ${place.id}`)
    assert.equal(place.lnglat.length, 2)
    assert.ok(place.lnglat[0] > 113.70 && place.lnglat[0] < 113.72, `Longitude out of range: ${place.lnglat[0]}`)
    assert.ok(place.lnglat[1] > 34.42 && place.lnglat[1] < 34.44, `Latitude out of range: ${place.lnglat[1]}`)
  }
})

test('west campus main gate is located at measured coordinates on 107 National Highway', () => {
  const gate = westData.westCampusPlaces.find(p => p.id === 'west-south-gate')
  assert.ok(gate, 'West campus main south gate required')
  assert.equal(gate.kind, 'entrance')
  assert.ok(gate.description.includes('107国道'))
  assert.deepEqual(gate.lnglat, [113.712076, 34.429580])
})

test('west campus tour covers valid POIs from gate to student affairs', () => {
  const { westCampusTour, westCampusPlaces } = westData
  assert.ok(westCampusTour.length >= 8)
  for (const id of westCampusTour) {
    assert.ok(westCampusPlaces.some(p => p.id === id), `Tour contains unknown place id: ${id}`)
  }
  assert.equal(westCampusTour[0], 'west-south-gate', 'Tour starts from the main south gate')
})

test('west campus map framing returns responsive zoom', () => {
  const wide = westData.westCampusMapFrame(1200, 800)
  const compact = westData.westCampusMapFrame(400, 600)
  assert.ok(wide.zoom > compact.zoom)
  assert.deepEqual(wide.center, [113.7121, 34.4318])
})
