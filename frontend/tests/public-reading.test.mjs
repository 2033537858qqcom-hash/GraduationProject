import test from 'node:test'
import assert from 'node:assert/strict'
import { contentOrigin, publicContentLink, formatPublicDate, publicSource, contentParagraphs } from '../src/lib/publicReading.ts'

test('an article can return to each known originating chapter, including the opening', () => {
  for (const id of ['home', 'about', 'departments', 'education', 'research', 'admissions', 'exchange', 'life', 'disclosure']) {
    const result = contentOrigin('some-published-content', id)
    assert.equal(result.id, id)
    assert.deepEqual(result.target, { path: '/', query: id === 'home' ? {} : { chapter: id } })
  }
})
test('direct visits and untrusted return parameters resolve to a known campus chapter', () => {
  assert.equal(contentOrigin('school-introduction', undefined).id, 'about')
  for (const from of ['https://example.test', '//example.test', 'constructor', '__proto__', ['about'], null, 'unknown']) {
    assert.equal(contentOrigin('platform-development', from).id, 'disclosure')
    assert.equal(contentOrigin('school-introduction', from).id, 'about')
  }
})
test('content links retain origin and keep a slug inside one route segment', () => {
  assert.deepEqual(publicContentLink('school-introduction', 'home'), { path: '/content/school-introduction', query: { from: 'home' } })
  assert.equal(publicContentLink('a/b?c=d', 'disclosure').path, '/content/a%2Fb%3Fc%3Dd')
})
test('publication dates use the campus timezone and missing dates stay absent', () => {
  assert.equal(formatPublicDate('2026-10-29T17:00:00Z'), '2026/10/30')
  for (const value of [null, undefined, '', 'invalid']) assert.equal(formatPublicDate(value), '')
})
test('source links accept public web addresses and reject executable or malformed schemes', () => {
  assert.deepEqual(publicSource('https://www.zzuit.edu.cn/gyxy/xyfg.htm'), { href: 'https://www.zzuit.edu.cn/gyxy/xyfg.htm', host: 'www.zzuit.edu.cn' })
  for (const value of [null, '', 'javascript:alert(1)', 'data:text/html,test', '/relative', 'file:///C:/secret']) assert.equal(publicSource(value), null)
})
test('reading paragraphs preserve supplied line breaks and markup as plain text', () => {
  assert.deepEqual(contentParagraphs('第一行\n第二行\n\n <script>alert(1)</script>\r\n\r\n末段'), ['第一行\n第二行', '<script>alert(1)</script>', '末段'])
})
