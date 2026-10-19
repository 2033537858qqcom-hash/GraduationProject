import { campusCoverage, mapFeatures, mapRoads, mapToModel } from './eastCampusLayout'
import { campusPlaces, featureVerticalRange } from './manifest'
import { westCampusPlaces, westMapPlaces } from './westCampusData'
import { westPlanDefinitions, westPlanFeatures, westPlanRoads, westPlanCoverage, westPlanToModel, westPlanVerticalRange, westPlanBounds } from './westCampusPlan'
import { westCampusCoverage, westMapFeatures, westMapRoads, westFeatureVerticalRange, westModelBounds } from './westCampusLayout'
import type { MapFeature, MapPoint } from './eastCampusLayout'

export type CampusId = 'EAST' | 'WEST'
export type CampusRepresentation = 'reference' | 'geographic'
export function campusSceneData(campusId: CampusId = 'EAST', representation: CampusRepresentation = 'reference') {
  if (campusId === 'EAST') return {
    places: campusPlaces, features: mapFeatures, roads: mapRoads, coverage: campusCoverage,
    toModel: mapToModel, verticalRange: featureVerticalRange,
    bounds: { minX: -128, maxX: 114, minZ: -102, maxZ: 99 },
  }
  if(representation==='reference') {
    const markers: MapFeature[]=westCampusPlaces.filter(place=>place.locationType==='point').map(place=>({
      id:`${place.id}-marker`,placeId:place.id,kind:'point',
      outline:[westPlanDefinitions.find(entry=>entry.id===place.id)!.center],markerHeight:place.height-0.16,
    }))
    return {places:westCampusPlaces,features:[...westPlanFeatures,...markers],roads:westPlanRoads,
      coverage:westPlanCoverage,toModel:westPlanToModel,verticalRange:westPlanVerticalRange,bounds:westPlanBounds}
  }
  const markers: MapFeature[] = westMapPlaces.map(place => ({
    id: `${place.id}-marker`, placeId: place.id, kind: 'point', outline: [[place.x,place.z]],
  }))
  return { places: westMapPlaces, features: [...westMapFeatures,...markers], roads: westMapRoads,
    coverage: westCampusCoverage, toModel: (point: MapPoint): [number,number] => [...point],
    verticalRange: westFeatureVerticalRange, bounds: westModelBounds }
}
