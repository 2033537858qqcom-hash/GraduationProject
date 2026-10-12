import type { CourseMaterial } from '../types'

export interface TeachingDraft {
  material?: { source: CourseMaterial | null; title: string; body: string; attachmentName: string }
  notice?: { title: string; body: string; requestKey: string }
}

const prefix = 'campus:teaching-draft:v1:'
const lifetime = 24 * 60 * 60 * 1000
const memory = new Map<string, { draft: TeachingDraft; file: File | null; savedAt: number }>()
const key = (userId: number, classId: number) => `${prefix}${userId}:${classId}`
const text = (value: unknown, max: number): value is string => typeof value === 'string' && value.length <= max
function valid(value: unknown): value is TeachingDraft {
  if (!value || typeof value !== 'object') return false
  const draft = value as TeachingDraft
  const m = draft.material, n = draft.notice
  if (m && (!text(m.title, 160) || !text(m.body, 20000) || !text(m.attachmentName, 255)
    || (m.source !== null && (!m.source || !Number.isSafeInteger(m.source.id) || m.source.id <= 0
      || !Number.isSafeInteger(m.source.version) || m.source.version < 0
      || !text(m.source.title, 160) || !text(m.source.body, 20000))))) return false
  if (n && (!text(n.title, 160) || !text(n.body, 10000) || !text(n.requestKey, 64))) return false
  return Boolean(m || n)
}

export function writeTeachingDraft(userId: number, classId: number, draft: TeachingDraft, file: File | null): boolean {
  const id = key(userId, classId)
  if (!draft.material && !draft.notice) { removeTeachingDraft(userId, classId); return true }
  const savedAt = Date.now()
  // Clone to keep reactive form objects from changing a different class's draft.
  const snapshot = JSON.parse(JSON.stringify(draft)) as TeachingDraft
  memory.set(id, { draft: snapshot, file, savedAt })
  try { sessionStorage.setItem(id, JSON.stringify({ savedAt, draft: snapshot })); return true }
  catch { return false }
}

export function readTeachingDraft(userId: number, classId: number) {
  const id = key(userId, classId)
  let entry = memory.get(id)
  let raw: string | null = null
  try { raw = sessionStorage.getItem(id) } catch { /* Browser storage may be unavailable. */ }
  try {
    if (raw) {
      const stored = JSON.parse(raw)
      if (!Number.isFinite(stored.savedAt) || stored.savedAt > Date.now() || Date.now() - stored.savedAt > lifetime || !valid(stored.draft)) {
        removeTeachingDraft(userId, classId); return null
      }
      if (!entry || entry.savedAt < stored.savedAt) entry = { draft: stored.draft as TeachingDraft, file: entry?.file ?? null, savedAt: stored.savedAt }
    }
  } catch { removeTeachingDraft(userId, classId); return null }
  if (!entry || Date.now() - entry.savedAt > lifetime) { removeTeachingDraft(userId, classId); return null }
  return { draft: structuredClone(entry.draft), file: entry.file }
}

export function removeTeachingDraft(userId: number, classId: number) {
  const id = key(userId, classId)
  memory.delete(id)
  try { sessionStorage.removeItem(id) } catch { /* Storage may be disabled. */ }
}

export function clearTeachingDrafts(userId?: number) {
  const userPrefix = userId === undefined ? prefix : `${prefix}${userId}:`
  for (const id of memory.keys()) if (id.startsWith(userPrefix)) memory.delete(id)
  try {
    for (let n = sessionStorage.length - 1; n >= 0; n--) {
      const id = sessionStorage.key(n)
      if (id?.startsWith(userPrefix)) sessionStorage.removeItem(id)
    }
  } catch { /* In-memory data has already been cleared. */ }
}
