<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { KeyRound } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { newPasswordError } from '../lib/passwordPolicy'
import { clearSession } from '../stores/auth'
const router = useRouter()
const form = reactive({ current: '', next: '', confirm: '' })
const busy = ref(false), error = ref('')
function clear() { form.current = ''; form.next = ''; form.confirm = '' }
onBeforeUnmount(clear)
async function submit() {
  if (busy.value) return
  error.value = newPasswordError(form.next, form.confirm)
  if (error.value) return
  busy.value = true
  try {
    await api<void>('/auth/password', { method: 'POST', body: JSON.stringify({ currentPassword: form.current, newPassword: form.next }) })
    clear(); clearSession()
    await router.replace({ path: '/login', query: { passwordChanged: '1' } })
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>
<template>
  <section class="password-section" aria-labelledby="account-password-heading">
    <h2 id="account-password-heading"><KeyRound :size="18" aria-hidden="true" />修改登录密码</h2>
    <p class="muted">至少十位，包含字母和数字，不超过 72 个 UTF-8 字节。修改后需要重新登录，其他设备上的旧会话也会失效。</p>
    <form class="editor-form" @submit.prevent="submit">
      <fieldset :disabled="busy" class="password-fields">
        <label>当前密码<input v-model="form.current" type="password" autocomplete="current-password" required maxlength="128" /></label>
        <label>新密码<input v-model="form.next" type="password" autocomplete="new-password" required minlength="10" maxlength="128" /></label>
        <label>确认新密码<input v-model="form.confirm" type="password" autocomplete="new-password" required minlength="10" maxlength="128" /></label>
      </fieldset>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在修改…' : '修改密码并重新登录' }}</button></div>
    </form>
  </section>
</template>
<style scoped>
.password-section { border-top: 1px solid var(--border, #dfe5ec); margin-top: 24px; padding-top: 24px; }
.password-section h2 { display: flex; align-items: center; gap: 8px; font-size: 18px; }
.password-fields { border: 0; padding: 0; margin: 0; display: grid; gap: 14px; max-width: 480px; min-width: 0; }
</style>
