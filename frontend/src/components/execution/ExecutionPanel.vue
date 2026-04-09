<template>
  <div v-if="visible" class="execution-panel">
    <div class="execution-panel__header">
      <span>Выполнение схемы</span>
      <div class="execution-panel__buttons">
        <button class="stop-btn" @click="stopExecution" :disabled="!isExecuting">Остановить</button>
        <button class="close-btn" @click="closePanel">✕</button>
      </div>
    </div>

    <div class="execution-panel__progress">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: progress + '%' }"></div>
      </div>
      <span class="progress-text">{{ Math.round(progress) }}%</span>
    </div>

    <div class="execution-panel__timer">
      {{ formatTime(elapsedSeconds) }}
    </div>

    <div class="execution-panel__stats">
      <div>Узлов: {{ completedNodes }}/{{ totalNodes }}</div>
      <div>Скорость: {{ nodesPerSecond.toFixed(1) }} уз/с</div>
    </div>

    <div class="execution-panel__logs" ref="logsContainer">
      <div
        v-for="(entry, index) in logs"
        :key="index"
        class="execution-panel__log-entry"
        :class="{ 'log-error': entry.level === 'error', 'log-success': entry.level === 'success' }"
        @click="onLogClick(entry)"
      >
        <span class="log-time">{{ formatLogTime(entry.timestamp) }}</span>
        <span class="log-message">{{ entry.message }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue';

interface LogEntry {
  timestamp: number;
  message: string;
  level: 'info' | 'error' | 'success' | 'warning';
  nodeId?: string;
}

const props = defineProps<{
  visible: boolean;
  isExecuting: boolean;
  progress: number;
  elapsedSeconds: number;
  totalNodes: number;
  completedNodes: number;
  logs: LogEntry[];
}>();

const emit = defineEmits<{
  (e: 'stop'): void;
  (e: 'close'): void;
  (e: 'highlight-node', nodeId: string): void;
}>();

const logsContainer = ref<HTMLElement>();
let autoScroll = true;

const nodesPerSecond = ref(0);

watch(() => props.elapsedSeconds, (newVal) => {
  if (newVal > 0) {
    nodesPerSecond.value = props.completedNodes / newVal;
  }
});

watch(() => props.logs, async () => {
  if (autoScroll) {
    await nextTick();
    if (logsContainer.value) {
      logsContainer.value.scrollTop = logsContainer.value.scrollHeight;
    }
  }
}, { deep: true });

function stopExecution() {
  emit('stop');
}

function closePanel() {
  emit('close');
}

function onLogClick(entry: LogEntry) {
  if (entry.nodeId) {
    emit('highlight-node', entry.nodeId);
  }
}

function formatTime(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  return `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
}

function formatLogTime(timestamp: number): string {
  const date = new Date(timestamp);
  return date.toLocaleTimeString('ru-RU', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

onMounted(() => {
  if (logsContainer.value) {
    logsContainer.value.addEventListener('scroll', handleScroll);
  }
});

onUnmounted(() => {
  if (logsContainer.value) {
    logsContainer.value.removeEventListener('scroll', handleScroll);
  }
});

function handleScroll() {
  if (!logsContainer.value) return;
  const { scrollTop, scrollHeight, clientHeight } = logsContainer.value;
  autoScroll = scrollTop + clientHeight >= scrollHeight - 10;
}
</script>

<style scoped>
.execution-panel {
  position: fixed;
  top: 20px;
  right: 20px;
  width: 380px;
  max-height: 500px;
  background: rgba(30, 30, 46, 0.95);
  color: #eee;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
  padding: 16px;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  gap: 12px;
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.execution-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 14px;
}

.execution-panel__buttons {
  display: flex;
  gap: 8px;
}

.stop-btn, .close-btn {
  padding: 6px 12px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.stop-btn {
  background: #ff5e5e;
  color: #fff;
}

.stop-btn:hover:not(:disabled) {
  background: #ff4040;
}

.stop-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.close-btn {
  background: #6c63ff;
  color: #fff;
}

.close-btn:hover {
  background: #5a52d9;
}

.execution-panel__progress {
  display: flex;
  align-items: center;
  gap: 12px;
}

.progress-bar {
  flex: 1;
  height: 8px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #4f7cff, #6c63ff);
  border-radius: 4px;
  transition: width 0.3s ease;
  animation: progress-pulse 2s infinite;
}

@keyframes progress-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.progress-text {
  font-size: 12px;
  font-weight: 500;
  min-width: 35px;
}

.execution-panel__timer {
  font-size: 18px;
  font-weight: 700;
  text-align: center;
  color: #4f7cff;
  font-family: monospace;
}

.execution-panel__stats {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  color: #cbd5ff;
}

.execution-panel__logs {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  background: rgba(12, 14, 24, 0.95);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.execution-panel__log-entry {
  font-size: 12px;
  line-height: 1.4;
  color: #d8d8e8;
  margin-bottom: 6px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
  white-space: pre-wrap;
  word-break: break-word;
}

.execution-panel__log-entry:hover {
  background: rgba(255, 255, 255, 0.05);
}

.log-error {
  color: #ff6b6b;
  background: rgba(255, 107, 107, 0.1);
}

.log-success {
  color: #51cf66;
  background: rgba(81, 207, 102, 0.1);
}

.log-time {
  color: #888;
  margin-right: 8px;
  font-family: monospace;
}

.log-message {
  flex: 1;
}
</style>