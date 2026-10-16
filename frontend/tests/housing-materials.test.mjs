import test from 'node:test'
import assert from 'node:assert/strict'
import { housingMaterialIssue, housingMaterialUrl, housingMaterialSize } from '../src/lib/housingMaterials.ts'

test('material selection gives useful size and format feedback before upload', () => {
  for (const name of ['材料.pdf', 'scan.PNG', 'page.jpeg', '证明.jpg']) assert.equal(housingMaterialIssue({ name, size: 1024 }), '')
  for (const file of [{ name: '材料.pdf', size: 0 }, { name: '材料.pdf', size: 2097153 }, { name: '材料.svg', size: 1024 }, { name: '材料.pdf.exe', size: 10 }]) assert.ok(housingMaterialIssue(file))
  assert.equal(housingMaterialIssue({ name: '材料.pdf', size: 2097152 }), '')
})
test('private material links carry the request and optional frozen submission', () => {
  assert.equal(housingMaterialUrl('mine', 10, 3), '/api/housing/transfers/mine/10/materials/3')
  assert.equal(housingMaterialUrl('review', 10, 3, 7), '/api/housing/transfers/review/10/materials/3?submission=7')
  for (const args of [['assign', 10, 3], ['mine', 0, 3], ['review', 10, -1], ['mine', 10, 3, 0], ['mine', 1.5, 3], ['mine', 10, Number.MAX_SAFE_INTEGER + 1]]) assert.equal(housingMaterialUrl(...args), null)
})
test('file sizes stay readable on narrow screens', () => {
  assert.equal(housingMaterialSize(100), '1 KB')
  assert.equal(housingMaterialSize(2048), '2 KB')
  assert.equal(housingMaterialSize(1572864), '1.5 MB')
})
