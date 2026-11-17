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
  /** Optional short-TTL (ms) response cache & in-flight deduplication for idempotent GET reads. */
  cacheTtl?: number
}

const DEFAULT_TIMEOUT = 15_000
/** Backend agent single-run budget is 60s, so AI requests use a 75s client budget. */
export const AI_REQUEST_TIMEOUT = 75_000
const MAX_CACHE_ENTRIES = 80
const getCache = new Map<string, { expiresAt: number; value: unknown }>()
const inFlightGets = new Map<string, Promise<unknown>>()

export function invalidateApiCache(prefix?: string) {
  if (!prefix) {
    getCache.clear()
    return
  }
  for (const key of getCache.keys()) {
    if (key.startsWith(prefix)) getCache.delete(key)
  }
}

let csrf: { token: string; headerName: string } | null = null
let csrfRequest: Promise<void> | null = null
export function resetCsrf() {
  csrf = null
  invalidateApiCache()
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
  const { timeout = DEFAULT_TIMEOUT, cacheTtl = 0, ...init } = options
  const method = (init.method ?? 'GET').toUpperCase()
  const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(method)

  if (unsafe) {
    invalidateApiCache()
  } else if (method === 'GET' && cacheTtl > 0) {
    const cached = getCache.get(path)
    if (cached) {
      if (cached.expiresAt > Date.now()) return cached.value as T
      getCache.delete(path)
    }
    if (!init.signal) {
      const existing = inFlightGets.get(path)
      if (existing) return (await existing) as T
    }
  }

  const execute = async (): Promise<T> => {
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
        invalidateApiCache()
        window.dispatchEvent(new Event('campus:unauthorized'))
      }
      throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED', error?.message ?? '操作失败，请稍后重试')
    }
    if (response.status === 204) return undefined as T
    const data = (await readJson(response)) as T
    if (method === 'GET' && cacheTtl > 0) {
      if (getCache.size >= MAX_CACHE_ENTRIES) {
        const oldest = getCache.keys().next().value
        if (oldest !== undefined) getCache.delete(oldest)
      }
      getCache.set(path, { expiresAt: Date.now() + cacheTtl, value: data })
    }
    return data
  }

  if (method === 'GET' && cacheTtl > 0 && !init.signal) {
    const shared = execute().finally(() => {
      inFlightGets.delete(path)
    })
    inFlightGets.set(path, shared)
    return (await shared) as T
  }
  return execute()
}

export interface StreamPostOptions extends ApiOptions {
  onStep?: (message: string) => void
  onDelta?: (text: string) => void
}

function parseSseFrame(raw: string): { event: string; data: string } | null {
  let event = 'message'
  const lines: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) lines.push(line.slice(5).trimStart())
  }
  return lines.length ? { event, data: lines.join('\n') } : null
}

export async function streamPostJson<T>(
  path: string,
  body: unknown,
  options: StreamPostOptions = {},
  retried = false,
): Promise<T> {
  const { timeout = AI_REQUEST_TIMEOUT, onStep, onDelta, ...init } = options
  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')
  headers.set('Accept', 'text/event-stream, application/json')
  await ensureCsrf()
  if (csrf) headers.set(csrf.headerName, csrf.token)

  const { signal, timedOut } = withTimeout(init.signal, timeout)
  let response: Response
  try {
    response = await fetch('/api' + path, {
      ...init,
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal,
      credentials: 'same-origin',
    })
  } catch (error) {
    if (timedOut()) throw new ApiError(0, 'TIMEOUT', '请求超时，请稍后重试')
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接校园服务，请检查网络或后端是否启动')
  }

  if (!response.ok) {
    const error = await readJson(response).catch(() => null)
    if (response.status === 403) {
      resetCsrf()
      if (!retried && isCsrfFailure(error?.code)) return streamPostJson<T>(path, body, options, true)
    }
    if (response.status === 401 && !['/me', '/auth/login'].includes(path)) {
      window.dispatchEvent(new Event('campus:unauthorized'))
    }
    throw new ApiError(response.status, error?.code ?? 'REQUEST_FAILED', error?.message ?? '操作失败，请稍后重试')
  }

  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream') || !response.body) {
    return (await readJson(response)) as T
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let resultPayload: T | undefined

  const processFrame = (frameText: string) => {
    const parsed = parseSseFrame(frameText)
    if (!parsed) return
    let payload: unknown
    try {
      payload = JSON.parse(parsed.data)
    } catch {
      return
    }
    if (parsed.event === 'step') {
      const msg = (payload as { message?: unknown })?.message
      if (typeof msg === 'string' && msg) onStep?.(msg)
    } else if (parsed.event === 'delta') {
      const text = (payload as { text?: unknown })?.text
      if (typeof text === 'string' && text) onDelta?.(text)
    } else if (parsed.event === 'result') {
      resultPayload = payload as T
    } else if (parsed.event === 'error') {
      const err = payload as { status?: number; code?: string; message?: string }
      throw new ApiError(err.status ?? 500, err.code ?? 'QUERY_FAILED', err.message ?? '查询失败，请稍后重试')
    }
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sepIndex: number
      while ((sepIndex = buffer.search(/\r?\n\r?\n/)) !== -1) {
        const match = buffer.match(/\r?\n\r?\n/)!
        const frame = buffer.slice(0, sepIndex)
        buffer = buffer.slice(sepIndex + match[0].length)
        processFrame(frame)
      }
    }
    const tail = buffer + decoder.decode()
    if (tail.trim()) processFrame(tail)
  } catch (error) {
    if (timedOut()) throw new ApiError(0, 'TIMEOUT', '请求超时，请稍后重试')
    throw error
  } finally {
    reader.releaseLock()
  }

  if (resultPayload === undefined) {
    throw new ApiError(0, 'STREAM_INCOMPLETE', '查询流意外中断，请重试')
  }
  return resultPayload
}

export function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '服务暂时不可用，请稍后重试'
}
