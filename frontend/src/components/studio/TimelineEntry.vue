<script setup lang="ts">
import { computed, ref } from 'vue'
import type { NodeExecution } from '@/services/api'

const props = defineProps<{
  node: NodeExecution
}>()

const showFullOutput = ref(false)

const statusColors: Record<string, string> = {
  completed: '#4caf50',
  running: '#2196f3',
  failed: '#ef4444',
  blocked: '#ff9800',
  skipped: '#9e9e9e',
  pending: '#d0d5dd'
}

function getStatusColor(status: string): string {
  return statusColors[status] || '#9e9e9e'
}

function getStatusLabel(status: string): string {
  return status.charAt(0).toUpperCase() + status.slice(1)
}

const outputPreview = computed(() => {
  const text = props.node.outputSummary || ''
  if (!text) return ''
  return showFullOutput.value ? text : text.length > 200 ? text.slice(0, 200) + '...' : text
})

const hasOutput = computed(() => !!props.node.outputSummary)

const filesList = computed(() => {
  if (!props.node.filesWritten) return []
  try {
    const parsed = JSON.parse(props.node.filesWritten)
    return Array.isArray(parsed) ? parsed : [props.node.filesWritten]
  } catch {
    return [props.node.filesWritten]
  }
})

const nodeTypeBadge = computed(() => {
  const map: Record<string, string> = {
    source: 'Receive',
    agent: 'Agent',
    review: 'Review',
    verifier: 'Verify',
    output: 'Output',
    condition: 'Condition',
    loop: 'Loop',
    memory: 'Memory'
  }
  return map[props.node.nodeType] || props.node.nodeType
})

const nodeTypeColor = computed(() => {
  const map: Record<string, string> = {
    source: '#4caf50',
    agent: '#2196f3',
    review: '#f59e0b',
    verifier: '#8b5cf6',
    output: '#ff9800',
  }
  return map[props.node.nodeType] || '#6b7280'
})

function formatDuration(ms: number): string {
  if (!ms) return '--'
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}.${Math.floor((ms % 1000) / 100)}s`
  const m = Math.floor(s / 60)
  return `${m}m ${s % 60}s`
}

function formatTokens(tokens: number): string {
  if (!tokens) return '--'
  if (tokens < 1000) return `${tokens} tok`
  return `${(tokens / 1000).toFixed(1)}k tok`
}
</script>

<template>
  <div class="tl-entry">
    <div class="tl-entry-main">
      <span class="tl-entry-dot" :style="{ background: getStatusColor(node.status) }" />
      <span class="tl-entry-name">{{ node.nodeName || node.nodeId }}</span>
      <span class="tl-entry-type" :style="{ color: nodeTypeColor }">{{ nodeTypeBadge }}</span>
      <span class="tl-entry-status" :class="node.status">{{ getStatusLabel(node.status) }}</span>
      <span class="tl-entry-dur">{{ formatDuration(node.durationMs) }}</span>
      <span class="tl-entry-tok">{{ formatTokens(node.tokensUsed) }}</span>
    </div>

    <!-- Error -->
    <div v-if="node.error" class="tl-entry-error">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           width="14" height="14">
        <circle cx="12" cy="12" r="10"/>
        <line x1="15" y1="9" x2="9" y2="15"/>
        <line x1="9" y1="9" x2="15" y2="15"/>
      </svg>
      <span>{{ node.error }}</span>
    </div>

    <!-- Output preview -->
    <div v-if="hasOutput" class="tl-entry-output">
      <pre class="tl-entry-output-text">{{ outputPreview }}</pre>
      <button
        v-if="node.outputSummary && node.outputSummary.length > 200"
        class="tl-entry-toggle"
        @click="showFullOutput = !showFullOutput"
      >
        {{ showFullOutput ? 'Show less' : 'Show more' }}
      </button>
    </div>

    <!-- Files written -->
    <div v-if="filesList.length > 0" class="tl-entry-files">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           width="12" height="12">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
        <polyline points="14 2 14 8 20 8"/>
      </svg>
      <span v-for="(file, i) in filesList" :key="i" class="tl-entry-file">{{ file }}</span>
    </div>
  </div>
</template>

<style scoped>
.tl-entry {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-2);
  border-radius: var(--radius-sm);
  transition: background var(--transition-fast);
}

.tl-entry:hover {
  background: var(--bg-hover);
}

.tl-entry-main {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
}

.tl-entry-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tl-entry-name {
  font-weight: 500;
  color: var(--text-primary);
  min-width: 80px;
}

.tl-entry-type {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  padding: 1px var(--space-2);
  border-radius: 10px;
  background: var(--bg-hover);
}

.tl-entry-status {
  font-size: var(--text-xs);
  font-weight: 500;
  min-width: 64px;
}
.tl-entry-status.completed { color: #4caf50; }
.tl-entry-status.failed { color: #ef4444; }
.tl-entry-status.running { color: #2196f3; }
.tl-entry-status.skipped { color: #9e9e9e; }
.tl-entry-status.blocked { color: #ff9800; }

.tl-entry-dur {
  font-size: var(--text-xs);
  color: var(--text-muted);
  min-width: 48px;
  text-align: right;
}

.tl-entry-tok {
  font-size: var(--text-xs);
  color: var(--text-muted);
  min-width: 48px;
  text-align: right;
}

/* Error */
.tl-entry-error {
  display: flex;
  align-items: flex-start;
  gap: var(--space-1);
  font-size: var(--text-xs);
  color: #ef4444;
  padding-left: calc(8px + var(--space-2));
}

/* Output */
.tl-entry-output {
  padding-left: calc(8px + var(--space-2));
}

.tl-entry-output-text {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  background: var(--bg-canvas);
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  max-height: 120px;
  overflow-y: auto;
}

.tl-entry-toggle {
  font-size: var(--text-xs);
  color: var(--accent);
  background: none;
  border: none;
  cursor: pointer;
  padding: var(--space-1) 0;
}

/* Files */
.tl-entry-files {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-wrap: wrap;
  padding-left: calc(8px + var(--space-2));
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.tl-entry-file {
  font-family: monospace;
  color: var(--text-secondary);
}
</style>
