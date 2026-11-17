import test, { afterEach, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import {
  CAMPUS_ATMOSPHERE_KEY,
  campusAtmosphere,
  initializeCampusAtmosphere,
  setCampusAtmosphere,
} from '../src/lib/campusTheme.ts'

const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')

let env
let releases

beforeEach(() => {
  releases = []
  const storageListeners = new Set()
  const attributes = new Map()

  env = {
    stored: null,
    writes: 0,
    storageListeners,
    attributes,
    storageEvent(key, newValue) {
      for (const listener of storageListeners) listener({ key, newValue })
    },
  }

  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      documentElement: {
        setAttribute(k, v) {
          attributes.set(k, v)
        },
        getAttribute(k) {
          return attributes.get(k) ?? null
        },
        removeAttribute(k) {
          attributes.delete(k)
        },
      },
    },
  })

  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: {
      localStorage: {
        getItem(k) {
          return k === CAMPUS_ATMOSPHERE_KEY ? env.stored : null
        },
        setItem(k, v) {
          if (k === CAMPUS_ATMOSPHERE_KEY) {
            env.writes += 1
            env.stored = v
          }
        },
      },
      addEventListener(name, listener) {
        if (name === 'storage') storageListeners.add(listener)
      },
      removeEventListener(name, listener) {
        if (name === 'storage') storageListeners.delete(listener)
      },
    },
  })
})

afterEach(() => {
  for (const release of releases.splice(0)) release()
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else delete globalThis.window
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
})

test('campusAtmosphere defaults to day, switches between day and sunny, and removes root attr outside portal/login', () => {
  releases.push(initializeCampusAtmosphere())
  assert.equal(campusAtmosphere.value, 'day')
  assert.equal(globalThis.document.documentElement.getAttribute('data-campus-atmosphere'), 'day')

  for (const mode of ['sunny', 'day']) {
    setCampusAtmosphere(mode)
    assert.equal(campusAtmosphere.value, mode)
    assert.equal(env.stored, mode)
    assert.equal(globalThis.document.documentElement.getAttribute('data-campus-atmosphere'), mode)
  }

  for (const release of releases.splice(0)) release()
  assert.equal(globalThis.document.documentElement.getAttribute('data-campus-atmosphere'), null)
})

test('invalid or removed atmosphere values fall back safely to day', () => {
  env.stored = 'night'
  releases.push(initializeCampusAtmosphere())
  assert.equal(campusAtmosphere.value, 'day')
  assert.equal(globalThis.document.documentElement.getAttribute('data-campus-atmosphere'), 'day')

  env.storageEvent(CAMPUS_ATMOSPHERE_KEY, 'sunny')
  assert.equal(campusAtmosphere.value, 'sunny')
  assert.equal(globalThis.document.documentElement.getAttribute('data-campus-atmosphere'), 'sunny')

  env.storageEvent(CAMPUS_ATMOSPHERE_KEY, 'rain')
  assert.equal(campusAtmosphere.value, 'day')
})
