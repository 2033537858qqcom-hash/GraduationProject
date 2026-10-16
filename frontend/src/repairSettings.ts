export interface RepairCategory { code: string; name: string }
export interface RepairLocation {
  id: number; campusId: number; campusName: string; code: string; name: string; kind: string
  classroomResourceId: number | null; buildingId: number | null; dormRoomId: number | null; sourceName: string | null; detailHint: string
  provenance: string; enabled: boolean; available: boolean; availabilityMessage: string
  buildingVerificationStatus: string | null; version: number; updatedAt: string
}
export interface RepairScope {
  id: number; userId: number; userName: string; capability: string; campusId: number; campusName: string
  category: string; locationId: number | null; locationName: string | null; enabled: boolean; effective: boolean
  effectivenessMessage: string; version: number; updatedAt: string
}
export interface RepairCatalog {
  campuses: { id: number; name: string }[]; categories: RepairCategory[]
  buildings: { id: number; campusId: number; name: string; verificationStatus: string }[]
  rooms: { id: number; campusId: number; name: string; buildingName: string | null; roomNumber: string | null; origin: string; available: boolean }[]
  people: { id: number; name: string; departmentName: string | null; capabilities: string[]; enabled: boolean }[]
  dormRooms: { id: number; campusId: number; name: string; buildingName: string; floorLabel: string; provenance: string; available: boolean }[]
}
export interface RepairPublicLocation { id: number; campusId: number; campusName: string; name: string; kind: string; detailHint: string; provenance: string }
export interface DormRepairEligibility { occupied: boolean; location: RepairPublicLocation | null; message: string }
export interface RepairAudit { id: number; actorName: string; action: string; entityType: string; entityId: number; summary: string; reason: string; createdAt: string }
export const repairKinds: Record<string, string> = { CLASSROOM: '教室', BUILDING_COMMON: '楼栋公共部位', CAMPUS_PUBLIC: '校园公共区域', DORM_ROOM: '本人宿舍房间' }
export const repairCapabilities: Record<string, string> = { DISPATCH: '后勤分诊与派单', WORK: '维修处理' }
export const repairProvenance: Record<string, string> = { DEMO: '演示地点', CONFIGURED: '管理员配置' }
export function repairPeopleForCapability(people: RepairCatalog['people'], capability: string) {
  return people.filter(person => person.enabled && person.capabilities.includes(capability))
}
export function repairSourceIds(kind: string, roomId: string, buildingId: string, dormRoomId = '') {
  return { classroomResourceId: kind === 'CLASSROOM' && roomId ? Number(roomId) : null,
    buildingId: ['CLASSROOM', 'BUILDING_COMMON'].includes(kind) && buildingId ? Number(buildingId) : null,
    dormRoomId: kind === 'DORM_ROOM' && dormRoomId ? Number(dormRoomId) : null }
}
/** Query strings identify a source to verify with the server; they never confer permission. */
export function repairLocationQuery(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}
