import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { readFile } from 'node:fs/promises'
import * as THREE from 'three'
import { intersectionArea } from '../scripts/campus-geometry.mjs'

let server, westData, mapData, plan, manifest, layout, sceneData, buildCampus, framing, evidence

before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)),
    configFile: false,
    cacheDir: fileURLToPath(new URL('../node_modules/.vite-tests/west-campus', import.meta.url)),
    logLevel: 'error',
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  westData = await server.ssrLoadModule('/src/campus/westCampusData.ts')
  mapData = await server.ssrLoadModule('/src/campus/westCampusMapData.ts')
  plan = await server.ssrLoadModule('/src/campus/westCampusPlan.ts')
  manifest = await server.ssrLoadModule('/src/campus/manifest.ts')
  layout = await server.ssrLoadModule('/src/campus/westCampusLayout.ts')
  ;({ campusSceneData: sceneData } = await server.ssrLoadModule('/src/campus/campusSceneData.ts'))
  ;({ buildCampus } = await server.ssrLoadModule('/src/campus/buildCampus.ts'))
  framing = await server.ssrLoadModule('/src/campus/framing.ts')
  evidence = JSON.parse(await readFile(new URL('../../assets/campus/west/amap-pois-2026-10-19.json',import.meta.url),'utf8'))
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
  const { westMapPlaces: westCampusPlaces } = mapData
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

test('west locations match independently saved AMap API results, with no invented footprints', () => {
  for (const place of mapData.westMapPlaces) {
    const poi = evidence.pois.find(poi => place.sourceUrl.endsWith(`/${poi.id}`))
    assert.ok(poi, `Missing API evidence for ${place.id}`)
    assert.deepEqual(place.lnglat,poi.lnglat)
    assert.equal(place.campusId,'WEST')
    assert.equal(place.locationType,'point','A POI cannot establish a building footprint')
    const position=layout.westLngLatToModel(poi.lnglat)
    assert.deepEqual([place.x,place.z],position)
    const roundTrip=layout.westModelToLngLat(position)
    assert.ok(Math.abs(roundTrip[0]-poi.lnglat[0])<1e-10 && Math.abs(roundTrip[1]-poi.lnglat[1])<1e-10)
  }
})

test('west campus tour covers verified places without the former guessed gate or affairs station', () => {
  const { westMapTour: westCampusTour, westMapPlaces: westCampusPlaces } = mapData
  assert.ok(westCampusTour.length >= 8)
  for (const id of westCampusTour) {
    assert.ok(westCampusPlaces.some(p => p.id === id), `Tour contains unknown place id: ${id}`)
  }
  assert.equal(westCampusTour[0], 'west-incubator')
  for (const id of ['west-south-gate','west-square','west-affairs-station','west-dorm-group']) {
    assert.ok(!westCampusPlaces.some(p=>p.id===id),`${id} has no confirmed evidence`)
  }
})

test('west campus map framing returns responsive zoom', () => {
  const wide = westData.westCampusMapFrame(1200, 800)
  const compact = westData.westCampusMapFrame(400, 600)
  assert.ok(wide.zoom > compact.zoom)
  assert.deepEqual(wide.center, westData.westCampusMeta.center)
  assert.deepEqual(compact.center,wide.center)
})

test('homonymous first buildings stay separate and unlocated official buildings receive no false coordinates', () => {
  const buildings=mapData.westMapPlaces.filter(p=>p.name.startsWith('1号楼'))
  assert.equal(buildings.length,2)
  assert.notEqual(buildings[0].sourceUrl,buildings[1].sourceUrl)
  assert.notDeepEqual(buildings[0].lnglat,buildings[1].lnglat)
  assert.equal(westData.westUnlocatedBuildings.length,2)
  assert.ok(westData.westUnlocatedBuildings.every(b=>!b.lnglat && b.source.startsWith('https://')))
  assert.ok(!mapData.westMapPlaces.some(p=>p.id==='west-library'))
})

test('west 3D scene has its own traced roofs, roads and selectable markers, without East-campus meshes', () => {
  const root=new THREE.Group(), model=buildCampus(root,{campusId:'WEST',representation:'geographic'})
  try {
    assert.ok(layout.westMapFeatures.filter(f=>f.kind==='building').length>=30)
    assert.ok(layout.westMapRoads.length>=5)
    assert.equal(sceneData('WEST','geographic').places.length,mapData.westMapPlaces.length)
    assert.ok(model.pickables.every(mesh=>mesh.userData.placeId.startsWith('west-')))
    for (const place of mapData.westMapPlaces) {
      const meshes=model.pickables.filter(mesh=>mesh.userData.placeId===place.id)
      assert.ok(meshes.length,`Missing ${place.id} marker`)
      const maxY=Math.max(...meshes.map(mesh=>{mesh.geometry.computeBoundingBox();return mesh.geometry.boundingBox.max.y}))
      assert.ok(Math.abs(maxY-place.height)<0.001)
      const ray=new THREE.Raycaster(new THREE.Vector3(place.x,50,place.z),new THREE.Vector3(0,-1,0))
      assert.equal(ray.intersectObjects(model.pickables,false)[0]?.object.userData.placeId,place.id)
    }
  } finally {
    model.dispose()
    root.traverse(o=>{if(o instanceof THREE.Mesh){o.geometry.dispose();for(const m of [o.material].flat())m.dispose()}})
  }
})

test('west overview fits the complete north/south envelope at desktop and phone aspect ratios', () => {
  for (const aspect of [1.65,0.62]) {
    const target=new THREE.Vector3(-15,-10,5), bounds=layout.westModelBounds
    const camera=new THREE.PerspectiveCamera(38,aspect,0.5,2500)
    camera.position.copy(framing.campusOverviewPosition(aspect,bounds,target));camera.lookAt(target);camera.updateMatrixWorld()
    for(const x of [bounds.minX,bounds.maxX])for(const z of [bounds.minZ,bounds.maxZ])for(const y of [-4,8]) {
      const point=new THREE.Vector3(x,y,z).project(camera)
      assert.ok(Math.abs(point.x)<=0.89 && Math.abs(point.y)<=0.89 && point.z>-1 && point.z<1)
    }
  }
})

test('west traced roofs do not overlap one another or cover the visible sports surfaces', () => {
  const buildings=layout.westMapFeatures.filter(f=>f.kind==='building')
  const surfaces=layout.westMapFeatures.filter(f=>['track','field'].includes(f.kind))
  for(let i=0;i<buildings.length;i++)for(let j=i+1;j<buildings.length;j++) {
    assert.ok(intersectionArea(buildings[i],buildings[j])<0.01,`${buildings[i].id} overlaps ${buildings[j].id}`)
  }
  for(const building of buildings)for(const surface of surfaces) {
    assert.ok(intersectionArea(building,surface)<0.01,`${building.id} overlaps ${surface.id}`)
  }
})

test('supplied west plan completes named teaching, training, housing and dining places without fake geographic coordinates', async () => {
  const places=westData.westCampusPlaces
  assert.equal(places.length,39)
  assert.equal(new Set(places.map(p=>p.id)).size,places.length)
  for(const name of ['图书馆','诚毅楼','科技信息楼','汽车实训室','城轨实训室','思源餐厅','钢构餐厅','购电中心','澡堂',
    '12号楼','13号楼','11号楼','20号楼','21号楼','22号楼','24号楼','19号楼','23号楼',
    '华信路','文明大道','成才大道','桃园路']) {
    assert.ok(places.some(p=>p.name===name),`${name} must be transcribed from the supplied plan`)
  }
  assert.ok(places.every(p=>p.verification==='reference-located' && !p.lnglat))
  assert.ok(places.every(p=>p.sourceUrl===plan.westPlanEvidence.url))
  const image=await readFile(new URL('../../frontend/public/campus/west-campus-layout-reference.jpg',import.meta.url))
  assert.ok(image.length>10000)
  assert.equal(plan.westPlanEvidence.scale,'not-to-scale')
  assert.equal(plan.westPlanEvidence.date,'unknown')
  assert.ok(!westData.westUnlocatedBuildings.some(p=>['图书馆','西校区图书馆','诚毅楼','科技信息楼'].includes(p.name)))
})

test('west plan preserves the supplied north/south and east/west relationships, including the separate fours', () => {
  const p=id=>westData.westCampusPlaces.find(p=>p.id===id)
  assert.ok(p('west-chengyi').x<p('west-library').x)
  assert.ok(p('west-library').x<p('west-experiment-building').x)
  assert.ok(p('west-information').z>p('west-chengyi').z)
  assert.ok(p('west-information').z<p('west-culture-square').z && p('west-culture-square').z<p('west-dorm-5').z)
  assert.ok(p('west-tech-center').z<p('west-auto-training').z && p('west-auto-training').z<p('west-rail-training').z)
  assert.ok(p('west-sports-ground').z<p('west-dorm-7').z && p('west-dorm-7').z<p('west-library').z)
  assert.ok(p('west-building-4').z<p('west-dorm-4').z && p('west-building-4').x<p('west-dorm-4').x)
  assert.ok(p('west-dorm-12').x<p('west-dorm-13').x)
  assert.ok(p('west-dorm-12').z<p('west-dorm-11').z && p('west-dorm-11').z<p('west-siyuan-canteen').z)
  assert.ok(p('west-siyuan-canteen').x<p('west-dorm-4').x && p('west-dorm-4').x<p('west-dorm-22').x)
  assert.ok(p('west-dorm-22').x<p('west-dorm-24').x)
  assert.ok(p('west-food-street').x<p('west-dorm-19').x && p('west-dorm-19').x<p('west-dorm-23').x)
  assert.ok(p('west-teaching-2').z<p('west-lab-3').z && p('west-lab-3').z<p('west-steel-canteen').z)
})

test('shared Mingde and canteen services do not create duplicate solid buildings', () => {
  const p=id=>westData.westCampusPlaces.find(p=>p.id===id)
  assert.equal(p('west-clinic').relatedPlaceId,'west-mingde')
  assert.equal(p('west-bathhouse').relatedPlaceId,'west-steel-canteen')
  assert.ok(p('west-mingde').features.includes('明德楼教室') && p('west-mingde').features.includes('明德楼宿舍'))
  for(const id of ['west-clinic','west-bathhouse','west-building-8']) {
    assert.equal(p(id).locationType,'point')
    assert.ok(!plan.westPlanFeatures.some(f=>f.kind==='building' && f.placeId===id))
  }
})

test('reference sandbox and georeferenced map overlay use different geometry and never project the schematic onto AMap', () => {
  const reference=sceneData('WEST'), geographic=sceneData('WEST','geographic')
  assert.ok(reference.places.some(p=>p.id==='west-library'))
  assert.ok(!geographic.places.some(p=>p.id==='west-library'))
  assert.ok(reference.roads.some(r=>r.name==='成才大道'))
  const root=new THREE.Group(),model=buildCampus(root,{campusId:'WEST'})
  try {
    for(const place of westData.westCampusPlaces) {
      const meshes=model.pickables.filter(m=>m.userData.placeId===place.id)
      assert.ok(meshes.length,`${place.id} must be selectable`)
      const top=Math.max(...meshes.map(m=>{m.geometry.computeBoundingBox();return m.geometry.boundingBox.max.y}))
      assert.ok(Math.abs(top-place.height)<0.001,`${place.id} label and rendered height disagree`)
      const ray=new THREE.Raycaster(new THREE.Vector3(place.x,50,place.z),new THREE.Vector3(0,-1,0))
      assert.equal(ray.intersectObjects(model.pickables,false)[0]?.object.userData.placeId,place.id)
    }
    const buildings=plan.westPlanFeatures.filter(f=>f.kind==='building')
    for(let i=0;i<buildings.length;i++)for(let j=i+1;j<buildings.length;j++) {
      assert.ok(intersectionArea(buildings[i],buildings[j])<0.01,`${buildings[i].id} overlaps ${buildings[j].id}`)
    }
  } finally {
    model.dispose()
    root.traverse(o=>{if(o instanceof THREE.Mesh){o.geometry.dispose();for(const m of [o.material].flat())m.dispose()}})
  }
})
