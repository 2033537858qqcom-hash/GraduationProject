import { api } from './lib/api.ts'
import type { Profile } from './types.ts'

export type TaskDomain = 'ALL' | 'LEAVE' | 'CERTIFICATE' | 'RESERVATION' | 'HOUSING' | 'REPAIR' | 'CONTENT'
export interface AggregatedTask {
  id: string
  domain: string
  categoryName: string
  title: string
  status: string
  statusText: string
  applicantName: string
  scopeDetail: string
  targetPath: string
  createdAt: string
  urgency: string
}
export interface TaskSourceError { domain: string; label: string; message: string }
export interface WorkspaceInfo { code: string; name: string; roleTitle: string; scopeNote: string; entryPaths: string[] }
export interface WorkbenchOverview {
  profile: Profile
  workspaces: WorkspaceInfo[]
  tasks: AggregatedTask[]
  totalPending: number
  taskCounts: Record<string, number>
  complete: boolean
  sourceErrors: TaskSourceError[]
}
export interface TaskPage {
  totalPending: number
  domainCounts: Record<string, number>
  tasks: AggregatedTask[]
  total: number
  page: number
  pageSize: number
  complete: boolean
  sourceErrors: TaskSourceError[]
}
export function getTaskPage(domain: TaskDomain, page: number, pageSize: number): Promise<TaskPage> {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (domain !== 'ALL') query.set('domain', domain)
  return api<TaskPage>('/workbench/tasks?' + query.toString())
}
