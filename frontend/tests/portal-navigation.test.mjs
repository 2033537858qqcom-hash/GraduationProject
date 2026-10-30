import test from 'node:test'
import assert from 'node:assert/strict'
import { portalNavigation, currentPortalNavigation } from '../src/lib/portalNavigation.ts'

const paths = groups => groups.flatMap(group => group.items.map(item => item.to))

test('payment entries follow student and administrator permissions', () => {
  assert.ok(paths(portalNavigation(['portal:read'])).includes('/portal/payments'))
  assert.ok(!paths(portalNavigation(['portal:read'])).includes('/admin/payments'))
  assert.ok(paths(portalNavigation(['foundation:manage'])).includes('/admin/payments'))
  assert.ok(!paths(portalNavigation(['foundation:manage'])).includes('/portal/payments'))
})

test('student navigation groups personal services without staff or administration access', () => {
  const groups = portalNavigation(['portal:read', 'reservation:apply', 'housing:read', 'repair:apply'])
  assert.deepEqual(groups.map(group => group.id), ['personal', 'account'])
  for (const path of ['/portal', '/portal/schedule', '/portal/learning', '/portal/affairs', '/portal/reservations', '/housing/mine', '/repairs/mine', '/portal/notices', '/profile']) assert.ok(paths(groups).includes(path))
  assert.ok(!paths(groups).includes('/workbench'))
})

test('foundation administration alone does not grant business review or resource configuration', () => {
  const groups = portalNavigation(['foundation:manage'])
  assert.deepEqual(groups.map(group => group.id), ['work', 'management', 'account'])
  assert.ok(paths(groups).includes('/admin/foundation'))
  for (const path of ['/student-affairs/leave', '/student-affairs/certificates', '/admin/repair-settings', '/admin/housing', '/portal']) assert.ok(!paths(groups).includes(path))
})

test('a student with staff capabilities keeps both navigation groups with no duplicate paths', () => {
  const groups = portalNavigation(['portal:read', 'teaching:manage', 'leave:review', 'repair:dispatch', 'repair:work'])
  const result = paths(groups)
  assert.ok(result.includes('/portal') && result.includes('/workbench') && result.includes('/teaching'))
  assert.ok(result.includes('/logistics/repairs/dispatch') && result.includes('/logistics/repairs/tasks'))
  assert.equal(new Set(result).size, result.length)
})

test('content, housing and resource capabilities retain their original destinations', () => {
  const groups = portalNavigation(['content:review', 'housing:review', 'housing:assign', 'housing:configure', 'repair:configure', 'reservation:review'])
  for (const path of ['/admin/content', '/student-affairs/housing', '/housing/assignments', '/admin/housing', '/admin/repair-settings', '/student-affairs/reservations']) assert.ok(paths(groups).includes(path))
  assert.ok(!paths(groups).includes('/admin/foundation'))
})

test('the active link follows the most specific route and request-page aliases', () => {
  const groups = portalNavigation(['portal:read', 'housing:read'])
  for (const [path, target] of [['/portal', '/portal'], ['/portal/schedule', '/portal/schedule'], ['/portal/learning/timetable', '/portal/learning'], ['/portal/leave', '/portal/affairs'], ['/portal/certificates', '/portal/affairs'], ['/housing/transfer', '/housing/mine'], ['/portal/notices', '/portal/notices']]) {
    assert.equal(currentPortalNavigation(groups, path)?.item.to, target)
  }
})

test('partial route names and unavailable destinations do not highlight an unrelated service', () => {
  const groups = portalNavigation(['foundation:manage'])
  for (const path of ['/portal/leave', '/admin/housing', '/profile-other', '/workbenches']) assert.equal(currentPortalNavigation(groups, path), undefined)
})
