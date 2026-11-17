<script setup lang="ts">
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from '@lucide/vue';
import { useToast } from '../composables/useToast';
import type { ToastType } from '../composables/useToast';

const { toasts, remove } = useToast();

function iconFor(type: ToastType) {
  switch (type) {
    case 'success':
      return CheckCircle2;
    case 'error':
      return AlertCircle;
    case 'warning':
      return AlertTriangle;
    default:
      return Info;
  }
}
</script>

<template>
  <div class="toast-hub" aria-live="polite" aria-atomic="true">
    <TransitionGroup name="toast-slide">
      <div v-for="item in toasts" :key="item.id" class="toast-pill" :class="`type-${item.type}`" role="alert">
        <component :is="iconFor(item.type)" :size="16" class="toast-icon" />
        <span class="toast-message">{{ item.message }}</span>
        <button class="toast-close" aria-label="关闭提示" @click="remove(item.id)">
          <X :size="13" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-hub {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  pointer-events: none;
  max-width: 90vw;
}

.toast-pill {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  background: var(--color-surface);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-popover);
  border-radius: var(--radius-pill);
  padding: 8px 16px 8px 14px;
  font-size: var(--text-supporting);
  font-weight: 500;
  color: var(--color-ink);
  max-width: 480px;
  transition:
    opacity 0.24s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.28s cubic-bezier(0.16, 1, 0.3, 1),
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.toast-icon {
  flex-shrink: 0;
}

.type-success {
  border-color: var(--color-success-border);
}
.type-success .toast-icon {
  color: var(--color-success);
}

.type-error {
  border-color: var(--color-danger-border);
}
.type-error .toast-icon {
  color: var(--color-danger);
}

.type-warning {
  border-color: var(--color-warning-border);
}
.type-warning .toast-icon {
  color: var(--color-warning);
}

.type-info {
  border-color: var(--color-info-border);
}
.type-info .toast-icon {
  color: var(--color-info);
}

.toast-message {
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.toast-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: 2px;
  border-radius: 50%;
  margin-left: 2px;
  transition:
    color 0.15s,
    background-color 0.15s;
}

.toast-close:hover {
  color: var(--color-text-secondary);
  background-color: var(--color-surface-soft);
}

/* Animations */
.toast-slide-enter-from {
  opacity: 0;
  transform: translateY(-16px) scale(0.96);
}
.toast-slide-enter-to {
  opacity: 1;
  transform: translateY(0) scale(1);
}
.toast-slide-leave-from {
  opacity: 1;
  transform: translateY(0) scale(1);
}
.toast-slide-leave-to {
  opacity: 0;
  transform: translateY(-12px) scale(0.96);
}

@media (prefers-reduced-motion: reduce) {
  .toast-pill,
  .toast-close {
    transition-duration: 0.01ms;
  }

  .toast-slide-enter-from,
  .toast-slide-leave-to {
    transform: none;
  }
}
</style>
