import test from 'node:test'
import assert from 'node:assert/strict'
import { downloadFile, downloadFilename, saveBlob } from '../src/lib/download.ts'

function browser(t) {
  const state = { clicks: 0, removed: 0, appended: 0, revoked: 0, timer: null, filename: '' }
  const link = { hidden: false, href: '', download: '', click() { state.clicks++; state.filename = this.download }, remove() { state.removed++ } }
  t.mock.method(globalThis, 'fetch')
  t.mock.method(URL, 'createObjectURL', () => 'blob:test')
  t.mock.method(URL, 'revokeObjectURL', () => { state.revoked++ })
  t.mock.method(globalThis, 'setTimeout', (fn, ms) => { state.timer = { fn, ms }; return 1 })
  const oldDocument = globalThis.document, oldWindow = globalThis.window
  globalThis.document = { createElement: () => link, body: { append: () => { state.appended++ } } }
  globalThis.window = new EventTarget()
  t.after(() => { globalThis.document = oldDocument; globalThis.window = oldWindow })
  return state
}

test('Chinese RFC 5987 filenames and invalid encodings have usable fallbacks', () => {
  assert.equal(downloadFilename("attachment; filename=proof.pdf; filename*=UTF-8''%E8%AF%81%E6%98%8E.pdf"), '证明.pdf')
  assert.equal(downloadFilename('attachment; filename="../../证明.pdf"'), '证明.pdf')
  assert.equal(downloadFilename("attachment; filename=report.pdf; filename*=UTF-8''%GG"), 'report.pdf')
})

test('object URLs remain alive after the attached link initiates download', t => {
  const state = browser(t)
  saveBlob(new Blob(['\uFEFF学号,成绩']), '成绩.csv')
  assert.equal(state.appended, 1); assert.equal(state.clicks, 1); assert.equal(state.removed, 1)
  assert.equal(state.filename, '成绩.csv'); assert.equal(state.revoked, 0)
  assert.ok(state.timer.ms >= 1000)
  state.timer.fn(); assert.equal(state.revoked, 1)
})

test('a valid PDF is downloaded with the server filename', async t => {
  const state = browser(t)
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/example/pdf'); assert.equal(options.credentials, 'same-origin')
    return new Response('%PDF-1.7\nexample', { headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': "attachment; filename*=UTF-8''%E8%AF%BE%E8%A1%A8.pdf" } })
  })
  await downloadFile('/api/example/pdf', 'fallback.pdf', 'application/pdf')
  assert.equal(state.filename, '课表.pdf'); assert.equal(state.clicks, 1)
})

test('401 dispatches session expiry and never saves an error as a file', async t => {
  const state = browser(t); let expired = false
  globalThis.window.addEventListener('campus:unauthorized', () => { expired = true })
  t.mock.method(globalThis, 'fetch', async () => new Response('{"message":"请先登录"}', { status: 401 }))
  await assert.rejects(downloadFile('/api/private/file'), /请先登录/)
  assert.equal(expired, true); assert.equal(state.clicks, 0)
})

test('403, 404 and network errors preserve a useful error message', async t => {
  const state = browser(t)
  for (const status of [403, 404]) {
    t.mock.method(globalThis, 'fetch', async () => new Response('{"message":"无权或文件已失效"}', { status }))
    await assert.rejects(downloadFile('/api/private/file'), /无权或文件已失效/)
  }
  t.mock.method(globalThis, 'fetch', async () => { throw new TypeError('offline') })
  await assert.rejects(downloadFile('/api/private/file'), /无法连接/)
  assert.equal(state.clicks, 0)
})

test('HTML, JSON, empty and malformed PDF responses are rejected', async t => {
  const state = browser(t)
  for (const [type, body] of [['text/html', '<html>login</html>'], ['application/problem+json', '{}'], ['application/pdf', 'broken'], ['application/pdf', '']]) {
    t.mock.method(globalThis, 'fetch', async () => new Response(body, { headers: { 'Content-Type': type } }))
    await assert.rejects(downloadFile('/api/private/file'))
  }
  t.mock.method(globalThis, 'fetch', async () => new Response('wrong', { headers: { 'Content-Type': 'text/plain' } }))
  await assert.rejects(downloadFile('/api/private/file', 'template.csv', 'text/csv'))
  assert.equal(state.clicks, 0)
})

test('downloads never fetch an external or malformed address', async t => {
  const state = browser(t)
  for (const path of ['https://example.com/file', '//example.com/file', '/api/\\example.com/file']) await assert.rejects(downloadFile(path), /地址无效/)
  assert.equal(fetch.mock.callCount(), 0); assert.equal(state.clicks, 0)
})
