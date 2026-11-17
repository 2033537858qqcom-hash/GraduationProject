import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: 'tests/visual',
  use: { baseURL: 'http://127.0.0.1:5199', viewport: { width: 1440, height: 900 } },
  projects: [{ name: 'chromium', use: { channel: 'chromium' } }],
  reporter: 'list',
})
