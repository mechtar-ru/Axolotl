<template>
  <div v-if="visible" class="onboarding-overlay">
    <div class="onboarding-card">
      <div class="onboarding-header">
        <h1>Welcome to Axolotl!</h1>
        <p>Visual AI agent orchestration</p>
      </div>

      <div class="onboarding-body">
        <!-- Step 1: Choose provider -->
        <div class="step" v-if="step === 1">
          <h2>Let's build your first AI workflow</h2>
          <p class="step-desc">First, pick an AI provider. This will be the default model for new agents.</p>

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
                {{ provider.available ? 'Available' : 'Unavailable' }}
              </span>
            </button>

            <div v-if="availableProviders.length === 0" class="no-providers">
              <p>No providers available</p>
              <p class="hint">Make sure Ollama is running or add API keys in Settings</p>
            </div>
          </div>
        </div>

        <!-- Step 2: Pick a starting point -->
        <div class="step" v-if="step === 2">
          <h2>Pick a starting point</h2>
          <p class="step-desc">Choose a template to get started instantly, or start from scratch</p>

          <div class="template-options">
            <button
              class="template-option"
              :class="{ selected: selectedTemplate === 'ai-pipeline' }"
              @click="selectedTemplate = 'ai-pipeline'"
            >
              <span class="template-option__icon">🤖</span>
              <span class="template-option__title">AI Pipeline</span>
              <span class="template-option__desc">Source → Agent → Output — a complete AI flow ready to run</span>
            </button>
            <button
              class="template-option"
              :class="{ selected: selectedTemplate === 'rag' }"
              @click="selectedTemplate = 'rag'"
            >
              <span class="template-option__icon">🧠</span>
              <span class="template-option__title">RAG Pipeline</span>
              <span class="template-option__desc">Source → Memory → Agent → Output — answer from context</span>
            </button>
            <button
              class="template-option"
              :class="{ selected: selectedTemplate === 'blank' }"
              @click="selectedTemplate = 'blank'"
            >
              <span class="template-option__icon">✨</span>
              <span class="template-option__title">Blank Canvas</span>
              <span class="template-option__desc">Start from scratch — full control over every node</span>
            </button>
          </div>
        </div>

        <!-- Step 3: Ready -->
        <div class="step" v-if="step === 3">
          <h2>You're all set!</h2>
          <p class="step-desc">
            <template v-if="selectedTemplate !== 'blank'">
              We'll create a <strong>{{ getTemplateLabel(selectedTemplate) }}</strong> and open it for you.
            </template>
            <template v-else>
              We'll open a blank canvas ready for your ideas.
            </template>
          </p>

          <div class="summary-card">
            <div class="summary-row">
              <span class="summary-label">Provider</span>
              <span class="summary-value">{{ getProviderLabel(selectedProvider) }}</span>
            </div>
            <div class="summary-row">
              <span class="summary-label">Model</span>
              <span class="summary-value"><code>{{ selectedModel }}</code></span>
            </div>
            <div class="summary-row">
              <span class="summary-label">Starting point</span>
              <span class="summary-value">{{ selectedTemplate === 'blank' ? 'Blank canvas' : getTemplateLabel(selectedTemplate) }}</span>
            </div>
          </div>

          <p class="next-steps-hint">You can always change the provider or model later in Settings.</p>
        </div>
      </div>

      <div class="onboarding-footer">
        <button v-if="step > 1" class="btn-back" @click="step--">Back</button>
        <button v-if="step === 1" class="btn-skip" @click="skipOnboarding">Skip</button>
        <button
          v-if="step < 3"
          class="btn-primary"
          :disabled="step === 1 && !selectedProvider"
          @click="step++"
        >
          Next
        </button>
        <button v-if="step === 3" class="btn-primary" @click="finishOnboarding">
          Start Building
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
  complete: [provider: string, model: string, template: string];
  skip: [];
}>();

const step = ref(1);
const providers = ref<ProviderInfo[]>([]);
const selectedProvider = ref('');
const selectedTemplate = ref('ai-pipeline');

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

function getTemplateLabel(template: string): string {
  const labels: Record<string, string> = {
    'ai-pipeline': 'AI Pipeline',
    'rag': 'RAG Pipeline',
    'blank': 'Blank Canvas',
  };
  return labels[template] || template;
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
  emit('complete', selectedProvider.value, selectedModel.value, selectedTemplate.value);
  emit('close');
}

onMounted(async () => {
  try {
    providers.value = await settingsApi.getProviders();
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
  background: #1e1e2e;
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 16px;
  padding: 32px;
  max-width: 540px;
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
  color: #e0e0e0;
  margin: 0 0 8px;
}

.onboarding-header p {
  font-size: 14px;
  color: #888;
  margin: 0;
}

.onboarding-body {
  margin-bottom: 24px;
}

.step h2 {
  font-size: 18px;
  color: #e0e0e0;
  margin: 0 0 8px;
}

.step-desc {
  font-size: 13px;
  color: #888;
  margin: 0 0 20px;
}

.provider-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.provider-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #2d2d44;
  border: 2px solid #4a4a6a;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  color: #eee;
  text-align: left;
  width: 100%;
}

.provider-option:hover {
  border-color: #6c63ff;
  background: rgba(108, 99, 255, 0.1);
}

.provider-option.selected {
  border-color: #6c63ff;
  background: rgba(108, 99, 255, 0.15);
  box-shadow: 0 0 12px rgba(108, 99, 255, 0.3);
}

.provider-icon { font-size: 24px; }
.provider-name { flex: 1; font-weight: 600; font-size: 14px; }
.provider-model { font-size: 12px; color: #6c63ff; font-family: monospace; }

.provider-status { font-size: 11px; padding: 3px 8px; border-radius: 4px; }
.provider-status.online { background: rgba(76, 175, 80, 0.2); color: #4caf50; }
.provider-status.offline { background: rgba(244, 67, 54, 0.2); color: #f44336; }

.no-providers { text-align: center; padding: 20px; color: #888; }
.no-providers .hint { font-size: 12px; color: #666; margin-top: 8px; }

/* Template options */
.template-options {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.template-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 16px;
  background: #2d2d44;
  border: 2px solid #4a4a6a;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  color: #eee;
  text-align: left;
  width: 100%;
  font-family: inherit;
  font-size: inherit;
}

.template-option:hover {
  border-color: #6c63ff;
  background: rgba(108, 99, 255, 0.1);
}

.template-option.selected {
  border-color: #6c63ff;
  background: rgba(108, 99, 255, 0.15);
  box-shadow: 0 0 12px rgba(108, 99, 255, 0.3);
}

.template-option__icon { font-size: 28px; }
.template-option__title { font-size: 14px; font-weight: 600; color: #e0e0e0; }
.template-option__desc { font-size: 12px; color: #888; line-height: 1.4; }

/* Summary card */
.summary-card {
  background: #2d2d44;
  border-radius: 10px;
  padding: 16px;
  margin: 16px 0;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}

.summary-row + .summary-row {
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.summary-label { font-size: 13px; color: #888; }
.summary-value { font-size: 13px; color: #e0e0e0; font-weight: 500; }
.summary-value code { background: #1e1e2e; padding: 2px 8px; border-radius: 4px; color: #6c63ff; }

.next-steps-hint {
  font-size: 12px;
  color: #666;
  text-align: center;
  margin: 0;
}

/* Node guide */
.node-guide {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.node-guide-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: #2d2d44;
  border-radius: 8px;
  border-left: 3px solid #6c63ff;
}

.node-guide-icon { font-size: 22px; }

.node-guide-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.node-guide-name { font-size: 14px; font-weight: 600; color: #e0e0e0; }
.node-guide-desc { font-size: 12px; color: #888; }

.more-nodes-hint {
  margin-top: 12px;
  padding: 8px 12px;
  background: rgba(108, 99, 255, 0.1);
  border-radius: 6px;
  font-size: 12px;
  color: #aaa;
  text-align: center;
}

.model-chosen { font-size: 14px; color: #e0e0e0; margin: 12px 0 20px; }
.model-chosen code { background: #2d2d44; padding: 2px 8px; border-radius: 4px; color: #6c63ff; }

.quick-start { background: #2d2d44; border-radius: 8px; padding: 16px; }
.quick-start h3 { font-size: 14px; color: #e0e0e0; margin: 0 0 12px; }
.quick-start ol { margin: 0; padding-left: 20px; color: #aaa; font-size: 13px; }
.quick-start li { margin-bottom: 6px; }

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
  background: #6c63ff;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-primary:hover:not(:disabled) { background: #5a52e0; transform: translateY(-1px); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-back {
  padding: 12px 20px;
  background: transparent;
  color: #888;
  border: 1px solid #4a4a6a;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.btn-back:hover { background: rgba(255, 255, 255, 0.05); }

.btn-skip {
  padding: 12px 20px;
  background: transparent;
  color: #888;
  border: 1px solid #4a4a6a;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.btn-skip:hover { background: rgba(255, 255, 255, 0.05); }
</style>
