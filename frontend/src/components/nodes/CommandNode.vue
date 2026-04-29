<template>
  <div class="node command-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <Handle type="target" :position="Position.Top" />
    
    <div class="node-header" @click="expanded = !expanded">
      <span class="node-icon">⚡</span>
      <span v-if="!editingName" class="node-title">{{ props.data.name || 'Command' }}</span>
      <input v-else ref="nameInput" v-model="localName" class="name-input" @keydown.enter="finishEditName" @blur="finishEditName" />
      <span class="node-status">{{ executionIcon }}</span>
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
        {{ resultExpanded ? '▼ Output' : '▶ Output' }}
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
    <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}мс</div>
    
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
    case 'running': return 'var(--warning)';
    case 'completed': return 'var(--success)';
    case 'failed': return 'var(--error)';
    default: return 'var(--text-muted)';
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
  border-color: var(--node-loop);
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
  color: var(--text-secondary);
}

.exit-code {
  margin-top: var(--space-1);
  padding: 2px 6px;
  border-radius: 3px;
  background: var(--error);
  color: var(--text-inverse);
  font-size: 10px;
}

.exit-code.success {
  background: var(--success);
}
</style>