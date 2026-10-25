<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../lib/api'
import { newPasswordError } from '../lib/passwordPolicy'
import type { ManagedAccount } from '../foundation'
const props = defineProps<{ account: ManagedAccount }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const busy = ref(false), error = ref('')
const form = reactive({ password: '', confirm: '', reason: '' })
function close() { if (!busy.value) emit('close') }
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => { dialog.value?.close(); form.password = ''; form.confirm = ''; form.reason = '' })
async function submit() {
  if (busy.value) return
  error.value = newPasswordError(form.password, form.confirm)
  if (error.value || !form.reason.trim()) { error.value ||= '请填写重置原因'; return }
  busy.value = true
  try {
    await api(`/admin/foundation/users/${props.account.id}/password`, { method: 'POST', body: JSON.stringify({ version: props.account.version, newPassword: form.password, reason: form.reason.trim() }) })
    form.password = ''; form.confirm = ''; emit('saved')
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="password-reset-dialog" aria-labelledby="reset-password-heading" @cancel.prevent="close">
    <h2 id="reset-password-heading">重置登录密码</h2>
    <p><strong>{{ account.fullName }}</strong> · {{ account.username }}</p>
    <p class="muted">请先核验本人身份。重置后原有会话立即失效，该人员下次登录必须修改初始密码。系统不会回显或在修改记录中保存密码。</p>
    <form class="editor-form" @submit.prevent="submit"><fieldset :disabled="busy" class="reset-fields">
      <label>新的临时密码<input v-model="form.password" type="password" autocomplete="new-password" required minlength="10" maxlength="128" /></label>
      <label>确认临时密码<input v-model="form.confirm" type="password" autocomplete="new-password" required minlength="10" maxlength="128" /></label>
      <label>重置原因<textarea v-model="form.reason" required maxlength="200" rows="2" placeholder="填写身份核验和重置原因，不要在此填写密码" /></label>
    </fieldset><p v-if="error" class="alert" role="alert">{{ error }}</p>
      <div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在重置…' : '确认重置并使旧会话失效' }}</button><button type="button" class="button compact" :disabled="busy" @click="close">取消</button></div>
    </form>
  </dialog>
</template>
<style scoped>
.password-reset-dialog { width: min(540px, calc(100vw - 32px)); max-height: calc(100dvh - 40px); overflow: auto; border: 1px solid #dfe5ec; border-radius: 12px; padding: 26px; color: var(--ink, #203650); }
.password-reset-dialog::backdrop { background: rgb(20 35 55 / 40%); }
.reset-fields { border: 0; padding: 0; margin: 0; display: grid; gap: 14px; min-width: 0; }
</style>
