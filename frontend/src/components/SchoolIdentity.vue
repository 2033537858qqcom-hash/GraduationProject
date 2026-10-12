<script setup lang="ts">
import { useId } from 'vue'
import wordmark from '../../../assets/brand/zzuit-wordmark-white.png'

const inkId = `school-identity-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
</script>

<template>
  <!-- The original official monochrome artwork is retained; only its display ink follows the page. -->
  <svg class="school-identity" viewBox="131 21 2305 577" role="img" aria-label="郑州工业应用技术学院校徽与校名" focusable="false">
    <defs>
      <filter :id="inkId" color-interpolation-filters="sRGB">
        <feFlood flood-color="currentColor" />
        <feComposite in2="SourceAlpha" operator="in" />
      </filter>
    </defs>
    <image :href="wordmark" width="2634" height="647" :filter="`url(#${inkId})`" />
  </svg>
</template>

<style scoped>
.school-identity { display: block; width: 100%; height: auto; aspect-ratio: 2305 / 577; }
</style>
