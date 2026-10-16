export interface AffairTimelineEvent { key: string; action: string; title: string; actor: string; comment: string; status: string | null; version: number | null; createdAt: string }
export interface AffairTimeline { kind: string; requestId: number; status: string; version: number; updatedAt: string; nextStep: string; items: AffairTimelineEvent[]; total: number; page: number; pageSize: number; historyNote: string }
export function affairTimelinePath(kind: string, id: number, page = 1, version?: number) {
  if (!['LEAVE', 'CERTIFICATE', 'REPAIR', 'HOUSING', 'RESERVATION'].includes(kind) || !Number.isSafeInteger(id) || id < 1 || !Number.isSafeInteger(page) || page < 1 || page > 1000000 || (version !== undefined && (!Number.isSafeInteger(version) || version < 0))) throw new Error('办理记录链接无效，请刷新列表后重试。')
  const query = new URLSearchParams({ page: String(page), pageSize: '10' })
  if (version !== undefined) query.set('expectedVersion', String(version))
  return `/affairs/mine/${kind}/${id}/timeline?${query}`
}
