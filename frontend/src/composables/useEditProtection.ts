import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { currentUser } from '../stores/auth'

export function useEditProtection(busy: Ref<boolean>, dirty: Ref<boolean>, dialog: Ref<HTMLDialogElement | null>, cancelButton: Ref<HTMLButtonElement | null>) {
  const confirmMessage = ref(''), confirmKind = ref('leave'), dialogComment = ref('')
  let resolveConfirmation: ((value: boolean) => void) | null = null
  function finish(value: boolean) {
    dialog.value?.close()
    const resolve = resolveConfirmation; resolveConfirmation = null; resolve?.(value)
  }
  async function ask(message: string, kind = 'leave') {
    finish(false)
    confirmMessage.value = message; confirmKind.value = kind; dialogComment.value = ''
    return new Promise<boolean>(resolve => {
      resolveConfirmation = resolve
      void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() })
    })
  }
  async function allowLeave() {
    if (!currentUser.value) return true
    if (busy.value) return false
    return !dirty.value || await ask('还有未保存的文字或确认信息。离开会放弃这些输入，请先保存或确认放弃。')
  }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' }
  }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); finish(false) })
  onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
  return { confirmMessage, confirmKind, dialogComment, ask, finish, allowLeave }
}
