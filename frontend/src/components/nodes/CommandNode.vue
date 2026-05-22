<template>
  <div class="node command-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <Handle type="target" :position="Position.Top" />
    
    <div class="node-header" @click="expanded = !expanded">
      <span class="node-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10"/></svg></span>
      <span v-if="!editingName" class="node-title">{{ props.data.name || 'Command' }}</span>
      <input v-else ref="nameInput" v-model="localName" class="name-input" @keydown.enter="finishEditName" @blur="finishEditName" />
      <span class="node-status">
        <svg v-if="props.data.executionStatus === 'running'" class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10" stroke-dasharray="31.4 31.4" stroke-linecap="round"/></svg>
        <svg v-else-if="props.data.executionStatus === 'completed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
        <svg v-else-if="props.data.executionStatus === 'failed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </span>
    </div>

    <template v-if="expanded">
      <div class="command-config">
        <div class="config-row">
          <label class="config-label">Command:</label>
          <input v-model="command" class="config-input" placeholder="npm run build" @change="updateConfig" />
        </div>
        <div class="config-row">
          <label class="config-label">Dir:</label>
          <input v-model="workingDir" class="config-input" placeholder="./my-project" @change="updateConfig" />
        </div>
        <div class="config-row">
          <label class="config-label">Timeout:</label>
          <input v-model.number="timeout" type="number" class="config-input small" placeholder="60" @change="updateConfig" />
          <span class="config-hint">sec</span>
        </div>
      </div>
    </template>

    <template v-if="props.data.result && resultExpanded">
      <button class="result-toggle" @click="resultExpanded = !resultExpanded">
        <svg :class="['chevron', { expanded: resultExpanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg>
        Output
      </button>
      <div v-if="resultExpanded" class="node-result">
        <pre class="result-output">{{ props.data.result }}</pre>
        <div v-if="props.data.exitCode !== undefined" class="exit-code" :class="{ success: props.data.exitCode === 0 }">
          Exit: {{ props.data.exitCode }}
        </div>
      </div>
    </template>
    
    <div v-if="props.data.executionStatus === 'running'" class="typing-indicator">
      <span class="typing-dot"></span>
      <span class="typing-dot"></span>
      <span class="typing-dot"></span>
    </div>
    <div v-if="props.data.nodeTimeMs" class="node-time">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
      {{ props.data.nodeTimeMs }}ms
    </div>
    
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue';
import { Handle, Position } from '@vue-flow/core';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name?: string;
    command?: string;
    workingDir?: string;
    timeout?: number;
    status?: string;
    result?: string;
    exitCode?: number;
    progress?: number;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    onRename?: (name: string) => void;
    onDelete?: () => void;
    onUpdate?: (updates: any) => void;
    nodeTimeMs?: number;
    config?: Record<string, any>;
  };
}>();

const emit = defineEmits<{
  (e: 'delete'): void;
}>();

const editingName = ref(false);
const localName = ref(props.data.name || 'Command');
const nameInput = ref<HTMLInputElement | null>(null);
const expanded = ref(true);
const resultExpanded = ref(true);

const command = ref(props.data.command || '');
const workingDir = ref(props.data.workingDir || '');
const timeout = ref(props.data.timeout || 60);

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
  editingName.value = false;
  if (localName.value !== props.data.name) {
    props.data.name = localName.value;
    if (props.data.onRename) props.data.onRename(localName.value);
  }
}

function updateConfig() {
  if (!props.data.config) {
    (props.data as any).config = {};
  }
  props.data.command = command.value;
  props.data.workingDir = workingDir.value;
  props.data.timeout = timeout.value;
  props.data.config!.command = command.value;
  props.data.config!.workingDir = workingDir.value;
  props.data.config!.timeout = timeout.value;
  if (props.data.onUpdate) {
    props.data.onUpdate({ command: command.value, workingDir: workingDir.value, timeout: timeout.value });
  }
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

.command-node {
  border-color: #ff5722;
}
.command-node.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}

.command-config {
  margin-top: 8px;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.config-label {
  font-size: 11px;
  color: var(--text-secondary);
  min-width: 40px;
}

.config-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.config-input.small {
  width: 60px;
  flex: none;
}

.config-hint {
  font-size: 10px;
  color: var(--text-muted);
}

.node-result {
  margin-top: 6px;
  padding: 6px;
  background: rgba(0,0,0,0.3);
  border-radius: 4px;
  font-size: 11px;
}

.result-output {
  max-height: 100px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: #aaa;
}

.exit-code {
  margin-top: 4px;
  padding: 2px 6px;
  border-radius: 3px;
  background: #f44336;
  color: white;
  font-size: 10px;
}

.exit-code.success {
  background: #4caf50;
}
.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
