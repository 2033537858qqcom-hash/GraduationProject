// 统一的 localStorage 访问封装：隐私模式、被禁用的站点数据或配额耗尽时都不会抛出异常。

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

export function readStoredItem(key: string): string | null {
  try {
    return storage()?.getItem(key) ?? null
  } catch {
    return null
  }
}

export function writeStoredItem(key: string, value: string): boolean {
  try {
    storage()?.setItem(key, value)
    return true
  } catch {
    return false
  }
}
