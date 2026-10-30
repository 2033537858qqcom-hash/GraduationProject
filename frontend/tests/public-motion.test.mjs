import test, { beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { initializePublicMotion, setPublicMotionPreference, publicMotionPreference, publicReducedMotion, PUBLIC_MOTION_KEY } from '../src/lib/publicMotion.ts'

const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
let env, releases
beforeEach(() => {
  releases = []
  const mediaListeners = new Set(), storageListeners = new Set()
  env = {
    stored: null, blocked: false, writes: 0, mediaListeners, storageListeners,
    media: {
      matches: false,
      addEventListener: (_name, listener) => mediaListeners.add(listener),
      removeEventListener: (_name, listener) => mediaListeners.delete(listener),
    },
    changeSystem(value) { this.media.matches = value; for (const listener of mediaListeners) listener() },
    storageEvent(key, newValue) { for (const listener of storageListeners) listener({ key, newValue }) },
  }
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    localStorage: {
      getItem() { if (env.blocked) throw new Error('Storage blocked'); return env.stored },
      setItem(key, value) { assert.equal(key, PUBLIC_MOTION_KEY); if (env.blocked) throw new Error('Storage blocked'); env.stored = value; env.writes++ },
    },
    matchMedia: () => env.media,
    addEventListener: (_name, listener) => storageListeners.add(listener),
    removeEventListener: (_name, listener) => storageListeners.delete(listener),
  } })
})
afterEach(() => {
  for (const release of releases.reverse()) release()
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else delete globalThis.window
})
function start() { const release = initializePublicMotion(); releases.push(release); return release }

test('public motion follows system changes without overriding the system preference', () => {
  start()
  assert.equal(publicReducedMotion.value, false)
  env.changeSystem(true)
  assert.equal(publicReducedMotion.value, true)
  setPublicMotionPreference('auto')
  assert.equal(publicReducedMotion.value, true)
  env.changeSystem(false)
  assert.equal(publicReducedMotion.value, false)
})
test('the visitor selection survives a layout remount', () => {
  const release = start()
  setPublicMotionPreference('reduce')
  assert.equal(publicReducedMotion.value, true)
  assert.equal(env.stored, 'reduce')
  release()
  start()
  assert.equal(publicMotionPreference.value, 'reduce')
  assert.equal(publicReducedMotion.value, true)
})
test('blocked storage still allows this visit to reduce motion', () => {
  env.blocked = true
  assert.doesNotThrow(start)
  assert.doesNotThrow(() => setPublicMotionPreference('reduce'))
  assert.equal(publicReducedMotion.value, true)
})
test('cross-tab changes and storage clearing update motion without writing back', () => {
  start()
  env.storageEvent('unrelated-preference', 'reduce')
  assert.equal(publicReducedMotion.value, false)
  env.storageEvent(PUBLIC_MOTION_KEY, 'reduce')
  assert.equal(publicReducedMotion.value, true)
  env.storageEvent(null, null)
  assert.equal(publicReducedMotion.value, false)
  assert.equal(env.writes, 0)
})
test('an unknown stored preference falls back to the system setting', () => {
  env.stored = 'unsupported'
  env.media.matches = true
  start()
  assert.equal(publicMotionPreference.value, 'auto')
  assert.equal(publicReducedMotion.value, true)
})
test('shared consumers keep one subscription and remove it after the last unmount', () => {
  const first = start(), second = start()
  assert.equal(env.mediaListeners.size, 1)
  assert.equal(env.storageListeners.size, 1)
  first()
  assert.equal(env.mediaListeners.size, 1)
  second()
  second()
  assert.equal(env.mediaListeners.size, 0)
  assert.equal(env.storageListeners.size, 0)
})
