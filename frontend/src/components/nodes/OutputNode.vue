<template>
  <div class="node output-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button
      v-if="isSelected"
      class="delete-btn"
      @click.stop="handleDelete"
      title="Delete node"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><line x1="17" y1="7" x2="17" y2="7"/><polyline points="5 12 5 19 19 19 19 12"/><line x1="12" y1="2" x2="12" y2="10"/><polyline points="9 7 12 10 15 7"/></svg>
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
      <button class="node-expand" @click="expanded = !expanded">
        <svg :class="['chevron', { expanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg>
      </button>
    </div>
    <div v-if="expanded" class="node-content">
      <div class="output-config">
        <label class="config-label">Output type:</label>
        <select v-model="outputType" class="config-select" @change="updateConfig">
          <option value="log">Log (panel)</option>
          <option value="file">File</option>
          <option value="memory">Memory (MemPalace)</option>
        </select>
      </div>
      <template v-if="outputType === 'file'">
        <div class="output-config">
          <label class="config-label">Path:</label>
          <input
            v-model="filePath"
            class="config-input"
            placeholder="./output/result.md"
            @change="updateConfig"
          />
        </div>
        <div class="output-config">
          <label class="config-label">Format:</label>
          <select v-model="fileFormat" class="config-select" @change="updateConfig">
            <option value="text">Text</option>
            <option value="markdown">Markdown</option>
            <option value="json">JSON</option>
          </select>
        </div>
      </template>
      <template v-if="outputType === 'memory'">
        <div class="output-config">
          <label class="config-label">Wing:</label>
          <input
            v-model="memoryWing"
            class="config-input"
            placeholder="axolotl"
            @change="updateConfig"
          />
        </div>
        <div class="output-config">
          <label class="config-label">Room:</label>
          <input
            v-model="memoryRoom"
            class="config-input"
            placeholder="agent-results"
            @change="updateConfig"
          />
        </div>
      </template>
      <template v-if="props.data.result">
        <button class="result-toggle" @click="resultExpanded = !resultExpanded">
          <svg :class="['chevron', { expanded: resultExpanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg> Result
        </button>
        <div v-if="resultExpanded" class="node-result">
          <span v-if="outputType === 'file' && props.data.executionStatus === 'completed'" class="file-saved">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg> {{ props.data.result }}
          </span>
          <span v-else class="result-text">{{ props.data.result }}</span>
        </div>
      </template>
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> {{ props.data.nodeTimeMs }}ms
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
    nodeTimeMs?: number;
    outputType?: 'log' | 'file' | 'memory';
    filePath?: string;
    fileFormat?: 'text' | 'json' | 'markdown';
    config?: Record<string, any>;
  };
}>();

const emit = defineEmits<{
  (e: 'delete'): void;
}>();

const editingName = ref(false);
const localName = ref(props.data.name);
const nameInput = ref<HTMLInputElement | null>(null);
const expanded = ref(true);
const resultExpanded = ref(true);

const outputType = ref<'log' | 'file' | 'memory'>(props.data.outputType || 'log');
const filePath = ref(props.data.filePath || './output/result.md');
const fileFormat = ref<'text' | 'json' | 'markdown'>(props.data.fileFormat || 'markdown');
const memoryWing = ref((props.data.config?.memoryWing as string) || 'axolotl');
const memoryRoom = ref((props.data.config?.memoryRoom as string) || 'agent-results');

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

function updateConfig() {
  if (!props.data.config) {
    (props.data as any).config = {};
  }
  props.data.config!.outputType = outputType.value;
  props.data.config!.filePath = filePath.value;
  props.data.config!.fileFormat = fileFormat.value;
  props.data.config!.memoryWing = memoryWing.value;
  props.data.config!.memoryRoom = memoryRoom.value;
  props.data.outputType = outputType.value;
  props.data.filePath = filePath.value;
  props.data.fileFormat = fileFormat.value;
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

.output-node {
  border-color: #ff9800;
}
.output-node.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}
.output-config {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.config-label {
  font-size: 11px;
  color: var(--text-secondary);
  min-width: 55px;
}
.config-select, .config-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 4px;
  padding: 3px 6px;
  font-size: 12px;
}
.file-saved {
  color: var(--success);
}
.result-text {
  color: white;
}
.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
