<template>
  <div class="canvas-container">
    <div class="schema-name" @click="editSchemaName">
      <span class="schema-title" @click="editSchemaName">📝 {{ schema.name }}</span>
      <div class="schema-actions">
        <button class="run-schema-btn" @click="executeSchema" :disabled="isExecuting">▶️ Выполнить</button>
        <button class="save-schema-btn" @click="saveSchema">💾 Сохранить</button>
        <button class="export-schema-btn" @click="exportSchema">📊 Экспорт</button>
        <button class="delete-schema-btn" @click.stop="confirmDeleteSchema" title="Удалить схему">🗑</button>
      </div>
    </div>
    
    <VueFlow
      v-model="elements"
      :node-types="nodeTypes"
      :edge-types="edgeTypes"
      :fit-view-on-init="true"
      @connect="onConnect"
      @node-drag-stop="onNodeDragStop"
      @node-click="onNodeClick"
      @edge-click="onEdgeClick"
      @pane-click="onPaneClick"
    >
      <Background />
      <Controls />
      <MiniMap />
      
      <div class="toolbar-panel">
        <button @click="addNode('source')">📥 Source</button>
        <button @click="addNode('agent')">🤖 Agent</button>
        <button @click="addNode('output')">📤 Output</button>
      </div>
    </VueFlow>

    <ExecutionPanel
      :visible="isExecuting || showExecutionPanel"
      :is-executing="isExecuting"
      :progress="executionProgress"
      :elapsed-seconds="elapsedSeconds"
      :total-nodes="totalNodes"
      :completed-nodes="completedNodes"
      :logs="executionLogs"
      @stop="stopExecution"
      @close="closeExecutionPanel"
      @highlight-node="highlightNode"
    />
    
    <div v-if="showDeleteConfirm" class="modal-overlay" @click.self="showDeleteConfirm = false">
      <div class="modal-content">
        <h3>Удалить схему?</h3>
        <p>Вы действительно хотите удалить схему "{{ schema.name }}"? Это действие нельзя отменить.</p>
        <div class="modal-buttons">
          <button @click="deleteSchema" class="delete-confirm-btn">Да, удалить</button>
          <button @click="showDeleteConfirm = false">Отмена</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, markRaw, onUnmounted } from 'vue';
import { VueFlow, type Node, type Edge, type Connection, type NodeDragEvent, type NodeMouseEvent, type EdgeMouseEvent, MarkerType } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { MiniMap } from '@vue-flow/minimap';
import type { WorkflowSchema, FlowNode, FlowEdge } from '../../types';
import AgentNode from '../nodes/AgentNode.vue';
import SourceNode from '../nodes/SourceNode.vue';
import OutputNode from '../nodes/OutputNode.vue';
import CustomEdge from '../edges/CustomEdge.vue';
import ExecutionPanel from '../execution/ExecutionPanel.vue';
import { useWebSocket } from '../../composables/useWebSocket';
import { useSchemaStore } from '../../stores/schemaStore';
import { schemaApi } from '../../services/api';

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
} as any;

const edgeTypes = {
  custom: CustomEdge,
};

const elements = ref<(Node | Edge)[]>([]);
const selectedNodeId = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);
const showDeleteConfirm = ref(false);
const showExecutionPanel = ref(false);
let nextNodeOffset = 0;

const schemaStore = useSchemaStore();
const { connect, disconnect, isConnected } = useWebSocket();
const isExecuting = ref(false);
const executionProgress = ref(0);
const totalNodes = ref(0);
const completedNodes = ref(0);
const executionLogs = ref<LogEntry[]>([]);
const nodeProgress = ref<Record<string, number>>({});
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

function convertToFlowElements() {
  const nodes: Node[] = (props.schema.nodes || []).map(node => ({
    id: node.id,
    type: node.type,
    position: node.position || { x: 100, y: 100 },
    selected: selectedNodeId.value === node.id,
    data: {
      ...node.data,
      name: node.name,
      status: node.status,
      progress: node.progress,
      executionStatus: node.executionStatus,
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
    },
  }));

  const edges: Edge[] = (props.schema.edges || []).map(edge => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: 'custom',
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
  console.log('Deleting edge:', edgeId);
  const updatedEdges = (props.schema.edges || []).filter(e => e.id !== edgeId);
  emit('update', { ...props.schema, edges: updatedEdges });
  if (selectedEdgeId.value === edgeId) {
    selectedEdgeId.value = null;
  }
}

function onNodeClick(event: NodeMouseEvent) {
  console.log('Node clicked:', event.node.id);
  selectedNodeId.value = event.node.id;
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
  convertToFlowElements();
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
  if (event.key === 'Delete' || event.key === 'Del' || event.key === 'Backspace') {
    if (event.key === 'Backspace') {
      event.preventDefault();
    }
    deleteSelected();
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', handleKeyDown);
}

onUnmounted(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('keydown', handleKeyDown);
  }
  disconnect();
});

watch(() => props.schema, () => {
  convertToFlowElements();
}, { deep: true, immediate: true });

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
    output: 'Результат'
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
  
  nextNodeOffset++;
  const updatedNodes = [...(props.schema.nodes || []), newNode];
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function onConnect(connection: Connection) {
  const newEdge: FlowEdge = {
    id: `edge-${Date.now()}`,
    source: connection.source || '',
    target: connection.target || '',
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
    n.id === nodeId ? { ...n, data: { ...n.data, result } } : n
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
          data: { ...n.data, executionStatus: normalizedStatus, status: normalizedStatus },
        }
      : n
  );
  emit('update', { ...props.schema, nodes: updatedNodes });
}

function onNodeDragStop(event: NodeDragEvent) {
  const node = event.node;
  if (!node || !node.id) return;
  
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
    emit('update', { ...props.schema, name: newName.trim() });
  }
}
</script>

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
  background: #1e1e2e;
  padding: 10px;
  border-radius: 8px;
  z-index: 1000;
}
.toolbar-panel button {
  margin-right: 10px;
  padding: 6px 12px;
  background: #6c63ff;
  border: none;
  border-radius: 4px;
  color: white;
  cursor: pointer;
}
.toolbar-panel button:hover {
  background: #5a52d9;
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
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 2000;
}
.modal-content {
  background: #2d2d44;
  padding: 20px;
  border-radius: 12px;
  max-width: 400px;
}
.modal-content h3 {
  margin-bottom: 15px;
}
.modal-content p {
  margin-bottom: 20px;
  color: #ccc;
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
  background: #dc3545;
  color: white;
}
.delete-confirm-btn:hover {
  background: #c82333;
}
.modal-buttons button:last-child {
  background: #6c63ff;
  color: white;
}
.modal-buttons button:last-child:hover {
  background: #5a52d9;
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
</style>
