<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

// A thin top bar shown only when a lazily loaded page takes noticeably long to arrive.
const router = useRouter();
const phase = ref<'idle' | 'loading' | 'finishing'>('idle');
let delayTimer: ReturnType<typeof setTimeout> | undefined;
let finishTimer: ReturnType<typeof setTimeout> | undefined;

const stopBefore = router.beforeEach(() => {
  clearTimeout(delayTimer);
  clearTimeout(finishTimer);
  phase.value = 'idle';
  delayTimer = setTimeout(() => {
    phase.value = 'loading';
  }, 150);
});
function done() {
  clearTimeout(delayTimer);
  if (phase.value !== 'loading') {
    phase.value = 'idle';
    return;
  }
  phase.value = 'finishing';
  clearTimeout(finishTimer);
  finishTimer = setTimeout(() => {
    phase.value = 'idle';
  }, 240);
}
const stopAfter = router.afterEach(done);
const stopError = router.onError(done);

onUnmounted(() => {
  clearTimeout(delayTimer);
  clearTimeout(finishTimer);
  phase.value = 'idle';
  stopBefore();
  stopAfter();
  stopError();
});
</script>

<template>
  <div
    class="route-progress"
    :class="{ active: phase === 'loading', finishing: phase === 'finishing' }"
    role="progressbar"
    aria-label="页面加载中"
    :aria-hidden="phase === 'idle'"
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
.route-progress.finishing {
  opacity: 0;
  transform: scaleX(1);
  transition:
    transform 140ms var(--ease-campus),
    opacity 180ms ease 60ms;
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
  .route-progress.finishing {
    transition: none;
  }
}
</style>
