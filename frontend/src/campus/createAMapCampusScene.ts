import { loadAMap } from './amapSdk'
import { publicReducedMotion } from '../lib/publicMotion'
import type { LngLat, MapClick } from './amapSdk'
import { campusPlaces } from './manifest'
import type { CampusPlace } from './manifest'
import { campusLngLat, campusMapFrame } from './georeference'
import { westCampusMapFrame, westMapPlaces as westCampusPlaces } from './westCampusMapData'
import type { SceneCallbacks } from './createCampusScene'
import { addCampusOverlay } from './amapCampusOverlay'

export interface MapSceneOptions {
  key: string
  initialCampus?: 'EAST' | 'WEST'
  onCalibration?: (coordinate: string) => void
  onModelStatus?: (ready: boolean) => void
  signal?: AbortSignal
}

export async function createAMapCampusScene(host: HTMLElement, callbacks: SceneCallbacks, options: MapSceneOptions) {
  const AMap = await loadAMap(options.key)
  if (options.signal?.aborted) throw new DOMException('Map load cancelled', 'AbortError')

  let currentCampus: 'EAST' | 'WEST' = options.initialCampus ?? 'EAST'
  const allPlaces: CampusPlace[] = [...campusPlaces, ...westCampusPlaces]

  function getPlaceLngLat(place: CampusPlace): LngLat | undefined {
    if (place.lnglat) return place.lnglat
    return place.campusId === 'WEST' ? undefined : campusLngLat([place.x, place.z])
  }

  const frame = () => currentCampus === 'WEST'
    ? westCampusMapFrame(host.clientWidth, host.clientHeight)
    : campusMapFrame(host.clientWidth, host.clientHeight)

  const defaultPitch = 38
  const initial = frame()

  // Initialize with Gaode 2.5D vector view with 3D building blocks & roads
  const map = new AMap.Map(host, {
    viewMode: '3D',
    center: initial.center,
    zoom: initial.zoom,
    pitch: defaultPitch,
    rotation: 0,
    zooms: [14, 20],
    features: ['bg', 'road', 'building', 'point'],
    showBuildingBlock: !options.onCalibration,
    showLabel: true,
    resizeEnable: true,
    animateEnable: !publicReducedMotion.value && !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    dragEnable: true,
    rotateEnable: true,
    pitchEnable: true,
    mapStyle: 'amap://styles/normal',
  })

  const satellite = new AMap.TileLayer.Satellite()
  // Default to normal 2.5D vector map without satellite photos
  let satelliteOn = false
  let night = false
  let is25D = true
  let activeTheme: 'normal' | 'whitesmoke' = 'normal'
  let overlayModelActive = false

  let overlay = addCampusOverlay(AMap, map, host, ready => options.onModelStatus?.(ready), currentCampus)
  let disposed = false, current: string | null = null
  let overviewActive = true, viewportWidth = host.clientWidth, viewportHeight = host.clientHeight
  let labelFrame = 0
  let settleTimer: ReturnType<typeof setTimeout> | undefined

  const eastImportant = new Set(['east-library', 'east-shuhua', 'east-jingyi', 'east-jinxiu-dining', 'east-wenhua', 'east-sports', 'south-gate', 'east-admin', 'east-gym'])
  const westImportant = new Set(['west-incubator', 'west-teaching-1', 'west-teaching-2', 'west-lab-3', 'west-mingde', 'west-canteen', 'west-tech-center', 'west-dorm-4', 'west-dorm-7', 'west-dorm-5'])
  const isImportant = (id: string) => currentCampus === 'WEST' ? westImportant.has(id) : eastImportant.has(id)

  function labels() {
    labelFrame = 0
    if (disposed) return
    const placed: { x: number; y: number }[] = []
    const activePlaces = currentCampus === 'WEST' ? westCampusPlaces : campusPlaces
    const points = [...activePlaces].sort((a, b) => Number(b.id === current) - Number(a.id === current)).map(place => {
      const lnglat = getPlaceLngLat(place)
      const pixel = lnglat ? map.lngLatToContainer(lnglat) : { x: -100, y: -100 }
      const allowed = map.getZoom() >= 17.5 || isImportant(place.id) || current === place.id
      const visible = allowed && pixel.x > 45 && pixel.x < host.clientWidth - 45 && pixel.y > 50 && pixel.y < host.clientHeight - 45 && !placed.some(p => Math.abs(p.x - pixel.x) < 100 && Math.abs(p.y - pixel.y) < 30)
      if (visible) placed.push(pixel)
      return { id: place.id, left: pixel.x, top: pixel.y, visible }
    })
    callbacks.labels(points)
  }
  function scheduleLabels() { if (!labelFrame) labelFrame = requestAnimationFrame(labels) }
  const click = (event: MapClick) => {
    options.onCalibration?.(`${event.lnglat.getLng().toFixed(7)}, ${event.lnglat.getLat().toFixed(7)}`)
    if (!options.onCalibration) {
      if (overlayModelActive) {
        const id = overlay.pick(event.pixel.x, event.pixel.y)
        if (id) callbacks.select(id)
      }
    }
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
  callbacks.view('overview'); scheduleLabels()
  let walking: import('./amapSdk').AMapWalkingInstance | undefined

  return {
    dispose() {
      disposed = true; resize.disconnect(); cancelAnimationFrame(labelFrame); clearTimeout(settleTimer)
      map.off('click', click); map.off('dragstart', leaveOverview)
      host.removeEventListener('wheel', leaveOverview); host.removeEventListener('touchstart', leaveOverview)
      walking?.clear(); overlay.dispose(); map.destroy()
    },
    switchCampus(campusId: 'EAST' | 'WEST') {
      if (campusId === currentCampus) return
      currentCampus = campusId
      current = null
      overviewActive = true
      walking?.clear()
      overlay.dispose()
      options.onModelStatus?.(false)
      overlay = addCampusOverlay(AMap, map, host, ready => options.onModelStatus?.(ready), currentCampus)
      overlay.setEvening(night)
      overlay.visible(overlayModelActive)
      const next = frame()
      map.setZoomAndCenter(next.zoom, next.center, publicReducedMotion.value, 950)
      map.setPitch(is25D ? defaultPitch : 0, false, 600)
      callbacks.view(is25D ? 'overview' : 'top')
      scheduleLabels()
    },
    getCampus(): 'EAST' | 'WEST' {
      return currentCampus
    },
    select(id: string | null) {
      const place = allPlaces.find(p => p.id === id)
      if (place?.campusId && place.campusId !== currentCampus) {
        this.switchCampus(place.campusId)
      }
      current = id
      overviewActive = !id
      overlay.select(id)
      const overview = frame()
      const target = place ? getPlaceLngLat(place) : overview.center
      if (target) {
        map.setZoomAndCenter(place ? 18.2 : overview.zoom, target, publicReducedMotion.value, 650)
        if (is25D) {
          map.setPitch(place ? 42 : defaultPitch, false, 500)
        }
      }
      callbacks.view(place ? 'detail' : is25D ? 'overview' : 'top'); scheduleLabels()
    },
    overview() {
      this.select(null)
      if (is25D) map.setPitch(defaultPitch, false, 500)
      map.setRotation(0, false)
    },
    top() {
      is25D = false
      map.setPitch(0, false, 500)
      map.setRotation(0, false)
      callbacks.view('top')
      scheduleLabels()
    },
    set25D(enabled: boolean) {
      is25D = enabled
      map.setPitch(enabled ? defaultPitch : 0, false, 500)
      callbacks.view(enabled ? 'overview' : 'top')
      scheduleLabels()
    },
    setTheme(theme: 'normal' | 'whitesmoke') {
      activeTheme = theme
      if (!satelliteOn && !night) {
        map.setMapStyle(`amap://styles/${theme}`)
      }
    },
    zoom(direction: 'in' | 'out') { overviewActive = false; map.setZoom(map.getZoom() + (direction === 'in' ? 0.5 : -0.5), publicReducedMotion.value); callbacks.view('free') },
    setEvening(evening: boolean) {
      if (evening !== night) {
        night = evening
        if (evening) {
          map.setMapStyle('amap://styles/dark')
        } else if (satelliteOn) {
          map.setMapStyle('amap://styles/normal')
        } else {
          map.setMapStyle(`amap://styles/${activeTheme}`)
        }
      }
      overlay.setEvening(evening)
    },
    setSatellite(enabled: boolean) {
      if (enabled === satelliteOn) return
      satelliteOn = enabled
      map.setFeatures(enabled ? ['bg', 'road', 'point'] : ['bg', 'road', 'building', 'point'])
      if (enabled) {
        map.add(satellite)
      } else {
        map.remove(satellite)
        map.setMapStyle(night ? 'amap://styles/dark' : `amap://styles/${activeTheme}`)
      }
    },
    setBuildings(enabled: boolean) {
      overlayModelActive = enabled
      overlay.visible(enabled)
      if (enabled) {
        map.setPitch(45, false, 600)
        callbacks.view('overview')
      }
    },
    planRoute(toId: string, fromId?: string) {
      const defaultStart = currentCampus === 'WEST' ? 'west-parking' : 'south-gate'
      const startId = fromId || defaultStart
      const fromPlace = allPlaces.find(p => p.id === startId)
      const toPlace = allPlaces.find(p => p.id === toId)
      if (!fromPlace || !toPlace) return
      const start = getPlaceLngLat(fromPlace)
      const end = getPlaceLngLat(toPlace)
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
