import type { AssistantGuide } from './assistant'

export interface RepairPreviewFields {
  category: '' | 'WATER' | 'ELECTRICITY' | 'NETWORK' | 'FURNITURE' | 'DOOR_WINDOW' | 'EQUIPMENT' | 'OTHER'
  title: string
  description: string
  locationId: number | null
  locationDetail: string
  contact: string
  contactWindow: string
}
export interface RepairPlace { id: number; campusId: number; campusName: string; name: string }
export interface AssistantRepairPreview {
  runId: string
  mode: 'LOCAL' | 'DEEPSEEK'
  modelUsed: boolean
  fields: RepairPreviewFields
  missing: string[]
  issues: string[]
  ready: boolean
  summary: string
  source: AssistantGuide | null
  eligibilityMessage: string
  locationName: string | null
  preparedAt: string
}
export function emptyRepairPreview(): RepairPreviewFields {
  return { category: '', title: '', description: '', locationId: null, locationDetail: '', contact: '', contactWindow: '' }
}
export function repairPreviewFingerprint(fields: RepairPreviewFields) {
  return JSON.stringify([fields.category, fields.title.trim(), fields.description.trim(), fields.locationId, fields.locationDetail.trim(), fields.contact.trim(), fields.contactWindow.trim()])
}
