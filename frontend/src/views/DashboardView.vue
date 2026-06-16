<script setup lang="ts">
import { ref, computed, watch, onMounted, defineOptions } from 'vue'
import { useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import { useSettingsStore } from '@/stores/settingsStore'
import AppCard from '@/components/app/AppCard.vue'
import AppModal from '@/components/ui/AppModal.vue'

import QuickStartDialog from '@/components/studio/QuickStartDialog.vue'
import NewAppModal from '@/components/studio/NewAppModal.vue'
import ConflictModal from '@/components/studio/ConflictModal.vue'
import TemplateCard from '@/components/app/TemplateCard.vue'
import ProjectsFolderPrompt from '@/components/settings/ProjectsFolderPrompt.vue'
import { appApi, schemaApi } from '@/services/api'
import { getTemplateById } from '@/templates'
import type { WorkflowSchema } from '@/types'

defineOptions({ name: 'DashboardView' })

const router = useRouter()
const schemaStore = useSchemaStore()
const settingsStore = useSettingsStore()

const showProjectsPrompt = ref(false)

const templates = ref([
  {
    id: 'template-chat',
    name: 'Chat Bot',
    description: 'AI chatbot with conversation memory',
    appType: 'CHAT',
    icon: 'M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z'
  },
  {
    id: 'template-doc',
    name: 'Document Analyzer',
    description: 'Analyze documents with AI extraction',
    appType: 'ANALYZER',
    icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z'
  },
  {
    id: 'template-content',
    name: 'Content Generator',
    description: 'Generate articles, posts, and marketing copy',
    appType: 'GENERATOR',
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z'
  },
  {
    id: 'template-email',
    name: 'Email Agent',
    description: 'Smart email drafting and reply assistant',
    appType: 'EMAIL',
    icon: 'M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z'
  },
  {
    id: 'template-data',
    name: 'Data Extractor',
    description: 'Extract structured data from text',
    appType: 'ANALYZER',
    icon: 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4'
  },
  {
    id: 'template-sokoban',
    name: 'Sokoban Game',
    description: 'Generate a playable Sokoban puzzle game',
    appType: 'GAME',
    icon: 'M6 12h4m4 0h4M6 16h4m4 0h4M6 8h12M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6z'
  },
  {
    id: 'template-blank',
    name: 'Blank App',
    description: 'Start from scratch with an empty canvas',
    appType: 'CUSTOM',
    icon: 'M12 4v16m8-8H4'
  }
])

const showNewAppModal = ref(false)

// Conflict modal state
const showConflictModal = ref(false)
const pendingTemplate = ref<any>(null)

const generatedApps = computed(() => {
  const seen = new Map<string, typeof schemaStore.schemas[0]>()
  for (const s of schemaStore.schemas) {
    if (s.targetPath && s.appType && s.appType !== 'CUSTOM') {
      seen.set(s.targetPath, s) // last wins (most recent)
    }
  }
  return Array.from(seen.values())
})

const showDeleteModal = ref(false)
const deleteTarget = ref<any>(null)

// Quick Start dialog
const showQuickStart = ref(false)

function openQuickStart() {
  showQuickStart.value = true
}

function onQuickStartCreated(schema: any) {
  showQuickStart.value = false
  if (schema?.id) {
    // Push into store so the Dashboard list stays fresh without re-fetch
    if (!schemaStore.schemas.find(s => s.id === schema.id)) {
      schemaStore.schemas.push(schema)
    }
    trackRecent(schema.id)
    router.push(`/app/${schema.id}`)
  }
}

const searchQuery = ref('')

// Recently opened tracking (localStorage, max 5)
const RECENT_STORAGE_KEY = 'axolotl_recent_apps'
const recentAppIds = ref<string[]>(loadRecentIds())

function loadRecentIds(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch { return [] }
}

function saveRecentIds(ids: string[]) {
  try {
    localStorage.setItem(RECENT_STORAGE_KEY, JSON.stringify(ids.slice(0, 5)))
  } catch { /* storage full — ignore */ }
}

function trackRecent(id: string) {
  const ids = recentAppIds.value.filter(i => i !== id)
  ids.unshift(id)
  recentAppIds.value = ids.slice(0, 5)
  saveRecentIds(recentAppIds.value)
}

const allAppsCollapsed = ref(false)

// Display / filtering / sorting options
const sortMode = ref<'updated' | 'name' | 'lastRun'>('updated')
const filterType = ref<string>('ALL')
const showTests = ref(false)

// Batch delete
const selectedForDelete = ref<Set<string>>(new Set())
const isDeleting = ref(false)

// Test schemas list
const testSchemas = ref<WorkflowSchema[]>([])
async function loadTestSchemas() {
  try {
    testSchemas.value = await schemaApi.getTestSchemas()
  } catch (e) {
    console.error('Failed to load test schemas:', e)
  }
}

// Type filter options
const appTypes = computed(() => {
  const types = new Set(visibleApps.value.map(a => a.appType || 'CUSTOM'))
  return ['ALL', ...Array.from(types).sort()]
})

// Filtered + sorted apps
const sortedFilteredApps = computed(() => {
  let apps = visibleApps.value

  // Filter by type
  if (filterType.value !== 'ALL') {
    apps = apps.filter(a => (a.appType || 'CUSTOM') === filterType.value)
  }

  // Filter test schemas
  if (!showTests.value) {
    const testNames = new Set(testSchemas.value.map(s => s.id))
    apps = apps.filter(a => !testNames.has(a.id))
  }

  // Sort
  const sorted = [...apps]
  switch (sortMode.value) {
    case 'updated':
      sorted.sort(byUpdatedAtDesc)
      break
    case 'name':
      sorted.sort((a, b) => a.name.localeCompare(b.name))
      break
    case 'lastRun':
      sorted.sort((a, b) => {
        const aDate = a.lastRunAt || a.updatedAt || ''
        const bDate = b.lastRunAt || b.updatedAt || ''
        return bDate.localeCompare(aDate)
      })
      break
  }
  return sorted
})

// Sort helper: updatedAt desc, fallback to createdAt
function byUpdatedAtDesc(a: any, b: any): number {
  const aDate = a.updatedAt || a.createdAt || ''
  const bDate = b.updatedAt || b.createdAt || ''
  return bDate.localeCompare(aDate)
}

// Merge generated app metadata into all schemas for unified grid
const visibleApps = computed(() => {
  const genMap = new Map<string, { targetPath: string; status: 'active' | 'idle' }>()
  for (const g of generatedApps.value) {
    if (g.targetPath) {
      genMap.set(g.id, { targetPath: g.targetPath, status: 'active' })
    }
  }
  return schemaStore.schemas.map(s => ({
    ...s,
    isGenerated: genMap.has(s.id),
    targetPath: genMap.get(s.id)?.targetPath || s.targetPath,
    status: genMap.has(s.id) ? ('active' as const) : ('idle' as const),
  })).sort(byUpdatedAtDesc)
})

const filteredApps = computed(() => {
  if (!searchQuery.value.trim()) return visibleApps.value
  const q = searchQuery.value.toLowerCase()
  return visibleApps.value.filter(app => app.name.toLowerCase().includes(q))
})

const recentApps = computed(() => {
  const ids = recentAppIds.value
  const map = new Map(visibleApps.value.map(a => [a.id, a]))
  return ids.map(id => map.get(id)).filter((x): x is NonNullable<typeof x> => x != null).slice(0, 5)
})

const otherApps = computed(() => {
  const recentIds = new Set(recentApps.value.map(a => a.id))
  return visibleApps.value.filter(a => !recentIds.has(a.id))
})

function continueDevelopment(app: any) {
  router.push(`/app/${app.id}`)
}

// Load apps on mount
onMounted(async () => {
  schemaStore.loadSchemas()
  loadGroups()
  await settingsStore.loadProjectsFolder()
  if (!settingsStore.projectsFolder) {
    showProjectsPrompt.value = true
  }
})

/** After creating a schema from a template, push the template's nodes and edges into it.
 *  Returns the updated full schema, or undefined if template has no nodes to apply.
 *  Retries getSchema up to 3 times with backoff to handle Neo4j async replication delay.
 */
async function applyTemplateToSchema(schemaId: string, templateId: string): Promise<WorkflowSchema | undefined> {
  const fullTemplate = getTemplateById(templateId)
  if (!fullTemplate || fullTemplate.defaultNodes.length === 0) return

  // Retry getSchema to handle Neo4j async replication delay
  let schema: WorkflowSchema | null = null
  for (let attempt = 0; attempt < 3; attempt++) {
    schema = await schemaApi.getSchema(schemaId)
    if (schema) break
    await new Promise(r => setTimeout(r, 200 * (attempt + 1)))
  }
  if (!schema) {
    console.error('Failed to fetch schema after retries:', schemaId)
    return
  }

  schema.nodes = fullTemplate.defaultNodes.map(n => ({
    id: n.id,
    type: n.type as any,
    name: n.name,
    position: { x: n.position.x, y: n.position.y },
    data: { ...n.data } as any,
  }))
  schema.edges = fullTemplate.defaultEdges.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    type: 'data' as const,
  }))
  return await schemaApi.updateSchema(schemaId, schema)
}

function openApp(id: string) {
  trackRecent(id)
  router.push(`/app/${id}`)
}

async function createFromTemplate(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template) return

  try {
    // For non-CUSTOM appTypes, check for path conflict first
    if (template.appType !== 'CUSTOM') {
      const pathCheck = await appApi.checkTargetPath(template.name, template.appType)
      if (pathCheck.exists) {
        // Show conflict modal
        showConflictModal.value = true
        pendingTemplate.value = template
        return
      }
    }
    // No conflict — create directly
    const appInfo = await appApi.createApp({
      name: template.name,
      appType: template.appType,
      description: template.description,
      templateId: template.id,
    })
    if (!appInfo) return

    // Push template nodes/edges into the schema (returns full WorkflowSchema)
    const updated = await applyTemplateToSchema(appInfo.id, templateId)
    if (updated) {
      schemaStore.schemas.push(updated)
    } else {
      // Template has no nodes (e.g. blank); still need to add to store
      schemaStore.schemas.push(appInfo as unknown as WorkflowSchema)
    }
    router.push(`/app/${appInfo.id}`)
  } catch (error) {
    console.error('Failed to create app from template:', error)
  }
}

async function onAppCreated(schemaId: string) {
  trackRecent(schemaId)
  router.push(`/app/${schemaId}`)
}

async function onConflictResolve(action: 'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH', customPath?: string) {
  if (!pendingTemplate.value) return
  try {
    const template = pendingTemplate.value
    const appInfo = await appApi.createApp({
      name: template.name,
      appType: template.appType,
      description: template.description,
      conflictAction: action,
      customTargetPath: action === 'CHANGE_PATH' ? customPath : undefined,
      templateId: template.id,
    })
    if (appInfo) {
      const updated = await applyTemplateToSchema(appInfo.id, template.id)
      if (updated) {
        schemaStore.schemas.push(updated)
      } else {
        schemaStore.schemas.push(appInfo as unknown as WorkflowSchema)
      }
      showConflictModal.value = false
      pendingTemplate.value = null
      router.push(`/app/${appInfo.id}`)
    }
  } catch (error) {
    console.error('Failed to resolve conflict:', error)
  }
}

function promptDeleteApp(app: any) {
  deleteTarget.value = app
  showDeleteModal.value = true
}

async function confirmDeleteApp() {
  if (!deleteTarget.value) return
  await schemaStore.deleteSchema(deleteTarget.value.id)
  showDeleteModal.value = false
  deleteTarget.value = null
}

// Project grouping
const schemaGroups = ref<Record<string, WorkflowSchema[]>>({})
const groupExpanded = ref<Record<string, boolean>>({})

// All known group names (for the AppCard dropdown), sorted
const groupNames = computed(() => {
  return Object.keys(schemaGroups.value).filter(g => g !== '').sort()
})

async function loadGroups() {
  try {
    schemaGroups.value = await schemaApi.getSchemaGroups()
  } catch (e) {
    console.error('Failed to load schema groups:', e)
  }
}

/** Handle group selection from AppCard dropdown */
async function onGroupSelect(schema: WorkflowSchema, groupName: string | null) {
  try {
    const updated = await schemaApi.updateSchema(schema.id, { ...schema, projectGroup: groupName || '' })
    // Update local store
    const idx = schemaStore.schemas.findIndex(s => s.id === schema.id)
    if (idx >= 0) {
      schemaStore.schemas[idx] = updated
    }
    await loadGroups()
  } catch (e) {
    console.error('Failed to update group:', e)
  }
}

const groupSortMode = ref<'alpha' | 'recency'>('recency')

const sortedGroupNames = computed(() => {
  const groups = Object.keys(schemaGroups.value)
  const emptyIdx = groups.indexOf('')
  const named = groups.filter(g => g !== '')

  let sorted: string[]
  if (groupSortMode.value === 'recency') {
    // Sort groups by most recent app in each group
    sorted = [...named].sort((a, b) => {
      const groupA = schemaGroups.value[a] || []
      const groupB = schemaGroups.value[b] || []
      const aLatest = Math.max(
        ...groupA.map(s => new Date(s.updatedAt || s.createdAt || 0).getTime())
      )
      const bLatest = Math.max(
        ...groupB.map(s => new Date(s.updatedAt || s.createdAt || 0).getTime())
      )
      return bLatest - aLatest
    })
  } else {
    sorted = [...named].sort((a, b) => a.localeCompare(b))
  }

  if (emptyIdx >= 0) {
    sorted.push('')
  }
  return sorted
})

function selectAllTests() {
  selectedForDelete.value = new Set(testSchemas.value.map(s => s.id))
}

function clearSelection() {
  selectedForDelete.value = new Set()
}

const showBatchDeleteModal = ref(false)

function batchDeleteSelected() {
  if (selectedForDelete.value.size === 0) return
  showBatchDeleteModal.value = true
}

async function confirmBatchDelete() {
  showBatchDeleteModal.value = false
  isDeleting.value = true
  try {
    await schemaApi.batchDeleteSchemas(Array.from(selectedForDelete.value))
    schemaStore.schemas = schemaStore.schemas.filter(s => !selectedForDelete.value.has(s.id))
    selectedForDelete.value = new Set()
    await loadGroups()
    await loadTestSchemas()
  } catch (e) {
    console.error('Batch delete failed:', e)
  } finally {
    isDeleting.value = false
  }
}

// ── Drag-n-drop between groups ──
const dragSchemaId = ref<string | null>(null)

function onDragStart(e: DragEvent, schemaId: string) {
  dragSchemaId.value = schemaId
  e.dataTransfer?.setData('text/plain', schemaId)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

async function onDrop(e: DragEvent, targetGroup: string) {
  e.preventDefault()
  const schemaId = e.dataTransfer?.getData('text/plain')
  if (!schemaId) return
  const currentSchema = visibleApps.value.find(a => a.id === schemaId)
  if (!currentSchema) return
  const currentGroup = currentSchema.projectGroup || ''
  if (currentGroup === targetGroup) {
    dragSchemaId.value = null
    return
  }
  try {
    await schemaApi.updateSchema(schemaId, { projectGroup: targetGroup || '' } as any)
    await loadGroups()
  } catch (e) {
    console.error('Failed to move schema:', e)
  }
  dragSchemaId.value = null
}

// schemas in a given group, filtered by search, sorted by updatedAt desc
function groupSchemas(group: string) {
  const all = schemaGroups.value[group] || []
  const filtered = !searchQuery.value.trim() ? all : all.filter(s =>
    s.name.toLowerCase().includes(searchQuery.value.toLowerCase()),
  )
  return [...filtered].sort((a, b) => {
    const aTime = a.updatedAt ? new Date(a.updatedAt).getTime() : 0
    const bTime = b.updatedAt ? new Date(b.updatedAt).getTime() : 0
    return bTime - aTime
  })
}

/** schemas not in any group AND not recent */
const otherSchemas = computed(() => {
  if (!schemaGroups.value['']) return []
  const recentIds = new Set(recentApps.value.map(a => a.id))
  return schemaGroups.value[''].filter(a => !recentIds.has(a.id))
})



const importInput = ref<HTMLInputElement | null>(null)

function triggerImport() {
  importInput.value?.click()
}

async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const schema = JSON.parse(text) as WorkflowSchema
    const created = await schemaApi.importSchema(schema)
    if (created) {
      if (!schemaStore.schemas.find(s => s.id === created.id)) {
        schemaStore.schemas.push(created)
      }
      router.push(`/app/${created.id}`)
    }
  } catch (err) {
    console.error('Import failed:', err)
  }
  input.value = ''
}
</script>

<template>
  <div class="dashboard">
    <header class="dashboard-header">
      <div>
        <h1>Welcome to Axolotl Studio</h1>
        <p class="subtitle">Build AI-powered apps visually</p>
      </div>
      <div class="header-actions">
        <button class="btn-secondary header-btn" @click="openQuickStart">
          <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M12.395 2.553a1 1 0 00-1.45-.385c-.345.23-.614.558-.822.88-.214.33-.403.713-.57 1.116-.334.804-.614 1.768-.84 2.734a31.365 31.365 0 00-.613 3.58 2.64 2.64 0 01-.945-1.067c-.328-.68-.398-1.534-.398-2.654A1 1 0 005.05 6.05 6.981 6.981 0 003 11a7 7 0 1011.95-4.95c-.592-.591-.98-.985-1.348-1.467-.363-.476-.724-1.063-1.207-2.03zM12.12 15.12A3 3 0 017 13s.879.5 2.5.5c0-1 .5-4 1.25-4.5.5 1 .786 1.293 1.371 1.879A2.99 2.99 0 0113 13a2.99 2.99 0 01-.879 2.121z"/></svg>
          Quick Start
        </button>
        <button class="btn-primary" @click="showNewAppModal = true">
          <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z"/></svg>
          New App
        </button>
        <button class="btn-secondary header-btn" @click="triggerImport">
          <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 3a1 1 0 011 1v5.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 111.414-1.414L9 9.586V4a1 1 0 011-1z"/><path d="M3 15a1 1 0 011 1v1a1 1 0 001 1h10a1 1 0 001-1v-1a1 1 0 112 0v1a3 3 0 01-3 3H5a3 3 0 01-3-3v-1a1 1 0 011-1z"/></svg>
          Import
        </button>
        <input
          ref="importInput"
          type="file"
          accept=".json"
          style="display: none"
          @change="onImportFile"
        />
      </div>
    </header>

    <!-- Search Bar -->
    <div class="search-bar">
      <svg class="search-icon" viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
        <path fill-rule="evenodd" d="M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z" clip-rule="evenodd"/>
      </svg>
      <input
        v-model="searchQuery"
        type="text"
        class="search-input"
        placeholder="Search apps..."
      />
    </div>

    <!-- My Apps Grid -->
    <section class="apps-section">
      <div class="apps-header">
        <h2>My Apps</h2>
        <span class="app-count">{{ sortedFilteredApps.length }} apps</span>
      </div>
      <div class="apps-controls">
        <div class="control-group">
          <select v-model="sortMode" class="control-select" title="Sort order">
            <option value="updated">Last Updated</option>
            <option value="name">Name A-Z</option>
            <option value="lastRun">Last Run</option>
          </select>
          <select v-model="filterType" class="control-select" title="Filter by type">
            <option value="ALL">All Types</option>
            <template v-for="t in appTypes" :key="t">
              <option v-if="t !== 'ALL'" :value="t">{{ t }}</option>
            </template>
          </select>
        </div>
        <div class="control-group">
          <button class="control-btn" :class="{ active: !showTests }" @click="showTests = false; clearSelection()" title="Hide tests">
            Apps
          </button>
          <button v-if="testSchemas.length > 0" class="control-btn" :class="{ active: showTests }" @click="showTests = true; clearSelection()" title="Show tests">
            {{ testSchemas.length }} tests
          </button>
          <button v-if="showTests && testSchemas.length > 0" class="control-btn danger" @click="selectAllTests(); batchDeleteSelected()" :disabled="isDeleting">
            {{ isDeleting ? 'Deleting...' : 'Delete all' }}
          </button>
        </div>
      </div>
      <div v-if="schemaStore.schemas.length === 0" class="empty-state">
        <p>No apps yet. Create your first app!</p>
        <button class="empty-cta-btn" @click="showQuickStart = true">Quick Start</button>
      </div>
      <div v-else-if="filteredApps.length === 0" class="empty-state">
        <p>No apps matching "{{ searchQuery }}"</p>
      </div>
      <template v-else>
        <!-- During search: flat results, no sections -->
        <template v-if="searchQuery.trim()">
          <div class="apps-grid">
            <AppCard
              v-for="app in filteredApps"
              :key="app.id"
              :app="app"
              @click="openApp(app.id)"
              @delete="promptDeleteApp(app)"
            />
          </div>
        </template>
        <!-- Normal view: sections -->
        <template v-else>
          <div class="apps-subsection">
            <div class="subsection-header" @click="allAppsCollapsed = !allAppsCollapsed">
              <div class="subsection-title">
                <svg class="chevron" :class="{ rotated: !allAppsCollapsed }" viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
                  <path d="M7 7l5 5-5 5" stroke="currentColor" stroke-width="2" fill="none"/>
                </svg>
                <h3>Recent</h3>
              </div>
              <span class="subsection-count">{{ Math.min(5, sortedFilteredApps.length) }}</span>
            </div>
            <div v-show="!allAppsCollapsed" class="apps-grid">
              <AppCard
                v-for="app in sortedFilteredApps.slice(0, 5)"
                :key="app.id"
                :app="app"
                :groups="groupNames"
                @click="openApp(app.id)"
                @delete="promptDeleteApp(app)"
                @group-select="onGroupSelect(app, $event)"
              />
            </div>
          </div>
          <div class="subsection-header-row">
            <h3>Groups</h3>
            <select v-model="groupSortMode" class="control-select small" title="Sort groups">
              <option value="alpha">A-Z</option>
              <option value="recency">Recent</option>
            </select>
          </div>
          <!-- Project Groups -->
          <div
            v-for="group in sortedGroupNames"
            :key="group"
            class="apps-subsection"
          >
            <div
              class="subsection-header"
              @click="groupExpanded[group] = !groupExpanded[group]"
            >
              <div class="subsection-title">
                <svg class="chevron" :class="{ rotated: groupExpanded[group] }" viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
                  <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clip-rule="evenodd"/>
                </svg>
                <h3>{{ group || 'Other' }}</h3>
                <span v-if="group" class="group-pill">{{ group }}</span>
              </div>
              <span class="subsection-count">{{ (schemaGroups[group] || []).length }}</span>
            </div>
            <div v-show="groupExpanded[group] === true" class="apps-grid" @dragover.prevent @drop="onDrop($event, group)">
              <AppCard
                v-for="app in groupSchemas(group)"
                :key="app.id"
                :app="app"
                :groups="groupNames"
                draggable="true"
                @click="openApp(app.id)"
                @delete="promptDeleteApp(app)"
                @group-select="onGroupSelect(app, $event)"
                @dragstart="onDragStart($event, app.id)"
              />
            </div>
          </div>
        </template>
      </template>
    </section>

    <!-- Templates Section -->
    <section class="templates-section">
      <h2>Start from a Template</h2>
      <div class="templates-grid">
        <TemplateCard
          v-for="template in templates"
          :key="template.id"
          :template="template"
          @select="createFromTemplate(template.id)"
        />
      </div>
    </section>

    <NewAppModal v-model="showNewAppModal" @created="onAppCreated" />

    <ConflictModal
      v-model="showConflictModal"
      :template="pendingTemplate"
      @resolve="onConflictResolve"
    />

    <!-- Delete Confirmation Modal -->
    <AppModal v-model="showDeleteModal" title="Delete App">
      <p>Delete "<strong>{{ deleteTarget?.name }}</strong>"? This cannot be undone.</p>
      <div class="modal-actions">
        <button class="btn-secondary" @click="showDeleteModal = false">Cancel</button>
        <button class="btn-danger" @click="confirmDeleteApp">Delete</button>
      </div>
    </AppModal>

    <!-- Batch Delete Confirmation Modal -->
    <AppModal v-model="showBatchDeleteModal" title="Delete Schemas">
      <p>Delete {{ selectedForDelete.size }} schemas? This cannot be undone.</p>
      <div class="modal-actions">
        <button class="btn-secondary" @click="showBatchDeleteModal = false">Cancel</button>
        <button class="btn-danger" @click="confirmBatchDelete" :disabled="isDeleting">
          {{ isDeleting ? 'Deleting...' : 'Delete' }}
        </button>
      </div>
    </AppModal>

    <QuickStartDialog
      :visible="showQuickStart"
      app-id=""
      @add-to-canvas="onQuickStartCreated"
      @close="showQuickStart = false"
    />

    <ProjectsFolderPrompt
      :visible="showProjectsPrompt"
      @done="showProjectsPrompt = false"
    />
  </div>
</template>

<style scoped>
.dashboard {
  padding: var(--space-8);
  max-width: 1200px;
  margin: 0 auto;
  min-height: 100vh;
  background: var(--bg-primary);
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-7);
  flex-wrap: wrap;
  gap: var(--space-3);
}

.header-actions {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.header-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-weight: 500;
}

.dashboard-header h1 {
  font-size: var(--text-2xl);
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.subtitle {
  color: var(--text-secondary);
  margin: var(--space-1) 0 0 0;
  font-size: var(--text-sm);
}

.header-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

h2 {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: var(--space-4);
}

.apps-section {
  margin-bottom: var(--space-7);
}

.apps-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.apps-section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.apps-section-title h2 {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.apps-count {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
  background: var(--bg-secondary);
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--border-color);
}

.sort-indicator {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-weight: 500;
}

.group-pill {
  font-size: 0.7rem;
  background: var(--accent-secondary);
  color: var(--text-on-accent, white);
  padding: 1px 8px;
  border-radius: 99px;
  margin-left: var(--space-2);
  opacity: 0.7;
}

.apps-subsection {
  margin-bottom: var(--space-4);
}

.subsection-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-1);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--transition);
  user-select: none;
  margin-bottom: var(--space-3);
}

.subsection-header:hover {
  background: var(--bg-hover);
}

.subsection-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.subsection-title h3 {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.chevron {
  color: var(--text-muted);
  transition: transform var(--transition);
  flex-shrink: 0;
}

.chevron.rotated {
  transform: rotate(0deg);
}

.chevron:not(.rotated) {
  transform: rotate(-90deg);
}

.subsection-count {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-muted);
  background: var(--bg-secondary);
  padding: 2px 10px;
  border-radius: 999px;
  border: 1px solid var(--border-color);
}

.apps-grid,
.templates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--space-4);
}

.empty-state {
  padding: var(--space-7);
  text-align: center;
  color: var(--text-muted);
  background: var(--bg-secondary);
  border-radius: var(--radius-md);
  border: 2px dashed var(--border-color);
}

.empty-cta-btn {
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-4);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: opacity var(--transition);
}
.empty-cta-btn:hover { opacity: 0.9; }

.search-bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
  padding: var(--space-3) var(--space-4);
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  transition: border-color var(--transition);
}

.search-bar:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.search-icon {
  width: var(--icon-sm);
  height: var(--icon-sm);
  color: var(--text-muted);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--text-muted);
}

.form-group {
  margin-bottom: var(--space-4);
}

.form-group label {
  display: block;
  margin-bottom: var(--space-1);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-secondary);
}

.input {
  width: 100%;
  padding: var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
}

.input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

select.input {
  cursor: pointer;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-5);
}

/* Conflict modal styles */
.conflict-options {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin: var(--space-4) 0;
}

.conflict-option {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-4);
  border: 2px solid var(--border-color);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition);
}

.conflict-option:hover {
  border-color: var(--accent-hover);
  background: var(--bg-hover);
}

.conflict-option.selected {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.conflict-option input[type="radio"] {
  margin-top: 0.125rem;
  cursor: pointer;
}

.option-content {
  flex: 1;
}

.option-content strong {
  display: block;
  font-size: var(--text-base);
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.option-content span {
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

/* New controls for apps filtering/sorting */
.apps-header {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}
.apps-header h2 { margin: 0; }
.app-count {
  font-size: 0.85rem;
  color: var(--text-muted);
}
.apps-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
  gap: var(--space-2);
}
.control-group {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}
.control-select {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: 0.8rem;
  cursor: pointer;
}
.control-select.small { padding: 2px 6px; font-size: 0.75rem; }
.control-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.15s;
}
.control-btn:hover { border-color: var(--accent); color: var(--text-primary); }
.control-btn.active { background: var(--accent); color: white; border-color: var(--accent); }
.control-btn.danger { border-color: var(--danger); color: var(--danger); }
.control-btn.danger:hover { background: var(--danger); color: white; }
.control-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.subsection-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.subsection-header-row h3 { margin: 0; }
</style>
