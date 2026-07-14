<script setup lang="ts">
import { ref, onMounted, onActivated } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { appApi, historyApi } from '@/services/api'
import { useSchemaStore } from '@/stores/schemaStore'
import type { AppInfo } from '@/services/api'
import type { ExecutionRecord } from '@/services/api'

defineOptions({ name: 'AppDashboardView' })

const route = useRoute()
const router = useRouter()
const schemaStore = useSchemaStore()
const appId = route.params.id as string

const app = ref<AppInfo | null>(null)
const generatedFiles = ref<Array<{ path: string; description: string }>>([])
const history = ref<ExecutionRecord[]>([])
const loading = ref(true)
const error = ref('')
const renameMode = ref(false)
const newName = ref('')

const accentColors: Record<string, string> = {
  CHAT: '#4caf50',
  ANALYZER: '#2196f3',
  GENERATOR: '#ff9800',
  EMAIL: '#9c27b0',
  GAME: '#e91e63',
  CUSTOM: '#6c63ff'
}
const bgColors: Record<string, string> = {
  CHAT: 'rgba(76, 175, 80, 0.08)',
  ANALYZER: 'rgba(33, 150, 243, 0.08)',
  GENERATOR: 'rgba(255, 152, 0, 0.08)',
  EMAIL: 'rgba(156, 39, 176, 0.08)',
  GAME: 'rgba(233, 30, 99, 0.08)',
  CUSTOM: 'rgba(108, 99, 255, 0.08)'
}

async function loadApp() {
  loading.value = true
  error.value = ''
  try {
    const [appData, files, execHistory] = await Promise.all([
      appApi.getApp(appId),
      appApi.getGeneratedFiles(appId).catch(() => []),
      historyApi.getSchemaHistory(appId).catch(() => []),
    ])
    app.value = appData
    generatedFiles.value = files
    history.value = execHistory
  } catch (e: any) {
    if (e?.response?.status === 404) {
      error.value = 'App not found'
    } else {
      error.value = e?.message || 'Failed to load app'
    }
  } finally {
    loading.value = false
  }
}

async function handleRename() {
  if (!app.value || !newName.value.trim()) return
  try {
    const updated = await appApi.updateApp(appId, { name: newName.value.trim() })
    app.value = updated
    renameMode.value = false
    // Sync with schemaStore so dashboard shows new name without reload
    const schema = schemaStore.schemas.find(s => s.id === appId)
    if (schema) schema.name = newName.value.trim()
  } catch (e: any) {
    console.error('AppDashboardView: Failed to rename:', e)
  }
}

function openStudio() {
  router.push(`/app/${appId}`)
}

function goToDashboard() {
  router.push('/')
}

function formatDate(ts: number): string {
  return new Date(ts).toLocaleDateString('en-US', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  })
}

function formatPath(path: string | undefined): string {
  if (!path) return '—'
  // Replace home dir for display
  return path.replace(/^\/Users\/[^/]+/, '~')
}

function statusColor(status: string): string {
  switch (status) {
    case 'COMPLETED': return '#4caf50'
    case 'RUNNING': return '#2196f3'
    case 'FAILED': return '#e53935'
    case 'BLOCKED': return '#ff9800'
    default: return 'var(--text-muted)'
  }
}

onMounted(loadApp)

// Refresh when re-activated from keep-alive cache
onActivated(() => {
  loadApp()
})
</script>

<template>
  <div class="app-dashboard">
    <!-- Loading -->
    <div v-if="loading" class="state-screen">
      <div class="spinner-lg" />
      <p>Loading app…</p>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="state-screen error-state">
      <p>{{ error }}</p>
      <button class="btn-secondary" @click="goToDashboard">Back to Dashboard</button>
    </div>

    <!-- Content -->
    <template v-else-if="app">
      <!-- Header -->
      <header class="dashboard-header">
        <button class="back-btn" @click="goToDashboard">
          <svg viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
            <path fill-rule="evenodd" d="M9.707 16.707a1 1 0 01-1.414 0l-6-6a1 1 0 010-1.414l6-6a1 1 0 011.414 1.414L5.414 9H17a1 1 0 110 2H5.414l4.293 4.293a1 1 0 010 1.414z" clip-rule="evenodd"/>
          </svg>
          Dashboard
        </button>

        <div class="app-title-row">
          <div class="app-icon" :style="{ background: bgColors[app.appType], color: accentColors[app.appType] }">
            {{ app.appType?.charAt(0) || 'A' }}
          </div>

          <div class="app-title-group">
            <Transition name="rename-slide" mode="out-in">
              <div v-if="renameMode" key="edit" class="rename-wrapper">
                <input
                  v-model="newName"
                  class="rename-input"
                  @keyup.enter="handleRename"
                  @keyup.escape="renameMode = false"
                  :placeholder="app.name"
                />
                <div class="rename-actions">
                  <button class="btn-sm" @click="handleRename">Save</button>
                  <button class="btn-sm secondary" @click="renameMode = false">Cancel</button>
                </div>
              </div>
              <div v-else key="display" class="rename-wrapper">
                <h1>{{ app.name }}</h1>
                <button class="rename-btn" @click="newName = app.name; renameMode = true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" style="vertical-align:middle;margin-right:4px"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg> Rename</button>
              </div>
            </Transition>
          </div>

          <div class="type-badge" :style="{ color: accentColors[app.appType], background: bgColors[app.appType] }">
            {{ app.appType }}
          </div>

          <button class="btn-primary" @click="openStudio">
            <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
              <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zm0 6a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2zm0 6a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2z"/>
            </svg>
            Open in Studio
          </button>
        </div>
      </header>

      <!-- Content Grid -->
      <div class="content-grid">
        <!-- Left Column: Info + Files -->
        <div class="left-col">
          <!-- Info Card -->
          <section class="card">
            <h2>App Info</h2>
            <dl class="info-list">
              <div class="info-row">
                <dt>Target Path</dt>
                <dd class="mono">{{ formatPath(app.targetPath) }}</dd>
              </div>
              <div class="info-row">
                <dt>Workspace</dt>
                <dd>{{ app.workspaceId ? app.workspaceId.slice(0, 8) + '…' : '—' }}</dd>
              </div>
              <div class="info-row">
                <dt>Conflict Action</dt>
                <dd>{{ app.targetPathConflictAction || '—' }}</dd>
              </div>
            </dl>
          </section>

          <!-- Generated Files -->
          <section class="card">
            <h2>Generated Files</h2>
            <div v-if="generatedFiles.length === 0" class="empty-card">
              <p>No files generated yet.</p>
              <p class="hint">Run the workflow in Studio to generate files.</p>
            </div>
            <ul v-else class="file-list">
              <li v-for="file in generatedFiles" :key="file.path" class="file-item">
                <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16" class="file-icon">
                  <path fill-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4z" clip-rule="evenodd"/>
                </svg>
                <div class="file-info">
                  <span class="file-path">{{ file.path }}</span>
                  <span class="file-desc">{{ file.description }}</span>
                </div>
              </li>
            </ul>
          </section>
        </div>

        <!-- Right Column: Actions + History -->
        <div class="right-col">
          <!-- Quick Actions -->
          <section class="card">
            <h2>Quick Actions</h2>
            <div class="action-list">
              <button class="action-btn" @click="openStudio">
                <svg viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
                  <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z"/>
                </svg>
                <div>
                  <strong>Open in Studio</strong>
                  <span>Edit the workflow graph</span>
                </div>
              </button>
              <button class="action-btn" @click="goToDashboard">
                <svg viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
                  <path d="M10.707 2.293a1 1 0 00-1.414 0l-7 7a1 1 0 001.414 1.414L4 10.414V17a1 1 0 001 1h2a1 1 0 001-1v-2a1 1 0 011-1h2a1 1 0 011 1v2a1 1 0 001 1h2a1 1 0 001-1v-6.586l.293.293a1 1 0 001.414-1.414l-7-7z"/>
                </svg>
                <div>
                  <strong>Back to Dashboard</strong>
                  <span>View all apps</span>
                </div>
              </button>
            </div>
          </section>

          <!-- Execution History -->
          <section class="card">
            <h2>Recent Activity</h2>
            <div v-if="history.length === 0" class="empty-card">
              <p>No executions yet.</p>
            </div>
            <ul v-else class="history-list">
              <li v-for="exec in history.slice(0, 5)" :key="exec.id" class="history-item">
                <span class="status-dot" :style="{ background: statusColor(exec.status) }" />
                <div class="history-info">
                  <span class="history-status">{{ exec.status }}</span>
                  <span class="history-time">{{ formatDate(exec.startTime) }}</span>
                </div>
                <span class="history-nodes">{{ exec.completedNodes }}/{{ exec.totalNodes }} nodes</span>
              </li>
            </ul>
          </section>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.app-dashboard {
  max-width: 960px;
  margin: 0 auto;
  padding: var(--space-8);
  min-height: 100vh;
  background: var(--bg-primary);
  color: var(--text-primary);
}

/* Loading / Error */
.state-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 40vh;
  gap: var(--space-4);
  color: var(--text-muted);
}

.spinner-lg {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-color);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-state p {
  color: var(--error);
  font-size: var(--text-base);
}

/* Header */
.dashboard-header {
  margin-bottom: var(--space-7);
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: var(--text-sm);
  cursor: pointer;
  padding: var(--space-1) 0;
  margin-bottom: var(--space-3);
  transition: color var(--transition);
}

.back-btn:hover {
  color: var(--accent);
}

.app-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.app-icon {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-lg);
  font-weight: 700;
  flex-shrink: 0;
}

.app-title-group {
  flex: 1;
  min-width: 0;
}

.app-title-group h1 {
  margin: 0;
  font-size: var(--text-2xl);
  font-weight: 700;
}

.rename-btn {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: var(--text-xs);
  cursor: pointer;
  padding: 0;
}

.rename-btn:hover {
  color: var(--accent);
}

/* Rename mode slide transition */
.rename-wrapper {
  min-height: 2lh;
}

.rename-slide-enter-active,
.rename-slide-leave-active {
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.rename-slide-enter-from {
  opacity: 0;
  transform: translateY(-6px);
}

.rename-slide-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

.rename-input {
  font-size: var(--text-lg);
  font-weight: 600;
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  width: 100%;
  max-width: 300px;
  box-sizing: border-box;
}

.rename-actions {
  display: flex;
  gap: var(--space-1);
  margin-top: var(--space-1);
}

.btn-sm {
  padding: var(--space-1) var(--space-2);
  font-size: var(--text-xs);
  border: 1px solid var(--accent);
  background: var(--accent);
  color: white;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.btn-sm.secondary {
  background: transparent;
  color: var(--text-primary);
  border-color: var(--border-color);
}

.type-badge {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-weight: 600;
}

/* No shared button styles — use global .btn-primary/.btn-secondary from App.vue */

/* Content Grid */
.content-grid {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: var(--space-6);
  align-items: start;
}

@media (max-width: 768px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

/* Cards */
.card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  margin-bottom: var(--space-4);
}

.card h2 {
  font-size: var(--text-sm);
  font-weight: 600;
  margin: 0 0 var(--space-4) 0;
  color: var(--text-primary);
}

/* Info List */
.info-list {
  margin: 0;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border-color);
}

.info-row:last-child {
  border-bottom: none;
}

.info-row dt {
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.info-row dd {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-primary);
}

.mono {
  font-family: var(--font-mono);
  font-size: var(--text-xs);
}

/* Files */
.empty-card {
  text-align: center;
  padding: var(--space-6);
  color: var(--text-muted);
}

.empty-card p {
  margin: 0;
  font-size: var(--text-sm);
}

.hint {
  font-size: var(--text-xs);
  margin-top: var(--space-1);
}

.file-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.file-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border-color);
}

.file-item:last-child {
  border-bottom: none;
}

.file-icon {
  color: var(--text-muted);
  margin-top: 0.15rem;
  flex-shrink: 0;
}

.file-info {
  flex: 1;
  min-width: 0;
}

.file-path {
  display: block;
  font-size: var(--text-sm);
  font-family: var(--font-mono);
  color: var(--text-primary);
  word-break: break-all;
}

.file-desc {
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

/* Actions */
.action-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.action-btn {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color var(--transition), background var(--transition);
  text-align: left;
  color: inherit;
  font: inherit;
  width: 100%;
}

.action-btn:hover {
  border-color: var(--accent);
  background: var(--bg-hover);
}

.action-btn svg {
  color: var(--accent);
  flex-shrink: 0;
}

.action-btn strong {
  display: block;
  font-size: var(--text-sm);
  font-weight: 600;
}

.action-btn span {
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

/* History */
.history-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.history-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border-color);
  font-size: var(--text-sm);
}

.history-item:last-child {
  border-bottom: none;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.history-info {
  flex: 1;
}

.history-status {
  display: block;
  font-weight: 500;
  color: var(--text-primary);
}

.history-time {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.history-nodes {
  color: var(--text-muted);
  font-size: var(--text-xs);
  white-space: nowrap;
}
</style>
