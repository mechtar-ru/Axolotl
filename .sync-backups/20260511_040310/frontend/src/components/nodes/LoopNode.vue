<template>
  <div class="node loop-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button
      v-if="isSelected"
      class="delete-btn"
      @click.stop="handleDelete"
      title="Delete node"
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
      <span v-if="!expanded" class="node-badge">max {{ localMaxIterations }}</span>
    </div>

    <div v-if="expanded" class="node-content">
      <label class="field-label">Exit condition:</label>
      <textarea
        v-model="localLoopCondition"
        placeholder="iterations < 10"
        rows="2"
        @mousedown.stop
        @mouseup.stop
      />
      <label class="field-label">Max iterations:</label>
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
        <strong>Result:</strong> {{ props.data.result }}
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
@import './node-base.css';

.loop-node {
  border-color: var(--warning);
}
.loop-node.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}
.field-label {
  color: var(--text-secondary);
  font-size: 12px;
  margin-bottom: 4px;
  display: block;
}
.number-input {
  width: 100%;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 4px;
  padding: 8px;
  font-size: 14px;
  margin-bottom: 8px;
}
.handle-body {
  background: var(--warning) !important;
  width: 12px !important;
  height: 12px !important;
}
.handle-exit {
  background: var(--success) !important;
  width: 12px !important;
  height: 12px !important;
}
</style>
