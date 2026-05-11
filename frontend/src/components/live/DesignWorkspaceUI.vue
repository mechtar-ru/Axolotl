<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { DesignWorkspaceFile } from '@/types'

const props = defineProps<{
  appType: 'GAME' | 'GENERATOR'
  executionResult: any
}>()

type Tab = 'concept' | 'review' | 'output'
type Phase = 'ideation' | 'review' | 'generating' | 'complete'

const activeTab = ref<Tab>('concept')
const conceptPrompt = ref('')
const plan = ref<string | null>(null)
const critiquePrompt = ref('')
const files = ref<DesignWorkspaceFile[]>([])
const phase = ref<Phase>('ideation')

// Watch execution results from WebSocket
watch(() => props.executionResult, (result) => {
  if (!result) return
  
  // If result has a plan, show it in Review tab
  if (result.plan) {
    plan.value = result.plan
    phase.value = 'review'
    activeTab.value = 'review'
  }
  
  // If result has files, show them in Output tab
  if (result.files && Array.isArray(result.files)) {
    files.value = result.files.map((f: any) => ({
      name: f.name || 'unnamed',
      content: f.content || '',
      type: f.type || 'text/plain',
      size: f.size ?? (f.content ? new Blob([f.content]).size : undefined)
    }))
    phase.value = 'complete'
    activeTab.value = 'output'
  }
}, { immediate: true })

// Tab labels
const tabLabels: Record<Tab, string> = {
  concept: 'Concept',
  review: 'Review',
  output: 'Output'
}

// File size formatter
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

// Download single file
function downloadFile(file: DesignWorkspaceFile) {
  const blob = new Blob([file.content], { type: file.type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = file.name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// Download all files (simplified — downloads one by one)
function downloadAll() {
  files.value.forEach(f => downloadFile(f))
}

// Generate draft (placeholder — execution triggered from Blueprint)
function generateDraft() {
  if (!conceptPrompt.value.trim()) return
  phase.value = 'ideation'
}

// Refine with critique (placeholder)
function refineWithCritique() {
  if (!critiquePrompt.value.trim()) return
}
</script>

<template>
  <div class="design-workspace">
    <!-- Tabs -->
    <div class="tabs">
      <button
        v-for="tab in (['concept', 'review', 'output'] as Tab[])"
        :key="tab"
        class="tab-btn"
        :class="{ active: activeTab === tab }"
        @click="activeTab = tab"
      >
        {{ tabLabels[tab] }}
      </button>
    </div>

    <div class="tab-content">
      <!-- Concept Tab -->
      <div v-if="activeTab === 'concept'" class="tab-pane concept-pane">
        <h3 class="pane-title">Describe what you'd like to build</h3>
        <textarea
          v-model="conceptPrompt"
          class="concept-textarea"
          :placeholder="appType === 'GAME' ? 'Describe your game idea (e.g. Tower defense with dragons and magic)...' : 'Describe what you want to generate (e.g. A landing page for a SaaS product)...'"
          rows="12"
        />
        <button
          class="action-btn"
          :disabled="!conceptPrompt.trim()"
          @click="generateDraft"
        >
          Generate Draft
        </button>
        <p v-if="phase === 'ideation' && !conceptPrompt" class="hint-text">
          Write a description above and click "Generate Draft" to start. Run the workflow from <strong>Blueprint</strong> to execute.
        </p>
      </div>

      <!-- Review Tab -->
      <div v-if="activeTab === 'review'" class="tab-pane review-pane">
        <div v-if="!plan" class="empty-state">
          <p>Run the workflow from <strong>Blueprint</strong> to see results here.</p>
        </div>
        <div v-else class="review-layout">
          <div class="review-plan">
            <h3 class="pane-title">Generated Plan</h3>
            <div class="plan-content">{{ plan }}</div>
          </div>
          <div class="review-critique">
            <h3 class="pane-title">Critique</h3>
            <textarea
              v-model="critiquePrompt"
              class="critique-textarea"
              placeholder="Add your feedback or suggestions for refinement..."
              rows="6"
            />
            <button
              class="action-btn"
              :disabled="!critiquePrompt.trim()"
              @click="refineWithCritique"
            >
              Refine with Critique
            </button>
            <p class="hint-text">
              Copy your critique to the Blueprint and re-run execution to refine the plan.
            </p>
          </div>
        </div>
      </div>

      <!-- Output Tab -->
      <div v-if="activeTab === 'output'" class="tab-pane output-pane">
        <div v-if="files.length === 0" class="empty-state">
          <p>No files generated yet. Run the workflow from Blueprint to generate files.</p>
        </div>
        <div v-else class="output-content">
          <div class="output-header">
            <h3 class="pane-title">Generated Files ({{ files.length }})</h3>
            <button
              v-if="files.length > 1"
              class="action-btn secondary-btn"
              @click="downloadAll"
            >
              Download All
            </button>
          </div>
          <div class="file-list">
            <div
              v-for="(file, index) in files"
              :key="index"
              class="file-item"
            >
              <div class="file-info">
                <svg class="file-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
                  <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                </svg>
                <span class="file-name">{{ file.name }}</span>
                <span v-if="file.size !== undefined" class="file-size">{{ formatSize(file.size) }}</span>
              </div>
              <button class="download-btn" @click="downloadFile(file)" title="Download">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                  <polyline points="7 10 12 15 17 10"/>
                  <line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.design-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

/* Tabs */
.tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border-color);
  padding: 0 1rem;
  flex-shrink: 0;
}

.tab-btn {
  padding: 0.75rem 1.25rem;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}

.tab-btn:hover {
  color: var(--text-secondary);
  background: var(--accent-bg);
}

.tab-btn.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

/* Tab content */
.tab-content {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
}

.tab-pane {
  height: 100%;
}

.pane-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

/* Concept tab */
.concept-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  line-height: 1.6;
  min-height: 200px;
  box-sizing: border-box;
}

.concept-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

/* Review tab */
.review-layout {
  display: flex;
  gap: 1.5rem;
  height: 100%;
}

.review-plan {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.plan-content {
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
  font-family: monospace;
}

.review-critique {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.critique-textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  line-height: 1.6;
  min-height: 120px;
}

.critique-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

/* Output tab */
.output-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.75rem;
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  transition: border-color 0.15s;
}

.file-item:hover {
  border-color: var(--accent);
}

.file-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.file-icon {
  color: var(--accent);
  flex-shrink: 0;
}

.file-name {
  font-size: 0.85rem;
  color: var(--text-primary);
  font-family: monospace;
}

.file-size {
  font-size: 0.75rem;
  color: var(--text-muted);
  font-family: monospace;
}

.download-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-primary);
  color: var(--text-muted);
  cursor: pointer;
  padding: 0;
  transition: all 0.15s;
}

.download-btn:hover {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

/* Action buttons */
.action-btn {
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

.action-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.secondary-btn {
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  margin-top: 0;
}

.secondary-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  border-color: var(--accent);
  color: var(--text-primary);
}

/* Empty state */
.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
  min-height: 200px;
}

/* Hint text */
.hint-text {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.5rem;
  line-height: 1.5;
}
</style>
