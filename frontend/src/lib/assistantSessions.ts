import type { AssistantAnswer, AssistantContext } from './assistant'
import type { AssistantLeavePreview } from './assistantLeave'
import type { AssistantCertificatePreview } from './assistantCertificate'

export interface AssistantSession { id: number; title: string; createdAt: string; updatedAt: string }
export interface AssistantTurn { id: number; question: string; mode: 'LOCAL' | 'DEEPSEEK'; context: AssistantContext | null; status: string; runId: string | null; createdAt: string }
export interface AssistantTaskSummary { id: number; kind: 'LEAVE' | 'CERTIFICATE'; stage: 'PREVIEW' | 'LINKED'; version: number; updatedAt: string }
export interface AssistantSessionDetail { session: AssistantSession; turns: AssistantTurn[]; tasks: AssistantTaskSummary[]; hasOlder: boolean }
export interface AssistantBusiness { id: number; kind: 'LEAVE' | 'CERTIFICATE'; status: string; version: number; targetPath: string }
export interface AssistantTaskView extends AssistantTaskSummary { leavePreview: AssistantLeavePreview | null; certificatePreview: AssistantCertificatePreview | null; business: AssistantBusiness | null }
export interface AssistantQueryResult { turn: AssistantTurn; answer: AssistantAnswer | null; repeated: boolean }
export function latestAssistantTask(tasks: AssistantTaskSummary[], kind: AssistantTaskSummary['kind']) {
  return tasks.filter(task => task.kind === kind).sort((a, b) => b.id - a.id)[0]?.id ?? null
}
export function mergeAssistantTurns(old: AssistantTurn[], fresh: AssistantTurn[]) {
  return [...new Map([...old, ...fresh].map(turn => [turn.id, turn])).values()].sort((a, b) => a.id - b.id)
}
export function restoredTurnLabel(status: string) {
  return status === 'SUCCESS' ? '历史提问 · 结果需重新查询' : status === 'RUNNING' ? '上次查询仍在处理' : '上次查询未完成 · 可手动重试'
}
