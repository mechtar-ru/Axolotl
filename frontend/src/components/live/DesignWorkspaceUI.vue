<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { DesignWorkspaceFile, WorkflowSchema, PlanningModels, PlanQuestion, PlanResponse } from '@/types'
import { schemaApi } from '@/services/api'
import PlanningModelsPicker from './PlanningModelsPicker.vue'

const props = defineProps<{
  appId: string
  appType: 'GAME' | 'GENERATOR'
  executionResult: any
}>()

// ── Phase & Tab State ──────────────────────────────────────

type PlanningPhase = 'concept' | 'outline' | 'refine' | 'execute'

const activeTab = ref<'concept' | 'review' | 'output'>('concept')
const conceptPrompt = ref('')
const phase = ref<PlanningPhase>('concept')
const isGenerating = ref(false)
const generationError = ref<string | null>(null)

// ── Planning State ─────────────────────────────────────────

const planningModels = ref<PlanningModels | null>(null)

// Outline state
const outlinePlan = ref<string | null>(null)
const questions = ref<PlanQuestion[]>([])
const questionAnswers = ref<Record<string, string>>({})

// Refine state
const refinedPlan = ref<string | null>(null)
const userEdits = ref('')

// Files from execution
const files = ref<DesignWorkspaceFile[]>([])

// ── Model Picker ───────────────────────────────────────────

async function loadSchemaState() {
  try {
    const schema = await schemaApi.getSchema(props.appId)
    planningModels.value = schema.planningModels || null
    if (schema.planningOutline) {
      outlinePlan.value = schema.planningOutline
      phase.value = 'outline'
    }
    if (schema.planningRefinedPlan) {
      refinedPlan.value = schema.planningRefinedPlan
      phase.value = 'refine'
    }
    if (schema.planningOutline || schema.planningRefinedPlan) {
      activeTab.value = 'review'
    }
    // Restore concept context (prompt, questions, answers, edits)
    if (schema.planningContext) {
      restoreContext(schema.planningContext)
    }
  } catch {
    // Silently fail - defaults will be used
  }
}

async function savePlanningModels(models: PlanningModels) {
  planningModels.value = models
  await schemaApi.updatePlanningModels(props.appId, models)
}

// ── Context Persistence ────────────────────────────────────

let saveContextTimer: ReturnType<typeof setTimeout> | null = null

function scheduleSaveContext() {
  if (saveContextTimer) clearTimeout(saveContextTimer)
  saveContextTimer = setTimeout(saveContext, 1000)
}

async function saveContext() {
  if (!outlinePlan.value && !refinedPlan.value) return // nothing to save yet
  const ctx = JSON.stringify({
    conceptPrompt: conceptPrompt.value,
    questions: questions.value,
    questionAnswers: questionAnswers.value,
    userEdits: userEdits.value,
  })
  try {
    await schemaApi.updatePlanningContext(props.appId, ctx)
  } catch {
    // silent — context save should never block the user
  }
}

function restoreContext(contextJson: string) {
  try {
    const ctx = JSON.parse(contextJson)
    if (ctx.conceptPrompt) conceptPrompt.value = ctx.conceptPrompt
    if (ctx.questions) questions.value = ctx.questions
    if (ctx.questionAnswers) questionAnswers.value = ctx.questionAnswers
    if (ctx.userEdits) userEdits.value = ctx.userEdits
  } catch {
    // silent — invalid JSON means no context to restore
  }
}

// Auto-save context when user edits answers or feedback
watch([userEdits, questionAnswers], () => {
  if (outlinePlan.value || refinedPlan.value) {
    scheduleSaveContext()
  }
}, { deep: true })

// ── Level 1: Outline ───────────────────────────────────────

async function generateOutline() {
  const prompt = conceptPrompt.value.trim()
  if (!prompt || isGenerating.value) return

  isGenerating.value = true
  generationError.value = null

  try {
    const model = planningModels.value?.fast || undefined
    const response = await schemaApi.plan(props.appId, {
      prompt,
      level: 'outline',
      model: model || '',
    })

    outlinePlan.value = response.content
    if (response.questions) {
      questions.value = response.questions
      // Initialize answers with defaults
      const answers: Record<string, string> = {}
      for (const q of response.questions) {
        answers[q.id] = q.defaultAnswer
      }
      questionAnswers.value = answers
    }

    phase.value = 'outline'
    activeTab.value = 'review'
    // Persist outline to schema so it survives navigation/refresh
    await schemaApi.updatePlanningOutline(props.appId, outlinePlan.value)
    // Persist concept context (prompt, questions, answers) for reload
    await saveContext()
  } catch (err: any) {
    generationError.value = err.message || 'Failed to generate outline'
    // Stay on concept tab on error — don't lose progress
  } finally {
    isGenerating.value = false
  }
}

// ── Level 2: Refine ────────────────────────────────────────

async function refinePlan() {
  if (!outlinePlan.value || isGenerating.value) return

  isGenerating.value = true
  generationError.value = null

  try {
    const model = planningModels.value?.medium || undefined
    const response = await schemaApi.plan(props.appId, {
      prompt: conceptPrompt.value.trim(),
      level: 'refine',
      model: model || '',
      context: {
        outline: outlinePlan.value,
        userEdits: userEdits.value,
        answers: questionAnswers.value,
      },
    })

    refinedPlan.value = response.content
    phase.value = 'refine'
    // Persist refined plan to schema so it survives navigation/refresh
    await schemaApi.updatePlanningRefinedPlan(props.appId, refinedPlan.value)
    // Persist latest edits/answers for reload
    await saveContext()
  } catch (err: any) {
    generationError.value = err.message || 'Failed to refine plan'
    // Show error but keep outline visible — don't lose progress
  } finally {
    isGenerating.value = false
  }
}

// ── Level 3: Execute ───────────────────────────────────────

async function executePlan() {
  const planContent = refinedPlan.value || outlinePlan.value
  if (!planContent || isGenerating.value) return

  isGenerating.value = true
  generationError.value = null

  try {
    // Use existing generateDraft logic: write plan to sourceNode sourceData → execute schema
    const schema = await schemaApi.getSchema(props.appId)
    const sourceNode = schema.nodes?.find(n => n.type === 'source')
    if (!sourceNode) {
      throw new Error('No source node found in this schema. Add a Source node to execute the plan.')
    }
    sourceNode.data = { ...sourceNode.data, sourceData: planContent }
    await schemaApi.updateSchema(props.appId, schema)
    await schemaApi.executeSchema(props.appId)

    // Plan has been consumed into sourceData — clear persisted plans
    await schemaApi.updatePlanningOutline(props.appId, null)
    await schemaApi.updatePlanningRefinedPlan(props.appId, null)
    await schemaApi.clearPlanningContext(props.appId)

    phase.value = 'execute'
  } catch (err: any) {
    generationError.value = err.message || 'Failed to execute plan'
  } finally {
    isGenerating.value = false
  }
}

// ── Watch execution results ────────────────────────────────

watch(() => props.executionResult, (result) => {
  if (!result) return

  if (result.files && Array.isArray(result.files)) {
    files.value = result.files.map((f: any) => ({
      name: f.name || 'unnamed',
      content: f.content || '',
      type: f.type || 'text/plain',
      size: f.size ?? (f.content ? new Blob([f.content]).size : undefined),
    }))
    isGenerating.value = false
    generationError.value = null
    activeTab.value = 'output'
  }
})

// ── Init ───────────────────────────────────────────────────

loadSchemaState()

// ── Helpers ────────────────────────────────────────────────

const tabLabels: Record<string, string> = {
  concept: 'Concept',
  review: 'Review',
  output: 'Output',
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

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

function downloadAll() {
  files.value.forEach(f => downloadFile(f))
}
</script>

<template>
  <div class="design-workspace">
    <!-- Tabs -->
    <div class="tabs">
      <button
        v-for="tab in (['concept', 'review', 'output'] as const)"
        :key="tab"
        class="tab-btn"
        :class="{ active: activeTab === tab }"
        @click="activeTab = tab"
      >
        {{ tabLabels[tab] }}
      </button>
    </div>

    <div class="tab-content">
      <!-- ========== Concept Tab ========== -->
      <div v-if="activeTab === 'concept'" class="tab-pane concept-pane">
        <h3 class="pane-title">Describe what you'd like to build</h3>
        <textarea
          v-model="conceptPrompt"
          class="concept-textarea"
          :placeholder="appType === 'GAME' ? 'Describe your game idea...' : 'Describe what you want to generate...'"
          rows="12"
        />
        <PlanningModelsPicker
          :model-value="planningModels"
          :default-model="'gpt-4o'"
          @update:model-value="savePlanningModels"
        />
        <div class="action-row">
          <button
            class="action-btn"
            :disabled="!conceptPrompt.trim() || isGenerating"
            @click="generateOutline"
          >
            <span v-if="isGenerating" class="spinner" />
            {{ isGenerating ? 'Generating Outline...' : 'Generate Draft' }}
          </button>
        </div>
        <p v-if="generationError" class="error-text">{{ generationError }}</p>
        <p v-else-if="isGenerating" class="hint-text">
          Generating your plan outline...
        </p>
        <p v-else-if="!conceptPrompt" class="hint-text">
          Write a description above and click "Generate Draft" to start.
        </p>
      </div>

      <!-- ========== Review Tab ========== -->
      <div v-if="activeTab === 'review'" class="tab-pane review-pane">
        <!-- Outline phase: plan + editable questions -->
        <div v-if="outlinePlan && !refinedPlan" class="review-layout">
          <div class="review-plan">
            <h3 class="pane-title">Outline Plan</h3>
            <div class="plan-content">{{ outlinePlan }}</div>

            <!-- Questions -->
            <div v-if="questions.length > 0" class="questions-section">
              <h3 class="pane-title">Clarifying Questions</h3>
              <div class="questions-list">
                <div v-for="q in questions" :key="q.id" class="question-item">
                  <label class="question-label">{{ q.text }}</label>
                  <div v-if="q.options && q.options.length > 0" class="question-options">
                    <label
                      v-for="opt in q.options"
                      :key="opt"
                      class="option-label"
                      :class="{ selected: questionAnswers[q.id] === opt }"
                    >
                      <input
                        type="radio"
                        :name="'q-' + q.id"
                        :value="opt"
                        v-model="questionAnswers[q.id]"
                      />
                      {{ opt }}
                    </label>
                  </div>
                  <input
                    v-else
                    v-model="questionAnswers[q.id]"
                    type="text"
                    class="question-input"
                    placeholder="Your answer..."
                  />
                </div>
              </div>
            </div>
          </div>
          <div class="review-sidebar">
            <h3 class="pane-title">Feedback / Edits</h3>
            <textarea
              v-model="userEdits"
              class="critique-textarea"
              placeholder="Add your feedback, corrections, or additional requirements..."
              rows="8"
            />
            <button
              class="action-btn"
              :disabled="isGenerating"
              @click="refinePlan"
            >
              <span v-if="isGenerating" class="spinner" />
              {{ isGenerating ? 'Refining...' : 'Refine Plan' }}
            </button>
            <p v-if="generationError" class="error-text">{{ generationError }}</p>
            <p class="hint-text">
              Edit questions above and add feedback, then click "Refine Plan" to generate a detailed design document.
            </p>
          </div>
        </div>

        <!-- Refined plan phase: detailed document + execute -->
        <div v-else-if="refinedPlan" class="review-layout">
          <div class="review-plan">
            <h3 class="pane-title">Refined Design Document</h3>
            <div class="plan-content">{{ refinedPlan }}</div>
          </div>
          <div class="review-sidebar">
            <h3 class="pane-title">Ready to Execute?</h3>
            <p class="hint-text">
              The plan above will be used as source data for your workflow. Click "Execute Plan" to run it.
            </p>
            <button
              class="action-btn execute-btn"
              :disabled="isGenerating"
              @click="executePlan"
            >
              <span v-if="isGenerating" class="spinner" />
              {{ isGenerating ? 'Executing...' : 'Execute Plan' }}
            </button>
            <button
              class="action-btn secondary-btn"
              :disabled="isGenerating"
              @click="refinedPlan = null; userEdits = ''"
            >
              Back to Outline
            </button>
            <p v-if="generationError" class="error-text">{{ generationError }}</p>
          </div>
        </div>

        <!-- Empty state -->
        <div v-else class="empty-state">
          <p>Generate a draft from the <strong>Concept</strong> tab to see results here.</p>
        </div>
      </div>

      <!-- ========== Output Tab ========== -->
      <div v-if="activeTab === 'output'" class="tab-pane output-pane">
        <div v-if="files.length === 0" class="empty-state">
          <p>No files generated yet. Refine your plan and execute it from the <strong>Review</strong> tab.</p>
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

.action-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-secondary);
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s;
  padding: 0;
}

.icon-btn:hover {
  background: var(--accent-bg);
  border-color: var(--accent);
  color: var(--accent);
}

.review-layout {
  display: flex;
  gap: 1.5rem;
  height: 100%;
}

.review-plan {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.review-sidebar {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
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
  min-height: 200px;
  max-height: 500px;
}

.questions-section {
  margin-top: 1rem;
}

.questions-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.question-item {
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.question-label {
  display: block;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.question-options {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.option-label {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.8rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s;
  background: var(--bg-primary);
}

.option-label:hover {
  border-color: var(--accent);
}

.option-label.selected {
  border-color: var(--accent);
  background: var(--accent-bg);
  color: var(--accent);
}

.option-label input {
  display: none;
}

.question-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.85rem;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: inherit;
  box-sizing: border-box;
}

.question-input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
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
  box-sizing: border-box;
  width: 100%;
}

.critique-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

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

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
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
  white-space: nowrap;
}

.action-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.execute-btn {
  background: #22c55e;
}

.execute-btn:hover:not(:disabled) {
  background: #16a34a;
}

.secondary-btn {
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.secondary-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  border-color: var(--accent);
  color: var(--text-primary);
}

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

.error-text {
  font-size: 0.8rem;
  color: var(--danger);
  margin-top: 0.5rem;
  line-height: 1.5;
  padding: 0.5rem 0.75rem;
  background: var(--danger-bg);
  border-radius: 6px;
  border: 1px solid var(--danger);
}

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.hint-text {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.5rem;
  line-height: 1.5;
}
</style>
