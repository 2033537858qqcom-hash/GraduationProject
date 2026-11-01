import test from 'node:test'
import assert from 'node:assert/strict'
import { WORKSPACE_PRIORITY, defaultWorkspaceCode, workspaceEntryVisible } from '../src/lib/workbenchWorkspace.ts'

const codes = list => list.map(code => ({ code }))

test('workspace priority keeps business posts before finance support and personal space', () => {
  assert.deepEqual(WORKSPACE_PRIORITY, [
    'TEACHING', 'COUNSELOR', 'AFFAIRS', 'HOUSING', 'LOGISTICS', 'ACADEMIC', 'CONTENT', 'ADMIN',
    'FINANCE', 'PAYMENT_SOURCE', 'PERSONAL',
  ])
  // 后端按 FINANCE/PAYMENT_SOURCE → 业务空间 → PERSONAL 返回时，默认仍落到业务岗位
  assert.equal(defaultWorkspaceCode(codes(['FINANCE', 'PAYMENT_SOURCE', 'ACADEMIC', 'PERSONAL'])), 'ACADEMIC')
  assert.equal(defaultWorkspaceCode(codes(['PAYMENT_SOURCE', 'HOUSING', 'PERSONAL'])), 'HOUSING')
  assert.equal(defaultWorkspaceCode(codes(['PAYMENT_SOURCE', 'LOGISTICS', 'PERSONAL'])), 'LOGISTICS')
  assert.equal(defaultWorkspaceCode(codes(['PAYMENT_SOURCE', 'ACADEMIC', 'PERSONAL'])), 'ACADEMIC')
  assert.equal(defaultWorkspaceCode(codes(['CONTENT', 'PERSONAL'])), 'CONTENT')
  assert.equal(defaultWorkspaceCode(codes(['ADMIN', 'PERSONAL'])), 'ADMIN')
  // 业务空间按优先级取第一个，与后端数组顺序无关
  assert.equal(defaultWorkspaceCode(codes(['PERSONAL', 'AFFAIRS', 'TEACHING'])), 'TEACHING')
  assert.equal(defaultWorkspaceCode(codes(['PERSONAL', 'COUNSELOR', 'AFFAIRS'])), 'COUNSELOR')
  // 只有财务/个人空间时依次回退
  assert.equal(defaultWorkspaceCode(codes(['PERSONAL', 'FINANCE'])), 'FINANCE')
  assert.equal(defaultWorkspaceCode(codes(['PERSONAL', 'PAYMENT_SOURCE'])), 'PAYMENT_SOURCE')
  assert.equal(defaultWorkspaceCode(codes(['PERSONAL'])), 'PERSONAL')
  // 后端新增的未知空间按返回顺序兜底，空列表返回空串
  assert.equal(defaultWorkspaceCode(codes(['PERSONAL', 'NEW_POST'])), 'PERSONAL')
  assert.equal(defaultWorkspaceCode(codes(['NEW_POST'])), 'NEW_POST')
  assert.equal(defaultWorkspaceCode([]), '')
})

test('each workspace only reveals its own entries and the content workspace maps to the content manager', () => {
  assert.equal(workspaceEntryVisible('TEACHING', '/teaching'), true)
  assert.equal(workspaceEntryVisible('TEACHING', '/teaching/timetable'), true)
  assert.equal(workspaceEntryVisible('TEACHING', '/academic'), false)
  assert.equal(workspaceEntryVisible('COUNSELOR', '/student-affairs/leave'), true)
  assert.equal(workspaceEntryVisible('COUNSELOR', '/student-affairs/certificates'), false)
  assert.equal(workspaceEntryVisible('AFFAIRS', '/student-affairs/certificates'), true)
  assert.equal(workspaceEntryVisible('HOUSING', '/housing/assignments'), true)
  assert.equal(workspaceEntryVisible('HOUSING', '/admin/housing'), true)
  assert.equal(workspaceEntryVisible('LOGISTICS', '/logistics/repairs/tasks'), true)
  assert.equal(workspaceEntryVisible('ACADEMIC', '/academic'), true)
  assert.equal(workspaceEntryVisible('ACADEMIC', '/payment-bases'), false)
  // 内容编辑/审核岗位的工作空间入口：只映射官网内容管理
  assert.equal(workspaceEntryVisible('CONTENT', '/admin/content'), true)
  assert.equal(workspaceEntryVisible('CONTENT', '/admin/foundation'), false)
  assert.equal(workspaceEntryVisible('CONTENT', '/profile'), false)
  assert.equal(workspaceEntryVisible('ADMIN', '/admin/content'), true)
  assert.equal(workspaceEntryVisible('ADMIN', '/profile'), false)
  assert.equal(workspaceEntryVisible('FINANCE', '/finance/payments'), true)
  assert.equal(workspaceEntryVisible('FINANCE', '/payment-bases'), false)
  assert.equal(workspaceEntryVisible('PAYMENT_SOURCE', '/payment-bases'), true)
  assert.equal(workspaceEntryVisible('PERSONAL', '/profile'), true)
  assert.equal(workspaceEntryVisible('PERSONAL', '/repairs/mine'), true)
  assert.equal(workspaceEntryVisible('PERSONAL', '/portal/reservations'), true)
  assert.equal(workspaceEntryVisible('PERSONAL', '/admin/content'), false)
})

test('全部服务开关、未就绪与未知工作空间都不会把入口误隐藏', () => {
  assert.equal(workspaceEntryVisible('TEACHING', '/finance/payments', true), true)
  assert.equal(workspaceEntryVisible('FINANCE', '/profile', true), true)
  assert.equal(workspaceEntryVisible(undefined, '/admin/content'), true)
  assert.equal(workspaceEntryVisible('', '/admin/content'), true)
  assert.equal(workspaceEntryVisible('NEW_POST', '/admin/content'), true)
})
