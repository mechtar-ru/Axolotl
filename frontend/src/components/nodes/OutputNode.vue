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
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>
    <div v-if="expanded" class="node-content">
      <div class="output-config">
        <label class="config-label">Тип вывода:</label>
        <select v-model="outputType" class="config-select" @change="updateConfig">
          <option value="log">📋 Лог (панель)</option>
          <option value="file">💾 Файл</option>
        </select>
      </div>
      <template v-if="outputType === 'file'">
        <div class="output-config">
          <label class="config-label">Путь:</label>
          <input
            v-model="filePath"
            class="config-input"
            placeholder="./output/result.md"
            @change="updateConfig"
          />
        </div>
        <div class="output-config">
          <label class="config-label">Формат:</label>
          <select v-model="fileFormat" class="config-select" @change="updateConfig">
            <option value="text">Текст</option>
            <option value="markdown">Markdown</option>
            <option value="json">JSON</option>
          </select>
        </div>
      </template>
      <div v-if="props.data.result" class="node-result">
        <span v-if="outputType === 'file' && props.data.executionStatus === 'completed'" class="file-saved">✅ {{ props.data.result }}</span>
        <span v-else>{{ props.data.result }}</span>
      </div>
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}мс</div>
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
    outputType?: 'log' | 'file';
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

const outputType = ref<'log' | 'file'>(props.data.outputType || 'log');
const filePath = ref(props.data.filePath || './output/result.md');
const fileFormat = ref<'text' | 'json' | 'markdown'>(props.data.fileFormat || 'markdown');

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

function updateConfig() {
  if (!props.data.config) {
    (props.data as any).config = {};
  }
  props.data.config!.outputType = outputType.value;
  props.data.config!.filePath = filePath.value;
  props.data.config!.fileFormat = fileFormat.value;
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
</style>
