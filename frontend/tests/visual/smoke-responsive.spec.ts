import { expect, test } from '@playwright/test'
import { mockSignedInApi, student } from './helpers'

test('public and portal routes render cleanly without overflow or console errors across desktop and mobile', async ({
  page,
}) => {
  const errors = []
  page.on('pageerror', err => errors.push(`pageerror: ${err.message}`))
  page.on('console', msg => {
    if (msg.type() === 'error') errors.push(`console.error: ${msg.text()}`)
  })

  const emptyPage = { items: [], total: 0, page: 1, pageSize: 20 }
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    const json = (body: unknown) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

    if (url.pathname === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 't' })
    if (url.pathname === '/api/me') return json(student)
    if (url.pathname === '/api/portal/overview')
      return json({ profile: student, timetable: [], notices: [], unreadCount: 2 })
    if (url.pathname === '/api/workbench/overview')
      return json({
        profile: student,
        workspaces: [],
        tasks: [],
        totalPending: 0,
        taskCounts: {},
        complete: true,
        sourceErrors: [],
      })
    if (url.pathname === '/api/workbench/tasks')
      return json({
        totalPending: 0,
        domainCounts: {},
        tasks: [],
        total: 0,
        page: 1,
        pageSize: 8,
        complete: true,
        sourceErrors: [],
      })
    if (url.pathname === '/api/notices') return json({ ...emptyPage, unreadCount: 2 })
    if (url.pathname === '/api/notices/unread-count') return json({ count: 2 })
    if (url.pathname === '/api/repairs/summary')
      return json({
        mineReturned: 0,
        mineAwaitingConfirmation: 0,
        mineCancellationPending: 0,
        dispatchPending: 0,
        dispatchCancellationPending: 0,
        workAssigned: 0,
        workInProgress: 0,
      })
    if (url.pathname === '/api/repairs/catalog') return json({ campuses: [] })
    if (
      [
        '/api/repairs/locations',
        '/api/repairs/mine',
        '/api/repairs/dispatch',
        '/api/repairs/work',
        '/api/reservations/mine',
        '/api/reservations/review',
      ].includes(url.pathname)
    ) {
      return json(emptyPage)
    }
    return json([])
  })

  const routes = ['/', '/login', '/portal', '/portal/notices', '/repairs/mine', '/portal/reservations']
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 375, height: 812 },
  ]) {
    await page.setViewportSize(viewport)
    for (const path of routes) {
      await page.goto(path, { waitUntil: 'networkidle' })
      await expect(page.locator('#app')).not.toBeEmpty()
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      )
      expect(overflow, `horizontal overflow on ${path} at ${viewport.width}x${viewport.height}`).toBeLessThanOrEqual(1)
    }
  }
  expect(errors).toEqual([])
})

test('unread notice badge syncs across topbar and mark-read flow with BaseSelect filter', async ({ page }) => {
  await mockSignedInApi(page)
  await page.unroute('**/api/**')

  let notices = [
    {
      id: 101,
      title: '宿舍报修已派工',
      body: '维修人员已接单，请留意联系方式。',
      category: 'REPAIR',
      targetPath: '/repairs/mine',
      read: false,
      createdAt: '2026-03-15T09:00:00',
    },
  ]

  await page.route('**/api/**', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.pathname === '/api/auth/csrf') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 't' }),
      })
    }
    if (url.pathname === '/api/me') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(student) })
    }
    if (url.pathname === '/api/notices') {
      const readFilter = url.searchParams.get('read')
      const filtered =
        readFilter === 'false'
          ? notices.filter(n => !n.read)
          : readFilter === 'true'
            ? notices.filter(n => n.read)
            : notices
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: filtered,
          total: filtered.length,
          page: 1,
          pageSize: 20,
          unreadCount: notices.filter(n => !n.read).length,
        }),
      })
    }
    if (url.pathname === '/api/notices/101/read' && req.method() === 'POST') {
      notices = notices.map(n => (n.id === 101 ? { ...n, read: true } : n))
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.goto('/portal/notices', { waitUntil: 'networkidle' })
  await expect(page.locator('.portal-header .portal-unread-badge')).toHaveText('1')

  // Open teleported BaseSelect dropdown and switch to 未读消息
  await page.locator('.foundation-filters .apple-select-trigger').click()
  const option = page.locator('[role="listbox"] [role="option"]', { hasText: '未读消息' })
  await expect(option).toBeVisible()
  await option.click()
  await expect(page.locator('.notice-row')).toHaveCount(1)

  // Mark notice read -> badge disappears & toast appears
  await page.getByRole('button', { name: '标为已读' }).click()
  await expect(page.locator('.portal-header .portal-unread-badge')).toHaveCount(0)
  await expect(page.locator('.toast-pill')).toContainText('已将该消息标记为已读')
})

test('unauthenticated login page applies portal-experience layout cleanly', async ({ page }) => {
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/csrf') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 't' }),
      })
    }
    if (url.pathname === '/api/me') {
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Unauthorized' }),
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/login', { waitUntil: 'networkidle' })
  await expect(page.locator('.login-form h2')).toHaveText('校园服务登录')

  const layout = await page.evaluate(() => {
    const titleRow = document.querySelector('.login-title-row')
    const field = document.querySelector('.login-password-field')
    const toggleBtn = field?.querySelector('button')
    const form = document.querySelector('.login-form')
    const footer = document.querySelector('.login-form-footer')
    return {
      titleRowDisplay: titleRow ? getComputedStyle(titleRow).display : null,
      titleRowHeight: titleRow?.getBoundingClientRect().height ?? 999,
      fieldRect: field?.getBoundingClientRect().toJSON(),
      btnRect: toggleBtn?.getBoundingClientRect().toJSON(),
      formRect: form?.getBoundingClientRect().toJSON(),
      footerRect: footer?.getBoundingClientRect().toJSON(),
    }
  })

  expect(layout.titleRowDisplay).toBe('flex')
  expect(layout.titleRowHeight).toBeLessThan(80)
  expect(layout.btnRect.left).toBeGreaterThanOrEqual(layout.fieldRect.left)
  expect(layout.btnRect.right).toBeLessThanOrEqual(layout.fieldRect.right + 1)
  expect(layout.btnRect.top).toBeGreaterThanOrEqual(layout.fieldRect.top)
  expect(layout.btnRect.bottom).toBeLessThanOrEqual(layout.fieldRect.bottom + 1)
  expect(layout.footerRect.top).toBeGreaterThanOrEqual(layout.formRect.bottom - 2)
})

test('mobile 375px portal header, reservations filters, schedule strip and profile stack cleanly without overlap', async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    const json = (body: unknown) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    if (url.pathname === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 't' })
    if (url.pathname === '/api/me') return json(student)
    if (url.pathname === '/api/notices/unread-count') return json({ count: 15 })
    if (url.pathname === '/api/workbench/overview')
      return json({
        profile: student,
        workspaces: [{ code: 'STUDENT', label: '学生事务', scopeNote: '' }],
        tasks: [],
        totalPending: 0,
        taskCounts: {},
        complete: true,
        sourceErrors: [],
      })
    if (url.pathname === '/api/workbench/tasks')
      return json({
        totalPending: 0,
        domainCounts: {},
        tasks: [],
        total: 0,
        page: 1,
        pageSize: 8,
        complete: true,
        sourceErrors: [],
      })
    if (url.pathname === '/api/schedule/mine')
      return json({
        weekStart: '2026-11-09',
        weekEnd: '2026-11-15',
        generatedAt: '2026-11-13T10:00:00',
        availableSources: ['LESSON'],
        unavailableSources: [],
        events: [],
      })
    if (url.pathname === '/api/housing/mine') return json({ current: null, history: [] })
    if (url.pathname === '/api/repairs/catalog') return json({ campuses: [] })
    if (
      ['/api/repairs/locations', '/api/repairs/mine', '/api/reservations/mine', '/api/reservations/venues'].includes(
        url.pathname,
      )
    )
      return json({ items: [], total: 0, page: 1, pageSize: 20 })
    return json([])
  })

  await page.goto('/portal/reservations', { waitUntil: 'networkidle' })
  const resMetrics = await page.evaluate(() => {
    const noticeLink = document.querySelector('.portal-notice-link')?.getBoundingClientRect()
    const profileAction = document.querySelector('.portal-profile-action')?.getBoundingClientRect()
    const searchGroup = document
      .querySelector('#portal-main .service-reservation .search-input-group')
      ?.getBoundingClientRect()
    const pills = document.querySelector('#portal-main .service-reservation .category-pills')?.getBoundingClientRect()
    const panel = document.querySelector('#portal-main .venue-catalog-section')?.getBoundingClientRect()
    return {
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      noticeRight: noticeLink?.right ?? 0,
      profileLeft: profileAction?.left ?? 0,
      searchHeight: searchGroup?.height ?? 999,
      pillsRight: pills?.right ?? 999,
      panelRight: panel?.right ?? 0,
    }
  })
  expect(resMetrics.overflow).toBeLessThanOrEqual(1)
  expect(resMetrics.noticeRight).toBeLessThanOrEqual(resMetrics.profileLeft + 1)
  expect(resMetrics.searchHeight).toBeLessThan(80)
  expect(resMetrics.pillsRight).toBeLessThanOrEqual(resMetrics.panelRight + 1)

  for (const path of ['/workbench', '/portal/schedule', '/housing/mine', '/repairs/mine', '/profile']) {
    await page.goto(path, { waitUntil: 'networkidle' })
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow, `mobile overflow on ${path}`).toBeLessThanOrEqual(1)
  }
})
