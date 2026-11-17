<script setup lang="ts">
export type AvatarMood = 'idle' | 'greeting' | 'working' | 'reply';

withDefaults(
  defineProps<{
    mood?: AvatarMood;
    size?: 'sm' | 'md' | 'lg';
    variant?: 'on-dark' | 'on-light';
    reducedMotion?: boolean;
  }>(),
  {
    mood: 'idle',
    size: 'sm',
    variant: 'on-dark',
    reducedMotion: false,
  },
);
</script>

<template>
  <span
    class="assistant-mark"
    :class="[`is-${mood}`, `size-${size}`, `variant-${variant}`, { 'is-reduced': reducedMotion }]"
    aria-hidden="true"
  >
    <svg class="assistant-mark-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <!-- Minimalist seal / folio corner frame -->
      <path
        class="mark-frame"
        d="M12.5 4.75H7.25C5.87 4.75 4.75 5.87 4.75 7.25V16.75C4.75 18.13 5.87 19.25 7.25 19.25H16.75C18.13 19.25 19.25 18.13 19.25 16.75V11.5"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <!-- Editorial folio lines -->
      <path class="mark-lines" d="M8.5 11.25H13.25M8.5 14.75H15.25" stroke-width="1.5" stroke-linecap="round" />
      <!-- Restraint 4-point astral star -->
      <path
        class="mark-star"
        d="M17.5 2.75L18.32 5.43L21 6.25L18.32 7.07L17.5 9.75L16.68 7.07L14 6.25L16.68 5.43L17.5 2.75Z"
      />
    </svg>
  </span>
</template>

<style scoped>
.assistant-mark {
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
  line-height: 0;
}

.assistant-mark.size-sm {
  width: 20px;
  height: 20px;
}

.assistant-mark.size-md {
  width: 22px;
  height: 22px;
}

.assistant-mark.size-lg {
  width: 26px;
  height: 26px;
}

.assistant-mark-svg {
  width: 100%;
  height: 100%;
  overflow: visible;
}

.variant-on-dark .mark-frame,
.variant-on-dark .mark-lines {
  stroke: var(--color-white);
}

.variant-on-dark .mark-lines {
  opacity: 0.82;
}

.variant-on-dark .mark-star {
  fill: var(--color-gold);
}

.variant-on-light .mark-frame,
.variant-on-light .mark-lines {
  stroke: var(--color-brand);
}

.variant-on-light .mark-lines {
  opacity: 0.72;
}

.variant-on-light .mark-star {
  fill: var(--color-gold);
}

.mark-star {
  transform-box: fill-box;
  transform-origin: center;
  transition: transform 0.35s var(--ease-campus);
}

.assistant-mark.is-working:not(.is-reduced) .mark-star {
  animation: mark-star-pulse 1.8s ease-in-out infinite;
}

@media (prefers-reduced-motion: reduce) {
  .mark-star {
    animation: none;
    transition: none;
  }
}

@keyframes mark-star-pulse {
  0%,
  100% {
    transform: scale(1) rotate(0deg);
    opacity: 0.9;
  }
  50% {
    transform: scale(1.16) rotate(45deg);
    opacity: 1;
  }
}
</style>
