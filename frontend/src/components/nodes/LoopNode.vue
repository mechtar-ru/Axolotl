<template>
  <div class="node loop-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button
      v-if="isSelected"
      class="delete-btn"
      @click.stop="handleDelete"
      title="Удалить узел"
    >
      ✕
    </button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">🔄</span>
      <span
        v-if="!editingName"
        class="node-name"
        @dblclick="startEditName"
      >
        {{ props.data.name }}
      </span>
      <input
        v-else
        ref="nameInput"
        v-model="localName"
        class="node-name-input"
        @blur="finishEditName"
        @keyup.enter="finishEditName"
      />
      <span class="node-status" :style="{ background: statusColor }"></span>
      <span class="execution-icon">{{ executionIcon }}</span>
      <button class="node-expand" @click="toggleExpand">
        {{ expanded ? '▼' : '▶' }}
      </button>
    </div>

    <div v-if="expanded" class="node-content">
      <label class="field-label">Условие выхода:</label>
      <textarea
        v-model="localLoopCondition"
        placeholder="iterations < 10"
        rows="2"
        @mousedown.stop
        @mouseup.stop
      />
      <label class="field-label">Макс. итераций:</label>
      <input
        v-model.number="localMaxIterations"
        type="number"
        min="1"
        max="1000"
        class="number-input"
        @mousedown.stop
      />
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
      <div v-if="props.data.result !== undefined && props.data.result !== null" class="node-result">
        <strong>Результат:</strong> {{ props.data.result }}
      </div>
    </div>

    <Handle id="body" type="source" :position="Position.Bottom" class="handle-body" />
    <Handle id="exit" type="source" :position="Position.Right" class="handle-exit" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue';
import { Handle, Position } from '@vue-flow/core';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    loopCondition?: string;
    maxIterations?: number;
    status?: string;
    result?: string;
    progress?: number;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
    onDelete?: () => void;
  };
}>();

const emit = defineEmits<{
  (e: 'delete'): void;
}>();

const expanded = ref(false);
const editingName = ref(false);
const localName = ref(props.data.name);
const localLoopCondition = ref(props.data.loopCondition || '');
const localMaxIterations = ref(props.data.maxIterations || 10);
const nameInput = ref<HTMLInputElement | null>(null);

const isSelected = computed(() => props.selected === true);
const statusColor = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '#ffa500';
    case 'completed': return '#00ff00';
    case 'failed': return '#ff0000';
    default: return '#888';
  }
});
const executionIcon = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '⏳';
    case 'completed': return '✅';
    case 'failed': return '❌';
    default: return '';
  }
});

watch(localLoopCondition, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ loopCondition: newVal });
  }
});

watch(localMaxIterations, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ maxIterations: newVal });
  }
});

function toggleExpand() {
  expanded.value = !expanded.value;
}

function startEditName() {
  editingName.value = true;
  nextTick(() => {
    nameInput.value?.focus();
  });
}

function finishEditName() {
  if (localName.value.trim() && props.data.onRename) {
    props.data.onRename(localName.value.trim());
  } else {
    localName.value = props.data.name;
  }
  editingName.value = false;
}

function handleDelete() {
  if (props.data.onDelete) {
    props.data.onDelete();
  } else {
    emit('delete');
  }
}
</script>

<style scoped>
.loop-node {
  border-color: #ff9800;
  position: relative;
  background: #2d2d44;
  border-radius: 8px;
  border: 2px solid #ff9800;
  min-width: 200px;
  max-width: 300px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.3);
  transition: box-shadow 0.3s ease;
}
.loop-node.selected {
  border-color: #ff6b6b;
  box-shadow: 0 0 0 2px rgba(255, 107, 107, 0.3);
}
.node-running {
  animation: pulse-running 1.5s ease-in-out infinite;
}
.node-completed {
  box-shadow: 0 0 12px rgba(76, 175, 80, 0.5);
}
.node-failed {
  box-shadow: 0 0 12px rgba(255, 0, 0, 0.5);
  animation: shake 0.4s ease-in-out;
}
@keyframes pulse-running {
  0%, 100% { box-shadow: 0 0 4px rgba(255, 165, 0, 0.3); }
  50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); }
}
@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-4px); }
  40% { transform: translateX(4px); }
  60% { transform: translateX(-4px); }
  80% { transform: translateX(4px); }
}
.delete-btn {
  position: absolute;
  top: -10px;
  right: -10px;
  width: 24px;
  height: 24px;
  background: #dc3545;
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  transition: all 0.2s;
}
.delete-btn:hover {
  background: #c82333;
  transform: scale(1.1);
}
.node-header {
  padding: 10px;
  background: #1e1e2e;
  border-radius: 6px 6px 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.node-icon {
  font-size: 20px;
}
.node-name {
  flex: 1;
  font-weight: bold;
  color: #eee;
  cursor: pointer;
}
.node-name-input {
  flex: 1;
  background: #1a1a2e;
  border: 1px solid #ff9800;
  color: #eee;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 14px;
  font-weight: bold;
}
.node-status {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.execution-icon {
  font-size: 14px;
  margin-left: 4px;
}
.node-expand {
  background: none;
  border: none;
  color: #eee;
  cursor: pointer;
  font-size: 12px;
}
.node-content {
  padding: 10px;
}
.field-label {
  color: #aaa;
  font-size: 12px;
  margin-bottom: 4px;
  display: block;
}
.node-content textarea {
  width: 100%;
  background: #1a1a2e;
  border: 1px solid #4a4a6a;
  color: #eee;
  border-radius: 4px;
  padding: 8px;
  font-family: monospace;
  resize: vertical;
  margin-bottom: 8px;
}
.number-input {
  width: 100%;
  background: #1a1a2e;
  border: 1px solid #4a4a6a;
  color: #eee;
  border-radius: 4px;
  padding: 8px;
  font-size: 14px;
  margin-bottom: 8px;
}
.progress-bar {
  width: 100%;
  height: 20px;
  background: #1a1a2e;
  border: 1px solid #4a4a6a;
  border-radius: 4px;
  margin-top: 8px;
  position: relative;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: #ff9800;
  transition: width 0.3s ease;
}
.progress-text {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #eee;
  font-size: 12px;
  font-weight: bold;
}
.node-result {
  margin-top: 10px;
  padding: 8px;
  background: #1a1a2e;
  border-radius: 4px;
  font-size: 12px;
  word-break: break-word;
}
.handle-body {
  background: #ff9800 !important;
  width: 12px !important;
  height: 12px !important;
}
.handle-exit {
  background: #4caf50 !important;
  width: 12px !important;
  height: 12px !important;
}
</style>
