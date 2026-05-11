<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import AppCard from '@/components/app/AppCard.vue'
import TemplateCard from '@/components/app/TemplateCard.vue'

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

// Load apps on mount
onMounted(() => {
  schemaStore.loadSchemas()
})

function openApp(id: string) {
  router.push(`/app/${id}`)
}

async function createFromTemplate(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template) return

  try {
    const schema = await schemaStore.createSchema(template.name)
    if (schema) {
      router.push(`/app/${schema.id}`)
    }
  } catch (error) {
    console.error('Failed to create schema from template:', error)
  }
}

async function createBlankApp() {
  if (!newAppName.value.trim()) return
  try {
    const schema = await schemaStore.createSchema(newAppName.value)
    showNewAppModal.value = false
    newAppName.value = ''
    if (schema) {
      router.push(`/app/${schema.id}`)
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

    <!-- My Apps Grid -->
    <section class="apps-section">
      <h2>My Apps</h2>
      <div v-if="schemaStore.schemas.length === 0" class="empty-state">
        <p>No apps yet. Create your first app!</p>
      </div>
      <div v-else class="apps-grid">
        <AppCard
          v-for="app in schemaStore.schemas"
          :key="app.id"
          :app="app"
          @click="openApp(app.id)"
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
    <div v-if="showNewAppModal" class="modal-overlay" @click.self="showNewAppModal = false">
      <div class="modal">
        <h3>Create New App</h3>
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
          </select>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showNewAppModal = false">Cancel</button>
          <button class="btn-primary" @click="createBlankApp" :disabled="!newAppName.trim()">Create</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 2rem;
  max-width: 1200px;
  margin: 0 auto;
  min-height: 100vh;
  background: var(--bg-primary);
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2.5rem;
}

.dashboard-header h1 {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.subtitle {
  color: var(--text-secondary);
  margin: 0.25rem 0 0 0;
  font-size: 0.95rem;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1.25rem;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s, transform 0.1s;
}

.btn-primary:hover {
  background: var(--accent-light);
  transform: translateY(-1px);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.btn-secondary {
  padding: 0.625rem 1.25rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.9rem;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-secondary:hover {
  background: var(--bg-hover);
}

.icon {
  width: 18px;
  height: 18px;
}

h2 {
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 1rem;
}

.apps-section,
.templates-section {
  margin-bottom: 2.5rem;
}

.apps-grid,
.templates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.empty-state {
  padding: 3rem;
  text-align: center;
  color: var(--text-muted);
  background: var(--bg-secondary);
  border-radius: 12px;
  border: 2px dashed var(--border-color);
}

/* Modal */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: var(--bg-secondary);
  border-radius: 12px;
  padding: 1.5rem;
  width: 90%;
  max-width: 440px;
  box-shadow: var(--shadow-lg);
}

.modal h3 {
  margin: 0 0 1rem 0;
  font-size: 1.1rem;
  color: var(--text-primary);
}

.form-group {
  margin-bottom: 1rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-secondary);
}

.input {
  width: 100%;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.9rem;
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
  gap: 0.75rem;
  margin-top: 1.25rem;
}
</style>
