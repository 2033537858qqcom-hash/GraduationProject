import { readonly, ref } from 'vue'
import { readStoredItem, writeStoredItem } from './safeStorage.ts'

export type CampusAtmosphere = 'day' | 'sunny'

export const CAMPUS_ATMOSPHERE_KEY = 'campus-atmosphere'

const validModes: readonly CampusAtmosphere[] = ['day', 'sunny']
const atmosphere = ref<CampusAtmosphere>('day')
export const campusAtmosphere = readonly(atmosphere)

let consumers = 0

function normalizeAtmosphere(value: string | null): CampusAtmosphere {
  return validModes.includes(value as CampusAtmosphere) ? (value as CampusAtmosphere) : 'day'
}

function syncRootAttribute() {
  if (typeof document === 'undefined') return
  if (consumers > 0) {
    document.documentElement.setAttribute('data-campus-atmosphere', atmosphere.value)
  } else {
    document.documentElement.removeAttribute('data-campus-atmosphere')
  }
}

function storageChanged(event: StorageEvent) {
  if (event.key === CAMPUS_ATMOSPHERE_KEY || event.key === null) {
    atmosphere.value = normalizeAtmosphere(event.newValue)
    syncRootAttribute()
  }
}

export function setCampusAtmosphere(value: CampusAtmosphere) {
  atmosphere.value = value
  writeStoredItem(CAMPUS_ATMOSPHERE_KEY, value)
  syncRootAttribute()
}

/** Shared atmosphere state for portal and login views without touching public site pages. */
export function initializeCampusAtmosphere() {
  if (typeof window === 'undefined') return () => {}
  consumers++
  if (consumers === 1) {
    atmosphere.value = normalizeAtmosphere(readStoredItem(CAMPUS_ATMOSPHERE_KEY))
    window.addEventListener('storage', storageChanged)
  }
  syncRootAttribute()
  let released = false
  return () => {
    if (released) return
    released = true
    if (--consumers === 0) {
      window.removeEventListener('storage', storageChanged)
    }
    syncRootAttribute()
  }
}
