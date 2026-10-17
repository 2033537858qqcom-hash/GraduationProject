import { ref } from 'vue'

export type ToastType = 'success' | 'error' | 'info' | 'warning'

export interface ToastItem {
  id: number
  type: ToastType
  message: string
  duration: number
}

const toasts = ref<ToastItem[]>([])
let counter = 0

function show(message: string, type: ToastType = 'info', duration = 3200) {
  const id = ++counter
  const item: ToastItem = { id, type, message, duration }
  toasts.value.push(item)

  if (duration > 0) {
    setTimeout(() => {
      remove(id)
    }, duration)
  }
  return id
}

function remove(id: number) {
  const index = toasts.value.findIndex(t => t.id === id)
  if (index !== -1) {
    toasts.value.splice(index, 1)
  }
}

export function useToast() {
  return {
    toasts,
    show,
    remove,
    success: (msg: string, duration = 3200) => show(msg, 'success', duration),
    error: (msg: string, duration = 4200) => show(msg, 'error', duration),
    info: (msg: string, duration = 3200) => show(msg, 'info', duration),
    warning: (msg: string, duration = 3600) => show(msg, 'warning', duration),
  }
}
