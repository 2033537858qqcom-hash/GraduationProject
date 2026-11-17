<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { clearSession, currentUser } from './stores/auth';
import ToastContainer from './components/ToastContainer.vue';
import ConfirmDialog from './components/ConfirmDialog.vue';
import RouteProgress from './components/RouteProgress.vue';
import { publicReducedMotion } from './lib/publicMotion';

const router = useRouter();
function expired() {
  if (!currentUser.value) return;
  const redirect = router.currentRoute.value.fullPath;
  clearSession();
  void router.replace({ path: '/login', query: { redirect, expired: '1' } });
}
onMounted(() => window.addEventListener('campus:unauthorized', expired));
onUnmounted(() => window.removeEventListener('campus:unauthorized', expired));
</script>

<template>
  <RouteProgress />
  <ToastContainer />
  <ConfirmDialog />
  <RouterView v-slot="{ Component }">
    <Transition :name="publicReducedMotion ? '' : 'campus-shell'" mode="out-in">
      <component :is="Component" />
    </Transition>
  </RouterView>
</template>

<style scoped>
.campus-shell-enter-active,
.campus-shell-leave-active {
  transition: opacity 180ms var(--ease-campus);
}
.campus-shell-enter-from,
.campus-shell-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .campus-shell-enter-active,
  .campus-shell-leave-active {
    transition: none;
  }
}
</style>
