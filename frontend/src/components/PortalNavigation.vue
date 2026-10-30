<script setup lang="ts">
import type { Component } from 'vue'
import { BedDouble, Bell, BookOpen, Building2, CalendarCheck, CalendarDays, ChartColumn, ClipboardCheck, ClipboardList, FileCheck2, House, NotebookPen, Newspaper, UserRound, Users, Wrench } from '@lucide/vue'
import type { PortalNavGroup } from '../lib/portalNavigation'

defineProps<{ groups: PortalNavGroup[]; active?: string; label: string }>()
const emit = defineEmits<{ navigate: [] }>()
const icons: Record<string, Component> = { home: House, calendar: CalendarDays, book: BookOpen, clipboard: ClipboardList, reservation: CalendarCheck, bed: BedDouble, wrench: Wrench, notebook: NotebookPen, check: ClipboardCheck, file: FileCheck2, chart: ChartColumn, users: Users, news: Newspaper, building: Building2, bell: Bell, user: UserRound }
function navigate(event: MouseEvent) {
  if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) emit('navigate')
}
</script>

<template>
  <nav class="portal-grouped-nav" :aria-label="label">
    <div v-for="group in groups" :key="group.id" class="portal-nav-group">
      <p>{{ group.label }}</p>
      <RouterLink v-for="item in group.items" :key="item.to" :to="item.to" :aria-current="active === item.to ? 'page' : undefined" @click="navigate"><component :is="icons[item.icon]" :size="17" aria-hidden="true" /><span>{{ item.label }}</span></RouterLink>
    </div>
  </nav>
</template>
