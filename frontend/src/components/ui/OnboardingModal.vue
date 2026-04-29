<template>
  <div v-if="visible" class="onboarding-overlay">
    <div class="onboarding-card">
      <div class="onboarding-header">
        <h1>🧬 Добро пожаловать в Axolotl!</h1>
        <p>Визуальная оркестрация AI-агентов</p>
      </div>

      <div class="onboarding-body">
        <div class="step" v-if="step === 1">
          <h2>Выберите AI провайдера</h2>
          <p class="step-desc">Это будет модель по умолчанию для новых агентов</p>

          <div class="provider-options">
            <button
              v-for="provider in availableProviders"
              :key="provider.name"
              class="provider-option"
              :class="{ selected: selectedProvider === provider.name }"
              @click="selectedProvider = provider.name"
            >
              <span class="provider-icon">{{ getProviderIcon(provider.name) }}</span>
              <span class="provider-name">{{ getProviderLabel(provider.name) }}</span>
              <span class="provider-model">{{ provider.defaultModel }}</span>
              <span class="provider-status" :class="provider.available ? 'online' : 'offline'">
                {{ provider.available ? '✓ Доступен' : '✗ Недоступен' }}
              </span>
            </button>

            <div v-if="availableProviders.length === 0" class="no-providers">
              <p>⚠️ Нет доступных провайдеров</p>
              <p class="hint">Убедитесь что Ollama запущен или добавьте API ключи в настройках</p>
            </div>
          </div>
        </div>

        <div class="step" v-if="step === 2">
          <h2>🎉 Готово!</h2>
          <p class="step-desc">Выбран провайдер: <strong>{{ getProviderLabel(selectedProvider) }}</strong></p>
          <p class="model-chosen">Модель: <code>{{ selectedModel }}</code></p>

          <div class="quick-start">
            <h3>Быстрый старт:</h3>
            <ol>
              <li>Создайте новую схему</li>
              <li>Добавьте узлы Source → Agent → Output</li>
              <li>Соедините их связями</li>
              <li>Нажмите ▶ Выполнить</li>
            </ol>
          </div>
        </div>
      </div>

      <div class="onboarding-footer">
        <button v-if="step === 1" class="btn-skip" @click="skipOnboarding">Пропустить</button>
        <button
          v-if="step === 1"
          class="btn-primary"
          :disabled="!selectedProvider"
          @click="step = 2"
        >
          Далее →
        </button>
        <button v-if="step === 2" class="btn-primary" @click="finishOnboarding">
          Начать работу 🚀
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { settingsApi, type ProviderInfo } from '../../services/api';

const props = defineProps<{
  visible: boolean;
}>();

const emit = defineEmits<{
  close: [];
  complete: [provider: string, model: string];
  skip: [];
}>();

const step = ref(1);
const providers = ref<ProviderInfo[]>([]);
const selectedProvider = ref('');

const availableProviders = computed(() =>
  providers.value.filter(p => p.available || p.name === 'ollama')
);

const selectedModel = computed(() => {
  const p = providers.value.find(p => p.name === selectedProvider.value);
  return p?.defaultModel || (p?.models?.[0] ?? '');
});

function getProviderIcon(name: string): string {
  const icons: Record<string, string> = {
    ollama: '🦙', openai: '🤖', anthropic: '🧠', deepseek: '🔍',
  };
  return icons[name] || '⚡';
}

function getProviderLabel(name: string): string {
  const labels: Record<string, string> = {
    ollama: 'Ollama (Local)',
    openai: 'OpenAI',
    anthropic: 'Anthropic',
    deepseek: 'DeepSeek',
  };
  return labels[name] || name;
}

function skipOnboarding() {
  localStorage.setItem('axolotl:onboarding', 'skipped');
  emit('skip');
  emit('close');
}

function finishOnboarding() {
  localStorage.setItem('axolotl:onboarding', 'done');
  localStorage.setItem('axolotl:default-provider', selectedProvider.value);
  emit('complete', selectedProvider.value, selectedModel.value);
  emit('close');
}

onMounted(async () => {
  try {
    providers.value = await settingsApi.getProviders();
    // Auto-select first available
    if (availableProviders.value.length > 0 && !selectedProvider.value) {
      selectedProvider.value = availableProviders.value[0]!.name;
    }
  } catch (e) {
    console.error('Failed to load providers:', e);
  }
});
</script>

<style scoped>
.onboarding-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
  backdrop-filter: blur(4px);
}

.onboarding-card {
  background: var(--bg-secondary);
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 16px;
  padding: 32px;
  max-width: 520px;
  width: 90%;
  max-height: 85vh;
  overflow-y: auto;
  box-shadow: 0 8px 40px rgba(108, 99, 255, 0.2);
}

.onboarding-header {
  text-align: center;
  margin-bottom: 24px;
}

.onboarding-header h1 {
  font-size: 24px;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.onboarding-header p {
  font-size: 14px;
  color: var(--text-muted-alt);
  margin: 0;
}

.onboarding-body {
  margin-bottom: 24px;
}

.step h2 {
  font-size: 18px;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.step-desc {
  font-size: 13px;
  color: var(--text-muted-alt);
  margin: 0 0 20px;
}

.provider-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.provider.option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--bg-card);
  border: 2px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  color: var(--text-primary);
  text-align: left;
  width: 100%;
}

.provider.option:hover {
  border-color: var(--accent);
  background: rgba(108, 99, 255, 0.1);
}

.provider.option.selected {
  border-color: var(--accent);
  background: rgba(108, 99, 255, 0.15);
  box-shadow: 0 0 12px rgba(108, 99, 255, 0.3);
}

.provider-icon {
  font-size: 24px;
}

.provider-name {
  flex: 1;
  font-weight: 600;
  font-size: 14px;
}

.provider-model {
  font-size: 12px;
  color: var(--accent);
  font-family: monospace;
}

.provider-status {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 4px;
}

.provider-status.online {
  background: rgba(76, 175, 80, 0.2);
  color: var(--success);
}

.provider-status.offline {
  background: rgba(244, 67, 54, 0.2);
  color: var(--error);
}

.no-providers {
  text-align: center;
  padding: 20px;
  color: var(--text-muted-alt);
}

.no-providers .hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 8px;
}

.model-chosen {
  font-size: 14px;
  color: var(--text-primary);
  margin: 12px 0 20px;
}

.model-chosen code {
  background: var(--bg-card);
  padding: 2px 8px;
  border-radius: 4px;
  color: var(--accent);
}

.quick-start {
  background: var(--bg-card);
  border-radius: 8px;
  padding: 16px;
}

.quick-start h3 {
  font-size: 14px;
  color: var(--text-primary);
  margin: 0 0 12px;
}

.quick-start ol {
  margin: 0;
  padding-left: 20px;
  color: var(--text-secondary);
  font-size: 13px;
}

.quick-start li {
  margin-bottom: 6px;
}

.onboarding-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

.btn-primary {
  flex: 1;
  padding: 12px 20px;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-primary:hover:not(:disabled) {
  background: var(--accent-hover);
  transform: translateY(-1px);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-skip {
  padding: 12px 20px;
  background: transparent;
  color: var(--text-muted-alt);
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.btn-skip:hover {
  background: rgba(255, 255, 255, 0.05);
}
</style>
