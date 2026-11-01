import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('restoring an ordinal question requires its saved business context', async () => {
 const descriptors = ['localStorage', 'window', 'fetch'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)])
 let server
 try {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { location: { search: '' }, addEventListener() {}, removeEventListener() {} } })
  globalThis.fetch = async () => { throw Error('network disabled') }
  server = await createServer({ configFile: false, root: fileURLToPath(new URL('../', import.meta.url)), cacheDir: fileURLToPath(new URL('../node_modules/.vite-tests/assistant-history', import.meta.url)), plugins: [vue()], server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } })
  const { checkPageSetup } = await server.ssrLoadModule('/tests/fixtures/page-runtime.ts')
  const errors = await checkPageSetup('/assistant-test', undefined, async state => {
   state.question.value = '尚未发送的输入'
   state.requery({ question: '第二笔为什么这么贵？', context: null })
   assert.equal(state.question.value, '尚未发送的输入')
   assert.match(state.error.value, /关联|选择/)
   state.requery({ question: '第二笔为什么这么贵？', context: { type: 'PAYMENT_MINE', id: 12 } })
   assert.equal(state.question.value, '第二笔为什么这么贵？')
   assert.equal(state.requeryContext.value.id, 12)
   state.requery({ question: '本周课表', context: null })
   assert.equal(state.question.value, '本周课表')
  })
  assert.ifError(errors[0])
 } finally {
  await server?.close()
  for (const [key, descriptor] of descriptors) {
   if (descriptor) Object.defineProperty(globalThis, key, descriptor)
   else delete globalThis[key]
  }
 }
})
