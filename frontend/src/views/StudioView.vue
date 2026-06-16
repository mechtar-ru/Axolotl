<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated, onDeactivated, provide, watch, defineOptions } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import { useCanvasStore } from '@/stores/useCanvasStore'
import { usePipelineStore } from '@/stores/usePipelineStore'
import { useReviewStore } from '@/stores/useReviewStore'
import { useAuthStore } from '@/stores/authStore'
import { schemaApi, planApi } from '@/services/api'
import type { WorkflowSchema } from '@/types'
import { useWebSocket } from '@/composables/useWebSocket'
import { provideExecutionState, createExecutionState } from '@/composables/useExecutionState'
import StudioTopBar from '@/components/studio/StudioTopBar.vue'
import BlueprintView from '@/components/studio/BlueprintView.vue'
import TimelineView from '@/components/studio/TimelineView.vue'
import QuickStartDialog from '@/components/studio/QuickStartDialog.vue'
import ResumeBanner from '@/components/studio/ResumeBanner.vue'
import PromptToSchemaModal from '@/components/editor/PromptToSchemaModal.vue'
import ReviewApprovalDialog from '@/components/studio/ReviewApprovalDialog.vue'
import DepsInstallDialog from '@/components/studio/DepsInstallDialog.vue'
import DiffReviewDialog from '@/components/studio/DiffReviewDialog.vue'
import ThoughtsPanel from '@/components/studio/ThoughtsPanel.vue'
import PipelinePanel from '@/components/studio/PipelinePanel.vue'
import type { ReviewData, ReviewFinding } from '@/stores/useReviewStore'
import { useToast } from '@/composables/useToast'

defineOptions({ name: 'StudioView' })

type StudioMode = 'blueprint' | 'timeline'

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()
const canvasStore = useCanvasStore()
const pipelineStore = usePipelineStore()
const reviewStore = useReviewStore()
const authStore = useAuthStore()
const toast = useToast()

const activeMode = ref<StudioMode>('blueprint')
const appId = ref('')

// WebSocket for real-time execution events
const { connect, disconnect } = useWebSocket()

// Guard against stale updates after unmount
let isActive = true

// App state
const app = computed(() => {
  return schemaStore.schemas.find(s => s.id === appId.value) || canvasStore.currentSchema
})

  const isRunning = ref(false)
const executionError = ref<string | null>(null)
const sessionGoal = ref('')
const sessionGoalOpen = ref(false)
let sessionGoalTimer: ReturnType<typeof setTimeout> | null = null
  // Sync execState.isExecuting alongside isRunning so TimelineView can auto-refresh
  function setIsExecuting(val: boolean) {
    isRunning.value = val
    if (execState) execState.isExecuting.value = val
  }

// Execution state from WebSocket events
const nodeResults = ref<Record<string, any>>({})      // nodeId → result content
const nodeStatuses = ref<Record<string, string>>({})   // nodeId → status (running/completed/failed)
const executionProgress = ref<{ totalNodes: number; completedNodes: number } | null>(null)

// Review approval state
const currentExecutionId = ref('')
const showReviewDialog = ref(false)
const reviewPlan = ref('')
const reviewNodeId = ref('')
const reviewFindings = ref<ReviewFinding[]>([])
const reviewIteration = ref(1)
const reviewMode = ref('manual')

// Deps install state
const showDepsDialog = ref(false)
const depsMissing = ref<string[]>([])
const depsProjectPath = ref('')

// Diff review state
const showDiffDialog = ref(false)
const diffDiffs = ref<Array<{filePath: string; diff: string; originalLength: number; newLength: number}>>([])
const diffNodeId = ref('')

// Thoughts/Reasoning display state
const showThoughtsPanel = ref(false)
const currentReasoningNodeId = ref('')
const currentReasoning = ref('')
const nodeReasonings = ref<Record<string, string>>({})  // nodeId → reasoning text

const showPipelinePanel = ref(false)
const hasFailedRun = ref(false)

async function checkFailedRun() {
  if (!appId.value) return
  try {
    const runs = await schemaApi.getRuns(appId.value)
    hasFailedRun.value = runs.some((r: any) => r.status === 'failed')
  } catch { /* ignore */ }
}

async function handleRetry() {
  if (isRunning.value) return
  try {
    await pipelineStore.retryPipeline(appId.value)
    await startExecution(true)
  } catch (e) {
    toast.error('Failed to retry: ' + ((e as Error).message || e))
  }
}

// Provide state for child components
const onReasoningBadgeClick = (nodeId: string) => {
  currentReasoningNodeId.value = nodeId
  currentReasoning.value = nodeReasonings.value[nodeId] || ''
  showThoughtsPanel.value = true
}

provide('appState', {
  app,
  isRunning,
  activeMode,
  appId,
  onReasoningBadgeClick
})

provide('isRunning', isRunning)
provide('nodeResults', nodeResults)
provide('nodeStatuses', nodeStatuses)
provide('executionProgress', executionProgress)

/**
 * Start execution with WebSocket connection.
 * @param skipSave — if true, skip schemaStore.updateSchema (caller already saved)
 */
const startExecution = async (skipSave: boolean = false, sessionInput?: string): Promise<void> => {
    if (isRunning.value) {
      console.warn('Execution already in progress')
      return
    }
    setIsExecuting(true)
    executionError.value = null
  nodeResults.value = {}
  nodeStatuses.value = {}
  nodeReasonings.value = {}
  executionProgress.value = null
  execState.stepEvents.value = []
  nodeStartTimes.clear()
  stepCounter = 0

  const currentApp = app.value
  if (!currentApp) {
    executionError.value = 'No app selected'
    setIsExecuting(false)
    return
  }

  connect(appId.value, {
    onDisconnect: () => {
      if (isActive) {
        setIsExecuting(false)
      }
    },
    onProgress: (data) => {
      if (!isActive) return
      nodeStatuses.value[data.nodeId] = data.status
      if (!nodeStartTimes.has(data.nodeId)) {
        nodeStartTimes.set(data.nodeId, Date.now())
      }
      if (data.status === 'running') {
        addStepEvent(data.nodeId, 'running')
      }
      if (data.progress !== undefined) {
        executionProgress.value = {
          totalNodes: (data as any).totalNodes || 0,
          completedNodes: (data as any).completedNodes || 0
        }
      }
    },
    onResult: (data) => {
      if (!isActive) return
      nodeResults.value[data.nodeId] = data.result
      addStepEvent(data.nodeId, 'completed')
    },
    onComplete: () => {
      if (!isActive) return
      setIsExecuting(false)
      addStepEvent('__execution__', 'completed', 'Execution finished')
      if (activeMode.value === 'timeline') {
        activeMode.value = 'blueprint'
      }
    },
    onError: (data) => {
      if (!isActive) return
      nodeStatuses.value[data.nodeId] = 'failed'
      executionError.value = data.error
      setIsExecuting(false)
      addStepEvent(data.nodeId, 'failed', data.error)
    },
    onLiveUpdate: (data) => {
      if (!isActive) return
      const payload = data.payload as Record<string, any>
      if (payload?.status === 'AWAITING_APPROVAL') {
        currentExecutionId.value = data.schemaId
        reviewNodeId.value = payload.nodeId || ''

        // Plan may be at payload.plan, payload.rewrittenPlan, or nested inside payload.findings.plan
        const findingsObj = payload.findings as Record<string, any> | undefined
        reviewPlan.value = payload.rewrittenPlan || payload.plan || findingsObj?.plan || ''

        // Findings items may be at payload.findings (array/string) or payload.findings.findings
        reviewFindings.value = parseFindings(findingsObj?.findings ?? payload.findings)
        reviewIteration.value = 1
        reviewMode.value = payload.mode || 'manual'
        showReviewDialog.value = true
      }
    },
    onDepsNeeded: (data) => {
      if (!isActive) return
      depsMissing.value = data.missing
      depsProjectPath.value = data.projectPath
      if (data.schemaId && !currentExecutionId.value) {
        currentExecutionId.value = data.schemaId
      }
      showDepsDialog.value = true
    },
    onDiffsNeeded: (data) => {
      if (!isActive) return
      diffDiffs.value = data.diffs || []
      diffNodeId.value = data.nodeId
      showDiffDialog.value = true
    },
    onReasoning: (data) => {
      if (!isActive) return
      nodeReasonings.value[data.nodeId] = data.reasoning
      // Store the most recent reasoning for display
      currentReasoningNodeId.value = data.nodeId
      currentReasoning.value = data.reasoning
    },
    onReconnect: async (schemaId) => {
      console.log('🔌 WebSocket reconnected, refreshing schema state for', schemaId)
      // Refresh schema state to recover from stale WS state
      try {
        const schema = await schemaApi.getSchema(schemaId)
        if (schema) {
          canvasStore.currentSchema =(schema)
        }
        // Re-query execution runs to get current status
        const runs = await schemaApi.getRuns(schemaId)
        if (runs.length > 0) {
          const latestRun = runs[0]!
          if (latestRun.status === 'running' || latestRun.status === 'paused') {
            setIsExecuting(true)
          } else if (latestRun.status === 'completed') {
            setIsExecuting(false)
            executionError.value = null
          }
        }
      } catch (e) {
        console.warn('🔌 Reconnect state recovery failed:', e)
      }
    },
  })

  if (!skipSave) {
    try {
      await canvasStore.flushSave()
    } catch (e) {
      toast.error('Failed to save: ' + ((e as Error).message || e))
    }
  }

  try {
    await schemaApi.executeSchema(appId.value, 'EXECUTE', sessionInput)
  } catch (err) {
    executionError.value = (err as Error).message
    toast.error('Execution failed to start: ' + ((err as Error).message || err))
    setIsExecuting(false)
    disconnect()
  }
}
provide('startExecution', startExecution)

// Execution state for TimelineView
const execState = createExecutionState()
provideExecutionState(execState)

const nodeStartTimes = new Map<string, number>()
let stepCounter = 0

const showQuickStart = ref(false)

function onShowQuickStart() {
  showQuickStart.value = true
}

const showGenerateFromPrompt = ref(false)

function onShowGenerateFromPrompt() {
  showGenerateFromPrompt.value = true
}

async function handleExportSchema() {
  if (!app.value?.id) return
  try {
    const schema = await schemaApi.exportSchema(app.value.id)
    const blob = new Blob([JSON.stringify(schema, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${schema.name || 'schema'}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (err) {
    console.error('Export failed:', err)
  }
}

const blueprintRef = ref<any>(null)
const canUndo = computed(() => blueprintRef.value?.undoRedo.canUndo.value ?? false)
const canRedo = computed(() => blueprintRef.value?.undoRedo.canRedo.value ?? false)

function handleUndo() {
  blueprintRef.value?.undoRedo.undo()
}

function handleRedo() {
  blueprintRef.value?.undoRedo.redo()
}

function onAddToCanvas(schema: WorkflowSchema) {
  schemaStore.updateSchema(schema)
  showQuickStart.value = false
}

function addStepEvent(nodeId: string, status: string, details?: string) {
  const schema = app.value
  const nodeDef = schema?.nodes?.find((n: any) => n.id === nodeId)
  const now = Date.now()
  const startTime = nodeStartTimes.get(nodeId) || now
  const duration = now - startTime

  execState.stepEvents.value.push({
    stepIndex: stepCounter++,
    blockId: nodeId,
    blockType: nodeDef?.type || 'agent',
    label: nodeDef?.name || nodeId,
    status,
    details: details || '',
    duration,
    timestamp: now
  })
}

function parseFindings(findings: any): ReviewFinding[] {
  if (!findings) return []
  if (Array.isArray(findings)) return findings
  if (typeof findings === 'string') {
    try {
      return JSON.parse(findings)
    } catch {}
  }
  return []
}

async function handleReviewApprove() {
  showReviewDialog.value = false
  try {
    await reviewStore.approveReview(currentExecutionId.value, reviewNodeId.value)
  } catch (e) {
    toast.error('Failed to approve review: ' + ((e as Error).message || e))
    executionError.value = 'Failed to approve review — execution may still be paused'
  }
}

async function handleReviewReject() {
  showReviewDialog.value = false
  try {
    await reviewStore.rejectReview(currentExecutionId.value, reviewNodeId.value)
  } catch (e) {
    toast.error('Failed to reject review: ' + ((e as Error).message || e))
    executionError.value = 'Failed to reject review'
  }
}

async function handleResume() {
  try {
    await schemaApi.resumeSchema(appId.value)
    await startExecution(true)
  } catch (e) {
    toast.error('Failed to resume: ' + ((e as Error).message || e))
  }
}

async function handleRestart() {
  try {
    await schemaApi.executeSchema(appId.value, 'EXECUTE')
    setIsExecuting(true)
    executionError.value = null
    nodeResults.value = {}
    nodeStatuses.value = {}
    executionProgress.value = null
    execState.stepEvents.value = []
    nodeStartTimes.clear()
    stepCounter = 0
  } catch (e) {
    toast.error('Failed to restart: ' + ((e as Error).message || e))
  }
}

function handleDismiss() {
  // User dismissed the banner - no action needed
}

// Load app on mount
onMounted(async () => {
  appId.value = route.params.id as string
  
  // If schemas aren't loaded yet, load them
  if (schemaStore.schemas.length === 0) {
    await schemaStore.loadSchemas()
  }
  
  // Set current schema
  const found = schemaStore.schemas.find(s => s.id === appId.value)
  if (found) {
    canvasStore.currentSchema = found
    document.title = `${found.name} - Axolotl Studio`
  }
  checkFailedRun()
  loadSessionGoal()
})

// Watch route param changes — needed when navigating between schemas
// while the component stays alive (same route, different :id)
watch(() => route.params.id, async (newId) => {
  if (appId.value && newId && appId.value !== newId) {
    // Save current state before switching
    try {
      await canvasStore.flushSave()
    } catch (err) {
      console.error('Failed to save canvas state before schema switch:', err)
    }

    // Reset execution state from previous schema
    nodeResults.value = {}
    nodeStatuses.value = {}
    executionProgress.value = null
    if (execState?.stepEvents) execState.stepEvents.value = []
    nodeStartTimes.clear()
    stepCounter = 0

    appId.value = newId as string
    const found = schemaStore.schemas.find(s => s.id === appId.value)
    if (found) {
      canvasStore.currentSchema = found
      document.title = `${found.name} - Axolotl Studio`
    }
  }
})

// Mode switching
function setMode(mode: StudioMode) {
  activeMode.value = mode
}

function toggleRun() {
  if (isRunning.value) {
    executionError.value = null
    disconnect()
    schemaApi.stopSchema(appId.value)
      .catch((err: Error) => {
        toast.error('Failed to stop execution: ' + (err.message || err))
        executionError.value = 'Failed to stop execution'
      })
    setIsExecuting(false)
  } else {
    startExecution(false)
  }
}

// Cleanup WebSocket on unmount
onUnmounted(() => {
  isActive = false
  disconnect()
  if (sessionGoalTimer) { clearTimeout(sessionGoalTimer); sessionGoalTimer = null }
})

// Flush pending saves + disconnect WebSocket when navigating away
onDeactivated(() => {
  if (sessionGoalTimer) { clearTimeout(sessionGoalTimer); sessionGoalTimer = null }
  try {
    canvasStore.flushSave()  // ensure dirty edits reach backend
  } catch (e) {
    toast.error('Failed to save: ' + ((e as Error).message || e))
  }
  if (isRunning.value) {
    disconnect()
  }
})

// Refresh schema data when coming back to a cached session
onActivated(async () => {
  const currentId = route.params.id as string
  if (!currentId) return

  // Sync auth token from localStorage (may have been updated by addInitScript between navigations)
  const storedToken = localStorage.getItem('axolotl_token')
  if (storedToken && authStore.token !== storedToken) {
    authStore.token = storedToken
  }

  const changed = currentId !== appId.value
  appId.value = currentId

  // Always re-fetch schema from backend to get latest persisted state
  // This ensures any changes made via BlockConfigPanel (model select, prompt, etc.)
  // are reflected even if the save was still in-flight when the user navigated away
  try {
    const fresh = await schemaApi.getSchema(currentId)
    if (fresh) {
      // Update both the schemas list and currentSchema
      const idx = schemaStore.schemas.findIndex(s => s.id === currentId)
      if (idx !== -1) {
        schemaStore.schemas[idx] = fresh
      } else {
        schemaStore.schemas.push(fresh)
      }
      canvasStore.currentSchema = fresh
      document.title = `${fresh.name} - Axolotl Studio`
    }
  } catch {
    // Fallback: use cached data
    const found = schemaStore.schemas.find(s => s.id === appId.value)
    if (found) {
      canvasStore.currentSchema = found
      document.title = `${found.name} - Axolotl Studio`
    } else {
      // Schema not in cached store — try reloading from backend
      await schemaStore.loadSchemas()
      const reloaded = schemaStore.schemas.find(s => s.id === appId.value)
      if (reloaded) {
        canvasStore.currentSchema = reloaded
        document.title = `${reloaded.name} - Axolotl Studio`
      }
    }
  }

  // Restore execution state from persisted backend data if an active/paused run exists
  try {
    const run = await schemaApi.getPausedRun(currentId)
    if (run) {
      const nodes = await schemaApi.getRunNodes(currentId, run.id)
      if (nodes.length > 0) {
        executionProgress.value = {
          totalNodes: nodes.length,
          completedNodes: nodes.filter(n => n.status === 'completed' || n.status === 'failed').length
        }
        for (const n of nodes) {
          if (n.nodeId) {
            nodeStatuses.value[n.nodeId] = n.status
            if (n.outputSummary) {
              nodeResults.value[n.nodeId] = n.outputSummary
            }
          }
        }
      }
    }
  } catch {
    // Not critical — ResumeBanner will show independently, or no active run exists
  }
})

// Session goal
async function loadSessionGoal() {
  if (!appId.value) return
  try {
    sessionGoal.value = await planApi.getSessionGoal()
  } catch {
    // Not critical
  }
}

function onSessionGoalInput() {
  if (sessionGoalTimer) clearTimeout(sessionGoalTimer)
  sessionGoalTimer = setTimeout(() => {
    if (appId.value) {
      planApi.setSessionGoal(sessionGoal.value).catch(() => {})
    }
  }, 800)
}

// Navigate back to dashboard
function goToDashboard() {
  router.push('/')
}
</script>

<template>
  <div class="studio">
    <StudioTopBar
      :app-name="app?.name || 'Untitled'"
      :active-mode="activeMode"
      :is-running="isRunning"
      :can-undo="canUndo"
      :can-redo="canRedo"
      @set-mode="setMode"
      @toggle-run="toggleRun"
      @back="goToDashboard"
      @show-quick-start="onShowQuickStart"
      @show-generate-from-prompt="onShowGenerateFromPrompt"
      @export-schema="handleExportSchema"
      @undo="handleUndo"
      @redo="handleRedo"
    />
    
    <ResumeBanner
      :schema-id="appId"
      @resume="handleResume"
      @restart="handleRestart"
      @dismiss="handleDismiss"
    />
    
    <div class="session-goal-bar">
      <button class="session-goal-toggle" @click="sessionGoalOpen = !sessionGoalOpen">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>
        </svg>
        Session Goal
        <svg
          width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          :class="['chevron', { open: sessionGoalOpen }]"
        >
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>
      <div v-if="sessionGoalOpen" class="session-goal-body">
        <textarea
          v-model="sessionGoal"
          class="session-goal-input"
          placeholder="Describe what you want to achieve in this session..."
          rows="3"
          @input="onSessionGoalInput"
        />
      </div>
    </div>
    
    <div class="studio-toolbar">
      <button
        class="toolbar-btn"
        :class="{ active: showPipelinePanel }"
        title="Pipeline"
        @click="showPipelinePanel = !showPipelinePanel"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
        </svg>
        Pipeline
      </button>
      <button
        v-if="!isRunning && hasFailedRun"
        class="toolbar-btn retry-btn"
        title="Retry from last failed stage"
        @click="handleRetry"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
        </svg>
        Retry
      </button>
    </div>

    <div class="studio-content" :class="{ 'with-pipeline': showPipelinePanel }">
      <div class="pipeline-sidebar" v-if="showPipelinePanel && activeMode === 'blueprint'">
        <PipelinePanel />
      </div>
      <div class="main-content">
        <BlueprintView
          ref="blueprintRef"
          v-show="activeMode === 'blueprint'"
          :app-id="appId"
        />
        <TimelineView
          v-show="activeMode === 'timeline'"
          :schema-id="appId"
          @select-block="(blockId) => {
            if (blockId === '__execution__') return
            activeMode = 'blueprint'
          }"
        />
      </div>
    </div>

    <QuickStartDialog
      :visible="showQuickStart"
      :app-id="appId"
      @close="showQuickStart = false"
      @add-to-canvas="onAddToCanvas"
    />

    <PromptToSchemaModal
      :visible="showGenerateFromPrompt"
      @close="showGenerateFromPrompt = false"
    />

    <ReviewApprovalDialog
      :visible="showReviewDialog"
      :schema-id="appId"
      :execution-id="currentExecutionId"
      :node-id="reviewNodeId"
      :original-plan="reviewPlan"
      :rewritten-plan="reviewPlan"
      :findings="reviewFindings"
      :iteration="reviewIteration"
      :max-iterations="3"
      :mode="reviewMode"
      :feedback-history="[]"
      @approve="handleReviewApprove"
      @reject="handleReviewReject"
    />

    <DepsInstallDialog
      :visible="showDepsDialog"
      :schema-id="appId"
      :execution-id="currentExecutionId"
      :node-id="reviewNodeId"
      :deps="depsMissing"
      :project-path="depsProjectPath"
      @close="showDepsDialog = false"
    />

    <DiffReviewDialog
      :visible="showDiffDialog"
      :schema-id="appId"
      :execution-id="currentExecutionId"
      :node-id="diffNodeId"
      :diffs="diffDiffs"
      @close="showDiffDialog = false"
    />

    <ThoughtsPanel
      :isOpen="showThoughtsPanel"
      :reasoning="currentReasoning"
      @close="showThoughtsPanel = false"
    />
  </div>
</template>

<style scoped>
.session-goal-bar {
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.session-goal-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px var(--space-3);
  width: 100%;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  background: none;
  border: none;
  cursor: pointer;
}
.session-goal-toggle:hover {
  background: var(--bg-hover);
}

.session-goal-toggle .chevron {
  margin-left: auto;
  transition: transform 0.2s;
}
.session-goal-toggle .chevron.open {
  transform: rotate(180deg);
}

.session-goal-body {
  border-top: 1px solid var(--border-color);
}

.session-goal-input {
  display: block;
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: none;
  resize: vertical;
  font-family: inherit;
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--text-primary);
  background: var(--bg-primary);
  outline: none;
}
.session-goal-input:focus {
  background: var(--bg-secondary);
}
.session-goal-input::placeholder {
  color: var(--text-muted);
}

.studio {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-canvas);
}

.placeholder-view {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-muted);
  gap: var(--space-3);
}

.placeholder-icon {
  color: var(--text-muted);
  opacity: 0.4;
}

.placeholder-view h2 {
  margin: 0;
  font-size: var(--text-lg);
  color: var(--text-secondary);
}

.placeholder-view p {
  margin: 0;
  font-size: var(--text-sm);
}

.studio-toolbar {
  display: flex;
  gap: 4px;
  padding: 4px 12px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-color);
}

.toolbar-btn {
  padding: 4px 10px;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  transition: all 0.15s;
}

.toolbar-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.toolbar-btn.active {
  background: var(--accent-bg);
  border-color: var(--accent);
  color: var(--accent);
}

.retry-btn {
  margin-left: auto;
  color: var(--warning);
}
.retry-btn:hover {
  background: var(--warning-bg);
  border-color: var(--warning);
  color: var(--warning);
}

.studio-content {
  flex: 1;
  overflow: hidden;
  position: relative;
  display: flex;
}

.studio-content.with-pipeline {
  display: flex;
}

.pipeline-sidebar {
  width: 320px;
  min-width: 320px;
  border-right: 1px solid var(--border-color);
  overflow-y: auto;
  background: var(--bg-surface);
}

.main-content {
  flex: 1;
  overflow: hidden;
  position: relative;
}
</style>
