<script setup lang="ts">
import CulturalOrnament from './CulturalOrnament.vue'

withDefaults(defineProps<{ bottom?: boolean }>(), { bottom: true })
</script>

<template>
  <div class="cultural-frame" aria-hidden="true">
    <CulturalOrnament motif="corner" class="frame-corner frame-top" />
    <CulturalOrnament v-if="bottom" motif="corner" class="frame-corner frame-bottom" />
    <span class="frame-rail frame-rail-left"></span>
    <span class="frame-rail frame-rail-right"></span>
  </div>
</template>

<style scoped>
.cultural-frame {
  position: absolute;
  inset: 0;
  pointer-events: none;
  user-select: none;
  --frame-default-size: 48px;
  --frame-default-edge: clamp(12px,1.5vw,28px);
  --frame-default-top: 76px;
  --frame-default-bottom: 22px;
  --frame-default-opacity: .3;
  --culture-opacity: var(--frame-opacity,var(--frame-default-opacity));
}
.frame-corner { width: var(--frame-size,var(--frame-default-size)); }
.frame-top { top: var(--frame-top,var(--frame-default-top)); left: var(--frame-edge,var(--frame-default-edge)); }
.frame-bottom { right: var(--frame-edge,var(--frame-default-edge)); bottom: var(--frame-bottom,var(--frame-default-bottom)); transform: rotate(180deg); }
.frame-rail {
  position: absolute;
  top: calc(var(--frame-top,var(--frame-default-top)) + var(--frame-size,var(--frame-default-size)) + 35px);
  bottom: calc(var(--frame-bottom,var(--frame-default-bottom)) + var(--frame-size,var(--frame-default-size)) + 35px);
  width: 1px;
  background: linear-gradient(transparent,var(--culture-color,var(--brand)) 35%,transparent 80%);
  opacity: .13;
}
.frame-rail-left { left: calc(var(--frame-edge,var(--frame-default-edge)) + var(--frame-size,var(--frame-default-size)) * .1); }
.frame-rail-right { right: calc(var(--frame-edge,var(--frame-default-edge)) + var(--frame-size,var(--frame-default-size)) * .1); }
@media (max-width: 760px) {
  .cultural-frame { --frame-default-size: 24px; --frame-default-edge: 4px; --frame-default-top: 10px; --frame-default-bottom: 12px; --frame-default-opacity: .23; }
  .frame-rail { display: none; }
}
</style>
