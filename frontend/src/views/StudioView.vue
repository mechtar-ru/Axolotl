<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated, onDeactivated, provide, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import { useAuthStore } from '@/stores/authStore'
import { schemaApi } from '@/services/api'
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
import PipelinePanel from '@/components/studio/PipelinePanel.vue'
import type { ReviewData, ReviewFinding } from '@/stores/schemaStore'

type StudioMode = 'blueprint' | 'timeline'

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()
const authStore = useAuthStore()

const activeMode = ref<StudioMode>('blueprint')
const appId = ref('')

// WebSocket for real-time execution events
const { connect, disconnect } = useWebSocket()

// Guard against stale updates after unmount
let isActive = true

// App state
const app = computed(() => {
  return schemaStore.schemas.find(s => s.id === appId.value) || schemaStore.currentSchema
})

const isRunning = ref(false)
const executionError = ref<string | null>(null)

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

const showPipelinePanel = ref(false)

// Provide state for child components
provide('appState', {
  app,
  isRunning,
  activeMode,
  appId
})

const showExecutionOverlay = computed(() => isRunning.value && activeMode.value === 'blueprint')
provide('showExecutionOverlay', showExecutionOverlay)

// LiveView injects isRunning directly — provide it separately too
provide('isRunning', isRunning)
provide('nodeResults', nodeResults)
provide('nodeStatuses', nodeStatuses)
provide('executionProgress', executionProgress)

/**
 * Start execution with WebSocket connection.
 * @param skipSave — if true, skip schemaStore.updateSchema (caller already saved)
 */
const startExecution = async (skipSave: boolean = false): Promise<void> => {
  isRunning.value = true
  executionError.value = null
  nodeResults.value = {}
  nodeStatuses.value = {}
  executionProgress.value = null
  execState.stepEvents.value = []
  nodeStartTimes.clear()
  stepCounter = 0

  const currentApp = app.value
  if (!currentApp) {
    executionError.value = 'No app selected'
    isRunning.value = false
    return
  }

  connect(appId.value, {
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
      isRunning.value = false
      addStepEvent('__execution__', 'completed', 'Execution finished')
      if (activeMode.value === 'timeline') {
        activeMode.value = 'blueprint'
      }
    },
    onError: (data) => {
      if (!isActive) return
      nodeStatuses.value[data.nodeId] = 'failed'
      executionError.value = data.error
      isRunning.value = false
      addStepEvent(data.nodeId, 'failed', data.error)
    },
    onLiveUpdate: (data) => {
      if (!isActive) return
      const payload = data.payload as Record<string, any>
      if (payload?.status === 'AWAITING_APPROVAL') {
        currentExecutionId.value = data.schemaId
        reviewNodeId.value = payload.nodeId || ''
        reviewPlan.value = payload.rewrittenPlan || payload.plan || ''
        reviewFindings.value = parseFindings(payload.findings)
        reviewIteration.value = 1
        reviewMode.value = payload.mode || 'manual'
        showReviewDialog.value = true
      }
    },
  })

  if (!skipSave) {
    await schemaStore.flushSave()
  }

  try {
    await schemaApi.executeSchema(appId.value, 'EXECUTE')
  } catch (err) {
    executionError.value = (err as Error).message
    isRunning.value = false
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

function handleReviewApprove() {
  showReviewDialog.value = false
  schemaStore.approveReview(currentExecutionId.value, reviewNodeId.value)
}

function handleReviewReject() {
  showReviewDialog.value = false
  schemaStore.rejectReview(currentExecutionId.value, reviewNodeId.value)
}

async function handleResume() {
  try {
    await schemaApi.resumeSchema(appId.value)
    // Start execution without saving first (schema already saved)
    startExecution(true)
  } catch (e) {
    console.error('Failed to resume:', e)
  }
}

async function handleRestart() {
  try {
    await schemaApi.executeSchema(appId.value, 'EXECUTE')
    isRunning.value = true
    executionError.value = null
    nodeResults.value = {}
    nodeStatuses.value = {}
    executionProgress.value = null
    execState.stepEvents.value = []
    nodeStartTimes.clear()
    stepCounter = 0
  } catch (e) {
    console.error('Failed to restart:', e)
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
    schemaStore.currentSchema = found
    document.title = `${found.name} - Axolotl Studio`
  }
})

// Watch route param changes — needed when navigating between schemas
// while the component stays alive (same route, different :id)
watch(() => route.params.id, (newId) => {
  if (newId && newId !== appId.value) {
    schemaStore.flushSave()  // flush pending changes before switching schemas
    appId.value = newId as string
    const found = schemaStore.schemas.find(s => s.id === appId.value)
    if (found) {
      schemaStore.currentSchema = found
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
    // Stop execution
    disconnect()
    schemaApi.stopSchema(appId.value)
      .catch((err: Error) => {
        console.error('Failed to stop execution:', err)
      })
    isRunning.value = false
  } else {
    startExecution(false)
  }
}

// Cleanup WebSocket on unmount
onUnmounted(() => {
  isActive = false
  disconnect()
})

// Flush pending saves + disconnect WebSocket when navigating away
onDeactivated(() => {
  schemaStore.flushSave()  // ensure dirty edits reach backend
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
      schemaStore.currentSchema = fresh
      document.title = `${fresh.name} - Axolotl Studio`
    }
  } catch {
    // Fallback: use cached data
    const found = schemaStore.schemas.find(s => s.id === appId.value)
    if (found) {
      schemaStore.currentSchema = found
      document.title = `${found.name} - Axolotl Studio`
    } else {
      // Schema not in cached store — try reloading from backend
      await schemaStore.loadSchemas()
      const reloaded = schemaStore.schemas.find(s => s.id === appId.value)
      if (reloaded) {
        schemaStore.currentSchema = reloaded
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
      @set-mode="setMode"
      @toggle-run="toggleRun"
      @back="goToDashboard"
      @show-quick-start="onShowQuickStart"
      @show-generate-from-prompt="onShowGenerateFromPrompt"
    />
    
    <ResumeBanner
      :schema-id="appId"
      @resume="handleResume"
      @restart="handleRestart"
      @dismiss="handleDismiss"
    />
    
    <div class="studio-toolbar">
      <button
        class="toolbar-btn"
        :class="{ active: showPipelinePanel }"
        title="Pipeline"
        @click="showPipelinePanel = !showPipelinePanel"
      >
        ⚙ Pipeline
      </button>
    </div>

    <div class="studio-content" :class="{ 'with-pipeline': showPipelinePanel }">
      <div class="pipeline-sidebar" v-if="showPipelinePanel && activeMode === 'blueprint'">
        <PipelinePanel />
      </div>
      <div class="main-content">
        <BlueprintView
          v-show="activeMode === 'blueprint'"
          :app-id="appId"
        />
        <TimelineView
          v-show="activeMode === 'timeline'"
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
  </div>
</template>

<style scoped>
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
