<template>
  <Transition name="modal">
    <div v-if="modelValue" class="app-modal-overlay" @click.self="close">
      <div
        ref="modalBox"
        class="app-modal-content"
        :class="{ 'app-modal-large': large }"
        @keydown.escape="close"
      >
        <div v-if="title" class="app-modal-header">
          <h3>{{ title }}</h3>
          <button class="app-modal-close" @click="close" title="Закрыть (Esc)">✕</button>
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

watch(() => props.modelValue, (open) => {
  if (open) {
    previousFocus = document.activeElement as HTMLElement;
    nextTick(() => {
      const first = modalBox.value?.querySelector<HTMLElement>(
        'input, textarea, select, button:not(.app-modal-close)'
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
  z-index: var(--z-modal, 1000);
  backdrop-filter: blur(4px);
}

.app-modal-content {
  background: var(--bg-card, #1e1e2e);
  border-radius: var(--radius-lg, 16px);
  padding: 24px;
  min-width: 320px;
  max-width: 500px;
  max-height: 85vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg, 0 20px 60px rgba(0, 0, 0, 0.5));
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.app-modal-large {
  max-width: 720px;
}

.app-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.app-modal-header h3 {
  margin: 0;
  color: var(--text-primary, #eee);
  font-size: 18px;
}
.app-modal-close {
  background: none;
  border: none;
  color: var(--text-secondary, #aaa);
  font-size: 18px;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
}
.app-modal-close:hover {
  color: var(--text-primary, #eee);
  background: rgba(255, 255, 255, 0.1);
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
