import { loadAMap } from './amapSdk'
import type { MapClick } from './amapSdk'
import { campusPlaces } from './manifest'
import { campusLngLat, campusMapFrame } from './georeference'
import type { SceneCallbacks } from './createCampusScene'
import { addCampusOverlay } from './amapCampusOverlay'

export interface MapSceneOptions {
  key: string
  onCalibration?: (coordinate: string) => void
  onModelStatus?: (ready: boolean) => void
  signal?: AbortSignal
}

export async function createAMapCampusScene(host: HTMLElement, callbacks: SceneCallbacks, options: MapSceneOptions) {
  const AMap = await loadAMap(options.key)
  if (options.signal?.aborted) throw new DOMException('Map load cancelled', 'AbortError')
  const frame = () => campusMapFrame(host.clientWidth, host.clientHeight)
  const initial = frame()
  const map = new AMap.Map(host, {
    viewMode: '3D', center: initial.center, zoom: initial.zoom, pitch: 0, rotation: 0,
    zooms: [14, 20], features: ['bg', 'road'], showLabel: false,
    resizeEnable: true, animateEnable: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    showBuildingBlock: false, dragEnable: true, rotateEnable: true, pitchEnable: true,
  })
  const satellite = new AMap.TileLayer.Satellite()
  map.add(satellite)
  const overlay = addCampusOverlay(AMap, map, host, ready => options.onModelStatus?.(ready))
  let disposed = false, current: string | null = null, satelliteOn = true, night = false, tilted = false
  let overviewActive = true, viewportWidth = host.clientWidth, viewportHeight = host.clientHeight
  let labelFrame = 0
  let settleTimer: ReturnType<typeof setTimeout> | undefined
  const important = new Set(['east-library', 'east-shuhua', 'east-jingyi', 'east-jinxiu-dining', 'east-wenhua', 'east-sports', 'south-gate', 'east-admin', 'east-gym'])
  function labels() {
    labelFrame = 0
    if (disposed) return
    const placed: { x: number; y: number }[] = []
    const points = [...campusPlaces].sort((a, b) => Number(b.id === current) - Number(a.id === current)).map(place => {
      const lnglat = campusLngLat([place.x, place.z])
      const pixel = lnglat ? map.lngLatToContainer(lnglat) : { x: -100, y: -100 }
      const allowed = map.getZoom() >= 17.8 || important.has(place.id) || current === place.id
      const visible = allowed && pixel.x > 45 && pixel.x < host.clientWidth - 45 && pixel.y > 50 && pixel.y < host.clientHeight - 45 && !placed.some(p => Math.abs(p.x - pixel.x) < 100 && Math.abs(p.y - pixel.y) < 30)
      if (visible) placed.push(pixel)
      return { id: place.id, left: pixel.x, top: pixel.y, visible }
    })
    callbacks.labels(points)
  }
  function scheduleLabels() { if (!labelFrame) labelFrame = requestAnimationFrame(labels) }
  const click = (event: MapClick) => {
    options.onCalibration?.(`${event.lnglat.getLng().toFixed(7)}, ${event.lnglat.getLat().toFixed(7)}`)
    if (!options.onCalibration) { const id = overlay.pick(event.pixel.x, event.pixel.y); if (id) callbacks.select(id) }
  }
  const stopped = () => { clearTimeout(settleTimer); callbacks.moving(false); scheduleLabels() }
  const moving = () => { callbacks.moving(true); scheduleLabels(); clearTimeout(settleTimer); settleTimer = setTimeout(stopped, 180) }
  map.on('click', click)
  const leaveOverview = () => { overviewActive = false }
  map.on('dragstart', leaveOverview)
  host.addEventListener('wheel', leaveOverview, { passive: true })
  host.addEventListener('touchstart', leaveOverview, { passive: true })
  for (const name of ['mapmove', 'zoomchange', 'rotatechange', 'pitchchange']) map.on(name, moving)
  for (const name of ['moveend', 'zoomend', 'rotateend', 'pitchend', 'complete']) map.on(name, stopped)
  const resize = new ResizeObserver(() => {
    if (host.clientWidth !== viewportWidth || host.clientHeight !== viewportHeight) {
      viewportWidth = host.clientWidth; viewportHeight = host.clientHeight
      if (overviewActive) { const next = frame(); map.setZoomAndCenter(next.zoom, next.center, true) }
    }
    scheduleLabels()
  })
  resize.observe(host)
  callbacks.view('top'); scheduleLabels()
  let walking: import('./amapSdk').AMapWalkingInstance | undefined
  return {
    dispose() {
      disposed = true; resize.disconnect(); cancelAnimationFrame(labelFrame); clearTimeout(settleTimer)
      map.off('click', click); map.off('dragstart', leaveOverview)
      host.removeEventListener('wheel', leaveOverview); host.removeEventListener('touchstart', leaveOverview)
      walking?.clear(); overlay.dispose(); map.destroy()
    },
    select(id: string | null) {
      current = id
      overviewActive = !id
      overlay.select(id)
      const place = campusPlaces.find(p => p.id === id)
      const overview = frame()
      const target = place ? campusLngLat([place.x, place.z]) : overview.center
      if (target) map.setZoomAndCenter(place ? 18.3 : overview.zoom, target, false, 650)
      callbacks.view(place ? 'detail' : tilted ? 'overview' : 'top'); scheduleLabels()
    },
    overview() { this.select(null) },
    top() { tilted = false; map.setPitch(0, false, 500); map.setRotation(0); callbacks.view('top') },
    zoom(direction: 'in' | 'out') { overviewActive = false; map.setZoom(map.getZoom() + (direction === 'in' ? 0.5 : -0.5)); callbacks.view('free') },
    setEvening(evening: boolean) {
      if (evening !== night) {
        map.setMapStyle(evening ? 'amap://styles/dark' : satelliteOn ? 'amap://styles/normal' : 'amap://styles/whitesmoke')
        night = evening
      }
      overlay.setEvening(evening)
    },
    setSatellite(enabled: boolean) {
      if (enabled === satelliteOn) return
      satelliteOn = enabled
      if (enabled) {
        map.add(satellite)
      } else {
        map.remove(satellite)
        map.setMapStyle(night ? 'amap://styles/dark' : 'amap://styles/whitesmoke')
      }
    },
    setBuildings(enabled: boolean) { tilted = enabled; overlay.visible(enabled); map.setPitch(enabled ? 45 : 0, false, 600); callbacks.view(enabled ? 'overview' : 'top') },
    planRoute(toId: string, fromId = 'south-gate') {
      const fromPlace = campusPlaces.find(p => p.id === fromId)
      const toPlace = campusPlaces.find(p => p.id === toId)
      if (!fromPlace || !toPlace) return
      const start = campusLngLat([fromPlace.x, fromPlace.z])
      const end = campusLngLat([toPlace.x, toPlace.z])
      if (!start || !end) return
      if (!walking && AMap.Walking) {
        walking = new AMap.Walking({ map, hideMarkers: false })
      }
      walking?.search(start, end, () => {})
    },
    clearRoute() {
      walking?.clear()
    },
    getWeather(city = '新郑市'): Promise<{ weather: string; temperature: number; wind: string } | null> {
      return new Promise(resolve => {
        if (!AMap.Weather) { resolve(null); return }
        const weather = new AMap.Weather()
        weather.getLive(city, (err, data) => {
          if (!err && data?.info === 'OK') {
            resolve({ weather: data.weather, temperature: data.temperature, wind: `${data.windDirection}风${data.windPower}级` })
          } else {
            resolve(null)
          }
        })
      })
    },
    readCoordinate(x: number, y: number) {
      if (x < 0 || y < 0 || x > host.clientWidth || y > host.clientHeight || !Number.isFinite(x + y)) return
      const point = map.containerToLngLat([x, y])
      options.onCalibration?.(`${point.getLng().toFixed(7)}, ${point.getLat().toFixed(7)}`)
    },
  }
}
