<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  appType: string
}>()

const input = ref('')
const output = ref<string | null>(null)
const isRunning = ref(false)

function run() {
  if (!input.value.trim() || isRunning.value) return
  
  isRunning.value = true
  output.value = null
  
  // Simulate processing
  setTimeout(() => {
    output.value = `App Type: ${props.appType}\nInput: ${input.value}\n\nThis is where the app output will appear. Connect WebSocket for real-time results.`
    isRunning.value = false
  }, 800)
}
</script>

<template>
  <div class="generic-ui">
    <div class="io-panel">
      <div class="io-section input-section">
        <h3 class="io-title">Input</h3>
        <textarea
          v-model="input"
          class="io-textarea"
          placeholder="Enter input for your app..."
          rows="8"
          :disabled="isRunning"
        />
        <button
          class="run-action-btn"
          @click="run"
          :disabled="!input.trim() || isRunning"
        >
          <svg v-if="isRunning" viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <rect x="6" y="4" width="4" height="16" rx="1"/>
            <rect x="14" y="4" width="4" height="16" rx="1"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <path d="M8 5v14l11-7z"/>
          </svg>
          {{ isRunning ? 'Running...' : 'Run' }}
        </button>
      </div>
      
      <div class="io-divider" />
      
      <div class="io-section output-section">
        <h3 class="io-title">Output</h3>
        <div v-if="!output" class="output-placeholder">
          <p>Run your app to see output here</p>
        </div>
        <pre v-else class="output-content">{{ output }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.generic-ui {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 2rem;
}

.io-panel {
  display: flex;
  width: 100%;
  max-width: 900px;
  height: 100%;
  gap: 1.5rem;
}

.io-section {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.io-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

.io-textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: none;
  font-family: monospace;
  line-height: 1.6;
}

.io-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.io-textarea:disabled {
  opacity: 0.6;
}

.run-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.75rem;
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  align-self: flex-start;
  transition: background 0.15s;
}

.run-action-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.run-action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.io-divider {
  width: 1px;
  background: var(--border-color);
  flex-shrink: 0;
}

.output-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
}

.output-content {
  flex: 1;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-y: auto;
  color: var(--text-primary);
  margin: 0;
  font-family: monospace;
}
</style>
