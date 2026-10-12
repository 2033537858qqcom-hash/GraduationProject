import assert from 'node:assert/strict'
import console from 'node:console'
import { existsSync } from 'node:fs'
import { createServer } from 'vite'
import * as THREE from 'three'

// Regression checks for source provenance, orientation, selection and camera framing.
// Passing these checks is not a survey or a claim that every traced edge is exact.
const server = await createServer({ server: { middlewareMode: true } })
try {
  const { buildCampus } = await server.ssrLoadModule('/src/campus/buildCampus.ts')
  const { campusPlaces, campusTour } = await server.ssrLoadModule('/src/campus/manifest.ts')
  const { campusEvidence, mapFeatures, mapToModel, detailViews, detailRegistration, detailToMap } = await server.ssrLoadModule('/src/campus/eastCampusLayout.ts')
  const { campusOverviewPosition, overviewTarget } = await server.ssrLoadModule('/src/campus/framing.ts')
  assert.equal(campusEvidence.horizontalAccuracy, 'manual-image-trace')
  assert.equal(campusEvidence.heightAccuracy, 'uniform-display-only')
  for (const file of [campusEvidence.screenshot, campusEvidence.extentScreenshot, campusEvidence.southScreenshot]) {
    assert.ok(existsSync(`../${file}`), `The source screenshot must be preserved: ${file}`)
  }
  for (const view of Object.values(detailViews)) for (const file of [view.standard, view.satellite].filter(Boolean)) {
    assert.ok(existsSync(`../${file}`), `Enlarged source evidence must be preserved: ${file}`)
  }
  for (const anchor of detailRegistration.anchors) {
    const traced = detailToMap('central', anchor.detail)
    assert.ok(Math.hypot(traced[0] - anchor.overview[0], traced[1] - anchor.overview[1]) < 2,
      'Detail image registration must preserve the overview anchor positions')
  }
  assert.ok(campusPlaces.every(place => place.verification === 'map-observed' && place.heightStatus === 'display-only' && !place.id.startsWith('demo-')))
  assert.equal(new Set(campusPlaces.map(place => place.id)).size, campusPlaces.length)
  assert.equal(new Set(mapFeatures.map(feature => feature.id)).size, mapFeatures.length)
  assert.ok(campusTour.every(id => campusPlaces.some(place => place.id === id)))
  const place = id => campusPlaces.find(place => place.id === id)
  assert.ok(place('east-square').x < place('east-library').x, 'The square is west of the library on the observed map')
  assert.ok(place('east-shuhua').z < place('east-library').z, 'North must remain negative z')
  assert.ok(place('east-wenhua').x > place('east-library').x && place('east-wenhua').z > place('east-library').z)
  assert.ok(place('east-sports').x < place('east-library').x && place('east-water').x > place('east-library').x)
  assert.ok(place('east-h8').x < place('east-shuhua').x && place('east-shuhua').x < place('east-zhiyuan').x)
  assert.ok(place('east-jingyi').z > place('east-zhiyuan').z && place('east-jingyi').z < place('east-library').z)
  assert.ok(place('east-jiangong').z > place('east-xinke').z && place('east-jiangong').z < place('east-construction').z)
  assert.equal(place('east-clinic').kind, 'service', 'The clinic POI must not become an invented independent building')
  const scene = new THREE.Scene(), model = buildCampus(scene)
  scene.updateMatrixWorld(true)
  const meshes = scene.children.filter(object => object instanceof THREE.Mesh)
  assert.ok(meshes.length < 60, 'Static geometry should remain batched')
  assert.deepEqual(new Set(model.pickables.map(mesh => mesh.userData.placeId)), new Set(campusPlaces.map(place => place.id)))
  let triangles = 0
  for (const mesh of meshes) {
    const positions = mesh.geometry.getAttribute('position')
    assert.ok(positions?.count > 0 && Array.from(positions.array).every(Number.isFinite))
    assert.equal(mesh.geometry.getAttribute('normal').count, positions.count)
    triangles += (mesh.geometry.index?.count ?? positions.count) / 3
  }
  const ray = new THREE.Raycaster()
  for (const item of campusPlaces) {
    // A courtyard's bounding-box centre may intentionally be empty: use an actual roof face.
    const mesh = model.pickables.find(mesh => mesh.userData.placeId === item.id)
    const positions = mesh.geometry.getAttribute('position'), normals = mesh.geometry.getAttribute('normal')
    let hit = false
    for (let index = 0; index < positions.count; index += 3) {
      if (normals.getY(index) < 0.9) continue
      const centre = new THREE.Vector3()
      for (let offset = 0; offset < 3; offset++) centre.add(new THREE.Vector3().fromBufferAttribute(positions, index + offset))
      centre.multiplyScalar(1 / 3)
      ray.set(new THREE.Vector3(centre.x, 40, centre.z), new THREE.Vector3(0, -1, 0))
      if (ray.intersectObjects(model.pickables, false)[0]?.object.userData.placeId === item.id) { hit = true; break }
    }
    assert.ok(hit, `${item.name} must be selectable on its visible geometry`)
  }
  const [courtyardX, courtyardZ] = mapToModel([466, 513])
  ray.set(new THREE.Vector3(courtyardX, 40, courtyardZ), new THREE.Vector3(0, -1, 0))
  assert.ok(!ray.intersectObjects(model.pickables, false).some(hit => hit.object.userData.placeId === 'east-medical'), 'The medical courtyard must remain open')
  for (const [id, sourceView, pixel] of [
    ['east-library', 'central', [225, 438]], ['east-library', 'central', [228, 489]],
    ['east-shuhua', 'north', [411, 296]], ['east-zhiyuan', 'north', [621, 301]],
    ['east-jingyi', 'central', [374, 244]], ['east-jingyi', 'central', [586, 272]],
    ['east-wenhua', 'wenhua', [515, 377]], ['east-wenhua', 'wenhua', [450, 430]],
    ['east-jiangong', 'south', [268, 478]],
  ]) {
    const [x, z] = mapToModel(detailToMap(sourceView, pixel))
    ray.set(new THREE.Vector3(x, 40, z), new THREE.Vector3(0, -1, 0))
    assert.ok(!ray.intersectObjects(model.pickables, false).some(hit => hit.object.userData.placeId === id),
      `${id}: observed courtyard or opening must remain unroofed`)
  }
  const bounds = new THREE.Box3().setFromObject(scene)
  assert.ok(bounds.min.y >= -1.61 && bounds.max.y < 4, 'Unknown heights must not turn into detailed multi-storey buildings')
  for (const aspect of [0.8, 1, 1.5, 2.2]) {
    const camera = new THREE.PerspectiveCamera(38, aspect, 0.5, 1200)
    camera.position.copy(campusOverviewPosition(aspect)); camera.lookAt(overviewTarget); camera.updateMatrixWorld()
    assert.ok(camera.position.distanceTo(overviewTarget) < 760)
    for (const x of [bounds.min.x, bounds.max.x]) for (const y of [bounds.min.y, bounds.max.y]) for (const z of [bounds.min.z, bounds.max.z]) {
      const point = new THREE.Vector3(x, y, z).project(camera)
      assert.ok(Math.abs(point.x) < 0.9 && Math.abs(point.y) < 0.9 && point.z > -1 && point.z < 1, `Campus must fit at aspect ${aspect}`)
    }
  }
  model.select(place('east-medical')); model.select(place('east-library')); model.select(); model.dispose()
  console.log(`Campus checks passed: ${campusPlaces.length} map-labelled places, ${mapFeatures.length} traced features, ${meshes.length} root meshes, ${Math.round(triangles).toLocaleString()} triangles.`)
} finally { await server.close() }
