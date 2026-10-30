import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

test('the homepage animation modules remain available after an independent test server closes', { timeout: 60000 }, async () => {
  const root = fileURLToPath(new URL('../', import.meta.url))
  let preview, testServer
  try {
    preview = await createServer({ root, mode: 'navigation-regression', logLevel: 'error', server: { host: '127.0.0.1', port: 0, strictPort: false, open: false } })
    await preview.listen()
    const address = preview.httpServer.address()
    const origin = `http://127.0.0.1:${address.port}`
    const response = await fetch(origin + '/src/pages/PublicHome.vue')
    assert.equal(response.status, 200)
    const source = await response.text()
    const animationModules = [...source.matchAll(/from\s*["']([^"']*\/gsap(?:_ScrollTrigger)?\.js[^"']*)["']/g)].map(match => match[1])
    assert.equal(animationModules.length, 2, 'The real homepage must load GSAP and ScrollTrigger')
    for (const target of animationModules) assert.equal((await fetch(origin + target)).status, 200, target)

    // Geometry/setup tests use an independent middleware server with its own dependency lifecycle.
    testServer = await createServer({ root, configFile: false, logLevel: 'error', server: { middlewareMode: true, hmr: false, ws: false }, optimizeDeps: { noDiscovery: true, include: [] } })
    assert.notEqual(preview.config.cacheDir, testServer.config.cacheDir, 'Live preview and independent tests must not own the same dependency cache')
    await testServer.ssrLoadModule('/src/lib/publicReading.ts')
    await testServer.close()
    testServer = undefined

    for (const target of animationModules) assert.equal((await fetch(origin + target)).status, 200, 'Closing tests must not invalidate a live homepage dependency: ' + target)
  } finally {
    await testServer?.close()
    await preview?.close()
  }
})
