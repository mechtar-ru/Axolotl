<script setup lang="ts">
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
  }
  onClick?: () => void
}>()

const emit = defineEmits<{
  click: []
  rename: [name: string]
  duplicate: []
  delete: []
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

function handleClick() {
  if (props.onClick) {
    props.onClick()
  } else {
    emit('click')
  }
}

function handleContextMenu(e: MouseEvent) {
  e.preventDefault()
  // Could emit event for parent to show context menu
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return ''
  try {
    const d = new Date(dateStr)
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  } catch {
    return ''
  }
}
</script>

<template>
  <div class="app-card" @click="handleClick" @contextmenu="handleContextMenu">
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
</style>
