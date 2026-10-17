import * as THREE from 'three'
import { buildCampus } from './buildCampus'
import type { AMapInstance, AMapSdk } from './amapSdk'
import { campusGeoAnchors, campusMapCenter, fitAffine } from './georeference'
import { campusPlaces } from './manifest'

export function addCampusOverlay(AMap: AMapSdk, map: AMapInstance, host: HTMLElement, status: (ready: boolean) => void) {
  const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera()
  const root = new THREE.Group(); root.visible = false; scene.add(root)
  const ambient = new THREE.AmbientLight('#ffffff', 2)
  const sun = new THREE.DirectionalLight('#fff1d9', 2.5); sun.position.set(-200, -100, 600)
  scene.add(ambient, sun)
  let renderer: THREE.WebGLRenderer | undefined, model: ReturnType<typeof buildCampus> | undefined
  let disposed = false, failed = false
  let renderWidth = 0, renderHeight = 0
  const raycaster = new THREE.Raycaster()
  function fail() {
    if (!failed && !disposed) { failed = true; root.visible = false; if (renderer) renderer.domElement.style.display = 'none'; status(false) }
  }
  function contextLost() { fail() }
  const layer = new AMap.GLCustomLayer({
    zIndex: 120,
    init() {
      if (disposed || renderer || campusGeoAnchors.length !== 3) return
      try {
        const coords = map.customCoords
        coords.setCenter(campusMapCenter)
        const fit = fitAffine(campusGeoAnchors.map(a => a.model), coords.lngLatsToCoords(campusGeoAnchors.map(a => a.lnglat)))
        const [[a, b, c], [d, e, f]] = fit.rows
        root.matrixAutoUpdate = false
        // Campus models are Y-up; AMap's shared world is Z-up.
        root.matrix.set(a, 0, b, c, d, 0, e, f, 0, fit.scale, 0, 0, 0, 0, 0, 1)
        model = buildCampus(root, { includeContext: false })
        root.updateMatrixWorld(true)
        // AMap may supply WebGL 1; current Three.js requires WebGL 2. Keep an
        // independent transparent canvas and synchronize the official map camera.
        renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, logarithmicDepthBuffer: true })
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
        renderer.setClearColor(0x000000, 0)
        renderer.domElement.className = 'campus-map-model-canvas'
        Object.assign(renderer.domElement.style, { position: 'absolute', inset: '0', width: '100%', height: '100%', zIndex: '2', pointerEvents: 'none', display: 'none' })
        renderer.domElement.setAttribute('aria-hidden', 'true')
        renderer.domElement.addEventListener('webglcontextlost', contextLost)
        host.appendChild(renderer.domElement)
        renderer.toneMapping = THREE.ACESFilmicToneMapping
        renderer.toneMappingExposure = 1.1
        status(true)
      } catch { fail() }
    },
    render() {
      if (!renderer || failed || disposed || !root.visible) return
      try {
        map.customCoords.setCenter(campusMapCenter)
        const { near, far, fov, up, position, lookAt } = map.customCoords.getCameraParams()
        camera.near = Math.min(near, 0.8); camera.far = Math.max(far, 3500); camera.fov = fov
        camera.aspect = Math.max(1, host.clientWidth) / Math.max(1, host.clientHeight)
        camera.position.set(...position); camera.up.set(...up); camera.lookAt(...lookAt)
        camera.updateProjectionMatrix(); camera.updateMatrixWorld()
        const width = Math.max(1, host.clientWidth), height = Math.max(1, host.clientHeight)
        if (width !== renderWidth || height !== renderHeight) {
          renderWidth = width; renderHeight = height; renderer.setSize(width, height, false)
        }
        renderer.render(scene, camera)
      } catch { fail() }
    },
  })
  map.add(layer)
  return {
    select(id: string | null) { model?.select(campusPlaces.find(p => p.id === id)); if (renderer && !failed) map.render() },
    visible(value: boolean) {
      root.visible = value && !failed
      if (renderer) { renderer.domElement.style.display = root.visible ? 'block' : 'none'; if (!failed) map.render() }
    },
    pick(x: number, y: number) {
      if (!model || !root.visible || failed) return undefined
      raycaster.setFromCamera(new THREE.Vector2(x / host.clientWidth * 2 - 1, 1 - y / host.clientHeight * 2), camera)
      return raycaster.intersectObjects(model.pickables, false)[0]?.object.userData.placeId as string | undefined
    },
    setEvening(value: boolean) { ambient.intensity = value ? 1.3 : 2; sun.intensity = value ? 1.6 : 2.5; model?.setEvening(value); if (renderer && !failed) map.render() },
    dispose() {
      disposed = true; map.remove(layer)
      const geometries = new Set<THREE.BufferGeometry>(), materials = new Set<THREE.Material>()
      root.traverse(object => {
        if (object instanceof THREE.Mesh) {
          geometries.add(object.geometry)
          for (const material of Array.isArray(object.material) ? object.material : [object.material]) materials.add(material)
        }
      })
      geometries.forEach(g => g.dispose()); materials.forEach(m => m.dispose()); model?.dispose()
      // Only this independent context is ours; AMap disposes its own canvas.
      renderer?.domElement.removeEventListener('webglcontextlost', contextLost)
      renderer?.dispose(); renderer?.forceContextLoss(); renderer?.domElement.remove()
    },
  }
}
