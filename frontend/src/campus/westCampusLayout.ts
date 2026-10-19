import type { LngLat } from './amapSdk'
import type { MapFeature, MapPoint, MapRoad } from './eastCampusLayout'

// Roof traces from the saved, north-up JS API satellite view. These are visible
// roof envelopes, not cadastral boundaries or measured ground-floor footprints.
export const westCampusEvidence = {
  date: '2026-10-19',
  screenshot: 'assets/campus/west/satellite-overview-2026-10-19.png',
  extentScreenshot: 'assets/campus/west/satellite-extent-2026-10-19.png',
  poiFile: 'assets/campus/west/amap-pois-2026-10-19.json',
  method: 'AMap.PlaceSearch GCJ-02 points and manual satellite roof tracing',
  heightAccuracy: 'display-only',
  // Calibration UI readouts for canvas (0,0), (836,0), (0,535).
  overview: { left: 113.709042, top: 34.432119, right: 113.719345, bottom: 34.426681 },
  extent: { left: 113.706906, top: 34.433245, right: 113.721477, bottom: 34.425554 },
  canvas: { x: 63.89583587646485, y: 379.7604370117187, width: 836, height: 535 },
} as const

export const westModelOrigin: LngLat = [113.7133, 34.4297]
export const westModelScale = 0.4
const lngMetres = 111320 * Math.cos(westModelOrigin[1] * Math.PI / 180)
export function westLngLatToModel([lng, lat]: LngLat): [number, number] {
  return [(lng - westModelOrigin[0]) * lngMetres * westModelScale, (westModelOrigin[1] - lat) * 111320 * westModelScale]
}
export function westModelToLngLat([x, z]: MapPoint): LngLat {
  return [westModelOrigin[0] + x / lngMetres / westModelScale, westModelOrigin[1] - z / 111320 / westModelScale]
}
function trace(points: MapPoint[], view: 'overview' | 'extent' = 'overview'): MapPoint[] {
  const frame = westCampusEvidence[view], canvas = westCampusEvidence.canvas
  return points.map(([x, y]) => westLngLatToModel([
    frame.left + (x - canvas.x) / canvas.width * (frame.right - frame.left),
    frame.top + (y - canvas.y) / canvas.height * (frame.bottom - frame.top),
  ]))
}
const roof = (id: string, points: MapPoint[]): MapFeature => ({ id, kind: 'building', outline: trace(points) })
const rect = (id: string, x: number, y: number, w: number, h: number) => roof(id, [[x,y],[x+w,y],[x+w,y+h],[x,y+h]])

// Unnamed roofs remain unnamed: a POI location alone cannot establish which
// roof, wing or floor belongs to it. The eastern adjoining schools are excluded.
export const westMapFeatures: MapFeature[] = [
  rect('west-roof-nw-1', 260, 389, 64, 12), rect('west-roof-nw-2', 260, 423, 66, 12),
  rect('west-roof-nw-3', 263, 458, 67, 12), rect('west-roof-nw-4', 265, 491, 69, 13),
  rect('west-roof-nw-5', 267, 527, 67, 13), rect('west-roof-nw-6', 272, 560, 65, 13),
  rect('west-roof-nw-7', 276, 595, 60, 13),
  rect('west-roof-north-1', 347, 390, 15, 65), rect('west-roof-north-2', 356, 473, 51, 13),
  rect('west-roof-north-3', 350, 510, 56, 13), rect('west-roof-north-4', 349, 547, 55, 14),
  rect('west-roof-north-5', 348, 582, 56, 14),
  rect('west-roof-ne-1', 414, 520, 52, 13), rect('west-roof-ne-2', 415, 550, 53, 13),
  rect('west-roof-ne-3', 414, 581, 54, 14), rect('west-roof-ne-4', 477, 595, 37, 14),
  rect('west-roof-axis-1', 514, 577, 14, 37),
  roof('west-roof-courtyard-west', [[283,666],[303,665],[303,693],[328,693],[329,668],[344,667],[343,709],[283,708]]),
  roof('west-roof-courtyard-center', [[423,661],[441,656],[459,660],[475,674],[476,690],[462,700],[438,698],[425,685]]),
  roof('west-roof-courtyard-east', [[514,658],[529,658],[529,710],[512,710],[512,699],[519,699],[519,669],[514,669]]),
  rect('west-roof-southwest-1', 266, 767, 20, 23), rect('west-roof-southwest-2', 294, 768, 31, 20),
  rect('west-roof-southwest-3', 326, 766, 17, 26),
  roof('west-roof-south-court', [[399,759],[422,758],[422,769],[410,769],[409,800],[438,800],[438,775],[453,775],[454,812],[399,813]]),
  rect('west-roof-east-shed-1', 480, 753, 57, 14), rect('west-roof-east-shed-2', 482, 782, 56, 14),
  roof('west-roof-training-north', [[421,817],[546,815],[549,848],[421,854]]),
  roof('west-roof-training-south', [[422,865],[547,857],[554,914],[530,914],[529,884],[478,887],[477,914],[448,914],[446,885],[422,889]]),
  rect('west-roof-south-annex-1', 280, 878, 28, 17), rect('west-roof-south-annex-2', 314, 879, 30, 16),
  rect('west-roof-south-annex-3', 357, 881, 29, 16), rect('west-roof-south-annex-4', 381, 900, 24, 15),
  // Northern sports surface is visible context; current ownership is not inferred.
  { id: 'west-context-track', kind: 'track', outline: trace([[456,418],[476,420],[492,438],[498,477],[493,507],[479,526],[454,530],[436,514],[428,480],[429,444],[440,424]], 'extent'),
    holes: [trace([[455,438],[474,438],[479,450],[480,496],[474,510],[455,511],[446,497],[445,452]], 'extent')] },
  { id: 'west-context-field', kind: 'field', outline: trace([[455,438],[474,438],[479,450],[480,496],[474,510],[455,511],[446,497],[445,452]], 'extent') },
  { id: 'west-central-courtyard', kind: 'plaza', outline: trace([[355,653],[415,652],[417,740],[354,743]]) },
]

// Traced visible road centre lines, without invented road names.
export const westMapRoads: MapRoad[] = [
  { id: 'west-highway-context', width: 26, points: trace([[179,380],[181,544],[198,701],[213,915]]) },
  { id: 'west-huaxin-context', width: 22, points: trace([[185,650],[331,648],[530,642],[562,640]]) },
  { id: 'west-taoyuan-context', width: 19, points: trace([[531,380],[535,580],[548,750],[565,915]]) },
  { id: 'west-inner-west', width: 12, points: trace([[248,383],[252,555],[268,646],[272,817],[276,910]]) },
  { id: 'west-inner-axis', width: 12, points: trace([[340,642],[340,739],[379,816],[389,910]]) },
  { id: 'west-inner-south', width: 12, points: trace([[269,815],[389,815],[550,805]]) },
  { id: 'west-inner-north', width: 12, points: trace([[252,576],[416,572],[529,565]]) },
]
// Presentation envelope, explicitly not a statement of campus ownership.
export const westCampusCoverage: MapPoint[] = [[-125,-145],[95,-145],[95,160],[-125,160]]
export const westModelBounds = { minX: -155, maxX: 100, minZ: -145, maxZ: 165 }
export const westGeoAnchors = [[0,0],[100,0],[0,100]].map(model => ({ model: model as [number,number], lnglat: westModelToLngLat(model as [number,number]) }))
export function westFeatureVerticalRange(feature: MapFeature) {
  return feature.kind === 'point' ? { base: 5.2, height: 0.16 }
    : { base: feature.kind === 'field' ? 0.34 : 0.24, height: feature.kind === 'building' ? 4.8 : 0.08 }
}
