import { test } from '@playwright/test'
import { writeFileSync, mkdirSync } from 'node:fs'
import { gotoSignedIn } from './helpers'
import { pages } from './pages'

const OUT = process.env.VISUAL_OUT ?? 'test-results/visual/before'

test('baseline: page heading metrics', async ({ page }) => {
  test.setTimeout(600_000)
  mkdirSync(OUT, { recursive: true })
  const rows: Record<string, unknown> = {}
  for (const p of pages) {
    await gotoSignedIn(page, p.path)
    const status = await page.locator('.status-page h1').count()
    const h1 = page.locator('main h1, h1').first()
    const info = await h1
      .evaluate(el => {
        const s = getComputedStyle(el)
        return {
          text: el.textContent?.trim().slice(0, 24),
          fontSize: s.fontSize,
          fontWeight: s.fontWeight,
          marginTop: s.marginTop,
          marginBottom: s.marginBottom,
          parent: el.parentElement?.className.slice(0, 50),
        }
      })
      .catch(() => 'NO_H1')
    rows[p.path] = { fallbackPage: status > 0, ...(typeof info === 'string' ? { h1: info } : info) }
    await page.screenshot({ path: `${OUT}/${p.name}.png` })
  }
  writeFileSync(`${OUT}/headings.json`, JSON.stringify(rows, null, 2))
  console.log(JSON.stringify(rows, null, 2))
})
