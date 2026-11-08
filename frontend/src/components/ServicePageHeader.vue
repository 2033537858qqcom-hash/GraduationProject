<script setup lang="ts">
defineProps<{ context: string; title: string; description: string }>();
</script>

<template>
  <header class="page-heading-row service-page-header">
    <div class="service-page-heading-copy">
      <div class="service-page-heading-content">
        <div v-if="$slots.icon" class="service-page-heading-icon"><slot name="icon" /></div>
        <div class="service-page-heading-text">
          <span class="portal-context">{{ context }}</span>
          <h1>{{ title }}</h1>
          <p class="muted">{{ description }}</p>
        </div>
      </div>
    </div>
    <div v-if="$slots.actions" class="form-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.service-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
  border-bottom: 1px solid var(--campus-heading-border);
  padding-bottom: 20px;
}
.service-page-heading-copy {
  flex: 1 1 320px;
  min-width: 0;
}
.service-page-heading-copy::before {
  content: '';
  display: block;
  width: 35px;
  height: 1px;
  background: var(--campus-heading-rule);
  margin-bottom: 12px;
}
.service-page-heading-content {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
.service-page-heading-icon {
  flex: 0 0 auto;
}
.service-page-heading-text {
  min-width: 0;
  overflow-wrap: anywhere;
}
.service-page-header > .form-actions {
  padding: 0;
  margin: 0;
}

@media (max-width: 760px) {
  .service-page-heading-copy {
    flex-basis: auto;
    width: 100%;
  }
  .service-page-header > .form-actions {
    width: 100%;
  }
}

@media (max-width: 600px) {
  .service-page-header {
    padding-bottom: 16px;
  }
}
</style>
