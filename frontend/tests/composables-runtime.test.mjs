import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { useRequest } from '../src/composables/useRequest.ts'
import { useToast } from '../src/composables/useToast.ts'

test('useRequest manages loading, data, stale request cancellation, and errors', async () => {
  const scope = effectScope()
  try {
    let seenSignals = []
    let resolvers = []

    const state = scope.run(() =>
      useRequest(
        (signal, label) => {
          seenSignals.push(signal)
          return new Promise((resolve, reject) => {
            signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
            resolvers.push({ label, resolve, reject })
          })
        },
        { immediate: false, keepPrevious: true },
      ),
    )

    assert.ok(state)
    assert.equal(state.loading.value, false)
    assert.equal(state.data.value, null)

    const p1 = state.run('first')
    assert.equal(state.loading.value, true)
    assert.equal(seenSignals[0].aborted, false)

    const p2 = state.run('second')
    assert.equal(seenSignals[0].aborted, true)
    assert.equal(seenSignals[1].aborted, false)

    resolvers[1].resolve('second-result')
    await Promise.all([p1, p2])
    assert.equal(state.data.value, 'second-result')
    assert.equal(state.loading.value, false)
    assert.equal(state.error.value, '')

    const p3 = state.run('fail')
    resolvers[2].reject(new Error('服务暂时不可用'))
    await p3
    assert.equal(state.error.value, '服务暂时不可用')
    assert.equal(state.data.value, 'second-result')
  } finally {
    scope.stop()
  }
})

test('useRequest with keepPrevious: false clears previous data while running', async () => {
  const scope = effectScope()
  try {
    let call = 0
    const state = scope.run(() =>
      useRequest(
        async () => {
          call++
          return `item-${call}`
        },
        { immediate: true, keepPrevious: false },
      ),
    )
    assert.ok(state)
    await new Promise(resolve => setTimeout(resolve, 10))
    assert.equal(state.data.value, 'item-1')

    const pending = state.run()
    assert.equal(state.data.value, null)
    await pending
    assert.equal(state.data.value, 'item-2')
  } finally {
    scope.stop()
  }
})

test('useToast adds typed notifications and removes them manually or by timer', async () => {
  const toast = useToast()
  const initialCount = toast.toasts.value.length
  const id1 = toast.success('已保存', 0)
  const id2 = toast.warning('请检查输入', 25)
  assert.equal(toast.toasts.value.length, initialCount + 2)
  assert.equal(toast.toasts.value.at(-2)?.type, 'success')
  assert.equal(toast.toasts.value.at(-1)?.type, 'warning')

  toast.remove(id1)
  assert.equal(
    toast.toasts.value.some(item => item.id === id1),
    false,
  )

  await new Promise(resolve => setTimeout(resolve, 60))
  assert.equal(
    toast.toasts.value.some(item => item.id === id2),
    false,
  )
})
