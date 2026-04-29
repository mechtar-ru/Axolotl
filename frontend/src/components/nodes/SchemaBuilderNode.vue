<template>
  <div class="schemabuilder-node" :class="{ completed: status === 'completed', running: status === 'running', failed: status === 'failed' }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">🏗️</span>
      <span class="node-title">{{ data.name || 'SchemaBuilder' }}</span>
      <span class="node-badge">BUILD</span>
    </div>
    <div class="node-body">
      <div class="config-row">
        <label class="config-label">Model:</label>
        <select v-model="localModel" class="model-select">
          <option value="">Default</option>
          <optgroup v-for="group in modelGroups" :key="group.name" :label="group.name">
            <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </optgroup>
        </select>
      </div>
      <div class="config-row">
        <label>
          <input type="checkbox" :checked="generateMd" @change="toggleMd" />
          📄 Generate .md plan
        </label>
      </div>
      <div v-if="!result" class="config-hint">
        Feed agent result → generates new workflow schema
      </div>
    </div>
    <div v-if="result" class="node-result">
      {{ truncateResult(result) }}
    </div>
    <div v-if="status === 'running'" class="running-indicator">
      <span class="pulse"></span>
      Building schema...
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { settingsApi } from '../../services/api';

interface NodeData {
  name?: string;
  model?: string;
  config?: Record<string, any>;
  result?: string;
  onUpdate?: (updates: any) => void;
}

const props = defineProps<{
  data: NodeData & { isStreaming?: boolean };
  id: string;
  status?: 'idle' | 'running' | 'completed' | 'failed';
  result?: string;
}>();

const emit = defineEmits<{
  (e: 'update:data', data: NodeData): void;
}>();

const localModel = ref(props.data.model || '');
const providerOptions = ref<{ value: string; label: string; group: string }[]>([]);

onMounted(async () => {
  try {
    const providers = await settingsApi.getProviders();
    const opts: { value: string; label: string; group: string }[] = [];
    for (const p of providers) {
      const group = p.name.charAt(0).toUpperCase() + p.name.slice(1);
      if (p.models?.length > 0) {
        for (const model of p.models) {
          opts.push({ value: model, label: model, group });
        }
      } else {
        opts.push({ value: p.name, label: `${group} (default)`, group });
      }
    }
    providerOptions.value = opts;
  } catch {
    providerOptions.value = [
      { value: 'ollama', label: 'Ollama (local)', group: 'Ollama' },
      { value: 'qwen2.5:7b', label: 'Qwen 2.5 7B', group: 'Ollama' },
    ];
  }
});

const modelGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {};
  for (const opt of providerOptions.value) {
    const g = groups[opt.group];
    if (g) g.push(opt);
    else groups[opt.group] = [opt];
  }
  return Object.entries(groups).map(([name, options]) => ({ name, options }));
});

const generateMd = computed(() => props.data.config?.generateMd !== false);

watch(localModel, (newVal) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ model: newVal });
  } else {
    emit('update:data', { ...props.data, model: newVal } as NodeData);
  }
});

function toggleMd() {
  if (props.data.onUpdate) {
    props.data.onUpdate({ config: { ...props.data.config, generateMd: !generateMd.value } });
  } else {
    emit('update:data', {
      ...props.data,
      config: { ...props.data.config, generateMd: !generateMd.value },
    } as NodeData);
  }
}

function truncateResult(text: string): string {
  if (!text) return '';
  return text.length > 150 ? text.substring(0, 150) + '...' : text;
}
</script>

<style scoped>
.schemabuilder-node {
  background: var(--bg-primary);
  border: 2px solid var(--warning);
  border-radius: var(--radius-md);
  min-width: 200px;
  max-width: 300px;
  font-family: var(--font-sans);
  box-shadow: var(--shadow-md);
}

.schemabuilder-node.completed { border-color: var(--success); }
.schemabuilder-node.running { border-color: var(--warning); }
.schemabuilder-node.failed { border-color: var(--error); }

.node-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px 12px;
  background: linear-gradient(135deg, var(--warning-light), rgba(255, 165, 0, 0.1));
  border-radius: 10px 10px 0 0;
  border-bottom: 1px solid rgba(245, 158, 11, 0.27);
}

.node-icon { font-size: 18px; }

.node-title {
  flex: 1;
  font-weight: 600;
  color: var(--text-primary);
  font-size: var(--text-md);
}

.node-badge {
  background: var(--warning);
  color: var(--bg-primary);
  font-size: 10px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-weight: 600;
}

.node-body { padding: var(--space-3); }

.config-row { margin-bottom: var(--space-2); }

.config-label {
  display: block;
  color: var(--text-muted);
  font-size: var(--text-xs);
  margin-bottom: var(--space-1);
}

.model-select {
  width: 100%;
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  font-size: var(--text-sm);
  cursor: pointer;
}

.config-row label {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  color: var(--text-secondary);
  font-size: var(--text-sm);
  cursor: pointer;
}

.config-row input[type="checkbox"] { accent-color: var(--warning); }

.config-hint {
  color: var(--text-muted);
  font-size: var(--text-xs);
  text-align: center;
  padding: var(--space-1);
}

.node-result {
  padding: 8px 12px;
  background: var(--bg-secondary);
  border-top: 1px solid var(--border-subtle);
  font-size: var(--text-xs);
  color: var(--text-secondary);
  white-space: pre-wrap;
  max-height: 80px;
  overflow-y: auto;
}

.running-indicator {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px 12px;
  background: rgba(245, 158, 11, 0.07);
  color: var(--warning);
  font-size: var(--text-sm);
  border-radius: 0 0 10px 10px;
}

.pulse {
  width: 8px;
  height: 8px;
  background: var(--warning);
  border-radius: 50%;
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}
</style>
