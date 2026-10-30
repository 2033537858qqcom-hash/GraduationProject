import { affairStatuses } from './affairs.ts'
import { certificateStatuses } from '../certificates.ts'

export type ServiceKind = 'LEAVE' | 'CERTIFICATE' | 'REPAIR' | 'HOUSING' | 'RESERVATION'
interface JourneyDefinition { steps: string[]; stages: Record<string, number>; preparation: string; notes: Record<string, string> }
const definitions: Record<ServiceKind, JourneyDefinition> = {
  LEAVE: {
    steps: ['填写资料', '辅导员审核', '返校与销假', '办理结束'],
    stages: { DRAFT: 0, RETURNED: 0, SUBMITTED: 1, APPROVED: 2, RETURN_PENDING: 2, COMPLETED: 3 },
    preparation: '先保存请假资料，再核对材料并手动提交；批准后返校仍需申报销假。',
    notes: { DRAFT: '草稿尚未提交。核对时间、事由及材料后，再提交审核。', SUBMITTED: '等待负责班级的辅导员核对，处理结果会进入个人消息。', APPROVED: '请假已批准。实际返校后，请在本申请中申报返校销假。', RETURN_PENDING: '返校已申报，等待辅导员核实；当前尚未确认销假。', COMPLETED: '本次请假已确认销假，可在办理记录中核对资料与意见。' },
  },
  CERTIFICATE: {
    steps: ['核对登记资料', '提交与审核', '查看证明结果'],
    stages: { DRAFT: 0, RETURNED: 0, SUBMITTED: 1, COMPLETED: 2 },
    preparation: '核对本人登记资料与实际用途，保存后手动提交；审核完成后下载演示证明。',
    notes: { DRAFT: '核对本人登记资料和证明用途，再提交审核。', SUBMITTED: '等待当前有权处理的学院人员核对登记资料与用途。', COMPLETED: '证明已办理完成，请在结果区域核对并下载演示 PDF。' },
  },
  REPAIR: {
    steps: ['记录故障', '分诊与派工', '维修处理', '本人验收', '查看结果'],
    stages: { DRAFT: 0, RETURNED: 0, SUBMITTED: 1, ASSIGNED: 2, IN_PROGRESS: 2, AWAITING_CONFIRMATION: 3, COMPLETED: 4 },
    preparation: '选择实际地点，说明故障并保存资料；图片上传后，再提交后勤处理。',
    notes: { DRAFT: '草稿尚未提交。核对地点、故障描述与已上传图片，再提交处理。', SUBMITTED: '等待当前负责范围的后勤人员分诊与派工。', ASSIGNED: '维修人员尚未接单，后续进展以当前工单为准。', IN_PROGRESS: '维修正在处理中，可查看进展与维修材料。', AWAITING_CONFIRMATION: '维修结果待本人验收；请核对结果后确认或反馈问题。', COMPLETED: '本次维修已完成，可查看验收记录与评价入口。' },
  },
  HOUSING: {
    steps: ['填写申请', '学院初审', '安排床位', '入住确认'],
    stages: { DRAFT: 0, RETURNED: 0, SUBMITTED: 1, AWAITING_ASSIGNMENT: 2, AWAITING_CHECKIN: 3, COMPLETED: 3 },
    preparation: '说明调宿原因与意向区域，保存资料后核对材料并提交；新床位由有权宿管安排。',
    notes: { DRAFT: '草稿尚未提交。核对原因、意向及材料说明，再提交学院初审。', SUBMITTED: '等待当前学院初审人员核对申请。', AWAITING_ASSIGNMENT: '学院初审已通过，等待有权宿管安排目标床位。', AWAITING_CHECKIN: '目标床位已预留，尚未完成入住；请核对记录中的预留期限与安排。', COMPLETED: '本次调宿已完成，可核对当前床位与入住记录。' },
  },
  RESERVATION: {
    steps: ['填写借用申请', '审核处理', '按时使用', '结束核销'],
    stages: { DRAFT: 0, RETURNED: 0, SUBMITTED: 1, APPROVED: 2, IN_USE: 2, COMPLETED: 3 },
    preparation: '先查资源与可用时段，再填写活动安排；提交时会重新检查占用与办理条件。',
    notes: { DRAFT: '草稿尚未提交。核对人数、时段和资源，再检查冲突并提交。', SUBMITTED: '等待该资源当前有权负责人审核；本人申请不能由自己审批。', APPROVED: '预约已获同意，尚未结束核销；使用与登记请核对详情中的可用操作。', IN_USE: '本次预约已登记使用，结束后仍需办理核销。', COMPLETED: '本次预约已结束核销，可查看实际使用与处理记录。' },
  },
}
const closedStatuses = new Set(['REJECTED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED'])

/** Read-only orientation. Actual actions, eligibility and history remain authoritative. */
export function serviceJourney(kind: ServiceKind, status?: string, options: { preparing?: boolean; reviewer?: boolean; cancellationPending?: boolean } = {}) {
  const definition = definitions[kind]
  const labels = kind === 'CERTIFICATE' ? certificateStatuses : affairStatuses(kind)
  const known = !!status && Object.hasOwn(labels, status)
  const base = { steps: definition.steps, index: null as number | null, label: options.preparing ? '正在填写' : '办理说明', note: definition.preparation, tone: 'neutral' }
  if (status && !known) return { ...base, label: '状态待核对', note: '请刷新记录，并以当前资料、处理意见及可用操作为准。' }
  if (options.cancellationPending) return { ...base, label: '取消待处理', tone: 'attention', note: '取消请求尚未处理完成；请核对当前记录中的意见与可用操作。' }
  if (!known) return { ...base, index: options.preparing ? 0 : null }
  const label = labels[status!]!
  if (closedStatuses.has(status!)) return { ...base, label, tone: 'closed', note: '该记录已结束本次办理，资料与处理记录保留；需要重新办理时请核对新申请条件。' }
  let note = definition.notes[status!] ?? definition.preparation
  if (status === 'RETURNED') note = options.reviewer ? '等待申请人补充并重新提交，材料以已提交版本为准。' : '请先查看处理意见，补充资料和材料后重新提交。'
  else if (options.reviewer && status === 'SUBMITTED') note = '核对申请资料与已提交材料，在当前记录中填写处理意见。'
  else if (options.reviewer && status === 'RETURN_PENDING') note = '核实实际返校情况，填写核对意见后确认销假或退回核对。'
  return { steps: definition.steps, index: options.preparing ? 0 : definition.stages[status!] ?? null, label, note,
    tone: status === 'RETURNED' ? 'attention' : status === 'COMPLETED' ? 'complete' : 'active' }
}
