<script setup lang="ts">
import { computed } from 'vue'
import { useSchemaStore } from '@/stores/schemaStore'
import { storeToRefs } from 'pinia'

const emit = defineEmits<{
  addNode: []
  run: []
  quickStart: []
}>()

const schemaStore = useSchemaStore()
const { currentSchema } = storeToRefs(schemaStore)

const defaultModel = computed(() => currentSchema.value?.defaultModel || '')
const targetPath = computed(() => currentSchema.value?.targetPath || '')
const schemaName = computed(() => currentSchema.value?.name || '')
const schemaDescription = computed(() => currentSchema.value?.description || '')

function updateName(value: string) {
  if (!currentSchema.value) return
  schemaStore.markDirty({ ...currentSchema.value, name: value })
}

function updateDescription(value: string) {
  if (!currentSchema.value) return
  schemaStore.markDirty({ ...currentSchema.value, description: value })
}
</script>

<template>
  <div class="schema-properties-panel">
    <div class="panel-header">
      <h3>Schema Properties</h3>
    </div>

    <div class="panel-body">
      <div class="config-section">
        <label class="config-label">Name</label>
        <input
          :value="schemaName"
          @input="updateName(($event.target as HTMLInputElement).value)"
          type="text"
          class="config-input"
          placeholder="Schema name"
        />
      </div>

      <div class="config-section">
        <label class="config-label">Description</label>
        <textarea
          :value="schemaDescription"
          @input="updateDescription(($event.target as HTMLTextAreaElement).value)"
          class="config-textarea"
          placeholder="Schema description"
          rows="3"
        />
      </div>

      <div class="config-section">
        <label class="config-label">Target Path</label>
        <div class="path-display">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" class="icon">
            <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
          </svg>
          <span class="path-text">{{ targetPath || '(not set)' }}</span>
        </div>
      </div>

      <div class="config-section">
        <label class="config-label">Default Model</label>
        <div class="model-display">
          <span class="model-text">{{ defaultModel || 'Auto (user default)' }}</span>
        </div>
      </div>

      <div class="config-section quick-actions">
        <label class="config-label">Quick Actions</label>
        <button class="action-btn" @click="emit('addNode')">
          Add Node
        </button>
        <button class="action-btn action-btn--primary" @click="emit('run')">
          Run
        </button>
        <button class="action-btn action-btn--accent" @click="emit('quickStart')">
          Quick Start
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.schema-properties-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 320px;
  height: 100%;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  box-shadow: var(--shadow-lg);
  z-index: 20;
  display: flex;
  flex-direction: column;
  animation: slideIn 0.2s ease-out;
}

@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.panel-header h3 {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
}

.config-section {
  margin-bottom: var(--space-5);
}

.config-label {
  display: block;
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.config-input,
.config-select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
  transition: border-color var(--transition);
}

.config-input:focus,
.config-select:focus,
.config-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.config-textarea {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
  line-height: 1.5;
}

.path-display {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-family: monospace;
  color: var(--text-primary);
}

.icon {
  flex-shrink: 0;
  color: var(--text-muted);
}

.path-text {
  word-break: break-all;
}

.model-display {
  padding: var(--space-2) var(--space-3);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--text-primary);
}

.quick-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.action-btn {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  cursor: pointer;
  transition: background var(--transition), border-color var(--transition);
  text-align: center;
}

.action-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent);
}

.action-btn--primary {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

.action-btn--primary:hover {
  opacity: 0.9;
}

.action-btn--accent {
  background: var(--accent-secondary, #00bcd4);
  color: white;
  border-color: var(--accent-secondary, #00bcd4);
}

.action-btn--accent:hover {
  opacity: 0.9;
}
</style>
