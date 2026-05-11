<script setup lang="ts">
import { ref } from 'vue'

const levelConfig = ref('')
const gameOutput = ref<string | null>(null)
const isRunning = ref(false)

function startGame() {
  if (!levelConfig.value.trim() || isRunning.value) return

  isRunning.value = true
  gameOutput.value = null

  // Simulate game startup — will be replaced with WebSocket connection
  setTimeout(() => {
    gameOutput.value = `Game started with configuration:\n\n${levelConfig.value}\n\n---\n\n[Game output will appear here once execution begins. Connect WebSocket for real-time results.]`
    isRunning.value = false
  }, 800)
}
</script>

<template>
  <div class="game-app-ui">
    <div class="game-panel">
      <div class="game-section input-section">
        <h3 class="game-title">Level Configuration</h3>
        <textarea
          v-model="levelConfig"
          class="game-textarea"
          placeholder="Enter game parameters (e.g. grid size, level design)..."
          rows="6"
          :disabled="isRunning"
        />
        <button
          class="start-btn"
          @click="startGame"
          :disabled="!levelConfig.trim() || isRunning"
        >
          <svg v-if="isRunning" viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <rect x="6" y="4" width="4" height="16" rx="1"/>
            <rect x="14" y="4" width="4" height="16" rx="1"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <path d="M8 5v14l11-7z"/>
          </svg>
          {{ isRunning ? 'Starting...' : 'Start Game' }}
        </button>
      </div>

      <div class="game-divider" />

      <div class="game-section output-section">
        <h3 class="game-title">Game Output</h3>
        <div v-if="!gameOutput" class="output-placeholder">
          <div class="placeholder-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="40" height="40">
              <rect x="2" y="6" width="20" height="12" rx="2" />
              <path d="M6 12h4" />
              <path d="M14 12h4" />
              <path d="M6 16h4" />
              <path d="M14 16h4" />
            </svg>
          </div>
          <p>Configure your level and click Start Game to play</p>
        </div>
        <pre v-else class="output-content">{{ gameOutput }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.game-app-ui {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 2rem;
}

.game-panel {
  display: flex;
  width: 100%;
  max-width: 900px;
  height: 100%;
  gap: 1.5rem;
}

.game-section {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.game-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

.game-textarea {
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

.game-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.game-textarea:disabled {
  opacity: 0.6;
}

.start-btn {
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

.start-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.start-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.game-divider {
  width: 1px;
  background: var(--border-color);
  flex-shrink: 0;
}

.output-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
  gap: 0.75rem;
}

.placeholder-icon {
  opacity: 0.4;
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
