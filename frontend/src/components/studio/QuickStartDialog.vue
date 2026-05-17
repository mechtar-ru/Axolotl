<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { schemaApi, settingsApi, type ProviderInfo } from '@/services/api'
import type { WorkflowSchema } from '@/types'

const props = defineProps<{
  visible: boolean
  appId: string
}>()

const emit = defineEmits<{
  close: []
  'add-to-canvas': [schema: WorkflowSchema]
}>()

const prompt = ref('')
const selectedModel = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const result = ref<WorkflowSchema | null>(null)
const providers = ref<ProviderInfo[]>([])
const generatedNodeCount = ref(0)
const generatedEdgeCount = ref(0)

// Fetch available providers on mount
let defaultModel: string | undefined
async function loadProviders() {
  providers.value = await settingsApi.getProviders()
  defaultModel = providers.value.find(p => p.available)?.defaultModel
  if (defaultModel) {
    selectedModel.value = defaultModel
  }
}
onMounted(loadProviders)

// Reset state when dialog opens
watch(() => props.visible, (newVal) => {
  if (newVal) {
    prompt.value = ''
    selectedModel.value = defaultModel || ''
    loading.value = false
    error.value = null
    result.value = null
    generatedNodeCount.value = 0
    generatedEdgeCount.value = 0
  }
})

async function generate() {
  if (!prompt.value.trim()) return

  loading.value = true
  error.value = null
  result.value = null

  try {
    const response = await schemaApi.generateNodes(
      props.appId,
      prompt.value,
      selectedModel.value || undefined
    )

    if (response.success && response.schema) {
      result.value = response.schema
      generatedNodeCount.value = response.schema.nodes?.length || 0
      generatedEdgeCount.value = response.schema.edges?.length || 0
    } else {
      error.value = response.error || 'Generation failed. Try a more specific description.'
    }
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || 'Network error. Please try again.'
  } finally {
    loading.value = false
  }
}

function addToCanvas() {
  if (result.value) {
    emit('add-to-canvas', result.value)
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && !loading.value) {
    emit('close')
  }
}

defineExpose({
  prompt,
  selectedModel,
  loading,
  error,
  result,
  providers,
  generate,
  addToCanvas,
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="quickstart-overlay" @click.self="!loading && emit('close')" @keydown="handleKeydown">
      <div class="quickstart-dialog">
        <!-- Header -->
        <div class="dialog-header">
          <h2 class="dialog-title">Quick Start — Describe Your App</h2>
          <button class="close-btn" @click="emit('close')" :disabled="loading" aria-label="Close">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
              <path d="M18 6L6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>

        <!-- Input area -->
        <div class="dialog-body">
          <label class="input-label" for="quickstart-prompt">Describe the pipeline you want to build:</label>
          <textarea
            id="quickstart-prompt"
            v-model="prompt"
            class="prompt-input"
            rows="4"
            placeholder="E.g. Build a Sokoban game in Python with pygame, 5 levels..."
            :disabled="loading"
          />

          <div class="model-row">
            <label class="input-label" for="quickstart-model">Model:</label>
            <select
              id="quickstart-model"
              v-model="selectedModel"
              class="model-select"
              :disabled="loading || providers.length === 0"
            >
              <option value="" disabled>Select a model</option>
              <template v-for="provider in providers" :key="provider.name">
                <option
                  v-for="model in provider.models"
                  :key="model"
                  :value="model"
                  :selected="model === selectedModel"
                >
                  {{ model }}
                </option>
              </template>
            </select>
          </div>

          <!-- Generate button -->
          <button
            class="generate-btn"
            :disabled="!prompt.trim() || loading"
            @click="generate"
          >
            <template v-if="loading">
              <span class="spinner" />
              Generating pipeline...
            </template>
            <template v-else>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <path d="M13 2L12 10H21L11 22L12 14H3L13 2Z" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              Generate Pipeline
            </template>
          </button>

          <!-- Error section -->
          <div v-if="error" class="error-section">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <circle cx="12" cy="12" r="10"/>
              <path d="M15 9l-6 6M9 9l6 6" stroke-linecap="round"/>
            </svg>
            {{ error }}
          </div>

          <!-- Result section -->
          <div v-if="result" class="result-section">
            <div class="result-indicator">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
                <path d="M22 11.08V12a10 10 0 11-5.93-9.14" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M22 4L12 14.01l-3-3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>Generated {{ generatedNodeCount }} node{{ generatedNodeCount !== 1 ? 's' : '' }} and {{ generatedEdgeCount }} edge{{ generatedEdgeCount !== 1 ? 's' : '' }}</span>
            </div>
            <div class="result-actions">
              <button class="add-btn" @click="addToCanvas">Add to Canvas</button>
              <button class="regenerate-btn" @click="generate" :disabled="loading">Regenerate</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.quickstart-overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-modal);
}

.quickstart-dialog {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  width: 100%;
  max-width: 560px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-5) var(--space-6);
  border-bottom: 1px solid var(--border-color);
}

.dialog-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
}

.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.close-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.dialog-body {
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.input-label {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-secondary);
}

.prompt-input {
  width: 100%;
  padding: var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-family: inherit;
  resize: vertical;
  min-height: 100px;
  box-sizing: border-box;
}

.prompt-input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 20%, transparent);
}

.prompt-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.model-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.model-select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: var(--text-sm);
  cursor: pointer;
}

.model-select:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  border: none;
  border-radius: var(--radius-md);
  background: var(--accent);
  color: white;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), opacity var(--transition-fast);
}

.generate-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.generate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  width: var(--icon-sm);
  height: var(--icon-sm);
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-section {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--error-light);
  color: var(--error);
  font-size: var(--text-sm);
  line-height: 1.4;
}

.error-section svg {
  flex-shrink: 0;
  margin-top: 2px;
}

.result-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  border-radius: var(--radius-md);
  background: var(--success-light);
  border: 1px solid color-mix(in srgb, var(--success) 20%, transparent);
}

.result-indicator {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--success);
  font-size: var(--text-sm);
  font-weight: 500;
}

.result-actions {
  display: flex;
  gap: var(--space-2);
}

.add-btn {
  flex: 1;
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius-md);
  background: var(--accent);
  color: white;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.add-btn:hover {
  background: var(--accent-hover);
}

.regenerate-btn {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.regenerate-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.regenerate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
