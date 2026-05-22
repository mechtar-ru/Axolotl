<template>
  <div class="node filewrite-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <Handle type="target" :position="Position.Top" />
    
    <div class="node-header" @click="expanded = !expanded">
      <span class="node-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M16 3h5v5"/><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><line x1="8" y1="10" x2="16" y2="10"/><line x1="8" y1="14" x2="14" y2="14"/></svg></span>
      <span v-if="!editingName" class="node-title">{{ props.data.name || 'FileWrite' }}</span>
      <input v-else ref="nameInput" v-model="localName" class="name-input" @keydown.enter="finishEditName" @blur="finishEditName" />
      <span class="node-status">
        <svg v-if="props.data.executionStatus === 'running'" class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10" stroke-dasharray="31.4 31.4" stroke-linecap="round"/></svg>
        <svg v-else-if="props.data.executionStatus === 'completed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
        <svg v-else-if="props.data.executionStatus === 'failed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </span>
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
        <svg :class="['chevron', { expanded: resultExpanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg>
        {{ props.data.result.substring(0, 30) }}...
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
  border-color: #00bcd4;
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
.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
