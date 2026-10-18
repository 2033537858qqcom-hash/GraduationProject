import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { campusCoverage, mapFeatures, mapRoads, mapToModel } from './eastCampusLayout'
import type { MapPoint, SurfaceKind } from './eastCampusLayout'
import { featureVerticalRange, sportsGrandstandRoofTop } from './manifest'
import type { CampusPlace } from './manifest'

function createFacadeTextures() {
  const kinds = ['building', 'library', 'sports', 'gym', 'annex', 'canteen', 'anonymous'] as const
  const textures: Record<string, { map: THREE.DataTexture; roughnessMap: THREE.DataTexture }> = {}
  const w = 64, h = 64
  for (const kind of kinds) {
    const colorData = new Uint8Array(w * h * 4)
    const roughData = new Uint8Array(w * h * 4)
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const idx = (y * w + x) * 4
        // Clean off-white porcelain / warm limestone base
        let r = 248, g = 247, b = 244, rough = 210
        if (y < 4 || y > 60) {
          // Subtle floor slab / parapet line
          r = 218; g = 215; b = 210; rough = 230
        } else if (y >= 16 && y <= 48 && x >= 8 && x <= 56) {
          // Ribbon window band
          const isMullion = (x >= 31 && x <= 33) || (x <= 9) || (x >= 55)
          if (isMullion) {
            r = 160; g = 170; b = 180; rough = 150
          } else {
            // Elegant modern architectural glass with soft sky gradient
            const grad = (y - 16) / 32
            if (kind === 'library') {
              r = Math.floor(130 + 40 * grad); g = Math.floor(175 + 40 * grad); b = Math.floor(220 + 30 * grad)
            } else if (kind === 'gym') {
              r = Math.floor(185 + 20 * grad); g = Math.floor(105 + 20 * grad); b = Math.floor(95 + 25 * grad)
            } else if (kind === 'canteen') {
              r = Math.floor(205 + 25 * grad); g = Math.floor(190 + 25 * grad); b = Math.floor(170 + 30 * grad)
            } else {
              r = Math.floor(140 + 35 * grad); g = Math.floor(180 + 35 * grad); b = Math.floor(215 + 30 * grad)
            }
            // Crisp subtle specular glass gleam
            if (Math.abs((x - 8) - (y - 16) * 1.2) < 3) {
              r = Math.min(255, r + 45); g = Math.min(255, g + 45); b = Math.min(255, b + 45)
            }
            rough = 35
          }
        } else if (x < 3 || x > 60) {
          r = 230; g = 227; b = 222; rough = 220
        }
        colorData[idx] = r; colorData[idx + 1] = g; colorData[idx + 2] = b; colorData[idx + 3] = 255
        roughData[idx] = rough; roughData[idx + 1] = rough; roughData[idx + 2] = rough; roughData[idx + 3] = 255
      }
    }
    const map = new THREE.DataTexture(colorData, w, h, THREE.RGBAFormat)
    map.wrapS = THREE.RepeatWrapping; map.wrapT = THREE.RepeatWrapping
    map.repeat.set(0.18, 0.42); map.needsUpdate = true
    const roughnessMap = new THREE.DataTexture(roughData, w, h, THREE.RGBAFormat)
    roughnessMap.wrapS = THREE.RepeatWrapping; roughnessMap.wrapT = THREE.RepeatWrapping
    roughnessMap.repeat.set(0.18, 0.42); roughnessMap.needsUpdate = true
    textures[kind] = { map, roughnessMap }
  }
  return textures
}

function createWaterTexture(): THREE.DataTexture {
  const w = 64, h = 64
  const data = new Uint8Array(w * h * 4)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const idx = (y * w + x) * 4
      const wave = Math.sin(x * 0.35 + y * 0.25) * 0.5 + Math.cos(x * 0.2 - y * 0.4) * 0.5
      data[idx] = Math.floor(130 + wave * 30)
      data[idx + 1] = Math.floor(190 + wave * 25)
      data[idx + 2] = Math.floor(235 + wave * 15)
      data[idx + 3] = 255
    }
  }
  const texture = new THREE.DataTexture(data, w, h, THREE.RGBAFormat)
  texture.wrapS = THREE.RepeatWrapping; texture.wrapT = THREE.RepeatWrapping
  texture.repeat.set(0.2, 0.2); texture.needsUpdate = true
  return texture
}

function buildCampusTrees(scene: THREE.Object3D) {
  const trunk = new THREE.CylinderGeometry(0.13, 0.2, 1.0, 5)
  trunk.translate(0, 0.5, 0)
  const tc = new Float32Array(trunk.attributes.position.count * 3)
  for (let i = 0; i < tc.length; i += 3) { tc[i] = 0.36; tc[i + 1] = 0.26; tc[i + 2] = 0.18 }
  trunk.setAttribute('color', new THREE.BufferAttribute(tc, 3))
  const crown = new THREE.ConeGeometry(0.8, 1.9, 6)
  crown.translate(0, 1.85, 0)
  const cc = new Float32Array(crown.attributes.position.count * 3)
  for (let i = 0; i < cc.length; i += 3) { cc[i] = 0.26; cc[i + 1] = 0.52; cc[i + 2] = 0.28 }
  crown.setAttribute('color', new THREE.BufferAttribute(cc, 3))
  const treeGeo = mergeGeometries([trunk, crown])
  trunk.dispose(); crown.dispose()
  if (!treeGeo) return null

  const positions: [number, number][] = []
  const treeRoads = new Set(['south-approach', 'north-axis', 'east-internal', 'central-east-curve', 'central-west-curve', 'east-bank', 'library-ring'])
  mapRoads.filter(r => treeRoads.has(r.id)).forEach(road => {
    for (let i = 1; i < road.points.length; i++) {
      const [ax, az] = mapToModel(road.points[i - 1]!)
      const [bx, bz] = mapToModel(road.points[i]!)
      const dx = bx - ax, dz = bz - az
      const len = Math.hypot(dx, dz)
      if (len < 12) continue
      const nx = -dz / len, nz = dx / len
      const count = Math.max(1, Math.floor(len / 18))
      const offset = road.width * 0.35 / 2 + 2.2
      for (let s = 1; s <= count; s++) {
        const t = s / (count + 1)
        const mx = ax + dx * t, mz = az + dz * t
        positions.push([mx + nx * offset, mz + nz * offset])
        positions.push([mx - nx * offset, mz - nz * offset])
      }
    }
  })

  const treeMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.85 })
  const instanced = new THREE.InstancedMesh(treeGeo, treeMat, positions.length)
  instanced.castShadow = true
  instanced.receiveShadow = true
  const dummy = new THREE.Object3D()
  positions.forEach(([x, z], i) => {
    dummy.position.set(x, 0, z)
    dummy.rotation.y = (i * 1.37) % (Math.PI * 2)
    const scale = 0.85 + (i % 4) * 0.08
    dummy.scale.set(scale, scale, scale)
    dummy.updateMatrix()
    instanced.setMatrixAt(i, dummy.matrix)
  })
  instanced.instanceMatrix.needsUpdate = true
  scene.add(instanced)
  return { mesh: instanced, geometry: treeGeo, material: treeMat }
}

export function buildCampus(scene: THREE.Object3D, options: { includeContext?: boolean } = {}) {
  const facadeTextures = createFacadeTextures()
  const waterTexture = createWaterTexture()
  const colors = {
    building: '#f6f4ed',
    library: '#faf8f3',
    sports: '#d8e5ef',
    gym: '#b85247',
    annex: '#eeeae2',
    canteen: '#faeee2',
    anonymous: '#ebe8df',
    water: '#569fcc',
    plaza: '#f0f3f6',
    track: '#cc6a59',
    field: '#5ea274',
    court: '#4f8fc5',
    point: '#2b6694',
    base: '#86b488',
    road: '#f7fafc',
    grandstand: '#d2dbe3',
  }
  const materials = {
    building: new THREE.MeshStandardMaterial({ color: colors.building, map: facadeTextures.building.map, roughnessMap: facadeTextures.building.roughnessMap, roughness: 0.85 }),
    library: new THREE.MeshStandardMaterial({ color: colors.library, map: facadeTextures.library.map, roughnessMap: facadeTextures.library.roughnessMap, roughness: 0.80 }),
    sports: new THREE.MeshStandardMaterial({ color: colors.sports, map: facadeTextures.sports.map, roughnessMap: facadeTextures.sports.roughnessMap, roughness: 0.78 }),
    gym: new THREE.MeshStandardMaterial({ color: colors.gym, map: facadeTextures.gym.map, roughnessMap: facadeTextures.gym.roughnessMap, roughness: 0.82 }),
    annex: new THREE.MeshStandardMaterial({ color: colors.annex, map: facadeTextures.annex.map, roughnessMap: facadeTextures.annex.roughnessMap, roughness: 0.86 }),
    canteen: new THREE.MeshStandardMaterial({ color: colors.canteen, map: facadeTextures.canteen.map, roughnessMap: facadeTextures.canteen.roughnessMap, roughness: 0.84 }),
    anonymous: new THREE.MeshStandardMaterial({ color: colors.anonymous, map: facadeTextures.anonymous.map, roughnessMap: facadeTextures.anonymous.roughnessMap, roughness: 0.86 }),
    water: new THREE.MeshStandardMaterial({ color: colors.water, map: waterTexture, roughness: 0.14, metalness: 0.26, polygonOffset: true, polygonOffsetFactor: -0.5, polygonOffsetUnits: -1 }),
    plaza: new THREE.MeshStandardMaterial({ color: colors.plaza, roughness: 0.84, polygonOffset: true, polygonOffsetFactor: -0.5, polygonOffsetUnits: -1 }),
    track: new THREE.MeshStandardMaterial({ color: colors.track, roughness: 0.85, polygonOffset: true, polygonOffsetFactor: -0.5, polygonOffsetUnits: -1 }),
    field: new THREE.MeshStandardMaterial({ color: colors.field, roughness: 0.85, polygonOffset: true, polygonOffsetFactor: -0.5, polygonOffsetUnits: -1 }),
    court: new THREE.MeshStandardMaterial({ color: colors.court, roughness: 0.85, polygonOffset: true, polygonOffsetFactor: -0.5, polygonOffsetUnits: -1 }),
    point: new THREE.MeshStandardMaterial({ color: colors.point, roughness: 0.82, polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -2 }),
    base: new THREE.MeshStandardMaterial({ color: colors.base, roughness: 0.86 }),
    road: new THREE.MeshStandardMaterial({ color: colors.road, roughness: 0.82, polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -2 }),
    grandstand: new THREE.MeshStandardMaterial({ color: '#d2dbe3', metalness: 0.62, roughness: 0.28 }),
  }
  const buckets = new Map<string, { material: THREE.Material; id?: string; geometries: THREE.BufferGeometry[] }>()
  const pickables: THREE.Mesh[] = []

  function shapeOf(outline: MapPoint[], holes: MapPoint[][] = []) {
    const shape = new THREE.Shape()
    outline.forEach((point, i) => {
      const [x, z] = mapToModel(point)
      if (i === 0) shape.moveTo(x, -z)
      else shape.lineTo(x, -z)
    })
    shape.closePath()
    holes.forEach(outline => {
      const hole = new THREE.Path()
      outline.forEach((point, i) => {
        const [x, z] = mapToModel(point)
        if (i === 0) hole.moveTo(x, -z)
        else hole.lineTo(x, -z)
      })
      hole.closePath()
      shape.holes.push(hole)
    })
    return shape
  }

  function add(geometry: THREE.BufferGeometry, material: THREE.Material, id?: string) {
    const key = `${material.uuid}:${id ?? 'context'}`
    if (!buckets.has(key)) buckets.set(key, { material, id, geometries: [] })
    buckets.get(key)!.geometries.push(geometry.index ? geometry.toNonIndexed() : geometry.clone())
    geometry.dispose()
  }

  function surface(outline: MapPoint[], holes: MapPoint[][], height: number, y: number, material: THREE.Material, id?: string) {
    const geometry = new THREE.ExtrudeGeometry(shapeOf(outline, holes), { depth: height, bevelEnabled: false })
    geometry.rotateX(-Math.PI / 2)
    geometry.translate(0, y, 0)
    add(geometry, material, id)
  }

  // A neutral support plane also carries peripheral streets and water outside the crop.
  if (options.includeContext !== false) {
    surface([[-65, 40], [638, 40], [638, 611], [-65, 611]], [], 1.6, -1.6, materials.anonymous)
    surface(campusCoverage, [], 0.05, 0, materials.base)
  }

  // Traced polylines, with no generated road grid, crossings or roadside furniture.
  if (options.includeContext !== false) mapRoads.forEach(road => {
    for (let i = 1; i < road.points.length; i++) {
      const [ax, az] = mapToModel(road.points[i - 1]!)
      const [bx, bz] = mapToModel(road.points[i]!)
      const w = road.width * 0.35
      const length = Math.hypot(bx - ax, bz - az)
      const geometry = new THREE.BoxGeometry(w, 0.09, length + w * 0.15)
      geometry.rotateY(Math.atan2(bx - ax, bz - az))
      geometry.translate((ax + bx) / 2, 0.1, (az + bz) / 2)
      add(geometry, materials.road)
      const junction = new THREE.CylinderGeometry(w / 2, w / 2, 0.09, 12)
      junction.translate(bx, 0.1, bz)
      add(junction, materials.road)
    }
  })

  function materialFor(kind: SurfaceKind, placeId?: string): THREE.MeshStandardMaterial {
    if (kind !== 'building') return materials[kind]
    if (!placeId) return materials.anonymous
    if (placeId === 'east-library') return materials.library
    if (placeId === 'east-gym') return materials.gym
    if (placeId === 'east-swimming') return materials.sports
    if (placeId === 'east-h8') return materials.annex
    if (placeId === 'east-jinxiu-dining' || placeId === 'east-canteen-wenhua') return materials.canteen
    return materials.building
  }

  mapFeatures.forEach(feature => {
    // Real-map overlays leave satellite roads, water and terrain visible.
    if (options.includeContext === false && feature.kind !== 'building' && feature.kind !== 'point') return
    const { base, height } = featureVerticalRange(feature)
    if (feature.kind === 'point') {
      const [x, z] = mapToModel(feature.outline[0]!)
      const marker = new THREE.CylinderGeometry(1.4, 1.4, 0.16, 24)
      marker.translate(x, base + height / 2, z)
      add(marker, materials.point, feature.placeId)
    } else {
      surface(feature.outline, feature.holes ?? [], height, base, materialFor(feature.kind, feature.placeId), feature.placeId)
    }
  })

  // Schematic silver arc grandstand roofs for the west stadium (GE 2022-05-26 shows bilateral arcs).
  // Display-only height; not surveyed. Top is flush with sportsGrandstandRoofTop for label sync.
  // Skip when overlay mode hides track/field context so roofs are not floating alone.
  if (options.includeContext !== false) {
    const track = mapFeatures.find(feature => feature.id === 'west-track')
    if (track) {
      const pts = track.outline.map(mapToModel)
      const xs = pts.map(p => p[0]), zs = pts.map(p => p[1])
      const minX = Math.min(...xs), maxX = Math.max(...xs)
      const minZ = Math.min(...zs), maxZ = Math.max(...zs)
      const cz = (minZ + maxZ) / 2
      const lenZ = maxZ - minZ
      const top = sportsGrandstandRoofTop
      for (const x of [minX - 1.6, maxX + 1.6]) {
        const path = new THREE.CatmullRomCurve3([
          new THREE.Vector3(x, top - 1.05, minZ + lenZ * 0.06),
          new THREE.Vector3(x, top - 0.22, cz),
          new THREE.Vector3(x, top - 1.05, maxZ - lenZ * 0.06),
        ])
        const vault = new THREE.TubeGeometry(path, 28, 1.55, 8, false)
        vault.computeBoundingBox()
        vault.translate(0, top - (vault.boundingBox?.max.y ?? top), 0)
        add(vault, materials.grandstand, 'east-sports')
        const stand = new THREE.BoxGeometry(2.1, Math.max(0.4, top - 1.15), lenZ * 0.72)
        stand.translate(x, Math.max(0.4, top - 1.15) / 2, cz)
        add(stand, materials.grandstand, 'east-sports')
      }
    }
  }


  const nonCastingMaterials = new Set([
    materials.water,
    materials.base,
    materials.road,
    materials.plaza,
    materials.track,
    materials.field,
    materials.court,
    materials.point,
  ])

  for (const bucket of buckets.values()) {
    const geometry = mergeGeometries(bucket.geometries)
    bucket.geometries.forEach(geometry => geometry.dispose())
    if (!geometry) throw new Error('Campus geometry could not be merged')
    const mesh = new THREE.Mesh(geometry, bucket.material)
    mesh.castShadow = !nonCastingMaterials.has(bucket.material as THREE.MeshStandardMaterial)
    mesh.receiveShadow = true
    if (bucket.id) {
      mesh.userData.placeId = bucket.id
      pickables.push(mesh)
    }
    scene.add(mesh)
  }

  const selectionMaterial = new THREE.MeshBasicMaterial({ color: '#447fa7', transparent: true, opacity: 0.25, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -4 })
  const selection = new THREE.Group()
  selection.visible = false
  scene.add(selection)

  const trees = options.includeContext !== false ? buildCampusTrees(scene) : null

  return {
    pickables,
    dispose() {
      selectionMaterial.dispose()
      trees?.geometry.dispose()
      trees?.material.dispose()
      Object.values(facadeTextures).forEach(t => { t.map.dispose(); t.roughnessMap.dispose() })
      waterTexture.dispose()
    },
    tick(time: number) {
      waterTexture.offset.x = (time * 0.00003) % 1
      waterTexture.offset.y = (time * 0.00002) % 1
    },
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
        overlay.position.y = 0.04
        selection.add(overlay)
      })
    },
    setEvening(evening: boolean) {
      materials.point.emissive.set(evening ? '#538bb8' : '#000000')
      materials.point.emissiveIntensity = evening ? 0.5 : 0
      materials.water.metalness = evening ? 0.35 : 0.22
    },
  }
}
