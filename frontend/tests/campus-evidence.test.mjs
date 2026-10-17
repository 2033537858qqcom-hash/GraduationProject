import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import * as THREE from 'three'
import { containsPoint, intersectionArea } from '../scripts/campus-geometry.mjs'

let server, layout, places, buildCampus
before(async () => {
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, logLevel: 'error', server: { middlewareMode: true, hmr: false, ws: false } })
  layout = await server.ssrLoadModule('/src/campus/eastCampusLayout.ts')
  ;({ campusPlaces: places } = await server.ssrLoadModule('/src/campus/manifest.ts'))
  ;({ buildCampus } = await server.ssrLoadModule('/src/campus/buildCampus.ts'))
})
after(async () => { await server?.close() })

test('BUG-C1: 毓秀楼 keeps its confirmed identity instead of 锦绣楼', () => {
  assert.equal(places.find(p => p.id === 'east-jinxiu')?.name, '毓秀楼')
  assert.equal(layout.mapFeatures.find(f => f.id === 'jinxiu-south')?.placeId, 'east-fenghua', 'The supplied distribution plan identifies this separate building as 风华楼')
})

test('BUG-C2: 锦绣餐厅 and 静逸楼 are independent buildings separated by a road without physical connection', () => {
  const dining = places.find(p => p.id === 'east-jinxiu-dining')
  const jingyiPlace = places.find(p => p.id === 'east-jingyi')
  assert.ok(dining, 'The restaurant needs a separate service identity')
  assert.equal(dining.kind, 'building')
  assert.equal(dining.locationType, 'outline')
  assert.ok(!dining.relatedPlaceId, '锦绣餐厅 and 静逸楼 have no shared physical wing')
  const features = layout.mapFeatures.filter(f => f.placeId === dining.id)
  assert.equal(features.length, 1)
  const jingyi = layout.mapFeatures.find(f => f.id === 'jingyi-roof')
  assert.equal(intersectionArea(features[0], jingyi), 0, '锦绣餐厅 and 静逸楼 must have zero intersection')
  assert.ok(dining.x < jingyiPlace.x, '锦绣餐厅 is west of 静逸楼')
  assert.ok(containsPoint(jingyi, layout.detailToMap('central', [574,230])), '静逸楼 POI belongs in the east building')
  const road = layout.mapRoads.find(r => r.id === 'jinxiu-jingyi-road')
  assert.ok(road, 'A campus road must run between 锦绣餐厅 and 静逸楼 to separate them')
})

test('BUG-C3: unverified room numbers and capacities cannot be published as bookable campus facts', () => {
  assert.ok(places.every(p => !p.reservableVenues?.length), 'Map POIs cannot establish room numbers, capacity or availability')
  const jingyi = places.find(p => p.id === 'east-jingyi')
  assert.ok(!/语音实验室|跨学科研讨间|外语视听说/.test(jingyi.description + jingyi.features.join('')))
})

test('BUG-C4: guessed dormitory and gym blocks cannot cover the sports facilities', () => {
  for (const feature of layout.mapFeatures) {
    assert.ok(!/^(dorm-block-|gym-main-|gym-west-|gym-south-)/.test(feature.id), `${feature.id} had no traced outline evidence`)
  }
  const buildings = layout.mapFeatures.filter(f => f.kind === 'building' && f.placeId)
  const surfaces = layout.mapFeatures.filter(f => ['track', 'court', 'water'].includes(f.kind))
  for (const building of buildings) for (const surface of surfaces) {
    assert.ok(intersectionArea(building, surface) < 0.01, `${building.id} intersects ${surface.id}`)
  }
})

test('BUG-C5: independently named buildings cannot intersect beyond the recorded tracing joins', () => {
  const buildings = layout.mapFeatures.filter(f => f.kind === 'building' && f.placeId)
  for (let i = 0; i < buildings.length; i++) for (let j = i + 1; j < buildings.length; j++) {
    const a = buildings[i], b = buildings[j]
    if (a.placeId && a.placeId === b.placeId) continue
    // These two old edges differ by less than one source pixel after registration.
    const edgeJoin = [a.id, b.id].includes('jiangong-roof') && [a.id, b.id].some(id => ['xinke-west', 'xinke-east'].includes(id))
    assert.ok(intersectionArea(a, b) <= (edgeJoin ? 10 : 0.01), `${a.id} intersects ${b.id}`)
  }
})

test('BUG-C6: supplied plan corrects real identities while unconfirmed or nonexistent footprints are excluded', () => {
  const unverified = ['east-dorms', 'east-jidian', 'east-golf', 'statue-huangdi', 'statue-kongzi', 'east-teacher-housing']
  for (const id of unverified) assert.ok(!places.some(p => p.id === id), `${id} has no confirmed location or does not exist`)
  assert.ok(layout.mapFeatures.filter(f => f.placeId === 'south-gate').every(f => f.kind === 'point'), 'The entrance POI must not be a solid wall across the road')
  const place = id => places.find(p => p.id === id)
  assert.ok(place('east-gym').x < place('east-swimming').x && place('east-gym').z < place('east-swimming').z)
  assert.ok(place('east-canteen-wenhua').x < place('east-admin').x)
  assert.ok(place('east-construction-training').z > place('east-canteen-wenhua').z)
  assert.ok(place('east-auditorium').x > place('east-admin').x)
  assert.ok(place('east-affairs').z < place('east-library').z)
  for (const id of ['east-h1', 'east-h2', 'east-affairs']) {
    assert.equal(place(id).locationType, 'point')
    assert.equal(place(id).verification, 'reference-located')
  }
})

test('BUG-C7: location metadata and marker selection agree with rendered geometry', () => {
  const scene = new THREE.Scene(), model = buildCampus(scene)
  try {
    for (const place of places) {
      const meshes = model.pickables.filter(mesh => mesh.userData.placeId === place.id)
      assert.ok(meshes.length, `${place.id} has no selectable geometry`)
      const top = Math.max(...meshes.map(mesh => { mesh.geometry.computeBoundingBox(); return mesh.geometry.boundingBox.max.y }))
      assert.ok(Math.abs(place.height - top) < 0.001, `${place.id}: label height disagrees with the geometry`)
      assert.ok(place.sourceScreenshot, `${place.id}: missing source evidence`)
    }
    const dining = places.find(p => p.id === 'east-jinxiu-dining')
    model.select(dining)
    const ray = new THREE.Raycaster(new THREE.Vector3(dining.x, 40, dining.z), new THREE.Vector3(0, -1, 0))
    assert.equal(ray.intersectObjects(model.pickables, false)[0]?.object.userData.placeId, dining.id)
  } finally {
    model.dispose()
    scene.traverse(object => { if (object instanceof THREE.Mesh) { object.geometry.dispose(); for (const material of [object.material].flat()) material.dispose() } })
  }
})
