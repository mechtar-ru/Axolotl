<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import { schemaApi } from '@/services/api'
import { useWebSocket } from '@/composables/useWebSocket'
import StudioTopBar from '@/components/studio/StudioTopBar.vue'
import BlueprintView from '@/components/studio/BlueprintView.vue'
import LiveView from '@/components/studio/LiveView.vue'

type StudioMode = 'blueprint' | 'live' | 'timeline'

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()

const activeMode = ref<StudioMode>('blueprint')
const appId = ref('')

// WebSocket for real-time execution events
const { connect, disconnect } = useWebSocket()

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
// LiveView injects isRunning directly — provide it separately too
provide('isRunning', isRunning)
provide('executionError', executionError)
provide('nodeResults', nodeResults)
provide('nodeStatuses', nodeStatuses)
provide('executionProgress', executionProgress)


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
    // Start execution
    isRunning.value = true
    executionError.value = null
    // Clear previous results
    nodeResults.value = {}
    nodeStatuses.value = {}
    executionProgress.value = null
    const currentApp = app.value
    if (currentApp) {
      // Connect WebSocket first
      connect(appId.value, {
        onProgress: (data) => {
          nodeStatuses.value[data.nodeId] = data.status
          if (data.progress !== undefined) {
            executionProgress.value = {
              totalNodes: data.totalNodes || 0,
              completedNodes: data.completedNodes || 0
            }
          }
        },
        onResult: (data) => {
          nodeResults.value[data.nodeId] = data.result
        },
        onComplete: () => {
          isRunning.value = false
        },
        onError: (data) => {
          nodeStatuses.value[data.nodeId] = 'failed'
          executionError.value = data.error
          isRunning.value = false
        },
      })

      schemaStore.updateSchema(currentApp)
        .then(() => schemaApi.executeSchema(appId.value, 'EXECUTE'))
        .catch((err: Error) => {
          executionError.value = err.message
          isRunning.value = false
          disconnect()
        })
    } else {
      isRunning.value = false
      executionError.value = 'No app selected'
    }
  }
}

// Cleanup WebSocket on unmount
onUnmounted(() => {
  disconnect()
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
    />
    
    <div class="studio-content">
      <BlueprintView
        v-if="activeMode === 'blueprint'"
        :app-id="appId"
      />
      <LiveView
        v-else-if="activeMode === 'live'"
        :app-id="appId"
      />
      <div v-else-if="activeMode === 'timeline'" class="placeholder-view">
        <div class="placeholder-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="48" height="48">
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
        </div>
        <h2>Timeline Mode</h2>
        <p>View execution history and traces</p>
      </div>
    </div>
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
  gap: 0.75rem;
}

.placeholder-icon {
  color: var(--text-muted);
  opacity: 0.4;
}

.placeholder-view h2 {
  margin: 0;
  font-size: 1.25rem;
  color: var(--text-secondary);
}

.placeholder-view p {
  margin: 0;
  font-size: 0.9rem;
}
</style>
