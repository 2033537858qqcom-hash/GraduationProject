import test from 'node:test'
import assert from 'node:assert/strict'
import { canSaveCertificatePreview, certificateDraftBody, certificateIdentityFingerprint, certificatePurposeFingerprint } from '../src/lib/assistantCertificate.ts'

const identity = { departmentId: 1, classGroupId: 1, studentName: '演示学生', identityNumber: '20260001', departmentName: '演示学院', majorName: '演示专业', className: '演示班级', campusName: '东校区' }
const fields = { purpose: '供实习单位核验在校身份' }
const preview = { ready: true, identity, fields }
test('saving requires a valid preview, local identity, personal confirmation and idle state', () => {
  assert.equal(canSaveCertificatePreview(null, fields, true, false), false)
  assert.equal(canSaveCertificatePreview({ ...preview, identity: null }, fields, true, false), false)
  assert.equal(canSaveCertificatePreview({ ...preview, ready: false }, fields, true, false), false)
  assert.equal(canSaveCertificatePreview(preview, fields, false, false), false)
  assert.equal(canSaveCertificatePreview(preview, fields, true, true), false)
  assert.equal(canSaveCertificatePreview(preview, fields, true, false), true)
})
test('editing purpose invalidates the reviewed preview', () => {
  assert.equal(canSaveCertificatePreview(preview, { purpose: '其他用途' }, true, false), false)
})
test('every registered identity change invalidates the reviewed snapshot', () => {
  for (const field of Object.keys(identity)) assert.notEqual(certificateIdentityFingerprint(identity), certificateIdentityFingerprint({ ...identity, [field]: typeof identity[field] === 'number' ? identity[field] + 1 : identity[field] + 'x' }), field)
  assert.equal(certificateIdentityFingerprint(null), '')
})
test('identity fingerprint is stable across object key ordering', () => {
  assert.equal(certificateIdentityFingerprint(identity), certificateIdentityFingerprint(Object.fromEntries(Object.entries(identity).reverse())))
})
test('ordinary save carries the reviewed identity, digital delivery and no submission or approval', () => {
  const body = certificateDraftBody(fields, 'unique-key', identity)
  assert.deepEqual(body, { requestKey: 'unique-key', purpose: fields.purpose, deliveryMethod: 'DIGITAL', identityConfirmed: true, version: 0, expectedIdentity: identity })
  assert.notEqual(body.expectedIdentity, identity)
  for (const field of ['studentId', 'status', 'action', 'documentNumber', 'fileId']) assert.equal(field in body, false)
})
test('purpose review and save use consistent trimming', () => {
  const spaced = { purpose: ` ${fields.purpose} ` }
  assert.equal(certificatePurposeFingerprint(spaced), certificatePurposeFingerprint(fields))
  assert.deepEqual(certificateDraftBody(spaced, 'key', identity), certificateDraftBody(fields, 'key', identity))
})
