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
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';

interface NodeData {
  name?: string;
  config?: Record<string, any>;
  result?: string;
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

const generateMd = computed(() => props.data.config?.generateMd !== false);

function toggleMd() {
  emit('update:data', {
    ...props.data,
    config: { ...props.data.config, generateMd: !generateMd.value },
  });
}

function truncateResult(text: string): string {
  if (!text) return '';
  return text.length > 150 ? text.substring(0, 150) + '...' : text;
}
</script>

<style scoped>
.schemabuilder-node {
  background: #1a1a2e;
  border: 2px solid #f59e0b;
  border-radius: 12px;
  min-width: 200px;
  max-width: 300px;
  font-family: 'Segoe UI', sans-serif;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.schemabuilder-node.completed {
  border-color: #10b981;
}

.schemabuilder-node.running {
  border-color: #f59e0b;
}

.schemabuilder-node.failed {
  border-color: #ef4444;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: linear-gradient(135deg, #f59e0b22, #f59e0b11);
  border-radius: 10px 10px 0 0;
  border-bottom: 1px solid #f59e0b44;
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
  background: #f59e0b;
  color: #1a1a2e;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.node-body {
  padding: 12px;
}

.config-row {
  margin-bottom: 8px;
}

.config-row label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #aaa;
  font-size: 12px;
  cursor: pointer;
}

.config-row input[type="checkbox"] {
  accent-color: #f59e0b;
}

.config-hint {
  color: #666;
  font-size: 11px;
  text-align: center;
  padding: 4px;
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
