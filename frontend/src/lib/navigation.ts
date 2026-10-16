export function safeLoginDestination(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\r\n]/.test(value)) return null
  const path = value.split(/[?#]/)[0] ?? ''
  if (/^\/assistant\/guides\/[a-z0-9][a-z0-9-]{0,79}$/.test(path)) return value
  return /^\/(?:portal(?:\/(?:learning(?:\/(?:timetable|grades|exams))?|affairs|leave|certificates|notices))?|profile|workbench|notices|admin\/(?:content|foundation|resources|counselors|repair-settings|housing)|logistics\/(?:repair-responsibilities|repairs\/(?:dispatch|tasks|statistics))|repairs\/mine|housing\/(?:mine|assignments|transfer)|teaching(?:\/timetable)?|academic|student-affairs\/(?:leave|certificates|housing))$/.test(path) ? value : null
}
export function safeAffairDestination(value: unknown): string | null {
  if (typeof value !== 'string') return null
  return /^\/(?:portal\/(?:leave|certificates)|student-affairs\/(?:leave|certificates|housing)|repairs\/mine|housing\/(?:transfer|assignments)|logistics\/repairs\/(?:dispatch|tasks))\?id=[1-9]\d*&tab=details$/.test(value) ? value : null
}
