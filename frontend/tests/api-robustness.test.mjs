import test from 'node:test'
import assert from 'node:assert/strict'
import { api, ApiError, invalidateApiCache, resetCsrf, streamPostJson } from '../src/lib/api.ts'

test('api throws NETWORK_ERROR when fetch fails', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => {
    throw new Error('Connection refused')
  }
  try {
    await assert.rejects(
      async () => {
        await api('/test-network-fail')
      },
      err => {
        assert(err instanceof ApiError)
        assert.equal(err.code, 'NETWORK_ERROR')
        return true
      },
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('api handles 204 No Content gracefully', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () =>
    new Response(null, {
      status: 204,
      statusText: 'No Content',
    })
  try {
    const result = await api('/test-204')
    assert.equal(result, undefined)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('api handles invalid json with INVALID_RESPONSE code', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () =>
    new Response('<html><body>502 Bad Gateway</body></html>', {
      status: 200,
      headers: { 'Content-Type': 'text/html' },
    })
  try {
    await assert.rejects(
      async () => {
        await api('/test-bad-json')
      },
      err => {
        assert(err instanceof ApiError)
        assert.equal(err.code, 'INVALID_RESPONSE')
        return true
      },
    )
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('api retries once when CSRF token expires on unsafe method', async () => {
  const originalFetch = globalThis.fetch
  resetCsrf()
  let fetchCount = 0

  globalThis.fetch = async url => {
    fetchCount++
    if (String(url).includes('/api/auth/csrf')) {
      return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'mock-csrf-token' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    // First POST returns 403 CSRF_INVALID, second returns 200 OK
    if (fetchCount === 2) {
      return new Response(JSON.stringify({ code: 'CSRF_INVALID', message: 'Token expired' }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response(JSON.stringify({ success: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  try {
    const result = await api('/test-csrf-retry', { method: 'POST', body: '{}' })
    assert.deepEqual(result, { success: true })
    assert.equal(fetchCount >= 3, true, '应获取csrf、发起请求、失败重置并重新发起')
  } finally {
    globalThis.fetch = originalFetch
    resetCsrf()
  }
})

test('streamPostJson dispatches progress steps and resolves final result', async () => {
  const originalFetch = globalThis.fetch
  resetCsrf()
  const steps = []

  globalThis.fetch = async url => {
    if (String(url).includes('/api/auth/csrf')) {
      return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'mock-csrf' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    const encoder = new TextEncoder()
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('event:step\ndata:{"message":"正在分析问题"}\n\n'))
        controller.enqueue(encoder.encode('event:step\ndata:{"message":"正在查询：课程成绩"}\n\n'))
        controller.enqueue(encoder.encode('event:result\ndata:{"ok":true,"count":2}\n\n'))
        controller.close()
      },
    })
    return new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })
  }

  try {
    const result = await streamPostJson(
      '/assistant/sessions/1/query/stream',
      { query: '成绩' },
      { onStep: message => steps.push(message) },
    )
    assert.deepEqual(steps, ['正在分析问题', '正在查询：课程成绩'])
    assert.deepEqual(result, { ok: true, count: 2 })
  } finally {
    globalThis.fetch = originalFetch
    resetCsrf()
  }
})

test('streamPostJson surfaces SSE error events as ApiError', async () => {
  const originalFetch = globalThis.fetch
  resetCsrf()

  globalThis.fetch = async url => {
    if (String(url).includes('/api/auth/csrf')) {
      return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'mock-csrf' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    const encoder = new TextEncoder()
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(
          encoder.encode('event:error\ndata:{"status":400,"code":"MODEL_CONSENT_REQUIRED","message":"请确认同意"}\n\n'),
        )
        controller.close()
      },
    })
    return new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })
  }

  try {
    await assert.rejects(
      () => streamPostJson('/assistant/sessions/1/query/stream', {}),
      err => {
        assert(err instanceof ApiError)
        assert.equal(err.code, 'MODEL_CONSENT_REQUIRED')
        assert.equal(err.message, '请确认同意')
        return true
      },
    )
  } finally {
    globalThis.fetch = originalFetch
    resetCsrf()
  }
})

test('api cacheTtl deduplicates concurrent GETs and invalidates on unsafe mutations', async () => {
  const originalFetch = globalThis.fetch
  invalidateApiCache()
  resetCsrf()
  let getCalls = 0

  globalThis.fetch = async url => {
    const target = String(url)
    if (target.includes('/api/auth/csrf')) {
      return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'mock-csrf' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (target.includes('/api/public/reference')) {
      getCalls++
      return new Response(JSON.stringify({ version: getCalls }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response(JSON.stringify({ updated: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  try {
    const [first, second] = await Promise.all([
      api('/public/reference', { cacheTtl: 10_000 }),
      api('/public/reference', { cacheTtl: 10_000 }),
    ])
    assert.deepEqual(first, { version: 1 })
    assert.deepEqual(second, { version: 1 })
    assert.equal(getCalls, 1)

    const cached = await api('/public/reference', { cacheTtl: 10_000 })
    assert.deepEqual(cached, { version: 1 })
    assert.equal(getCalls, 1)

    await api('/admin/reference', { method: 'POST', body: '{}' })
    const refreshed = await api('/public/reference', { cacheTtl: 10_000 })
    assert.deepEqual(refreshed, { version: 2 })
    assert.equal(getCalls, 2)
  } finally {
    globalThis.fetch = originalFetch
    invalidateApiCache()
    resetCsrf()
  }
})
