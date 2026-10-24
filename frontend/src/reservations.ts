import { api } from './lib/api.ts'

export interface VenueSummary {
  id: number
  code: string
  name: string
  category: 'VENUE' | 'EQUIPMENT'
  placeId: string | null
  buildingName: string | null
  locationDetail: string
  capacity: number | null
  availableEquipment: string | null
  rules: string | null
  enabled: boolean
}

export interface TimeSlot {
  startsAt: string
  endsAt: string
  activityTheme: string
  status: string
}

export interface ReservationItem {
  id: number
  requestKey: string
  venueId: number
  venueName: string
  category: string
  placeId: string | null
  locationDetail: string
  activityTheme: string
  activityType: string
  expectedParticipants: number
  borrowEquipment: string | null
  startsAt: string
  endsAt: string
  status: string
  processingComment: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface ReservationActionDto {
  id: number
  reservationId: number
  reservationVersion: number
  actorId: number
  actorName: string
  action: string
  status: string
  comment: string
  createdAt: string
}

export interface ReservationDetail {
  id: number
  applicantId: number
  requestKey: string
  venueId: number
  venueName: string
  category: string
  placeId: string | null
  locationDetail: string
  applicantName: string
  identityNumber: string
  contactPhone: string
  departmentName: string
  className: string | null
  activityTheme: string
  activityType: string
  expectedParticipants: number
  borrowEquipment: string | null
  startsAt: string
  endsAt: string
  status: string
  processingComment: string | null
  version: number
  createdAt: string
  updatedAt: string
  actions: ReservationActionDto[]
}

export interface CreateReservationCommand {
  requestKey: string
  venueId: number
  activityTheme: string
  activityType: string
  expectedParticipants: number
  borrowEquipment?: string
  startsAt: string
  endsAt: string
  contactPhone: string
}

export interface CheckConflictCommand {
  venueId: number
  startsAt: string
  endsAt: string
  excludeId?: number
}

export interface ConflictResult {
  hasConflict: boolean
  message: string
  conflictingSlots: TimeSlot[]
}

export interface ReviewReservationCommand {
  action: 'APPROVE' | 'RETURN' | 'REJECT'
  comment: string
}

export interface ReservationPage {
  items: ReservationItem[]
  total: number
  page: number
  pageSize: number
}

export const reservationStatuses: Record<string, string> = {
  DRAFT: '未提交草稿',
  SUBMITTED: '待审核',
  RETURNED: '待补充材料',
  APPROVED: '已同意预约',
  IN_USE: '使用中',
  COMPLETED: '已结束核销',
  EXPIRED: '已过期未使用',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  WITHDRAWN: '已撤回',
}

export const reservationActions: Record<string, string> = {
  DRAFT: '保存草稿',
  SUBMIT: '提交申请',
  APPROVE: '审批通过',
  RETURN: '退回修改',
  REJECT: '驳回借用',
  CANCEL: '取消预约',
  WITHDRAW: '撤回申请',
  START: '登记开始使用',
  COMPLETE: '确认使用结束',
  EXPIRE: '预约过期未使用',
  RESOURCE_DISABLED: '场地停用取消预约',
}

export const activityTypes: Record<string, string> = {
  ACADEMIC: '学术研讨/讲座',
  CULTURAL: '文艺展演/排练',
  SPORTS: '体育赛事/锻炼',
  CLASS_MEETING: '班级会议/团日活动',
  CLUB: '社团纳新/实践活动',
  VOLUNTEER: '志愿公益服务',
  OTHER: '其他校园事务',
}

export async function listVenues(params?: { category?: string; placeId?: string; keyword?: string; enabledOnly?: boolean }): Promise<VenueSummary[]> {
  const query = new URLSearchParams()
  if (params?.category) query.set('category', params.category)
  if (params?.placeId) query.set('placeId', params.placeId)
  if (params?.keyword) query.set('keyword', params.keyword)
  if (params?.enabledOnly !== undefined) query.set('enabledOnly', String(params.enabledOnly))
  return api<VenueSummary[]>('/reservations/venues?' + query.toString())
}

export async function getVenue(id: number): Promise<VenueSummary> {
  return api<VenueSummary>('/reservations/venues/' + id)
}

export async function getVenueSchedule(id: number, date: string): Promise<TimeSlot[]> {
  const query = new URLSearchParams({ date })
  return api<TimeSlot[]>(`/reservations/venues/${id}/schedule?${query.toString()}`)
}

export async function checkConflict(cmd: CheckConflictCommand): Promise<ConflictResult> {
  return api<ConflictResult>('/reservations/check-conflict', {
    method: 'POST',
    body: JSON.stringify(cmd),
  })
}

export async function listMine(params?: { status?: string; page?: number; pageSize?: number }): Promise<ReservationPage> {
  const query = new URLSearchParams()
  if (params?.status) query.set('status', params.status)
  query.set('page', String(params?.page ?? 1))
  query.set('pageSize', String(params?.pageSize ?? 20))
  return api<ReservationPage>('/reservations/mine?' + query.toString())
}

export async function getDetail(id: number): Promise<ReservationDetail> {
  return api<ReservationDetail>('/reservations/' + id)
}

export function reservationPassUrl(id: number, inline = false): string {
  if (!Number.isSafeInteger(id) || id < 1) throw new Error('借用申请编号无效，请刷新后重试')
  return `/api/reservations/${id}/pass${inline ? '?inline=true' : ''}`
}

export async function saveDraft(cmd: CreateReservationCommand): Promise<ReservationDetail> {
  return api<ReservationDetail>('/reservations/draft', {
    method: 'POST',
    body: JSON.stringify(cmd),
  })
}

export async function submitReservation(cmd: CreateReservationCommand): Promise<ReservationDetail> {
  return api<ReservationDetail>('/reservations/submit', {
    method: 'POST',
    body: JSON.stringify(cmd),
  })
}

export async function withdrawReservation(id: number): Promise<ReservationDetail> {
  return api<ReservationDetail>(`/reservations/${id}/withdraw`, {
    method: 'POST',
  })
}

export async function cancelReservation(id: number, reason?: string): Promise<ReservationDetail> {
  return api<ReservationDetail>(`/reservations/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason || '申请人主动取消' }),
  })
}

export async function listReview(params?: { status?: string; venueId?: number; page?: number; pageSize?: number }): Promise<ReservationPage> {
  const query = new URLSearchParams()
  if (params?.status) query.set('status', params.status)
  if (params?.venueId) query.set('venueId', String(params.venueId))
  query.set('page', String(params?.page ?? 1))
  query.set('pageSize', String(params?.pageSize ?? 20))
  return api<ReservationPage>('/reservations/review?' + query.toString())
}

export async function reviewReservation(id: number, cmd: ReviewReservationCommand): Promise<ReservationDetail> {
  return api<ReservationDetail>(`/reservations/${id}/review`, {
    method: 'POST',
    body: JSON.stringify(cmd),
  })
}

export interface VenueManagerItem {
  userId: number
  username: string
  fullName: string
  departmentName: string
  identityNumber: string
  eligible: boolean
}

export interface VenueManagementItem {
  id: number
  code: string
  name: string
  category: 'VENUE' | 'EQUIPMENT'
  placeId: string | null
  buildingName: string | null
  locationDetail: string
  capacity: number | null
  availableEquipment: string | null
  rules: string | null
  enabled: boolean
  managers: VenueManagerItem[]
  activeReviewerCount: number
  pendingReviewCount: number
  unattendedReviewCount: number
}

export interface VenueUpdateCommand {
  name?: string
  capacity?: number
  availableEquipment?: string
  rules?: string
  enabled?: boolean
}

export async function listVenuesForManagement(): Promise<VenueManagementItem[]> {
  return api<VenueManagementItem[]>('/reservations/venues/manage')
}

export async function getReviewerCandidates(): Promise<VenueManagerItem[]> {
  return api<VenueManagerItem[]>('/reservations/reviewers/candidates')
}

export async function updateVenue(id: number, cmd: VenueUpdateCommand): Promise<{ success: boolean; message: string }> {
  return api<{ success: boolean; message: string }>(`/reservations/venues/${id}`, {
    method: 'PUT',
    body: JSON.stringify(cmd),
  })
}

export async function addVenueManager(venueId: number, userId: number): Promise<{ success: boolean; message: string }> {
  return api<{ success: boolean; message: string }>(`/reservations/venues/${venueId}/managers`, {
    method: 'POST',
    body: JSON.stringify({ userId }),
  })
}

export async function removeVenueManager(venueId: number, userId: number): Promise<{ success: boolean; message: string }> {
  return api<{ success: boolean; message: string }>(`/reservations/venues/${venueId}/managers/${userId}`, {
    method: 'DELETE',
  })
}


export async function recordReservationUsage(id: number, version: number, action: 'START' | 'COMPLETE', comment: string): Promise<ReservationDetail> {
  return api<ReservationDetail>(`/reservations/${id}/usage`, { method: 'POST', body: JSON.stringify({ version, action, comment }) })
}
