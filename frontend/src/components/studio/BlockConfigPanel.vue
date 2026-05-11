<script setup lang="ts">
import { ref, computed, watch } from 'vue'

const props = defineProps<{
  blockId: string
}>()

const emit = defineEmits<{
  close: []
}>()

// Block config state
const blockLabel = ref('')
const blockDescription = ref('')
const model = ref('')
const prompt = ref('')
const blockType = ref('agent')

// Determine config sections based on block type
const showModelSelector = computed(() => blockType.value === 'agent')
const showPrompt = computed(() => blockType.value === 'agent')
const showMemoryType = computed(() => blockType.value === 'memory')
const showActionType = computed(() => blockType.value === 'output')
const showInputType = computed(() => blockType.value === 'source')

function saveConfig() {
  // Auto-save on changes
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
  }
}
</script>

<template>
  <div class="config-panel" @keydown="handleKeydown">
    <div class="panel-header">
      <h3>Configure Block</h3>
      <button class="close-btn" @click="emit('close')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
          <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <!-- Block Name -->
      <div class="config-section">
        <label class="config-label">Block Name</label>
        <input
          v-model="blockLabel"
          type="text"
          class="config-input"
          placeholder="Name this block"
          @input="saveConfig"
        />
      </div>

      <!-- Description (all blocks) -->
      <div class="config-section">
        <label class="config-label">Description</label>
        <textarea
          v-model="blockDescription"
          class="config-textarea"
          placeholder="What should this block do?"
          rows="3"
          @input="saveConfig"
        />
      </div>

      <!-- Input Type (Receive blocks) -->
      <div v-if="showInputType" class="config-section">
        <label class="config-label">Input Type</label>
        <select v-model="blockType" class="config-select">
          <option value="source">Chat / Text</option>
          <option value="source-file">File Upload</option>
          <option value="source-webhook">Webhook</option>
          <option value="source-schedule">Schedule / Timer</option>
        </select>
      </div>

      <!-- Model Selector (Think blocks) -->
      <div v-if="showModelSelector" class="config-section">
        <label class="config-label">Model</label>
        <select v-model="model" class="config-select">
          <option value="local">Local (Ollama)</option>
          <option value="gpt-4o">GPT-4o</option>
          <option value="gpt-4o-mini">GPT-4o Mini</option>
          <option value="claude-sonnet">Claude Sonnet</option>
          <option value="claude-haiku">Claude Haiku</option>
          <option value="deepseek">DeepSeek</option>
        </select>
      </div>

      <!-- Prompt (Think blocks) -->
      <div v-if="showPrompt" class="config-section">
        <label class="config-label">System Prompt</label>
        <textarea
          v-model="prompt"
          class="config-textarea config-textarea--large"
          placeholder="Describe what this AI should do..."
          rows="6"
          @input="saveConfig"
        />
      </div>

      <!-- Memory Type (Remember blocks) -->
      <div v-if="showMemoryType" class="config-section">
        <label class="config-label">Memory Type</label>
        <select v-model="blockType" class="config-select">
          <option value="memory">Chat History</option>
          <option value="memory-knowledge">Knowledge Base</option>
          <option value="memory-facts">Structured Facts</option>
        </select>
      </div>

      <!-- Action Type (Act blocks) -->
      <div v-if="showActionType" class="config-section">
        <label class="config-label">Action Type</label>
        <select v-model="blockType" class="config-select">
          <option value="output">Reply / Output</option>
          <option value="output-save">Save to file</option>
          <option value="output-api">Call External API</option>
          <option value="output-email">Send Email</option>
        </select>
      </div>
    </div>
  </div>
</template>

<style scoped>
.config-panel {
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
  padding: 1rem;
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.panel-header h3 {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-primary);
}

.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.15s;
}

.close-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
}

.config-section {
  margin-bottom: 1.25rem;
}

.config-label {
  display: block;
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 0.375rem;
}

.config-input,
.config-select {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.85rem;
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
  transition: border-color 0.15s;
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
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.85rem;
  background: var(--bg-primary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
  line-height: 1.5;
}

.config-textarea--large {
  min-height: 120px;
  font-size: 0.82rem;
}
</style>
