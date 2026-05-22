<script setup lang="ts">
import { ref, computed, inject, type Ref, type ComputedRef } from 'vue'
import { useSchemaStore } from '@/stores/schemaStore'
import ChatAppUI from '@/components/live/ChatAppUI.vue'
import DocAnalyzerAppUI from '@/components/live/DocAnalyzerAppUI.vue'
import DesignWorkspaceUI from '@/components/live/DesignWorkspaceUI.vue'
import GenericAppUI from '@/components/live/GenericAppUI.vue'

const props = defineProps<{
  appId: string
}>()

const schemaStore = useSchemaStore()

const app = computed(() => schemaStore.schemas.find(s => s.id === props.appId))

const appType = computed(() => (app.value as any)?.appType || 'CUSTOM')

// Injected state from StudioView
const isRunning = inject<Ref<boolean>>('isRunning', ref(false))

// Injected execution results
const nodeResults = inject<Ref<Record<string, any>>>('nodeResults', ref({}))

// Find the latest output result — take the last added node result
const executionResult = computed(() => {
  const results = nodeResults.value
  const keys = Object.keys(results)
  if (keys.length === 0) return null
  // Return the last output node result, or the last result overall
  const outputKey = (keys.find(k => k.startsWith('out-')) || keys[keys.length - 1]) as string
  return results[outputKey]
})
</script>

<template>
  <div class="live-view">
    <!-- Canvas background (dimmed) -->
    <div class="canvas-backdrop" />
    
    <!-- App-specific UI -->
    <div class="app-ui-container">
      <ChatAppUI v-if="appType === 'CHAT'" :execution-result="executionResult" />
      <DocAnalyzerAppUI v-else-if="appType === 'ANALYZER'" :execution-result="executionResult" />
      <DesignWorkspaceUI v-else-if="appType === 'GAME' || appType === 'GENERATOR'" :app-id="props.appId" :app-type="appType" :execution-result="executionResult" />
      <GenericAppUI v-else :app-type="appType" :execution-result="executionResult" />
    </div>
    
    <!-- Back to Blueprint hint -->
    <div class="back-hint">
      Press <kbd>B</kbd> to return to Blueprint
    </div>
  </div>
</template>

<style scoped>
.live-view {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
}

.canvas-backdrop {
  position: absolute;
  inset: 0;
  opacity: 0.15;
  background-image: radial-gradient(circle, var(--text-muted) 1px, transparent 1px);
  background-size: 20px 20px;
  pointer-events: none;
}

.app-ui-container {
  position: relative;
  z-index: var(--z-canvas);
  width: 100%;
  max-width: 800px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.back-hint {
  position: absolute;
  bottom: var(--space-4);
  left: 50%;
  transform: translateX(-50%);
  font-size: var(--text-xs);
  color: var(--text-muted);
  background: var(--bg-secondary);
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-color);
}

.back-hint kbd {
  font-family: inherit;
  font-weight: 600;
  color: var(--text-secondary);
}
</style>
