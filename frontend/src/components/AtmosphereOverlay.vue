<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { CampusAtmosphere } from '../lib/campusTheme';

const props = withDefaults(
  defineProps<{
    atmosphere: CampusAtmosphere;
    reducedMotion?: boolean;
    surface?: 'login' | 'portal';
  }>(),
  {
    reducedMotion: false,
    surface: 'portal',
  },
);

const leavesVideo = ref<HTMLVideoElement>();

function syncVideoState() {
  const video = leavesVideo.value;
  if (!video) return;
  if (props.atmosphere === 'sunny' && !props.reducedMotion) {
    video.play().catch(() => {});
  } else {
    video.pause();
    video.currentTime = 0;
  }
}

watch(
  () => [props.atmosphere, props.reducedMotion] as const,
  () => syncVideoState(),
);

onMounted(() => {
  syncVideoState();
});

onBeforeUnmount(() => {
  const video = leavesVideo.value;
  if (video) {
    video.pause();
    video.currentTime = 0;
  }
});
</script>

<template>
  <div class="atmosphere-overlays" :data-surface="surface" :data-active-atmosphere="atmosphere" aria-hidden="true">
    <!-- Sunny mode: soft dappled leaf shadows matching theme-switch.pages.dev -->
    <div id="leaves-overlay" class="atmosphere-layer atmosphere-leaves" :class="{ active: atmosphere === 'sunny' }">
      <video
        ref="leavesVideo"
        class="atmosphere-leaves-video"
        src="/media/leaves.mp4"
        autoplay
        muted
        loop
        playsinline
      />
    </div>
  </div>
</template>

<style scoped>
.atmosphere-overlays {
  display: contents;
}

.atmosphere-layer {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 999;
  opacity: 0;
  transition: opacity 700ms cubic-bezier(0.23, 1, 0.32, 1);
}

.atmosphere-layer.active {
  opacity: 1;
}

.atmosphere-leaves {
  mix-blend-mode: multiply;
}

.atmosphere-leaves-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: brightness(1.06) contrast(1.1);
}
</style>
