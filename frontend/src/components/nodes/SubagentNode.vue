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
  background: #1a1a2e;
  border: 2px solid #6c63ff;
  border-radius: 12px;
  min-width: 200px;
  max-width: 300px;
  font-family: 'Segoe UI', sans-serif;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.subagent-node.completed {
  border-color: #10b981;
}

.subagent-node.running {
  border-color: #f59e0b;
}

.subagent-node.failed {
  border-color: #ef4444;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: linear-gradient(135deg, #6c63ff22, #6c63ff11);
  border-radius: 10px 10px 0 0;
  border-bottom: 1px solid #6c63ff44;
}

.node-icon {
  font-size: 18px;
}

.node-title {
  flex: 1;
  font-weight: 600;
  color: #eee;
  font-size: 14px;
}

.node-badge {
  background: #6c63ff;
  color: white;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.node-body {
  padding: 12px;
}

.target-schema {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.label {
  color: #888;
  font-size: 12px;
}

.value {
  color: #6c63ff;
  font-size: 12px;
  font-weight: 500;
}

.no-target {
  color: #f59e0b;
  font-size: 12px;
  text-align: center;
  padding: 8px;
  background: #f59e0b11;
  border-radius: 6px;
}

.mapping-info {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #ffffff11;
}

.node-result {
  padding: 8px 12px;
  background: #0f0f1a;
  border-top: 1px solid #ffffff11;
  font-size: 11px;
  color: #aaa;
  white-space: pre-wrap;
  max-height: 80px;
  overflow-y: auto;
}

.running-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f59e0b11;
  color: #f59e0b;
  font-size: 12px;
  border-radius: 0 0 10px 10px;
}

.pulse {
  width: 8px;
  height: 8px;
  background: #f59e0b;
  border-radius: 50%;
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}
</style>
