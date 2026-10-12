import { ref } from 'vue'
import { api, ApiError, resetCsrf } from '../lib/api'
import type { Profile } from '../types'
import { clearTeachingDrafts } from '../lib/teachingDrafts'

export const currentUser = ref<Profile | null>(null)
let initialized = false
let initialization: Promise<void> | null = null

export function clearSession() {
  clearTeachingDrafts(currentUser.value?.id)
  currentUser.value = null
  initialized = true
  resetCsrf()
}

export async function restoreSession(refresh = false) {
  if (initialized && (!refresh || !currentUser.value)) return
  if (!initialization) {
    initialization = (async () => {
      try {
        currentUser.value = await api<Profile>('/me')
        initialized = true
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          clearSession()
        } else throw error
      }
    })().finally(() => { initialization = null })
  }
  await initialization
}

export async function login(username: string, password: string) {
  currentUser.value = await api<Profile>('/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  })
  initialized = true
  resetCsrf()
}

export async function logout() {
  await api<void>('/auth/logout', { method: 'POST' })
  clearSession()
}

export function landingPage(profile = currentUser.value) {
  if (profile?.permissions.includes('foundation:manage')) return '/workbench'
  if (profile?.permissions.includes('portal:read')) return '/portal'
  if (profile?.permissions.some(permission => permission.startsWith('content:'))) return '/admin/content'
  return '/workbench'
}
