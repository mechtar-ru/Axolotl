<template>
  <Transition name="modal">
    <div v-if="modelValue" class="sb-overlay" @click.self="close">
      <div class="sb-modal">
        <!-- Header -->
        <div class="sb-header">
          <div class="sb-header-left">
            <span class="sb-icon">🏗️</span>
            <h3>Schema Builder</h3>
            <span class="sb-badge">AI</span>
          </div>
          <button class="sb-close" @click="close" title="Close (Esc)">✕</button>
        </div>

        <!-- State: input -->
        <div v-if="state === 'input'" class="sb-body">
          <p class="sb-description">
            Describe the application or workflow you want to build. The AI will generate a complete schema with nodes, edges, and detailed prompts.
          </p>

          <div class="sb-field">
            <label class="sb-label">What do you want to build?</label>
            <textarea
              ref="promptInput"
              v-model="prompt"
              class="sb-textarea"
              placeholder="e.g. A REST API backend with user authentication, CRUD endpoints for a task manager, unit tests, and error handling..."
              rows="6"
              @keydown.ctrl.enter="generate"
              @keydown.meta.enter="generate"
            ></textarea>
            <span class="sb-hint">Ctrl+Enter to generate</span>
          </div>

          <div class="sb-field">
            <label class="sb-label">Model (optional)</label>
            <select v-model="selectedModel" class="sb-select">
              <option value="">Default</option>
              <optgroup v-for="group in modelGroups" :key="group.name" :label="group.name">
                <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </optgroup>
            </select>
          </div>

          <div class="sb-actions">
            <button class="sb-btn sb-btn-secondary" @click="close">Cancel</button>
            <button
              class="sb-btn sb-btn-primary"
              :disabled="!prompt.trim()"
              @click="generate"
            >
              <span class="sb-btn-icon">✨</span>
              Generate Schema
            </button>
          </div>

          <!-- Example prompts -->
          <div class="sb-examples">
            <span class="sb-examples-label">Try these:</span>
            <button
              v-for="(ex, i) in examplePrompts"
              :key="i"
              class="sb-example-chip"
              @click="prompt = ex"
            >
              {{ ex.length > 60 ? ex.substring(0, 60) + '...' : ex }}
            </button>
          </div>
        </div>

        <!-- State: loading -->
        <div v-else-if="state === 'loading'" class="sb-body sb-body-center">
          <div class="sb-spinner"></div>
          <p class="sb-loading-text">Generating your schema...</p>
          <p class="sb-loading-hint">The AI is designing nodes, edges, and prompts</p>
        </div>

        <!-- State: error -->
        <div v-else-if="state === 'error'" class="sb-body">
          <div class="sb-error-box">
            <span class="sb-error-icon">⚠️</span>
            <p class="sb-error-text">{{ error }}</p>
          </div>
          <div class="sb-actions">
            <button class="sb-btn sb-btn-secondary" @click="state = 'input'">← Try Again</button>
            <button class="sb-btn sb-btn-primary" @click="generate">Retry</button>
          </div>
        </div>

        <!-- State: preview -->
        <div v-else-if="state === 'preview'" class="sb-body">
          <div class="sb-preview-header">
            <div class="sb-preview-info">
              <h4 class="sb-preview-name">{{ generatedSchema?.name }}</h4>
              <p class="sb-preview-desc">{{ generatedSchema?.description }}</p>
            </div>
            <div class="sb-preview-stats">
              <span class="sb-stat">{{ generatedSchema?.nodes?.length || 0 }} nodes</span>
              <span class="sb-stat-sep">·</span>
              <span class="sb-stat">{{ generatedSchema?.edges?.length || 0 }} edges</span>
            </div>
          </div>

          <!-- Node list -->
          <div class="sb-node-list">
            <div
              v-for="node in generatedSchema?.nodes || []"
              :key="node.id"
              class="sb-node-card"
              :class="'sb-node-' + node.type"
            >
              <div class="sb-node-header">
                <span class="sb-node-type-badge">{{ node.type }}</span>
                <span class="sb-node-name">{{ node.name }}</span>
              </div>
              <div v-if="node.data?.systemPrompt" class="sb-node-prompt">
                <span class="sb-node-prompt-label">System:</span>
                {{ truncate(node.data.systemPrompt, 120) }}
              </div>
              <div v-if="node.data?.userPrompt" class="sb-node-prompt">
                <span class="sb-node-prompt-label">User:</span>
                {{ truncate(node.data.userPrompt, 120) }}
              </div>
              <div v-if="node.data?.sourceData" class="sb-node-prompt">
                <span class="sb-node-prompt-label">Source:</span>
                {{ truncate(node.data.sourceData, 120) }}
              </div>
              <div v-if="node.data?.enabledTools?.length" class="sb-node-tools">
                <span v-for="tool in node.data.enabledTools" :key="tool" class="sb-tool-tag">{{ tool }}</span>
              </div>
            </div>
          </div>

          <!-- Plan explanation -->
          <div v-if="planExplanation" class="sb-explanation">
            <h5 class="sb-explanation-title">📝 Plan Explanation</h5>
            <div class="sb-explanation-text" v-html="renderMarkdown(planExplanation)"></div>
          </div>

          <div class="sb-actions">
            <button class="sb-btn sb-btn-secondary" @click="resetAndStartOver">← New Prompt</button>
            <button class="sb-btn sb-btn-success" @click="openSchema">
              <span class="sb-btn-icon">🚀</span>
              Open in Canvas
            </button>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { schemaApi, settingsApi } from '../../services/api';
import type { WorkflowSchema } from '../../types';

const props = defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'schema-created', schema: WorkflowSchema): void;
}>();

type State = 'input' | 'loading' | 'preview' | 'error';

const state = ref<State>('input');
const prompt = ref('');
const selectedModel = ref('');
const error = ref('');
const generatedSchema = ref<WorkflowSchema | null>(null);
const planExplanation = ref('');
const promptInput = ref<HTMLTextAreaElement | null>(null);

const providerOptions = ref<{ value: string; label: string; group: string }[]>([]);

const examplePrompts = [
  'A full-stack task manager app with Vue 3 frontend and Spring Boot backend, including user auth, CRUD operations, and unit tests',
  'A code review pipeline that reads PR diffs, analyzes code quality, checks for security issues, and generates a review report',
  'A data processing workflow that fetches CSV from URL, validates schema, transforms data, and outputs JSON to a file',
  'A chatbot backend with conversation memory, context-aware responses using RAG, guardrails for safety, and response logging',
];

onMounted(async () => {
  try {
    const providers = await settingsApi.getProviders();
    const opts: { value: string; label: string; group: string }[] = [];
    for (const p of providers) {
      const group = p.name.charAt(0).toUpperCase() + p.name.slice(1);
      if (p.models?.length > 0) {
        for (const model of p.models) {
          opts.push({ value: model, label: model, group });
        }
      } else {
        opts.push({ value: p.name, label: `${group} (default)`, group });
      }
    }
    providerOptions.value = opts;
  } catch {}
});

const modelGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {};
  for (const opt of providerOptions.value) {
    const g = groups[opt.group];
    if (g) g.push(opt);
    else groups[opt.group] = [opt];
  }
  return Object.entries(groups).map(([name, options]) => ({ name, options }));
});

watch(() => props.modelValue, (open) => {
  if (open) {
    state.value = 'input';
    nextTick(() => {
      promptInput.value?.focus();
    });
  }
});

function close() {
  emit('update:modelValue', false);
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.modelValue) {
    close();
  }
}

watch(() => props.modelValue, (open) => {
  if (open) {
    document.addEventListener('keydown', onKey);
  } else {
    document.removeEventListener('keydown', onKey);
  }
});

onUnmounted(() => {
  document.removeEventListener('keydown', onKey);
});

async function generate() {
  if (!prompt.value.trim()) return;

  state.value = 'loading';
  error.value = '';

  try {
    const result = await schemaApi.generateFromPrompt(
      prompt.value.trim(),
      selectedModel.value || undefined,
    );

    if (result.success && result.schema) {
      generatedSchema.value = result.schema;
      planExplanation.value = result.planExplanation || '';
      state.value = 'preview';
    } else {
      error.value = result.error || 'Unknown error occurred';
      state.value = 'error';
    }
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message || 'Network error';
    state.value = 'error';
  }
}

function openSchema() {
  if (generatedSchema.value) {
    emit('schema-created', generatedSchema.value);
    close();
  }
}

function resetAndStartOver() {
  state.value = 'input';
  generatedSchema.value = null;
  planExplanation.value = '';
  error.value = '';
}

function truncate(text: string, max: number): string {
  if (!text) return '';
  return text.length > max ? text.substring(0, max) + '...' : text;
}

function renderMarkdown(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.*?)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>');
}
</script>

<style scoped>
.sb-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-modal, 1000);
  backdrop-filter: blur(6px);
}

.sb-modal {
  background: #12121e;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.6);
  width: 640px;
  max-width: 95vw;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sb-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: linear-gradient(135deg, #f59e0b15, #f59e0b08);
  border-bottom: 1px solid rgba(245, 158, 11, 0.2);
}

.sb-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sb-header h3 {
  margin: 0;
  color: #eee;
  font-size: 18px;
  font-weight: 600;
}

.sb-icon {
  font-size: 22px;
}

.sb-badge {
  background: #f59e0b;
  color: #12121e;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.sb-close {
  background: none;
  border: none;
  color: #888;
  font-size: 18px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s;
}
.sb-close:hover {
  color: #eee;
  background: rgba(255, 255, 255, 0.1);
}

.sb-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.sb-body-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

.sb-description {
  color: #aaa;
  font-size: 14px;
  margin: 0 0 16px;
  line-height: 1.5;
}

.sb-field {
  margin-bottom: 16px;
}

.sb-label {
  display: block;
  color: #ccc;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 6px;
}

.sb-textarea {
  width: 100%;
  background: #0a0a14;
  color: #eee;
  border: 1px solid #333;
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.5;
  resize: vertical;
  min-height: 120px;
  font-family: inherit;
  transition: border-color 0.2s;
  box-sizing: border-box;
}
.sb-textarea:focus {
  outline: none;
  border-color: #f59e0b;
}
.sb-textarea::placeholder {
  color: #555;
}

.sb-hint {
  display: block;
  color: #555;
  font-size: 11px;
  margin-top: 4px;
  text-align: right;
}

.sb-select {
  width: 100%;
  background: #0a0a14;
  color: #eee;
  border: 1px solid #333;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
  box-sizing: border-box;
}

.sb-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 20px;
}

.sb-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border-radius: 10px;
  border: none;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.sb-btn-primary {
  background: #f59e0b;
  color: #12121e;
}
.sb-btn-primary:hover:not(:disabled) {
  background: #fbbf24;
  transform: translateY(-1px);
}
.sb-btn-primary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.sb-btn-secondary {
  background: rgba(255, 255, 255, 0.06);
  color: #aaa;
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.sb-btn-secondary:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #eee;
}

.sb-btn-success {
  background: #10b981;
  color: #fff;
}
.sb-btn-success:hover {
  background: #34d399;
  transform: translateY(-1px);
}

.sb-btn-icon {
  font-size: 16px;
}

/* Examples */
.sb-examples {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.sb-examples-label {
  display: block;
  color: #666;
  font-size: 12px;
  margin-bottom: 8px;
}

.sb-example-chip {
  display: inline-block;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.2);
  color: #f59e0b;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  margin: 0 6px 6px 0;
  cursor: pointer;
  transition: all 0.15s;
}
.sb-example-chip:hover {
  background: rgba(245, 158, 11, 0.15);
  border-color: #f59e0b;
}

/* Loading */
.sb-spinner {
  width: 48px;
  height: 48px;
  border: 3px solid rgba(245, 158, 11, 0.2);
  border-top-color: #f59e0b;
  border-radius: 50%;
  animation: sb-spin 0.8s linear infinite;
  margin-bottom: 16px;
}

@keyframes sb-spin {
  to { transform: rotate(360deg); }
}

.sb-loading-text {
  color: #eee;
  font-size: 16px;
  font-weight: 500;
  margin: 0 0 4px;
}

.sb-loading-hint {
  color: #666;
  font-size: 13px;
  margin: 0;
}

/* Error */
.sb-error-box {
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 10px;
  padding: 16px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;
}

.sb-error-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.sb-error-text {
  color: #fca5a5;
  font-size: 14px;
  margin: 0;
  word-break: break-word;
}

/* Preview */
.sb-preview-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.sb-preview-name {
  color: #eee;
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 4px;
}

.sb-preview-desc {
  color: #888;
  font-size: 13px;
  margin: 0;
}

.sb-preview-stats {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #f59e0b;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.sb-stat-sep {
  color: #555;
}

/* Node list */
.sb-node-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.sb-node-card {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 10px;
  padding: 12px;
  transition: border-color 0.15s;
}
.sb-node-card:hover {
  border-color: rgba(255, 255, 255, 0.12);
}

.sb-node-source { border-left: 3px solid #3b82f6; }
.sb-node-agent { border-left: 3px solid #8b5cf6; }
.sb-node-output { border-left: 3px solid #10b981; }
.sb-node-condition { border-left: 3px solid #f59e0b; }
.sb-node-loop { border-left: 3px solid #06b6d4; }
.sb-node-memory { border-left: 3px solid #ec4899; }
.sb-node-guardrail { border-left: 3px solid #ef4444; }
.sb-node-transform { border-left: 3px solid #6366f1; }

.sb-node-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.sb-node-type-badge {
  background: rgba(255, 255, 255, 0.08);
  color: #aaa;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.sb-node-name {
  color: #ddd;
  font-size: 13px;
  font-weight: 500;
}

.sb-node-prompt {
  color: #777;
  font-size: 12px;
  margin-top: 4px;
  line-height: 1.4;
}

.sb-node-prompt-label {
  color: #999;
  font-weight: 500;
  margin-right: 4px;
}

.sb-node-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.sb-tool-tag {
  background: rgba(139, 92, 246, 0.15);
  color: #a78bfa;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
}

/* Explanation */
.sb-explanation {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 16px;
}

.sb-explanation-title {
  color: #ccc;
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 8px;
}

.sb-explanation-text {
  color: #888;
  font-size: 12px;
  line-height: 1.5;
}

/* Transition */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-active .sb-modal,
.modal-leave-active .sb-modal {
  transition: transform 0.2s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .sb-modal,
.modal-leave-to .sb-modal {
  transform: scale(0.95) translateY(8px);
}
</style>
