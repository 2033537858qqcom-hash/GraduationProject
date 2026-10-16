export interface HousingImportRow { line: number; username: string; studentName: string; roomCode: string; bedCode: string; place: string; reason: string; errors: string[]; assignmentId: number | null }
export interface HousingImportBatch { id: number | null; token: string | null; status: 'INVALID' | 'PREVIEW' | 'EXPIRED' | 'COMPLETED'; buildingId: number; campusName: string; buildingName: string; sourceName: string; total: number; valid: number; invalid: number; ignored: number; canConfirm: boolean; expiresAt: string | null; completedAt: string | null; rows: HousingImportRow[] }
export interface HousingImportSummary { id: number; token: string; sourceName: string; total: number; completedAt: string }
export function housingImportFileIssue(file: { name: string; size: number }) {
  if (!/\.csv$/i.test(file.name)) return '请选择 CSV 文件；Excel 请另存为 CSV UTF-8。'
  if (file.size === 0 || file.size > 102400) return 'CSV 须大于 0 字节且不超过 100 KB。'
  return ''
}
export function decodeHousingImport(bytes: Uint8Array) {
  if (!bytes.byteLength || bytes.byteLength > 102400) throw new Error('CSV 须大于 0 字节且不超过 100 KB。')
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes) }
  catch { throw new Error('文件编码不是 UTF-8，请在 Excel 中另存为 CSV UTF-8。') }
}
export function housingImportExpiry(value: string | null) { return value ? Date.parse(value + '+08:00') : NaN }
export function housingImportCanConfirm(batch: HousingImportBatch | null, buildingId: number, unchanged: boolean, now = Date.now()) {
  return !!batch && batch.status === 'PREVIEW' && !!batch.token && batch.canConfirm && batch.invalid === 0 && batch.total > 0 && batch.buildingId === buildingId && unchanged && housingImportExpiry(batch.expiresAt) > now
}
