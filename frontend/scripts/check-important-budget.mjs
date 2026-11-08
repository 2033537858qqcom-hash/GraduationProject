// 统计 src 中 !important 的数量，超过 design-budget.json 中的基线即失败。
// 基线只允许下降：清理掉一批后应同步下调 design-budget.json 的 important 值。
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join, extname } from 'node:path'

const root = fileURLToPath(new URL('../src', import.meta.url))
const budget = JSON.parse(readFileSync(fileURLToPath(new URL('../design-budget.json', import.meta.url)), 'utf8'))

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) yield* walk(full)
    else if (['.css', '.vue', '.ts'].includes(extname(full))) yield full
  }
}

let count = 0
for (const file of walk(root)) count += (readFileSync(file, 'utf8').match(/!important/g) ?? []).length

if (count > budget.important) {
  console.error(`!important budget exceeded: ${count} > ${budget.important}`)
  process.exitCode = 1
} else {
  console.log(`!important: ${count} / budget ${budget.important}`)
}
