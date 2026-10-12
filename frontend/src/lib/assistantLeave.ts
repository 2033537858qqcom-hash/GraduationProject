import type { AssistantGuide } from './assistant'

export interface LeavePreviewFields {
  reasonType: '' | 'PERSONAL' | 'SICK' | 'OTHER'
  reason: string; contact: string; startDate: string; startTime: string; endDate: string; endTime: string
}
export interface AssistantLeavePreview {
  runId: string; mode: 'LOCAL' | 'DEEPSEEK'; modelUsed: boolean; fields: LeavePreviewFields
  missing: string[]; issues: string[]; ready: boolean; summary: string; duration: string | null
  source: AssistantGuide | null; eligibilityMessage: string; preparedAt: string
}
export function emptyLeavePreview(): LeavePreviewFields {
  return { reasonType: '', reason: '', contact: '', startDate: '', startTime: '', endDate: '', endTime: '' }
}
export function leavePreviewFingerprint(fields: LeavePreviewFields) {
  return JSON.stringify([fields.reasonType, fields.reason.trim(), fields.contact.trim(), fields.startDate, fields.startTime, fields.endDate, fields.endTime])
}
export function canSaveLeavePreview(preview: AssistantLeavePreview | null, fields: LeavePreviewFields, confirmed: boolean, busy: boolean) {
  return !!preview?.ready && confirmed && !busy && leavePreviewFingerprint(preview.fields) === leavePreviewFingerprint(fields)
}
export function leaveDraftBody(fields: LeavePreviewFields, requestKey: string) {
  return { requestKey, reasonType: fields.reasonType, reason: fields.reason.trim(), contact: fields.contact.trim(),
    startsAt: `${fields.startDate}T${fields.startTime}:00`, endsAt: `${fields.endDate}T${fields.endTime}:00`, version: 0 }
}
