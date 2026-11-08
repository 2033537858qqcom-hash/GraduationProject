export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export interface ApiOptions extends RequestInit {
  /** Milliseconds before the request is aborted. Defaults to 15s; pass 0 to disable. */
  timeout?: number
}

const DEFAULT_TIMEOUT = 15_000

let csrf: { token: string; headerName: string } | null = null
let csrfRequest: Promise<void> | null = null
export function resetCsrf() {
  csrf = null
}

async function ensureCsrf() {
  if (csrf) return
  if (!csrfRequest) {
    csrfRequest = (async () => {
      const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
      if (!response.ok) throw new ApiError(response.status, 'CSRF_UNAVAILABLE', '会话校验暂时不可用')
      csrf = await response.json()
    })().finally(() => {
      csrfRequest = null
    })
  }
  await csrfRequest
}

function withTimeout(signal: AbortSignal | null | undefined, timeout: number) {
  if (!timeout) return { signal: signal ?? undefined, timedOut: () => false }
  const timer = AbortSignal.timeout(timeout)
  return {
    signal: signal ? AbortSignal.any([signal, timer]) : timer,
    timedOut: () => timer.aborted,
  }
}

async function readJson(response: Response) {
  const text = await response.text()
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法识别的数据')
  }
}

function isCsrfFailure(code: string | undefined) {
  return !code || code.includes('CSRF')
}

export async function api<T>(path: string, options: ApiOptions = {}, retried = false): Promise<T> {
  const { timeout = DEFAULT_TIMEOUT, ...init } = options
  const method = (init.method ?? 'GET').toUpperCase()
  const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(method)
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (unsafe) {
    await ensureCsrf()
    if (csrf) headers.set(csrf.headerName, csrf.token)
  }
  const { signal, timedOut } = withTimeout(init.signal, timeout)
  let response: Response
  try {
    response = await fetch('/api' + path, { ...init, headers, signal, credentials: 'same-origin' })
  } catch (error) {
    if (timedOut()) throw new ApiError(0, 'TIMEOUT', '请求超时，请稍后重试')
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接校园服务，请检查网络或后端是否启动')
  }
  if (!response.ok) {
    const error = await readJson(response).catch(() => null)
    if (response.status === 403) {
      resetCsrf()
      // An expired CSRF token is recoverable: refresh it and replay the request once.
      if (unsafe && !retried && isCsrfFailure(error?.code)) return api<T>(path, options, true)
    }
    if (response.status === 401 && !['/me', '/auth/login'].includes(path)) {
      window.dispatchEvent(new Event('campus:unauthorized'))
    }
    throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED', error?.message ?? '操作失败，请稍后重试')
  }
  if (response.status === 204) return undefined as T
  return (await readJson(response)) as T
}

export function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '服务暂时不可用，请稍后重试'
}
