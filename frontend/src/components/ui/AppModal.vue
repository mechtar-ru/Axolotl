<template>
  <Transition name="modal">
    <div v-if="modelValue" class="app-modal-overlay" @click.self="close" role="dialog" aria-modal="true" :aria-labelledby="title ? 'modal-title' : undefined">
      <div
        ref="modalBox"
        class="app-modal-content"
        :class="{ 'app-modal-large': large }"
        @keydown.escape="close"
        @keydown.tab="onTab"
      >
        <div v-if="title" class="app-modal-header">
          <h3 id="modal-title">{{ title }}</h3>
          <button class="app-modal-close" @click="close" aria-label="Close (Esc)">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <slot />
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  title?: string;
  large?: boolean;
}>(), {
  title: '',
  large: false,
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
}>();

const modalBox = ref<HTMLElement | null>(null);
let previousFocus: HTMLElement | null = null;

function close() {
  emit('update:modelValue', false);
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.modelValue) {
    close();
  }
}

// Focus trap - cycle focus within modal
function onTab(e: KeyboardEvent) {
  if (!props.modelValue) return;
  const focusableElements = modalBox.value?.querySelectorAll<HTMLElement>(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  );
  if (!focusableElements?.length) return;
  const firstElement = focusableElements[0];
  const lastElement = focusableElements[focusableElements.length - 1];
  if (!firstElement || !lastElement) return;
  if (e.shiftKey && document.activeElement === firstElement) {
    e.preventDefault();
    lastElement.focus();
  } else if (!e.shiftKey && document.activeElement === lastElement) {
    e.preventDefault();
    firstElement.focus();
  }
}

watch(() => props.modelValue, (open) => {
  if (open) {
    previousFocus = document.activeElement as HTMLElement;
    nextTick(() => {
      const first = modalBox.value?.querySelector<HTMLElement>(
        'input, textarea, select, button:not([disabled])'
      );
      first?.focus();
    });
    document.addEventListener('keydown', onKey);
  } else {
    document.removeEventListener('keydown', onKey);
    previousFocus?.focus();
  }
});

onUnmounted(() => {
  document.removeEventListener('keydown', onKey);
});
</script>

<style scoped>
.app-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-modal-backdrop);
  backdrop-filter: blur(4px);
}

.app-modal-content {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  min-width: 320px;
  max-width: 500px;
  max-height: 85vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-color);
}
.app-modal-large {
  max-width: 720px;
}

.app-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-4);
}
.app-modal-header h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: var(--text-lg);
}
.app-modal-close {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: var(--space-1);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
}
.app-modal-close:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}
.app-modal-close:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-active .app-modal-content,
.modal-leave-active .app-modal-content {
  transition: transform 0.2s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .app-modal-content,
.modal-leave-to .app-modal-content {
  transform: scale(0.95) translateY(8px);
}
</style>
