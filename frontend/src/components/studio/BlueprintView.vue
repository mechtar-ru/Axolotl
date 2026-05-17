<script setup lang="ts">
import { ref, markRaw, onMounted, watch, nextTick, inject } from 'vue'
import { VueFlow, useVueFlow, type Node, type Edge, MarkerType } from '@vue-flow/core'
import { Background, BackgroundVariant } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { useSchemaStore } from '@/stores/schemaStore'
import { schemaApi } from '@/services/api'
import type { FlowNode, FlowEdge } from '@/types'
import BlockPalette from '@/components/studio/BlockPalette.vue'
import BlockConfigPanel from '@/components/studio/BlockConfigPanel.vue'
import LiveView from '@/components/studio/LiveView.vue'

// Import block components for VueFlow nodeTypes
import ReceiveBlock from '@/components/blocks/ReceiveBlock.vue'
import ThinkBlock from '@/components/blocks/ThinkBlock.vue'
import RememberBlock from '@/components/blocks/RememberBlock.vue'
import ActBlock from '@/components/blocks/ActBlock.vue'
import VerifyBlock from '@/components/blocks/VerifyBlock.vue'
import ReviewBlock from '@/components/blocks/ReviewBlock.vue'

const props = defineProps<{
  appId: string
}>()

const schemaStore = useSchemaStore()

// Register custom node types for VueFlow
// markRaw prevents Vue from wrapping component defs in reactive proxies,
// which breaks VueFlow's internal component instance tracking
const nodeTypes = {
  source: markRaw(ReceiveBlock),
  agent: markRaw(ThinkBlock),
  verifier: markRaw(VerifyBlock),
  review: markRaw(ReviewBlock),
  memory: markRaw(RememberBlock),
  output: markRaw(ActBlock),
}

const { nodes, edges, addNodes, addEdges, onConnect, screenToFlowCoordinate, fitView } = useVueFlow({ id: 'blueprint-flow' })

const selectedBlockId = ref<string | null>(null)
const configPanelOpen = ref(false)
const showExecutionOverlay = inject('showExecutionOverlay', ref(false))

// Build VueFlow nodes from schema data
function buildVueFlowNodes(schema: any): Node[] {
  if (!schema.nodes) return []
  return schema.nodes.map((n: FlowNode) => ({
    id: n.id,
    type: n.type || 'agent',
    position: n.position || { x: 100, y: 200 },
    data: {
      label: n.name,
      type: n.type,
      config: n.data,
      status: n.status
    }
  }))
}

function buildVueFlowEdges(schema: any): Edge[] {
  if (!schema.edges) return []
  return schema.edges.map((e: FlowEdge) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed }
  }))
}

// Load full schema (with nodes + edges) from API detail endpoint
async function loadFullSchema() {
  try {
    const fullSchema = await schemaApi.getSchema(props.appId)
    if (fullSchema?.nodes?.length) {
      addNodes(buildVueFlowNodes(fullSchema))
      addEdges(buildVueFlowEdges(fullSchema))
      nextTick(() => fitView({ padding: 0.2 }))
    }
  } catch (err) {
    console.error('Failed to load full schema:', err)
  }
}

onMounted(loadFullSchema)

// Watch for schema updates (e.g. after save)
watch(() => schemaStore.currentSchema, (schema) => {
  if (!schema || schema.id !== props.appId) return
  if (!schema.nodes?.length) return

  addNodes(buildVueFlowNodes(schema))
  addEdges(buildVueFlowEdges(schema))
  nextTick(() => fitView({ padding: 0.2 }))
}, { deep: true })

// Handle drag and drop from BlockPalette
const dropPosition = ref({ x: 0, y: 0 })
const draggingType = ref<string | null>(null)

function onDragOverHandler(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function onDropHandler(event: DragEvent) {
  event.preventDefault()
  
  const data = event.dataTransfer?.getData('application/json')
  if (!data) return
  
  try {
    const parsed = JSON.parse(data)
    if (parsed.type === 'new-block') {
      const position = screenToFlowCoordinate({
        x: event.clientX,
        y: event.clientY
      })
      
      const newId = `${parsed.blockType}-${Date.now()}`
      const newNode: Node = {
        id: newId,
        type: parsed.blockType,
        position,
        data: {
          label: parsed.blockLabel || parsed.blockType,
          type: parsed.blockType,
          config: {}
        }
      }
      
      addNodes([newNode])
      
      // Sync VueFlow nodes back to schema store
      if (schemaStore.currentSchema) {
        const updatedNodes: FlowNode[] = nodes.value.map(n => ({
          id: n.id,
          type: (n.type || 'agent') as FlowNode['type'],
          name: (n.data?.label as string) || n.id,
          position: n.position,
          data: (n.data?.config as Record<string, any>) || {}
        }))
        schemaStore.updateSchema({
          ...schemaStore.currentSchema,
          nodes: updatedNodes
        })
      }
    }
  } catch (e) {
    console.error('Failed to parse drop data', e)
  }
}

// Handle node click (open config panel)
function onNodeClickHandler(event: any) {
  const node = event.node
  if (node) {
    selectedBlockId.value = node.id
    configPanelOpen.value = true
  }
}

// Handle pane click (close config panel)
function onPaneClickHandler() {
  selectedBlockId.value = null
  configPanelOpen.value = false
}

// Handle new connections
onConnect((connection) => {
  if (!connection || !connection.source || !connection.target) {
    console.warn('Skipping incomplete connection', connection)
    return
  }
  const newEdge: Edge = {
    id: `edge-${Date.now()}`,
    source: connection.source,
    target: connection.target,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed }
  }
  addEdges([newEdge])
})
</script>

<template>
  <div class="blueprint-view">
    <!-- Block Palette -->
    <div v-show="!showExecutionOverlay" class="palette-wrapper">
      <BlockPalette />
    </div>
    
    <!-- Canvas -->
    <div class="canvas-wrapper">
      <VueFlow
        id="blueprint-flow"
        :node-types="nodeTypes as any"
        :default-viewport="{ x: 0, y: 0, zoom: 1 }"
        :min-zoom="0.2"
        :max-zoom="4"
        @drop="onDropHandler"
        @dragover="onDragOverHandler"
        @node-click="onNodeClickHandler"
        @pane-click="onPaneClickHandler"
      >
        <Background :variant="BackgroundVariant.Dots" :gap="20" :size="1" />
        <Controls :show-interactive="false" />
        <MiniMap
          :node-color="'var(--bg-secondary)'"
          :mask-color="'var(--bg-canvas)'"
          :style="{ background: 'var(--bg-secondary)' }"
        />
      </VueFlow>
    </div>
    
    <!-- Config Panel -->
    <BlockConfigPanel
      v-show="!showExecutionOverlay && configPanelOpen && selectedBlockId"
      :block-id="selectedBlockId || ''"
      @close="configPanelOpen = false"
    />

    <!-- Execution Overlay -->
    <div v-show="showExecutionOverlay" class="execution-overlay">
      <LiveView :app-id="appId" />
    </div>
  </div>
</template>

<style scoped>
.blueprint-view {
  display: flex;
  height: 100%;
  position: relative;
}

.palette-wrapper {
  position: absolute;
  top: 1rem;
  left: 1rem;
  z-index: 10;
}

.canvas-wrapper {
  flex: 1;
  height: 100%;
}

/* VueFlow overrides for theme support */
:deep(.vue-flow__background) {
  background: var(--bg-canvas);
}

:deep(.vue-flow__controls) {
  box-shadow: var(--shadow-md);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

:deep(.vue-flow__controls button) {
  background: var(--bg-secondary);
  color: var(--text-primary);
  border-color: var(--border-color);
  width: 28px;
  height: 28px;
}

:deep(.vue-flow__controls button:hover) {
  background: var(--bg-hover);
}

:deep(.vue-flow__minimap) {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
  box-shadow: var(--shadow-md);
}

:deep(.vue-flow__edge-path) {
  stroke: var(--text-muted);
  stroke-width: 2;
}

:deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--accent);
}

.execution-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: var(--bg-canvas);
  overflow: hidden;
}
</style>
