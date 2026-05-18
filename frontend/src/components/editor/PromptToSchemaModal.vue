<template>
  <div v-if="visible" class="prompt-to-schema-overlay" @click.self="close">
    <div class="prompt-to-schema-modal">
      <div class="modal-header">
        <span>Generate Workflow from Prompt</span>
        <button class="close-btn" @click="close">✕</button>
      </div>
      <div class="modal-body">
        <textarea
          v-model="prompt"
          placeholder="Describe the workflow you want to create...&#10;&#10;Example: Create a code review agent that analyzes pull requests, checks for bugs, and generates a report."
          rows="8"
        />
        <div v-if="error" class="error">{{ error }}</div>
        <div v-if="result" class="result-preview">
          <h3>{{ result.schema?.name }}</h3>
          <p class="schema-description">{{ result.schema?.description }}</p>
          <div class="node-list">
            <div v-for="node in result.schema?.nodes" :key="node.id" class="node-item">
              <span class="node-type-badge">{{ node.type }}</span>
              <span class="node-name">{{ node.name }}</span>
            </div>
          </div>
          <div v-if="result.planExplanation" class="plan-explanation">
            <strong>Plan:</strong>
            <p>{{ result.planExplanation }}</p>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="generate-btn" @click="generate" :disabled="generating || !prompt.trim()">
          <svg v-if="generating" class="spinner" viewBox="0 0 24 24" fill="none" width="16" height="16">
            <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" stroke-dasharray="31.4 31.4" stroke-linecap="round"/>
          </svg>
          {{ generating ? 'Generating...' : 'Generate' }}
        </button>
        <button class="save-btn" @click="saveSchema" :disabled="!result">Save Schema</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { schemaApi } from '@/services/api'
import { useRouter } from 'vue-router'
import type { WorkflowSchema } from '@/types'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', schema: WorkflowSchema): void
}>()

const router = useRouter()
const prompt = ref('')
const generating = ref(false)
const error = ref<string | null>(null)
const result = ref<{ success: boolean; schema?: WorkflowSchema; planExplanation?: string; error?: string } | null>(null)

watch(() => props.visible, (v) => {
  if (v) {
    prompt.value = ''
    error.value = null
    result.value = null
    generating.value = false
  }
})

function close() {
  emit('close')
}

async function generate() {
  if (!prompt.value.trim()) return
  generating.value = true
  error.value = null
  result.value = null
  try {
    const res = await schemaApi.generateFromPrompt(prompt.value)
    if (res.success && res.schema) {
      result.value = res
    } else {
      error.value = res.error || 'Generation failed'
    }
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || 'An unexpected error occurred'
  } finally {
    generating.value = false
  }
}

async function saveSchema() {
  if (!result.value?.schema) return
  try {
    const created = await schemaApi.createSchema(result.value.schema)
    emit('saved', created)
    close()
    router.push(`/studio/${created.id}`)
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || 'Failed to save schema'
  }
}
</script>

<style scoped>
.prompt-to-schema-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 3000;
}

.prompt-to-schema-modal {
  background: #1e1e2e;
  border-radius: 16px;
  width: 640px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  color: #eee;
  font-weight: 600;
  font-size: 15px;
}

.close-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #eee;
  width: 30px;
  height: 30px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.modal-body {
  flex: 1;
  padding: 16px 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

textarea {
  width: 100%;
  background: #13131f;
  border: 1px solid #3a3a5a;
  color: #eee;
  border-radius: 8px;
  padding: 12px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 14px;
  line-height: 1.6;
  resize: vertical;
  outline: none;
  box-sizing: border-box;
}

textarea:focus {
  border-color: var(--accent, #6c63ff);
}

.error {
  background: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #fca5a5;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
}

.result-preview {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.result-preview h3 {
  margin: 0;
  font-size: 16px;
  color: #eee;
  font-weight: 600;
}

.schema-description {
  margin: 0;
  font-size: 13px;
  color: #999;
  line-height: 1.5;
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.node-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 6px;
}

.node-type-badge {
  background: rgba(108, 99, 255, 0.2);
  color: #c8c0ff;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  white-space: nowrap;
}

.node-name {
  font-size: 13px;
  color: #ccc;
}

.plan-explanation {
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  padding-top: 10px;
  font-size: 13px;
  color: #aaa;
  line-height: 1.5;
}

.plan-explanation strong {
  color: #ddd;
  display: block;
  margin-bottom: 4px;
}

.plan-explanation p {
  margin: 0;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.generate-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--accent, #6c63ff);
  border: none;
  color: white;
  padding: 8px 20px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.generate-btn:hover:not(:disabled) {
  background: #5a54e0;
}

.generate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.save-btn {
  background: #4f7cff;
  border: none;
  color: white;
  padding: 8px 20px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.save-btn:hover:not(:disabled) {
  background: #3d6bef;
}

.save-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
