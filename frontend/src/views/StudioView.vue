<script setup lang="ts">
import { ref, computed, onMounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import { schemaApi } from '@/services/api'
import StudioTopBar from '@/components/studio/StudioTopBar.vue'
import BlueprintView from '@/components/studio/BlueprintView.vue'

type StudioMode = 'blueprint' | 'live' | 'timeline'

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()

const activeMode = ref<StudioMode>('blueprint')
const appId = ref('')

// App state
const app = computed(() => {
  return schemaStore.schemas.find(s => s.id === appId.value) || schemaStore.currentSchema
})

const isRunning = ref(false)
const executionError = ref<string | null>(null)

// Provide state for child components
provide('appState', {
  app,
  isRunning,
  activeMode,
  appId
})

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
    schemaApi.stopSchema(appId.value)
      .catch((err: Error) => {
        console.error('Failed to stop execution:', err)
      })
    isRunning.value = false
  } else {
    // Start execution
    isRunning.value = true
    executionError.value = null
    // First save the schema, then execute
    const currentApp = app.value
    if (currentApp) {
      schemaStore.updateSchema(currentApp)
        .then(() => schemaApi.executeSchema(appId.value, 'EXECUTE'))
        .then(() => {
          isRunning.value = false
        })
        .catch((err: Error) => {
          isRunning.value = false
          executionError.value = err.message
        })
    } else {
      isRunning.value = false
      executionError.value = 'No app selected'
    }
  }
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
      @set-mode="setMode"
      @toggle-run="toggleRun"
      @back="goToDashboard"
    />
    
    <div class="studio-content">
      <BlueprintView
        v-if="activeMode === 'blueprint'"
        :app-id="appId"
      />
      <div v-else-if="activeMode === 'live'" class="placeholder-view">
        <div class="placeholder-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="48" height="48">
            <polygon points="5 3 19 12 5 21 5 3" />
          </svg>
        </div>
        <h2>Live Mode</h2>
        <p>Run your app to see it in action</p>
      </div>
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
