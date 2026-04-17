<template>
  <div class="settings-page">
    <div class="settings-header">
      <button class="back-btn" @click="goBack">← Назад</button>
      <h1>⚙️ Настройки</h1>
    </div>

    <div class="settings-content">
      <div v-if="loading" class="loading">Загрузка...</div>

      <div v-else-if="error" class="error">{{ error }}</div>

      <div v-else>
        <!-- Built-in providers -->
        <div v-for="provider in builtInProviders" :key="provider.name" class="provider-card">
          <div class="provider-header">
            <span class="status-dot" :class="provider.available ? 'online' : 'offline'"></span>
            <h2>{{ getProviderLabel(provider.name) }}</h2>
            <span class="status-text">{{ provider.available ? 'Подключен' : 'Недоступен' }}</span>
            <button class="refresh-btn" @click="refreshProviders">🔄</button>
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
                <button class="toggle-vis" @click="showKeys[provider.name] = !showKeys[provider.name]" title="Показать/скрыть">
                  {{ showKeys[provider.name] ? '🙈' : '👁' }}
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
              <label>Модель по умолчанию</label>
              <select
                :value="editedModels[provider.name] ?? provider.defaultModel ?? ''"
                class="field-input"
                @change="editedModels[provider.name] = ($event.target as HTMLSelectElement).value"
              >
                <option value="">Авто</option>
                <option v-for="m in provider.models" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>

            <div class="field-actions">
              <button class="save-btn" @click="saveProvider(provider.name)" :disabled="saving[provider.name]">
                {{ saving[provider.name] ? 'Сохранение...' : '💾 Сохранить' }}
              </button>
              <button class="test-btn" @click="testProvider(provider.name)" :disabled="testing[provider.name]">
                {{ testing[provider.name] ? 'Проверка...' : '🔍 Тест' }}
              </button>
              <span v-if="testResults[provider.name]" class="test-result" :class="testResults[provider.name]?.ok ? 'test-ok' : 'test-fail'">
                {{ testResults[provider.name]?.ok ? '✅ Доступен' : '❌ ' + testResults[provider.name]?.msg }}
              </span>
            </div>

            <div v-if="provider.models.length > 0" class="field">
              <label>Доступные модели</label>
              <div class="model-list">
                <span v-for="model in provider.models" :key="model" class="model-tag">{{ model }}</span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="builtInProviders.length === 0" class="empty">
          Провайдеры не найдены. Убедитесь что Ollama запущен.
        </div>

        <!-- Custom LLM endpoints -->
        <div class="section-divider">
          <h2 class="section-title">🔌 Custom LLM APIs</h2>
          <button class="add-btn" @click="addCustomEndpoint">＋ Add new</button>
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
                <button class="toggle-vis" @click="customShowKeys[ep.id!] = !customShowKeys[ep.id!]" title="Показать/скрыть">
                  {{ customShowKeys[ep.id!] ? '🙈' : '👁' }}
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
                {{ customSaving[ep.id!] ? 'Сохранение...' : '💾 Сохранить' }}
              </button>
              <button class="test-btn" @click="testCustomEndpoint(ep)" :disabled="customTesting[ep.id!]">
                {{ customTesting[ep.id!] ? 'Проверка...' : '🔍 Тест' }}
              </button>
              <span v-if="customTestResults[ep.id!]" class="test-result" :class="customTestResults[ep.id!]?.success ? 'test-ok' : 'test-fail'">
                {{ customTestResults[ep.id!]?.success ? '✅ OK' : '❌ ' + customTestResults[ep.id!]?.message }}
              </span>
              <button class="delete-btn" @click="deleteCustomEndpoint(ep.id!)" title="Удалить">🗑</button>
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
import { settingsApi, customEndpointApi, type ProviderInfo, type CustomLlmEndpoint } from '../services/api';

const router = useRouter();
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

const builtInProviders = computed(() => providers.value.filter(p => !p.custom));

function getProviderLabel(name: string): string {
  const labels: Record<string, string> = {
    ollama: '🦙 Ollama (Local)',
    openai: '🤖 OpenAI',
    anthropic: '🧠 Anthropic',
    deepseek: '🔍 DeepSeek',
  };
  return labels[name] || name;
}

async function refreshProviders() {
  loading.value = true;
  error.value = '';
  try {
    providers.value = await settingsApi.getProviders();
  } catch (e: any) {
    error.value = 'Ошибка загрузки провайдеров: ' + (e.message || e);
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
    error.value = 'Ошибка сохранения: ' + (e.message || e);
  } finally {
    saving[name] = false;
  }
}

async function testProvider(name: string) {
  testing[name] = true;
  testResults[name] = null;
  try {
    const result = await settingsApi.testProvider(name);
    testResults[name] = { ok: result.available, msg: result.available ? 'OK' : 'Нет ключа или недоступен' };
  } catch (e: any) {
    testResults[name] = { ok: false, msg: e.message || 'Ошибка' };
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
});
</script>

<style scoped>
.settings-page {
  max-width: 700px;
  margin: 0 auto;
  padding: 30px;
}

.settings-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 30px;
}

.settings-header h1 {
  font-size: 24px;
  color: var(--text-primary, #eee);
}

.back-btn {
  padding: 8px 16px;
  background: var(--bg-card);
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 14px;
}

.back-btn:hover {
  background: var(--bg-hover);
}

.provider-card {
  background: var(--bg-card);
  border-radius: var(--radius-md);
  padding: 20px;
  margin-bottom: 16px;
  border: 1px solid var(--border);
}

.provider-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.provider-header h2 {
  font-size: 18px;
  color: var(--text-primary);
  flex: 1;
}

.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.online {
  background: var(--success);
  box-shadow: 0 0 8px rgba(76, 175, 80, 0.5);
}

.status-dot.offline {
  background: var(--error);
  box-shadow: 0 0 8px rgba(220, 53, 69, 0.5);
}

.status-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.refresh-btn {
  background: none;
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  cursor: pointer;
  font-size: 14px;
}

.refresh-btn:hover {
  background: var(--bg-hover);
}

.provider-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field label {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field-row {
  display: flex;
  gap: 6px;
}

.field-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 4px;
  padding: 8px 12px;
  font-size: 14px;
  outline: none;
}

.field-input:focus {
  border-color: var(--border-focus);
}

.toggle-vis {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 4px;
  cursor: pointer;
  padding: 0 8px;
  font-size: 14px;
}

.field-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.save-btn {
  padding: 8px 16px;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
}

.save-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.save-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-btn {
  padding: 8px 16px;
  background: var(--bg-hover);
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
}

.test-btn:hover:not(:disabled) {
  background: var(--border);
}

.test-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-result {
  font-size: 13px;
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
  gap: 6px;
}

.model-tag {
  background: var(--bg-primary);
  color: var(--accent);
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 13px;
  font-family: var(--font-mono, monospace);
}

.loading, .error, .empty {
  text-align: center;
  padding: 40px;
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
  margin: 30px 0 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}

.section-title {
  font-size: 18px;
  color: var(--text-primary);
}

.add-btn {
  padding: 6px 14px;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
}

.add-btn:hover {
  opacity: 0.9;
}

.custom-card {
  border-left: 3px solid var(--accent);
}

.inline-error {
  background: rgba(220, 53, 69, 0.15);
  color: var(--error);
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 13px;
  margin-bottom: 12px;
}

.toggle-label {
  display: flex !important;
  align-items: center;
  gap: 8px;
  text-transform: none !important;
  letter-spacing: 0 !important;
  font-size: 14px !important;
  cursor: pointer;
  color: var(--text-primary) !important;
}

.toggle-label input[type="checkbox"] {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
}

.delete-btn {
  padding: 6px 12px;
  background: none;
  border: 1px solid var(--error);
  color: var(--error);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
  margin-left: auto;
}

.delete-btn:hover {
  background: var(--error);
  color: white;
}

.empty-hint {
  text-align: center;
  padding: 24px;
  color: var(--text-secondary);
  font-style: italic;
}
</style>
