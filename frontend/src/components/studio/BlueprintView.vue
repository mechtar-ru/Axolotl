<script setup lang="ts">
import { ref, markRaw, onMounted, onBeforeUnmount, watch, nextTick, inject } from 'vue'
import { VueFlow, useVueFlow, type Node, type Edge, MarkerType, type NodeTypesObject, type NodeComponent } from '@vue-flow/core'
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
// Note: VueFlow expects NodeComponents that accept NodeProps (id, position, data, type, selected, connectable, etc.)
// Our custom blocks accept different props, but VueFlow will pass NodeProps at runtime.
// We use type assertion to satisfy TypeScript since runtime behavior works (extra props ignored).
const nodeTypes: NodeTypesObject = {
  source: markRaw(ReceiveBlock) as NodeComponent,
  agent: markRaw(ThinkBlock) as NodeComponent,
  verifier: markRaw(VerifyBlock) as NodeComponent,
  review: markRaw(ReviewBlock) as NodeComponent,
  draft: markRaw(DraftBlock) as NodeComponent,
  memory: markRaw(RememberBlock) as NodeComponent,
  output: markRaw(ActBlock) as NodeComponent,
  planner: markRaw(PlannerBlock) as NodeComponent,
  prep: markRaw(PrepBlock) as NodeComponent,
  'doc-agent': markRaw(DocAgentBlock) as NodeComponent,
}

const { nodes, edges, addNodes, addEdges, onConnect, screenToFlowCoordinate, fitView, setNodes, setEdges } = useVueFlow({ id: `blueprint-flow-${props.appId}` })
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
const paletteOpen = ref(true) // mobile toggle
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
      status: n.status,
      sourceType: n.data?.sourceType,
      sourceContent: n.data?.sourceContent,
      filePath: n.data?.filePath,
      url: n.data?.url,
      projectPath: n.data?.projectPath,
      maxDepth: n.data?.maxDepth,
      maxFiles: n.data?.maxFiles,
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
    console.error('BlueprintView: Failed to load full schema:', err)
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
  // Also sync flow to store with local debounce (2s) to coordinate with store's debounce
  let flowSyncTimer: ReturnType<typeof setTimeout> | null = null
  let lastSyncTime = 0
  const SYNC_INTERVAL = 2000 // minimum time between syncs
  
  watch([nodes, edges], () => {
    if (!flowReady.value) return
    undoRedo.capture()
    const now = Date.now()
    // Throttle: only sync if enough time has passed since last sync
    if (now - lastSyncTime >= SYNC_INTERVAL) {
      if (flowSyncTimer) clearTimeout(flowSyncTimer)
      flowSyncTimer = null
      if (!syncing) {
        syncFlowToStore()
        lastSyncTime = now
      }
    } else {
      // Debounce: if sync timer exists, reset it
      if (flowSyncTimer) clearTimeout(flowSyncTimer)
      flowSyncTimer = setTimeout(() => {
        if (!syncing) {
          syncFlowToStore()
          lastSyncTime = Date.now()
        }
        flowSyncTimer = null
      }, SYNC_INTERVAL)
    }
  }, { deep: true })

  onBeforeUnmount(() => {
    if (flowSyncTimer) clearTimeout(flowSyncTimer)
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
      
      const newId = `${parsed.blockType}-${crypto.randomUUID()}`
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
    console.error('BlueprintView: Failed to parse drop data', e)
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

// Keyboard navigation for canvas
function onKeyDown(event: KeyboardEvent) {
  // Ignore if typing in an input
  const target = event.target as HTMLElement
  if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') {
    return
  }

  // Escape: close config panel
  if (event.key === 'Escape') {
    if (configPanelOpen.value) {
      configPanelOpen.value = false
      schemaPanelOpen.value = true
      selectedBlockId.value = null
    }
    return
  }

  // Ctrl/Cmd + S: save
  if ((event.metaKey || event.ctrlKey) && event.key === 's') {
    event.preventDefault()
    syncFlowToStore()
    return
  }

  // Navigation with arrow keys (when a node is selected)
  if (selectedBlockId.value && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
    event.preventDefault()
    const node = nodes.value.find(n => n.id === selectedBlockId.value)
    if (!node) return

    const step = event.shiftKey ? 50 : 20
    let newX = node.position.x
    let newY = node.position.y

    switch (event.key) {
      case 'ArrowUp': newY -= step; break
      case 'ArrowDown': newY += step; break
      case 'ArrowLeft': newX -= step; break
      case 'ArrowRight': newX += step; break
    }

    setNodes(nodes.value.map(n => n.id === node.id ? { ...n, position: { x: newX, y: newY } } : n))
    syncFlowToStore()
    return
  }

  // Delete/Backspace: delete selected node
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedBlockId.value) {
    event.preventDefault()
    canvasStore.removeNode(selectedBlockId.value)
    selectedBlockId.value = null
    configPanelOpen.value = false
    schemaPanelOpen.value = true
    return
  }
}

// Listen for keyboard events on the canvas wrapper
onMounted(() => {
  window.addEventListener('keydown', onKeyDown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
})

// Touch gesture support for mobile — pan/zoom on canvas
let lastTouchDist = 0
let lastTouchCenter = { x: 0, y: 0 }
let lastTouchPos = { x: 0, y: 0 }
let touchMoved = false

function onTouchStart(e: TouchEvent) {
  touchMoved = false
  if (e.touches.length === 2) {
    e.preventDefault()
    const t1 = e.touches[0]!
    const t2 = e.touches[1]!
    lastTouchDist = Math.hypot(t2.clientX - t1.clientX, t2.clientY - t1.clientY)
    lastTouchCenter = {
      x: (t1.clientX + t2.clientX) / 2,
      y: (t1.clientY + t2.clientY) / 2,
    }
  } else if (e.touches.length === 1) {
    lastTouchPos = { x: e.touches[0]!.clientX, y: e.touches[0]!.clientY }
  }
}

function onTouchMove(e: TouchEvent) {
  touchMoved = true
  if (e.touches.length === 2) {
    e.preventDefault()
    const t1 = e.touches[0]!
    const t2 = e.touches[1]!
    const dist = Math.hypot(t2.clientX - t1.clientX, t2.clientY - t1.clientY)
    const delta = dist - lastTouchDist
    if (Math.abs(delta) > 5) {
      const zoom = delta > 0 ? 1.05 : 0.95
      fitView({ padding: 0.2, duration: 100 })
    }
    lastTouchDist = dist
  } else if (e.touches.length === 1) {
    // Single-finger pan by default (no modifier needed)
    e.preventDefault()
    const t = e.touches[0]!
    const dx = t.clientX - lastTouchPos.x
    const dy = t.clientY - lastTouchPos.y
    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
      fitView({ padding: 0.2, duration: 0 })
    }
    lastTouchPos = { x: e.touches[0]!.clientX, y: e.touches[0]!.clientY }
  }
}

function onTouchEnd(_e: TouchEvent) {
  // On single finger tap (no move), simulate pane click
  if (!touchMoved) {
    onPaneClickHandler()
  }
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
      // Prioritize top-level data.model/data.systemPrompt over config.model/config.systemPrompt
      const model = n.data?.model || vueFlowConfig.model || '';
      const systemPrompt = n.data?.systemPrompt || vueFlowConfig.systemPrompt || '';
      const { model: _m, systemPrompt: _sp, ...restConfig } = vueFlowConfig;
      return {
        id: n.id,
        type: (n.type || 'agent') as FlowNode['type'],
        name: (n.data?.label as string) || n.id,
        position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
        data: {
          model,
          systemPrompt,
          config: restConfig,
          sourceType: n.data?.sourceType,
          sourceContent: n.data?.sourceContent,
          filePath: n.data?.filePath,
          url: n.data?.url,
          projectPath: n.data?.projectPath,
          maxDepth: n.data?.maxDepth,
          maxFiles: n.data?.maxFiles,
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
    id: `edge-${crypto.randomUUID()}`,
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
    <!-- Mobile palette toggle -->
    <button class="palette-toggle" @click.stop="paletteOpen = !paletteOpen" aria-label="Toggle palette">
      <svg v-if="paletteOpen" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
    </button>
    <!-- Block Palette -->
    <div class="palette-wrapper" :class="{ 'palette-hidden': !paletteOpen }">
      <BlockPalette />
    </div>
    
    <!-- Live region for node status updates -->
    <div ref="nodeStatusAnnouncer" aria-live="assertive" aria-atomic="true" class="sr-only" id="node-status-announcer" />
    
    <!-- Canvas -->
    <div class="canvas-wrapper" ref="touchCanvasRef" @touchstart="onTouchStart" @touchmove="onTouchMove" @touchend="onTouchEnd">
      <div v-if="!flowReady" class="canvas-skeleton">
        <div class="cs-header">
          <div class="cs-line" style="width: 30%"></div>
          <div class="cs-line" style="width: 15%"></div>
        </div>
        <div class="cs-body">
          <div class="cs-block" v-for="i in 3" :key="i">
            <div class="cs-block-header"></div>
            <div class="cs-block-label"></div>
          </div>
        </div>
      </div>
        v-if="flowReady"
        id="blueprint-flow"
        :node-types="nodeTypes"
        :default-viewport="{ x: 0, y: 0, zoom: 1 }"
        :min-zoom="0.2"
        :max-zoom="4"
        :only-render-visible-elements="true"
        :only-render-visible-elements-margin="0.5"
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

/* Palette toggle button (visible only on mobile) */
.palette-toggle {
  display: none;
  position: fixed;
  top: 60px;
  left: 8px;
  z-index: var(--z-panel);
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background var(--transition-fast);
}
.palette-toggle:hover {
  background: var(--bg-hover);
}

/* Mobile responsive */
@media (max-width: 768px) {
  .palette-wrapper {
    position: fixed;
    top: 60px;
    left: 0;
    z-index: var(--z-panel);
    width: 200px;
    max-height: 50vh;
    overflow-y: auto;
    box-shadow: var(--shadow-lg);
  }
  .palette-wrapper.palette-hidden {
    display: none !important;
  }
  .palette-toggle {
    display: flex;
  }
  .canvas-wrapper {
    margin: 0;
  }
}

/* Canvas loading skeleton */
@keyframes canvas-shimmer {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 0.7; }
}
.canvas-skeleton {
  padding: 60px 24px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.cs-header {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
}
.cs-line {
  height: 14px;
  border-radius: 6px;
  background: var(--border-color);
  animation: canvas-shimmer 1.5s ease-in-out infinite;
}
.cs-body {
  display: flex;
  gap: 32px;
  justify-content: center;
  padding: 40px 0;
}
.cs-block {
  width: 120px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}
.cs-block-header {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: var(--border-color);
  animation: canvas-shimmer 1.5s ease-in-out infinite;
}
.cs-block-label {
  width: 60%;
  height: 12px;
  border-radius: 4px;
  background: var(--border-color);
  animation: canvas-shimmer 1.5s ease-in-out infinite;
}
</style>
