<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { appApi, schemaApi } from '@/services/api'
import type { WorkflowSchema } from '@/types'

const props = defineProps<{
  visible: boolean
  appId: string
}>()

const emit = defineEmits<{
  close: []
  'add-to-canvas': [schema: WorkflowSchema]
}>()

const createMode = computed(() => !props.appId)

// Presets — pure app descriptions, no pipeline structure
interface Preset {
  id: string
  name: string
  description: string
}
const presets: Preset[] = [
  {
    id: 'emotion-diary',
    name: 'Emotion Diary',
    description: 'Мобильное приложение-дневник эмоций с 6 категориями (радость, грусть, гнев, страх, удивление, спокойствие). Календарь с тепловой картой настроения, ежедневные вопросы для рефлексии, статистика за неделю/месяц, персонализированные советы. Тёмная и светлая тема. Офлайн-первый с локальным хранилищем.',
  },
  {
    id: 'chat-bot',
    name: 'Chat Bot',
    description: 'AI-чат бот с памятью разговора. Поддержка контекста, личности ассистента, управление историей диалога. Веб-интерфейс с адаптивным дизайном.',
  },
  {
    id: 'content-gen',
    name: 'Content Generator',
    description: 'Генератор контента для статей и соцсетей. Ввод темы и ключевых слов, исследование, создание структуры, написание полного текста с проверкой тона и грамматики. Сохранение в файл.',
  },
  {
    id: 'sokoban',
    name: 'Sokoban Game',
    description: 'Классическая игра Sokoban для браузера на HTML/CSS/JS. 5 уровней, управление стрелками, отмена ходов (Z), сброс (R), счётчик ходов,检测 победы. Адаптивный дизайн.',
  },
]

const prompt = ref('')
const appName = ref('')
const loading = ref(false)
const error = ref<string | null>(null)

// Reset state when dialog opens
watch(() => props.visible, (newVal) => {
  if (newVal) {
    prompt.value = ''
    appName.value = ''
    loading.value = false
    error.value = null
  }
})

function applyPreset(presetId: string) {
  const preset = presets.find(p => p.id === presetId)
  if (preset) {
    prompt.value = preset.description
  }
}

async function generate() {
  if (!prompt.value.trim()) return
  if (createMode.value && !appName.value.trim()) return

  loading.value = true
  error.value = null

  try {
    let targetId = props.appId

    // Create mode: create a blank schema first
    if (createMode.value) {
      // Check for path conflict before creating
      try {
        const pathCheck = await appApi.checkTargetPath(appName.value.trim(), 'CUSTOM')
        if (pathCheck.exists) {
          error.value = `Directory "${pathCheck.targetPath}" already exists. Create the app from the Dashboard to choose a conflict resolution strategy.`
          loading.value = false
          return
        }
      } catch {
        // Path check failed — proceed anyway, createApp will handle it
      }
      const created = await appApi.createApp({
        name: appName.value.trim(),
        appType: 'CUSTOM',
        description: '',
      })
      if (!created || !created.id) {
        error.value = 'Failed to create app. Please try again.'
        loading.value = false
        return
      }
      targetId = created.id
    }

    // Fetch the current schema
    const schema = await schemaApi.getSchema(targetId)

    // Apply fixed 5-node pipeline: Receive → Review → Agent → Verify → Output
    const description = prompt.value.trim()

    schema.nodes = ([
      {
        id: 'receive-1',
        type: 'source' as any,
        name: 'Receive',
        position: { x: 100, y: 200 },
        data: {
          sourceData: description,
          sourceType: 'text',
          config: { sourceType: 'text' },
        } as Record<string, any>,
      },
      {
        id: 'review-1',
        type: 'review' as any,
        name: 'Review Plan',
        position: { x: 350, y: 200 },
        data: {
          checks: { premortem: true, prism: false, postmortem: false },
          mode: 'manual',
          maxAutoIterations: 3,
          generatePlan: true,
          config: { premortem: true },
        } as Record<string, any>,
      },
      {
        id: 'think-1',
        type: 'agent' as any,
        name: 'Agent',
        position: { x: 600, y: 200 },
        data: {
          systemPrompt: 'You are a senior developer. Use the tools available to implement the described application. Write production-quality code.',
          userPrompt: 'Implement the application described in the Receive node:\n\n{{sourceData}}',
          model: null,
          agentType: 'coder',
          enabledTools: ['file_write', 'directory_read', 'file_read', 'bash'],
          config: {},
        } as Record<string, any>,
      },
      {
        id: 'verify-1',
        type: 'verifier' as any,
        name: 'Verify',
        position: { x: 850, y: 200 },
        data: {
          checks: { syntaxCheck: true, testCommand: '', premortem: true },
          rewriteOnFail: true,
          maxRewriteRetries: 3,
          config: {},
          validationCriteria: description,
        } as Record<string, any>,
      },
      {
        id: 'act-1',
        type: 'output' as any,
        name: 'Output',
        position: { x: 1100, y: 200 },
        data: {
          mode: 'summary_report',
          reportPath: 'pipeline-report.md',
          includeReview: true,
          includeFiles: true,
          includeVerification: true,
          includeMetrics: true,
          config: {},
        } as Record<string, any>,
      },
    ]) as any

    schema.edges = [
      { id: 'e1', source: 'receive-1', target: 'review-1', type: 'data' as any },
      { id: 'e2', source: 'review-1', target: 'think-1', type: 'data' as any },
      { id: 'e3', source: 'think-1', target: 'verify-1', type: 'data' as any },
      { id: 'e4', source: 'verify-1', target: 'act-1', type: 'data' as any },
    ]

    const updated = await schemaApi.updateSchema(targetId, schema)

    if (updated) {
      emit('add-to-canvas', updated)
    } else {
      error.value = 'Failed to save pipeline. Please try again.'
    }
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || 'Network error. Please try again.'
  } finally {
    loading.value = false
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && !loading.value) {
    emit('close')
  }
}

defineExpose({
  prompt,
  appName,
  loading,
  error,
  generate,
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="quickstart-overlay" @click.self="!loading && emit('close')" @keydown="handleKeydown">
      <div class="quickstart-dialog">
        <!-- Header -->
        <div class="dialog-header">
          <h2 class="dialog-title">Quick Start</h2>
          <button class="close-btn" @click="emit('close')" :disabled="loading" aria-label="Close">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
              <path d="M18 6L6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>

        <!-- Input area -->
        <div class="dialog-body">
          <p class="dialog-hint">
            Describe what you want to build. We'll create a pipeline:
            <strong>Receive → Review Plan → Agent → Verify → Output</strong>
          </p>

          <!-- App Name (create mode only) -->
          <div v-if="createMode" class="field-row">
            <label class="input-label" for="quickstart-name">App Name:</label>
            <input
              id="quickstart-name"
              v-model="appName"
              class="text-input"
              type="text"
              placeholder="My App"
              :disabled="loading"
            />
          </div>

          <!-- Preset selector -->
          <div class="field-row">
            <label class="input-label" for="quickstart-preset">Preset:</label>
            <select
              id="quickstart-preset"
              class="text-input"
              @change="applyPreset(($event.target as HTMLSelectElement).value)"
              :disabled="loading"
            >
              <option value="">Custom</option>
              <option v-for="p in presets" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </div>

          <label class="input-label" for="quickstart-prompt">Describe your app:</label>
          <textarea
            id="quickstart-prompt"
            v-model="prompt"
            class="prompt-input"
            rows="5"
            placeholder="E.g. Build a Sokoban game in Python with pygame, 5 levels..."
            :disabled="loading"
          />

          <!-- Generate button -->
          <button
            class="generate-btn"
            :disabled="!prompt.trim() || loading || (createMode && !appName.trim())"
            @click="generate"
          >
            <template v-if="loading">
              <span class="spinner" />
              Creating pipeline...
            </template>
            <template v-else>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <path d="M13 2L12 10H21L11 22L12 14H3L13 2Z" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              Create Pipeline
            </template>
          </button>

          <!-- Error section -->
          <div v-if="error" class="error-section">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <circle cx="12" cy="12" r="10"/>
              <path d="M15 9l-6 6M9 9l6 6" stroke-linecap="round"/>
            </svg>
            {{ error }}
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.quickstart-overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-modal);
}

.quickstart-dialog {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  width: 100%;
  max-width: 560px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-5) var(--space-6);
  border-bottom: 1px solid var(--border-color);
}

.dialog-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
}

.dialog-hint {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}

.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.close-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.dialog-body {
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.input-label {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-secondary);
}

.field-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.text-input {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: var(--text-sm);
  box-sizing: border-box;
}

.text-input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 20%, transparent);
}

.text-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.prompt-input {
  width: 100%;
  padding: var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background: var(--bg-input);
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-family: inherit;
  resize: vertical;
  min-height: 100px;
  box-sizing: border-box;
}

.prompt-input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 20%, transparent);
}

.prompt-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  border: none;
  border-radius: var(--radius-md);
  background: var(--accent);
  color: white;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast), opacity var(--transition-fast);
}

.generate-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.generate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner {
  width: var(--icon-sm);
  height: var(--icon-sm);
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-section {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--error-light);
  color: var(--error);
  font-size: var(--text-sm);
  line-height: 1.4;
}

.error-section svg {
  flex-shrink: 0;
  margin-top: 2px;
}
</style>
