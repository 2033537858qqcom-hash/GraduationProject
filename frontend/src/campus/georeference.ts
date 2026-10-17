import type { LngLat } from './amapSdk'
import { campusCoverage, detailToMap, mapToModel } from './eastCampusLayout'

export type Point2 = readonly [number, number]
export interface GeoAnchor { name: string; model: Point2; lnglat: LngLat }
// Saved satellite features were matched to visible live JS API views using SIFT
// and a RANSAC similarity transform, then read through the calibration UI.
// GCJ-02 throughout. Image residuals are not survey/entrance accuracy.
export const campusGeoAnchors: GeoAnchor[] = [
  { name: '图书馆内庭', model: mapToModel(detailToMap('central', [225, 438])), lnglat: [113.7572980, 34.4135520] },
  { name: '书华楼庭院', model: mapToModel(detailToMap('north', [410, 296])), lnglat: [113.7581920, 34.4155320] },
  { name: '文华楼北庭院', model: mapToModel(detailToMap('wenhua', [515, 377])), lnglat: [113.7588450, 34.4128770] },
]
export const campusGeoEvidence = {
  date: '2026-10-17', coordinateSystem: 'GCJ-02', method: 'satellite-feature-registration-and-visible-coordinate-readout',
  screenshot: 'artifacts/campus-amap-integration-2026-10-17/calibration-independent-check.jpg',
  imageRegistration: [
    { view: 'library', inliers: 456, rmsCanvasPixels: 0.814 },
    { view: 'shuhua', inliers: 340, rmsCanvasPixels: 0.903 },
    { view: 'wenhua', inliers: 469, rmsCanvasPixels: 0.829 },
    { view: 'jingyi-check', inliers: 365, rmsCanvasPixels: 0.858 },
  ],
  validation: { name: '静逸楼东庭院', model: mapToModel(detailToMap('central', [584, 271])), lnglat: [113.7595300, 34.4144090] as LngLat },
} as const
export const campusMapCenter: LngLat = [113.7576, 34.4126]

export function fitAffine(source: Point2[], target: Point2[]) {
  if (source.length !== 3 || target.length !== 3 || [...source, ...target].some(p => p.length !== 2 || p.some(n => !Number.isFinite(n)))) throw new Error('Three finite control points are required')
  const [p, q, r] = source as [Point2, Point2, Point2]
  const [u, v, w] = target as [Point2, Point2, Point2]
  const determinant = (q[0] - p[0]) * (r[1] - p[1]) - (r[0] - p[0]) * (q[1] - p[1])
  if (Math.abs(determinant) < 1e-8) throw new Error('Control points are collinear')
  const rows = [0, 1].map(i => {
    const a = ((v[i]! - u[i]!) * (r[1] - p[1]) - (w[i]! - u[i]!) * (q[1] - p[1])) / determinant
    const b = ((w[i]! - u[i]!) * (q[0] - p[0]) - (v[i]! - u[i]!) * (r[0] - p[0])) / determinant
    return [a, b, u[i]! - a * p[0] - b * p[1]] as const
  }) as [readonly [number, number, number], readonly [number, number, number]]
  const scale = Math.sqrt(Math.abs(rows[0][0] * rows[1][1] - rows[0][1] * rows[1][0]))
  if (!Number.isFinite(scale) || scale < 1e-8) throw new Error('Control points collapse the campus')
  return {
    rows, scale,
    apply: ([x, y]: Point2): [number, number] => rows.map(([a, b, c]) => a * x + b * y + c) as [number, number],
  }
}

export function campusLngLat(model: Point2): LngLat | undefined {
  if (campusGeoAnchors.length !== 3) return undefined
  // Campus-scale interpolation in GCJ-02 is sufficient for directory centering.
  // The 3D overlay itself fits in AMap's projected custom-coordinate system.
  return fitAffine(campusGeoAnchors.map(a => a.model), campusGeoAnchors.map(a => a.lnglat)).apply(model)
}

export function campusMapFrame(width: number, height: number) {
  const radius = 6378137, radians = Math.PI / 180
  const points = campusCoverage.map(point => {
    const [lng, lat] = campusLngLat(mapToModel(point))!
    return [radius * lng * radians, radius * Math.log(Math.tan(Math.PI / 4 + lat * radians / 2))] as const
  })
  const xs = points.map(p => p[0]), ys = points.map(p => p[1])
  const left = Math.min(...xs), right = Math.max(...xs), bottom = Math.min(...ys), top = Math.max(...ys)
  const pixelsPerMetre = Math.min(Math.max(160, width - 80) / (right - left), Math.max(160, height - 90) / (top - bottom))
  return {
    center: [(left + right) / 2 / radius / radians, (2 * Math.atan(Math.exp((bottom + top) / 2 / radius)) - Math.PI / 2) / radians] as LngLat,
    zoom: Math.max(14, Math.min(17, Math.log2(pixelsPerMetre * 2 * Math.PI * radius / 256))),
  }
}
