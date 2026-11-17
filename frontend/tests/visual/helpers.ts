import type { Page, Route } from '@playwright/test'

export const student = {
  id: 1,
  username: '2024001',
  fullName: '测试学生',
  identityNumber: '410000000000000000',
  campusName: '本部',
  departmentName: '计算机学院',
  className: '2024 级 1 班',
  avatarUrl: null,
  demo: true,
  roles: ['STUDENT'],
  permissions: [
    'portal:read',
    'learning:read',
    'workbench:read',
    'housing:read',
    'housing:assign',
    'housing:configure',
    'housing:review',
    'repair:apply',
    'repair:dispatch',
    'repair:work',
    'repair:configure',
    'leave:apply',
    'reservation:apply',
    'payment:source',
    'foundation:manage',
    'certificate:apply',
  ],
  passwordChangeRequired: false,
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** 模拟登录态：会话接口返回学生，其余 API 返回空数据，页面可以完整渲染骨架。 */
export async function mockSignedInApi(page: Page) {
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/csrf') return json(route, { headerName: 'X-CSRF-TOKEN', token: 'test' })
    if (url.pathname === '/api/me') return json(route, student)
    if (url.pathname.endsWith('/unread-count')) return json(route, { count: 0 })
    return json(route, [])
  })
}

export async function gotoSignedIn(page: Page, path: string) {
  await mockSignedInApi(page)
  await page.goto(path)
  await page.waitForLoadState('networkidle')
}
