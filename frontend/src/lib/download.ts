import { ApiError, resetCsrf } from './api.ts'

export function downloadFilename(disposition: string | null, fallback = '下载文件') {
  let name = ''
  const encoded = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) { try { name = decodeURIComponent(encoded.trim()) } catch { /* Use the plain filename below. */ } }
  if (!name) name = disposition?.match(/filename="([^"]*)"|filename=([^;]+)/i)?.slice(1).find(Boolean)?.trim() ?? fallback
  const leaf = name.split(/[/\\]/).at(-1) ?? fallback
  return Array.from(leaf, char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127 || '<>:"|?*'.includes(char) ? '_' : char).join('').trim() || fallback
}

/** Keep the object URL alive until the browser has taken ownership of the download. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = downloadFilename(null, filename)
  link.hidden = true
  document.body.append(link)
  try { link.click() }
  finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 60_000) }
}

export async function downloadFile(path: string, filename?: string, expectedType?: string) {
  if (!path.startsWith('/api/') || /[\\\r\n]/.test(path)) throw new Error('下载地址无效')
  let response: Response
  try { response = await fetch(path, { credentials: 'same-origin', redirect: 'error' }) }
  catch { throw new ApiError(0, 'NETWORK_ERROR', '下载失败，无法连接校园服务，请稍后重试') }
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    if (response.status === 401) window.dispatchEvent(new Event('campus:unauthorized'))
    if (response.status === 403) resetCsrf()
    const fallback = response.status === 401 ? '登录已失效，请重新登录后下载' : response.status === 403 ? '当前账号无权下载此文件' : '文件下载失败，请刷新后重试'
    throw new ApiError(response.status, error?.code ?? 'DOWNLOAD_FAILED', error?.message ?? fallback)
  }
  const type = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() ?? ''
  if (!type || type === 'text/html' || type === 'application/json' || type.endsWith('+json') || (expectedType && type !== expectedType)) {
    throw new ApiError(200, 'INVALID_FILE', '服务器未返回有效文件，请稍后重试')
  }
  const blob = await response.blob()
  if (!blob.size) throw new ApiError(200, 'EMPTY_FILE', '文件内容为空，请联系管理员检查')
  if (type === 'application/pdf' && !(await blob.slice(0, 5).text()).startsWith('%PDF-')) {
    throw new ApiError(200, 'INVALID_FILE', 'PDF 文件内容异常，请稍后重试')
  }
  saveBlob(blob, downloadFilename(response.headers.get('Content-Disposition'), filename))
}
