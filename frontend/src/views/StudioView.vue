<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated, onDeactivated, provide, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
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

type StudioMode = 'blueprint' | 'timeline'

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()

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
  })

  if (!skipSave) {
    await schemaStore.updateSchema(currentApp)
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

// Disconnect WebSocket when navigating away (component cached, not destroyed)
onDeactivated(() => {
  if (isRunning.value) {
    disconnect()
  }
})

// Optionally refresh schema data when coming back to a cached session
onActivated(() => {
  // Ensure appId matches current route (handles edge case where
  // user navigates directly to a different /app/:id while cached)
  const currentId = route.params.id as string
  if (currentId && currentId !== appId.value) {
    appId.value = currentId
    const found = schemaStore.schemas.find(s => s.id === appId.value)
    if (found) {
      schemaStore.currentSchema = found
      document.title = `${found.name} - Axolotl Studio`
    }
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
    
    <div class="studio-content">
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
  </div>
</template>

<style scoped>
.studio {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-canvas);
}

.studio-content {
  flex: 1;
  overflow: hidden;
  position: relative;
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
</style>
