<script setup lang="ts">
import { ref, computed, onMounted, watch, inject, onDeactivated, onActivated } from 'vue'
import { schemaApi } from '@/services/api'
import type { ExecutionRun, NodeExecution } from '@/services/api'
import { useExecutionState } from '@/composables/useExecutionState'
import type { Ref } from 'vue'
import TimelineEntry from '@/components/studio/TimelineEntry.vue'

const props = defineProps<{
  schemaId: string
}>()

const emit = defineEmits<{
  'select-block': [blockId: string]
}>()

const execState = useExecutionState()
const liveEvents = computed(() => execState?.stepEvents.value || [])
const isRunning = inject<Ref<boolean>>('isRunning', ref(false))
const startExecution = inject<() => Promise<void>>('startExecution', async () => {})

// ── Run state ──
const runs = ref<ExecutionRun[]>([])
const loading = ref(false)
const expandedRunId = ref<string | null>(null)
const expandedRunNodes = ref<NodeExecution[]>([])
const loadingNodes = ref(false)
const displayLimit = ref(10)
const confirmDeleteRunId = ref<string | null>(null)
const releasingStale = ref(false)
const reRunning = ref(false)
const errorMessage = ref<string | null>(null)
const runNodeStatuses = ref<Record<string, Array<{nodeId: string; status: string}>>>({})
const staleRunCount = ref(0) // resuming runs detected during fetch

const visibleRuns = computed(() => runs.value.slice(0, displayLimit.value))
const hasMore = computed(() => runs.value.length > displayLimit.value)
const hasStaleRuns = computed(() => staleRunCount.value > 0)
const latestPausedRunId = computed(() => runs.value.find(r => r.status === 'paused')?.id ?? null)

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
  // Use completedAt if available; for running runs use current time; 
  // for completed runs without completedAt fall back to startedAt (shows 0s)
  const end = run.completedAt 
    ? new Date(run.completedAt).getTime() 
    : (run.status === 'running' ? Date.now() : start)
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

function clearError() {
  errorMessage.value = null
}

// ── Data fetching ──

async function fetchRuns() {
  if (!props.schemaId) return
  loading.value = true
  clearError()
  try {
    const all = await schemaApi.getRuns(props.schemaId)
    staleRunCount.value = all.filter(r => r.status === 'resuming').length
    runs.value = all.filter(r => r.status !== 'resuming')
    // Auto-expand latest running/completed run on initial load
    if (!expandedRunId.value) {
      const latest = runs.value[0]
      if (latest && (latest.status === 'running' || latest.status === 'completed')) {
        expandRun(latest.id)
      }
    }
    // Fetch node statuses for flow dots on visible runs
    fetchNodeStatuses()
  } catch (e) {
    console.error('Failed to fetch runs:', e)
    errorMessage.value = 'Failed to load run history'
  } finally {
    loading.value = false
  }
}

async function fetchNodeStatuses() {
  const targets = visibleRuns.value.filter(r => !runNodeStatuses.value[r.id])
  if (targets.length === 0) return
  const results = await Promise.allSettled(
    targets.map(r => schemaApi.getRunNodes(props.schemaId, r.id))
  )
   for (let i = 0; i < targets.length; i++) {
     const run = targets[i]!
     const result = results[i]!
     if (result.status === 'fulfilled') {
       const nodeExecs = (result as PromiseFulfilledResult<NodeExecution[]>).value
       runNodeStatuses.value[run.id] = nodeExecs.map(n => ({
         nodeId: n.nodeId,
         status: n.status
       }))
     }
   }
}

// ── Run expansion ──

async function expandRun(runId: string) {
  if (expandedRunId.value === runId) {
    expandedRunId.value = null
    expandedRunNodes.value = []
    return
  }
  expandedRunId.value = runId
  loadingNodes.value = true
  clearError()
  try {
    expandedRunNodes.value = await schemaApi.getRunNodes(props.schemaId, runId)
  } catch (e) {
    console.error('Failed to fetch run nodes:', e)
    errorMessage.value = 'Failed to load node details'
    expandedRunNodes.value = []
  } finally {
    loadingNodes.value = false
  }
}

// ── Actions ──

async function releaseStale() {
  if (releasingStale.value) return
  releasingStale.value = true
  clearError()
  try {
    await schemaApi.cleanupRuns(props.schemaId)
    await fetchRuns()
  } catch (e) {
    console.error('Failed to release stale runs:', e)
    errorMessage.value = 'Failed to release stale runs'
  } finally {
    releasingStale.value = false
  }
}

async function deleteRun(runId: string) {
  clearError()
  try {
    await schemaApi.deleteRun(props.schemaId, runId)
    runs.value = runs.value.filter(r => r.id !== runId)
    delete runNodeStatuses.value[runId]
    if (expandedRunId.value === runId) {
      expandedRunId.value = null
      expandedRunNodes.value = []
    }
  } catch (e) {
    console.error('Failed to delete run:', e)
    errorMessage.value = 'Failed to delete run'
  }
  confirmDeleteRunId.value = null
}

function requestDeleteRun(runId: string) {
  confirmDeleteRunId.value = runId
  // Auto-cancel after 3 seconds
  setTimeout(() => {
    if (confirmDeleteRunId.value === runId) {
      confirmDeleteRunId.value = null
    }
  }, 3000)
}

async function reRun() {
  if (reRunning.value) return
  reRunning.value = true
  clearError()
  try {
    await startExecution()
  } catch (e) {
    console.error('Failed to execute:', e)
    errorMessage.value = 'Failed to start execution'
  } finally {
    reRunning.value = false
  }
}

async function resumeRun(runId?: string) {
  clearError()
  try {
    await schemaApi.resumeSchema(props.schemaId, runId)
    await fetchRuns()
  } catch (e) {
    console.error('Failed to resume:', e)
    errorMessage.value = 'Failed to resume run'
  }
}

function showMore() {
  displayLimit.value += 10
}

// ── Auto-refresh after execution completes ──
watch(isRunning, (newVal, oldVal) => {
  if (oldVal === true && newVal === false) {
    // Execution just finished — refresh run list
    fetchRuns()
  }
})

// ── Schema ID changes ──
watch(() => props.schemaId, () => {
  displayLimit.value = 10
  expandedRunId.value = null
  expandedRunNodes.value = []
  runNodeStatuses.value = {}
  fetchRuns()
})

onMounted(fetchRuns)
onActivated(() => {
  fetchRuns()
})
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
      <button
        v-if="hasStaleRuns"
        class="tl-btn tl-btn-stale"
        :disabled="releasingStale"
        @click="releaseStale"
      >
        {{ releasingStale ? 'Releasing...' : 'Release Stale Runs' }}
      </button>
    </div>

    <!-- Error message -->
    <div v-if="errorMessage" class="tl-error">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           width="14" height="14">
        <circle cx="12" cy="12" r="10"/>
        <line x1="15" y1="9" x2="9" y2="15"/>
        <line x1="9" y1="9" x2="15" y2="15"/>
      </svg>
      <span>{{ errorMessage }}</span>
      <button class="tl-error-dismiss" @click="clearError">x</button>
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
      <button
        class="tl-cta"
        :disabled="reRunning"
        @click="reRun"
      >
        {{ reRunning ? 'Starting...' : 'Execute Pipeline' }}
      </button>
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

          <!-- Node flow dots -->
          <div class="tl-flowdots" v-if="runNodeStatuses[run.id]?.length">
            <span
              v-for="(ns, i) in runNodeStatuses[run.id]"
              :key="i"
              class="tl-flowdot"
              :style="{ background: nodeStatusColor(ns.status) }"
              :title="`${ns.nodeId}: ${ns.status}`"
            />
          </div>

          <span class="tl-mode">{{ run.mode }}</span>
          <span class="tl-session">Session {{ runs.length - runs.indexOf(run) }}</span>
          <span class="tl-date">{{ formatDate(run.startedAt) }}</span>
          <span class="tl-time">{{ formatTime(run.startedAt) }}</span>
          <span class="tl-duration">{{ formatDuration(run) }}</span>
          <span
            class="tl-tokens"
            :title="run.totalTokens === 0 && run.estimatedCost === 0 ? 'Local models don\'t report token usage' : ''"
          >{{ formatTokens(run.totalTokens) }}</span>
          <span
            v-if="run.error"
            class="tl-error-badge"
            :title="run.error"
          >error</span>
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
              <!-- Only show Resume on the latest paused run -->
              <button
                v-if="run.status === 'paused' && run.id === latestPausedRunId"
                class="tl-act tl-act-resume"
                @click.stop="resumeRun(run.id)"
              >
                Resume
              </button>
              <button
                class="tl-act tl-act-rerun"
                :disabled="reRunning"
                @click.stop="reRun"
              >
                {{ reRunning ? 'Running...' : (run.status === 'completed' ? 'New Session' : 'Re-run') }}
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
                @click.stop="requestDeleteRun(run.id)"
              >
                Delete
              </button>
            </div>

            <!-- Superseded label for older paused runs -->
            <div v-if="run.status === 'paused' && run.id !== latestPausedRunId" class="tl-superseded">
              Superseded by run
              <span class="tl-superseded-id">{{ shortId(latestPausedRunId ?? '') }}</span>
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
        Show more ({{ Math.max(0, runs.length - displayLimit) }} remaining)
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
  --tl-color-stale: #f59e0b;
  --tl-color-error: #ef4444;
  --tl-color-completed: #4caf50;
  --tl-color-running: #2196f3;
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
.tl-btn:hover:not(:disabled) { background: var(--bg-hover); color: var(--text-primary); }
.tl-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.tl-btn-stale { color: var(--tl-color-stale); border-color: var(--tl-color-stale); }

/* ── Error ── */
.tl-error {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-6);
  background: rgba(239, 68, 68, 0.08);
  color: var(--tl-color-error);
  font-size: var(--text-xs);
  flex-shrink: 0;
}
.tl-error-dismiss {
  margin-left: auto;
  background: none;
  border: none;
  color: var(--tl-color-error);
  cursor: pointer;
  font-size: var(--text-sm);
  padding: 0 var(--space-1);
}

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
.tl-cta:hover:not(:disabled) { opacity: 0.9; }
.tl-cta:disabled { opacity: 0.6; cursor: not-allowed; }

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

/* Node flow dots */
.tl-flowdots {
  display: flex;
  gap: 3px;
  flex-shrink: 0;
}
.tl-flowdot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.tl-mode {
  font-weight: 600;
  font-size: var(--text-xs);
  color: var(--text-secondary);
  min-width: 56px;
}

.tl-session {
  font-family: monospace;
  font-size: var(--text-xs);
  color: var(--accent);
  background: rgba(99, 102, 241, 0.08);
  padding: 1px var(--space-2);
  border-radius: 8px;
  min-width: 64px;
  text-align: center;
}

.tl-date { color: var(--text-muted); min-width: 56px; }
.tl-time { color: var(--text-secondary); min-width: 44px; }
.tl-duration { color: var(--text-muted); min-width: 52px; }
.tl-tokens { color: var(--text-muted); flex: 1; text-align: right; }

.tl-error-badge {
  font-size: 9px;
  text-transform: uppercase;
  font-weight: 700;
  color: var(--tl-color-error);
  background: rgba(239, 68, 68, 0.1);
  padding: 1px var(--space-2);
  border-radius: 8px;
}

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
.tl-act:hover:not(:disabled) { background: var(--bg-hover); color: var(--text-primary); }
.tl-act:disabled { opacity: 0.5; cursor: not-allowed; }
.tl-act-resume { color: var(--tl-color-running); border-color: var(--tl-color-running); }
.tl-act-rerun { color: var(--tl-color-completed); border-color: var(--tl-color-completed); }
.tl-act-confirm { color: var(--tl-color-error); border-color: var(--tl-color-error); }
.tl-act-delete { color: var(--text-muted); }

/* ── Superseded label ── */
.tl-superseded {
  font-size: var(--text-xs);
  color: var(--text-muted);
  padding: var(--space-1) 0;
  font-style: italic;
}
.tl-superseded-id {
  font-family: monospace;
  color: var(--text-secondary);
}

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
  background: var(--tl-color-completed);
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

/* ── Scrollbar ── */
.tl-list::-webkit-scrollbar,
.tl-live-list::-webkit-scrollbar {
  width: 6px;
}
.tl-list::-webkit-scrollbar-thumb,
.tl-live-list::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}
</style>
