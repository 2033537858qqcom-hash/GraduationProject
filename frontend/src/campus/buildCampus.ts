import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { campusCoverage, mapFeatures, mapRoads, mapToModel } from './eastCampusLayout'
import type { MapPoint, SurfaceKind } from './eastCampusLayout'
import type { CampusPlace } from './manifest'

export function buildCampus(scene: THREE.Scene) {
  const colors = { building: '#dbd0be', anonymous: '#e0ddd4', water: '#74a4b1', plaza: '#d9d1bd',
    track: '#ae7466', field: '#6e947c', court: '#ad8c80', point: '#315f87', base: '#92a490', road: '#bac6c3' }
  const materials = Object.fromEntries(Object.entries(colors).map(([name, color]) => [name,
    new THREE.MeshStandardMaterial({color, roughness: name === 'water' ? 0.4 : 0.86}),
  ])) as Record<keyof typeof colors, THREE.MeshStandardMaterial>
  const buckets = new Map<string, {material: THREE.Material; id?: string; geometries: THREE.BufferGeometry[]}>()
  const pickables: THREE.Mesh[] = []
  function shapeOf(outline: MapPoint[], holes: MapPoint[][] = []) {
    const shape = new THREE.Shape()
    outline.forEach((point, i) => { const [x, z] = mapToModel(point); if (i === 0) shape.moveTo(x, -z); else shape.lineTo(x, -z) })
    shape.closePath()
    holes.forEach(outline => {
      const hole = new THREE.Path()
      outline.forEach((point, i) => { const [x, z] = mapToModel(point); if (i === 0) hole.moveTo(x, -z); else hole.lineTo(x, -z) })
      hole.closePath(); shape.holes.push(hole)
    })
    return shape
  }
  function add(geometry: THREE.BufferGeometry, material: THREE.Material, id?: string) {
    const key = `${material.uuid}:${id ?? 'context'}`
    if (!buckets.has(key)) buckets.set(key, {material, id, geometries: []})
    buckets.get(key)!.geometries.push(geometry.index ? geometry.toNonIndexed() : geometry.clone())
    geometry.dispose()
  }
  function surface(outline: MapPoint[], holes: MapPoint[][], height: number, y: number, material: THREE.Material, id?: string) {
    const geometry = new THREE.ExtrudeGeometry(shapeOf(outline, holes), {depth: height, bevelEnabled: false})
    geometry.rotateX(-Math.PI / 2); geometry.translate(0, y, 0); add(geometry, material, id)
  }
  // A neutral support plane also carries peripheral streets and water outside the crop.
  surface([[-65, 40], [638, 40], [638, 611], [-65, 611]], [], 1.6, -1.6, materials.anonymous)
  surface(campusCoverage, [], 0.05, 0, materials.base)
  // Traced polylines, with no generated road grid, crossings or roadside furniture.
  mapRoads.forEach(road => {
    for (let i = 1; i < road.points.length; i++) {
      const [ax, az] = mapToModel(road.points[i - 1]!), [bx, bz] = mapToModel(road.points[i]!)
      const w = road.width * 0.35, length = Math.hypot(bx - ax, bz - az)
      const geometry = new THREE.BoxGeometry(w, 0.09, length + w * 0.15)
      geometry.rotateY(Math.atan2(bx - ax, bz - az)); geometry.translate((ax + bx) / 2, 0.1, (az + bz) / 2)
      add(geometry, materials.road)
      const junction = new THREE.CylinderGeometry(w / 2, w / 2, 0.09, 12)
      junction.translate(bx, 0.1, bz); add(junction, materials.road)
    }
  })
  const materialFor = (kind: SurfaceKind, labelled: boolean) => kind === 'building' ? labelled ? materials.building : materials.anonymous : materials[kind]
  mapFeatures.forEach(feature => {
    if (feature.kind === 'point') {
      const [x, z] = mapToModel(feature.outline[0]!)
      const marker = new THREE.CylinderGeometry(1.4, 1.4, 0.16, 24)
      marker.translate(x, feature.markerHeight ?? 0.28, z); add(marker, materials.point, feature.placeId)
    } else {
      // Equal-height masses encode no factual floor count or building height.
      const height = feature.kind === 'building' ? 3.2 : 0.08
      const y = feature.kind === 'field' ? 0.34 : feature.kind === 'water' ? 0.19 : 0.24
      surface(feature.outline, feature.holes ?? [], height, y, materialFor(feature.kind, !!feature.placeId), feature.placeId)
    }
  })
  for (const bucket of buckets.values()) {
    const geometry = mergeGeometries(bucket.geometries)
    bucket.geometries.forEach(geometry => geometry.dispose())
    if (!geometry) throw new Error('Campus geometry could not be merged')
    const mesh = new THREE.Mesh(geometry, bucket.material)
    mesh.castShadow = bucket.material === materials.building || bucket.material === materials.anonymous
    mesh.receiveShadow = true
    if (bucket.id) { mesh.userData.placeId = bucket.id; pickables.push(mesh) }
    scene.add(mesh)
  }
  const selectionMaterial = new THREE.MeshBasicMaterial({color: '#447fa7', transparent: true, opacity: 0.2, depthWrite: false})
  const selection = new THREE.Group()
  selection.visible = false; scene.add(selection)
  return {
    pickables,
    dispose() { selectionMaterial.dispose() },
    select(place?: CampusPlace) {
      for (const mesh of [...selection.children]) {
        selection.remove(mesh)
        if (mesh instanceof THREE.Mesh) mesh.geometry.dispose()
      }
      selection.visible = !!place
      if (!place) return
      // Highlight traced geometry and courtyard holes, not a guessed rectangle.
      pickables.filter(mesh => mesh.userData.placeId === place.id).forEach(mesh => {
        const overlay = new THREE.Mesh(mesh.geometry.clone(), selectionMaterial)
        overlay.position.y = 0.04; selection.add(overlay)
      })
    },
    setEvening(evening: boolean) {
      materials.point.emissive.set(evening ? '#538bb8' : '#000000')
      materials.point.emissiveIntensity = evening ? 0.5 : 0
    },
  }
}
