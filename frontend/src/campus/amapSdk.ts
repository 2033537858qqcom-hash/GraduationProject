export type LngLat = readonly [number, number]
type Vector3 = [number, number, number]
export interface MapClick { lnglat: { getLng(): number; getLat(): number }; pixel: { x: number; y: number } }
export interface AMapInstance {
  on(event: string, handler: (event: MapClick) => void): void
  off(event: string, handler: (event: MapClick) => void): void
  add(layer: unknown): void
  remove(layer: unknown): void
  destroy(): void
  render(): void
  getZoom(): number
  setZoom(zoom: number, immediately?: boolean): void
  setZoomAndCenter(zoom: number, center: LngLat, immediately?: boolean, duration?: number): void
  setPitch(pitch: number, immediately?: boolean, duration?: number): void
  setRotation(rotation: number, immediately?: boolean): void
  setMapStyle(style: string): void
  lngLatToContainer(lnglat: LngLat): { x: number; y: number }
  containerToLngLat(pixel: readonly [number, number]): { getLng(): number; getLat(): number }
  customCoords: {
    setCenter(center: LngLat): void
    lngLatsToCoords(points: LngLat[]): [number, number][]
    getCameraParams(): { near: number; far: number; fov: number; up: Vector3; position: Vector3; lookAt: Vector3 }
  }
}
export interface AMapWalkingInstance {
  search(origin: LngLat, destination: LngLat, callback: (status: string, result: unknown) => void): void
  clear(): void
}
export interface AMapWeatherLiveResult {
  info: string
  weather: string
  temperature: number
  windDirection: string
  windPower: string
}
export interface AMapWeatherInstance {
  getLive(city: string, callback: (err: unknown, data: AMapWeatherLiveResult) => void): void
}
export interface AMapSdk {
  Map: new (host: HTMLElement, options: Record<string, unknown>) => AMapInstance
  TileLayer: { Satellite: new () => unknown; RoadNet: new () => unknown }
  GLCustomLayer: new (options: { zIndex: number; init: (gl: WebGLRenderingContext | WebGL2RenderingContext) => void; render: () => void }) => unknown
  Walking?: new (options: { map: unknown; hideMarkers?: boolean }) => AMapWalkingInstance
  Weather?: new () => AMapWeatherInstance
  plugin?: (plugins: string[], callback: () => void) => void
}
declare global {
  interface Window { AMap?: AMapSdk; _AMapSecurityConfig?: { serviceHost: string } }
}

let pending: Promise<AMapSdk> | undefined
export function loadAMap(key: string): Promise<AMapSdk> {
  if (!/^[a-f\d]{32}$/i.test(key)) return Promise.reject(new Error('尚未配置高德 Web 端 Key。'))
  if (window.AMap) return Promise.resolve(window.AMap)
  if (pending) return pending
  pending = new Promise<AMapSdk>((resolve, reject) => {
    window._AMapSecurityConfig = { serviceHost: `${window.location.origin}/_AMapService` }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.Walking,AMap.Weather`
    script.async = true
    script.referrerPolicy = 'strict-origin-when-cross-origin'
    const timeout = window.setTimeout(() => finish(), 20000)
    function finish() {
      window.clearTimeout(timeout); script.onload = null; script.onerror = null
      if (window.AMap) { resolve(window.AMap); return }
      script.remove(); pending = undefined
      reject(new Error('高德地图未能加载，请检查网络或 Key 的域名配置，也可以使用校园沙盘。'))
    }
    script.onload = finish; script.onerror = finish
    document.head.appendChild(script)
  })
  return pending
}
