/** Shared date, time, and currency formatters used across portal views. */

export function formatDateTime(value: string | null | undefined, fallback = ''): string {
  if (!value) return fallback
  return value.slice(0, 16).replace('T', ' ')
}

export function formatDateTimeSeconds(value: string | null | undefined, fallback = ''): string {
  if (!value) return fallback
  return value.slice(0, 19).replace('T', ' ')
}

export function formatShortDateTime(value: string | null | undefined, fallback = ''): string {
  if (!value) return fallback
  return value.slice(5, 16).replace('T', ' ')
}

export function formatDate(value: string | null | undefined, separator = '-', fallback = ''): string {
  if (!value) return fallback
  const date = value.slice(0, 10)
  return separator === '-' ? date : date.replaceAll('-', separator)
}

export function shanghaiToday(now = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(now)
}

export function formatMoney(cents: number): string {
  return '¥' + (cents / 100).toFixed(2)
}
