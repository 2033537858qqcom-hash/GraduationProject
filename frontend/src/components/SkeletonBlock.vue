<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'list' | 'cards' | 'split' | 'table' | 'schedule' | 'detail';
    rows?: number;
    label?: string;
  }>(),
  {
    variant: 'list',
    rows: 4,
    label: '正在读取内容…',
  },
);
</script>

<template>
  <div class="campus-skeleton" :class="`variant-${variant}`" role="status" :aria-label="label">
    <span class="sr-only">{{ label }}</span>

    <div v-if="variant === 'split'" class="skeleton-split">
      <div class="skeleton-side">
        <div v-for="n in rows" :key="n" class="skeleton-box skeleton-side-item"></div>
      </div>
      <div class="skeleton-card skeleton-detail-card">
        <div class="skeleton-box skeleton-line-short"></div>
        <div class="skeleton-box skeleton-line-title"></div>
        <div class="skeleton-box skeleton-line-medium"></div>
        <div class="skeleton-box skeleton-block-tall"></div>
      </div>
    </div>

    <div v-else-if="variant === 'cards'" class="skeleton-cards">
      <div v-for="n in rows" :key="n" class="skeleton-card">
        <div class="skeleton-card-head">
          <div class="skeleton-box skeleton-pill"></div>
          <div class="skeleton-box skeleton-line-tiny"></div>
        </div>
        <div class="skeleton-box skeleton-line-title"></div>
        <div class="skeleton-box skeleton-line-medium"></div>
        <div class="skeleton-box skeleton-line-short"></div>
      </div>
    </div>

    <div v-else-if="variant === 'table'" class="skeleton-table">
      <div class="skeleton-box skeleton-table-head"></div>
      <div v-for="n in rows" :key="n" class="skeleton-table-row">
        <div class="skeleton-box skeleton-line-medium"></div>
        <div class="skeleton-box skeleton-pill"></div>
        <div class="skeleton-box skeleton-line-short"></div>
      </div>
    </div>

    <div v-else-if="variant === 'schedule'" class="skeleton-schedule">
      <div v-for="n in rows" :key="n" class="skeleton-card skeleton-schedule-item">
        <div class="skeleton-box skeleton-date-badge"></div>
        <div class="skeleton-schedule-copy">
          <div class="skeleton-box skeleton-line-short"></div>
          <div class="skeleton-box skeleton-line-title"></div>
          <div class="skeleton-box skeleton-line-medium"></div>
        </div>
      </div>
    </div>

    <div v-else-if="variant === 'detail'" class="skeleton-detail-card">
      <div class="skeleton-box skeleton-line-short"></div>
      <div class="skeleton-box skeleton-line-title"></div>
      <div class="skeleton-box skeleton-line-medium"></div>
      <div class="skeleton-box skeleton-block-tall"></div>
    </div>

    <div v-else class="skeleton-list">
      <div v-for="n in rows" :key="n" class="skeleton-card skeleton-list-item">
        <div class="skeleton-list-main">
          <div class="skeleton-box skeleton-line-title"></div>
          <div class="skeleton-box skeleton-line-medium"></div>
        </div>
        <div class="skeleton-box skeleton-pill"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.campus-skeleton {
  width: 100%;
  min-width: 0;
}

.skeleton-split {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 20px;
}

.skeleton-side,
.skeleton-list,
.skeleton-schedule,
.skeleton-table {
  display: grid;
  gap: 12px;
}

.skeleton-side-item {
  height: 76px;
  border-radius: var(--radius-md);
}

.skeleton-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 16px;
}

.skeleton-card {
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.skeleton-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.skeleton-detail-card {
  display: grid;
  gap: 14px;
  padding: 20px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.skeleton-list-item {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
}

.skeleton-list-main,
.skeleton-schedule-copy {
  display: grid;
  gap: 8px;
}

.skeleton-schedule-item {
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 16px;
}

.skeleton-date-badge {
  width: 72px;
  height: 64px;
  border-radius: var(--radius-md);
}

.skeleton-table-head {
  height: 36px;
  border-radius: var(--radius-sm);
}

.skeleton-table-row {
  display: grid;
  grid-template-columns: minmax(0, 2fr) auto minmax(90px, 1fr);
  align-items: center;
  gap: 16px;
  padding: 14px 12px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.skeleton-line-title {
  height: 20px;
  width: 62%;
}

.skeleton-line-medium {
  height: 14px;
  width: 82%;
}

.skeleton-line-short {
  height: 12px;
  width: 38%;
}

.skeleton-line-tiny {
  height: 12px;
  width: 56px;
}

.skeleton-pill {
  height: 22px;
  width: 72px;
  border-radius: var(--radius-pill);
}

.skeleton-block-tall {
  height: 160px;
  border-radius: var(--radius-md);
}

@media (max-width: 860px) {
  .skeleton-split {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-box {
    animation: none;
  }
}
</style>
