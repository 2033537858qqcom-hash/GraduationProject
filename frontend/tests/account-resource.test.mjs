import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { newPasswordError } from '../src/lib/passwordPolicy.ts'

test('password confirmation accepts a matching strong password without trimming it', () => {
  assert.equal(newPasswordError('Campus2026 Secure ', 'Campus2026 Secure '), '')
  assert.match(newPasswordError('Campus2026 Secure ', 'Campus2026 Secure'), /不一致/)
})
test('password rules reject short and single-character-class values', () => {
  for (const value of ['abc1', 'abcdefghijk', '123456789012']) assert.ok(newPasswordError(value, value))
})
test('password length uses UTF-8 bytes so multibyte values cannot be silently truncated', () => {
  const allowed = '中'.repeat(23) + 'a12', rejected = '中'.repeat(24) + 'a1'
  assert.equal(newPasswordError(allowed, allowed), '')
  assert.match(newPasswordError(rejected, rejected), /72/)
})
test('map choices in the server catalog stay aligned with both existing campus scenes', async () => {
  const catalog = JSON.parse(await readFile(new URL('../../backend/src/main/resources/campus/reservation-places.json', import.meta.url), 'utf8'))
  const definitions = new Map()
  for (const [campusCode, file] of [['EAST', 'placeDefinitions.ts'], ['WEST', 'westCampusPlan.ts']]) {
    const source = await readFile(new URL(`../src/campus/${file}`, import.meta.url), 'utf8')
    const name = campusCode === 'EAST' ? 'campusPlaceDefinitions' : 'westPlanDefinitions'
    const array = source.match(new RegExp(`export const ${name}[^=]*= \\[([\\s\\S]*?)\\n\\]`))?.[1]
    assert.ok(array, `Missing named place definition array: ${name}`)
    for (const match of array.matchAll(/^\s*\{\s*id:\s*'([^']+)',\s*name:\s*'([^']+)'/gm)) definitions.set(match[1], { campusCode, name: match[2] })
  }
  assert.equal(catalog.length, definitions.size)
  assert.equal(new Set(catalog.map(p => p.id)).size, catalog.length)
  for (const place of catalog) assert.deepEqual({ campusCode: place.campusCode, name: place.name }, definitions.get(place.id))
})
