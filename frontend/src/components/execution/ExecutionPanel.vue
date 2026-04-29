<template>
  <div v-if="visible" class="execution-panel" role="region" aria-label="Выполнение схемы" aria-live="polite">
    <div class="sr-only" aria-live="polite" aria-atomic="true">{{ screenReaderAnnouncement }}</div>
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
      <div v-if="completedNodes > 0">Ср: {{ avgNodeTime }}мс/уз</div>
      <div v-if="estimatedRemaining > 0">Осталось: {{ formatTime(estimatedRemaining) }}</div>
    </div>

    <div class="execution-panel__tokens">
      <span v-if="(totalTokens ?? 0) > 0">{{ (totalTokens ?? 0).toLocaleString() }} токенов</span>
      <span v-if="(estimatedCost ?? 0) > 0" class="cost">~${{ (estimatedCost ?? 0).toFixed(4) }}</span>
    </div>

    <!-- Tabs: Logs / Trajectory -->
    <div class="panel-tabs">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'logs' }"
        @click="activeTab = 'logs'"
      >
        📋 Логи
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'trajectory' }"
        @click="activeTab = 'trajectory'"
      >
        🎯 Траектория
        <span v-if="trajectoryStats.toolCalls > 0" class="tab-badge">
          {{ trajectoryStats.totalIterations }}·{{ trajectoryStats.toolCalls }}
        </span>
      </button>
    </div>

    <!-- Waves section -->
    <div v-if="waves.length > 0" class="execution-panel__waves">
      <div class="waves-header">
        <span>🌊 Волны</span>
        <span class="waves-count">{{ waves.length }}</span>
      </div>
      <div class="waves-list">
        <div
          v-for="(wave, i) in waves"
          :key="i"
          class="wave-item"
          :class="'wave-' + wave.status"
          @click="$emit('highlight-wave', wave.nodeIds)"
        >
          <span class="wave-number">Волна {{ i + 1 }}</span>
          <span class="wave-status">{{ waveStatusIcon(wave.status) }} {{ waveStatusLabel(wave.status) }}</span>
          <span class="wave-nodes">{{ wave.nodeIds.length }} узлов</span>
        </div>
      </div>
    </div>

    <!-- Logs Tab -->
    <div v-if="activeTab === 'logs'" class="execution-panel__logs" ref="logsContainer">
      <div
        v-for="(entry, index) in logs"
        :key="index"
        class="execution-panel__log-entry"
        :class="{ 'log-error': entry.level === 'error', 'log-success': entry.level === 'success', 'log-warning': entry.level === 'warning', 'log-info': entry.level === 'info' }"
        @click="onLogClick(entry)"
      >
        <span class="log-time">{{ formatLogTime(entry.timestamp) }}</span>
        <span class="log-message">{{ entry.message }}</span>
      </div>
    </div>

    <!-- Trajectory Tab -->
    <div v-if="activeTab === 'trajectory'" class="execution-panel__trajectory" ref="trajectoryContainer">
      <div v-if="trajectory.length === 0" class="trajectory-empty">
        Траектория будет отображаться здесь при выполнении агента с инструментами
      </div>
      <div v-else class="trajectory-list">
        <div
          v-for="(iter, i) in trajectory"
          :key="i"
          class="trajectory-iteration"
        >
          <div class="iter-header">
            <span class="iter-number">Итерация {{ iter.iteration }}</span>
            <span class="iter-duration">{{ iter.durationMs }}ms</span>
            <span v-if="iter.toolCalls > 0" class="iter-tools">🔧 {{ iter.toolCalls }}</span>
          </div>
          <div v-for="tc in iter.toolCallsDetail" :key="tc.toolName" class="iter-tool">
            <span class="tool-icon">🔧</span>
            <span class="tool-name">{{ tc.toolName }}</span>
            <span class="tool-duration">{{ tc.durationMs }}ms</span>
            <span class="tool-result" :class="{ 'tool-error': !tc.success }">
              {{ tc.success ? '✓' : '✗' }}
            </span>
          </div>
        </div>
        <div v-if="trajectoryStats.totalIterations > 0" class="trajectory-summary">
          <span>📊 {{ trajectoryStats.totalIterations }} итераций</span>
          <span>🔧 {{ trajectoryStats.toolCalls }} инструментов</span>
          <span>⏱ {{ trajectoryStats.totalTimeMs }}ms</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onUnmounted, computed } from 'vue';

interface LogEntry {
  timestamp: number;
  message: string;
  level: 'info' | 'error' | 'success' | 'warning';
  nodeId?: string;
}

interface ToolCallDetail {
  toolName: string;
  args: string;
  durationMs: number;
  success: boolean;
  result: string;
}

interface TrajectoryIteration {
  iteration: number;
  durationMs: number;
  toolCalls: number;
  predictCalls: number;
  toolCallsDetail: ToolCallDetail[];
}

const props = defineProps<{
  visible: boolean;
  isExecuting: boolean;
  progress: number;
  elapsedSeconds: number;
  totalNodes: number;
  completedNodes: number;
  logs: LogEntry[];
  totalTokens?: number;
  estimatedCost?: number;
}>();
const emit = defineEmits<{
  (e: 'stop'): void;
  (e: 'close'): void;
  (e: 'highlight-node', nodeId: string): void;
  (e: 'highlight-wave', nodeIds: string[]): void;
  (e: 'add-tool-call', data: ToolCallDetail): void;
  (e: 'add-iteration', data: TrajectoryIteration): void;
}>();

const waves = ref<Array<{ nodeIds: string[]; status: 'pending' | 'running' | 'completed' }>>([]);
const logsContainer = ref<HTMLElement>();
const trajectoryContainer = ref<HTMLElement>();
let autoScroll = true;

const activeTab = ref<'logs' | 'trajectory'>('logs');

const trajectory = ref<TrajectoryIteration[]>([]);
const trajectoryStats = ref({
  totalIterations: 0,
  toolCalls: 0,
  predictCalls: 0,
  totalTimeMs: 0,
  estimatedCost: 0
});

const nodesPerSecond = ref(0);
const avgNodeTime = computed(() => {
  if (props.completedNodes <= 0 || props.elapsedSeconds <= 0) return '—';
  return Math.round((props.elapsedSeconds * 1000) / props.completedNodes);
});
const estimatedRemaining = computed(() => {
  if (props.completedNodes <= 0 || props.elapsedSeconds <= 0 || props.totalNodes <= props.completedNodes) return 0;
  const rate = props.completedNodes / props.elapsedSeconds;
  if (rate <= 0) return 0;
  const remaining = props.totalNodes - props.completedNodes;
  return Math.round(remaining / rate);
});

const screenReaderAnnouncement = computed(() => {
  if (props.isExecuting) {
    return `Выполнение: ${Math.round(props.progress)}%. Обработано узлов: ${props.completedNodes} из ${props.totalNodes}.`;
  }
  return '';
});

function addToolCall(data: ToolCallDetail) {
  const currentIter = trajectory.value[trajectory.value.length - 1];
  if (currentIter) {
    currentIter.toolCallsDetail.push(data);
    currentIter.toolCalls++;
    trajectoryStats.value.toolCalls++;
  }
  emit('add-tool-call', data);
}

function addIteration(data: { iteration: number; durationMs: number; toolCalls: number; predictCalls: number }) {
  const iter: TrajectoryIteration = {
    iteration: data.iteration,
    durationMs: data.durationMs,
    toolCalls: data.toolCalls,
    predictCalls: data.predictCalls,
    toolCallsDetail: []
  };
  trajectory.value.push(iter);
  trajectoryStats.value.totalIterations = data.iteration;
  trajectoryStats.value.totalTimeMs += data.durationMs;
  emit('add-iteration', iter);
}

function addTrajectoryComplete(data: { totalIterations: number; totalTimeMs: number; totalToolCalls: number; totalPredictCalls: number; estimatedCost: number }) {
  trajectoryStats.value = {
    totalIterations: data.totalIterations,
    toolCalls: data.totalToolCalls,
    predictCalls: data.totalPredictCalls,
    totalTimeMs: data.totalTimeMs,
    estimatedCost: data.estimatedCost
  };
}

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

function waveStatusIcon(status: string): string {
  return { pending: '⏳', running: '🔄', completed: '✅' }[status] || '❓';
}

function waveStatusLabel(status: string): string {
  return { pending: 'ожидает', running: 'выполняется', completed: 'завершена' }[status] || status;
}

// Called from parent when wave update received via WebSocket
function updateWave(waveNumber: number, nodeIds: string[], status: 'pending' | 'running' | 'completed') {
  while (waves.value.length <= waveNumber) {
    waves.value.push({ nodeIds: [], status: 'pending' });
  }
  waves.value[waveNumber] = { nodeIds, status: status as 'pending' | 'running' | 'completed' };
}

defineExpose({ updateWave, addToolCall, addIteration, addTrajectoryComplete });

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
  background: var(--bg-secondary);
  color: var(--text-primary);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: var(--space-4);
  z-index: var(--z-panel);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  backdrop-filter: var(--backdrop);
  border: 1px solid var(--border-subtle);
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
  background: var(--danger);
  color: var(--text-inverse);
}

.stop-btn:hover:not(:disabled) {
  background: var(--danger-hover);
}

.stop-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.close-btn {
  background: var(--accent);
  color: var(--text-inverse);
}

.close-btn:hover {
  background: var(--accent-hover);
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
  background: var(--accent-gradient);
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
  color: var(--info);
  font-family: monospace;
}

.execution-panel__stats {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: var(--text-sm);
  color: rgba(79, 172, 254, 0.7);
}

.execution-panel__tokens {
  display: flex;
  justify-content: space-between;
  font-size: var(--text-sm);
  color: var(--text-muted);
}
.execution-panel__tokens .cost {
  color: var(--success);
}

/* Tabs */
.panel-tabs {
  display: flex;
  gap: 4px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
  padding: 4px;
}

.tab-btn {
  flex: 1;
  padding: 6px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn.active {
  background: var(--accent-light);
  color: var(--violet-light);
}

.tab-btn:hover:not(.active) {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-secondary);
}

.tab-badge {
  margin-left: 4px;
  padding: 1px 4px;
  border-radius: 8px;
  background: rgba(108, 99, 255, 0.5);
  font-size: 10px;
}

/* Waves */
.execution-panel__waves {
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 8px;
  background: rgba(0, 0, 0, 0.2);
}

.waves-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-1-5);
}

.waves-count {
  background: rgba(108, 99, 255, 0.2);
  color: var(--violet-light);
  padding: 2px 6px;
  border-radius: 10px;
  font-size: 10px;
}

.waves-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 150px;
  overflow-y: auto;
}

.wave-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.2s;
  background: rgba(255, 255, 255, 0.03);
}

.wave-item:hover {
  background: rgba(255, 255, 255, 0.08);
}

.wave-running {
  background: rgba(255, 165, 0, 0.15);
  animation: wave-pulse 1.5s ease-in-out infinite;
}

.wave-completed {
  background: rgba(76, 175, 80, 0.1);
}

@keyframes wave-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.wave-number {
  color: var(--text-primary);
  font-weight: 600;
}

.wave-status {
  color: var(--text-muted);
  margin-left: auto;
}

.wave-nodes {
  color: var(--text-muted);
  font-size: 10px;
}

/* Logs */
.execution-panel__logs {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  background: rgba(12, 14, 24, 0.95);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  max-height: 200px;
}

.execution-panel__log-entry {
  font-size: 12px;
  line-height: 1.4;
  margin-bottom: 6px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
  white-space: pre-wrap;
  word-break: break-word;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.execution-panel__log-entry::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 4px;
  flex-shrink: 0;
}

.log-error::before { background: var(--error); }
.log-success::before { background: var(--success); }
.log-warning::before { background: var(--warning); }
.log-info::before { background: var(--info); }
.log-error .log-message { color: var(--error); }
.log-success .log-message { color: var(--success); }
.log-warning .log-message { color: var(--warning); }
.log-info .log-message { color: var(--info); }
.default .log-message { color: var(--text-primary); }

.execution-panel__log-entry:hover {
  background: rgba(255, 255, 255, 0.05);
}

.log-error {
  color: var(--error);
  background: var(--error-light);
}

.log-success {
  color: var(--success);
  background: var(--success-light);
}

.log-warning {
  color: var(--warning);
  background: var(--warning-light);
}

.log-info {
  color: var(--info);
  background: var(--info-light);
}

.log-time {
  color: var(--text-muted);
  margin-right: 8px;
  font-family: monospace;
}

.log-message {
  flex: 1;
}

/* Trajectory */
.execution-panel__trajectory {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  background: rgba(12, 14, 24, 0.95);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  max-height: 200px;
}

.trajectory-empty {
  text-align: center;
  color: var(--text-muted);
  font-size: var(--text-sm);
  padding: 20px;
}

.trajectory-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.trajectory-iteration {
  border: 1px solid rgba(108, 99, 255, 0.2);
  border-radius: 8px;
  padding: 8px;
  background: rgba(0, 0, 0, 0.2);
}

.iter-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-xs);
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.iter-number {
  font-weight: 600;
  color: var(--violet-light);
}

.iter-duration {
  margin-left: auto;
  color: var(--text-muted);
}

.iter-tools {
  color: var(--text-muted);
}

.iter-tool {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  font-size: 10px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 4px;
  margin-top: 2px;
}

.tool-icon {
  font-size: 10px;
}

.tool-name {
  color: var(--text-primary);
}

.tool-duration {
  margin-left: auto;
  color: var(--text-muted);
}

.tool-result {
  color: var(--success);
}

.tool-result.tool-error {
  color: var(--error);
}

.trajectory-summary {
  display: flex;
  justify-content: space-between;
  padding: 8px;
  font-size: 11px;
  color: var(--text-secondary);
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  margin-top: 8px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>