<template>
  <div class="custom-endpoint-section">
    <div class="section-divider">
      <h2 class="section-title">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
        Custom LLM APIs
      </h2>
      <button class="add-btn" @click="addEndpoint">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        Add new
      </button>
    </div>

    <div v-for="ep in localEndpoints" :key="ep.id" class="provider-card custom-card" :class="{ collapsed: isCollapsed(ep.id!) }">
      <div class="provider-header" @click="toggleCollapse(ep.id!)">
        <h2 style="flex:1; font-size:var(--text-md);">{{ ep.name || 'New Endpoint' }}</h2>
        <svg class="collapse-chevron" :class="{ rotated: !isCollapsed(ep.id!) }" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
      </div>
      <div v-if="errors[ep.id!]" class="inline-error">{{ errors[ep.id!] }}</div>
      <div v-show="!isCollapsed(ep.id!)" class="provider-fields">
        <div class="field">
          <label>Name</label>
          <input :value="ep.name" class="field-input" placeholder="My LLM Provider" @input="updateField(ep.id!, 'name', ($event.target as HTMLInputElement).value)" />
        </div>
        <div class="field">
          <label>Base URL</label>
          <input :value="ep.baseUrl" class="field-input" placeholder="https://api.example.com/v1" @input="updateField(ep.id!, 'baseUrl', ($event.target as HTMLInputElement).value)" />
        </div>
        <div class="field">
          <label>API Key</label>
          <div class="field-row">
            <input
              :type="showKeys[ep.id!] ? 'text' : 'password'"
              :value="editedKeys[ep.id!] ?? ''"
              :placeholder="ep.apiKey ? '•••••••• (saved)' : 'Enter API key...'"
              class="field-input"
              @input="editedKeys[ep.id!] = ($event.target as HTMLInputElement).value"
            />
            <button class="icon-btn" @click="toggleShowKey(ep)" title="Show/Hide">
              <svg v-if="showKeys[ep.id!]" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
              <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
        </div>
        <div class="field">
          <label>Model Name</label>
          <input :value="ep.modelName" class="field-input" placeholder="gpt-4, claude-3, etc." @input="updateField(ep.id!, 'modelName', ($event.target as HTMLInputElement).value)" />
        </div>
        <div class="field">
          <label>Auth Type</label>
          <select :value="ep.authType" class="field-input" @change="updateField(ep.id!, 'authType', ($event.target as HTMLSelectElement).value)">
            <option value="bearer">Bearer Token</option>
            <option value="api-key">API Key Header</option>
            <option value="none">None</option>
          </select>
        </div>
        <div class="field">
          <label class="toggle-label">
            <input type="checkbox" :checked="ep.enabled" @change="updateField(ep.id!, 'enabled', ($event.target as HTMLInputElement).checked)" />
            Enabled
          </label>
        </div>
        <div class="field-actions">
          <button class="save-btn" @click="saveEndpoint(ep)" :disabled="saving[ep.id!]">
            <svg v-if="!saving[ep.id!]" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
            {{ saving[ep.id!] ? 'Saving...' : 'Save' }}
          </button>
          <button class="test-btn" @click="testEndpoint(ep)" :disabled="testing[ep.id!]">
            <svg v-if="!testing[ep.id!]" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            {{ testing[ep.id!] ? 'Testing...' : 'Test' }}
          </button>
          <span v-if="testResults[ep.id!]" class="test-result" :class="testResults[ep.id!]?.success ? 'test-ok' : 'test-fail'">
            <svg v-if="testResults[ep.id!]?.success" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
            <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            {{ testResults[ep.id!]?.success ? 'OK' : testResults[ep.id!]?.message }}
          </span>
          <button v-if="!confirmDelete[ep.id!]" class="delete-btn" @click="confirmDelete[ep.id!] = true; scheduleClearConfirm(ep.id!)" title="Delete">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
          </button>
          <button v-else class="delete-btn confirm-delete" @click="deleteEndpoint(ep.id!)">
            Delete?
          </button>
        </div>
      </div>
    </div>

    <div v-if="localEndpoints.length === 0" class="empty-hint">
      No custom LLM endpoints configured. Click "Add new" to add one.
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { customEndpointApi, type CustomLlmEndpoint } from '../../services/api'

const props = defineProps<{
  endpoints: CustomLlmEndpoint[]
}>()

const emit = defineEmits<{
  changed: [endpoints: CustomLlmEndpoint[]]
}>()

const localEndpoints = ref<CustomLlmEndpoint[]>([])

watch(() => props.endpoints, (val) => {
  localEndpoints.value = val.map(e => ({ ...e }))
}, { immediate: true })

const saving = reactive<Record<string, boolean>>({})
const testing = reactive<Record<string, boolean>>({})
const testResults = reactive<Record<string, { success: boolean; message: string } | null>>({})
const errors = reactive<Record<string, string>>({})
const editedKeys = reactive<Record<string, string>>({})
const showKeys = reactive<Record<string, boolean>>({})
const customCollapsed = reactive<Record<string, boolean>>({})
const confirmDelete = reactive<Record<string, boolean>>({})

// ──── Internal mutated copy (all edits happen here) ────

function updateField(id: string, field: string, value: any) {
  const ep = localEndpoints.value.find(e => e.id === id)
  if (ep) {
    (ep as any)[field] = value
    if (errors[id]) delete errors[id]
  }
}

function toggleShowKey(ep: CustomLlmEndpoint) {
  showKeys[ep.id!] = !showKeys[ep.id!]
  if (showKeys[ep.id!] && !editedKeys[ep.id!] && ep.apiKey) {
    editedKeys[ep.id!] = ep.apiKey
  }
}

function toggleCollapse(id: string) {
  customCollapsed[id] = !customCollapsed[id]
}

function isCollapsed(id: string): boolean {
  return customCollapsed[id] ?? true
}

function scheduleClearConfirm(id: string) {
  setTimeout(() => { confirmDelete[id] = false }, 4000)
}

function addEndpoint() {
  const newEp: CustomLlmEndpoint = {
    id: crypto.randomUUID(),
    name: '',
    baseUrl: '',
    apiKey: '',
    modelName: '',
    authType: 'bearer',
    enabled: true,
    priority: 100,
  }
  localEndpoints.value.push(newEp)
}

async function saveEndpoint(ep: CustomLlmEndpoint) {
  if (!ep.name || !ep.name.trim()) {
    errors[ep.id!] = 'Name is required'
    return
  }
  const duplicate = localEndpoints.value.find(e => e.id !== ep.id && e.name === ep.name.trim())
  if (duplicate) {
    errors[ep.id!] = `Name "${ep.name}" already exists`
    return
  }
  saving[ep.id!] = true
  errors[ep.id!] = ''
  try {
    const payload = { ...ep }
    const newKey = editedKeys[ep.id!]
    if (newKey !== undefined && newKey !== '') {
      payload.apiKey = newKey
    }
    const saved = await customEndpointApi.create(payload)
    const idx = localEndpoints.value.findIndex(e => e.id === ep.id)
    if (idx >= 0) localEndpoints.value[idx] = saved
    editedKeys[ep.id!] = ''
    emit('changed', [...localEndpoints.value])
  } catch (e: any) {
    if (e.response?.status === 409) {
      errors[ep.id!] = e.response.data?.error || 'Name already exists'
    } else {
      errors[ep.id!] = 'Error saving: ' + (e.response?.data?.error || e.message)
    }
  } finally {
    saving[ep.id!] = false
  }
}

async function testEndpoint(ep: CustomLlmEndpoint) {
  testing[ep.id!] = true
  testResults[ep.id!] = null
  try {
    const payload = { ...ep }
    const newKey = editedKeys[ep.id!]
    if (newKey !== undefined && newKey !== '') payload.apiKey = newKey
    const result = await customEndpointApi.test(payload)
    testResults[ep.id!] = result
  } catch (e: any) {
    testResults[ep.id!] = { success: false, message: e.message || 'Error' }
  } finally {
    testing[ep.id!] = false
  }
}

async function deleteEndpoint(id: string) {
  try {
    await customEndpointApi.remove(id)
    localEndpoints.value = localEndpoints.value.filter(e => e.id !== id)
    emit('changed', [...localEndpoints.value])
  } catch (e: any) {
    console.error('Failed to delete custom endpoint:', e)
  } finally {
    confirmDelete[id] = false
  }
}
</script>

<style scoped>
.custom-endpoint-section {
  margin-top: var(--space-6);
}
.section-divider {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) 0;
}
.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-lg);
  color: var(--text-primary);
}

.add-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-3);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-sm);
}

.add-btn:hover {
  opacity: 0.9;
}

.inline-error {
  background: var(--error-light);
  color: var(--error);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  margin-bottom: var(--space-3);
}

.empty-hint {
  text-align: center;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  padding: var(--space-6);
}

.delete-btn {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  background: transparent;
  color: var(--text-muted);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2);
  cursor: pointer;
  font-size: var(--text-xs);
}

.delete-btn:hover {
  background: rgba(239, 68, 68, 0.1);
  color: var(--error);
  border-color: var(--error);
}

.confirm-delete {
  background: var(--error) !important;
  color: white !important;
  border-color: var(--error) !important;
}

.toggle-label {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--text-primary);
  cursor: pointer;
}

.toggle-label input[type="checkbox"] {
  accent-color: var(--accent);
}

.custom-card .provider-header h2 {
  font-size: var(--text-md);
}

/* Shared card styles */
.provider-card {
  background: var(--bg-card);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  margin-bottom: var(--space-4);
  border: 1px solid var(--border-color);
}

.provider-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  cursor: pointer;
  user-select: none;
}

.collapsed .provider-header {
  margin-bottom: 0;
}

.collapsed .provider-fields {
  display: none;
}

.collapse-chevron {
  flex-shrink: 0;
  transition: transform 0.2s;
  color: var(--text-muted);
}

.collapse-chevron.rotated {
  transform: rotate(180deg);
}

.provider-fields {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.field label {
  display: block;
  font-size: var(--text-xs);
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field-row {
  display: flex;
  gap: var(--space-2);
}

.field-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  outline: none;
}

.field-input:focus {
  border-color: var(--border-focus);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  padding: var(--space-2);
  line-height: 1;
}

.icon-btn:hover {
  background: var(--bg-hover);
}

.field-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-1);
}

.save-btn, .test-btn, .icon-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.save-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
}

.save-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.save-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--bg-hover);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
}

.test-btn:hover:not(:disabled) {
  background: var(--bg-active);
}

.test-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-result {
  font-size: var(--text-xs);
}

.test-ok {
  color: var(--success);
}

.test-fail {
  color: var(--error);
}
</style>
