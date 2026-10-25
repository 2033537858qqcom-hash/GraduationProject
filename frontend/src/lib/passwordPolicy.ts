export function newPasswordError(password: string, confirmation: string): string {
  if (password.length < 10 || !/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) return '新密码至少十位，并包含字母和数字'
  if (new TextEncoder().encode(password).length > 72) return '新密码不能超过 72 个 UTF-8 字节'
  if (password !== confirmation) return '两次输入的新密码不一致'
  return ''
}
