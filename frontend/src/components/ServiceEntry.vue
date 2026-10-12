<script setup lang="ts">
import type { Component } from 'vue'
import { ArrowUpRight } from '@lucide/vue'

defineProps<{ to: string; title: string; description: string; icon: Component }>()
</script>

<template>
  <RouterLink :to="to" class="service-entry">
    <span class="service-entry-icon"><component :is="icon" :size="22" aria-hidden="true" /></span>
    <span class="service-entry-copy"><strong>{{ title }}</strong><span>{{ description }}</span></span>
    <ArrowUpRight class="service-entry-arrow" :size="16" aria-hidden="true" />
  </RouterLink>
</template>
