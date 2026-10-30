export interface PortalNavItem {
  to: string
  label: string
  icon: string
  aliases?: string[]
}
export interface PortalNavGroup { id: string; label: string; items: PortalNavItem[] }

/** Navigation follows granted capabilities; role names never grant access. */
export function portalNavigation(permissions: readonly string[]): PortalNavGroup[] {
  const can = (permission: string) => permissions.includes(permission)
  const student = can('portal:read')
  const staff = permissions.some(permission => [
    'teaching:manage', 'academic:manage', 'leave:review', 'certificate:review', 'reservation:review',
    'housing:review', 'housing:assign', 'housing:configure', 'repair:dispatch', 'repair:work',
    'repair:configure', 'foundation:manage', 'content:edit', 'content:review',
  ].includes(permission))
  const personal: PortalNavItem[] = []
  const work: PortalNavItem[] = []
  const management: PortalNavItem[] = []
  if (student) personal.push(
    { to: '/portal', label: '学生首页', icon: 'home' },
    { to: '/portal/schedule', label: '我的日程', icon: 'calendar' },
    { to: '/portal/learning', label: '学习与课表', icon: 'book' },
    { to: '/portal/affairs', label: '我的办事', icon: 'clipboard', aliases: ['/portal/leave', '/portal/certificates'] },
  )
  if (can('reservation:apply')) personal.push({ to: '/portal/reservations', label: '场地借用', icon: 'reservation' })
  if (can('housing:read')) personal.push({ to: '/housing/mine', label: '住宿与调宿', icon: 'bed', aliases: ['/housing/transfer'] })
  if (can('repair:apply')) personal.push({ to: '/repairs/mine', label: '校园报修', icon: 'wrench' })
  if (!student || staff) work.push({ to: '/workbench', label: '岗位工作台', icon: 'home' })
  const workEntries: [string, string, string, string][] = [
    ['teaching:manage', '/teaching', '教学班与资料', 'book'],
    ['academic:manage', '/academic', '教务信息维护', 'notebook'],
    ['leave:review', '/student-affairs/leave', '班级请假处理', 'check'],
    ['certificate:review', '/student-affairs/certificates', '学院证明处理', 'file'],
    ['reservation:review', '/student-affairs/reservations', '借用审批', 'reservation'],
    ['housing:review', '/student-affairs/housing', '学院调宿初审', 'bed'],
    ['housing:assign', '/housing/assignments', '住宿办理', 'bed'],
    ['repair:dispatch', '/logistics/repairs/dispatch', '后勤分诊派单', 'wrench'],
    ['repair:dispatch', '/logistics/repairs/statistics', '报修统计', 'chart'],
    ['repair:work', '/logistics/repairs/tasks', '我的维修任务', 'wrench'],
  ]
  for (const [permission, to, label, icon] of workEntries) if (can(permission)) work.push({ to, label, icon })
  if (can('repair:dispatch') || can('repair:work')) work.push({ to: '/logistics/repair-responsibilities', label: '我的负责范围', icon: 'users' })
  if (permissions.some(permission => permission.startsWith('content:'))) work.push({ to: '/admin/content', label: '官网内容管理', icon: 'news' })
  if (can('foundation:manage')) management.push(
    { to: '/admin/foundation', label: '账号与组织', icon: 'users' },
    { to: '/admin/resources', label: '教室资源', icon: 'building' },
    { to: '/admin/counselors', label: '辅导员负责班级', icon: 'users' },
  )
  if (can('repair:configure')) management.push({ to: '/admin/repair-settings', label: '报修配置', icon: 'wrench' })
  if (can('housing:configure')) management.push({ to: '/admin/housing', label: '宿舍资源', icon: 'bed' })
  return [
    { id: 'personal', label: '我的校园', items: personal },
    { id: 'work', label: '岗位办理', items: work },
    { id: 'management', label: '基础管理', items: management },
    { id: 'account', label: '账号服务', items: [
      { to: student ? '/portal/notices' : '/notices', label: '消息通知', icon: 'bell' },
      { to: '/profile', label: '个人资料', icon: 'user' },
    ] },
  ].filter(group => group.items.length)
}

export function currentPortalNavigation(groups: PortalNavGroup[], path: string) {
  let selected: { group: PortalNavGroup; item: PortalNavItem } | undefined
  let specificity = -1
  for (const group of groups) for (const item of group.items) {
    for (const target of [item.to, ...(item.aliases ?? [])]) {
      if ((path === target || path.startsWith(target + '/')) && target.length > specificity) {
        selected = { group, item }
        specificity = target.length
      }
    }
  }
  return selected
}
