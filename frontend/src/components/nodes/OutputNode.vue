<template>
  <div class="node output-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
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
      <span class="node-icon">📤</span>
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
    </div>
    <div v-if="props.data.result" class="node-content">
      <div class="node-result">{{ props.data.result }}</div>
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from 'vue';
import { Handle, Position } from '@vue-flow/core';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    result?: string;
    progress?: number;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    onRename?: (name: string) => void;
    onDelete?: () => void;
  };
}>();

const emit = defineEmits<{
  (e: 'delete'): void;
}>();

const editingName = ref(false);
const localName = ref(props.data.name);
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
.output-node {
  border-color: #ff9800;
  position: relative;
  background: #2d2d44;
  transition: box-shadow 0.3s ease;
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
.output-node.selected {
  border-color: #6c63ff;
  box-shadow: 0 0 0 2px rgba(108,99,255,0.3);
}
.node {
  background: #2d2d44;
  border-radius: 8px;
  border: 2px solid #4a4a6a;
  min-width: 200px;
  max-width: 300px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.3);
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
  border: 1px solid #6c63ff;
  color: #eee;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 14px;
  font-weight: bold;
}
.execution-icon {
  font-size: 14px;
  margin-left: 4px;
}
.node-status {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.node-content {
  padding: 10px;
}
.node-result {
  margin-top: 10px;
  padding: 8px;
  background: #1a1a2e;
  border-radius: 4px;
  font-size: 12px;
  word-break: break-word;
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
</style>
