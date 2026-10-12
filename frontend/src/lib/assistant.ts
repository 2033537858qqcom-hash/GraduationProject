export interface AssistantContext { type: 'LEAVE_MINE' | 'LEAVE_REVIEW' | 'CERTIFICATE_MINE' | 'CERTIFICATE_REVIEW'; id: number }
export interface AssistantGuide { slug: string; title: string; body: string; version: number; updatedAt: string; targetPath: string }
export interface AssistantFact { key: string; title: string; description: string; targetPath: string | null }
export interface AssistantResult { name: string; title: string; status: string; message: string; total: number; items: AssistantFact[]; sources: AssistantGuide[]; targetPath: string | null }
export interface AssistantAnswer { runId: string; mode: 'LOCAL' | 'DEEPSEEK'; modelUsed: boolean; summary: string; results: AssistantResult[]; queriedAt: string; promptTokens: number | null; completionTokens: number | null }
export interface AssistantCapabilities { deepseekReady: boolean; model: string; tools: { name: string; title: string }[]; message: string }
export function assistantContext(path: string, id: unknown): AssistantContext | null {
  const types: Record<string, AssistantContext['type']> = { '/portal/leave': 'LEAVE_MINE', '/portal/certificates': 'CERTIFICATE_MINE', '/student-affairs/leave': 'LEAVE_REVIEW', '/student-affairs/certificates': 'CERTIFICATE_REVIEW' }
  if (!types[path] || typeof id !== 'string' || !/^[1-9]\d{0,14}$/.test(id) || !Number.isSafeInteger(Number(id))) return null
  return { type: types[path], id: Number(id) }
}
export function safeAssistantDestination(value: unknown): string | null {
  if (typeof value !== 'string') return null
  if (/^\/assistant\/guides\/[a-z0-9][a-z0-9-]{0,79}$/.test(value)) return value
  if (/^\/(?:portal\/(?:affairs|notices|learning(?:\/(?:timetable|grades|exams))?)|notices|teaching\/timetable|student-affairs\/(?:leave|certificates))$/.test(value)) return value
  if (/^\/(?:portal\/learning\/(?:timetable|grades|exams)|teaching\/timetable)\?term=[1-9]\d{0,14}(?:&week=\d{4}-\d{2}-\d{2})?$/.test(value)) return value
  if (/^\/(?:portal\/(?:leave|certificates)|student-affairs\/(?:leave|certificates))\?id=[1-9]\d*&tab=details$/.test(value)) return value
  return null
}
export function assistantSuggestions(path: string, tools: string[], context: AssistantContext | null): string[] {
  if (context && tools.includes('application_status')) return ['当前申请的处理进度', context.type.startsWith('LEAVE') ? '请假最长多少天' : '证明怎么领取']
  if (path.endsWith('/grades') && tools.includes('my_grades')) return ['我的已发布成绩', '成绩怎么查询']
  if (path.endsWith('/exams') && tools.includes('my_exams')) return ['我的考试安排', '考试怎么查询']
  if (path.startsWith('/student-affairs') && tools.includes('my_review_pending')) return ['岗位待办', path.includes('certificates') ? '证明怎么领取' : '请假怎么申请']
  if (path.includes('/learning') || path.startsWith('/teaching')) return tools.includes('my_timetable') ? ['本周课表', '下周课表'] : ['课表查询规则', '我有多少未读消息']
  if (path.startsWith('/portal') && tools.includes('my_affairs')) return ['我的办事进度', '哪些申请需要补交', '我有多少未读消息']
  if (tools.includes('my_review_pending')) return ['岗位待办', '证明办理规则', '我有多少未读消息']
  if (tools.includes('my_timetable')) return ['本周课表', '我有多少未读消息']
  return ['请假怎么申请', '证明怎么领取', '我有多少未读消息']
}
