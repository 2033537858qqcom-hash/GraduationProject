<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

// A thin top bar shown only when a lazily loaded page takes noticeably long to arrive.
const router = useRouter();
const active = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;

const stopBefore = router.beforeEach(() => {
  clearTimeout(timer);
  timer = setTimeout(() => (active.value = true), 150);
});
function done() {
  clearTimeout(timer);
  active.value = false;
}
const stopAfter = router.afterEach(done);
const stopError = router.onError(done);

onUnmounted(() => {
  done();
  stopBefore();
  stopAfter();
  stopError();
});
</script>

<template>
  <div
    class="route-progress"
    :class="{ active }"
    role="progressbar"
    aria-label="页面加载中"
    :aria-hidden="!active"
  ></div>
</template>

<style scoped>
.route-progress {
  position: fixed;
  inset: 0 0 auto;
  z-index: 10000;
  height: 2px;
  background: linear-gradient(90deg, var(--color-brand), var(--color-gold));
  transform: scaleX(0);
  transform-origin: left;
  opacity: 0;
  pointer-events: none;
}
.route-progress.active {
  opacity: 1;
  animation: route-progress 2.4s var(--ease-campus) forwards;
}

@keyframes route-progress {
  to {
    transform: scaleX(0.85);
  }
}

@media (prefers-reduced-motion: reduce) {
  .route-progress.active {
    animation: none;
    transform: scaleX(1);
  }
}
</style>
