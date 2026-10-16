import type { AssistantGuide } from './assistant'
export interface HousingPreviewFields { reason: string; preferredArea: string; materialNote: string }
export interface AssistantHousingPreview {
  runId: string; mode: 'LOCAL' | 'DEEPSEEK'; modelUsed: boolean; fields: HousingPreviewFields
  missing: string[]; issues: string[]; ready: boolean; summary: string; source: AssistantGuide | null
  eligibilityMessage: string; currentPlace: string | null; stayToken: string | null; preparedAt: string
}
export function emptyHousingPreview(): HousingPreviewFields { return { reason: '', preferredArea: '', materialNote: '' } }
export function housingPreviewFingerprint(fields: HousingPreviewFields) { return JSON.stringify([fields.reason.trim(), fields.preferredArea.trim(), fields.materialNote.trim()]) }
export function sameHousingStay(a: AssistantHousingPreview, b: AssistantHousingPreview) { return !!a.stayToken && a.stayToken === b.stayToken && a.currentPlace === b.currentPlace }
