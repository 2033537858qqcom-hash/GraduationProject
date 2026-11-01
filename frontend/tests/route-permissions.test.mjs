import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const routerCode = fs.readFileSync(new URL('../src/router.ts', import.meta.url), 'utf8')
const studentPortalCode = fs.readFileSync(new URL('../src/pages/StudentPortal.vue', import.meta.url), 'utf8')
const workbenchCode = fs.readFileSync(new URL('../src/pages/WorkbenchPage.vue', import.meta.url), 'utf8')
const paymentLibCode = fs.readFileSync(new URL('../src/lib/payment.ts', import.meta.url), 'utf8')

/**
 * 从 router.ts 源码中提取路由对象（每个路由对象是单层花括号结构，meta 为其中唯一的嵌套对象）。
 * router.ts 无法在 Node 中直接 import（依赖 vue-router 的浏览器 history），因此做源码解析；
 * 解析不到预期路由时用例会失败并提示检查 router.ts 结构变化。
 */
function routeRecords(source) {
  const records = []
  for (const match of source.matchAll(/\{(?:[^{}]|\{[^{}]*\})*\}/g)) {
    const body = match[0]
    const path = /\bpath:\s*'([^']*)'/.exec(body)
    if (!path) continue
    const meta = {}
    const metaBody = /\bmeta:\s*\{([^{}]*)\}/.exec(body)
    if (metaBody) {
      for (const pair of metaBody[1].matchAll(/(\w+):\s*(?:'([^']*)'|(true|false))/g)) {
        meta[pair[1]] = pair[2] !== undefined ? pair[2] : pair[3] === 'true'
      }
    }
    const redirect = /\bredirect:\s*'([^']*)'/.exec(body)
    const component = /\bcomponent:\s*\(\)\s*=>\s*import\('([^']+)'\)/.exec(body)
    records.push({ path: path[1], meta, redirect: redirect?.[1] ?? null, component: component?.[1] ?? null })
  }
  return records
}

const routes = routeRecords(routerCode)
const byPath = new Map(routes.map(route => [route.path, route]))
const pickMeta = meta => Object.fromEntries(
  ['permission', 'financePermission', 'contentPermission', 'repairResponsibility'].filter(key => key in meta).map(key => [key, meta[key]]),
)

// 每个业务路由的 meta 权限矩阵：permission / financePermission / contentPermission / repairResponsibility。
const expectedPermissions = {
  // 学生门户
  'portal': { permission: 'portal:read' },
  'portal/schedule': { permission: 'portal:read' },
  'portal/learning': { permission: 'learning:read' },
  'portal/learning/timetable': { permission: 'learning:read' },
  'portal/learning/grades': { permission: 'learning:read' },
  'portal/learning/exams': { permission: 'learning:read' },
  'portal/notices': { permission: 'portal:read' },
  'portal/affairs': { permission: 'portal:read' },
  'portal/payments': { permission: 'portal:read' },
  'portal/certificates': { permission: 'certificate:apply' },
  'portal/leave': { permission: 'leave:apply' },
  'portal/reservations': { permission: 'reservation:apply' },
  // 教学与教务
  'teaching': { permission: 'teaching:manage' },
  'teaching/timetable': { permission: 'teaching:manage' },
  'academic': { permission: 'academic:manage' },
  // 岗位办理
  'student-affairs/certificates': { permission: 'certificate:review' },
  'student-affairs/leave': { permission: 'leave:review' },
  'student-affairs/reservations': { permission: 'reservation:review' },
  'student-affairs/housing': { permission: 'housing:review' },
  'housing/assignments': { permission: 'housing:assign' },
  'housing/mine': { permission: 'housing:read' },
  'housing/transfer': { permission: 'housing:read' },
  'repairs/mine': { permission: 'repair:apply' },
  'logistics/repairs/dispatch': { permission: 'repair:dispatch' },
  'logistics/repairs/statistics': { permission: 'repair:dispatch' },
  'logistics/repairs/tasks': { permission: 'repair:work' },
  'logistics/repair-responsibilities': { repairResponsibility: true },
  // 工作台与内容
  'workbench': { permission: 'workbench:read' },
  'admin/content': { contentPermission: true },
  // 财务/缴费三条路由
  'finance/payments': { financePermission: true },
  'payment-bases': { permission: 'payment:source' },
  // 基础管理
  'admin/foundation': { permission: 'foundation:manage' },
  'admin/resources': { permission: 'foundation:manage' },
  'admin/counselors': { permission: 'foundation:manage' },
  'admin/repair-settings': { permission: 'repair:configure' },
  'admin/housing': { permission: 'housing:configure' },
  // 登录后无岗位权限门控的页面（后端仅要求已登录，属于既定设计）
  'notices': {},
  'profile': {},
}

test('every business route exists and carries the expected permission meta', () => {
  for (const [path, expected] of Object.entries(expectedPermissions)) {
    const route = byPath.get(path)
    assert.ok(route, `router.ts 缺少路由 ${path}`)
    assert.equal(route.component !== null, true, `${path} 应挂载页面组件而不是 redirect`)
    assert.deepEqual(pickMeta(route.meta), expected, `${path} 的权限 meta 与约定不一致`)
  }
})

test('finance entry routes keep the V36 finance separation and legacy redirects', () => {
  assert.deepEqual(pickMeta(byPath.get('finance/payments').meta), { financePermission: true })
  assert.deepEqual(pickMeta(byPath.get('payment-bases').meta), { permission: 'payment:source' })
  assert.deepEqual(pickMeta(byPath.get('portal/payments').meta), { permission: 'portal:read' })
  // 旧入口仅重定向到财务双岗页面，管理员没有财务权限
  assert.equal(byPath.get('admin/payments').redirect, '/finance/payments')
  assert.deepEqual(pickMeta(byPath.get('admin/payments').meta), {})
  // 内容岗位由 contentPermission 统一门控，而不是硬编码角色名
  assert.deepEqual(pickMeta(byPath.get('admin/content').meta), { contentPermission: true })
  assert.equal(/to\.meta\.contentPermission\s*&&\s*!currentUser\.value\?\.permissions\.some\(p\s*=>\s*p\.startsWith\('content:'\)\)/.test(routerCode), true)
})

test('the route guard resolves each permission kind with the matching permission set', () => {
  assert.match(routerCode, /to\.meta\.permission\s*&&\s*!currentUser\.value\?\.permissions\.includes\(String\(to\.meta\.permission\)\)/)
  assert.match(routerCode, /to\.meta\.financePermission\s*&&\s*!currentUser\.value\?\.permissions\.some\(p\s*=>\s*p\s*===\s*'payment:manage'\s*\|\|\s*p\s*===\s*'payment:review'\)/)
  assert.match(routerCode, /to\.meta\.repairResponsibility\s*&&\s*!currentUser\.value\?\.permissions\.some\(p\s*=>\s*p\s*===\s*'repair:dispatch'\s*\|\|\s*p\s*===\s*'repair:work'\)/)
  // 未登录先跳登录、强制改密优先于任何业务页
  assert.match(routerCode, /if\s*\(to\.meta\.authenticated\s*&&\s*!currentUser\.value\)\s*return\s*\{\s*path:\s*'\/login'/)
  assert.match(routerCode, /passwordChangeRequired\s*&&\s*to\.path\s*!==\s*'\/account\/password'\s*\)\s*return\s*'\/account\/password'/)
})

test('student portal exposes the payment hall card for portal:read accounts', () => {
  // 服务目录复用 ServiceEntry/服务卡模式，缴费入口由纯函数按 portal:read 生成，不硬编码绕过权限
  assert.match(studentPortalCode, /import\s*\{\s*portalPaymentServiceEntry\s*\}\s*from\s*'\.\.\/lib\/payment'/)
  assert.match(studentPortalCode, /portalPaymentServiceEntry\(overview\.value\?\.profile\.permissions\s*\?\?\s*\[\]\)/)
  assert.match(studentPortalCode, /items\.push\(\{\s*\.\.\.payment,\s*icon:\s*Wallet\s*\}\)/)
  // 目标落点是缴费大厅路由，缴费入口只在 /portal/payments 业务页内
  assert.match(paymentLibCode, /export const PORTAL_PAYMENT_ROUTE = '\/portal\/payments'/)
  assert.equal(byPath.get('portal/payments')?.component?.endsWith('PaymentPage.vue'), true)
})

test('student portal no longer keeps the unreachable timetable/notices view branches', () => {
  assert.doesNotMatch(studentPortalCode, /view\s*===\s*'timetable'/)
  assert.doesNotMatch(studentPortalCode, /view\s*===\s*'notices'/)
  assert.doesNotMatch(studentPortalCode, /route\.path\.endsWith\('timetable'\)/)
  assert.doesNotMatch(studentPortalCode, /route\.path\.endsWith\('notices'\)/)
  // 课表与消息功能仍由各自页面承载，删除的是 StudentPortal 内不可达分支
  assert.equal(byPath.get('portal/learning/timetable')?.component?.endsWith('LearningPage.vue'), true)
  assert.equal(byPath.get('portal/notices')?.component?.endsWith('NoticesPage.vue'), true)
})

test('content workspace maps to the content manager and outranks finance support', async () => {
  const { defaultWorkspaceCode, workspaceEntryVisible } = await import('../src/lib/workbenchWorkspace.ts')
  assert.equal(workspaceEntryVisible('CONTENT', '/admin/content'), true)
  assert.equal(workspaceEntryVisible('CONTENT', '/admin/foundation'), false)
  assert.equal(defaultWorkspaceCode([{ code: 'PERSONAL' }, { code: 'FINANCE' }, { code: 'CONTENT' }]), 'CONTENT')
  // 工作台的官网内容入口同时受权限与当前工作空间约束，没有硬编码角色名
  assert.match(workbenchCode, /v-if="content && entryVisible\('\/admin\/content'\)"/)
  assert.match(workbenchCode, /const content = computed\(\(\) => currentUser\.value\?\.permissions\.some\(p => p\.startsWith\('content:'\)\)\)/)
})

test('workbench selects a business workspace by priority and maps the content workspace', () => {
  // 默认落点与入口可见性由 lib 纯函数决定，不再依赖后端返回顺序
  assert.match(workbenchCode, /import\s*\{\s*defaultWorkspaceCode,\s*workspaceEntryVisible\s*\}\s*from\s*'\.\.\/lib\/workbenchWorkspace'/)
  assert.match(workbenchCode, /const preferred = defaultWorkspaceCode\(workspaces\.value\)/)
  assert.match(workbenchCode, /return workspaceEntryVisible\(activeWorkspace\.value\?\.code, pathPrefix, showAllServices\.value\)/)
  assert.doesNotMatch(workbenchCode, /workspaces\.value\.find\(w\s*=>\s*w\.code\s*!==\s*'PERSONAL'\)/)
  // localStorage 记忆仍保留：读取/写入同一键，只有用户主动切换才标记为手选
  assert.match(workbenchCode, /localStorage\.getItem\(WORKSPACE_STORAGE_KEY\)/)
  assert.match(workbenchCode, /localStorage\.setItem\(WORKSPACE_STORAGE_KEY, code\)/)
  assert.match(workbenchCode, /selectWorkspace\(ws\.code, true\)/)
})
