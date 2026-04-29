<template>
  <div class="toast-container">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="toast"
        :class="'toast--' + toast.type"
      >
        <span class="toast-icon">{{ icon(toast.type) }}</span>
        <span class="toast-message">{{ toast.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { useToast } from '../composables/useToast';

const { toasts } = useToast();

function icon(type: string) {
  switch (type) {
    case 'success': return '✓';
    case 'error': return '✕';
    default: return 'ℹ';
  }
}
</script>

<style scoped>
.toast-container {
  position: fixed;
  bottom: 20px;
  right: 20px;
  z-index: 9999;
  display: flex;
  flex-direction: column-reverse;
  gap: 8px;
  pointer-events: none;
}
.toast {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--text-primary);
  min-width: 250px;
  max-width: 400px;
  box-shadow: var(--shadow-md);
  pointer-events: auto;
}
.toast--success { background: var(--success-dark); }
.toast--error { background: var(--error-dark); }
.toast--info { background: var(--info-dark); }
.toast-icon {
  font-weight: bold;
  font-size: 16px;
  flex-shrink: 0;
}
.toast-message {
  flex: 1;
  word-break: break-word;
}

.toast-enter-active { transition: all 0.3s ease; }
.toast-leave-active { transition: all 0.3s ease; }
.toast-enter-from { opacity: 0; transform: translateX(60px); }
.toast-leave-to { opacity: 0; transform: translateX(60px); }
</style>
