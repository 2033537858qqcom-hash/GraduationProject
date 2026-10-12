import type { AssistantGuide } from './assistant'
import type { CertificateIdentity } from '../certificates'

export interface CertificatePreviewFields { purpose: string }
export interface AssistantCertificatePreview {
  runId: string; mode: 'LOCAL' | 'DEEPSEEK'; modelUsed: boolean; fields: CertificatePreviewFields
  identity: CertificateIdentity | null; missing: string[]; issues: string[]; ready: boolean; summary: string
  source: AssistantGuide | null; canSubmit: boolean; eligibilityMessage: string; preparedAt: string
}
export function certificatePurposeFingerprint(fields: CertificatePreviewFields) { return fields.purpose.trim() }
export function certificateIdentityFingerprint(identity: CertificateIdentity | null) {
  return identity ? JSON.stringify([identity.departmentId, identity.classGroupId, identity.studentName, identity.identityNumber,
    identity.departmentName, identity.majorName, identity.className, identity.campusName]) : ''
}
export function canSaveCertificatePreview(preview: AssistantCertificatePreview | null, fields: CertificatePreviewFields, confirmed: boolean, busy: boolean) {
  return !!preview?.ready && !!preview.identity && confirmed && !busy && certificatePurposeFingerprint(preview.fields) === certificatePurposeFingerprint(fields)
}
export function certificateDraftBody(fields: CertificatePreviewFields, requestKey: string, identity: CertificateIdentity) {
  return { requestKey, purpose: fields.purpose.trim(), deliveryMethod: 'DIGITAL', identityConfirmed: true, version: 0, expectedIdentity: { ...identity } }
}
