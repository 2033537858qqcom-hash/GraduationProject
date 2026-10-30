<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import type { LakeLightEffect } from '../visuals/createLakeLight'
import { publicReducedMotion } from '../lib/publicMotion'

const props = defineProps<{ imageAspect: number }>()
const surface = ref<HTMLElement>()
const state = ref('static')
let query: MediaQueryList | undefined
let observer: IntersectionObserver | undefined
let effect: LakeLightEffect | undefined
let mounted = false
let visible = false
let loading = false
let failed = false

async function sync() {
  if (!mounted) return
  if (!query?.matches || publicReducedMotion.value) {
    effect?.dispose()
    effect = undefined
    state.value = 'static'
    return
  }
  if (failed) return
  if (!visible || document.hidden) {
    effect?.setRunning(false)
    state.value = effect ? 'paused' : 'static'
    return
  }
  if (effect) {
    effect.setRunning(true)
    state.value = 'active'
    return
  }
  if (loading) return
  loading = true
  try {
    // Three.js is fetched only when this photograph can actually animate.
    const { createLakeLight } = await import('../visuals/createLakeLight')
    if (!mounted || !query.matches || publicReducedMotion.value || !visible || document.hidden || !surface.value) return
    effect = createLakeLight(surface.value, props.imageAspect, () => {
      failed = true
      state.value = 'unavailable'
    })
    effect.setRunning(true)
    state.value = 'active'
  } catch {
    failed = true
    state.value = 'unavailable'
    // The original photograph remains visible underneath, including without WebGL.
  } finally { loading = false }
}

watch(publicReducedMotion, sync)
onMounted(() => {
  mounted = true
  query = window.matchMedia('(min-width: 900px) and (hover: hover) and (prefers-reduced-motion: no-preference)')
  query.addEventListener('change', sync)
  document.addEventListener('visibilitychange', sync)
  observer = new IntersectionObserver(entries => {
    visible = entries.some(entry => entry.isIntersecting)
    void sync()
  }, { threshold: 0 })
  if (surface.value) observer.observe(surface.value)
})
onUnmounted(() => {
  mounted = false
  observer?.disconnect()
  query?.removeEventListener('change', sync)
  document.removeEventListener('visibilitychange', sync)
  effect?.dispose()
})
</script>

<template>
  <span ref="surface" class="lake-light" :data-state="state" aria-hidden="true"></span>
</template>

<style scoped>
.lake-light { position: absolute; inset: 0; display: block; pointer-events: none; }
.lake-light :deep(canvas) { display: block; width: 100%; height: 100%; }
</style>
