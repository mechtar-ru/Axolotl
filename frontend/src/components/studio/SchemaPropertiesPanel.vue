<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useSchemaStore } from '@/stores/schemaStore'
import { useCanvasStore } from '@/stores/useCanvasStore'
import { useSettingsStore } from '@/stores/settingsStore'
import { storeToRefs } from 'pinia'
import { settingsApi } from '@/services/api'
import type { WorkflowSchema } from '@/types'

const emit = defineEmits<{
  addNode: []
  run: []
  quickStart: []
}>()

const schemaStore = useSchemaStore()
const canvasStore = useCanvasStore()
const settingsStore = useSettingsStore()
const { currentSchema } = storeToRefs(canvasStore)

const isEditingPath = ref(false)
const folderPickerRef = ref<HTMLInputElement | null>(null)

// Debounced saves for rapid input (name, description)
let saveTimeout: ReturnType<typeof setTimeout> | null = null
let isAlive = true
onUnmounted(() => { isAlive = false; if (saveTimeout) clearTimeout(saveTimeout) })

function debounceMarkDirty(data: any) {
  if (saveTimeout) clearTimeout(saveTimeout)
  saveTimeout = setTimeout(() => {
    if (!isAlive) return
    canvasStore.markDirty(data)
    saveTimeout = null
  }, 400)
}

// ─── Model dropdown ──────────────────────────────────────────────
const providerOptions = ref<{ value: string; label: string; group: string }[]>([])

const providerGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {}
  for (const opt of providerOptions.value) {
    if (!groups[opt.group]) groups[opt.group] = []
    groups[opt.group]!.push({ value: opt.value, label: opt.label })
  }
  return groups
})

onMounted(async () => {
  try {
    const providers = await settingsApi.getProviders()
    const opts: { value: string; label: string; group: string }[] = []
    for (const p of providers) {
      const disabled = new Set(p.disabledModels || [])
      for (const m of p.models || []) {
        if (!disabled.has(m)) {
          opts.push({ value: m, label: m, group: p.name })
        }
      }
    }
    providerOptions.value = opts
  } catch {
    // providers unavailable — dropdown stays empty
  }
})

// ─── Computed values ─────────────────────────────────────────────
const schemaName = computed(() => currentSchema.value?.name || '')
const schemaDescription = computed(() => currentSchema.value?.description || '')
const targetPath = computed(() => currentSchema.value?.targetPath || '')
const defaultModel = computed(() => currentSchema.value?.defaultModel || '')
const schemaProjectType = computed(() => currentSchema.value?.projectType || 'FLUTTER')
const hasReviewNode = computed(() =>
  (currentSchema.value?.nodes || []).some(n => n.type === 'review')
)

// ─── Actions ─────────────────────────────────────────────────────
function updateName(value: string) {
  if (!currentSchema.value) return
  debounceMarkDirty({ ...currentSchema.value, name: value })
}

function updateDescription(value: string) {
  if (!currentSchema.value) return
  debounceMarkDirty({ ...currentSchema.value, description: value })
}

function updateDefaultModel(value: string) {
  if (!currentSchema.value) return
  canvasStore.markDirty({
    ...currentSchema.value,
    defaultModel: value || undefined,
  })
}

function updateProjectType(value: string) {
  if (!currentSchema.value) return
  canvasStore.markDirty({
    ...currentSchema.value,
    projectType: value as WorkflowSchema['projectType'],
  })
}

function updateAutoApproveDrafts(value: boolean) {
  if (!currentSchema.value) return
  canvasStore.markDirty({
    ...currentSchema.value,
    autoApproveDrafts: value,
  })
}

function startEditPath() {
  isEditingPath.value = true
  // Focus the input on next tick after it renders
  setTimeout(() => {
    const input = document.querySelector('.path-input') as HTMLInputElement
    input?.focus()
    input?.select()
  }, 0)
}

function commitPath(value: string) {
  isEditingPath.value = false
  if (!currentSchema.value) return
  const trimmed = value.trim()
  if (trimmed === currentSchema.value.targetPath) return // no change
  if (!trimmed) {
    // Clear was rejected — just reset
    return
  }
  canvasStore.markDirty({ ...currentSchema.value, targetPath: trimmed })
}

function cancelEditPath() {
  isEditingPath.value = false
}

function browseFolder() {
  folderPickerRef.value?.click()
}

function onFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  input.value = '' // reset so same folder can be picked again
  if (!files || files.length === 0) return
  const dirName = files[0]!.webkitRelativePath.split('/')[0]
  if (!dirName) return
  const base = settingsStore.projectsFolder
  const path = base ? `${base}/${dirName}/` : `${dirName}/`
  if (!currentSchema.value) return
  if (path !== currentSchema.value.targetPath) {
    canvasStore.markDirty({ ...currentSchema.value, targetPath: path })
  }
}
</script>

<template>
  <div class="schema-properties-panel">
    <div class="panel-header">
      <h3>Schema Properties</h3>
    </div>

    <div class="panel-body">
      <!-- Name -->
      <div class="config-section">
        <label class="config-label">Name</label>
        <input
          :value="schemaName"
          @input="updateName(($event.target as HTMLInputElement).value)"
          type="text"
          class="config-input"
          placeholder="Schema name"
        />
      </div>

      <!-- Description -->
      <div class="config-section">
        <label class="config-label">Description</label>
        <textarea
          :value="schemaDescription"
          @input="updateDescription(($event.target as HTMLTextAreaElement).value)"
          class="config-textarea"
          placeholder="Schema description"
          rows="3"
        />
      </div>

      <!-- Target Path -->
      <div class="config-section">
        <label class="config-label">Target Path</label>
        <div v-if="!isEditingPath" class="path-display">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" class="icon clickable-icon" @click.stop="browseFolder" title="Browse for folder">
            <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
          </svg>
          <span class="path-text clickable" @click="startEditPath">{{ targetPath || '(not set)' }}</span>
        </div>
        <div v-else class="path-display">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" class="icon" style="cursor:pointer" @click="cancelEditPath">
            <path d="M18 6L6 18M6 6l12 12"/>
          </svg>
          <input
            :value="targetPath"
            @blur="commitPath(($event.target as HTMLInputElement).value)"
            @keydown.enter="commitPath(($event.target as HTMLInputElement).value)"
            @keydown.escape="cancelEditPath"
            type="text"
            class="path-input"
            placeholder="e.g. ~/git/Axolotl/my-app"
          />
        </div>
        <input ref="folderPickerRef" type="file" webkitdirectory style="display:none" @change="onFolderPicked" />
      </div>

      <!-- Default Model -->
      <div class="config-section">
        <label class="config-label">Default Model</label>
        <select
          :value="defaultModel"
          @change="updateDefaultModel(($event.target as HTMLSelectElement).value)"
          class="config-select"
        >
          <option value="">Auto (user default)</option>
          <template v-for="(models, group) in providerGroups" :key="group">
            <optgroup :label="group">
              <option v-for="m in models" :key="m.value" :value="m.value">
                {{ m.label }}
              </option>
            </optgroup>
          </template>
        </select>
      </div>

      <!-- Project Type -->
      <div class="config-section">
        <label class="config-label">Project Type</label>
        <select
          :value="schemaProjectType"
          @change="updateProjectType(($event.target as HTMLSelectElement).value)"
          class="config-select"
        >
          <option value="FLUTTER">Flutter</option>
          <option value="PYTHON">Python</option>
          <option value="WEB">Web (Vite/React)</option>
          <option value="GO">Go</option>
          <option value="RUST">Rust</option>
        </select>
      </div>

      <!-- Auto-approve drafts (visible when review node exists) -->
      <div v-if="hasReviewNode" class="config-section">
        <label class="config-checkbox">
          <input
            type="checkbox"
            :checked="currentSchema?.autoApproveDrafts ?? false"
            @change="updateAutoApproveDrafts(($event.target as HTMLInputElement).checked)"
          />
          Auto-approve drafts
          <span class="config-hint">Skip human approval of draft artifacts</span>
        </label>
      </div>

      <!-- Quick Actions -->
      <div class="config-section quick-actions">
        <label class="config-label">Quick Actions</label>
        <button class="action-btn" @click="emit('addNode')">
          Add Node
        </button>
        <button class="action-btn action-btn--primary" @click="emit('run')">
          Run
        </button>
        <button class="action-btn action-btn--accent" @click="emit('quickStart')">
          Quick Start
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.schema-properties-panel {
  width: 320px;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  box-shadow: var(--shadow-lg);
  z-index: var(--z-panel);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.panel-header h3 {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
}

.config-section {
  margin-bottom: var(--space-5);
}

.config-label {
  display: block;
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.config-input,
.config-select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
  transition: border-color var(--transition);
}

.config-input:focus,
.config-select:focus,
.config-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.config-textarea {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
  line-height: 1.5;
}

.path-display {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--text-primary);
}

.path-display.clickable {
  cursor: pointer;
  transition: background var(--transition);
}

.path-display.clickable:hover {
  background: var(--bg-active);
}

.path-text {
  word-break: break-all;
  font-family: var(--font-mono);
}

.path-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  font-family: var(--font-mono);
  color: var(--text-primary);
  outline: none;
}

.icon {
  flex-shrink: 0;
  color: var(--text-muted);
}

.config-checkbox {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  font-size: var(--text-sm);
  color: var(--text-primary);
  flex-wrap: wrap;
}

.config-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
  cursor: pointer;
}

.config-hint {
  display: block;
  font-size: var(--text-xs);
  color: var(--text-muted);
  margin-top: var(--space-1);
  width: 100%;
  margin-left: calc(16px + var(--space-2));
}

.clickable-icon {
  cursor: pointer;
  transition: color var(--transition);
}

.clickable-icon:hover {
  color: var(--accent);
}

.quick-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.action-btn {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  cursor: pointer;
  transition: background var(--transition), border-color var(--transition);
  text-align: center;
}

.action-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent);
}

.action-btn--primary {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

.action-btn--primary:hover {
  opacity: 0.9;
}

.action-btn--accent {
  background: var(--accent-secondary, #00bcd4);
  color: white;
  border-color: var(--accent-secondary, #00bcd4);
}

.action-btn--accent:hover {
  opacity: 0.9;
}
</style>
