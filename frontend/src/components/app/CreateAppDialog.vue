<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { appApi } from '@/services/api'

const props = defineProps<{
  template: {
    id: string
    name: string
    description: string
    appType: string
    icon: string
  }
}>()

const emit = defineEmits<{
  create: [data: { name: string; appType: string; description: string; customTargetPath?: string }]
  cancel: []
}>()

const projectName = ref(props.template.name)
const targetPath = ref('')
const customTargetPath = ref('')
const useCustomPath = ref(false)
const pathExists = ref(false)
const checking = ref(false)
const creating = ref(false)
const error = ref('')

const AXOLOTL_BASE = import.meta.env.VITE_AXOLOTL_APPS_PATH || ''

// Compute default target path from project name
function computeTargetPath(name: string): string {
  if (AXOLOTL_BASE) {
    return `${AXOLOTL_BASE}/${name}/`
  }
  // Fallback — try to infer from backend convention
  return `~/Axolotl/${name}/`
}

// Check if path exists
async function checkPath(name: string) {
  if (!name.trim() || props.template.appType === 'CUSTOM') {
    pathExists.value = false
    return
  }
  checking.value = true
  try {
    const result = await appApi.checkTargetPath(name.trim(), props.template.appType)
    targetPath.value = result.targetPath || computeTargetPath(name.trim())
    pathExists.value = result.exists
  } catch {
    targetPath.value = computeTargetPath(name.trim())
    pathExists.value = false
  } finally {
    checking.value = false
  }
}

// Watch for name changes
watch(projectName, (name) => {
  if (!useCustomPath.value) {
    checkPath(name)
  }
})

const displayPath = computed(() => {
  if (useCustomPath.value) return customTargetPath.value
  return targetPath.value
})

const canCreate = computed(() => {
  return projectName.value.trim() && !creating.value
})

async function handleCreate() {
  if (!canCreate.value) return
  creating.value = true
  error.value = ''

  try {
    emit('create', {
      name: projectName.value.trim(),
      appType: props.template.appType,
      description: props.template.description,
      customTargetPath: useCustomPath.value ? customTargetPath.value : undefined,
    })
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || 'Failed to create app'
    creating.value = false
  }
}

onMounted(() => {
  checkPath(projectName.value)
})

// Accent color by type
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
</script>

<template>
  <div class="modal-overlay" @click.self="emit('cancel')">
    <div class="modal create-dialog">
      <!-- Header -->
      <div class="dialog-header">
        <div class="template-badge" :style="{ background: bgColors[template.appType], color: accentColors[template.appType] }">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="22" height="22">
            <path :d="template.icon" />
          </svg>
        </div>
        <div>
          <h3>Create from "{{ template.name }}"</h3>
          <p class="dialog-subtitle">{{ template.description }}</p>
        </div>
      </div>

      <!-- Form -->
      <div class="form-body">
        <!-- Project Name -->
        <div class="form-group">
          <label>Project Name</label>
          <input
            v-model="projectName"
            type="text"
            class="input"
            placeholder="My Awesome Project"
            @keyup.enter="handleCreate"
          />
        </div>

        <!-- App Type (read-only badge) -->
        <div class="form-group">
          <label>App Type</label>
          <div class="type-badge" :style="{ color: accentColors[template.appType], background: bgColors[template.appType] }">
            {{ template.appType }}
          </div>
        </div>

        <!-- Target Path -->
        <div class="form-group">
          <div class="path-label-row">
            <label>Target Path</label>
            <button class="link-btn" @click="useCustomPath = !useCustomPath">
              {{ useCustomPath ? 'Use default' : 'Custom path' }}
            </button>
          </div>

          <div v-if="!useCustomPath" class="path-display">
            <span class="path-text">{{ displayPath }}</span>
            <span v-if="checking" class="path-status checking">Checking…</span>
            <span v-else-if="pathExists" class="path-status exists"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" style="vertical-align:middle;margin-right:4px"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg> Already exists</span>
            <span v-else class="path-status ok"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" style="vertical-align:middle;margin-right:4px"><polyline points="20 6 9 17 4 12"/></svg> Available</span>
          </div>

          <input
            v-else
            v-model="customTargetPath"
            type="text"
            class="input"
            placeholder="/Users/.../Axolotl/my-app/"
          />
        </div>

        <!-- Error -->
        <div v-if="error" class="error-msg">{{ error }}</div>
      </div>

      <!-- Actions -->
      <div class="modal-actions">
        <button class="btn-secondary" @click="emit('cancel')" :disabled="creating">Cancel</button>
        <button
          class="btn-primary"
          @click="handleCreate"
          :disabled="!canCreate"
        >
          <span v-if="creating" class="spinner" />
          {{ creating ? 'Creating…' : 'Create App' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
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
  border-radius: 14px;
  padding: 1.5rem;
  width: 90%;
  max-width: 480px;
  box-shadow: var(--shadow-lg);
}

.dialog-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1.5rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color);
}

.template-badge {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.dialog-header h3 {
  margin: 0 0 0.15rem 0;
  font-size: 1.05rem;
  color: var(--text-primary);
}

.dialog-subtitle {
  margin: 0;
  font-size: 0.82rem;
  color: var(--text-secondary);
}

.form-body {
  margin-bottom: 1rem;
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

.path-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.path-label-row label {
  margin-bottom: 0;
}

.link-btn {
  background: none;
  border: none;
  color: var(--accent);
  font-size: 0.78rem;
  cursor: pointer;
  padding: 0;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.link-btn:hover {
  color: var(--accent-light);
}

.path-display {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 0.75rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.85rem;
  font-family: var(--font-mono);
}

.path-text {
  flex: 1;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.path-status {
  font-size: 0.78rem;
  white-space: nowrap;
}

.path-status.checking {
  color: var(--text-muted);
}

.path-status.exists {
  color: #e65100;
}

.path-status.ok {
  color: #4caf50;
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

.type-badge {
  display: inline-block;
  padding: 0.3rem 0.75rem;
  border-radius: 6px;
  font-size: 0.82rem;
  font-weight: 600;
}

.error-msg {
  padding: 0.625rem 0.75rem;
  background: rgba(244, 67, 54, 0.08);
  color: #e53935;
  border-radius: 6px;
  font-size: 0.85rem;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding-top: 0.5rem;
}

/* No shared button styles — use global .btn-primary/.btn-secondary from App.vue */

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
