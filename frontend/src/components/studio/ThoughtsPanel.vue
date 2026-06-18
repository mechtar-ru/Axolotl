<template>
  <Teleport to="body">
    <Transition name="slide-fade">
      <div v-if="isOpen" class="thoughts-panel-overlay" @click="close">
        <div class="thoughts-panel" @click.stop>
          <!-- Header -->
          <div class="thoughts-header">
            <h3 class="thoughts-title">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                <circle cx="5" cy="19" r="1"/>
                <circle cx="3" cy="21" r="0.75"/>
              </svg>
              LLM Thoughts & Reasoning
            </h3>
            <button class="thoughts-close" @click="close" aria-label="Close thoughts panel">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
                <path d="M18 6 6 18"/><path d="m6 6 12 12"/>
              </svg>
            </button>
          </div>

          <!-- Content -->
          <div class="thoughts-body">
            <div v-if="reasoning" class="thoughts-content">
              <pre class="thoughts-text">{{ reasoning }}</pre>
            </div>
            <div v-else class="thoughts-empty">
              No reasoning available for this node.
            </div>
          </div>

          <!-- Footer with Copy button -->
          <div class="thoughts-footer">
            <button v-if="reasoning" class="btn-copy" @click="copyToClipboard">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
              </svg>
              {{ copyStatus || 'Copy' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';

const props = defineProps<{
  reasoning: string | null;
  isOpen: boolean;
}>();

const emit = defineEmits<{
  close: [];
}>();

const copyStatus = ref<string | null>(null);

const close = () => {
  emit('close');
};

const copyToClipboard = async () => {
  if (!props.reasoning) return;
  
  try {
    await navigator.clipboard.writeText(props.reasoning);
    copyStatus.value = 'Copied!';
    setTimeout(() => {
      copyStatus.value = null;
    }, 2000);
  } catch (err) {
    console.error('ThoughtsPanel: Failed to copy:', err);
    copyStatus.value = 'Failed';
  }
};
</script>

<style scoped>
.thoughts-panel-overlay {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}

.thoughts-panel {
  width: min(50vw, 600px);
  background: var(--surface-primary);
  border-left: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  box-shadow: -2px 0 8px rgba(0, 0, 0, 0.15);
}

.thoughts-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  background: var(--surface-secondary);
}

.thoughts-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.thoughts-close {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: var(--space-1);
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: all 0.2s;
}

.thoughts-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.thoughts-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
  background: var(--surface-primary);
}

.thoughts-content {
  width: 100%;
}

.thoughts-text {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--text-primary);
  background: var(--bg-subtle);
  padding: var(--space-3);
  border-radius: 4px;
  border: 1px solid var(--border-subtle);
  margin: 0;
  overflow-x: auto;
  word-wrap: break-word;
  white-space: pre-wrap;
}

.thoughts-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  text-align: center;
}

.thoughts-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border-subtle);
  background: var(--surface-secondary);
}

.btn-copy {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-xs);
  font-weight: 500;
  background: var(--bg-interactive);
  color: var(--text-interactive);
  border: 1px solid var(--border-subtle);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-copy:hover {
  background: var(--bg-interactive-hover);
  border-color: var(--border-interactive);
}

/* Slide fade transition */
.slide-fade-enter-active,
.slide-fade-leave-active {
  transition: all 0.3s ease;
}

.slide-fade-enter-from {
  transform: translateX(100%);
  opacity: 0;
}

.slide-fade-enter-to {
  transform: translateX(0);
  opacity: 1;
}

.slide-fade-leave-from {
  transform: translateX(0);
  opacity: 1;
}

.slide-fade-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>
