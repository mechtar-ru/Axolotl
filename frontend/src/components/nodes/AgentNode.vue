<template>
  <div class="node agent-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" :style="{ position: 'relative' }">
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
      <span class="node-icon">🤖</span>
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
      <button v-if="expanded" class="prompt-editor-btn" @click="openFullEditor" title="Полный редактор (Ctrl+E)">
        ✏️
      </button>
    </div>
    
    <div v-if="expanded" class="node-content">
      <textarea
        v-model="localPrompt"
        placeholder="Введите промпт для агента..."
        rows="5"
        @mousedown.stop
        @mouseup.stop
      />
      <select
        v-model="localModel"
        class="model-select"
      >
        <option value="">По умолчанию</option>
        <optgroup v-for="group in modelGroups" :key="group.name" :label="group.name">
          <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </optgroup>
      </select>
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
      <template v-if="props.data.result">
        <button class="result-toggle" @click="resultExpanded = !resultExpanded">
          {{ resultExpanded ? '▼ Результат' : '▶ Результат' }}
        </button>
        <div v-if="resultExpanded" class="node-result">
          <div>{{ props.data.result }}</div>
        </div>
      </template>
      <div v-if="props.data.isStreaming" class="typing-indicator">
        <span class="typing-dot"></span>
        <span class="typing-dot"></span>
        <span class="typing-dot"></span>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}мс</div>
    </div>
    
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { settingsApi } from '../../services/api';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    userPrompt?: string;
    status?: string;
    result?: string;
    progress?: number;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    model?: string;
    nodeTimeMs?: number;
    isStreaming?: boolean;
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
    onDelete?: () => void;
    onOpenPromptEditor?: () => void;
  };
}>();

const emit = defineEmits<{
  (e: 'delete'): void;
}>();

const resultExpanded = ref(true);

const expanded = ref(false);
const editingName = ref(false);
const localName = ref(props.data.name);
const localPrompt = ref(props.data.userPrompt || '');
const localModel = ref(props.data.model || 'ollama');
const nameInput = ref<HTMLInputElement | null>(null);
const providerOptions = ref<{ value: string; label: string; group: string }[]>([]);

onMounted(async () => {
  try {
    const providers = await settingsApi.getProviders();
    const opts: { value: string; label: string; group: string }[] = [];
    for (const p of providers) {
      const group = p.name.charAt(0).toUpperCase() + p.name.slice(1);
      if (p.models.length > 0) {
        for (const model of p.models) {
          opts.push({ value: model, label: model, group });
        }
      } else {
        opts.push({ value: p.name, label: `${group} (по умолчанию)`, group });
      }
    }
    providerOptions.value = opts;
  } catch (e) {
    providerOptions.value = [
      { value: 'ollama', label: 'Ollama (local)', group: 'Ollama' },
      { value: 'openai', label: 'OpenAI (GPT-4)', group: 'OpenAI' },
      { value: 'anthropic', label: 'Anthropic (Claude)', group: 'Anthropic' },
      { value: 'deepseek', label: 'DeepSeek', group: 'DeepSeek' },
    ];
  }
});

const isSelected = computed(() => props.selected === true);

const modelGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {};
  for (const opt of providerOptions.value) {
    const g = groups[opt.group];
    if (!g) groups[opt.group] = [opt];
    else g.push(opt);
  }
  return Object.entries(groups).map(([name, options]) => ({ name, options }));
});
const statusColor = computed(() => {
  switch (props.data.status) {
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

watch(localPrompt, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ userPrompt: newVal });
  }
});

watch(localModel, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ model: newVal });
  }
});

function toggleExpand() {
  expanded.value = !expanded.value;
}

function openFullEditor() {
  if (props.data.onOpenPromptEditor) {
    props.data.onOpenPromptEditor();
  }
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

.agent-node {
  border-color: var(--node-agent);
}
.agent-node.selected {
  border-color: #ff6b6b;
  box-shadow: 0 0 0 2px rgba(255, 107, 107, 0.3);
}
.prompt-editor-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  padding: 0 2px;
}
.prompt-editor-btn:hover {
  filter: brightness(1.3);
}
</style>
