<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import AppCard from '@/components/app/AppCard.vue'
import AppModal from '@/components/ui/AppModal.vue'
import TemplateCard from '@/components/app/TemplateCard.vue'
import { appApi, schemaApi } from '@/services/api'
import { getTemplateById } from '@/templates'
import type { WorkflowSchema } from '@/types'

const router = useRouter()
const schemaStore = useSchemaStore()

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
const newAppName = ref('')
const newAppType = ref('CUSTOM')

// Conflict modal state
const showConflictModal = ref(false)
const pendingTemplate = ref<any>(null)
const conflictAction = ref<'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH'>('CONTINUE')
const customTargetPath = ref('')

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

const searchQuery = ref('')

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
  }))
})

const filteredApps = computed(() => {
  if (!searchQuery.value.trim()) return visibleApps.value
  const q = searchQuery.value.toLowerCase()
  return visibleApps.value.filter(app => app.name.toLowerCase().includes(q))
})

function continueDevelopment(app: any) {
  router.push(`/app/${app.id}`)
}

// Load apps on mount
onMounted(() => {
  schemaStore.loadSchemas()
})

/** After creating a schema from a template, push the template's nodes and edges into it.
 *  Returns the updated full schema, or undefined if template has no nodes to apply.
 */
async function applyTemplateToSchema(schemaId: string, templateId: string): Promise<WorkflowSchema | undefined> {
  const fullTemplate = getTemplateById(templateId)
  if (!fullTemplate || fullTemplate.defaultNodes.length === 0) return

  const schema = await schemaApi.getSchema(schemaId)
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

async function resolveConflict() {
  if (!pendingTemplate.value) return
  try {
    const template = pendingTemplate.value
    const appInfo = await appApi.createApp({
      name: template.name,
      appType: template.appType,
      description: template.description,
      conflictAction: conflictAction.value,
      customTargetPath: conflictAction.value === 'CHANGE_PATH' ? customTargetPath.value : undefined
    })
    if (appInfo) {
      // Push template nodes/edges if this template has a definition
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

async function createBlankApp() {
  if (!newAppName.value.trim()) return
  try {
    // For CUSTOM app types, use the original schemaStore.createSchema
    if (newAppType.value === 'CUSTOM') {
      const schema = await schemaStore.createSchema(newAppName.value, newAppType.value)
      showNewAppModal.value = false
      newAppName.value = ''
      if (schema) {
        router.push(`/app/${schema.id}`)
      }
    } else {
      // For non-CUSTOM types, use appApi with conflict check
      const pathCheck = await appApi.checkTargetPath(newAppName.value, newAppType.value)
      if (pathCheck.exists) {
        // Show conflict modal — create a pseudo-template for the pending app
        showConflictModal.value = true
        pendingTemplate.value = {
          id: 'blank',
          name: newAppName.value,
          appType: newAppType.value,
          description: ''
        }
        return
      }
      // No conflict — create directly
      const appInfo = await appApi.createApp({
        name: newAppName.value,
        appType: newAppType.value,
        description: '',
      })
      if (appInfo) {
        schemaStore.schemas.push(appInfo as unknown as WorkflowSchema)
        showNewAppModal.value = false
        newAppName.value = ''
        router.push(`/app/${appInfo.id}`)
      }
    }
  } catch (error) {
    console.error('Failed to create blank app:', error)
  }
}
</script>

<template>
  <div class="dashboard">
    <header class="dashboard-header">
      <div>
        <h1>Welcome to Axolotl Studio</h1>
        <p class="subtitle">Build AI-powered apps visually</p>
      </div>
      <button class="btn-primary" @click="showNewAppModal = true">
        <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z"/></svg>
        New App
      </button>
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
      <h2>My Apps</h2>
      <div v-if="schemaStore.schemas.length === 0" class="empty-state">
        <p>No apps yet. Create your first app!</p>
      </div>
      <div v-else-if="filteredApps.length === 0" class="empty-state">
        <p>No apps matching "{{ searchQuery }}"</p>
      </div>
      <div v-else class="apps-grid">
        <AppCard
          v-for="app in filteredApps"
          :key="app.id"
          :app="app"
          @click="openApp(app.id)"
          @delete="promptDeleteApp(app)"
        />
      </div>
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

    <!-- New App Modal -->
    <AppModal v-model="showNewAppModal" title="Create New App">
      <div class="form-group">
        <label>App Name</label>
        <input
          v-model="newAppName"
          type="text"
          placeholder="My Awesome App"
          class="input"
          @keyup.enter="createBlankApp"
        />
      </div>
      <div class="form-group">
        <label>App Type</label>
        <select v-model="newAppType" class="input">
          <option value="CUSTOM">Custom</option>
          <option value="CHAT">Chat Bot</option>
          <option value="ANALYZER">Analyzer</option>
          <option value="GENERATOR">Generator</option>
          <option value="EMAIL">Email Agent</option>
          <option value="GAME">Game</option>
        </select>
      </div>
      <div class="modal-actions">
        <button class="btn-secondary" @click="showNewAppModal = false">Cancel</button>
        <button class="btn-primary" @click="createBlankApp" :disabled="!newAppName.trim()">Create</button>
      </div>
    </AppModal>

    <!-- Conflict Resolution Modal -->
    <AppModal v-model="showConflictModal" title="Directory Conflict">
      <p>The target path already exists for "{{ pendingTemplate?.name }}". Choose how to proceed:</p>
      <div class="conflict-options">
        <label class="conflict-option" :class="{ selected: conflictAction === 'CONTINUE' }">
          <input type="radio" v-model="conflictAction" value="CONTINUE" />
          <div class="option-content">
            <strong>Continue</strong>
            <span>Keep existing files and append new ones</span>
          </div>
        </label>
        <label class="conflict-option" :class="{ selected: conflictAction === 'OVERWRITE' }">
          <input type="radio" v-model="conflictAction" value="OVERWRITE" />
          <div class="option-content">
            <strong>Overwrite</strong>
            <span>Delete existing directory and start fresh</span>
          </div>
        </label>
        <label class="conflict-option" :class="{ selected: conflictAction === 'CHANGE_PATH' }">
          <input type="radio" v-model="conflictAction" value="CHANGE_PATH" />
          <div class="option-content">
            <strong>Change Path</strong>
            <span>Specify a different target path</span>
          </div>
        </label>
      </div>
      <div v-if="conflictAction === 'CHANGE_PATH'" class="form-group">
        <label>Custom Path</label>
        <input v-model="customTargetPath" type="text" placeholder="/Users/.../Axolotl/my-app/" class="input" />
      </div>
      <div class="modal-actions">
        <button class="btn-secondary" @click="showConflictModal = false">Cancel</button>
        <button class="btn-primary" @click="resolveConflict">
          {{ conflictAction === 'CONTINUE' ? 'Continue' : conflictAction === 'OVERWRITE' ? 'Overwrite' : 'Change Path' }}
        </button>
      </div>
    </AppModal>

    <!-- Delete Confirmation Modal -->
    <AppModal v-model="showDeleteModal" title="Delete App">
      <p>Delete "<strong>{{ deleteTarget?.name }}</strong>"? This cannot be undone.</p>
      <div class="modal-actions">
        <button class="btn-secondary" @click="showDeleteModal = false">Cancel</button>
        <button class="btn-danger" @click="confirmDeleteApp">Delete</button>
      </div>
    </AppModal>
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

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition), transform 0.1s;
}

.btn-primary:hover {
  background: var(--accent-hover);
  transform: translateY(-1px);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.btn-secondary {
  padding: var(--space-3) var(--space-5);
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--transition);
}

.btn-secondary:hover {
  background: var(--bg-hover);
}

.btn-danger {
  padding: var(--space-3) var(--space-5);
  background: var(--error);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition);
}

.btn-danger:hover {
  background: var(--error-hover);
}

.icon {
  width: var(--icon-sm);
  height: var(--icon-sm);
}

h2 {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: var(--space-4);
}

.apps-section,
.templates-section {
  margin-bottom: var(--space-7);
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
</style>
