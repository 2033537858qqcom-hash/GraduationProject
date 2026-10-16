import test from 'node:test'
import assert from 'node:assert/strict'
import { safeLoginDestination, safeAffairDestination } from '../src/lib/navigation.ts'

test('login keeps a known student or staff deep link with its selection', () => {
  for (const path of ['/portal/affairs?kind=CERTIFICATE&page=2', '/portal/certificates?id=14&tab=history', '/student-affairs/certificates?id=14&tab=details', '/teaching/timetable', '/notices?read=false&page=3', '/admin/repair-settings', '/admin/housing', '/housing/mine', '/housing/assignments', '/housing/transfer', '/student-affairs/housing', '/logistics/repair-responsibilities', '/repairs/mine?id=5&tab=details', '/logistics/repairs/dispatch?id=5', '/logistics/repairs/tasks?id=5']) assert.equal(safeLoginDestination(path), path)
})
test('login cannot be redirected to an external, malformed, or unknown destination', () => {
  for (const path of ['https://example.test', '//example.test', '/\\example.test', 'javascript:alert(1)', '/portal/../../other', '/portal/%2e%2e/other', '/portal\n/leave', '/admin/unknown', '/logistics/unknown', ['/portal']]) assert.equal(safeLoginDestination(path), null)
})
test('business message links require the known request route and positive numeric identity', () => {
  for (const path of ['/portal/leave?id=1&tab=details', '/portal/certificates?id=18&tab=details', '/student-affairs/certificates?id=8&tab=details', '/student-affairs/leave?id=8&tab=details', '/repairs/mine?id=5&tab=details', '/logistics/repairs/dispatch?id=5&tab=details', '/logistics/repairs/tasks?id=5&tab=details']) assert.equal(safeAffairDestination(path), path)
})
test('message content cannot introduce external or arbitrary action links', () => {
  for (const path of [null, undefined, 'https://example.test', '/admin/foundation?id=1&tab=details', '/portal/leave?id=0&tab=details', '/portal/certificates?id=1&tab=details&action=approve', '/portal/certificates?id=abc&tab=details', '//example.test?id=1&tab=details']) assert.equal(safeAffairDestination(path), null)
})
