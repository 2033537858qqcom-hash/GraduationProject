import test from 'node:test'
import assert from 'node:assert/strict'
import { confirmDialog, confirmRequest, registerConfirmHost, settleConfirm } from '../src/composables/useConfirm.ts'

test('confirmDialog falls back to window.confirm when no host is registered', async () => {
  registerConfirmHost(false)
  const originalConfirm = globalThis.window?.confirm
  let promptedMessage = ''
  if (!globalThis.window) {
    globalThis.window = {}
  }
  globalThis.window.confirm = msg => {
    promptedMessage = msg
    return true
  }
  try {
    const result = await confirmDialog('测试提示文案')
    assert.equal(result, true)
    assert.equal(promptedMessage, '测试提示文案')
  } finally {
    globalThis.window.confirm = originalConfirm
  }
})

test('confirmDialog displays request when host is registered and resolves on settle', async () => {
  registerConfirmHost(true)
  try {
    const promise = confirmDialog('请确认操作', {
      title: '删除警告',
      confirmText: '确定删除',
      danger: true,
    })

    assert.notEqual(confirmRequest.value, null)
    assert.equal(confirmRequest.value?.message, '请确认操作')
    assert.equal(confirmRequest.value?.title, '删除警告')
    assert.equal(confirmRequest.value?.confirmText, '确定删除')
    assert.equal(confirmRequest.value?.danger, true)

    settleConfirm(true)
    const result = await promise
    assert.equal(result, true)
    assert.equal(confirmRequest.value, null)
  } finally {
    registerConfirmHost(false)
  }
})

test('confirmDialog cancels previous unresolved request when a new one arrives', async () => {
  registerConfirmHost(true)
  try {
    const firstPromise = confirmDialog('第一个确认')
    const secondPromise = confirmDialog('第二个确认')

    const firstResult = await firstPromise
    assert.equal(firstResult, false, '上一个未完成的请求应被取消')

    settleConfirm(true)
    const secondResult = await secondPromise
    assert.equal(secondResult, true)
  } finally {
    registerConfirmHost(false)
  }
})
