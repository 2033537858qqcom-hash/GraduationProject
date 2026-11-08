import { shallowRef } from 'vue'

export interface ConfirmOptions {
  title?: string
  confirmText?: string
  cancelText?: string
  /** Visually emphasise a destructive action. */
  danger?: boolean
}

export interface ConfirmRequest extends Required<Omit<ConfirmOptions, 'danger'>> {
  message: string
  danger: boolean
  resolve: (value: boolean) => void
}

/** The request currently shown by the mounted <ConfirmDialog> host. */
export const confirmRequest = shallowRef<ConfirmRequest | null>(null)
let hostMounted = false

export function registerConfirmHost(mounted: boolean) {
  hostMounted = mounted
  if (!mounted) settleConfirm(false)
}

export function settleConfirm(value: boolean) {
  const request = confirmRequest.value
  confirmRequest.value = null
  request?.resolve(value)
}

/**
 * Branded replacement for `window.confirm`. Resolves to `true` when the user confirms.
 * Falls back to the native dialog when no host is mounted (e.g. isolated component tests).
 */
export function confirmDialog(message: string, options: ConfirmOptions = {}): Promise<boolean> {
  if (!hostMounted) return Promise.resolve(window.confirm(message))
  settleConfirm(false)
  return new Promise(resolve => {
    confirmRequest.value = {
      message,
      title: options.title ?? '请确认',
      confirmText: options.confirmText ?? '确定',
      cancelText: options.cancelText ?? '取消',
      danger: options.danger ?? false,
      resolve,
    }
  })
}
