import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileStyle } from 'vue/compiler-sfc'

test('scoped header styles do not change the entire workflow container', () => {
  const source = readFileSync(new URL('../src/components/ServicePageHeader.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const result = compileStyle({
    source: descriptor.styles[0].content,
    filename: 'ServicePageHeader.vue',
    id: 'data-v-test',
    scoped: true,
  })
  assert.deepEqual(result.errors, [])
  assert.doesNotMatch(result.code, /#portal-main\s+\.service-workflow\s*\{[^}]*(?:font-family|font-size)/s)
})
