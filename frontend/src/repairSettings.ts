export interface RepairCategory { code: string; name: string }
export interface RepairLocation {
  id: number; campusId: number; campusName: string; code: string; name: string; kind: string
  classroomResourceId: number | null; buildingId: number | null; sourceName: string | null; detailHint: string
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
}
export interface RepairAudit { id: number; actorName: string; action: string; entityType: string; entityId: number; summary: string; reason: string; createdAt: string }
export const repairKinds: Record<string, string> = { CLASSROOM: '教室', BUILDING_COMMON: '楼栋公共部位', CAMPUS_PUBLIC: '校园公共区域' }
export const repairCapabilities: Record<string, string> = { DISPATCH: '后勤分诊与派单', WORK: '维修处理' }
export const repairProvenance: Record<string, string> = { DEMO: '演示地点', CONFIGURED: '管理员配置' }
export function repairPeopleForCapability(people: RepairCatalog['people'], capability: string) {
  return people.filter(person => person.enabled && person.capabilities.includes(capability))
}
export function repairSourceIds(kind: string, roomId: string, buildingId: string) {
  return { classroomResourceId: kind === 'CLASSROOM' && roomId ? Number(roomId) : null,
    buildingId: kind !== 'CAMPUS_PUBLIC' && buildingId ? Number(buildingId) : null }
}
