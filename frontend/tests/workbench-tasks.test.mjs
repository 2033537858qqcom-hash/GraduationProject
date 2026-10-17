import test from 'node:test'
import assert from 'node:assert/strict'
import { useTaskQueue } from '../src/composables/useTaskQueue.ts'

const task = (id, domain = 'RESERVATION') => ({ id, domain, title: id })
const page = (tasks, total = tasks.length, current = 1) => ({ tasks, total, page: current, pageSize: 8, totalPending: total,
  domainCounts: { reservation: total }, complete: true, sourceErrors: [] })
const overview = tasks => ({ tasks, totalPending: 10, taskCounts: { reservation: 9, certificate: 1 }, complete: true, sourceErrors: [] })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }

test('category selection queries the server for a task outside the eight overview items', async () => {
  const calls = []
  const queue = useTaskQueue(async (...args) => { calls.push(args); return page([task('proof', 'CERTIFICATE')]) })
  queue.initialize(overview(Array.from({ length: 8 }, (_, i) => task(`res-${i}`))))
  await queue.load('CERTIFICATE')
  assert.deepEqual(calls, [['CERTIFICATE', 1, 8]])
  assert.equal(queue.tasks.value[0].id, 'proof')
})

test('pagination requests another server page and changing category resets the page', async () => {
  const calls = []
  const queue = useTaskQueue(async (domain, current, size) => { calls.push([domain, current, size]); return page([task(`${domain}-${current}`)], 19, current) })
  await queue.load('RESERVATION', 2)
  assert.equal(queue.currentPage.value, 2)
  assert.equal(queue.pageCount.value, 3)
  await queue.load('CERTIFICATE')
  assert.deepEqual(calls, [['RESERVATION', 2, 8], ['CERTIFICATE', 1, 8]])
  assert.equal(queue.currentPage.value, 1)
})

test('late category responses cannot overwrite a newer selection', async () => {
  const slow = deferred(), fast = deferred()
  const queue = useTaskQueue(domain => domain === 'RESERVATION' ? slow.promise : fast.promise)
  const first = queue.load('RESERVATION'), second = queue.load('CERTIFICATE')
  fast.resolve(page([task('proof', 'CERTIFICATE')]))
  await second
  slow.resolve(page([task('old')], 19))
  await first
  assert.equal(queue.taskFilter.value, 'CERTIFICATE')
  assert.equal(queue.tasks.value[0].id, 'proof')
  assert.equal(queue.total.value, 1)
})

test('a stale error cannot clear the latest results', async () => {
  const slow = deferred()
  const queue = useTaskQueue(domain => domain === 'RESERVATION' ? slow.promise : Promise.resolve(page([task('proof', 'CERTIFICATE')])))
  const first = queue.load('RESERVATION')
  await queue.load('CERTIFICATE')
  slow.reject(new Error('old failure'))
  await first
  assert.equal(queue.error.value, '')
  assert.equal(queue.tasks.value[0].id, 'proof')
})

test('a failed load and an incomplete source never signal that all work is complete', async () => {
  const queue = useTaskQueue(async () => { throw new Error('network down') })
  queue.initialize(overview([]))
  await queue.load()
  assert.equal(queue.complete.value, false)
  assert.ok(queue.error.value)
  const partial = useTaskQueue(async () => ({ ...page([]), complete: false, sourceErrors: [{ domain: 'reservation', label: '场地借用', message: '重试' }] }))
  await partial.load()
  assert.equal(partial.complete.value, false)
  assert.equal(partial.sourceErrors.value.length, 1)
})

test('a shrinking queue returns to the last available page', async () => {
  const calls = []
  const queue = useTaskQueue(async (domain, current) => { calls.push(current); return page(current === 3 ? [] : [task('last')], 9, current) })
  await queue.load('RESERVATION', 3)
  assert.deepEqual(calls, [3, 2])
  assert.equal(queue.currentPage.value, 2)
  assert.equal(queue.tasks.value[0].id, 'last')
})
