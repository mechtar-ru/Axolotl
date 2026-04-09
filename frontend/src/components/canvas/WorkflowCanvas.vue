<template>
  <div class="canvas-container">
    <div class="schema-name" @click="editSchemaName">
      📝 {{ schema.name }}
      <button class="delete-schema-btn" @click.stop="confirmDeleteSchema" title="Удалить схему">🗑</button>
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
import { VueFlow, type Node, type Edge, type Connection, type NodeDragEvent, type NodeMouseEvent, type EdgeMouseEvent } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { MiniMap } from '@vue-flow/minimap';
import type { WorkflowSchema, FlowNode, FlowEdge } from '../../types';
import AgentNode from '../nodes/AgentNode.vue';
import SourceNode from '../nodes/SourceNode.vue';
import OutputNode from '../nodes/OutputNode.vue';
import CustomEdge from '../edges/CustomEdge.vue';

const props = defineProps<{
  schema: WorkflowSchema;
}>();

const emit = defineEmits<{
  (e: 'update', schema: WorkflowSchema): void;
  (e: 'delete', id: string): void;
}>();

const nodeTypes = {
  agent: markRaw(AgentNode),
  source: markRaw(SourceNode),
  output: markRaw(OutputNode),
};

const edgeTypes = {
  custom: markRaw(CustomEdge),
};

const elements = ref<(Node | Edge)[]>([]);
const selectedNodeId = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);
const showDeleteConfirm = ref(false);
let nextNodeOffset = 0;

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
    markerEnd: { type: 'arrowclosed' },
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
  padding: 8px 16px;
  border-radius: 8px;
  cursor: pointer;
  color: #eee;
  display: flex;
  align-items: center;
  gap: 10px;
}
.delete-schema-btn {
  background: #dc3545;
  border: none;
  border-radius: 4px;
  color: white;
  cursor: pointer;
  padding: 2px 8px;
  font-size: 12px;
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
</style>
