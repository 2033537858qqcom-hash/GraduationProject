export interface HousingMaterial { id: number; name: string; type: string; size: number; createdAt: string }
export interface HousingSubmission { id: number; revision: number; requestVersion: number; reason: string; preferredArea: string; materialNote: string; createdAt: string; files: HousingMaterial[] }
export interface HousingMaterials { version: number; status: string; canEdit: boolean; current: HousingMaterial[]; submissions: HousingSubmission[]; total: number; page: number; pageSize: number }
export function housingMaterialIssue(file: { name: string; size: number }) {
  if (!file.size || file.size > 2 * 1024 * 1024) return '请选择 2 MB 以内且非空的材料。'
  if (!/\.(pdf|png|jpe?g)$/i.test(file.name)) return '请选择 PDF、JPEG 或 PNG 材料。'
  return ''
}
export function housingMaterialUrl(view: string, request: number, file: number, submission?: number) {
  if (!['mine', 'review'].includes(view) || ![request, file, ...(submission === undefined ? [] : [submission])].every(id => Number.isSafeInteger(id) && id > 0)) return null
  return `/api/housing/transfers/${view}/${request}/materials/${file}${submission === undefined ? '' : '?submission=' + submission}`
}
export function housingMaterialSize(size: number) { return size < 1024 * 1024 ? `${Math.max(1, Math.ceil(size / 1024))} KB` : `${(size / 1024 / 1024).toFixed(1)} MB` }
