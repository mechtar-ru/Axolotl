<template>
  <div class="node filewrite-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <Handle type="target" :position="Position.Top" />
    
    <div class="node-header" @click="expanded = !expanded">
      <span class="node-icon">📝</span>
      <span v-if="!editingName" class="node-title">{{ props.data.name || 'FileWrite' }}</span>
      <input v-else ref="nameInput" v-model="localName" class="name-input" @keydown.enter="finishEditName" @blur="finishEditName" />
      <span class="node-status">{{ executionIcon }}</span>
    </div>

    <template v-if="expanded">
      <div class="filewrite-config">
        <div class="config-row">
          <label class="config-label">Path:</label>
          <input v-model="filePath" class="config-input" placeholder="./src/app.js" @change="updateConfig" />
        </div>
        <div class="config-row">
          <label class="config-label">Format:</label>
          <select v-model="fileFormat" class="config-select" @change="updateConfig">
            <option value="text">Text</option>
            <option value="javascript">JavaScript</option>
            <option value="typescript">TypeScript</option>
            <option value="python">Python</option>
            <option value="json">JSON</option>
            <option value="markdown">Markdown</option>
            <option value="yaml">YAML</option>
            <option value="html">HTML</option>
            <option value="css">CSS</option>
          </select>
        </div>
        <div class="config-row">
          <label class="config-label">Mode:</label>
          <select v-model="writeMode" class="config-select" @change="updateConfig">
            <option value="overwrite">Overwrite</option>
            <option value="append">Append</option>
            <option value="create-dir">Create + Write</option>
          </select>
        </div>
      </div>
    </template>

    <template v-if="props.data.result && resultExpanded">
      <button class="result-toggle" @click="resultExpanded = !resultExpanded">
        {{ resultExpanded ? '▼' : '▶' }} {{ props.data.result.substring(0, 30) }}...
      </button>
      <div v-if="resultExpanded" class="node-result">
        <div>{{ props.data.result }}</div>
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
    filePath?: string;
    fileFormat?: string;
    writeMode?: 'overwrite' | 'append' | 'create-dir';
    status?: string;
    result?: string;
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
const localName = ref(props.data.name || 'FileWrite');
const nameInput = ref<HTMLInputElement | null>(null);
const expanded = ref(true);
const resultExpanded = ref(true);

const filePath = ref(props.data.filePath || './output/file.txt');
const fileFormat = ref(props.data.fileFormat || 'text');
const writeMode = ref(props.data.writeMode || 'overwrite');

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
  props.data.filePath = filePath.value;
  props.data.fileFormat = fileFormat.value;
  props.data.writeMode = writeMode.value;
  props.data.config!.filePath = filePath.value;
  props.data.config!.fileFormat = fileFormat.value;
  props.data.config!.writeMode = writeMode.value;
  if (props.data.onUpdate) {
    props.data.onUpdate({ filePath: filePath.value, fileFormat: fileFormat.value, writeMode: writeMode.value });
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

.filewrite-node {
  border-color: var(--node-filewrite);
}
.filewrite-node.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}

.filewrite-config {
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

.config-input, .config-select {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.node-result {
  margin-top: 6px;
  padding: 6px;
  background: rgba(0,0,0,0.3);
  border-radius: 4px;
  font-size: 11px;
  word-break: break-all;
}
</style>