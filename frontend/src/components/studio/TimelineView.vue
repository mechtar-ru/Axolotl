<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useExecutionState } from '@/composables/useExecutionState'
import { schemaApi } from '@/services/api'
import type { ExecutionRun, NodeExecution } from '@/services/api'

const props = defineProps<{
  schemaId: string | null
}>()

const emit = defineEmits<{
  'select-block': [blockId: string]
}>()

const execState = useExecutionState()

// ── State ──

const runs = ref<ExecutionRun[]>([])
const loading = ref(false)
const loadingNodes = ref<Set<string>>(new Set())
const expandedRunId = ref<string | null>(null)
const nodeCache = ref<Map<string, NodeExecution[]>>(new Map())
const errorMsg = ref('')
const releasing = ref(false)

const PAGE_SIZE = 10
const visibleCount = ref(PAGE_SIZE)

// ── Derived ──

const filteredRuns = computed(() =>
  runs.value.filter(r => r.status !== 'resuming')
)

const visibleRuns = computed(() =>
  filteredRuns.value.slice(0, visibleCount.value)
)

const hasMore = computed(() =>
  visibleRuns.value.length < filteredRuns.value.length
)

const liveEvents = computed(() => execState?.stepEvents.value || [])

const hasLiveEvents = computed(() => liveEvents.value.length > 0)

// ── Data Fetching ──

async function loadRuns() {
  if (!props.schemaId) return
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await schemaApi.getRuns(props.schemaId)
    // Sort newest first
    data.sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())
    runs.value = data
  } catch (e: any) {
    errorMsg.value = 'Failed to load runs: ' + (e.message || e)
  } finally {
    loading.value = false
  }
}

async function loadNodes(runId: string) {
  if (nodeCache.value.has(runId)) return
  if (!props.schemaId) return
  loadingNodes.value.add(runId)
  try {
    const nodes = await schemaApi.getRunNodes(props.schemaId, runId)
    nodeCache.value.set(runId, nodes)
  } catch (e: any) {
    nodeCache.value.set(runId, [])
  } finally {
    loadingNodes.value.delete(runId)
  }
}

function toggleExpand(runId: string) {
  if (expandedRunId.value === runId) {
    expandedRunId.value = null
  } else {
    expandedRunId.value = runId
    loadNodes(runId)
  }
}

function loadMore() {
  visibleCount.value += PAGE_SIZE
}

async function onReleaseStale() {
  if (!props.schemaId) return
  releasing.value = true
  try {
    await schemaApi.cleanupStaleRuns(props.schemaId)
    await loadRuns()
  } catch (e: any) {
    errorMsg.value = 'Failed to release stale runs: ' + (e.message || e)
  } finally {
    releasing.value = false
  }
}

async function onReRun(run: ExecutionRun) {
  if (run.mode === 'PIPELINE') {
    emit('select-block', '__execute_pipeline__')
  } else {
    emit('select-block', '__execute__')
  }
}

// ── Formatting Helpers ──

function formatRelative(dateStr: string): string {
  const ms = Date.now() - new Date(dateStr).getTime()
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return `${sec}s ago`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}h ago`
  return new Date(dateStr).toLocaleDateString()
}

function formatDuration(run: ExecutionRun): string {
  if (!run.completedAt && run.status !== 'failed' && run.status !== 'paused') return ''
  const start = new Date(run.startedAt).getTime()
  const end = run.completedAt ? new Date(run.completedAt).getTime() : Date.now()
  const ms = end - start
  if (ms < 1000) return `${ms}ms`
  const sec = Math.floor(ms / 1000)
  return `${sec}s`
}

const statusColors: Record<string, string> = {
  completed: '#4caf50',
  failed: '#ef4444',
  paused: '#f59e0b',
  running: '#2196f3',
  resuming: '#9ca3af',
}

function statusColor(status: string): string {
  return statusColors[status] || '#6c63ff'
}

function nodeTypeIcon(type: string): string {
  const icons: Record<string, string> = {
    source: '📄',
    agent: '🤖',
    review: '👁',
    verifier: '✓',
    output: '📦',
  }
  return icons[type] || '●'
}

function tokensLabel(run: ExecutionRun): string {
  if (run.totalTokens === 0 && run.estimatedCost === 0) return '—'
  return `${run.totalTokens} tok`
}

function nodeOutputPreview(output: string | null, max = 200): string {
  if (!output) return ''
  const cleaned = output.replace(/```json\s*/g, '').trim()
  if (cleaned.length <= max) return cleaned
  return cleaned.slice(0, max) + '…'
}

const STATUS_LABELS: Record<string, string> = {
  completed: 'Completed',
  failed: 'Failed',
  paused: 'Paused',
  running: 'Running',
  resuming: 'Resuming',
}

// ── Watch for schema changes ──

watch(() => props.schemaId, (newId) => {
  if (newId) {
    expandedRunId.value = null
    nodeCache.value = new Map()
    visibleCount.value = PAGE_SIZE
    loadRuns()
  }
})

// ── Lifecycle ──

onMounted(() => {
  if (props.schemaId) loadRuns()
})
</script>

<template>
  <div class="timeline-view">
    <div class="timeline-header">
      <h2>Run History</h2>
      <div class="header-actions">
        <span class="run-count">{{ runs.length }} run{{ runs.length !== 1 ? 's' : '' }}</span>
        <button class="header-btn" :disabled="releasing" @click="onReleaseStale" title="Release stuck resuming runs">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
          </svg>
          {{ releasing ? 'Releasing…' : 'Release Stale' }}
        </button>
        <button class="header-btn" @click="loadRuns" title="Refresh">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="timeline-loading">
      <span class="spinner" /> Loading runs…
    </div>

    <!-- Error -->
    <div v-else-if="errorMsg" class="timeline-error">{{ errorMsg }}</div>

    <!-- Empty -->
    <div v-else-if="filteredRuns.length === 0" class="timeline-empty">
      <div class="empty-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="48" height="48">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
      </div>
      <p>No runs yet. Execute your schema to see results here.</p>
      <button class="run-cta-btn" @click="$emit('select-block', '__execute__')">Execute</button>
    </div>

    <!-- Run List -->
    <div v-else class="timeline-list">
      <div
        v-for="run in visibleRuns"
        :key="run.id"
        class="run-card"
        :class="{ expanded: expandedRunId === run.id }"
      >
        <!-- Run header (collapsed state) -->
        <div class="run-header" @click="toggleExpand(run.id)">
          <span class="run-status-dot" :style="{ background: statusColor(run.status) }" />
          <span class="run-status-label">{{ STATUS_LABELS[run.status] || run.status }}</span>
          <span class="run-mode-tag">{{ run.mode }}</span>
          <span class="run-time">{{ formatRelative(run.startedAt) }}</span>
          <span class="run-duration">{{ formatDuration(run) }}</span>
          <span class="run-tokens">{{ tokensLabel(run) }}</span>
          <svg
            class="chevron"
            :class="{ rotated: expandedRunId === run.id }"
            viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"
          >
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </div>

        <!-- Error pill -->
        <div v-if="run.error" class="run-error" :title="run.error">{{ run.error }}</div>

        <!-- ResumesFrom indicator -->
        <div v-if="run.resumesFrom" class="run-resumes-from">
          Continued from run {{ run.resumesFrom.slice(0, 8) }}…
        </div>

        <!-- Expanded node list -->
        <div v-if="expandedRunId === run.id" class="run-nodes">
          <div v-if="loadingNodes.has(run.id)" class="nodes-loading">
            <span class="spinner" /> Loading node details…
          </div>
          <div v-else-if="(nodeCache.get(run.id) || []).length === 0" class="nodes-empty">
            No node details available.
          </div>
          <div v-else class="nodes-list">
            <div
              v-for="node in (nodeCache.get(run.id) || [])"
              :key="node.nodeId"
              class="node-row"
              @click="emit('select-block', node.nodeId)"
            >
              <span class="node-status-icon" :style="{ color: statusColor(node.status) }">
                {{ node.status === 'completed' ? '✓' : node.status === 'failed' ? '✗' : node.status === 'skipped' ? '–' : '●' }}
              </span>
              <span class="node-type-badge" :style="{ background: statusColor(node.nodeType) }">
                {{ nodeTypeIcon(node.nodeType) }}
              </span>
              <span class="node-name">{{ node.nodeName || node.nodeId }}</span>
              <span class="node-status-text">{{ node.status }}</span>
              <span v-if="node.durationMs" class="node-duration">{{ node.durationMs > 1000 ? (node.durationMs / 1000).toFixed(1) + 's' : node.durationMs + 'ms' }}</span>
              <span v-if="node.tokensUsed" class="node-tokens">{{ node.tokensUsed }} tok</span>

              <!-- Output preview -->
              <div v-if="node.outputSummary" class="node-output-preview">
                {{ nodeOutputPreview(node.outputSummary) }}
              </div>

              <!-- Files written -->
              <div v-if="node.filesWritten && node.filesWritten.length > 0" class="node-files">
                <span v-for="f in node.filesWritten" :key="f" class="file-chip">{{ f }}</span>
              </div>

              <!-- Node error -->
              <div v-if="node.error" class="node-error-row">{{ node.error }}</div>
            </div>
          </div>
        </div>

        <!-- Action buttons (collapsed) -->
        <div v-if="expandedRunId !== run.id" class="run-actions">
          <button v-if="run.status === 'paused'" class="action-btn resume-btn" @click.stop="emit('select-block', '__resume__')">
            Resume
          </button>
          <button v-if="run.status === 'completed' || run.status === 'failed'" class="action-btn rerun-btn" @click.stop="onReRun(run)">
            Re-run
          </button>
        </div>
      </div>

      <!-- Load More -->
      <button v-if="hasMore" class="load-more-btn" @click="loadMore">
        Show more ({{ filteredRuns.length - visibleRuns.length }} remaining)
      </button>
    </div>

    <!-- Live Events Bar -->
    <div v-if="hasLiveEvents" class="live-bar">
      <span class="live-dot" />
      <span class="live-label">Live: {{ liveEvents.length }} event{{ liveEvents.length !== 1 ? 's' : '' }}</span>
    </div>
  </div>
</template>

<style scoped>
.timeline-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-primary);
  overflow: hidden;
}

.timeline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
  flex-shrink: 0;
}

.timeline-header h2 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-primary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.run-count {
  font-size: var(--text-xs);
  color: var(--text-muted);
  background: var(--bg-hover);
  padding: 0.2rem 0.6rem;
  border-radius: 10px;
}

.header-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: border-color var(--transition-fast);
}

.header-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.header-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid var(--text-muted);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.timeline-loading,
.timeline-error,
.timeline-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--text-muted);
  font-size: var(--text-sm);
  gap: var(--space-2);
}

.timeline-error {
  color: var(--error);
}

.timeline-empty {
  flex-direction: column;
  gap: var(--space-3);
}

.empty-icon {
  opacity: 0.3;
}

.timeline-empty p {
  margin: 0;
}

.run-cta-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
}

.run-cta-btn:hover { opacity: 0.9; }

.timeline-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.run-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color var(--transition-fast);
}

.run-card:hover {
  border-color: var(--accent-secondary);
}

.run-card.expanded {
  border-color: var(--accent);
}

.run-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  user-select: none;
}

.run-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.run-status-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-primary);
  min-width: 68px;
}

.run-mode-tag {
  font-size: var(--text-2xs);
  background: var(--bg-hover);
  color: var(--text-secondary);
  padding: 1px var(--space-1);
  border-radius: var(--radius-sm);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.run-time {
  font-size: var(--text-xs);
  color: var(--text-muted);
  margin-left: auto;
}

.run-duration,
.run-tokens {
  font-size: var(--text-xs);
  color: var(--text-muted);
  min-width: 32px;
  text-align: right;
}

.chevron {
  transition: transform var(--transition-fast);
  color: var(--text-muted);
  flex-shrink: 0;
}

.chevron.rotated {
  transform: rotate(90deg);
}

.run-error {
  padding: 0 var(--space-3) var(--space-2);
  font-size: var(--text-xs);
  color: var(--error);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.run-resumes-from {
  padding: 0 var(--space-3) var(--space-2);
  font-size: var(--text-2xs);
  color: var(--text-muted);
}

.run-nodes {
  border-top: 1px solid var(--border-color);
  background: var(--bg-primary);
}

.nodes-loading,
.nodes-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-4);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.nodes-list {
  display: flex;
  flex-direction: column;
}

.node-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
  transition: background var(--transition-fast);
  font-size: var(--text-xs);
}

.node-row:last-child {
  border-bottom: none;
}

.node-row:hover {
  background: var(--bg-hover);
}

.node-status-icon {
  font-size: 10px;
  width: 14px;
  text-align: center;
  flex-shrink: 0;
}

.node-type-badge {
  width: 18px;
  height: 18px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: white;
  flex-shrink: 0;
}

.node-name {
  font-weight: 500;
  color: var(--text-primary);
  font-size: var(--text-xs);
}

.node-status-text {
  color: var(--text-muted);
  font-size: var(--text-2xs);
}

.node-duration,
.node-tokens {
  color: var(--text-muted);
  font-size: var(--text-2xs);
}

.node-output-preview {
  width: 100%;
  padding: var(--space-1) 0;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  color: var(--text-secondary);
  max-height: 60px;
  overflow: hidden;
  line-height: 1.4;
}

.node-files {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.file-chip {
  font-size: var(--text-2xs);
  background: var(--bg-hover);
  color: var(--accent);
  padding: 1px var(--space-1);
  border-radius: var(--radius-sm);
}

.node-error-row {
  width: 100%;
  color: var(--error);
  font-size: var(--text-2xs);
}

.run-actions {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-3) var(--space-2);
}

.action-btn {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-size: var(--text-2xs);
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}

.action-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.load-more-btn {
  padding: var(--space-2);
  border: 1px dashed var(--border-color);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-size: var(--text-xs);
  cursor: pointer;
  text-align: center;
}

.load-more-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.live-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-secondary);
  border-top: 1px solid var(--border-color);
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2196f3;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.live-label {
  color: var(--text-muted);
}
</style>
