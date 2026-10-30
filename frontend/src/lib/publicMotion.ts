import { computed, readonly, ref } from 'vue'

export const PUBLIC_MOTION_KEY = 'campus-public-motion'
type MotionPreference = 'auto' | 'reduce'
const preference = ref<MotionPreference>('auto')
const systemReduced = ref(false)
export const publicMotionPreference = readonly(preference)
export const publicReducedMotion = computed(() => preference.value === 'reduce' || systemReduced.value)
let consumers = 0
let query: MediaQueryList | undefined

function readPreference(value: string | null): MotionPreference {
  return value === 'reduce' ? 'reduce' : 'auto'
}
function systemChanged() { systemReduced.value = query?.matches ?? false }
function storageChanged(event: StorageEvent) {
  if (event.key === PUBLIC_MOTION_KEY || event.key === null) preference.value = readPreference(event.newValue)
}

export function setPublicMotionPreference(value: MotionPreference) {
  preference.value = value
  try { window.localStorage.setItem(PUBLIC_MOTION_KEY, value) } catch { /* This visit still respects the selection when storage is unavailable. */ }
}

/** One system/storage subscription for the public layout, including direct detail-page visits. */
export function initializePublicMotion() {
  if (typeof window === 'undefined') return () => {}
  consumers++
  if (consumers === 1) {
    try { preference.value = readPreference(window.localStorage.getItem(PUBLIC_MOTION_KEY)) } catch { preference.value = 'auto' }
    query = window.matchMedia('(prefers-reduced-motion: reduce)')
    systemChanged()
    query.addEventListener('change', systemChanged)
    window.addEventListener('storage', storageChanged)
  }
  let released = false
  return () => {
    if (released) return
    released = true
    if (--consumers === 0) {
      query?.removeEventListener('change', systemChanged)
      window.removeEventListener('storage', storageChanged)
      query = undefined
    }
  }
}
