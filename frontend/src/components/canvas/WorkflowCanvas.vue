<template>
  <div class="canvas-container">
    <div class="schema-name" @click="editSchemaName">
      <span class="schema-title" @click="editSchemaName">📝 {{ schema.name }}</span>
      <div class="schema-actions">
        <select v-model="executionMode" class="mode-selector" title="Режим выполнения">
          <option value="EXECUTE">▶️ Execute</option>
          <option value="ANALYZE">🔍 Analyze</option>
          <option value="DRY_RUN">🎭 Dry Run</option>
        </select>
        <button class="run-schema-btn" @click.stop="executeSchema" :disabled="isExecuting">
          ▶️ {{ executionMode === 'EXECUTE' ? 'Выполнить' : executionMode === 'ANALYZE' ? 'Анализ' : 'Симуляция' }}
        </button>
        <button class="save-schema-btn" @click.stop="saveSchema">💾 Сохранить</button>
        <button class="export-schema-btn" @click.stop="exportSchema">📊 Экспорт</button>
        <button class="delete-schema-btn" @click.stop="confirmDeleteSchema" title="Удалить схему">🗑</button>
      </div>
    </div>

    <VueFlow ref="vueFlowRef" v-model="elements" :node-types="nodeTypes" :edge-types="edgeTypes"
      :fit-view-on-init="true" :multi-select-on-click="true" @connect="onConnect" @node-drag-stop="onNodeDragStop"
      @node-click="onNodeClick" @node-context-menu="onNodeContextMenu" @edge-click="onEdgeClick" @pane-click="onPaneClick"
      @node-double-click="onNodeDoubleClick">
      <Background />
      <Controls />
      <MiniMap />

      <div v-if="showSearch" class="search-panel">
        <input ref="searchInput" v-model="searchQuery" placeholder="Поиск узлов..." class="search-input"
          @keydown.escape="showSearch = false; searchQuery = ''" />
        <button class="search-close" @click="showSearch = false; searchQuery = ''">✕</button>
      </div>

      <div class="toolbar-panel">
        <div class="toolbar-group toolbar-add">
          <button class="toolbar-btn toolbar-add-btn" @click="showAddMenu = !showAddMenu" title="Добавить узел">
            ＋ Добавить <span class="chevron">{{ showAddMenu ? '▲' : '▼' }}</span>
          </button>
          <div v-if="showAddMenu" class="add-dropdown">
            <button @click="addNode('source'); showAddMenu = false">📥 Source</button>
            <button @click="addNode('agent'); showAddMenu = false">🤖 Agent</button>
            <button @click="addNode('condition'); showAddMenu = false">⚖️ Condition</button>
            <button @click="addNode('loop'); showAddMenu = false">🔄 Loop</button>
            <button @click="addNode('output'); showAddMenu = false">📤 Output</button>
            <button @click="addNode('memory'); showAddMenu = false">🧠 Memory</button>
            <button @click="addNode('guardrail'); showAddMenu = false">🛡️ Guardrail</button>
            <button @click="addNode('human'); showAddMenu = false">👤 Human</button>
            <button @click="addNode('fallback'); showAddMenu = false">🔄 Fallback</button>
            <button @click="addNode('subagent'); showAddMenu = false">🤝 Subagent</button>
            <button @click="addNode('comment'); showAddMenu = false">📝 Заметка</button>
          </div>
        </div>
        <div class="toolbar-separator"></div>
        <div class="toolbar-group">
          <button class="toolbar-btn" @click="groupSelectedNodes" :disabled="selectedNodeIds.size < 2" title="Группировать (Ctrl+G)">📦 Группа</button>
          <button class="toolbar-btn" @click="ungroupSelectedNode"
            :disabled="!selectedNodeId || !(props.schema.nodes || []).find(n => n.id === selectedNodeId)?.parentId"
            title="Разгруппировать">📤 Разгрупп.</button>
        </div>
        <div class="toolbar-separator"></div>
        <div class="toolbar-group">
          <button class="toolbar-btn" @click="showHistory = !showHistory" title="История выполнений">📜</button>
          <button class="toolbar-btn" @click="showMemoryGraph = !showMemoryGraph" title="Граф памяти">🧠</button>
          <button class="toolbar-btn" @click="exportAsImage" title="Сохранить как PNG">📷</button>
        </div>
      </div>
    </VueFlow>

    <ExecutionPanel ref="executionPanelRef" :visible="isExecuting || showExecutionPanel" :is-executing="isExecuting"
      :progress="executionProgress" :elapsed-seconds="elapsedSeconds" :total-nodes="totalNodes"
      :completed-nodes="completedNodes" :logs="executionLogs" :total-tokens="executionTotalTokens"
      :estimated-cost="executionEstimatedCost" @stop="stopExecution" @close="closeExecutionPanel"
      @highlight-node="highlightNode" />

    <PromptEditorModal :visible="showPromptEditor" :node-name="promptEditorNodeName"
      :user-prompt="promptEditorUserPrompt" :system-prompt="promptEditorSystemPrompt" @close="showPromptEditor = false"
      @save="onPromptEditorSave" />

    <ExecutionHistory :visible="showHistory" :schema-id="schema.id" @close="showHistory = false" />

    <MemoryGraphView :visible="showMemoryGraph" @close="showMemoryGraph = false" />

    <!-- Floating memory result cards -->
    <MemoryResultCard v-for="card in memoryResultCards" :key="card.id"
      :result="{ wing: card.wing, room: card.room, content: card.content, score: card.score }"
      :initial-position="{ x: card.x, y: card.y }" @close="closeMemoryCard(card.id)" @pin="pinMemoryResult" />

    <AppModal v-model="showDeleteConfirm" title="Удалить схему?">
      <p>Вы действительно хотите удалить схему "{{ schema.name }}"? Это действие нельзя отменить.</p>
      <div class="modal-buttons">
        <button @click="deleteSchema" class="delete-confirm-btn">Да, удалить</button>
        <button @click="showDeleteConfirm = false">Отмена</button>
      </div>
    </AppModal>

    <NodeContextMenu
      :visible="ctxMenu.visible"
      :x="ctxMenu.x"
      :y="ctxMenu.y"
      :can-edit-prompt="ctxMenu.nodeType === 'agent'"
      @rename="startRenameFromCtx"
      @duplicate="duplicateNode"
      @delete="deleteSelectedNode"
      @toggle-collapse="toggleCollapseCtx"
      @edit-prompt="openPromptEditorFromCtx"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, markRaw, onMounted, onUnmounted, computed, nextTick } from 'vue';
import { VueFlow, type Node, type Edge, type Connection, type NodeDragEvent, type NodeMouseEvent, type EdgeMouseEvent, MarkerType, useVueFlow } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { MiniMap } from '@vue-flow/minimap';
import type { WorkflowSchema, FlowNode, FlowEdge, ExecutionMode } from '../../types';
import AgentNode from '../nodes/AgentNode.vue';
import SourceNode from '../nodes/SourceNode.vue';
import OutputNode from '../nodes/OutputNode.vue';
import ConditionNode from '../nodes/ConditionNode.vue';
import LoopNode from '../nodes/LoopNode.vue';
import GroupNode from '../nodes/GroupNode.vue';
import CommentNode from '../nodes/CommentNode.vue';
import MemoryNode from '../nodes/MemoryNode.vue';
import GuardrailNode from '../nodes/GuardrailNode.vue';
import HumanNode from '../nodes/HumanNode.vue';
import FallbackNode from '../nodes/FallbackNode.vue';
import SubagentNode from '../nodes/SubagentNode.vue';
import CustomEdge from '../edges/CustomEdge.vue';
import ExecutionPanel from '../execution/ExecutionPanel.vue';
import AppModal from '../ui/AppModal.vue';
import NodeContextMenu from './NodeContextMenu.vue';
import PromptEditorModal from '../editor/PromptEditorModal.vue';
import ExecutionHistory from '../execution/ExecutionHistory.vue';
import MemoryGraphView from '../memory/MemoryGraphView.vue';
import MemoryResultCard from '../nodes/MemoryResultCard.vue';
import { useWebSocket } from '../../composables/useWebSocket';
import { useSchemaStore } from '../../stores/schemaStore';
import { schemaApi } from '../../services/api';
import { toPng, toSvg } from 'html-to-image';

const props = defineProps<{
  schema: WorkflowSchema;
}>();

const emit = defineEmits<{
  (e: 'update', schema: WorkflowSchema): void;
  (e: 'delete', id: string): void;
  (e: 'save'): void;
  (e: 'export'): void;
}>();

const nodeTypes = {
  agent: AgentNode,
  source: SourceNode,
  output: OutputNode,
  condition: ConditionNode,
  loop: LoopNode,
  group: GroupNode,
  comment: CommentNode,
  memory: MemoryNode,
  guardrail: GuardrailNode,
  human: HumanNode,
  fallback: FallbackNode,
  subagent: SubagentNode,
} as any;

const edgeTypes = {
  custom: CustomEdge,
};

const elements = ref<(Node | Edge)[]>([]);
const selectedNodeId = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);
const selectedNodeIds = ref<Set<string>>(new Set());
const showDeleteConfirm = ref(false);
const showExecutionPanel = ref(false);
const executionPanelRef = ref<InstanceType<typeof ExecutionPanel> | null>(null);
const showSearch = ref(false);
const searchQuery = ref('');
let nextNodeOffset = 0;
const copiedNode = ref<FlowNode | null>(null);
const searchInput = ref<HTMLInputElement | null>(null);
const showPromptEditor = ref(false);
const promptEditorNodeId = ref<string | null>(null);
const showHistory = ref(false);
const showAddMenu = ref(false);
const ctxMenu = ref<{ visible: boolean; x: number; y: number; nodeId: string; nodeType: string }>({
  visible: false, x: 0, y: 0, nodeId: '', nodeType: ''
});
const showMemoryGraph = ref(false);
const memoryResultCards = ref<Array<{ id: string; wing: string; room: string; content: string; score?: number; x: number; y: number }>>([]);

// Undo/Redo
const undoStack = ref<string[]>([]);
const redoStack = ref<string[]>([]);
const MAX_HISTORY = 50;
let lastSnapshot = '';

function snapshotState() {
  const state = JSON.stringify({
    nodes: props.schema.nodes || [],
    edges: props.schema.edges || [],
  });
  return state;
}

function pushUndo() {
  const snap = snapshotState();
  if (snap === lastSnapshot) return;
  undoStack.value.push(lastSnapshot || snap);
  if (undoStack.value.length > MAX_HISTORY) undoStack.value.shift();
  redoStack.value = [];
  lastSnapshot = snap;
}

function undo() {
  if (undoStack.value.length === 0) return;
  redoStack.value.push(snapshotState());
  const prev = undoStack.value.pop()!;
  const state = JSON.parse(prev);
  lastSnapshot = JSON.stringify(state);
  emit('update', { ...props.schema, nodes: state.nodes, edges: state.edges });
}

function redo() {
  if (redoStack.value.length === 0) return;
  undoStack.value.push(snapshotState());
  const next = redoStack.value.pop()!;
  const state = JSON.parse(next);
  lastSnapshot = JSON.stringify(state);
  emit('update', { ...props.schema, nodes: state.nodes, edges: state.edges });
}

const schemaStore = useSchemaStore();
const { connect, disconnect, isConnected } = useWebSocket();
const isExecuting = ref(false);
const executionMode = ref<ExecutionMode>(schemaStore.executionMode);
watch(executionMode, (mode) => schemaStore.setExecutionMode(mode));
const executionProgress = ref(0);
const totalNodes = ref(0);
const completedNodes = ref(0);
const executionLogs = ref<LogEntry[]>([]);
const nodeProgress = ref<Record<string, number>>({});
const nodeTimes = ref<Record<string, number>>({});
const executionTotalTokens = ref(0);
const executionEstimatedCost = ref(0);
const executionStartTime = ref(0);
const elapsedSeconds = ref(0);
let timerInterval: number | null = null;

interface LogEntry {
  timestamp: number;
  message: string;
  level: 'info' | 'error' | 'success' | 'warning';
  nodeId?: string;
}

function pushLog(message: string, level: 'info' | 'error' | 'success' | 'warning' = 'info', nodeId?: string) {
  const entry: LogEntry = {
    timestamp: Date.now(),
    message,
    level,
    nodeId
  };
  executionLogs.value.unshift(entry);
  if (executionLogs.value.length > 50) {
    executionLogs.value.splice(50);
  }
}

function startTimer() {
  executionStartTime.value = Date.now();
  elapsedSeconds.value = 0;
  timerInterval = window.setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - executionStartTime.value) / 1000);
  }, 1000);
}

function stopTimer() {
  if (timerInterval !== null) {
    clearInterval(timerInterval);
    timerInterval = null;
  }
}

function resetExecutionPanel() {
  executionLogs.value = [];
  nodeProgress.value = {};
  completedNodes.value = 0;
  executionProgress.value = 0;
  elapsedSeconds.value = 0;
  stopTimer();
}

function isSearchMatch(nodeName: string, nodeType: string): boolean {
  if (!searchQuery.value) return false;
  const q = searchQuery.value.toLowerCase();
  return nodeName.toLowerCase().includes(q) || nodeType.toLowerCase().includes(q);
}

function convertToFlowElements() {
  const nodes: Node[] = (props.schema.nodes || []).map(node => ({
    id: node.id,
    type: node.type,
    position: node.position || { x: 100, y: 100 },
    parentNode: node.parentId || undefined,
    selected: selectedNodeId.value === node.id,
    class: isSearchMatch(node.name, node.type) ? 'search-match' : undefined,
    data: {
      ...node.data,
      name: node.name,
      status: node.status,
      progress: node.progress,
      executionStatus: node.executionStatus,
      collapsed: node.collapsed,
      nodeTimeMs: node.data?.nodeTimeMs,
      onUpdate: (updates: any) => {
        const updatedNodes = (props.schema.nodes || []).map(n =>
          n.id === node.id ? { ...n, ...updates, data: { ...n.data, ...updates } } : n
        );
        emit('update', { ...props.schema, nodes: updatedNodes });
      },
      onRename: (newName: string) => {
        const updatedNodes = (props.schema.nodes || []).map(n =>
          n.id === node.id ? { ...n, name: newName } : n
        );
        emit('update', { ...props.schema, nodes: updatedNodes });
      },
      onDelete: () => {
        deleteNode(node.id);
      },
      onOpenPromptEditor: node.type === 'agent' ? () => openPromptEditor(node.id) : undefined,
      onMemoryResults: node.type === 'memory' ? (nodeId: string, results: any[]) => {
        showMemoryResults(nodeId, results);
      } : undefined,
    },
  }));

  const edges: Edge[] = (props.schema.edges || []).map(edge => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: 'custom',
    sourceHandle: edge.sourcePort || undefined,
    targetHandle: edge.targetPort || undefined,
    markerEnd: { type: MarkerType.ArrowClosed },
    selected: selectedEdgeId.value === edge.id,
    data: {
      onDelete: () => {
        console.log('Deleting edge from callback:', edge.id);
        deleteEdge(edge.id);
      },
    },
  }));

  elements.value = [...nodes, ...edges];
}

function deleteNode(nodeId: string) {
  pushUndo();
  const updatedNodes = (props.schema.nodes || []).filter(n => n.id !== nodeId);
  const updatedEdges = (props.schema.edges || []).filter(e =>
    e.source !== nodeId && e.target !== nodeId
  );
  emit('update', { ...props.schema, nodes: updatedNodes, edges: updatedEdges });
  if (selectedNodeId.value === nodeId) {
    selectedNodeId.value = null;
  }
}

function deleteEdge(edgeId: string) {
  pushUndo();
  console.log('Deleting edge:', edgeId);
  const updatedEdges = (props.schema.edges || []).filter(e => e.id !== edgeId);
  emit('update', { ...props.schema, edges: updatedEdges });
  if (selectedEdgeId.value === edgeId) {
    selectedEdgeId.value = null;
  }
}

function onNodeClick(event: NodeMouseEvent) {
  console.log('Node clicked:', event.node.id);
  ctxMenu.value.visible = false;
  const nodeId = event.node.id;
  if (event.event?.shiftKey) {
    // Multi-select with Shift+click
    const newSet = new Set(selectedNodeIds.value);
    if (newSet.has(nodeId)) newSet.delete(nodeId);
    else newSet.add(nodeId);
    selectedNodeIds.value = newSet;
  } else {
    selectedNodeIds.value = new Set([nodeId]);
  }
  selectedNodeId.value = nodeId;
  selectedEdgeId.value = null;
  convertToFlowElements();
}

function onEdgeClick(event: EdgeMouseEvent) {
  console.log('Edge clicked:', event.edge.id);
  selectedEdgeId.value = event.edge.id;
  selectedNodeId.value = null;
  convertToFlowElements();
}

function onPaneClick() {
  console.log('Pane clicked');
  selectedNodeId.value = null;
  selectedEdgeId.value = null;
  showAddMenu.value = false;
  ctxMenu.value.visible = false;
  convertToFlowElements();
}

function onNodeContextMenu(event: NodeMouseEvent) {
  event.event?.preventDefault();
  const node = event.node;
  selectedNodeId.value = node.id;
  ctxMenu.value = {
    visible: true,
    x: (event.event as MouseEvent).clientX,
    y: (event.event as MouseEvent).clientY,
    nodeId: node.id,
    nodeType: (node.data as any)?.type || node.type || '',
  };
}

function startRenameFromCtx() {
  ctxMenu.value.visible = false;
  // Find the node element and trigger dblclick on name
  const node = (props.schema.nodes || []).find(n => n.id === ctxMenu.value.nodeId);
  if (node && node.data?.onRename) {
    const newName = prompt('Новое имя:', node.name);
    if (newName?.trim()) node.data.onRename(newName.trim());
  }
}

function duplicateNode() {
  ctxMenu.value.visible = false;
  const node = (props.schema.nodes || []).find(n => n.id === ctxMenu.value.nodeId);
  if (!node) return;
  const newNode: any = {
    ...node,
    id: `node-${Date.now()}`,
    position: { x: (node.position?.x || 0) + 40, y: (node.position?.y || 0) + 40 },
    data: { ...node.data, name: `${node.name} (копия)` },
  };
  delete newNode.data.onRename;
  delete newNode.data.onDelete;
  delete newNode.data.onUpdate;
  addNode(node.type || 'agent', newNode.position, newNode.data);
}

function deleteSelectedNode() {
  ctxMenu.value.visible = false;
  if (selectedNodeId.value) {
    removeNode(selectedNodeId.value);
  }
}

function toggleCollapseCtx() {
  ctxMenu.value.visible = false;
  // Toggle collapse handled by node expand button — emit custom event
  const node = elements.value.find((el: any) => el.id === ctxMenu.value.nodeId);
  if (node && (node as any).data?.onToggleExpand) {
    (node as any).data.onToggleExpand();
  }
}

function openPromptEditorFromCtx() {
  ctxMenu.value.visible = false;
  const node = (props.schema.nodes || []).find(n => n.id === ctxMenu.value.nodeId);
  if (node?.data?.onOpenPromptEditor) {
    node.data.onOpenPromptEditor();
  }
}

function deleteSelected() {
  if (selectedNodeId.value) {
    console.log('Deleting selected node:', selectedNodeId.value);
    deleteNode(selectedNodeId.value);
  } else if (selectedEdgeId.value) {
    console.log('Deleting selected edge:', selectedEdgeId.value);
    deleteEdge(selectedEdgeId.value);
  }
}

function confirmDeleteSchema() {
  showDeleteConfirm.value = true;
}

function deleteSchema() {
  emit('delete', props.schema.id);
  showDeleteConfirm.value = false;
}

function handleKeyDown(event: KeyboardEvent) {
  const mod = event.metaKey || event.ctrlKey;

  if (event.key === 'Delete' || event.key === 'Del') {
    deleteSelected();
    return;
  }

  // Cmd/Ctrl + S — сохранить
  if (mod && event.key === 's') {
    event.preventDefault();
    saveSchema();
    return;
  }

  // Cmd/Ctrl + Z — undo
  if (mod && event.key === 'z' && !event.shiftKey) {
    event.preventDefault();
    undo();
    return;
  }

  // Cmd/Ctrl + Shift + Z — redo
  if (mod && event.key === 'z' && event.shiftKey) {
    event.preventDefault();
    redo();
    return;
  }

  // Cmd/Ctrl + F — поиск
  if (mod && event.key === 'f') {
    event.preventDefault();
    showSearch.value = !showSearch.value;
    if (!showSearch.value) searchQuery.value = '';
    return;
  }

  // Escape — закрыть поиск
  if (event.key === 'Escape' && showSearch.value) {
    showSearch.value = false;
    searchQuery.value = '';
    return;
  }

  // Cmd/Ctrl + Enter — выполнить
  if (mod && event.key === 'Enter') {
    event.preventDefault();
    executeSchema();
    return;
  }

  // Cmd/Ctrl + C — копировать узел
  if (mod && event.key === 'c' && selectedNodeId.value) {
    event.preventDefault();
    const node = (props.schema.nodes || []).find(n => n.id === selectedNodeId.value);
    if (node) {
      copiedNode.value = { ...node, data: { ...node.data } };
    }
    return;
  }

  // Cmd/Ctrl + V — вставить узел
  if (mod && event.key === 'v' && copiedNode.value) {
    event.preventDefault();
    pasteNode();
    return;
  }

  // Cmd/Ctrl + D — дублировать (аналог C+V за один шаг)
  if (mod && event.key === 'd' && selectedNodeId.value) {
    event.preventDefault();
    const node = (props.schema.nodes || []).find(n => n.id === selectedNodeId.value);
    if (node) {
      copiedNode.value = { ...node, data: { ...node.data } };
      pasteNode();
    }
    return;
  }

  // Cmd/Ctrl + G — группировать выделенные узлы
  if (mod && event.key === 'g' && !event.shiftKey) {
    event.preventDefault();
    groupSelectedNodes();
    return;
  }

  // Cmd/Ctrl + Shift + G — разгруппировать
  if (mod && event.key === 'g' && event.shiftKey) {
    event.preventDefault();
    ungroupSelectedNode();
    return;
  }

  // Cmd/Ctrl + E — открыть редактор промпта
  if (mod && event.key === 'e' && selectedNodeId.value) {
    event.preventDefault();
    const node = (props.schema.nodes || []).find(n => n.id === selectedNodeId.value);
    if (node?.type === 'agent') {
      openPromptEditor(node.id);
    }
    return;
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', handleKeyDown);
  window.addEventListener('axolotl:highlight-node', handleExternalHighlight);
}

onUnmounted(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('keydown', handleKeyDown);
    window.removeEventListener('axolotl:highlight-node', handleExternalHighlight);
  }
  disconnect();
});

function handleExternalHighlight(event: Event) {
  const detail = (event as CustomEvent).detail as { nodeId: string };
  if (detail?.nodeId) {
    highlightNode(detail.nodeId);
  }
}

watch(() => props.schema, () => {
  convertToFlowElements();
  const snap = snapshotState();
  if (!lastSnapshot) lastSnapshot = snap;
}, { deep: true, immediate: true });

watch(searchQuery, () => {
  convertToFlowElements();
});

function generateUniqueName(baseName: string): string {
  const names = (props.schema.nodes || []).map(n => n.name);
  if (!names.includes(baseName)) return baseName;
  let counter = 1;
  while (names.includes(`${baseName} (${counter})`)) counter++;
  return `${baseName} (${counter})`;
}

function addNode(type: string) {
  const nameMap = {
    source: 'Входные данные',
    agent: 'Аналитик',
    condition: 'Условие',
    loop: 'Цикл',
    output: 'Результат',
    memory: 'Память',
    guardrail: 'Валидация',
    human: 'Человек',
    fallback: 'Резервный путь',
    comment: 'Заметка',
  };
  const baseName = nameMap[type as keyof typeof nameMap] || 'Новый узел';
  const uniqueName = generateUniqueName(baseName);

  const offset = nextNodeOffset;
  const newNode: FlowNode = {
    id: `node-${Date.now()}-${Math.random()}`,
    type: type as any,
    name: uniqueName,
    position: { x: 250 + offset * 30, y: 250 + offset * 30 },
    data: {},
    status: 'idle',
  };

  if (type === 'agent') newNode.data.userPrompt = '';
  if (type === 'source') newNode.data.sourceData = '';
  if (type === 'condition') newNode.data.condition = '';
  if (type === 'loop') { newNode.data.loopCondition = ''; newNode.data.maxIterations = 10; }

  nextNodeOffset++;
  pushUndo();
  const updatedNodes = [...(props.schema.nodes || []), newNode];
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function pasteNode() {
  if (!copiedNode.value) return;
  const source = copiedNode.value;
  const uniqueName = generateUniqueName(source.name);
  const pasted: FlowNode = {
    ...source,
    id: `node-${Date.now()}-${Math.random()}`,
    name: uniqueName,
    position: {
      x: (source.position?.x || 250) + 40,
      y: (source.position?.y || 250) + 40,
    },
    data: { ...source.data },
    status: 'idle',
  };
  delete pasted.progress;
  delete pasted.executionStatus;
  nextNodeOffset++;
  const updatedNodes = [...(props.schema.nodes || []), pasted];
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function onConnect(connection: Connection) {
  pushUndo();
  const newEdge: FlowEdge = {
    id: `edge-${Date.now()}`,
    source: connection.source || '',
    target: connection.target || '',
    sourcePort: connection.sourceHandle || undefined,
    targetPort: connection.targetHandle || undefined,
    type: 'data',
  };
  const updatedEdges = [...(props.schema.edges || []), newEdge];
  emit('update', { ...props.schema, edges: updatedEdges });
}

async function executeSchema() {
  console.log('▶️ executeSchema вызвана');
  if (!props.schema.id) {
    console.error('❌ schema.id отсутствует');
    return;
  }

  console.log('✅ Начало выполнения схемы:', props.schema.id);
  isExecuting.value = true;
  showExecutionPanel.value = true;
  executionProgress.value = 0;
  totalNodes.value = props.schema.nodes?.length || 0;
  completedNodes.value = 0;
  resetExecutionPanel();
  startTimer();
  pushLog('Запрошено начало выполнения схемы', 'info');

  try {
    console.log('🔌 Подключение к WebSocket...');
    connect(props.schema.id, {
      onProgress: (data) => {
        console.log('📊 Progress callback:', data);
        updateNodeProgress(data.nodeId, data.status, data.progress);
        nodeProgress.value[data.nodeId] = data.progress;
        const total = totalNodes.value || 1;
        const progressSum = Object.values(nodeProgress.value).reduce((sum, value) => sum + value, 0);
        executionProgress.value = Math.min(100, progressSum / total);
        pushLog(`${data.message} (${data.progress}%)`, 'info', data.nodeId);
        if (data.status === 'completed' || data.progress >= 100) {
          completedNodes.value = Math.min(total, completedNodes.value + 1);
          executionProgress.value = total > 0 ? (completedNodes.value / total) * 100 : 100;
        }
      },
      onResult: (data) => {
        console.log('📋 Result callback:', data);
        updateNodeResult(data.nodeId, data.result);
        pushLog(`результат получен`, 'success', data.nodeId);
      },
      onError: (data) => {
        console.log('❌ Error callback:', data);
        updateNodeStatus(data.nodeId, 'failed');
        pushLog(`ошибка: ${data.error}`, 'error', data.nodeId);
        disconnect();
        isExecuting.value = false;
        stopTimer();
      },
      onComplete: (data) => {
        console.log('✅ Complete callback:', data);
        pushLog(`Выполнение завершено: ${data.nodesCompleted}/${totalNodes.value} узлов`, 'success');
        disconnect();
        isExecuting.value = false;
        stopTimer();
        alert(`Выполнение завершено! Время: ${data.totalTime}мс, узлов выполнено: ${data.nodesCompleted}`);
      },
      onMetrics: (data) => {
        console.log('📈 Metrics callback:', data);
        // Обновляем локальные метрики из WebSocket
        totalNodes.value = data.totalNodes;
        completedNodes.value = data.completedNodes;
        elapsedSeconds.value = data.elapsedTime / 1000; // конвертируем в секунды
        executionProgress.value = data.totalNodes > 0 ? (data.completedNodes / data.totalNodes) * 100 : 100;
      },
      onWave: (data: { waveNumber: number; nodeIds: string[]; status: string }) => {
        console.log('🌊 Wave callback:', data);
        const waveStatus = data.status as 'pending' | 'running' | 'completed';
        executionPanelRef.value?.updateWave(data.waveNumber, data.nodeIds, waveStatus);
        pushLog(`Волна ${data.waveNumber + 1}: ${data.status}`, 'info', '');
      },
      onNodeTime: (data) => {
        console.log('⏱ Node time:', data);
        nodeTimes.value[data.nodeId] = data.durationMs;
        const updatedNodes = (props.schema.nodes || []).map(n =>
          n.id === data.nodeId ? { ...n, data: { ...n.data, nodeTimeMs: data.durationMs } } : n
        );
        emit('update', { ...props.schema, nodes: updatedNodes });
        pushLog(`Время: ${data.durationMs}мс`, 'info', data.nodeId);
      },
      onToken: (data) => {
        // Append streaming tokens to node result in real-time, show typing animation
        const updatedNodes = (props.schema.nodes || []).map(n => {
          if (n.id === data.nodeId) {
            const currentResult = n.data?.result || '';
            return { ...n, data: { ...n.data, result: currentResult + data.token, isStreaming: true } };
          }
          return n;
        });
        emit('update', { ...props.schema, nodes: updatedNodes });
      },
      onLog: (message) => {
        try {
          const logData = JSON.parse(message);
          if (logData.type === 'log') {
            pushLog(logData.message, logData.level, logData.nodeId || undefined);
          }
        } catch (e) {
          console.warn('Failed to parse log message:', message);
        }
      },
    });

    console.log('⏳ Ожидание подключения WebSocket...');
    while (!isConnected.value) {
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    console.log('✅ WebSocket подключен, отправка запроса на выполнение...');

    console.log('📤 Отправка запроса на выполнение...');
    await schemaStore.executeCurrentSchema();
    console.log('✅ Запрос на выполнение отправлен');

  } catch (error) {
    console.error('❌ Ошибка выполнения:', error);
    pushLog('Ошибка запуска выполнения схемы', 'error');
    isExecuting.value = false;
    stopTimer();
    alert('Ошибка выполнения схемы');
  }
}

function saveSchema() {
  emit('save');
}

function exportSchema() {
  emit('export');
}

async function stopExecution() {
  if (!props.schema.id) return;
  try {
    pushLog('Запрошена остановка выполнения', 'warning');
    await schemaApi.stopSchema(props.schema.id);
    disconnect();
    isExecuting.value = false;
    stopTimer();
    pushLog('Запрос на остановку отправлен', 'info');
  } catch (error) {
    console.error('❌ Ошибка остановки выполнения:', error);
    pushLog('Ошибка отправки запроса на остановку', 'error');
  }
}

function closeExecutionPanel() {
  showExecutionPanel.value = false;
}

function highlightNode(nodeId: string) {
  const selector = `[data-id="${nodeId}"], [data-node-id="${nodeId}"], [data-nodeid="${nodeId}"]`;
  let nodeElement = document.querySelector<HTMLElement>(selector);

  if (!nodeElement) {
    nodeElement = Array.from(document.querySelectorAll<HTMLElement>('.vue-flow__node, .vue-flow__node-custom, .vue-flow__node-default'))
      .find(el => el.dataset.id === nodeId || el.dataset.nodeId === nodeId || el.getAttribute('data-nodeid') === nodeId) || null;
  }

  if (nodeElement) {
    nodeElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
    nodeElement.classList.add('highlighted-node');
    setTimeout(() => {
      nodeElement.classList.remove('highlighted-node');
    }, 2000);
  } else {
    console.warn('Не удалось найти узел для подсветки:', nodeId);
  }
}

function normalizeStatus(status: string) {
  const normalized = status?.toString().toLowerCase();
  if (['idle', 'running', 'completed', 'failed'].includes(normalized)) {
    return normalized as 'idle' | 'running' | 'completed' | 'failed';
  }
  return 'idle';
}

function updateNodeProgress(nodeId: string, status: string, progress: number) {
  const normalizedStatus = normalizeStatus(status);
  const updatedNodes = (props.schema.nodes || []).map(n =>
    n.id === nodeId
      ? {
        ...n,
        status: normalizedStatus as any,
        progress,
        executionStatus: normalizedStatus as any,
        data: { ...n.data, progress, executionStatus: normalizedStatus, status: normalizedStatus },
      }
      : n
  );
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function updateNodeResult(nodeId: string, result: any) {
  const updatedNodes = (props.schema.nodes || []).map(n =>
    n.id === nodeId ? { ...n, data: { ...n.data, result, isStreaming: false } } : n
  );
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function updateNodeStatus(nodeId: string, status: 'idle' | 'running' | 'completed' | 'failed') {
  const normalizedStatus = normalizeStatus(status);
  const updatedNodes = (props.schema.nodes || []).map(n =>
    n.id === nodeId
      ? {
        ...n,
        status: normalizedStatus,
        executionStatus: normalizedStatus,
        data: { ...n.data, executionStatus: normalizedStatus, status: normalizedStatus, isStreaming: false },
      }
      : n
  );
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function onNodeDragStop(event: NodeDragEvent) {
  const node = event.node;
  if (!node || !node.id) return;

  pushUndo();
  const currentNodes = props.schema.nodes || [];
  const updatedNodes = currentNodes.map(n => {
    if (n.id === node.id) {
      return { ...n, position: node.position };
    }
    return n;
  });
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function editSchemaName() {
  const newName = prompt('Введите новое имя схемы:', props.schema.name);
  if (newName && newName.trim()) {
    pushUndo();
    emit('update', { ...props.schema, name: newName.trim() });
  }
}

// === PNG/SVG Export ===
async function exportAsImage() {
  const el = document.querySelector('.vue-flow');
  if (!el) return;
  try {
    const dataUrl = await toPng(el as HTMLElement, { backgroundColor: '#1a1a2e', pixelRatio: 2 });
    const a = document.createElement('a');
    a.href = dataUrl;
    a.download = `${props.schema.name || 'schema'}.png`;
    a.click();
  } catch (e) {
    console.error('PNG export failed:', e);
  }
}

// === Memory Result Floating Cards ===
function showMemoryResults(nodeId: string, results: Array<{ wing: string; room: string; content: string; score?: number }>) {
  // Find the memory node position to place cards nearby
  const memoryNode = (props.schema.nodes || []).find(n => n.id === nodeId);
  const baseX = memoryNode?.position?.x ?? 400;
  const baseY = memoryNode?.position?.y ?? 200;

  memoryResultCards.value = results.map((r, i) => ({
    id: `mem-card-${nodeId}-${i}`,
    wing: r.wing,
    room: r.room,
    content: r.content,
    score: r.score,
    x: baseX + 350 + (i % 3) * 340,
    y: baseY + Math.floor(i / 3) * 200,
  }));
}

function closeMemoryCard(id: string) {
  memoryResultCards.value = memoryResultCards.value.filter(c => c.id !== id);
}

function pinMemoryResult(result: { wing: string; room: string; content: string; score?: number }) {
  // Create a new SourceNode with the pinned memory content
  const nodeId = `source-memory-${Date.now()}`;
  const lastNode = (props.schema.nodes || []).slice(-1)[0];
  const x = (lastNode?.position?.x ?? 100) + 50;
  const y = (lastNode?.position?.y ?? 100) + 50;

  const memoryNode: FlowNode = {
    id: nodeId,
    type: 'source',
    name: `Memory: ${result.wing}/${result.room}`,
    position: { x, y },
    data: {
      sourceData: result.content,
      config: { memoryWing: result.wing, memoryRoom: result.room, memoryScore: result.score },
    },
    status: 'idle',
  };

  pushUndo();
  const updatedNodes = [...(props.schema.nodes || []), memoryNode];
  emit('update', { ...props.schema, nodes: updatedNodes });

  // Remove the floating card
  memoryResultCards.value = memoryResultCards.value.filter(c =>
    !(c.wing === result.wing && c.room === result.room && c.content === result.content)
  );
}

// === Zoom to node (double-click) ===
const vueFlowRef = ref<InstanceType<typeof VueFlow> | null>(null);

function onNodeDoubleClick(event: any) {
  const nodeId = event.node?.id;
  if (!nodeId) return;
  const node = (props.schema.nodes || []).find(n => n.id === nodeId);
  if (!node) return;

  // Zoom to node
  nextTick(() => {
    vueFlowRef.value?.fitView({ nodes: [nodeId], duration: 300, padding: 0.2 });
  });

  // Agent nodes also open prompt editor
  if (node?.type === 'agent') {
    openPromptEditor(nodeId);
  }
}

// === Prompt Editor ===
const promptEditorNodeName = computed(() => {
  if (!promptEditorNodeId.value) return '';
  return (props.schema.nodes || []).find(n => n.id === promptEditorNodeId.value)?.name || '';
});
const promptEditorUserPrompt = computed(() => {
  if (!promptEditorNodeId.value) return '';
  return (props.schema.nodes || []).find(n => n.id === promptEditorNodeId.value)?.data?.userPrompt || '';
});
const promptEditorSystemPrompt = computed(() => {
  if (!promptEditorNodeId.value) return undefined;
  return (props.schema.nodes || []).find(n => n.id === promptEditorNodeId.value)?.data?.systemPrompt;
});

function openPromptEditor(nodeId: string) {
  promptEditorNodeId.value = nodeId;
  showPromptEditor.value = true;
}

function onPromptEditorSave(data: { userPrompt: string; systemPrompt?: string }) {
  if (!promptEditorNodeId.value) return;
  const updatedNodes = (props.schema.nodes || []).map(n =>
    n.id === promptEditorNodeId.value
      ? { ...n, data: { ...n.data, userPrompt: data.userPrompt, ...(data.systemPrompt !== undefined ? { systemPrompt: data.systemPrompt } : {}) } }
      : n
  );
  emit('update', { ...props.schema, nodes: updatedNodes });
  showPromptEditor.value = false;
}

// === Node Grouping ===
function groupSelectedNodes() {
  const ids = Array.from(selectedNodeIds.value);
  if (ids.length < 2) return;
  pushUndo();

  const childNodes = (props.schema.nodes || []).filter(n => ids.includes(n.id));
  if (childNodes.length === 0) return;

  // Calculate bounding box
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const cn of childNodes) {
    const x = cn.position?.x || 0;
    const y = cn.position?.y || 0;
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x + 250);
    maxY = Math.max(maxY, y + 150);
  }

  const groupId = `group-${Date.now()}`;
  const groupNode: FlowNode = {
    id: groupId,
    type: 'group',
    name: 'Группа',
    position: { x: minX - 30, y: minY - 40 },
    data: {},
    status: 'idle',
  };

  // Set parentId on children, adjust positions relative to group
  const updatedNodes = (props.schema.nodes || []).map(n => {
    if (ids.includes(n.id)) {
      return {
        ...n,
        parentId: groupId,
        position: {
          x: (n.position?.x || 0) - (minX - 30),
          y: (n.position?.y || 0) - (minY - 40),
        },
      };
    }
    return n;
  });

  emit('update', { ...props.schema, nodes: [groupNode, ...updatedNodes] });
  selectedNodeIds.value = new Set();
}

function ungroupSelectedNode() {
  if (!selectedNodeId.value) return;
  const groupId = selectedNodeId.value;
  const groupNode = (props.schema.nodes || []).find(n => n.id === groupId);
  if (!groupNode) return;

  pushUndo();

  const updatedNodes = (props.schema.nodes || [])
    .filter(n => n.id !== groupId)
    .map(n => {
      if (n.parentId === groupId) {
        return {
          ...n,
          parentId: undefined,
          position: {
            x: (n.position?.x || 0) + (groupNode.position?.x || 0),
            y: (n.position?.y || 0) + (groupNode.position?.y || 0),
          },
        };
      }
      return n;
    });

  emit('update', { ...props.schema, nodes: updatedNodes });
  selectedNodeIds.value = new Set();
}
</script>

<style>
/* Глобальный стиль для подсветки найденных узлов (scoped не работает внутри Vue Flow) */
.vue-flow .search-match {
  box-shadow: 0 0 0 3px #6c63ff, 0 0 20px rgba(108, 99, 255, 0.4) !important;
  z-index: 100;
}
</style>

<style scoped>
.canvas-container {
  width: 100%;
  height: 100vh;
  position: relative;
}

.schema-name {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1000;
  background: #2d2d44;
  padding: 10px 16px;
  border-radius: 12px;
  color: #eee;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.25);
}

.schema-title {
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

.schema-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.schema-name button {
  padding: 8px 12px;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}

.run-schema-btn {
  background: #4f7cff;
}

.save-schema-btn {
  background: #20c997;
}

.export-schema-btn {
  background: #6c63ff;
}

.delete-schema-btn {
  background: #dc3545;
}

.schema-name button:hover {
  filter: brightness(1.05);
}

.schema-name button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.delete-schema-btn:hover {
  background: #c82333;
}

.toolbar-panel {
  position: absolute;
  top: 50px;
  left: 10px;
  background: var(--bg-card, #1e1e2e);
  padding: 6px 10px;
  border-radius: var(--radius-sm, 8px);
  z-index: 1000;
  display: flex;
  align-items: center;
  gap: 4px;
  box-shadow: var(--shadow-sm);
  border: 1px solid var(--border, #4a4a6a);
}

.toolbar-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.toolbar-add {
  position: relative;
}

.toolbar-separator {
  width: 1px;
  height: 24px;
  background: var(--border, #4a4a6a);
  margin: 0 4px;
}

.toolbar-btn {
  padding: 6px 10px;
  background: var(--accent, #6c63ff);
  border: none;
  border-radius: 4px;
  color: white;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
  transition: opacity 0.2s;
}

.toolbar-btn:hover:not(:disabled) {
  opacity: 0.85;
}

.toolbar-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.chevron {
  font-size: 10px;
  margin-left: 2px;
}

.add-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 4px;
  background: var(--bg-card, #1e1e2e);
  border: 1px solid var(--border, #4a4a6a);
  border-radius: var(--radius-sm, 6px);
  box-shadow: var(--shadow-md, 0 8px 30px rgba(0, 0, 0, 0.35));
  z-index: 1001;
  min-width: 160px;
  padding: 4px;
}

.add-dropdown button {
  display: block;
  width: 100%;
  padding: 8px 12px;
  background: none;
  border: none;
  color: var(--text-primary, #eee);
  cursor: pointer;
  text-align: left;
  border-radius: 4px;
  font-size: 13px;
}

.add-dropdown button:hover {
  background: var(--bg-hover, #3d3d5c);
}

.execution-panel {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 320px;
  max-height: 420px;
  background: rgba(20, 22, 34, 0.96);
  color: #eee;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
  padding: 16px;
  z-index: 1100;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.execution-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 14px;
}

.stop-btn {
  background: #ff5e5e;
  border: none;
  color: #fff;
  border-radius: 8px;
  padding: 6px 12px;
  cursor: pointer;
}

.stop-btn:hover {
  background: #ff4040;
}

.execution-panel__stats {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  color: #cbd5ff;
}

.execution-panel__logs {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  background: rgba(12, 14, 24, 0.95);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.execution-panel__log-entry {
  font-size: 12px;
  line-height: 1.4;
  color: #d8d8e8;
  margin-bottom: 6px;
  white-space: pre-wrap;
  word-break: break-word;
}

.modal-buttons {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.modal-buttons button {
  padding: 8px 16px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.delete-confirm-btn {
  background: var(--error);
  color: white;
}

.delete-confirm-btn:hover {
  opacity: 0.9;
}

.modal-buttons button:last-child {
  background: var(--accent);
  color: white;
}

.modal-buttons button:last-child:hover {
  opacity: 0.9;
}

/* Highlighted node animation */
.highlighted-node {
  animation: highlight-pulse 2s ease-in-out;
}

@keyframes highlight-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(108, 99, 255, 0.7);
    transform: scale(1);
  }

  50% {
    box-shadow: 0 0 0 10px rgba(108, 99, 255, 0);
    transform: scale(1.05);
  }

  100% {
    box-shadow: 0 0 0 0 rgba(108, 99, 255, 0);
    transform: scale(1);
  }
}

.search-panel {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1100;
  display: flex;
  gap: 4px;
}

.search-input {
  width: 280px;
  padding: 8px 12px;
  background: #2d2d44;
  border: 1px solid #6c63ff;
  border-radius: 8px;
  color: #eee;
  font-size: 14px;
  outline: none;
}

.search-input:focus {
  border-color: #8b83ff;
}

.search-close {
  background: #dc3545;
  border: none;
  color: white;
  border-radius: 8px;
  width: 36px;
  cursor: pointer;
  font-size: 14px;
}

.mode-selector {
  padding: 8px 12px;
  background: #2d2d44;
  border: 1px solid #6c63ff;
  border-radius: 8px;
  color: #eee;
  font-size: 14px;
  cursor: pointer;
  outline: none;
  min-width: 120px;
}

.mode-selector:hover {
  border-color: #8b83ff;
}

.mode-selector:focus {
  border-color: #8b83ff;
}
</style>
