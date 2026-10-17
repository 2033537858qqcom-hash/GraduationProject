import { campusEvidence, detailViews, mapFeatures, mapToModel } from './eastCampusLayout'
import type { MapFeature } from './eastCampusLayout'
import { campusPlaceDefinitions } from './placeDefinitions'

export type CampusCategory = 'learning' | 'housing' | 'services' | 'sports' | 'landscape'
export type BuildingKind = 'building' | 'plaza' | 'sports' | 'water' | 'entrance' | 'service'

export interface CampusPlace {
  id: string
  name: string
  category: CampusCategory
  kind: BuildingKind
  x: number
  z: number
  width: number
  depth: number
  /** Highest rendered surface in model units; not a measured building height. */
  height: number
  description: string
  features: string[]
  sourceUrl: string
  sourceScreenshot: string
  locationType: 'outline' | 'point'
  verification: 'map-observed' | 'reference-located'
  heightStatus: 'display-only'
  /** Association within a shared group, not exclusive building ownership. */
  relatedPlaceId?: string
}

/** Display-only top of schematic silver grandstand arcs on east-sports; not surveyed. */
export const sportsGrandstandRoofTop = 3.2

// Schematic heights; no floor counts or real-world metres are inferred.
export const buildingHeightMap: Record<string, number> = {
  'east-library': 5.8, 'east-swimming': 4.6, 'east-gym': 3.8,
  'east-shuhua': 4.2, 'east-zhiyuan': 4.2, 'east-jingyi': 4.2,
  'east-jinxiu-dining': 4.2, 'east-wenhua': 4.2,
  'east-xinke': 4, 'east-jiangong': 4, 'east-construction': 4, 'east-medical': 4,
  'east-art': 3.8, 'east-h8': 3.8, 'east-jinxiu': 3.6, 'east-fenghua': 3.6,
  'east-admin': 3.4, 'east-auditorium': 3.4,
  'east-canteen-wenhua': 3.4, 'east-construction-training': 3.4,
}

export function featureVerticalRange(feature: MapFeature): { base: number; height: number } {
  if (feature.kind === 'point') return { base: (feature.markerHeight ?? 0.28) - 0.08, height: 0.16 }
  return {
    base: feature.kind === 'field' ? 0.34 : feature.kind === 'water' ? 0.19 : 0.24,
    height: feature.kind === 'building' ? (feature.placeId && buildingHeightMap[feature.placeId]) || 3.4 : 0.08,
  }
}

export const campusPlaces: CampusPlace[] = campusPlaceDefinitions.map(entry => {
  const features = mapFeatures.filter(feature => feature.placeId === entry.id)
  if (!features.length) throw new Error(`Missing map geometry for ${entry.id}`)
  const points = features.flatMap(feature => feature.outline.map(mapToModel))
  const xs = points.map(point => point[0]), zs = points.map(point => point[1])
  const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs)
  const evidenceView = features.find(feature => feature.evidenceView)?.evidenceView
  return {
    ...entry,
    x: (minX + maxX) / 2, z: (minZ + maxZ) / 2,
    width: Math.max(3, maxX - minX), depth: Math.max(3, maxZ - minZ),
    height: Math.max(
      ...features.map(feature => { const range = featureVerticalRange(feature); return range.base + range.height }),
      entry.id === 'east-sports' ? sportsGrandstandRoofTop : 0,
    ),
    sourceUrl: entry.locationSource === 'layout' ? campusEvidence.layoutReferenceUrl : campusEvidence.mapUrl,
    sourceScreenshot: entry.locationSource === 'layout' ? campusEvidence.layoutReferenceFile : evidenceView ? detailViews[evidenceView].standard : campusEvidence.screenshot,
    locationType: features.every(feature => feature.kind === 'point') ? 'point' : 'outline',
    verification: entry.locationSource === 'layout' ? 'reference-located' : 'map-observed',
    heightStatus: 'display-only',
  }
})

export const campusCategories = [
  { id: 'all', label: '全部' }, { id: 'learning', label: '楼宇' }, { id: 'housing', label: '住宿' },
  { id: 'sports', label: '文体' }, { id: 'landscape', label: '景观' }, { id: 'services', label: '服务' },
] as const

export const campusTour = [
  'south-gate', 'east-construction', 'east-medical', 'east-admin', 'east-auditorium',
  'east-canteen-wenhua', 'east-library', 'east-jinxiu-dining', 'east-jingyi',
  'east-shuhua', 'east-zhiyuan', 'east-art', 'east-square', 'east-sports',
  'east-gym', 'east-swimming', 'east-jinxiu', 'east-fenghua', 'east-water', 'east-gate',
]
