<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { schemaApi } from '@/services/api'
import type { ExecutionRun, NodeExecution } from '@/services/api'
import { useExecutionState } from '@/composables/useExecutionState'
import TimelineEntry from '@/components/studio/TimelineEntry.vue'

const props = defineProps<{
  schemaId: string
}>()

const emit = defineEmits<{
  'select-block': [blockId: string]
}>()

const execState = useExecutionState()
const liveEvents = computed(() => execState?.stepEvents.value || [])

const runs = ref<ExecutionRun[]>([])
const loading = ref(false)
const expandedRunId = ref<string | null>(null)
const expandedRunNodes = ref<NodeExecution[]>([])
const loadingNodes = ref(false)
const displayLimit = ref(10)
const confirmDeleteRunId = ref<string | null>(null)

const visibleRuns = computed(() => runs.value.slice(0, displayLimit.value))
const hasMore = computed(() => runs.value.length > displayLimit.value)
const hasStaleRuns = computed(() => runs.value.some(r => r.status === 'resuming'))

const liveEventsCapped = computed(() => {
  const ev = liveEvents.value
  return ev.length > 100 ? ev.slice(ev.length - 100) : ev
})

const statusColors: Record<string, string> = {
  running: '#2196f3',
  completed: '#4caf50',
  failed: '#ef4444',
  paused: '#f59e0b',
  cancelled: '#6b7280',
  resuming: '#8b5cf6'
}

function getStatusColor(status: string): string {
  return statusColors[status] || '#9e9e9e'
}

function nodeStatusColor(status: string): string {
  switch (status) {
    case 'completed': return '#4caf50'
    case 'running': return '#2196f3'
    case 'failed': return '#ef4444'
    case 'blocked': return '#ff9800'
    case 'skipped': return '#9e9e9e'
    default: return '#d0d5dd'
  }
}

function formatDuration(run: ExecutionRun): string {
  if (!run.startedAt) return '--'
  const start = new Date(run.startedAt).getTime()
  const end = run.completedAt ? new Date(run.completedAt).getTime() : Date.now()
  const ms = end - start
  if (ms < 1000) return '<1s'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return `${m}m ${s % 60}s`
}

function formatTokens(tokens: number): string {
  if (tokens === 0) return '--'
  if (tokens < 1000) return `${tokens} tok`
  return `${(tokens / 1000).toFixed(1)}k tok`
}

function formatTime(iso: string): string {
  if (!iso) return ''
  return new Date(iso).toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatDate(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  const today = new Date()
  const yesterday = new Date(today)
  yesterday.setDate(yesterday.getDate() - 1)
  if (d.toDateString() === today.toDateString()) return 'Today'
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday'
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
}

function shortId(id: string): string {
  return id?.length > 8 ? id.slice(0, 8) : id || ''
}

function truncate(text: string | null | undefined, max: number): string {
  if (!text) return ''
  return text.length > max ? text.slice(0, max) + '...' : text
}

async function fetchRuns() {
  if (!props.schemaId) return
  loading.value = true
  try {
    const all = await schemaApi.getRuns(props.schemaId)
    runs.value = all.filter(r => r.status !== 'resuming')
  } catch (e) {
    console.error('Failed to fetch runs:', e)
  } finally {
    loading.value = false
  }
}

async function expandRun(runId: string) {
  if (expandedRunId.value === runId) {
    expandedRunId.value = null
    expandedRunNodes.value = []
    return
  }
  expandedRunId.value = runId
  loadingNodes.value = true
  try {
    expandedRunNodes.value = await schemaApi.getRunNodes(props.schemaId, runId)
  } catch (e) {
    console.error('Failed to fetch run nodes:', e)
    expandedRunNodes.value = []
  } finally {
    loadingNodes.value = false
  }
}

async function releaseStale() {
  try {
    await schemaApi.cleanupRuns(props.schemaId)
    await fetchRuns()
  } catch (e) {
    console.error('Failed to release stale runs:', e)
  }
}

async function deleteRun(runId: string) {
  try {
    await schemaApi.deleteRun(props.schemaId, runId)
    runs.value = runs.value.filter(r => r.id !== runId)
    if (expandedRunId.value === runId) {
      expandedRunId.value = null
      expandedRunNodes.value = []
    }
  } catch (e) {
    console.error('Failed to delete run:', e)
  }
  confirmDeleteRunId.value = null
}

async function reRun() {
  try {
    await schemaApi.executeSchema(props.schemaId, 'EXECUTE')
  } catch (e) {
    console.error('Failed to execute:', e)
  }
}

async function resumeRun() {
  try {
    await schemaApi.resumeSchema(props.schemaId)
    await fetchRuns()
  } catch (e) {
    console.error('Failed to resume:', e)
  }
}

function showMore() {
  displayLimit.value += 10
}

watch(() => props.schemaId, () => {
  displayLimit.value = 10
  expandedRunId.value = null
  expandedRunNodes.value = []
  fetchRuns()
})

onMounted(fetchRuns)
</script>

<template>
  <div class="timeline-view">
    <!-- Header -->
    <div class="tl-header">
      <div class="tl-header-left">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             width="18" height="18" class="tl-header-icon">
          <circle cx="12" cy="12" r="10"/>
          <polyline points="12 6 12 12 16 14"/>
        </svg>
        <h2>Run History</h2>
        <span class="tl-count">{{ runs.length }}</span>
      </div>
      <button v-if="hasStaleRuns" class="tl-btn tl-btn-stale" @click="releaseStale">
        Release Stale Runs
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="tl-loading">
      <div class="tl-spinner"/>
      <p>Loading runs...</p>
    </div>

    <!-- Empty state -->
    <div v-else-if="runs.length === 0" class="tl-empty">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
           width="40" height="40" class="tl-empty-icon">
        <circle cx="12" cy="12" r="10"/>
        <polyline points="12 6 12 12 16 14"/>
      </svg>
      <p>No runs yet</p>
      <button class="tl-cta" @click="reRun">Execute Pipeline</button>
    </div>

    <!-- Run list -->
    <div v-else class="tl-list">
      <div
        v-for="run in visibleRuns"
        :key="run.id"
        class="tl-card"
        :class="{ expanded: expandedRunId === run.id }"
      >
        <!-- Collapsed header -->
        <div class="tl-card-header" @click="expandRun(run.id)">
          <span class="tl-dot" :style="{ background: getStatusColor(run.status) }" />
          <span class="tl-mode">{{ run.mode }}</span>
          <span class="tl-date">{{ formatDate(run.startedAt) }}</span>
          <span class="tl-time">{{ formatTime(run.startedAt) }}</span>
          <span class="tl-duration">{{ formatDuration(run) }}</span>
          <span class="tl-tokens">{{ formatTokens(run.totalTokens) }}</span>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               width="16" height="16"
               class="tl-chevron"
               :class="{ open: expandedRunId === run.id }">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </div>

        <!-- Expanded body -->
        <div v-if="expandedRunId === run.id" class="tl-card-body">
          <div v-if="loadingNodes" class="tl-nodes-loading">Loading node details...</div>

          <template v-else-if="expandedRunNodes.length > 0">
            <TimelineEntry
              v-for="node in expandedRunNodes"
              :key="node.id"
              :node="node"
            />

            <div class="tl-card-actions">
              <button
                v-if="run.status === 'paused'"
                class="tl-act tl-act-resume"
                @click.stop="resumeRun()"
              >
                Resume
              </button>
              <button class="tl-act tl-act-rerun" @click.stop="reRun()">
                Re-run
              </button>
              <button
                v-if="confirmDeleteRunId === run.id"
                class="tl-act tl-act-confirm"
                @click.stop="deleteRun(run.id)"
              >
                Confirm Delete
              </button>
              <button
                v-else
                class="tl-act tl-act-delete"
                @click.stop="confirmDeleteRunId = run.id"
              >
                Delete
              </button>
            </div>

            <div v-if="run.resumesFrom" class="tl-resumes">
              Continued from run
              <span class="tl-resumes-id">{{ shortId(run.resumesFrom) }}</span>
            </div>
          </template>

          <div v-else class="tl-no-nodes">No node execution data available</div>
        </div>
      </div>

      <button v-if="hasMore" class="tl-more" @click="showMore">
        Show more ({{ runs.length - displayLimit }} remaining)
      </button>
    </div>

    <!-- Live Events Bar -->
    <div v-if="liveEventsCapped.length > 0" class="tl-live">
      <div class="tl-live-header">
        <span class="tl-live-dot" />
        <span>Live Events</span>
        <span class="tl-live-count">{{ liveEventsCapped.length }}</span>
      </div>
      <div class="tl-live-list">
        <div
          v-for="ev in liveEventsCapped"
          :key="ev.blockId + '-' + ev.stepIndex"
          class="tl-live-item"
        >
          <span class="tl-live-status" :style="{ background: getStatusColor(ev.status) }" />
          <span class="tl-live-label">{{ ev.label }}</span>
          <span class="tl-live-kind">{{ ev.status }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.timeline-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

/* ── Header ── */
.tl-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
  flex-shrink: 0;
}

.tl-header-left {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.tl-header-icon {
  color: var(--text-secondary);
}

.tl-header h2 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-primary);
}

.tl-count {
  font-size: var(--text-xs);
  color: var(--text-muted);
  background: var(--bg-hover);
  padding: 2px var(--space-2);
  border-radius: 10px;
}

/* ── Buttons ── */
.tl-btn {
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  cursor: pointer;
  background: var(--bg-surface);
  color: var(--text-secondary);
  transition: all var(--transition-fast);
}
.tl-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.tl-btn-stale { color: #f59e0b; border-color: #f59e0b; }

/* ── Loading ── */
.tl-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: var(--space-3);
  color: var(--text-muted);
  font-size: var(--text-sm);
}
.tl-spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-color);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: tl-spin 0.8s linear infinite;
}
@keyframes tl-spin { to { transform: rotate(360deg); } }

/* ── Empty state ── */
.tl-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: var(--space-3);
  color: var(--text-muted);
  font-size: var(--text-sm);
}
.tl-empty-icon { opacity: 0.3; }
.tl-empty p { margin: 0; }
.tl-cta {
  padding: var(--space-2) var(--space-4);
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
}
.tl-cta:hover { opacity: 0.9; }

/* ── Run list ── */
.tl-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4) var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

/* ── Card ── */
.tl-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color var(--transition-fast);
}
.tl-card:hover { border-color: var(--accent); }
.tl-card.expanded { border-color: var(--accent); }

.tl-card-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  cursor: pointer;
  user-select: none;
  font-size: var(--text-sm);
  color: var(--text-primary);
}

.tl-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tl-mode {
  font-weight: 600;
  font-size: var(--text-xs);
  color: var(--text-secondary);
  min-width: 56px;
}

.tl-date { color: var(--text-muted); min-width: 56px; }
.tl-time { color: var(--text-secondary); min-width: 44px; }
.tl-duration { color: var(--text-muted); min-width: 52px; }
.tl-tokens { color: var(--text-muted); flex: 1; text-align: right; }
.tl-chevron {
  flex-shrink: 0;
  transition: transform var(--transition-fast);
  color: var(--text-muted);
}
.tl-chevron.open { transform: rotate(90deg); }

/* ── Card body ── */
.tl-card-body {
  border-top: 1px solid var(--border-color);
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.tl-nodes-loading,
.tl-no-nodes {
  font-size: var(--text-sm);
  color: var(--text-muted);
  padding: var(--space-3) 0;
  text-align: center;
}

/* ── Action buttons ── */
.tl-card-actions {
  display: flex;
  gap: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border-color);
  margin-top: var(--space-2);
}

.tl-act {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  cursor: pointer;
  border: 1px solid var(--border-color);
  background: var(--bg-surface);
  color: var(--text-secondary);
  transition: all var(--transition-fast);
}
.tl-act:hover { background: var(--bg-hover); color: var(--text-primary); }
.tl-act-resume { color: #2196f3; border-color: #2196f3; }
.tl-act-rerun { color: #4caf50; border-color: #4caf50; }
.tl-act-confirm { color: #ef4444; border-color: #ef4444; }
.tl-act-delete { color: var(--text-muted); }

/* ── resumesFrom label ── */
.tl-resumes {
  font-size: var(--text-xs);
  color: var(--text-muted);
  padding: var(--space-1) 0;
}
.tl-resumes-id {
  font-family: monospace;
  color: var(--text-secondary);
}

/* ── Show more ── */
.tl-more {
  padding: var(--space-2);
  border: 1px dashed var(--border-color);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: var(--text-sm);
  transition: all var(--transition-fast);
}
.tl-more:hover {
  border-color: var(--accent);
  color: var(--accent);
}

/* ── Live Events ── */
.tl-live {
  border-top: 1px solid var(--border-color);
  background: var(--bg-secondary);
  flex-shrink: 0;
  max-height: 160px;
  display: flex;
  flex-direction: column;
}

.tl-live-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-secondary);
}

.tl-live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4caf50;
  animation: tl-pulse 1.5s ease-in-out infinite;
}
@keyframes tl-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.tl-live-count {
  margin-left: auto;
  background: var(--bg-hover);
  padding: 0 var(--space-2);
  border-radius: 8px;
}

.tl-live-list {
  overflow-y: auto;
  padding: 0 var(--space-4) var(--space-2);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tl-live-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

.tl-live-status {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tl-live-label {
  font-weight: 500;
  color: var(--text-primary);
}

.tl-live-kind {
  color: var(--text-muted);
  text-transform: uppercase;
  font-size: 10px;
}
</style>
