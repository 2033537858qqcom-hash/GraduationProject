export interface MissionFields {
 campus: string | null; startsAt: string | null; endsAt: string | null; participants: number | null
 keyword: string | null; activityTheme: string | null; activityType: string | null; contactPhone: string | null
}
export interface RepairMissionFields {category:string|null;title:string|null;description:string|null;locationId:number|null;locationDetail:string|null;contact:string|null;contactWindow:string|null}
export interface LeaveMissionFields {reasonType:string|null;reason:string|null;contact:string|null;startDate:string|null;startTime:string|null;endDate:string|null;endTime:string|null}
export interface MissionOption { key: string; venueId: number; name: string; location: string; capacity: number | null; venueVersion: number }
export interface MissionSummary { id: number; requestKey: string; goal: string; mode: 'LOCAL' | 'DEEPSEEK'; stage: string; version: number; updatedAt: string; kind?: string }
export interface MissionStep { number: number; action: string; status: string; message: string; observation: { action: string; status: string; count: number; missing: string[] } | null; promptTokens: number | null; completionTokens: number | null; createdAt: string }
export interface MissionView extends Omit<MissionSummary, 'requestKey'> {
 sessionId: number; fields: MissionFields; missing: string[]; options: MissionOption[]; selectedKey: string | null; proposalHash: string | null
 conflicts: { key: string; title: string; description: string; targetPath: string | null }[]; message: string; steps: MissionStep[]
 business: { id: number; status: string; version: number; targetPath: string } | null; canContinue: boolean
 repairFields?: RepairMissionFields | null; repairPreview?: {ready:boolean;summary:string;locationName:string|null;issues:string[]} | null
 repairLocations?: {id:number;campusName:string;name:string}[]
 leaveFields?: LeaveMissionFields|null;leavePreview?:{ready:boolean;summary:string;duration:string|null;issues:string[];eligibilityMessage:string}|null
}
const clean = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : null
export function missionFieldsPayload(input: Partial<Record<keyof MissionFields, unknown>>): MissionFields {
 const count = typeof input.participants === 'number' || typeof input.participants === 'string' && input.participants.trim() ? Number(input.participants) : NaN
 const time = (value: unknown) => clean(value)?.replace(/(T\d{2}:\d{2}):00$/, '$1') ?? null
 return { campus: clean(input.campus), startsAt: time(input.startsAt), endsAt: time(input.endsAt), participants: Number.isSafeInteger(count) && count >= 1 && count <= 10000 ? count : null,
  keyword: clean(input.keyword), activityTheme: clean(input.activityTheme), activityType: clean(input.activityType), contactPhone: clean(input.contactPhone) }
}
export function repairMissionFieldsPayload(input: Partial<Record<keyof RepairMissionFields,unknown>>): RepairMissionFields {
 const value=typeof input.locationId==='number'||typeof input.locationId==='string'&&input.locationId.trim()?Number(input.locationId):NaN
 return {category:clean(input.category),title:clean(input.title),description:clean(input.description),locationId:Number.isSafeInteger(value)&&value>0?value:null,locationDetail:clean(input.locationDetail),contact:clean(input.contact),contactWindow:clean(input.contactWindow)}
}
export function leaveMissionFieldsPayload(input:Partial<Record<keyof LeaveMissionFields,unknown>>):LeaveMissionFields{return {reasonType:clean(input.reasonType),reason:clean(input.reason),contact:clean(input.contact),startDate:clean(input.startDate),startTime:clean(input.startTime),endDate:clean(input.endDate),endTime:clean(input.endTime)}}
type Proposal = Pick<MissionView, 'version' | 'stage' | 'proposalHash'> & {kind?:string;fields:MissionFields|null;repairFields?:RepairMissionFields|null;leaveFields?:LeaveMissionFields|null}
export function canConfirmMission(mission: Proposal | null, fields: Partial<Record<string, unknown>>, confirmed: boolean) {
 if(!mission||mission.stage!=='WAITING_CONFIRMATION'||!confirmed||!/^[a-f0-9]{64}$/.test(mission.proposalHash??''))return false
 if(mission.kind==='LEAVE')return JSON.stringify(leaveMissionFieldsPayload(fields))===JSON.stringify(leaveMissionFieldsPayload(mission.leaveFields??{}))
 return mission.kind==='REPAIR'?JSON.stringify(repairMissionFieldsPayload(fields))===JSON.stringify(repairMissionFieldsPayload(mission.repairFields??{})):JSON.stringify(missionFieldsPayload(fields))===JSON.stringify(missionFieldsPayload(mission.fields??{}))
}
export function missionConfirmationPayload(mission: Proposal, fields: Partial<Record<string, unknown>>, confirmed: boolean, requestKey: string) {
 if (!canConfirmMission(mission, fields, confirmed)) throw Error('请先更新修改后的条件，再核对并确认当前草稿预览。')
 return { requestKey, version: mission.version, action: 'CONFIRM', confirmed: true, proposalHash: mission.proposalHash }
}
export function missionNeedsPolling(mission: { stage: string } | null) { return mission?.stage === 'RUNNING' }
export function missionStageLabel(stage: string) {
 return ({ RUNNING: '执行中', WAITING_INPUT: '需要补充条件', WAITING_SELECTION: '请选择场地', WAITING_CONFIRMATION: '等待本人确认', PAUSED: '已暂停，可继续', INTERRUPTED: '执行已中断，可恢复', FAILED: '执行未完成', CANCELLED: '已取消', DONE: '草稿已保存并核验' } as Record<string, string>)[stage] ?? stage
}
export const missionActionLabels: Record<string, string> = { planning: '规划下一步', request_input: '核对缺少的条件', check_schedule: '检查本人日程', find_venues: '查找可用场地', present_options: '展示候选场地', prepare_reservation: '核对预约预览', save_reservation: '保存本人草稿', verify_reservation: '读取原申请核验', finish: '确认任务完成',check_leave_requirements:'核对请假规则和时间',inspect_leave_eligibility:'核对本人请假资格',prepare_leave:'核对请假预览',save_leave:'保存本人请假草稿',verify_leave:'读取原请假申请核验',check_repair_requirements:'核对报修资料与指南',inspect_repair_location:'检查本人可报修地点',prepare_repair:'核对报修预览',save_repair:'保存本人报修草稿',verify_repair:'读取原工单核验' }
export function missionStepStatusLabel(status: string) { return ({ RUNNING: '执行中', OK: '完成', PARTIAL: '部分完成', NO_DATA: '暂无候选', NEEDS_INPUT: '等待补充', WAITING_SELECTION: '等待选择', WAITING_CONFIRMATION: '等待确认', SAVED: '已保存', VERIFIED: '已核验', DONE: '完成', FAILED: '未完成', CANCELLED: '已取消', INTERRUPTED: '已中断' } as Record<string, string>)[status] ?? '待核对' }
export function missionBusinessStatusLabel(status: string) { return ({ DRAFT: '草稿', SUBMITTED: '待审核', RETURNED: '待补充', APPROVED: '已批准', IN_USE: '使用中', COMPLETED: '已结束', REJECTED: '已拒绝', WITHDRAWN: '已撤回', CANCELLED: '已取消', EXPIRED: '已过期' } as Record<string, string>)[status] ?? '查看最新状态' }
