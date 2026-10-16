import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { buildCampus } from './buildCampus'
import { campusPlaces } from './manifest'
import type { CampusPlace } from './manifest'
import { campusOverviewPosition, overviewTarget } from './framing'

export type CampusView = 'overview' | 'top' | 'detail' | 'free'
export interface PlaceLabel { id: string; left: number; top: number; visible: boolean }
interface SceneCallbacks {
  select: (id: string) => void
  labels: (labels: PlaceLabel[]) => void
  view: (view: CampusView) => void
  moving: (moving: boolean) => void
  unavailable: (message: string) => void
}

export function createCampusScene(host: HTMLElement, callbacks: SceneCallbacks) {
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(38, 1, 0.5, 1200)
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  let disposed = false, contextLost = false, frame = 0
  const resize = new ResizeObserver(size)
  let width = 1, height = 1, view: CampusView = 'overview', current: CampusPlace | undefined
  let transition: { start: number; from: THREE.Vector3; to: THREE.Vector3; targetFrom: THREE.Vector3; targetTo: THREE.Vector3 } | undefined
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
  const raycaster = new THREE.Raycaster(), pointer = new THREE.Vector2(), projected = new THREE.Vector3()
  const labelRay = new THREE.Ray(), labelAnchor = new THREE.Vector3(), obstruction = new THREE.Vector3()
  const labelOccluders = campusPlaces.filter(place => place.kind === 'building').map(place => ({ id: place.id,
    bounds: new THREE.Box3(new THREE.Vector3(place.x - place.width / 2, 0, place.z - place.depth / 2),
      new THREE.Vector3(place.x + place.width / 2, place.height + 3, place.z + place.depth / 2)),
  }))
  const activePointers = new Set<number>()
  let down = { x: 0, y: 0 }, gesture = false
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFShadowMap
  renderer.shadowMap.autoUpdate = false
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.25
  renderer.domElement.setAttribute('aria-label', '三维校园场景。拖动旋转，滚轮缩放；也可使用场景按钮和地点目录探索。')
  host.appendChild(renderer.domElement)
  const controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true; controls.dampingFactor = 0.09
  controls.minDistance = 30; controls.maxDistance = 760
  controls.minPolarAngle = 0.035; controls.maxPolarAngle = Math.PI / 2.35
  controls.maxTargetRadius = 118
  controls.cursor.set(0, 0, 0)
  controls.screenSpacePanning = false
  controls.zoomToCursor = true
  const ambient = new THREE.HemisphereLight('#f3f7ff', '#7b8974', 2.3)
  const sun = new THREE.DirectionalLight('#fff2dc', 3.1)
  sun.position.set(-65, 110, 65)
  sun.castShadow = true; sun.shadow.mapSize.set(2048, 2048)
  Object.assign(sun.shadow.camera, { left: -135, right: 135, top: 115, bottom: -115, near: 1, far: 260 })
  sun.shadow.normalBias = 0.16; sun.shadow.bias = -0.00015
  scene.add(ambient, sun)
  scene.background = new THREE.Color('#e8eef0')
  scene.fog = new THREE.Fog('#e8eef0', 850, 1200)
  let model: ReturnType<typeof buildCampus>
  try { model = buildCampus(scene) } catch (error) { dispose(); throw error }
  renderer.shadowMap.needsUpdate = true

  function invalidate() {
    if (!frame && !disposed && !contextLost && !document.hidden) frame = requestAnimationFrame(render)
  }
  function setView(next: CampusView) { view = next; callbacks.view(next) }
  function stopTransition() {
    transition = undefined; callbacks.moving(false)
  }
  function render(time: number) {
    frame = 0
    if (disposed || contextLost || document.hidden) return
    if (transition) {
      const progress = Math.min(1, (time - transition.start) / 950)
      const eased = progress < 0.5 ? 4 * progress ** 3 : 1 - (-2 * progress + 2) ** 3 / 2
      camera.position.lerpVectors(transition.from, transition.to, eased)
      controls.target.lerpVectors(transition.targetFrom, transition.targetTo, eased)
      if (progress === 1) stopTransition()
    }
    controls.update()
    renderer.render(scene, camera)
    const nextLabels = campusPlaces.map(place => {
      const anchorHeight = place.height + 4
      labelAnchor.set(place.x, anchorHeight, place.z)
      projected.copy(labelAnchor).project(camera)
      const important = ['east-library', 'south-gate', 'east-gate', 'statue-huangdi', 'east-sports', 'east-square', 'east-art', 'east-wenhua', 'east-shuhua', 'east-gym', 'east-water', 'east-dorms', 'east-affairs', 'east-admin', 'east-jidian'].includes(place.id)
      const mobile = ['east-library', 'south-gate', 'east-gate', 'statue-huangdi', 'east-sports', 'east-square', 'east-gym', 'east-water'].includes(place.id)
      const distance = camera.position.distanceTo(new THREE.Vector3(place.x, place.height, place.z))
      const anchorDistance = camera.position.distanceTo(labelAnchor)
      labelRay.set(camera.position, labelAnchor.clone().sub(camera.position).normalize())
      const occluded = labelOccluders.some(other => other.id !== place.id &&
        labelRay.intersectBox(other.bounds, obstruction) && obstruction.distanceTo(camera.position) < anchorDistance - 1)
      return { id: place.id, left: (projected.x + 1) * width / 2, top: (1 - projected.y) * height / 2,
        visible: projected.z > -1 && projected.z < 1 && Math.abs(projected.x) < 0.88 && Math.abs(projected.y) < 0.86 &&
          (!occluded || current?.id === place.id) && (current?.id === place.id || (distance > 150 ? important && (width > 600 || mobile) : distance < 110)) }
    })
    const occupied: PlaceLabel[] = []
    // The selected place gets priority; suppress overlapping labels and those hidden behind buildings.
    const orderedLabels = [...nextLabels].sort((a, b) => Number(b.id === current?.id) - Number(a.id === current?.id))
    orderedLabels.forEach(label => {
      if (!label.visible) return
      if (occupied.some(other => Math.abs(other.left - label.left) < 86 && Math.abs(other.top - label.top) < 26)) label.visible = false
      else occupied.push(label)
    })
    callbacks.labels(nextLabels)
    if (transition) invalidate()
  }
  function overviewPosition() {
    return campusOverviewPosition(width / height)
  }
  function move(position: THREE.Vector3, target: THREE.Vector3, nextView: CampusView, instant = false) {
    stopTransition()
    // Flush OrbitControls' residual damping before starting an intentional camera move.
    const damping = controls.enableDamping; controls.enableDamping = false; controls.update(); controls.enableDamping = damping
    setView(nextView)
    if (instant || reduced.matches) { camera.position.copy(position); controls.target.copy(target); controls.update(); invalidate(); return }
    transition = { start: performance.now(), from: camera.position.clone(), to: position, targetFrom: controls.target.clone(), targetTo: target }
    callbacks.moving(true); invalidate()
  }
  function pick(event: PointerEvent) {
    const rect = renderer.domElement.getBoundingClientRect()
    pointer.set((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1)
    raycaster.setFromCamera(pointer, camera)
    return raycaster.intersectObjects(model.pickables, false)[0]?.object.userData.placeId as string | undefined
  }
  function pointerDown(event: PointerEvent) {
    activePointers.add(event.pointerId)
    if (activePointers.size > 1) gesture = true
    else down = { x: event.clientX, y: event.clientY }
  }
  function pointerUp(event: PointerEvent) {
    const wasGesture = gesture
    activePointers.delete(event.pointerId)
    if (!activePointers.size) gesture = false
    if (wasGesture || event.button !== 0 || Math.hypot(event.clientX - down.x, event.clientY - down.y) > 5) return
    const id = pick(event)
    if (id) callbacks.select(id)
  }
  function pointerCancel(event: PointerEvent) { activePointers.delete(event.pointerId); if (!activePointers.size) gesture = false }
  function pointerMove(event: PointerEvent) {
    if (activePointers.size || event.pointerType === 'touch') return
    renderer.domElement.style.cursor = pick(event) ? 'pointer' : 'grab'
  }
  function interaction() { stopTransition(); setView('free'); invalidate() }
  function visibility() { if (document.hidden) { cancelAnimationFrame(frame); frame = 0 } else invalidate() }
  function onContextLost(event: Event) {
    event.preventDefault(); contextLost = true; cancelAnimationFrame(frame); frame = 0; stopTransition()
    callbacks.unavailable('三维画面暂时中断，请重新加载场景。地点目录仍可使用。')
  }
  function size() {
    if (disposed) return
    width = Math.max(1, host.clientWidth); height = Math.max(1, host.clientHeight)
    renderer.setSize(width, height)
    camera.aspect = width / height; camera.updateProjectionMatrix()
    if (view === 'overview') move(overviewPosition(), overviewTarget, 'overview', true)
    else if (view === 'top') top(true)
    invalidate()
  }
  function top(instant = false) {
    const distance = Math.max(345, 385 / camera.aspect)
    move(new THREE.Vector3(0, distance, 0.1), new THREE.Vector3(0, 0, 0), 'top', instant)
  }
  function onReducedMotion() {
    if (reduced.matches && transition) move(transition.to, transition.targetTo, view, true)
  }
  controls.addEventListener('change', invalidate); controls.addEventListener('start', interaction)
  renderer.domElement.addEventListener('pointerdown', pointerDown)
  renderer.domElement.addEventListener('pointerup', pointerUp)
  renderer.domElement.addEventListener('pointercancel', pointerCancel)
  renderer.domElement.addEventListener('pointermove', pointerMove)
  renderer.domElement.addEventListener('webglcontextlost', onContextLost)
  document.addEventListener('visibilitychange', visibility)
  reduced.addEventListener('change', onReducedMotion)
  resize.observe(host); size()

  function dispose() {
    disposed = true; cancelAnimationFrame(frame)
    // Initialization can fail before the observer has been created.
    resize?.disconnect()
    document.removeEventListener('visibilitychange', visibility)
    reduced.removeEventListener('change', onReducedMotion)
    controls.removeEventListener('change', invalidate); controls.removeEventListener('start', interaction); controls.dispose()
    renderer.domElement.removeEventListener('pointerdown', pointerDown)
    renderer.domElement.removeEventListener('pointerup', pointerUp)
    renderer.domElement.removeEventListener('pointercancel', pointerCancel)
    renderer.domElement.removeEventListener('pointermove', pointerMove)
    renderer.domElement.removeEventListener('webglcontextlost', onContextLost)
    const geometries = new Set<THREE.BufferGeometry>(), materials = new Set<THREE.Material>()
    scene.traverse(object => {
      if (object instanceof THREE.Mesh) {
        if (object instanceof THREE.InstancedMesh) object.dispose()
        geometries.add(object.geometry)
        for (const material of Array.isArray(object.material) ? object.material : [object.material]) materials.add(material)
      }
    })
    geometries.forEach(geometry => geometry.dispose()); materials.forEach(material => material.dispose())
    model?.dispose(); sun.shadow.dispose(); renderer.dispose(); renderer.forceContextLoss(); renderer.domElement.remove()
  }
  return {
    dispose,
    select(id: string | null) {
      current = campusPlaces.find(place => place.id === id); model.select(current)
      if (!current) { move(overviewPosition(), overviewTarget, 'overview'); return }
      const target = new THREE.Vector3(current.x, current.height * 0.25, current.z)
      const extent = Math.max(current.width, current.depth)
      const distance = Math.max(42, extent * 1.4) * (width / height < 1 ? 1.25 : 1)
      move(target.clone().add(new THREE.Vector3(distance * 0.85, distance * 0.83, distance)), target, 'detail')
    },
    overview() { move(overviewPosition(), overviewTarget, 'overview') },
    top,
    zoom(direction: 'in' | 'out') {
      stopTransition(); setView('free')
      const offset = camera.position.clone().sub(controls.target)
      offset.setLength(THREE.MathUtils.clamp(offset.length() * (direction === 'in' ? 0.8 : 1.25), controls.minDistance, controls.maxDistance))
      move(controls.target.clone().add(offset), controls.target.clone(), 'free')
    },
    setEvening(evening: boolean) {
      const background = evening ? '#263b55' : '#e8eef0'
      scene.background = new THREE.Color(background); scene.fog = new THREE.Fog(background, 850, 1200)
      ambient.color.set(evening ? '#b7c9ef' : '#f3f7ff'); ambient.intensity = evening ? 1.25 : 2.3
      sun.color.set(evening ? '#ffc999' : '#fff2dc'); sun.intensity = evening ? 1.6 : 3.1
      model.setEvening(evening); invalidate()
    },
  }
}
