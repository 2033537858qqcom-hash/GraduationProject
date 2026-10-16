import test from 'node:test'
import assert from 'node:assert/strict'
import { affairTimelinePath } from '../src/lib/affairTimeline.ts'

test('timeline links are limited to one own business and safe numeric identifiers', () => {
  assert.equal(affairTimelinePath('LEAVE', 8), '/affairs/mine/LEAVE/8/timeline?page=1&pageSize=10')
  for (const kind of ['LEAVE', 'CERTIFICATE', 'REPAIR', 'HOUSING']) assert.ok(affairTimelinePath(kind, 1).startsWith('/affairs/mine/' + kind))
  for (const args of [['../review', 8], ['UNKNOWN', 8], ['LEAVE', 0], ['REPAIR', 1.5], ['HOUSING', Number.MAX_SAFE_INTEGER + 1]]) assert.throws(() => affairTimelinePath(...args))
})
test('paging carries the server revision and rejects invalid page or revision values', () => {
  assert.equal(affairTimelinePath('HOUSING', 7, 2, 0), '/affairs/mine/HOUSING/7/timeline?page=2&pageSize=10&expectedVersion=0')
  for (const args of [['LEAVE', 8, 0], ['LEAVE', 8, 1000001], ['REPAIR', 8, 1, -1], ['CERTIFICATE', 8, 2, NaN]]) assert.throws(() => affairTimelinePath(...args))
})
