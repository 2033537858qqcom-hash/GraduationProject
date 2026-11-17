import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, type RouteLocationNormalized } from 'vue-router'
import { currentUser } from '../stores/auth.ts'
import { confirmDialog } from './useConfirm.ts'

function bindBeforeUnload(dirty: Ref<boolean>, busy: Ref<boolean>) {
  function beforeUnload(event: BeforeUnloadEvent) {
    if (dirty.value || busy.value) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
}

export interface LeaveGuardOptions {
  title?: string
  routeUpdate?: boolean | ((to: RouteLocationNormalized, from: RouteLocationNormalized) => boolean)
  onBusy?: () => void
  requireAuth?: boolean
}

/**
 * Warn before leaving a dirty or in-flight form via browser unload or Vue Router navigation.
 */
export function useLeaveGuard(
  busy: Ref<boolean>,
  dirty: Ref<boolean>,
  message: string,
  options: LeaveGuardOptions = {},
) {
  async function allowLeave(): Promise<boolean> {
    if (options.requireAuth && !currentUser.value) return true
    if (busy.value) {
      options.onBusy?.()
      return false
    }
    return !dirty.value || (await confirmDialog(message, options.title ? { title: options.title } : undefined))
  }
  bindBeforeUnload(dirty, busy)
  onBeforeRouteLeave(allowLeave)
  if (options.routeUpdate) {
    onBeforeRouteUpdate((to, from) => {
      if (typeof options.routeUpdate === 'function' && options.routeUpdate(to, from)) return true
      return allowLeave()
    })
  }
  return { allowLeave }
}

export function useEditProtection(
  busy: Ref<boolean>,
  dirty: Ref<boolean>,
  dialog: Ref<HTMLDialogElement | null>,
  cancelButton: Ref<HTMLButtonElement | null>,
) {
  const confirmMessage = ref(''),
    confirmKind = ref('leave'),
    dialogComment = ref('')
  let resolveConfirmation: ((value: boolean) => void) | null = null
  function finish(value: boolean) {
    dialog.value?.close()
    const resolve = resolveConfirmation
    resolveConfirmation = null
    resolve?.(value)
  }
  async function ask(message: string, kind = 'leave') {
    finish(false)
    confirmMessage.value = message
    confirmKind.value = kind
    dialogComment.value = ''
    return new Promise<boolean>(resolve => {
      resolveConfirmation = resolve
      void nextTick(() => {
        dialog.value?.showModal()
        cancelButton.value?.focus()
      })
    })
  }
  async function allowLeave() {
    if (!currentUser.value) return true
    if (busy.value) return false
    return !dirty.value || (await ask('还有未保存的文字或确认信息。离开会放弃这些输入，请先保存或确认放弃。'))
  }
  bindBeforeUnload(dirty, busy)
  onBeforeUnmount(() => finish(false))
  onBeforeRouteLeave(allowLeave)
  onBeforeRouteUpdate(allowLeave)
  return { confirmMessage, confirmKind, dialogComment, ask, finish, allowLeave }
}
