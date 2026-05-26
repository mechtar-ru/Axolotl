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
import DepsInstallDialog from '@/components/studio/DepsInstallDialog.vue'
import DiffReviewDialog from '@/components/studio/DiffReviewDialog.vue'
import PipelinePanel from '@/components/studio/PipelinePanel.vue'
import type { ReviewData, ReviewFinding } from '@/stores/schemaStore'
import { useToast } from '@/composables/useToast'

type StudioMode = 'blueprint' | 'timeline'

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()
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
  return schemaStore.schemas.find(s => s.id === appId.value) || schemaStore.currentSchema
})

  const isRunning = ref(false)
  const executionError = ref<string | null>(null)
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

const showPipelinePanel = ref(false)

// Provide state for child components
provide('appState', {
  app,
  isRunning,
  activeMode,
  appId
})

provide('isRunning', isRunning)
provide('nodeResults', nodeResults)
provide('nodeStatuses', nodeStatuses)
provide('executionProgress', executionProgress)

/**
 * Start execution with WebSocket connection.
 * @param skipSave — if true, skip schemaStore.updateSchema (caller already saved)
 */
  const startExecution = async (skipSave: boolean = false): Promise<void> => {
    if (isRunning.value) {
      console.warn('Execution already in progress')
      return
    }
    setIsExecuting(true)
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
      showDepsDialog.value = true
    },
    onDiffsNeeded: (data) => {
      if (!isActive) return
      diffDiffs.value = data.diffs || []
      diffNodeId.value = data.nodeId
      showDiffDialog.value = true
    },
  })

  if (!skipSave) {
    try {
      await schemaStore.flushSave()
    } catch (e) {
      toast.error('Failed to save: ' + ((e as Error).message || e))
    }
  }

  try {
    await schemaApi.executeSchema(appId.value, 'EXECUTE')
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
    await schemaStore.approveReview(currentExecutionId.value, reviewNodeId.value)
  } catch (e) {
    toast.error('Failed to approve review: ' + ((e as Error).message || e))
    executionError.value = 'Failed to approve review — execution may still be paused'
  }
}

async function handleReviewReject() {
  showReviewDialog.value = false
  try {
    await schemaStore.rejectReview(currentExecutionId.value, reviewNodeId.value)
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
    schemaStore.currentSchema = found
    document.title = `${found.name} - Axolotl Studio`
  }
})

// Watch route param changes — needed when navigating between schemas
// while the component stays alive (same route, different :id)
watch(() => route.params.id, (newId) => {
  if (newId && newId !== appId.value) {
    try {
      schemaStore.flushSave()
    } catch (e) {
      toast.error('Failed to save: ' + ((e as Error).message || e))
    }
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
})

// Flush pending saves + disconnect WebSocket when navigating away
onDeactivated(() => {
  try {
    schemaStore.flushSave()  // ensure dirty edits reach backend
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
      @export-schema="handleExportSchema"
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
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
        </svg>
        Pipeline
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
