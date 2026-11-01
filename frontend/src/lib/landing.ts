import type { Profile } from '../types'

/**
 * 登录成功（或已登录访问 /login）后的落点：
 * 强制改密 → 管理底座 → 学生门户 → 内容岗位 → 岗位工作台兜底。
 * 纯函数，便于在 Node 测试中直接断言各角色落点，不依赖浏览器与网络。
 */
export function landingPage(profile: Profile | null | undefined): string {
  if (profile?.passwordChangeRequired) return '/account/password'
  if (profile?.permissions.includes('foundation:manage')) return '/workbench'
  if (profile?.permissions.includes('portal:read')) return '/portal'
  if (profile?.permissions.some(permission => permission.startsWith('content:'))) return '/admin/content'
  return '/workbench'
}
