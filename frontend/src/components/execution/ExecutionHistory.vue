<template>
  <div v-if="visible" class="history-panel">
    <div class="history-header">
      <span>История выполнений</span>
      <button class="close-btn" @click="$emit('close')">✕</button>
    </div>
    <div class="history-list">
      <div
        v-for="record in history"
        :key="record.id"
        class="history-item"
        :class="{ selected: selectedId === record.id }"
        @click="selectRecord(record)"
      >
        <div class="history-item-header">
          <span class="history-status" :class="record.status">{{ statusIcon(record.status) }}</span>
          <span class="history-time">{{ formatTime(record.totalTimeMs) }}</span>
        </div>
        <div class="history-item-details">
          <span>{{ record.completedNodes }}/{{ record.totalNodes }} узлов</span>
          <span class="history-date">{{ formatDate(record.startTime) }}</span>
        </div>
        <div v-if="selectedId === record.id && record.nodeResults" class="history-nodes">
          <div v-for="(nr, key) in record.nodeResults" :key="key" class="history-node">
            <span class="node-name">{{ nr.nodeName }}</span>
            <span class="node-status" :class="nr.status">{{ nr.status }}</span>
            <div v-if="nr.result" class="node-result-preview">{{ truncate(nr.result, 100) }}</div>
          </div>
        </div>
      </div>
      <div v-if="history.length === 0" class="history-empty">
        Нет выполнений
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { historyApi, type ExecutionRecord } from '../../services/api';

const props = defineProps<{
  visible: boolean;
  schemaId: string;
}>();

defineEmits<{
  (e: 'close'): void;
}>();

const history = ref<ExecutionRecord[]>([]);
const selectedId = ref<string | null>(null);

watch(() => props.visible, async (v) => {
  if (v && props.schemaId) {
    try {
      history.value = await historyApi.getSchemaHistory(props.schemaId);
    } catch (e) {
      console.error('Failed to load history:', e);
    }
  }
});

function selectRecord(record: ExecutionRecord) {
  selectedId.value = selectedId.value === record.id ? null : record.id;
}

function statusIcon(status: string) {
  return status === 'completed' ? '✅' : status === 'cancelled' ? '⚠️' : '❌';
}

function formatTime(ms: number): string {
  if (ms < 1000) return ms + 'мс';
  return (ms / 1000).toFixed(1) + 'с';
}

function formatDate(ts: number): string {
  return new Date(ts).toLocaleString('ru-RU', {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    day: '2-digit', month: '2-digit',
  });
}

function truncate(str: string, len: number): string {
  return str.length > len ? str.substring(0, len) + '...' : str;
}
</script>

<style scoped>
.history-panel {
  position: fixed;
  bottom: 20px;
  right: 20px;
  width: 380px;
  max-height: 500px;
  background: rgba(30, 30, 46, 0.95);
  color: var(--text-primary);
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  font-weight: 600;
  font-size: 14px;
}
.close-btn {
  background: rgba(255,255,255,0.1);
  border: none;
  color: var(--text-primary);
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
}
.history-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.history-item {
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.2s;
}
.history-item:hover {
  background: rgba(255,255,255,0.05);
}
.history-item.selected {
  background: rgba(108, 99, 255, 0.1);
  border: 1px solid rgba(108, 99, 255, 0.3);
}
.history-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.history-time {
  font-weight: 600;
  color: var(--info);
}
.history-item-details {
  display: flex;
  justify-content: space-between;
  font-size: var(--text-sm);
  color: var(--text-muted);
  margin-top: var(--space-1);
}
.history-date {
  font-size: 11px;
}
.history-nodes {
  margin-top: 8px;
  padding: 8px;
  background: rgba(0,0,0,0.2);
  border-radius: 6px;
}
.history-node {
  padding: 4px 0;
  border-bottom: 1px solid rgba(255,255,255,0.05);
  font-size: 12px;
}
.history-node:last-child {
  border-bottom: none;
}
.node-name {
  font-weight: 500;
  margin-right: 8px;
}
.node-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}
.node-status.completed {
  color: var(--success);
}
.node-status.failed {
  color: var(--error);
}
.node-result-preview {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-secondary);
  word-break: break-word;
}
.history-empty {
  text-align: center;
  color: var(--text-muted);
  padding: var(--space-6);
}
</style>
