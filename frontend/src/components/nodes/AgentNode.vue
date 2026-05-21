<template>
  <div class="node agent-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" :style="{ position: 'relative' }">
    <button 
      v-if="isSelected" 
      class="delete-btn" 
      @click.stop="handleDelete"
      title="Delete node"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
    </button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><rect x="3" y="7" width="18" height="12" rx="2"/><circle cx="9" cy="12" r="1.5"/><circle cx="15" cy="12" r="1.5"/><path d="M12 2v5"/><path d="M9 5h6"/><line x1="12" y1="17" x2="12" y2="19"/></svg></span>
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
        <svg v-if="props.data.executionStatus === 'running'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" class="spin"><circle cx="12" cy="12" r="10"/></svg>
        <svg v-else-if="props.data.executionStatus === 'completed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
        <svg v-else-if="props.data.executionStatus === 'failed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
      </span>
      <button class="node-expand" @click="toggleExpand">
        {{ expanded ? '▼' : '▶' }}
      </button>
      <button v-if="expanded" class="prompt-editor-btn" @click="openFullEditor" title="Full editor (Ctrl+E)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg>
      </button>
      <span v-if="!expanded && localPromptPreview" class="node-badge">{{ truncate(localPromptPreview, 30) }}</span>
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
              <span class="tool-icon" v-html="tool.icon"></span>
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
          <button class="copy-result-btn" @click.stop="copyResult" title="Copy">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
        </div>
      </template>
      <div v-if="props.data.isStreaming" class="typing-indicator">
        <span class="typing-dot"></span>
        <span class="typing-dot"></span>
        <span class="typing-dot"></span>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12" style="vertical-align:middle;margin-right:2px"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> {{ props.data.nodeTimeMs }}ms</div>
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

// Start collapsed by default to match editor behavior and unit tests
const expanded = ref(false);
const editingName = ref(false);
const localName = ref(props.data.name);
const localPrompt = ref(props.data.userPrompt || '');
// Keep a local copy for display when collapsed
const localPromptPreview = computed(() => localPrompt.value || '');
const localModel = ref(props.data.model || 'ollama');
const nameInput = ref<HTMLInputElement | null>(null);
const providerOptions = ref<{ value: string; label: string; group: string }[]>([]);

const toolsExpanded = ref(false);
const localAgentType = ref(props.data.agentType || 'assistant');
const localEnabledTools = ref<string[]>(props.data.enabledTools || []);
const localMaxToolCalls = ref(props.data.maxToolCalls || 10);

const availableTools = [
  { id: 'file_read', name: 'Read File', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>', category: 'file', desc: 'Read file contents' },
  { id: 'file_write', name: 'Write File', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>', category: 'file', desc: 'Create and modify files' },
  { id: 'directory_read', name: 'List Files', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>', category: 'file', desc: 'Browse directory structure' },
  { id: 'grep', name: 'Search Files', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>', category: 'file', desc: 'Search text across files (regex)' },
  { id: 'git', name: 'Git', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>', category: 'exec', desc: 'Git operations (status, diff, log)' },
  { id: 'bash', name: 'Bash', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>', category: 'exec', desc: 'Execute shell commands' },
  { id: 'memory_read', name: 'Read Memory', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>', category: 'memory', desc: 'Read from agent memory' },
  { id: 'memory_write', name: 'Write Memory', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>', category: 'memory', desc: 'Save to agent memory' },
  { id: 'memory_search', name: 'Search Memory', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>', category: 'memory', desc: 'Search by keywords' },
  { id: 'web_search', name: 'Web Search', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>', category: 'web', desc: 'Search the web' },
  { id: 'web_fetch', name: 'Fetch URL', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>', category: 'web', desc: 'Download page contents' },
  { id: 'web_api', name: 'Web API', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>', category: 'web', desc: 'Call REST APIs (JSON)' },
  { id: 'graph_query', name: 'Code Graph', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>', category: 'graph', desc: 'Query Neo4j code graph' },
  { id: 'mcp_execute', name: 'MCP Tools', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M7 7h10v2l-2 3v6H9v-6L7 9V7z"/><line x1="12" y1="2" x2="12" y2="7"/></svg>', category: 'mcp', desc: 'Execute MCP tools' },
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
        const disabled = p.disabledModels ?? [];
        for (const model of p.models) {
          if (disabled.includes(model)) continue;
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
