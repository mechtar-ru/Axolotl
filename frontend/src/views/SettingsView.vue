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
      <div v-if="loading" class="loading">Loading...</div>

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

        <!-- Built-in providers -->
        <div v-for="provider in builtInProviders" :key="provider.name" class="provider-card">
          <div class="provider-header">
            <span class="status-dot" :class="provider.available ? 'online' : 'offline'"></span>
            <h2>
              <svg v-if="provider.name === 'ollama'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2C8.13 2 5 5.13 5 9c0 3.87 3.13 7 7 7s7-3.13 7-7c0-3.87-3.13-7-7-7z"/><path d="M8 9c0-2.2 1.8-4 4-4s4 1.8 4 4"/><circle cx="9" cy="9" r="1"/><circle cx="15" cy="9" r="1"/></svg>
              <svg v-else-if="provider.name === 'openai'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a4 4 0 0 1 4 4c0 .89-.3 1.73-.8 2.4A4 4 0 0 1 16 12a4 4 0 0 1-4 4 4 4 0 0 1-.8-3.6A4 4 0 0 1 8 12a4 4 0 0 1 4-4c.3 0 .58.03.86.1"/><circle cx="12" cy="12" r="10"/></svg>
              <svg v-else-if="provider.name === 'anthropic'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a10 10 0 1 0 10 10"/><path d="M12 12 8 8l4 4 4-4-4 4Z"/><path d="M12 2v10"/></svg>
              <svg v-else-if="provider.name === 'deepseek'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 2v4m0 12v4m-6-8H2m20 0h-4"/></svg>
              {{ getProviderLabel(provider.name) }}
            </h2>
            <span class="status-text">{{ provider.available ? 'Connected' : 'Unavailable' }}</span>
            <button class="refresh-btn" @click="refreshProviders" title="Refresh">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
            </button>
          </div>

          <div class="provider-fields">
            <div class="field">
              <label>API Key</label>
              <div class="field-row">
                <input
                  :type="showKeys[provider.name] ? 'text' : 'password'"
                  :value="editedKeys[provider.name] ?? ''"
                  :placeholder="editedKeys[provider.name] !== undefined ? '' : 'sk-...'"
                  class="field-input"
                  @input="editedKeys[provider.name] = ($event.target as HTMLInputElement).value"
                />
                <button class="icon-btn" @click="showKeys[provider.name] = !showKeys[provider.name]" title="Show/Hide">
                  <svg v-if="showKeys[provider.name]" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                  <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                </button>
              </div>
            </div>

            <div class="field">
              <label>Base URL</label>
              <input
                :value="editedUrls[provider.name] ?? provider.baseUrl ?? ''"
                class="field-input"
                placeholder="https://api.example.com/v1"
                @input="editedUrls[provider.name] = ($event.target as HTMLInputElement).value"
              />
            </div>

            <div class="field">
              <label>Default Model</label>
              <select
                :value="editedModels[provider.name] ?? provider.defaultModel ?? ''"
                class="field-input"
                @change="editedModels[provider.name] = ($event.target as HTMLSelectElement).value"
              >
                <option value="">Auto</option>
                <option v-for="m in provider.models" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>

            <div class="field-actions">
              <button class="save-btn" @click="saveProvider(provider.name)" :disabled="saving[provider.name]">
                <svg v-if="!saving[provider.name]" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
                {{ saving[provider.name] ? 'Saving...' : 'Save' }}
              </button>
              <button class="test-btn" @click="testProvider(provider.name)" :disabled="testing[provider.name]">
                <svg v-if="!testing[provider.name]" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                {{ testing[provider.name] ? 'Testing...' : 'Test' }}
              </button>
              <span v-if="testResults[provider.name]" class="test-result" :class="testResults[provider.name]?.ok ? 'test-ok' : 'test-fail'">
                <svg v-if="testResults[provider.name]?.ok" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                {{ testResults[provider.name]?.ok ? 'Available' : testResults[provider.name]?.msg }}
              </span>
            </div>

            <div v-if="provider.models.length > 0" class="field">
              <label>Available Models</label>
              <div class="model-list">
                <span v-for="model in provider.models" :key="model" class="model-tag">{{ model }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="builtInProviders.length === 0" class="empty">
          No providers found. Make sure Ollama is running.
        </div>

        <!-- Custom LLM endpoints -->
        <div class="section-divider">
          <h2 class="section-title">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
            Custom LLM APIs
          </h2>
          <button class="add-btn" @click="addCustomEndpoint">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            Add new
          </button>
        </div>

        <div v-for="ep in customEndpoints" :key="ep.id" class="provider-card custom-card">
          <div v-if="customErrors[ep.id!]" class="inline-error">{{ customErrors[ep.id!] }}</div>

          <div class="provider-fields">
            <div class="field">
              <label>Name</label>
              <input
                :value="ep.name"
                class="field-input"
                placeholder="My LLM Provider"
                @input="updateCustomField(ep.id!, 'name', ($event.target as HTMLInputElement).value)"
              />
            </div>

            <div class="field">
              <label>Base URL</label>
              <input
                :value="ep.baseUrl"
                class="field-input"
                placeholder="https://api.example.com/v1"
                @input="updateCustomField(ep.id!, 'baseUrl', ($event.target as HTMLInputElement).value)"
              />
            </div>

            <div class="field">
              <label>API Key</label>
              <div class="field-row">
                <input
                  :type="customShowKeys[ep.id!] ? 'text' : 'password'"
                  :value="customEditedKeys[ep.id!] ?? ''"
                  :placeholder="ep.apiKey ? '•••••••• (saved)' : 'Enter API key...'"
                  class="field-input"
                  @input="customEditedKeys[ep.id!] = ($event.target as HTMLInputElement).value"
                />
                <button class="icon-btn" @click="customShowKeys[ep.id!] = !customShowKeys[ep.id!]" title="Show/Hide">
                  <svg v-if="customShowKeys[ep.id!]" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                  <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                </button>
              </div>
            </div>

            <div class="field">
              <label>Model Name</label>
              <input
                :value="ep.modelName"
                class="field-input"
                placeholder="gpt-4, claude-3, etc."
                @input="updateCustomField(ep.id!, 'modelName', ($event.target as HTMLInputElement).value)"
              />
            </div>

            <div class="field">
              <label>Auth Type</label>
              <select
                :value="ep.authType"
                class="field-input"
                @change="updateCustomField(ep.id!, 'authType', ($event.target as HTMLSelectElement).value)"
              >
                <option value="bearer">Bearer Token</option>
                <option value="api-key">API Key Header</option>
                <option value="none">None</option>
              </select>
            </div>

            <div class="field">
              <label class="toggle-label">
                <input type="checkbox" :checked="ep.enabled" @change="updateCustomField(ep.id!, 'enabled', ($event.target as HTMLInputElement).checked)" />
                Enabled
              </label>
            </div>

            <div class="field-actions">
              <button class="save-btn" @click="saveCustomEndpoint(ep)" :disabled="customSaving[ep.id!]">
                <svg v-if="!customSaving[ep.id!]" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
                {{ customSaving[ep.id!] ? 'Saving...' : 'Save' }}
              </button>
              <button class="test-btn" @click="testCustomEndpoint(ep)" :disabled="customTesting[ep.id!]">
                <svg v-if="!customTesting[ep.id!]" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                {{ customTesting[ep.id!] ? 'Testing...' : 'Test' }}
              </button>
              <span v-if="customTestResults[ep.id!]" class="test-result" :class="customTestResults[ep.id!]?.success ? 'test-ok' : 'test-fail'">
                <svg v-if="customTestResults[ep.id!]?.success" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                {{ customTestResults[ep.id!]?.success ? 'OK' : customTestResults[ep.id!]?.message }}
              </span>
              <button class="delete-btn" @click="deleteCustomEndpoint(ep.id!)" title="Delete">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              </button>
            </div>
          </div>
        </div>

        <div v-if="customEndpoints.length === 0" class="empty-hint">
          No custom LLM endpoints configured. Click "Add new" to add one.
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useSettingsStore } from '@/stores/settingsStore';
import ThemeToggle from '@/components/ui/ThemeToggle.vue';
import { settingsApi, customEndpointApi, type ProviderInfo, type CustomLlmEndpoint } from '../services/api';

function debounce<T extends (...args: any[]) => any>(fn: T, delay: number): (...args: Parameters<T>) => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  return (...args: Parameters<T>) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

const router = useRouter();
const settingsStore = useSettingsStore();
const providers = ref<ProviderInfo[]>([]);
const loading = ref(true);
const error = ref('');
const saving = reactive<Record<string, boolean>>({});
const testing = reactive<Record<string, boolean>>({});
const testResults = reactive<Record<string, { ok: boolean; msg: string } | null>>({});
const editedKeys = reactive<Record<string, string>>({});
const editedUrls = reactive<Record<string, string>>({});
const editedModels = reactive<Record<string, string>>({});
const showKeys = reactive<Record<string, boolean>>({});

// Custom endpoints state
const customEndpoints = ref<CustomLlmEndpoint[]>([]);
const customSaving = reactive<Record<string, boolean>>({});
const customTesting = reactive<Record<string, boolean>>({});
const customTestResults = reactive<Record<string, { success: boolean; message: string } | null>>({});
const customErrors = reactive<Record<string, string>>({});
const customEditedKeys = reactive<Record<string, string>>({});
const customShowKeys = reactive<Record<string, boolean>>({});

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

async function _saveUserDefaultModel() {
  try {
    await settingsApi.setUserDefaultModel(userDefaultModel.value);
  } catch (e: any) {
    error.value = 'Save error: ' + (e.message || e);
  }
}
const saveUserDefaultModel = debounce(_saveUserDefaultModel, 400);

const builtInProviders = computed(() => providers.value.filter(p => !p.custom));

function getProviderLabel(name: string): string {
  const labels: Record<string, string> = {
    ollama: 'Ollama (Local)',
    openai: 'OpenAI',
    anthropic: 'Anthropic',
    deepseek: 'DeepSeek',
  };
  return labels[name] || name;
}

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

async function saveProvider(name: string) {
  saving[name] = true;
  try {
    const data: Record<string, string> = {};
    if (editedKeys[name] !== undefined) data.apiKey = editedKeys[name];
    if (editedUrls[name] !== undefined) data.baseUrl = editedUrls[name];
    if (editedModels[name] !== undefined) data.defaultModel = editedModels[name];
    await settingsApi.updateProvider(name, data);
    await refreshProviders();
    editedKeys[name] = '';
  } catch (e: any) {
    error.value = 'Save error: ' + (e.message || e);
  } finally {
    saving[name] = false;
  }
}

async function testProvider(name: string) {
  testing[name] = true;
  testResults[name] = null;
  try {
    const result = await settingsApi.testProvider(name);
    testResults[name] = { ok: result.available, msg: result.available ? 'OK' : 'No key or unavailable' };
  } catch (e: any) {
    testResults[name] = { ok: false, msg: e.message || 'Error' };
  } finally {
    testing[name] = false;
  }
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
  customSaving[ep.id!] = true;
  customErrors[ep.id!] = '';
  try {
    const payload = { ...ep };
    // Only send API key if user typed a new one
    const newKey = customEditedKeys[ep.id!];
    if (newKey !== undefined && newKey !== '') {
      payload.apiKey = newKey;
    }
    const isNew = !customEndpoints.value.some(e => e.id === ep.id && e.name && e.name.trim());
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
  }
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
  // Build flat model list from all providers
  const opts: { value: string; label: string; group: string }[] = [];
  for (const p of providers.value) {
    const group = p.name.charAt(0).toUpperCase() + p.name.slice(1);
    if (p.models?.length > 0) {
      for (const model of p.models) {
        opts.push({ value: model, label: model, group });
      }
    }
  }
  allModelOptions.value = opts;
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

.status-text {
  font-size: var(--text-xs);
  color: var(--text-secondary);
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

.model-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.model-tag {
  background: var(--bg-primary);
  color: var(--accent);
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-family: var(--font-mono);
}

.loading, .error, .empty {
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
</style>
