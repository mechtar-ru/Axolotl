<script setup lang="ts">
import { ref, computed, inject, type Ref, type ComputedRef } from 'vue'
import { useSchemaStore } from '@/stores/schemaStore'
import ChatAppUI from '@/components/live/ChatAppUI.vue'
import DocAnalyzerAppUI from '@/components/live/DocAnalyzerAppUI.vue'
import GameAppUI from '@/components/live/GameAppUI.vue'
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
  const outputKey = keys.find(k => k.startsWith('out-')) || keys[keys.length - 1]
  return results[outputKey]
})
</script>

<template>
  <div class="live-view">
    <!-- Canvas background (dimmed) -->
    <div class="canvas-backdrop" />
    
    <!-- App-specific UI -->
    <div class="app-ui-container">
      <ChatAppUI v-if="appType === 'CHAT'" />
      <DocAnalyzerAppUI v-else-if="appType === 'ANALYZER'" />
      <GameAppUI v-else-if="appType === 'GAME'" :execution-result="executionResult" />
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
  z-index: 1;
  width: 100%;
  max-width: 800px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.back-hint {
  position: absolute;
  bottom: 1rem;
  left: 50%;
  transform: translateX(-50%);
  font-size: 0.75rem;
  color: var(--text-muted);
  background: var(--bg-secondary);
  padding: 0.25rem 0.75rem;
  border-radius: 4px;
  border: 1px solid var(--border-color);
}

.back-hint kbd {
  font-family: inherit;
  font-weight: 600;
  color: var(--text-secondary);
}
</style>
