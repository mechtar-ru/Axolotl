<template>
  <div class="condition-wrapper">
    <button
      v-if="isSelected"
      class="delete-btn"
      @click.stop="handleDelete"
      title="Удалить узел"
    >
      ✕
    </button>
    <div class="condition-diamond" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }">
      <Handle type="target" :position="Position.Top" />

      <div class="diamond-inner">
        <div class="diamond-content">
          <div class="node-header">
            <span class="node-icon">⚖️</span>
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
            <textarea
              v-model="localCondition"
              placeholder="Условие: data.value > 10"
              rows="3"
              @mousedown.stop
              @mouseup.stop
            />
            <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
              <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
              <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
            </div>
            <div v-if="props.data.result !== undefined && props.data.result !== null" class="node-result">
              <strong>Результат:</strong> {{ props.data.result }}
            </div>
          </div>
        </div>
      </div>

      <Handle id="true" type="source" :position="Position.Bottom" class="handle-true" />
      <Handle id="false" type="source" :position="Position.Right" class="handle-false" />
    </div>
    <span class="handle-label handle-label-true">T</span>
    <span class="handle-label handle-label-false">F</span>
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
    condition?: string;
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
const localCondition = ref(props.data.condition || '');
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

watch(localCondition, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ condition: newVal });
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
.condition-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.delete-btn {
  position: absolute;
  top: -16px;
  right: -16px;
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
.condition-diamond {
  background: #2d2d44;
  border: 2px solid #2196f3;
  border-radius: 8px;
  min-width: 200px;
  max-width: 300px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.3);
  position: relative;
}
.condition-diamond.selected {
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
.diamond-inner {
  padding: 0;
}
.diamond-content {
  padding: 0;
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
  border: 1px solid #2196f3;
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
  background: #2196f3;
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
/* Handle labels */
.handle-label {
  position: absolute;
  font-size: 11px;
  font-weight: bold;
  pointer-events: none;
}
.handle-label-true {
  bottom: -18px;
  left: 50%;
  transform: translateX(-50%);
  color: #4caf50;
}
.handle-label-false {
  right: -18px;
  top: 50%;
  transform: translateY(-50%);
  color: #f44336;
}
/* Handle colors */
.handle-true {
  background: #4caf50 !important;
  width: 12px !important;
  height: 12px !important;
}
.handle-false {
  background: #f44336 !important;
  width: 12px !important;
  height: 12px !important;
}
</style>
