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
        <div v-for="provider in providers" :key="provider.name" class="provider-card">
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
              <span v-if="testResults[provider.name]" class="test-result" :class="testResults[provider.name].ok ? 'test-ok' : 'test-fail'">
                {{ testResults[provider.name].ok ? '✅ Доступен' : '❌ ' + testResults[provider.name].msg }}
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

        <div v-if="providers.length === 0" class="empty">
          Провайдеры не найдены. Убедитесь что Ollama запущен.
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { settingsApi, type ProviderInfo } from '../services/api';

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

function goBack() {
  router.push('/');
}

onMounted(refreshProviders);
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

.no-models {
  color: var(--text-muted);
  font-style: italic;
}

.loading, .error, .empty {
  text-align: center;
  padding: 40px;
  color: var(--text-secondary);
}

.error {
  color: var(--error);
}
</style>
