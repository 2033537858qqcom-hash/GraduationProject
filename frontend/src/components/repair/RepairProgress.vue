<script setup lang="ts">
import type { Component } from 'vue';
import { AlertCircle, AlertTriangle, ArrowRight, Check } from '@lucide/vue';
defineProps<{
  closed: boolean;
  paused: boolean;
  overview: { icon: Component; class: string; text: string; hint?: string };
  updatedAtText: string;
  currentTask: { label: string } | null;
  disabled: boolean;
  steps: { id: number; state: string; title: string; subtitle: string; time?: string | null }[];
}>();
const emit = defineEmits<{ action: [] }>();
</script>
<template>
  <section
    class="repair-stepper-container"
    :class="{ 'is-closed': closed, 'is-paused': paused }"
    aria-label="当前办理进展"
  >
    <div class="repair-stepper-header">
      <div class="repair-current-message">
        <component :is="overview.icon" :size="18" :class="`repair-current-icon ${overview.class}`" />
        <div>
          <strong class="stepper-overview-tag" :class="overview.class">{{ overview.text }}</strong>
          <p v-if="overview.hint" class="stepper-overview-hint">{{ overview.hint }}</p>
          <time v-if="!closed" class="repair-current-time">最近更新 {{ updatedAtText }}</time>
        </div>
      </div>
      <button v-if="currentTask" class="button compact" :disabled="disabled" @click="emit('action')">
        {{ currentTask.label }}
        <ArrowRight :size="14" />
      </button>
    </div>
    <ol v-if="!closed && !paused" class="repair-stepper">
      <li v-for="(step, index) in steps" :key="step.id" class="repair-step-item" :class="`is-${step.state}`">
        <div class="repair-step-marker">
          <div class="repair-step-circle">
            <Check v-if="step.state === 'completed'" :size="12" />
            <AlertTriangle v-else-if="step.state === 'warning'" :size="14" />
            <AlertCircle v-else-if="step.state === 'rejected'" :size="14" />
            <span v-else class="step-num">{{ index + 1 }}</span>
          </div>
          <div v-if="index < steps.length - 1" class="repair-step-line">
            <span class="repair-step-line-fill" :class="{ filled: step.state === 'completed' }"></span>
          </div>
        </div>
        <div class="repair-step-content">
          <strong class="repair-step-title">{{ step.title }}</strong>
          <span class="repair-step-subtitle" :title="step.subtitle">{{ step.subtitle }}</span>
          <time v-if="step.time" class="repair-step-time">{{ step.time }}</time>
        </div>
      </li>
    </ol>
  </section>
</template>
