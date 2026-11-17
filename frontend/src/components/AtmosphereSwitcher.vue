<script setup lang="ts">
import { Sun, SunMedium } from '@lucide/vue';
import { campusAtmosphere, setCampusAtmosphere, type CampusAtmosphere } from '../lib/campusTheme';

const options: { key: CampusAtmosphere; label: string; title: string }[] = [
  { key: 'day', label: '昼', title: 'Day · 静态宣纸昼色' },
  { key: 'sunny', label: '晴', title: 'Sunny · 动态树影晴光' },
];
</script>

<template>
  <div class="atmosphere-switcher" role="radiogroup" aria-label="界面昼晴主题">
    <button
      v-for="item in options"
      :key="item.key"
      type="button"
      class="atmosphere-btn"
      :class="{ active: campusAtmosphere === item.key }"
      role="radio"
      :aria-checked="campusAtmosphere === item.key"
      :title="item.title"
      @click="setCampusAtmosphere(item.key)"
    >
      <Sun v-if="item.key === 'day'" :size="12" aria-hidden="true" />
      <SunMedium v-else :size="12" aria-hidden="true" />
      <span>{{ item.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.atmosphere-switcher {
  display: inline-flex;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-xs);
  background: var(--color-surface);
  overflow: hidden;
  flex-shrink: 0;
}

.atmosphere-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 28px;
  padding: 4px 10px;
  border: 0;
  border-right: 1px solid var(--color-border-subtle);
  border-radius: 0;
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--text-meta);
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color var(--motion-fast) ease,
    color var(--motion-fast) ease;
}

.atmosphere-btn:last-child {
  border-right: 0;
}

.atmosphere-btn:hover {
  color: var(--color-ink);
  background: var(--color-surface-soft);
}

.atmosphere-btn.active {
  background: var(--color-brand);
  color: var(--color-on-brand, var(--color-white));
  font-weight: 600;
}
</style>
