<template>
  <div class="node source-node" :class="{ selected: isSelected }" style="position: relative">
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
      <span class="node-icon">📥</span>
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
      <span class="execution-icon">{{ executionIcon }}</span>
    </div>
    <div class="node-content">
      <textarea
        v-model="localSourceData"
        placeholder="Введите данные для источника..."
        rows="4"
      />
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
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
    sourceData?: string;
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

const editingName = ref(false);
const localName = ref(props.data.name);
const localSourceData = ref(props.data.sourceData || '');
const nameInput = ref<HTMLInputElement | null>(null);

const isSelected = computed(() => props.selected === true);
const executionIcon = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '⏳';
    case 'completed': return '✅';
    case 'failed': return '❌';
    default: return '';
  }
});

watch(localSourceData, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ sourceData: newVal });
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
.source-node {
  border-color: #4caf50;
  position: relative;
}
.source-node.selected {
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
.node-content {
  padding: 10px;
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
  background: #4caf50;
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
