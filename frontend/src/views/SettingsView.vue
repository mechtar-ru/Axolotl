<template>
  <div class="settings-page">
    <div class="settings-header">
      <button class="back-btn" @click="goBack">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5m7-7-7 7 7 7"/></svg>
        Back
      </button>
      <h1>
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
        Settings
      </h1>
    </div>

    <div class="settings-content">
      <div v-if="loading" class="loading-state"><span class="settings-spinner" /> Loading providers...</div>

      <div v-else-if="error" class="error">{{ error }}</div>

      <div v-else>
        <!-- Theme selector -->
        <div class="provider-card theme-card">
          <div class="provider-header">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="13.5" cy="6.5" r="2.5"/><circle cx="6.5" cy="9.5" r="2.5"/><circle cx="17.5" cy="14.5" r="2.5"/><circle cx="9.5" cy="17.5" r="2.5"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.9 0 1.6-.7 1.6-1.6 0-.4-.2-.8-.5-1.1-.3-.3-.5-.7-.5-1.1 0-.9.7-1.6 1.6-1.6h1.8c3.9 0 7-3.1 7-7C22 5.5 17.5 2 12 2z"/></svg>
            <h2>Theme</h2>
          </div>
          <div class="provider-fields">
            <div class="field">
              <label>Appearance</label>
              <ThemeToggle :model-value="settingsStore.theme" @update:model-value="settingsStore.setTheme" />
            </div>
          </div>
        </div>

        <!-- User default model -->
        <div class="provider-card user-default-card">
          <div class="provider-header">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
            <h2>Default Model</h2>
          </div>
          <div class="provider-fields">
            <div class="field">
              <label>Use for new nodes &amp; schemas</label>
              <select v-model="userDefaultModel" class="field-input" @change="saveUserDefaultModel">
                <option value="">Auto (Ollama)</option>
                <optgroup v-for="group in allModelGroups" :key="group.name" :label="group.name">
                  <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                </optgroup>
              </select>
            </div>
          </div>
        </div>

        <!-- Projects Folder -->
        <div class="provider-card">
          <div class="provider-header">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
            <h2>Projects Folder</h2>
          </div>
          <div class="provider-fields">
            <div class="field">
              <label>Where new projects are created</label>
              <div class="path-row">
                <input
                  :value="projectsFolder"
                  @input="onProjectsFolderInput(($event.target as HTMLInputElement).value)"
                  type="text"
                  class="field-input path-field"
                  placeholder="e.g. /Users/name/git/Axolotl"
                />
                <input ref="folderPickerRef" type="file" webkitdirectory style="display:none" @change="onSettingsFolderPicked" />
                <button class="browse-btn" @click="folderPickerRef?.click()" title="Browse">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                    <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
                  </svg>
                </button>
                <button class="save-btn" :disabled="projectsFolder === settingsStore.projectsFolder" @click="saveProjectsFolder">Save</button>
              </div>
            </div>
          </div>
        </div>

        <!-- Built-in providers -->
        <ProviderCard
          v-for="provider in builtInProviders"
          :key="provider.name"
          :provider="provider"
          :user-default-model="userDefaultModel"
          @save="saveProvider"
          @test="testProvider"
          @refresh="refreshProviders"
          @toggle-model="toggleModel"
          @enable-all-models="enableAllModels"
          @disable-all-models="disableAllModels"
          @enable-all-in-group="enableAllInGroup"
          @disable-all-in-group="disableAllInGroup"
        />

        <div v-if="builtInProviders.length === 0" class="empty">
          No providers found. Make sure Ollama is running.
        </div>

        <!-- Custom LLM endpoints -->
        <CustomEndpointList
          :endpoints="customEndpoints"
          @add="addCustomEndpoint"
          @save="saveCustomEndpoint"
          @test="testCustomEndpoint"
          @delete="deleteCustomEndpoint"
          @update-field="updateCustomField"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useSettingsStore } from '@/stores/settingsStore';
import ThemeToggle from '@/components/ui/ThemeToggle.vue';
import ProviderCard from '@/components/settings/ProviderCard.vue';
import CustomEndpointList from '@/components/settings/CustomEndpointList.vue';
import { settingsApi, customEndpointApi, type ProviderInfo, type CustomLlmEndpoint } from '../services/api';

const router = useRouter();
const settingsStore = useSettingsStore();
const providers = ref<ProviderInfo[]>([]);
const loading = ref(true);
const error = ref('');

// Custom endpoints state
const customEndpoints = ref<CustomLlmEndpoint[]>([]);
const customSaving = reactive<Record<string, boolean>>({});
const customTesting = reactive<Record<string, boolean>>({});
const customTestResults = reactive<Record<string, { success: boolean; message: string } | null>>({});
const customErrors = reactive<Record<string, string>>({});
const customEditedKeys = reactive<Record<string, string>>({});
const customShowKeys = reactive<Record<string, boolean>>({});
const customCollapsed = reactive<Record<string, boolean>>({});
const confirmDelete = reactive<Record<string, boolean>>({});

// User default model
const userDefaultModel = ref('');
const allModelOptions = ref<{ value: string; label: string; group: string }[]>([]);

const allModelGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {};
  for (const opt of allModelOptions.value) {
    const g = groups[opt.group];
    if (g) g.push(opt);
    else groups[opt.group] = [opt];
  }
  return Object.entries(groups).map(([name, options]) => ({ name, options }));
});

async function saveUserDefaultModel() {
  try {
    await settingsApi.setUserDefaultModel(userDefaultModel.value);
  } catch (e: any) {
    error.value = 'Save error: ' + (e.message || e);
  }
}

// ──── Projects folder ────
const projectsFolder = ref('')
const folderPickerRef = ref<HTMLInputElement | null>(null)

function onProjectsFolderInput(val: string) {
  projectsFolder.value = val
}

function onSettingsFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  input.value = ''
  if (!files || files.length === 0) return
  const dirName = files[0].webkitRelativePath.split('/')[0]
  if (dirName) {
    const base = projectsFolder.value ? projectsFolder.value.replace(/\/[^/]*\/?$/, '') : ''
    projectsFolder.value = base ? `${base}/${dirName}/` : `~/Axolotl/${dirName}`
  }
}

async function saveProjectsFolder() {
  await settingsStore.saveProjectsFolder(projectsFolder.value)
}

onMounted(async () => {
  await settingsStore.loadProjectsFolder()
  projectsFolder.value = settingsStore.projectsFolder
})

const builtInProviders = computed(() => providers.value.filter(p => !p.custom));

async function refreshProviders() {
  loading.value = true;
  error.value = '';
  try {
    providers.value = await settingsApi.getProviders();
  } catch (e: any) {
    error.value = 'Error loading providers: ' + (e.message || e);
  } finally {
    loading.value = false;
  }
}

async function refreshCustomEndpoints() {
  try {
    customEndpoints.value = await customEndpointApi.list();
  } catch (e: any) {
    console.error('Failed to load custom endpoints:', e);
  }
}

async function enableAllInGroup(providerName: string, models: string[]) {
  const p = providers.value.find(p => p.name === providerName);
  if (!p) return;
  for (const m of models) {
    if (p.disabledModels?.includes(m)) {
      await settingsStore.setModelDisabled(providerName, m, false);
    }
  }
  await refreshProviders();
  rebuildModelOptions();
}

async function disableAllInGroup(providerName: string, models: string[]) {
  const p = providers.value.find(p => p.name === providerName);
  if (!p) return;
  for (const m of models) {
    if (!p.disabledModels?.includes(m)) {
      await settingsStore.setModelDisabled(providerName, m, true);
    }
  }
  await refreshProviders();
  rebuildModelOptions();
}

async function enableAllModels(providerName: string) {
  const p = providers.value.find(p => p.name === providerName);
  if (!p) return;
  for (const m of p.models || []) {
    if (p.disabledModels?.includes(m)) {
      await settingsStore.setModelDisabled(providerName, m, false);
    }
  }
  await refreshProviders();
  rebuildModelOptions();
}

async function disableAllModels(providerName: string) {
  const p = providers.value.find(p => p.name === providerName);
  if (!p) return;
  for (const m of p.models || []) {
    if (!p.disabledModels?.includes(m)) {
      await settingsStore.setModelDisabled(providerName, m, true);
    }
  }
  await refreshProviders();
  rebuildModelOptions();
}

async function saveProvider(name: string, data: { apiKey?: string; baseUrl?: string; defaultModel?: string }) {
  try {
    const payload: Record<string, string> = {};
    if (data.apiKey !== undefined) payload.apiKey = data.apiKey;
    if (data.baseUrl !== undefined) payload.baseUrl = data.baseUrl;
    if (data.defaultModel !== undefined) payload.defaultModel = data.defaultModel;
    await settingsApi.updateProvider(name, payload);
    await refreshProviders();
  } catch (e: any) {
    error.value = 'Save error: ' + (e.message || e);
  }
}

async function testProvider(name: string, apiKey?: string, baseUrl?: string) {
  try {
    await settingsApi.testProvider(name, apiKey, baseUrl);
    await refreshProviders();
  } catch (e: any) {
    error.value = 'Test error: ' + (e.message || e);
  }
}

async function toggleShowKey(providerName: string) {
  showKeys[providerName] = !showKeys[providerName];
  if (showKeys[providerName] && !editedKeys[providerName]) {
    try {
      const key = await settingsApi.getProviderApiKey(providerName);
      if (key) editedKeys[providerName] = key;
    } catch (e) {
      console.error('Failed to fetch API key:', e);
    }
  }
}

async function toggleModel(providerName: string, model: string, enabled: boolean) {
  await settingsStore.setModelDisabled(providerName, model, !enabled);
  // Refresh provider list to sync disabledModels state
  await refreshProviders();
  // Rebuild model options for the default model dropdown
  rebuildModelOptions();
}

function rebuildModelOptions() {
  const opts = settingsStore.getAllModelOptions();
  allModelOptions.value = opts;
}

function addCustomEndpoint() {
  const newEp: CustomLlmEndpoint = {
    id: crypto.randomUUID(),
    name: '',
    baseUrl: '',
    apiKey: '',
    modelName: '',
    authType: 'bearer',
    enabled: true,
    priority: 100,
  };
  customEndpoints.value.push(newEp);
}

function updateCustomField(id: string, field: string, value: any) {
  const ep = customEndpoints.value.find(e => e.id === id);
  if (ep) (ep as any)[field] = value;
  if (customErrors[id]) delete customErrors[id];
}

async function saveCustomEndpoint(ep: CustomLlmEndpoint) {
  if (!ep.name || !ep.name.trim()) {
    customErrors[ep.id!] = 'Name is required';
    return;
  }
  // Client-side duplicate check
  const duplicate = customEndpoints.value.find(e => e.id !== ep.id && e.name === ep.name.trim())
  if (duplicate) {
    customErrors[ep.id!] = `Name "${ep.name}" already exists`;
    return;
  }
  customSaving[ep.id!] = true;
  customErrors[ep.id!] = '';
  try {
    const payload = { ...ep };
    // Only send API key if user typed a new one
    const newKey = customEditedKeys[ep.id!];
    if (newKey !== undefined && newKey !== '') {
      payload.apiKey = newKey;
    }
    const saved = await customEndpointApi.create(payload);
    const idx = customEndpoints.value.findIndex(e => e.id === ep.id);
    if (idx >= 0) customEndpoints.value[idx] = saved;
    customEditedKeys[ep.id!] = '';
  } catch (e: any) {
    if (e.response?.status === 409) {
      customErrors[ep.id!] = e.response.data?.error || 'Name already exists';
    } else {
      customErrors[ep.id!] = 'Error saving: ' + (e.response?.data?.error || e.message);
    }
  } finally {
    customSaving[ep.id!] = false;
  }
}

async function testCustomEndpoint(ep: CustomLlmEndpoint) {
  customTesting[ep.id!] = true;
  customTestResults[ep.id!] = null;
  try {
    const payload = { ...ep };
    const newKey = customEditedKeys[ep.id!];
    if (newKey !== undefined && newKey !== '') payload.apiKey = newKey;
    const result = await customEndpointApi.test(payload);
    customTestResults[ep.id!] = result;
  } catch (e: any) {
    customTestResults[ep.id!] = { success: false, message: e.message || 'Error' };
  } finally {
    customTesting[ep.id!] = false;
  }
}

async function deleteCustomEndpoint(id: string) {
  try {
    await customEndpointApi.remove(id);
    customEndpoints.value = customEndpoints.value.filter(e => e.id !== id);
  } catch (e: any) {
    console.error('Failed to delete custom endpoint:', e);
  } finally {
    confirmDelete[id] = false;
  }
}

function toggleCustomShowKey(id: string, endpoint: CustomLlmEndpoint) {
  customShowKeys[id] = !customShowKeys[id];
  if (customShowKeys[id] && !customEditedKeys[id] && endpoint.apiKey) {
    customEditedKeys[id] = endpoint.apiKey;
  }
}

function toggleCustomCollapse(id: string) {
  customCollapsed[id] = !customCollapsed[id];
}

function isCustomCollapsed(id: string): boolean {
  return customCollapsed[id] ?? true;
}

function scheduleClearConfirm(id: string) {
  setTimeout(() => { confirmDelete[id] = false }, 4000)
}

function goBack() {
  router.push('/');
}

onMounted(async () => {
  await Promise.all([refreshProviders(), refreshCustomEndpoints()]);
  // Load user default model
  try {
    userDefaultModel.value = await settingsApi.getUserDefaultModel();
  } catch {}
  // Build model options using store (respects disabledModels filter)
  rebuildModelOptions();
});
</script>

<style scoped>
.settings-page {
  max-width: 700px;
  margin: 0 auto;
  padding: var(--space-6);
}

.settings-header {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.settings-header h1 {
  font-size: var(--text-2xl);
  color: var(--text-primary);
}

.back-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--bg-card);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-sm);
}

.back-btn:hover {
  background: var(--bg-hover);
}

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

.status-pill {
  font-size: var(--text-xs);
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 99px;
  white-space: nowrap;
}

.pill-online {
  background: rgba(34, 197, 94, 0.15);
  color: var(--success);
}

.pill-offline {
  background: rgba(239, 68, 68, 0.15);
  color: var(--error);
}

.provider-header h2 {
  font-size: var(--text-lg);
  color: var(--text-primary);
  flex: 1;
}

.status-dot {
  width: var(--icon-sm);
  height: var(--icon-sm);
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.online {
  background: var(--success);
  box-shadow: var(--shadow-glow-success);
}

.status-dot.offline {
  background: var(--error);
  box-shadow: var(--shadow-glow-error);
}

.refresh-btn {
  background: none;
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2);
  cursor: pointer;
  font-size: var(--text-sm);
}

.refresh-btn:hover {
  background: var(--bg-hover);
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

.model-toggles {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: var(--space-2);
}

.model-group-header {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  margin: 0 calc(-1 * var(--space-2)) var(--space-1);
  border-top: 1px solid var(--border-color);
  cursor: pointer;
  user-select: none;
  position: sticky;
  top: 0;
  background: var(--bg-primary);
  z-index: 1;
}

.model-group-header:first-of-type {
  border-top: none;
  margin-top: 0;
}

.model-group-header:hover {
  background: var(--bg-hover);
}

.model-group-arrow {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 10px;
  width: 12px;
  text-align: center;
}

.model-group-label-text {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: var(--text-muted);
  flex: 1;
}

.model-group-count {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-secondary);
  white-space: nowrap;
  margin-right: var(--space-2);
}

.model-group-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s;
}

.model-group-header:hover .model-group-actions {
  opacity: 1;
}

.group-toggle-btn {
  padding: 1px 6px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  border-radius: 3px;
  cursor: pointer;
  font-size: 9px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  line-height: 1.3;
}

.group-toggle-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.group-toggle-btn.group-off:hover {
  background: var(--error);
  border-color: var(--error);
  color: white;
}

.model-summary {
  text-align: center;
  padding: var(--space-2) var(--space-2) var(--space-1);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 500;
  border-top: 1px solid var(--border-color);
  margin: var(--space-1) calc(-1 * var(--space-2)) calc(-1 * var(--space-1));
}

.model-toggle {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  color: var(--text-primary);
  transition: background 0.15s;
}

.model-toggle:hover {
  background: var(--bg-hover);
}

.model-toggle.disabled {
  color: var(--text-muted);
  text-decoration: line-through;
}

.model-toggle input[type="checkbox"] {
  accent-color: var(--accent);
  width: 14px;
  height: 14px;
}

.model-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.models-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.models-header label {
  margin-bottom: 0 !important;
}

.model-bulk-actions {
  display: flex;
  gap: var(--space-1);
}

.bulk-toggle-btn {
  padding: 2px 8px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  line-height: 1.4;
}

.bulk-toggle-btn:hover {
  background: var(--bg-active);
  color: var(--text-primary);
}

.bulk-toggle-btn.bulk-off:hover {
  color: var(--error);
  border-color: var(--error);
}

.model-search-wrap {
  position: relative;
  margin-bottom: var(--space-2);
}

.search-icon {
  position: absolute;
  left: var(--space-2);
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-muted);
  pointer-events: none;
}

.model-search-input {
  width: 100%;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2) var(--space-1) 28px;
  font-size: var(--text-xs);
  outline: none;
  box-sizing: border-box;
}

.model-search-input:focus {
  border-color: var(--border-focus);
}

.model-search-input::placeholder {
  color: var(--text-muted);
}

.search-empty {
  padding: var(--space-3) var(--space-2);
  text-align: center;
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-style: italic;
}

.loading-state {
  text-align: center;
  padding: var(--space-8);
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
}

.settings-spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid var(--text-muted);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.error, .empty {
  text-align: center;
  padding: var(--space-8);
  color: var(--text-secondary);
}

.error {
  color: var(--error);
}

/* Custom endpoints section */
.section-divider {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: var(--space-6) 0 var(--space-4);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--border-color);
}

.section-title {
  font-size: var(--text-lg);
  color: var(--text-primary);
}

.add-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
}

.add-btn:hover {
  opacity: 0.9;
}

.custom-card {
  border-left: 3px solid var(--accent);
}

.inline-error {
  background: var(--error-light);
  color: var(--error);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  margin-bottom: var(--space-3);
}

.toggle-label {
  display: flex !important;
  align-items: center;
  gap: var(--space-2);
  text-transform: none !important;
  letter-spacing: 0 !important;
  font-size: var(--text-sm) !important;
  cursor: pointer;
  color: var(--text-primary) !important;
}

.toggle-label input[type="checkbox"] {
  width: var(--icon-sm);
  height: var(--icon-sm);
  accent-color: var(--accent);
}

.delete-btn {
  padding: var(--space-2) var(--space-3);
  background: none;
  border: 1px solid var(--error);
  color: var(--error);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
  margin-left: auto;
}

.delete-btn:hover {
  background: var(--error);
  color: white;
}

.confirm-delete {
  background: var(--error) !important;
  color: white !important;
  font-weight: 700;
}

.empty-hint {
  text-align: center;
  padding: var(--space-5);
  color: var(--text-secondary);
  font-style: italic;
}

.user-default-card {
  border-left: 3px solid var(--accent);
  margin-bottom: var(--space-5);
}

.theme-card {
  border-left: 3px solid var(--warning);
  margin-bottom: var(--space-5);
}

.path-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.path-field {
  font-family: var(--font-mono);
}

.browse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-2);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--transition);
}

.browse-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.save-btn {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: white;
  font-size: var(--text-xs);
  cursor: pointer;
  transition: opacity var(--transition);
}

.save-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.save-btn:not(:disabled):hover {
  opacity: 0.9;
}
</style>
