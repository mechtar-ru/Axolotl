<template>
  <div class="node agent-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" :style="{ position: 'relative' }">
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
      <button v-if="expanded" class="prompt-editor-btn" @click="openFullEditor" title="Full editor (Ctrl+E)">
        ✏️
      </button>
      <span v-if="!expanded && localPrompt" class="node-badge">{{ truncate(localPrompt, 30) }}</span>
    </div>
    
    <div v-if="expanded" class="node-content">
      <textarea
        v-model="localPrompt"
        placeholder="Enter agent prompt..."
        rows="5"
        @mousedown.stop
        @mouseup.stop
      />
      <select
        v-model="localModel"
        class="model-select"
      >
        <option value="">Default</option>
        <optgroup v-for="group in modelGroups" :key="group.name" :label="group.name">
          <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </optgroup>
      </select>
      <div class="tools-section">
        <div class="tools-header" @click="toolsExpanded = !toolsExpanded">
          <span>{{ toolsExpanded ? '▼' : '▶' }}</span>
          <span>Tools ({{ localEnabledTools?.length || 0 }})</span>
        </div>
        <div v-if="toolsExpanded" class="tools-body">
          <select v-model="localAgentType" class="agent-type-select">
            <option value="assistant">Assistant</option>
            <option value="coder">Coder</option>
            <option value="researcher">Researcher</option>
            <option value="reviewer">Reviewer</option>
            <option value="project-analyzer">Project Analyzer</option>
            <option value="graph-engineer">Graph Engineer</option>
            <option value="mcp-agent">MCP Agent</option>
            <option value="custom">Custom</option>
          </select>
          <div class="tools-checklist">
            <label v-for="tool in availableTools" :key="tool.id" class="tool-checkbox" :title="tool.desc">
              <input
                type="checkbox"
                :checked="localEnabledTools?.includes(tool.id)"
                @change="toggleTool(tool.id)"
              />
              <span class="tool-icon">{{ tool.icon }}</span>
              <span class="tool-name">{{ tool.name }}</span>
            </label>
          </div>
          <div class="tool-limit">
            <label>Call limit:</label>
            <input
              type="number"
              v-model.number="localMaxToolCalls"
              min="1"
              max="100"
              class="tool-limit-input"
            />
          </div>
        </div>
      </div>
      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
      <template v-if="props.data.result">
        <button class="result-toggle" @click="resultExpanded = !resultExpanded">
          {{ resultExpanded ? '▼ Result' : '▶ Result' }}
        </button>
        <div v-if="resultExpanded" class="node-result">
          <div>{{ props.data.result }}</div>
          <button class="copy-result-btn" @click.stop="copyResult" title="Copy">📋</button>
        </div>
      </template>
      <div v-if="props.data.isStreaming" class="typing-indicator">
        <span class="typing-dot"></span>
        <span class="typing-dot"></span>
        <span class="typing-dot"></span>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}ms</div>
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
    agentType?: string;
    enabledTools?: string[];
    maxToolCalls?: number;
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

function copyResult() {
  if (props.data.result) {
    navigator.clipboard.writeText(props.data.result);
  }
}

const expanded = ref(true);
const editingName = ref(false);
const localName = ref(props.data.name);
const localPrompt = ref(props.data.userPrompt || '');
const localModel = ref(props.data.model || 'ollama');
const nameInput = ref<HTMLInputElement | null>(null);
const providerOptions = ref<{ value: string; label: string; group: string }[]>([]);

const toolsExpanded = ref(false);
const localAgentType = ref(props.data.agentType || 'assistant');
const localEnabledTools = ref<string[]>(props.data.enabledTools || []);
const localMaxToolCalls = ref(props.data.maxToolCalls || 10);

const availableTools = [
  { id: 'file_read', name: 'Read File', icon: '📄', category: 'file', desc: 'Read file contents' },
  { id: 'file_write', name: 'Write File', icon: '💾', category: 'file', desc: 'Create and modify files' },
  { id: 'directory_read', name: 'List Files', icon: '📁', category: 'file', desc: 'Browse directory structure' },
  { id: 'grep', name: 'Search Files', icon: '🔎', category: 'file', desc: 'Search text across files (regex)' },
  { id: 'git', name: 'Git', icon: '🔀', category: 'exec', desc: 'Git operations (status, diff, log)' },
  { id: 'bash', name: 'Bash', icon: '⌨️', category: 'exec', desc: 'Execute shell commands' },
  { id: 'memory_read', name: 'Read Memory', icon: '🧠', category: 'memory', desc: 'Read from agent memory' },
  { id: 'memory_write', name: 'Write Memory', icon: '💭', category: 'memory', desc: 'Save to agent memory' },
  { id: 'memory_search', name: 'Search Memory', icon: '🔍', category: 'memory', desc: 'Search by keywords' },
  { id: 'web_search', name: 'Web Search', icon: '🔍', category: 'web', desc: 'Search the web' },
  { id: 'web_fetch', name: 'Fetch URL', icon: '🌐', category: 'web', desc: 'Download page contents' },
  { id: 'web_api', name: 'Web API', icon: '🌍', category: 'web', desc: 'Call REST APIs (JSON)' },
  { id: 'graph_query', name: 'Code Graph', icon: '🔗', category: 'graph', desc: 'Query Neo4j code graph' },
  { id: 'mcp_execute', name: 'MCP Tools', icon: '🔌', category: 'mcp', desc: 'Execute MCP tools' },
];

const agentTypePresets: Record<string, { tools: string[], systemPrompt: string }> = {
  assistant: { tools: [], systemPrompt: 'You are a helpful assistant.' },
  coder: { tools: ['file_read', 'file_write', 'bash', 'grep'], systemPrompt: 'You are a programmer. Write clean code with tests. Use grep for searching.' },
  researcher: { tools: ['web_search', 'web_fetch', 'web_api', 'directory_read'], systemPrompt: 'You are a researcher. Gather information, analyze data from APIs.' },
  reviewer: { tools: ['file_read', 'directory_read', 'git', 'bash'], systemPrompt: 'You are a code reviewer. Analyze code, find bugs, check git diff.' },
  'project-analyzer': { tools: ['directory_read', 'file_read', 'grep', 'memory_write'], systemPrompt: 'You are a project analyzer. Analyze architecture, dependencies, patterns. Suggest improvements.' },
  'graph-engineer': { tools: ['directory_read', 'file_read', 'graph_query', 'memory_write'], systemPrompt: 'You are a code graph engineer. Use Neo4j to analyze project structure, find dependencies.' },
  'mcp-agent': { tools: ['mcp_execute', 'memory_read', 'memory_write'], systemPrompt: 'You are an MCP agent. Execute tools via MCP protocol. Coordinate work with external services.' },
};

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
        opts.push({ value: p.name, label: `${group} (default)`, group });
      }
    }
    providerOptions.value = opts;
  } catch (e) {
    providerOptions.value = [
      { value: 'ollama', label: 'Ollama (local)', group: 'Ollama' },
      { value: 'openai', label: 'OpenAI (GPT-4)', group: 'OpenAI' },
      { value: 'anthropic', label: 'Anthropic (Claude)', group: 'Anthropic' },
      { value: 'deepseek', label: 'DeepSeek', group: 'DeepSeek' },
      { value: 'zen', label: 'OpenCode Zen (big-pickle)', group: 'OpenCode Zen' },
      { value: 'big-pickle', label: 'Big Pickle (free)', group: 'OpenCode Zen' },
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

watch(localAgentType, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ agentType: newVal });
  }
  const preset = agentTypePresets[newVal];
  if (preset && (!props.data.enabledTools || props.data.enabledTools.length === 0)) {
    localEnabledTools.value = [...preset.tools];
    if (preset.systemPrompt && props.data.onUpdate) {
      props.data.onUpdate({ systemPrompt: preset.systemPrompt });
    }
  }
});

watch(localEnabledTools, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ enabledTools: newVal });
  }
}, { deep: true });

watch(localMaxToolCalls, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ maxToolCalls: newVal });
  }
});

function toggleTool(toolId: string) {
  const idx = localEnabledTools.value.indexOf(toolId);
  if (idx >= 0) {
    localEnabledTools.value.splice(idx, 1);
  } else {
    localEnabledTools.value.push(toolId);
  }
  if (props.data.onUpdate) {
    props.data.onUpdate({ enabledTools: [...localEnabledTools.value] });
  }
}

function toggleExpand() {
  expanded.value = !expanded.value;
}

function truncate(str: string, len: number): string {
  return str.length > len ? str.substring(0, len) + '...' : str;
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
.tools-section {
  margin-top: 8px;
  border: 1px solid var(--border-color, #333);
  border-radius: 4px;
  overflow: hidden;
}
.tools-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: var(--bg-secondary, #2a2a2a);
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
}
.tools-header:hover {
  background: var(--bg-hover, #333);
}
.tools-body {
  padding: 8px;
  background: var(--bg-primary, #1a1a1a);
}
.agent-type-select {
  width: 100%;
  padding: 4px 8px;
  margin-bottom: 8px;
  border: 1px solid var(--border-color, #444);
  border-radius: 4px;
  background: var(--bg-secondary, #2a2a2a);
  color: var(--text-color, #fff);
  font-size: 12px;
}
.tools-checklist {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}
.tool-checkbox {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 6px;
  border-radius: 4px;
  background: var(--bg-secondary, #2a2a2a);
  cursor: pointer;
  font-size: 11px;
}
.tool-checkbox:hover {
  background: var(--bg-hover, #333);
}
.tool-checkbox input {
  margin: 0;
}
.tool-icon {
  font-size: 12px;
}
.tool-name {
  color: var(--text-secondary, #aaa);
}
.tool-limit {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--text-secondary, #aaa);
}
.tool-limit-input {
  width: 60px;
  padding: 2px 6px;
  border: 1px solid var(--border-color, #444);
  border-radius: 4px;
  background: var(--bg-secondary, #2a2a2a);
  color: var(--text-color, #fff);
  font-size: 11px;
}
</style>
