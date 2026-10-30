export type CampusCode = 'EAST' | 'WEST'
export type ExploreMode = 'map' | 'model'
export interface CampusServiceOrigin { placeId: string; campus: CampusCode; view: ExploreMode; name: string }

export function campusPlaceId(value: unknown): string | null {
  return typeof value === 'string' && value.length <= 64 && /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(value) ? value : null
}
function validContext(placeId: string, campus: unknown, view: unknown) {
  return (campus === 'EAST' || campus === 'WEST') && (view === 'map' || view === 'model') && (placeId.startsWith('west-') ? campus === 'WEST' : campus === 'EAST')
}
function placeName(value: unknown) {
  return typeof value === 'string' && value.trim().length <= 80 && !Array.from(value).some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) ? value.trim() || '所选校园地点' : '所选校园地点'
}
export function campusReservationLink(place: { id: string; name: string }, campus: CampusCode, view: ExploreMode): string | null {
  const id = campusPlaceId(place.id)
  if (!id || !validContext(id, campus, view)) return null
  return '/portal/reservations?' + new URLSearchParams({ venue: id, origin: 'campus', place: id, campus, view, placeName: placeName(place.name) })
}
export function campusServiceOrigin(query: Record<string, unknown>): CampusServiceOrigin | null {
  const id = campusPlaceId(query.place)
  if (query.origin !== 'campus' || !id || !validContext(id, query.campus, query.view)) return null
  return { placeId: id, campus: query.campus as CampusCode, view: query.view as ExploreMode, name: placeName(query.placeName) }
}
export function campusExploreReturn(origin: CampusServiceOrigin) {
  return '/campus/explore?' + new URLSearchParams({ campus: origin.campus, view: origin.view, focus: origin.placeId })
}
/** A local exploration URL contains only orientation fields, never an arbitrary return path. */
export function safeCampusExploreDestination(value: unknown): string | null {
  if (typeof value !== 'string' || !/^\/campus\/explore(?:\?|$)/.test(value) || /[\\\r\n]/.test(value)) return null
  const url = new URL(value, 'https://campus.invalid')
  const allowed = ['campus', 'view', 'focus', 'navigate']
  if (url.hash || [...url.searchParams.keys()].some(key => !allowed.includes(key) || url.searchParams.getAll(key).length !== 1)) return null
  const focus = url.searchParams.get('focus'), navigate = url.searchParams.get('navigate')
  if (focus !== null && navigate !== null) return null
  const id = focus ?? navigate
  if (id !== null && !campusPlaceId(id)) return null
  const campus = url.searchParams.get('campus'), view = url.searchParams.get('view')
  if (campus !== null && !['EAST', 'WEST'].includes(campus) || view !== null && !['map', 'model'].includes(view)) return null
  if (id && campus && !validContext(id, campus, view ?? 'model')) return null
  return value
}
