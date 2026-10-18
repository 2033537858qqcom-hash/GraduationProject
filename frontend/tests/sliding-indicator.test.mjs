import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { useSlidingIndicator } from '../src/composables/useSlidingIndicator.ts'

test('sliding indicator computes offset and width of active element', async () => {
  const trigger = ref(0)
  const fakeActive = {
    getBoundingClientRect: () => ({ left: 150, width: 88, top: 0, bottom: 20 }),
  }
  const fakeContainer = {
    scrollLeft: 10,
    getBoundingClientRect: () => ({ left: 50, top: 0, bottom: 20, width: 600 }),
    querySelector: (selector) => selector.includes('selected') ? fakeActive : null,
  }

  const containerRef = ref(fakeContainer)
  const { indicatorStyle, updateIndicator } = useSlidingIndicator(containerRef, () => trigger.value)

  updateIndicator()
  // Wait a microtask / nextTick
  await new Promise(resolve => setTimeout(resolve, 20))

  assert.equal(indicatorStyle.value.opacity, 1)
  assert.equal(indicatorStyle.value.width, '88px')
  // left = 150 - 50 + 10 = 110
  assert.equal(indicatorStyle.value.transform, 'translateX(110px)')
})

test('sliding indicator sets opacity to 0 when no active element is matched', async () => {
  const fakeContainer = {
    scrollLeft: 0,
    getBoundingClientRect: () => ({ left: 0, top: 0, bottom: 20, width: 600 }),
    querySelector: () => null,
  }

  const containerRef = ref(fakeContainer)
  const { indicatorStyle, updateIndicator } = useSlidingIndicator(containerRef)

  updateIndicator()
  await new Promise(resolve => setTimeout(resolve, 20))

  assert.equal(indicatorStyle.value.opacity, 0)
})

test('portal nav route activation cleanly separates reservations from affairs', () => {
  const isAffairs = (path) => ['/portal/affairs', '/portal/leave', '/portal/certificates'].some(p => path.startsWith(p))
  const isReservations = (path) => path.startsWith('/portal/reservations')

  assert.equal(isAffairs('/portal/affairs'), true)
  assert.equal(isReservations('/portal/affairs'), false)

  assert.equal(isAffairs('/portal/reservations'), false)
  assert.equal(isReservations('/portal/reservations'), true)
  assert.equal(isReservations('/portal/reservations?tab=mine'), true)
})

test('indicator hides when a previously selected navigation item disappears', async () => {
  const scope = effectScope()
  let active = { getBoundingClientRect: () => ({ left: 90, width: 70 }) }
  const container = ref({
    scrollLeft: 0,
    getBoundingClientRect: () => ({ left: 0 }),
    querySelector: () => active,
  })
  const indicator = scope.run(() => useSlidingIndicator(container))
  try {
    indicator.updateIndicator()
    await nextTick()
    assert.equal(indicator.indicatorStyle.value.opacity, 1)
    active = null
    indicator.updateIndicator()
    await nextTick()
    assert.equal(indicator.indicatorStyle.value.opacity, 0)
  } finally { scope.stop() }
})

test('indicator measures a conditionally recreated container without a filter change', async () => {
  const scope = effectScope()
  const container = ref(null)
  const indicator = scope.run(() => useSlidingIndicator(container))
  try {
    container.value = {
      scrollLeft: 0,
      removeEventListener() {},
      getBoundingClientRect: () => ({ left: 20 }),
      querySelector: () => ({ getBoundingClientRect: () => ({ left: 45, width: 95 }) }),
    }
    await nextTick()
    await nextTick()
    assert.equal(indicator.indicatorStyle.value.opacity, 1)
    assert.equal(indicator.indicatorStyle.value.transform, 'translateX(25px)')
    container.value = null
    await nextTick()
    await nextTick()
    assert.equal(indicator.indicatorStyle.value.opacity, 0)
  } finally { scope.stop() }
})
