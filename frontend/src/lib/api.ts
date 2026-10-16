export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

let csrf: { token: string; headerName: string } | null = null
let csrfRequest: Promise<void> | null = null
export function resetCsrf() { csrf = null }

async function ensureCsrf() {
  if (csrf) return
  if (!csrfRequest) {
    csrfRequest = (async () => {
      const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
      if (!response.ok) throw new ApiError(response.status, 'CSRF_UNAVAILABLE', '会话校验暂时不可用')
      csrf = await response.json()
    })().finally(() => { csrfRequest = null })
  }
  await csrfRequest
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) {
    await ensureCsrf()
    if (csrf) headers.set(csrf.headerName, csrf.token)
  }
  let response: Response
  try {
    response = await fetch('/api' + path, { ...options, headers, credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接校园服务，请检查网络或后端是否启动')
  }
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    if (response.status === 403) resetCsrf()
    if (response.status === 401 && !['/me', '/auth/login'].includes(path)) {
      window.dispatchEvent(new Event('campus:unauthorized'))
    }
    throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED', error?.message ?? '操作失败，请稍后重试')
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '服务暂时不可用，请稍后重试'
}
