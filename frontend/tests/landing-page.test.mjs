import test from 'node:test'
import assert from 'node:assert/strict'
import { landingPage } from '../src/lib/landing.ts'

const profile = (permissions, extra = {}) => ({
  id: 1, username: 'demo', fullName: '演示用户', identityNumber: '1001',
  campusName: null, departmentName: null, className: null, avatarUrl: null,
  demo: true, roles: [], permissions, passwordChangeRequired: false, ...extra,
})

test('各角色登录后的落点与既有约定一致', () => {
  // 强制改密优先于一切业务页
  assert.equal(landingPage(profile(['portal:read'], { passwordChangeRequired: true })), '/account/password')
  // 管理底座 → 工作台
  assert.equal(landingPage(profile(['workbench:read', 'foundation:manage'])), '/workbench')
  // 学生门户 → /portal
  assert.equal(landingPage(profile(['portal:read', 'learning:read'])), '/portal')
  // 内容编辑/审核 → 官网内容管理
  assert.equal(landingPage(profile(['workbench:read', 'content:edit'])), '/admin/content')
  assert.equal(landingPage(profile(['workbench:read', 'content:review'])), '/admin/content')
  // 财务双岗、教务、后勤、宿管等岗位 → 工作台
  for (const permissions of [['workbench:read', 'payment:manage'], ['workbench:read', 'payment:review'],
    ['workbench:read', 'payment:source'], ['workbench:read', 'academic:manage'], ['workbench:read', 'repair:dispatch'],
    ['workbench:read', 'housing:assign'], ['workbench:read']]) {
    assert.equal(landingPage(profile(permissions)), '/workbench', permissions.join(','))
  }
  // 内容岗位即使同时持有 workbench:read 也直接进入内容页；学生门户优先于内容岗位
  assert.equal(landingPage(profile(['workbench:read', 'content:edit', 'content:review'])), '/admin/content')
  assert.equal(landingPage(profile(['portal:read', 'workbench:read', 'content:edit'])), '/portal')
})

test('空账号或无权限账号回退到工作台，未知权限不影响既定顺序', () => {
  assert.equal(landingPage(profile([])), '/workbench')
  assert.equal(landingPage(profile(['agent:query'])), '/workbench')
  assert.equal(landingPage(null), '/workbench')
  assert.equal(landingPage(undefined), '/workbench')
})
