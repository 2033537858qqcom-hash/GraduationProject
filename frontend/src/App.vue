<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { clearSession, currentUser } from './stores/auth'
import ToastContainer from './components/ToastContainer.vue'

const router = useRouter()
function expired() {
  if (!currentUser.value) return
  const redirect = router.currentRoute.value.fullPath
  clearSession()
  void router.replace({ path: '/login', query: { redirect, expired: '1' } })
}
onMounted(() => window.addEventListener('campus:unauthorized', expired))
onUnmounted(() => window.removeEventListener('campus:unauthorized', expired))
</script>
<template>
  <ToastContainer />
  <RouterView />
</template>
