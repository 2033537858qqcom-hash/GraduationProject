<script setup lang="ts">
import type { Component } from 'vue'
import { ArrowUpRight } from '@lucide/vue'

withDefaults(defineProps<{
  to: string
  title: string
  description: string
  icon: Component
  category?: string
  badge?: string | number
  badgeType?: 'primary' | 'warning' | 'success' | 'neutral' | 'danger'
  theme?: string
}>(), {
  category: '',
  badge: '',
  badgeType: 'neutral',
  theme: 'blue',
})
</script>

<template>
  <RouterLink :to="to" class="service-entry" :class="[`theme-${theme}`]">
    <div class="service-entry-top">
      <span class="service-entry-icon" :class="[`icon-theme-${theme}`]">
        <component :is="icon" :size="22" aria-hidden="true" />
      </span>
      <div v-if="category || badge" class="service-entry-meta">
        <span v-if="category" class="service-category-tag">{{ category }}</span>
        <span v-if="badge" class="service-badge" :class="[`badge-${badgeType}`]">{{ badge }}</span>
      </div>
      <span class="service-entry-arrow-wrap">
        <ArrowUpRight class="service-entry-arrow" :size="16" aria-hidden="true" />
      </span>
    </div>
    <div class="service-entry-copy">
      <strong>{{ title }}</strong>
      <span>{{ description }}</span>
    </div>
  </RouterLink>
</template>
