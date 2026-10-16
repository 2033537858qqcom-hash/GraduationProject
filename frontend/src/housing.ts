export interface HousingPage<T> { items: T[]; total: number; page: number; pageSize: number }
export interface HousingCampus { id: number; name: string }
export interface HousingManagerOption { id: number; name: string; departmentName: string | null; enabled: boolean }
export interface HousingBuildingOption { id: number; campusId: number; name: string; enabled: boolean }
export interface HousingCatalog { campuses: HousingCampus[]; buildings: HousingBuildingOption[]; managers: HousingManagerOption[] }
export interface DormBuilding { id: number; campusId: number; campusName: string; code: string; name: string; provenance: string; enabled: boolean; version: number; roomCount: number; occupiedCount: number }
export interface DormRoom { id: number; buildingId: number; buildingName: string; code: string; name: string; floorLabel: string; enabled: boolean; version: number; bedCount: number; occupiedCount: number }
export interface DormBed { id: number; roomId: number; buildingId: number; roomName: string; floorLabel: string; code: string; label: string; status: string; enabled: boolean; roomEnabled: boolean; buildingEnabled: boolean; version: number; assignmentId: number | null; assignmentVersion: number | null; occupantUsername: string | null; occupantName: string | null }
export interface DormScope { id: number; userId: number; userName: string; buildingId: number; campusName: string; buildingName: string; enabled: boolean; effective: boolean; effectivenessMessage: string; version: number }
export interface HousingAssignment { id: number; studentUsername: string; studentName: string; bedId: number; buildingId: number; campusName: string; buildingName: string; roomName: string; floorLabel: string; bedLabel: string; status: string; version: number; startedAt: string; endedAt: string | null; endReason: string | null; importBatchId: number | null }
export interface HousingCurrent { campusName: string; buildingName: string; roomName: string; floorLabel: string; bedLabel: string; startedAt: string; managers: string[] }
export interface HousingHistory { id: number; campusName: string; buildingName: string; roomName: string; floorLabel: string; bedLabel: string; startedAt: string; endedAt: string; endReason: string }
export interface HousingMine { current: HousingCurrent | null; history: HousingHistory[] }
export interface HousingResponsibilities { scopes: DormScope[]; occupied: number; vacant: number }
export interface HousingStudent { id: number; username: string; fullName: string; housed: boolean }
export const housingBedStatus: Record<string, string> = { VACANT: '空闲', OCCUPIED: '已入住', MAINTENANCE: '维护中', RESERVED: '已预留' }
export function housingRequestKey() { return `HK${crypto.randomUUID().replaceAll('-', '')}` }
export function bedCanAssign(bed: { enabled: boolean; status: string; buildingEnabled: boolean; roomEnabled: boolean }) {
  return bed.enabled && bed.buildingEnabled && bed.roomEnabled && bed.status === 'VACANT'
}
export function housingWhen(value: string | null) { return value ? value.replace('T', ' ').slice(0, 16) : '' }
export interface HousingTransfer { id: number; status: string; studentUsername: string; studentName: string; sourcePlace: string; reason: string; preferredArea: string; materialNote: string; reviewComment: string | null; targetPlace: string | null; targetBedId: number | null; reserveUntil: string | null; cancelRequested: boolean; version: number; allowedActions: string[] }
export const transferStatus: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '待初审', RETURNED: '待补交', AWAITING_ASSIGNMENT: '待安排', AWAITING_CHECKIN: '待入住', COMPLETED: '已完成', REJECTED: '已拒绝', WITHDRAWN: '已撤回', CANCELLED: '已取消', EXPIRED: '已过期' }
