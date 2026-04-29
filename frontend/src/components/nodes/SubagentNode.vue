<template>
  <div class="subagent-node" :class="{ completed: status === 'completed', running: status === 'running', failed: status === 'failed' }">
    <div class="node-header">
      <span class="node-icon">🤖</span>
      <span class="node-title">{{ data.name || 'Subagent' }}</span>
      <span class="node-badge">SUB</span>
    </div>
    <div class="node-body">
      <div v-if="targetSchemaName" class="target-schema">
        <span class="label">Цель:</span>
        <span class="value">{{ targetSchemaName }}</span>
      </div>
      <div v-else class="no-target">
        ⚠️ Не выбрана схема
      </div>
      <div v-if="data.inputMapping && Object.keys(data.inputMapping).length > 0" class="mapping-info">
        <span class="label">Входы:</span>
        <span class="value">{{ Object.keys(data.inputMapping).length }} маппингов</span>
      </div>
    </div>
    <div v-if="result" class="node-result">
      {{ truncateResult(result) }}
    </div>
    <div v-if="status === 'running'" class="running-indicator">
      <span class="pulse"></span>
      Выполняется...
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface NodeData {
  name?: string;
  subagentSchemaId?: string;
  inputMapping?: Record<string, string>;
  result?: string;
}

const props = defineProps<{
  data: NodeData & { isStreaming?: boolean };
  id: string;
  status?: 'idle' | 'running' | 'completed' | 'failed';
  result?: string;
}>();

const targetSchemaName = computed(() => {
  return props.data.subagentSchemaId ? `[${props.data.subagentSchemaId.substring(0, 8)}...]` : null;
});

function truncateResult(text: string): string {
  if (!text) return '';
  return text.length > 150 ? text.substring(0, 150) + '...' : text;
}
</script>

<style scoped>
.subagent-node {
  background: var(--bg-primary);
  border: 2px solid var(--accent);
  border-radius: var(--radius-md);
  min-width: 200px;
  max-width: 300px;
  font-family: var(--font-sans);
  box-shadow: var(--shadow-md);
}

.subagent-node.completed {
  border-color: var(--success);
}

.subagent-node.running {
  border-color: var(--warning);
}

.subagent-node.failed {
  border-color: var(--error);
}

.node-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px 12px;
  background: linear-gradient(135deg, var(--accent-light), rgba(108, 99, 255, 0.08));
  border-radius: 10px 10px 0 0;
  border-bottom: 1px solid rgba(108, 99, 255, 0.27);
}

.node-icon {
  font-size: 18px;
}

.node-title {
  flex: 1;
  font-weight: 600;
  color: var(--text-primary);
  font-size: var(--text-md);
}

.node-badge {
  background: var(--accent);
  color: var(--text-inverse);
  font-size: 10px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-weight: 600;
}

.node-body {
  padding: var(--space-3);
}

.target-schema {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.label {
  color: var(--text-muted);
  font-size: var(--text-sm);
}

.value {
  color: var(--accent);
  font-size: var(--text-sm);
  font-weight: 500;
}

.no-target {
  color: var(--warning);
  font-size: var(--text-sm);
  text-align: center;
  padding: var(--space-2);
  background: rgba(245, 158, 11, 0.07);
  border-radius: var(--radius-sm);
}

.mapping-info {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border-subtle);
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
