<script setup lang="ts">
import { ref, markRaw, onMounted, onBeforeUnmount, watch, nextTick, inject } from 'vue'
import { VueFlow, useVueFlow, type Node, type Edge, MarkerType } from '@vue-flow/core'
import { Background, BackgroundVariant } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { useSchemaStore } from '@/stores/schemaStore'
import { useCanvasStore } from '@/stores/useCanvasStore'
import { schemaApi } from '@/services/api'
import type { FlowNode, FlowEdge } from '@/types'
import BlockPalette from '@/components/studio/BlockPalette.vue'
import BlockConfigPanel from '@/components/studio/BlockConfigPanel.vue'
import SchemaPropertiesPanel from '@/components/studio/SchemaPropertiesPanel.vue'
import { useUndoRedo } from '@/composables/useUndoRedo'

// Import block components for VueFlow nodeTypes
import ReceiveBlock from '@/components/blocks/ReceiveBlock.vue'
import ThinkBlock from '@/components/blocks/ThinkBlock.vue'
import RememberBlock from '@/components/blocks/RememberBlock.vue'
import ActBlock from '@/components/blocks/ActBlock.vue'
import VerifyBlock from '@/components/blocks/VerifyBlock.vue'
import ReviewBlock from '@/components/blocks/ReviewBlock.vue'
import DraftBlock from '@/components/blocks/DraftBlock.vue'
import PlannerBlock from '@/components/blocks/PlannerBlock.vue'
import PrepBlock from '@/components/blocks/PrepBlock.vue'
import DocAgentBlock from '@/components/blocks/DocAgentBlock.vue'

const props = defineProps<{
  appId: string
}>()

const schemaStore = useSchemaStore()
const canvasStore = useCanvasStore()

// Register custom node types for VueFlow
// markRaw prevents Vue from wrapping component defs in reactive proxies,
// which breaks VueFlow's internal component instance tracking
const nodeTypes = {
  source: markRaw(ReceiveBlock),
  agent: markRaw(ThinkBlock),
  verifier: markRaw(VerifyBlock),
  review: markRaw(ReviewBlock),
  draft: markRaw(DraftBlock),
  memory: markRaw(RememberBlock),
  output: markRaw(ActBlock),
  planner: markRaw(PlannerBlock),
  prep: markRaw(PrepBlock),
  'doc-agent': markRaw(DocAgentBlock),
}

const { nodes, edges, addNodes, addEdges, onConnect, screenToFlowCoordinate, fitView, setNodes, setEdges } = useVueFlow({ id: 'blueprint-flow' })
const flowReady = ref(false)

const undoRedo = useUndoRedo(setNodes, setEdges, nodes, edges)
defineExpose({ undoRedo, canUndo: undoRedo.canUndo, canRedo: undoRedo.canRedo })

const emit = defineEmits<{
  'show-quick-start': []
  'show-generate-from-prompt': []
}>()

const selectedBlockId = ref<string | null>(null)
const configPanelOpen = ref(false)
const schemaPanelOpen = ref(true)
const startExecution = inject<(schemaId: string) => Promise<void>>('startExecution', async () => {})

// Build VueFlow nodes from schema data
function buildVueFlowNodes(schema: any): Node[] {
  if (!schema.nodes) return []
  return schema.nodes.map((n: FlowNode) => ({
    id: n.id,
    type: n.type || 'agent',
    position: n.position || { x: 100, y: 200 },
    dimensions: { width: 200, height: 100 },
    data: {
      label: n.name,
      type: n.type,
      config: {
        ...((n.data?.config as Record<string, any>) || {}),
        model: n.data?.model || '',
        systemPrompt: n.data?.systemPrompt || '',
      },
      status: n.status
    }
  }))
}

function buildVueFlowEdges(schema: any): Edge[] {
  if (!schema.edges) return []
  const nodeIds = new Set((schema.nodes || []).map((n: any) => n.id))
  return schema.edges
    .filter((e: FlowEdge) => e?.source && e?.target && nodeIds.has(e.source) && nodeIds.has(e.target))
    .map((e: FlowEdge) => ({
      id: e.id || `${e.source}->${e.target}`,
      source: e.source,
      target: e.target,
      type: 'smoothstep',
      markerEnd: { type: MarkerType.ArrowClosed }
    }))
}

let syncing = false

// Load full schema (with nodes + edges) from API detail endpoint
// Only loads if flow hasn't been initialized yet
async function loadFullSchema() {
  if (flowReady.value) return
  try {
    const fullSchema = await schemaApi.getSchema(props.appId)
    if (fullSchema?.nodes?.length) {
      setNodes(buildVueFlowNodes(fullSchema))
      setEdges(buildVueFlowEdges(fullSchema))
      flowReady.value = true
      nextTick(() => fitView({ padding: 0.2 }))
    }
  } catch (err) {
    console.error('Failed to load full schema:', err)
  }
}

onMounted(loadFullSchema)

// Watch for schema updates (e.g. after save) — prevent re-entrant sync loops
watch(() => canvasStore.currentSchema, (schema) => {
  if (syncing) return
  if (!schema || schema.id !== props.appId) return
  if (!schema.nodes?.length) return

  syncing = true
  setNodes(buildVueFlowNodes(schema))
  setEdges(buildVueFlowEdges(schema))
  flowReady.value = true
  undoRedo.reset()
  nextTick(() => {
    fitView({ padding: 0.2 })
    syncing = false
  })
}, { deep: true, immediate: true })

// Debounced undo capture on nodes/edges changes (drag, delete, resize)
// Composable internally debounces to 500ms
watch([nodes, edges], () => {
  if (!flowReady.value) return
  undoRedo.capture()
}, { deep: true })

// Separate 500ms debounce for store sync
let syncTimer: ReturnType<typeof setTimeout> | null = null
watch([nodes, edges], () => {
  if (syncTimer) clearTimeout(syncTimer)
  syncTimer = setTimeout(() => syncFlowToStore(), 500)
}, { deep: true })

onBeforeUnmount(() => {
  if (syncTimer) {
    clearTimeout(syncTimer)
    syncTimer = null
  }
})

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
      const newNode: Node & { dimensions: { width: number; height: number } } = {
        id: newId,
        type: parsed.blockType,
        position,
        dimensions: { width: 200, height: 100 },
        data: {
          label: parsed.blockLabel || parsed.blockType,
          type: parsed.blockType,
          config: {}
        }
      }
      
      undoRedo.capture()
      addNodes([newNode])
      syncFlowToStore()
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
    schemaPanelOpen.value = false
  }
}

// Handle pane click (close config panel)
function onPaneClickHandler() {
  selectedBlockId.value = null
  configPanelOpen.value = false
  schemaPanelOpen.value = true
}

function onAddNode() {
  // Focus on palette — user can drag a new block
  // For now, no-op; palette is visible
}

function onRunSchema() {
  if (canvasStore.currentSchema) {
    canvasStore.executeSchema(canvasStore.currentSchema.id)
  }
}

function onQuickStart() {
  // Will show QuickStartDialog — the inject ref handles visibility
  // For now, we can trigger a custom event or just log
  console.log('Quick start requested')
}

// Sync VueFlow nodes + edges back to schemaStore so they persist to backend
function syncFlowToStore() {
  if (!canvasStore.currentSchema) return

  syncing = true

  const updatedNodes: FlowNode[] = nodes.value.map(n => {
    const vueFlowConfig = (n.data?.config as Record<string, any>) || {};
    const { model: m, systemPrompt: sp, ...restConfig } = vueFlowConfig;
    return {
      id: n.id,
      type: (n.type || 'agent') as FlowNode['type'],
      name: (n.data?.label as string) || n.id,
      position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
      data: {
        model: m || '',
        systemPrompt: sp || '',
        config: restConfig
      }
    }
  })

  const updatedEdges: FlowEdge[] = edges.value.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    type: 'data'
  }))

  canvasStore.markDirty({
    ...canvasStore.currentSchema,
    nodes: updatedNodes,
    edges: updatedEdges
  })
  syncing = false
}

// Handle new connections — persist immediately
onConnect((connection) => {
  if (!connection || !connection.source || !connection.target) {
    console.warn('Skipping incomplete connection', connection)
    return
  }
  undoRedo.capture()
  const newEdge: Edge = {
    id: `edge-${Date.now()}`,
    source: connection.source,
    target: connection.target,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed }
  }
  addEdges([newEdge])
  syncFlowToStore()
})
</script>

<template>
  <div class="blueprint-view">
    <!-- Block Palette -->
    <div class="palette-wrapper">
      <BlockPalette />
    </div>
    
    <!-- Canvas -->
    <div class="canvas-wrapper">
      <VueFlow
        v-if="flowReady"
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
      v-if="configPanelOpen && selectedBlockId"
      :block-id="selectedBlockId || ''"
      @close="configPanelOpen = false"
    />

    <!-- Schema Properties Panel (shown when nothing is selected) -->
    <SchemaPropertiesPanel
      v-if="!configPanelOpen && !selectedBlockId"
      @add-node="console.log('Add node')"
      @run="startExecution(props.appId)"
      @quick-start="emit('show-quick-start')"
    />
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
  z-index: var(--z-canvas);
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
  border-radius: var(--radius-sm);
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
  border-radius: var(--radius-sm);
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
</style>
