<template>
  <div class="onboarding-overlay" @click.self="$emit('close')">
    <div class="onboarding-modal">
      <div class="onboarding-header">
        <span class="onboarding-title">👋 Welcome to Axolotl!</span>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <div class="onboarding-steps">
        <div
          v-for="(step, i) in steps"
          :key="i"
          class="step"
          :class="{ active: currentStep === i, completed: currentStep > i }"
          @click="currentStep = i"
        >
          <div class="step-number">{{ currentStep > i ? '✓' : i + 1 }}</div>
          <div class="step-content">
            <div class="step-title">{{ step.title }}</div>
            <div v-if="currentStep === i" class="step-description">{{ step.description }}</div>
            <div v-if="currentStep === i && step.action" class="step-action">
              <button class="action-btn" @click="step.action">
                {{ step.actionLabel || 'Try it' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="onboarding-footer">
        <div class="step-indicator">{{ currentStep + 1 }} / {{ steps.length }}</div>
        <div class="footer-buttons">
          <button v-if="currentStep > 0" class="footer-btn" @click="currentStep--">Back</button>
          <button
            v-if="currentStep < steps.length - 1"
            class="footer-btn primary"
            @click="currentStep++"
          >
            Next
          </button>
          <button
            v-else
            class="footer-btn primary"
            @click="$emit('close')"
          >
            Get Started
          </button>
        </div>
      </div>

      <!-- Keyboard shortcuts hint -->
      <div class="shortcuts-hint">
        <div class="hint-title">⌨️ Quick Shortcuts</div>
        <div class="shortcut-grid">
          <div v-for="s in shortcuts" :key="s.key" class="shortcut-item">
            <kbd>{{ s.key }}</kbd>
            <span>{{ s.action }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'try-source'): void;
  (e: 'try-agent'): void;
  (e: 'try-execute'): void;
  (e: 'try-templates'): void;
}>();

const currentStep = ref(0);

const steps = [
  {
    title: 'Create your first workflow',
    description: 'Click "＋ Add" in the toolbar, then select a node type to start building your workflow. Start with a Source node to provide input data.',
    actionLabel: 'Try: Add Source node',
    action: () => { emit('try-source'); }
  },
  {
    title: 'Connect nodes together',
    description: 'Drag from the output port (right side) of one node to the input port (left side) of another node to create a connection. This defines the execution flow.',
    actionLabel: 'Try: Add Agent node',
    action: () => { emit('try-agent'); }
  },
  {
    title: 'Run your workflow',
    description: 'Click the "▶ Execute" button to run your workflow. Watch the real-time progress in the execution panel that appears on the right.',
    actionLabel: 'Try: Create & Run',
    action: () => { emit('try-execute'); }
  },
  {
    title: 'Explore templates',
    description: 'Click the 🏗️ icon in the toolbar to browse pre-built workflow templates. Great for learning patterns and getting started quickly.',
    actionLabel: 'Try: Open Templates',
    action: () => { emit('try-templates'); }
  },
  {
    title: 'Keyboard shortcuts',
    description: 'Use Ctrl+Z to undo, Ctrl+S to save, and Tab to navigate between nodes. Press ? for a full list of shortcuts.',
  },
];

const shortcuts = [
  { key: 'Ctrl+S', action: 'Save' },
  { key: 'Ctrl+Z', action: 'Undo' },
  { key: 'Ctrl+Enter', action: 'Execute' },
  { key: 'Tab', action: 'Navigate nodes' },
  { key: 'Del', action: 'Delete selected' },
  { key: 'Ctrl+G', action: 'Group nodes' },
];
</script>

<style scoped>
.onboarding-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: var(--overlay-heavy); display: flex; align-items: center; justify-content: center;
  z-index: var(--z-tooltip); backdrop-filter: var(--backdrop);
}
.onboarding-modal {
  background: var(--bg-secondary); border: 1px solid var(--border); border-radius: var(--radius-lg);
  width: 600px; max-height: 85vh; overflow-y: auto; color: var(--text-primary);
  box-shadow: var(--shadow-lg);
}
.onboarding-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: var(--space-5) var(--space-6); border-bottom: 1px solid var(--border);
}
.onboarding-title { font-size: var(--text-xl); font-weight: 700; }
.close-btn { background: none; border: none; color: var(--text-muted); font-size: var(--text-xl); cursor: pointer; }
.close-btn:hover { color: var(--text-primary); }

.onboarding-steps { padding: var(--space-6); display: flex; flex-direction: column; gap: var(--space-3); }
.step {
  display: flex; gap: var(--space-3); padding: var(--space-3-5); border: 1px solid var(--border); border-radius: var(--radius-sm);
  cursor: pointer; transition: all var(--transition);
}
.step:hover { background: rgba(255, 255, 255, 0.03); }
.step.active { border-color: var(--accent); background: rgba(108, 99, 255, 0.05); }
.step.completed { border-color: var(--success); }
.step-number {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--border); display: flex; align-items: center; justify-content: center;
  font-size: var(--text-sm); font-weight: 600; flex-shrink: 0;
}
.step.active .step-number { background: var(--accent); }
.step.completed .step-number { background: var(--success); }
.step-content { flex: 1; }
.step-title { font-size: var(--text-md); font-weight: 600; }
.step-description { font-size: var(--text-sm); color: var(--text-secondary); margin-top: var(--space-1-5); line-height: 1.5; }
.step-action { margin-top: var(--space-2-5); }
.action-btn {
  background: var(--accent); border: none; color: var(--text-inverse); padding: 6px 14px;
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-sm);
}
.action-btn:hover { background: var(--accent-hover); }

.onboarding-footer {
  display: flex; justify-content: space-between; align-items: center;
  padding: var(--space-4) var(--space-6); border-top: 1px solid var(--border);
}
.step-indicator { font-size: var(--text-sm); color: var(--text-muted); }
.footer-buttons { display: flex; gap: var(--space-2); }
.footer-btn {
  padding: var(--space-2) var(--space-4); border: 1px solid var(--border); background: transparent;
  color: var(--text-primary); border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-sm);
}
.footer-btn.primary { background: var(--accent); border-color: var(--accent); }
.footer-btn:hover:not(.primary) { background: rgba(255,255,255,0.05); }
.footer-btn.primary:hover { background: var(--accent-hover); }

.shortcuts-hint {
  padding: var(--space-4) var(--space-6); border-top: 1px solid var(--border); background: rgba(0,0,0,0.2);
}
.hint-title { font-size: var(--text-sm); color: var(--text-muted); margin-bottom: var(--space-2); }
.shortcut-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-1-5); }
.shortcut-item {
  display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-xs); color: var(--text-secondary);
}
.shortcut-item kbd {
  background: var(--border); padding: 2px 6px; border-radius: 4px; font-size: 10px;
  font-family: monospace; color: var(--text-primary); min-width: 60px; text-align: center;
}
</style>
