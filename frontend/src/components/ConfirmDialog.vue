<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { AlertTriangle } from '@lucide/vue';
import { confirmRequest, registerConfirmHost, settleConfirm } from '../composables/useConfirm';

const dialog = ref<HTMLDialogElement | null>(null);
const cancelButton = ref<HTMLButtonElement | null>(null);

watch(confirmRequest, async request => {
  await nextTick();
  if (request && !dialog.value?.open) {
    dialog.value?.showModal();
    // Default focus on the safe choice, matching native confirm behaviour for destructive prompts.
    cancelButton.value?.focus();
  } else if (!request && dialog.value?.open) dialog.value.close();
});

onMounted(() => registerConfirmHost(true));
onUnmounted(() => registerConfirmHost(false));
</script>

<template>
  <dialog
    ref="dialog"
    class="confirm-dialog"
    aria-labelledby="confirm-dialog-title"
    aria-describedby="confirm-dialog-message"
    @cancel.prevent="settleConfirm(false)"
    @click.self="settleConfirm(false)"
  >
    <div v-if="confirmRequest" class="confirm-dialog-body" :class="{ danger: confirmRequest.danger }">
      <h2 id="confirm-dialog-title">
        <AlertTriangle :size="18" aria-hidden="true" />
        {{ confirmRequest.title }}
      </h2>
      <p id="confirm-dialog-message">{{ confirmRequest.message }}</p>
      <div class="confirm-dialog-actions">
        <button ref="cancelButton" type="button" class="confirm-cancel" @click="settleConfirm(false)">
          {{ confirmRequest.cancelText }}
        </button>
        <button type="button" class="confirm-ok" @click="settleConfirm(true)">
          {{ confirmRequest.confirmText }}
        </button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.confirm-dialog {
  width: min(420px, calc(100vw - 32px));
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  color: var(--color-ink);
  box-shadow: var(--shadow-panel);
}
.confirm-dialog[open] {
  animation: confirm-dialog-in 0.22s var(--ease-campus);
}
.confirm-dialog::backdrop {
  background: rgb(33 60 53 / 42%);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}
.confirm-dialog[open]::backdrop {
  animation: confirm-backdrop-in 0.2s ease-out;
}
.confirm-dialog-body {
  display: grid;
  gap: var(--space-3);
  padding: var(--space-5);
}
.confirm-dialog-body h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-heading);
  font-size: var(--text-title-sm);
}
.confirm-dialog-body h2 svg {
  color: var(--color-warning);
}
.confirm-dialog-body.danger h2 svg {
  color: var(--color-danger);
}
.confirm-dialog-body p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--text-reading);
  line-height: 1.7;
}
.confirm-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
.confirm-dialog-actions button {
  min-height: 36px;
  padding: 0 var(--space-4);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  font: inherit;
  font-size: var(--text-body);
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--ease-campus);
}
.confirm-cancel {
  background: var(--color-surface);
  color: var(--color-ink);
}
.confirm-cancel:hover {
  background: var(--color-surface-soft);
}
.confirm-ok {
  border-color: var(--color-brand);
  background: var(--color-brand);
  color: var(--color-white);
}
.confirm-ok:hover {
  background: var(--color-brand-hover);
}
.danger .confirm-ok {
  border-color: var(--color-danger);
  background: var(--color-danger);
}
.danger .confirm-ok:hover {
  background: var(--color-danger-strong);
}
.confirm-dialog-actions button:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}

@keyframes confirm-dialog-in {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.97);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@keyframes confirm-backdrop-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .confirm-dialog[open],
  .confirm-dialog[open]::backdrop {
    animation: none;
  }
}
</style>
