import { ref } from 'vue'
import { api } from '../lib/api.ts'
import { currentUser } from './auth.ts'

export const unreadNoticeCount = ref(0)

export function setUnreadNoticeCount(count: number) {
  unreadNoticeCount.value = Math.max(0, Number.isFinite(count) ? Math.floor(count) : 0)
}

export function decrementUnreadNoticeCount() {
  setUnreadNoticeCount(unreadNoticeCount.value - 1)
}

export async function refreshUnreadNoticeCount() {
  if (!currentUser.value) {
    unreadNoticeCount.value = 0
    return
  }
  try {
    const result = await api<{ unreadCount?: number }>('/notices?page=1&pageSize=1')
    if (typeof result?.unreadCount === 'number') {
      setUnreadNoticeCount(result.unreadCount)
    }
  } catch {
    /* Notice polling failure stays quiet in top bar. */
  }
}
