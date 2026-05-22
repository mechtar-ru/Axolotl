<template>
  <div class="condition-wrapper">
    <button
      v-if="isSelected"
      class="delete-btn"
      @click.stop="handleDelete"
      title="Delete node"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
    <div class="condition-diamond" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }">
      <Handle type="target" :position="Position.Top" />

      <div class="diamond-inner">
        <div class="diamond-content">
          <div class="node-header">
            <span class="node-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><line x1="12" y1="2" x2="12" y2="22"/><path d="M3 8c1.5 0 3-1 3-3"/><path d="M21 8c-1.5 0-3-1-3-3"/><line x1="3" y1="12" x2="21" y2="12"/></svg>
            </span>
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
            <span class="execution-icon">
              <svg v-if="props.data.executionStatus === 'running'" class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10" stroke-dasharray="31.4 31.4" stroke-linecap="round"/></svg>
              <svg v-else-if="props.data.executionStatus === 'completed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
              <svg v-else-if="props.data.executionStatus === 'failed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </span>
            <button class="node-expand" @click="toggleExpand">
              <svg :class="['chevron', { expanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg>
            </button>
            <span v-if="!expanded && localCondition" class="node-badge">{{ truncate(localCondition, 25) }}</span>
          </div>

          <div v-if="expanded" class="node-content">
            <textarea
              v-model="localCondition"
              placeholder="Condition: data.value > 10"
              rows="3"
              @mousedown.stop
              @mouseup.stop
            />
            <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
              <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
              <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
            </div>
            <div v-if="props.data.result !== undefined && props.data.result !== null" class="node-result">
              <strong>Result:</strong> {{ props.data.result }}
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

function truncate(str: string, len: number): string {
  return str.length > len ? str.substring(0, len) + '...' : str;
}

const isSelected = computed(() => props.selected === true);
const statusColor = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '#ffa500';
    case 'completed': return '#00ff00';
    case 'failed': return '#ff0000';
    default: return '#888';
  }
});
const executionIcon = computed(() => '');

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
@import './node-base.css';

.condition-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.condition-diamond {
  background: var(--bg-card);
  border: 2px solid #2196f3;
  border-radius: var(--radius-sm);
  min-width: 200px;
  max-width: 300px;
  box-shadow: var(--shadow-sm);
  position: relative;
}
.condition-diamond.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}
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
  color: var(--success);
}
.handle-label-false {
  right: -18px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--error);
}
.handle-true {
  background: var(--success) !important;
  width: 12px !important;
  height: 12px !important;
}
.handle-false {
  background: var(--error) !important;
  width: 12px !important;
  height: 12px !important;
}
.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
