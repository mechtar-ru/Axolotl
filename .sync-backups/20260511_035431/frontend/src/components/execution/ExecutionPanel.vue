<template>
  <div v-if="visible" class="execution-panel">
    <div class="execution-panel__header">
      <span>Schema Execution</span>
      <div class="execution-panel__buttons">
        <button class="stop-btn" @click="stopExecution" :disabled="!isExecuting">Stop</button>
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
      <div>Nodes: {{ completedNodes }}/{{ totalNodes }}</div>
      <div>Speed: {{ nodesPerSecond.toFixed(1) }} n/s</div>
      <div v-if="completedNodes > 0">Avg: {{ avgNodeTime }}ms</div>
    </div>

    <div class="execution-panel__tokens">
      <span v-if="(totalTokens ?? 0) > 0">{{ (totalTokens ?? 0).toLocaleString() }} tokens</span>
      <span v-if="(estimatedCost ?? 0) > 0" class="cost">~${{ (estimatedCost ?? 0).toFixed(4) }}</span>
    </div>

    <!-- Tabs: Logs / Trajectory -->
    <div class="panel-tabs">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'logs' }"
        @click="activeTab = 'logs'"
      >
        📋 Logs
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'trajectory' }"
        @click="activeTab = 'trajectory'"
      >
        🎯 Trajectory
        <span v-if="trajectoryStats.toolCalls > 0" class="tab-badge">
          {{ trajectoryStats.totalIterations }}·{{ trajectoryStats.toolCalls }}
        </span>
      </button>
    </div>

    <!-- Waves section -->
    <div v-if="waves.length > 0" class="execution-panel__waves">
      <div class="waves-header">
        <span>Waves</span>
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
          <span class="wave-number">Wave {{ i + 1 }}</span>
          <span class="wave-status">{{ waveStatusIcon(wave.status) }} {{ waveStatusLabel(wave.status) }}</span>
          <span class="wave-nodes">{{ waveNodeNames(wave.nodeIds) }}</span>
        </div>
      </div>
    </div>

    <!-- Logs Tab -->
    <div v-if="activeTab === 'logs'" class="execution-panel__logs" ref="logsContainer">
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

    <!-- Trajectory Tab -->
    <div v-if="activeTab === 'trajectory'" class="execution-panel__trajectory" ref="trajectoryContainer">
      <div v-if="trajectory.length === 0" class="trajectory-empty">
        Trajectory will appear here when an agent with tools executes
      </div>
      <div v-else class="trajectory-list">
        <div
          v-for="(iter, i) in trajectory"
          :key="i"
          class="trajectory-iteration"
        >
          <div class="iter-header">
            <span class="iter-number">Iteration {{ iter.iteration }}</span>
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
          <span>📊 {{ trajectoryStats.totalIterations }} iterations</span>
          <span>🔧 {{ trajectoryStats.toolCalls }} tools</span>
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
  nodeNameMap?: Record<string, string>;
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
  return { pending: 'pending', running: 'running', completed: 'done' }[status] || status;
}

function waveNodeNames(nodeIds: string[]): string {
  const map = props.nodeNameMap || {};
  const names = nodeIds.map(id => map[id] || id.slice(0, 8));
  return names.join(', ');
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
  width: 100%;
  height: 100%;
  background: rgba(30, 30, 46, 0.95);
  color: #eee;
  border-radius: 0;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
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

.execution-panel__tokens {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #888;
}
.execution-panel__tokens .cost {
  color: #51cf66;
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
  color: #888;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn.active {
  background: rgba(108, 99, 255, 0.3);
  color: #b8b0ff;
}

.tab-btn:hover:not(.active) {
  background: rgba(255, 255, 255, 0.05);
  color: #aaa;
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
  font-size: 12px;
  color: #aaa;
  margin-bottom: 6px;
}

.waves-count {
  background: rgba(108, 99, 255, 0.2);
  color: #b8b0ff;
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
  color: #ddd;
  font-weight: 600;
}

.wave-status {
  color: #888;
  margin-left: auto;
}

.wave-nodes {
  color: #666;
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
  color: #666;
  font-size: 12px;
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
  font-size: 11px;
  color: #aaa;
  margin-bottom: 4px;
}

.iter-number {
  font-weight: 600;
  color: #b8b0ff;
}

.iter-duration {
  margin-left: auto;
  color: #666;
}

.iter-tools {
  color: #888;
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
  color: #ddd;
}

.tool-duration {
  margin-left: auto;
  color: #666;
}

.tool-result {
  color: #51cf66;
}

.tool-result.tool-error {
  color: #ff6b6b;
}

.trajectory-summary {
  display: flex;
  justify-content: space-between;
  padding: 8px;
  font-size: 11px;
  color: #aaa;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  margin-top: 8px;
}
</style>