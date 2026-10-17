<script setup lang="ts">
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from '@lucide/vue'
import { useToast } from '../composables/useToast'
import type { ToastType } from '../composables/useToast'

const { toasts, remove } = useToast()

function iconFor(type: ToastType) {
  switch (type) {
    case 'success': return CheckCircle2
    case 'error': return AlertCircle
    case 'warning': return AlertTriangle
    default: return Info
  }
}
</script>

<template>
  <div class="toast-hub" aria-live="polite" aria-atomic="true">
    <TransitionGroup name="toast-slide">
      <div
        v-for="item in toasts"
        :key="item.id"
        class="toast-pill"
        :class="`type-${item.type}`"
        role="alert"
      >
        <component :is="iconFor(item.type)" :size="16" class="toast-icon" />
        <span class="toast-message">{{ item.message }}</span>
        <button
          class="toast-close"
          aria-label="关闭提示"
          @click="remove(item.id)"
        >
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
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(220, 228, 238, 0.85);
  box-shadow: 0 12px 32px rgba(18, 38, 63, 0.12), 0 2px 6px rgba(0, 0, 0, 0.04);
  border-radius: 9999px;
  padding: 8px 16px 8px 14px;
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
  max-width: 480px;
  transition: all 0.28s cubic-bezier(0.16, 1, 0.3, 1);
}

.toast-icon {
  flex-shrink: 0;
}

.type-success {
  border-color: rgba(52, 199, 89, 0.3);
}
.type-success .toast-icon {
  color: #2e7d32;
}

.type-error {
  border-color: rgba(239, 68, 68, 0.3);
}
.type-error .toast-icon {
  color: #d32f2f;
}

.type-warning {
  border-color: rgba(245, 158, 11, 0.3);
}
.type-warning .toast-icon {
  color: #ed6c02;
}

.type-info {
  border-color: rgba(0, 113, 227, 0.3);
}
.type-info .toast-icon {
  color: #0071e3;
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
  color: #9ca3af;
  cursor: pointer;
  padding: 2px;
  border-radius: 50%;
  margin-left: 2px;
  transition: color 0.15s, background-color 0.15s;
}

.toast-close:hover {
  color: #4b5563;
  background-color: rgba(0, 0, 0, 0.05);
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
</style>
