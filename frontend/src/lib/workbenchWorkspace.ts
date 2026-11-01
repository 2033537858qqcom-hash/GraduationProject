import type { WorkspaceInfo } from '../workbench'

/**
 * 工作空间默认落点优先级：业务岗位 → 财务协同 → 个人空间。
 * 数组顺序即业务优先级；后端返回顺序调整、或新增未知空间时，默认落点不变。
 */
export const WORKSPACE_PRIORITY = [
  'TEACHING', 'COUNSELOR', 'AFFAIRS', 'HOUSING', 'LOGISTICS', 'ACADEMIC', 'CONTENT', 'ADMIN',
  'FINANCE', 'PAYMENT_SOURCE', 'PERSONAL',
] as const

/** 当前账号可进入的工作空间中，按业务优先级应默认展示的空间编码；未知编码按后端顺序兜底。 */
export function defaultWorkspaceCode(workspaces: readonly Pick<WorkspaceInfo, 'code'>[]): string {
  for (const code of WORKSPACE_PRIORITY) {
    if (workspaces.some(workspace => workspace.code === code)) return code
  }
  return workspaces[0]?.code ?? ''
}

/** 每个工作空间只展示自身岗位入口；财务与业务空间互不遮蔽。 */
const WORKSPACE_ENTRY_PREFIXES: Record<string, readonly string[]> = {
  TEACHING: ['/teaching'],
  COUNSELOR: ['/student-affairs/leave', '/admin/counselors'],
  AFFAIRS: ['/student-affairs'],
  HOUSING: ['/housing/assignments', '/admin/housing'],
  LOGISTICS: ['/logistics'],
  ACADEMIC: ['/academic'],
  CONTENT: ['/admin/content'],
  FINANCE: ['/finance/payments'],
  PAYMENT_SOURCE: ['/payment-bases'],
  ADMIN: ['/admin'],
  PERSONAL: ['/profile', '/repairs/mine', '/portal/reservations'],
}

/**
 * 入口是否属于当前工作空间。showAll 为“查看全部已授权服务”；
 * 未知空间编码（后端新增的岗位）保持全部可见，避免入口被误隐藏。
 */
export function workspaceEntryVisible(code: string | undefined, pathPrefix: string, showAll = false): boolean {
  if (showAll || !code) return true
  const prefixes = WORKSPACE_ENTRY_PREFIXES[code]
  if (!prefixes) return true
  return prefixes.some(prefix => pathPrefix.startsWith(prefix))
}
