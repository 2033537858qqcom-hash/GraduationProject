import test from 'node:test'
import assert from 'node:assert/strict'
import { api, ApiError, resetCsrf } from '../src/lib/api.ts'

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
