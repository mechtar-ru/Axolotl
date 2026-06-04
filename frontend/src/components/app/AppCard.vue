<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps<{
  app: {
    id: string
    name: string
    description?: string
    appType?: string
    updatedAt?: string
    createdAt?: string
    version?: string
    userId?: string
    workspaceId?: string
    // NEW FIELDS — merged from generatedApps
    targetPath?: string
    isGenerated?: boolean
    status?: 'active' | 'idle'
  }
  onClick?: () => void
}>()

const emit = defineEmits<{
  click: []
  rename: [name: string]
  duplicate: []
  delete: []
  setGroup: []
}>()

const router = useRouter()

const appTypeIcons: Record<string, string> = {
  CHAT: 'M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z',
  ANALYZER: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
  GENERATOR: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
  EMAIL: 'M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  GAME: 'M6 12h4m4 0h4M6 16h4m4 0h4M6 8h12M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6z',
  CUSTOM: 'M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4'
}

const appTypeColors: Record<string, string> = {
  CHAT: '#4caf50',
  ANALYZER: '#2196f3',
  GENERATOR: '#ff9800',
  EMAIL: '#9c27b0',
  GAME: '#e91e63',
  CUSTOM: '#6c63ff'
}

function getAppTypeColor(type?: string): string {
  return appTypeColors[type || 'CUSTOM'] as string
}

function getAppTypeLabel(type?: string): string {
  const labels: Record<string, string> = {
    CHAT: 'Chat',
    ANALYZER: 'Analyzer',
    GENERATOR: 'Generator',
    EMAIL: 'Email',
    GAME: 'Game',
    CUSTOM: 'Custom'
  }
  return labels[type || 'CUSTOM'] || type || 'CUSTOM'
}

function handleClick(e: MouseEvent) {
  // Vue 3 processes all VNode handlers synchronously, so @click.stop
  // on children doesn't prevent the parent's @click from firing.
  // Guard: skip navigation when clicking action buttons.
  const target = e.target as HTMLElement
  if (target.closest('.card-actions')) return
  if (props.onClick) {
    props.onClick()
  } else {
    emit('click')
  }
}

const deleting = ref(false)

async function handleDelete(e: MouseEvent) {
  e.stopPropagation()
  deleting.value = true
  emit('delete')
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return ''
  try {
    const d = new Date(dateStr)
    const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
    const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
    return `${date} at ${time}`
  } catch {
    return ''
  }
}

function formatPath(fullPath: string): string {
  // 1. Replace /Users/evgenijtihomirov with ~
  const homeReplaced = fullPath.replace(/^\/Users\/evgenijtihomirov/, '~')
  // 2. Strip trailing slash for splitting, re-add later
  const cleaned = homeReplaced.replace(/\/$/, '')
  const parts = cleaned.split('/').filter(Boolean)
  // 3. If 2 or fewer segments, show as-is
  if (parts.length <= 2) return cleaned + '/'
  // 4. Show last 2 segments with ellipsis prefix
  return '\u2026/' + parts.slice(-2).join('/') + '/'
}

function getStatusDotColor(status?: 'active' | 'idle'): string {
  if (status === 'active') return '#4caf50'
  return '#9e9e9e' // gray for idle/default
}
</script>

<template>
  <div class="app-card" @click="handleClick">
    <div class="app-card-header">
      <div class="app-type-badge" :style="{ background: getAppTypeColor(app.appType) }">
        <svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path :d="appTypeIcons[app.appType || 'CUSTOM'] || appTypeIcons.CUSTOM" />
        </svg>
      </div>
      <div class="app-type-label">{{ getAppTypeLabel(app.appType) }}</div>
    </div>
    <div class="app-card-body">
      <h3 class="app-name">{{ app.name }}</h3>
      <p v-if="app.description" class="app-description">{{ app.description }}</p>
    </div>
    <div class="app-card-footer">
      <span v-if="app.updatedAt" class="app-date">Updated {{ formatDate(app.updatedAt) }}</span>
      <span v-else-if="app.createdAt" class="app-date">Created {{ formatDate(app.createdAt) }}</span>
      <div class="card-actions">
        <button class="action-btn group-btn" @click.stop="emit('setGroup')" title="Set project group">
          <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
            <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zm0 6a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zm10-1a1 1 0 00-1 1v6a1 1 0 001 1h3a1 1 0 001-1v-6a1 1 0 00-1-1h-3z"/>
          </svg>
        </button>
        <button class="action-btn delete-btn" @click="handleDelete" title="Delete app">
          <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
            <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd"/>
          </svg>
        </button>
      </div>
    </div>
    <div v-if="app.isGenerated && app.targetPath" class="app-card-path" :title="app.targetPath">
      <svg class="path-icon" viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
        <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
      </svg>
      <span class="path-text">{{ formatPath(app.targetPath) }}</span>
      <span class="status-dot" :style="{ background: getStatusDotColor(app.status) }" :title="app.status === 'active' ? 'Active sessions' : 'Idle'"></span>
    </div>
  </div>
</template>

<style scoped>
.app-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1.25rem;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s;
}

.app-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--accent);
}

.app-card-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.app-type-badge {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.app-type-badge svg {
  width: 20px;
  height: 20px;
}

.app-type-label {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted);
}

.app-card-body {
  margin-bottom: 0.75rem;
}

.app-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.25rem 0;
  line-height: 1.3;
}

.app-description {
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin: 0;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.app-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.app-date {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.app-card-path {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.625rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--border-color);
  font-size: 0.75rem;
  color: var(--text-muted);
  cursor: default;
}

.path-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--text-muted);
  opacity: 0.7;
}

.path-text {
  font-family: var(--font-mono);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.card-actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}
.action-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-muted);
  padding: 4px;
  border-radius: 4px;
  line-height: 1;
  transition: color 0.15s;
}
.action-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.delete-btn:hover {
  color: var(--danger);
}
.group-btn:hover {
  color: var(--accent);
}
.delete-btn:hover {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.1);
}
</style>
