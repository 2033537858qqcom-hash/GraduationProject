<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import { landingPage, currentUser, restoreSession } from '../stores/auth'
import { safeRecoveryDestination } from '../lib/navigation'
import CulturalFrame from '../components/CulturalFrame.vue'
import CulturalOrnament from '../components/CulturalOrnament.vue'
const route = useRoute(), router = useRouter()
const reconnecting = ref(false), reconnectError = ref('')
const message = computed(() => route.path === '/forbidden'
  ? '当前账号没有访问此页面的权限。'
  : route.path === '/unavailable' ? '校园服务暂时无法连接。重新连接后，可以继续访问原页面。' : '你访问的页面不存在。')
async function reconnect() {
  if (reconnecting.value) return
  reconnecting.value = true
  reconnectError.value = ''
  try {
    await restoreSession(true)
    await router.replace(safeRecoveryDestination(route.query.redirect) ?? (currentUser.value ? landingPage() : '/login'))
  } catch {
    reconnectError.value = '服务仍未连接，请稍后重新尝试。'
  } finally { reconnecting.value = false }
}
</script>
<template>
  <div class="status-scene">
    <CulturalFrame /><CulturalOrnament motif="landscape" class="status-landscape" />
    <main class="status-page">
      <span class="eyebrow">CAMPUS SERVICES</span><h1>{{ route.meta.title }}</h1><p>{{ message }}</p>
      <p v-if="reconnectError" role="alert">{{ reconnectError }}</p>
      <div class="status-actions">
        <button v-if="route.path === '/unavailable'" class="button primary" :disabled="reconnecting" @click="reconnect"><RefreshCw :size="16" />{{ reconnecting ? '正在连接…' : '重新连接' }}</button>
        <RouterLink v-else class="button primary" :to="currentUser ? landingPage() : '/login'">{{ currentUser ? '个人门户' : '校园服务登录' }}</RouterLink>
        <RouterLink class="quiet-link" to="/">学校官网</RouterLink>
      </div>
    </main>
  </div>
</template>

<style scoped>
.status-actions { align-items: center; flex-wrap: wrap; }
.status-actions .button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
</style>
