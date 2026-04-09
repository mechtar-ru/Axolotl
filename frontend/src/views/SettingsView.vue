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

          <div class="provider-details">
            <div class="field">
              <label>URL</label>
              <span>{{ provider.baseUrl }}</span>
            </div>
            <div class="field">
              <label>Модели</label>
              <div v-if="provider.models.length > 0" class="model-list">
                <span v-for="model in provider.models" :key="model" class="model-tag">{{ model }}</span>
              </div>
              <span v-else class="no-models">Нет доступных моделей</span>
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
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { settingsApi, type ProviderInfo } from '../services/api';

const router = useRouter();
const providers = ref<ProviderInfo[]>([]);
const loading = ref(true);
const error = ref('');

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
  color: #eee;
}

.back-btn {
  padding: 8px 16px;
  background: #2d2d44;
  color: #eee;
  border: 1px solid #4a4a6a;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.back-btn:hover {
  background: #3d3d5c;
}

.provider-card {
  background: #2d2d44;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
  border: 1px solid #4a4a6a;
}

.provider-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.provider-header h2 {
  font-size: 18px;
  color: #eee;
  flex: 1;
}

.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.online {
  background: #4caf50;
  box-shadow: 0 0 8px rgba(76, 175, 80, 0.5);
}

.status-dot.offline {
  background: #f44336;
  box-shadow: 0 0 8px rgba(244, 67, 54, 0.5);
}

.status-text {
  font-size: 13px;
  color: #888;
}

.refresh-btn {
  background: none;
  border: 1px solid #4a4a6a;
  color: #eee;
  border-radius: 6px;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 14px;
}

.refresh-btn:hover {
  background: #3d3d5c;
}

.provider-details {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field label {
  display: block;
  font-size: 12px;
  color: #888;
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field span {
  color: #eee;
  font-size: 14px;
}

.model-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.model-tag {
  background: #1a1a2e;
  color: #6c63ff;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 13px;
  font-family: monospace;
}

.no-models {
  color: #666;
  font-style: italic;
}

.loading, .error, .empty {
  text-align: center;
  padding: 40px;
  color: #888;
}

.error {
  color: #f44336;
}
</style>
