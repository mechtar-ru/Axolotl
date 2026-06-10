<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { appApi, schemaApi } from '@/services/api'
import { useSettingsStore } from '@/stores/settingsStore'
import type { WorkflowSchema, FlowNode, FlowEdge } from '@/types'

const props = defineProps<{
  visible: boolean
  appId: string
}>()

const emit = defineEmits<{
  close: []
  'add-to-canvas': [schema: WorkflowSchema]
}>()

const settingsStore = useSettingsStore()
const createMode = computed(() => !props.appId)

// ─── State ───

const prompt = ref('')
const appName = ref('')
const loading = ref(false)
const error = ref<string | null>(null)

const projectType = ref<'CUSTOM' | 'FLUTTER' | 'WEB' | 'PYTHON'>('CUSTOM')
const pipelineTemplate = ref<'standard' | 'app-creation' | 'minimal'>('standard')
const selectedModel = ref<string>('')

interface Preset {
  id: string
  name: string
  description: string
  projectType: 'CUSTOM' | 'FLUTTER' | 'WEB' | 'PYTHON'
  pipelineTemplate: 'standard' | 'app-creation' | 'minimal'
}

const presets: Preset[] = [
  {
    id: 'eios',
    name: 'EIOS (Flutter)',
    description: 'Flutter EIOS: local-first emotion tracker. 4 screens: emotion check-in (State), regulation protocols (Reset), trends (Patterns), library (Library). 7 emotions (обида/тоска/раздражение/тревожность/опустошение/подавленность/напряжение), 6 body zones (chest/jaw/stomach/throat/shoulders/head). SQLite+SQLCipher. Dark theme: graphite+indigo+amber. Russian UX, anti-rumination. 10 seconds vs 10 minutes UX principle.',
    projectType: 'FLUTTER',
    pipelineTemplate: 'app-creation',
  },
  {
    id: 'chat-bot',
    name: 'Chat Bot',
    description: 'AI-чат бот с памятью разговора. Поддержка контекста, личности ассистента, управление историей диалога. Веб-интерфейс с адаптивным дизайном.',
    projectType: 'CUSTOM',
    pipelineTemplate: 'standard',
  },
  {
    id: 'content-gen',
    name: 'Content Generator',
    description: 'Генератор контента для статей и соцсетей. Ввод темы и ключевых слов, исследование, создание структуры, написание полного текста с проверкой тона и грамматики. Сохранение в файл.',
    projectType: 'CUSTOM',
    pipelineTemplate: 'standard',
  },
  {
    id: 'sokoban',
    name: 'Sokoban Game',
    description: 'Классическая игра Sokoban для браузера на HTML/CSS/JS. 5 уровней, управление стрелками, отмена ходов (Z), сброс (R), счётчик ходов,检测 победы. Адаптивный дизайн.',
    projectType: 'WEB',
    pipelineTemplate: 'minimal',
  },
]

const projectTypes = [
  { value: 'CUSTOM', label: 'Generic' },
  { value: 'FLUTTER', label: 'Flutter / Dart' },
  { value: 'WEB', label: 'Web (HTML/CSS/JS)' },
  { value: 'PYTHON', label: 'Python' },
]

const pipelineTemplates = [
  { value: 'standard', label: 'Standard', desc: 'Receive → Review → Agent → Verify → Output' },
  { value: 'app-creation', label: 'App Creation', desc: 'Receive → Review → Planner → Review → Prep → Agent → Verify → Doc-Agent → Output' },
  { value: 'minimal', label: 'Minimal', desc: 'Receive → Agent → Verify → Output' },
]

const modelOptions = ref<{ value: string; label: string; group: string }[]>([])
const modelLoading = ref(false)

const modelGroups = computed(() => {
  const groups: { label: string; options: { value: string; label: string }[] }[] = []
  for (const m of modelOptions.value) {
    let g = groups.find(g => g.label === m.group)
    if (!g) {
      g = { label: m.group, options: [] }
      groups.push(g)
    }
    g.options.push({ value: m.value, label: m.label })
  }
  return groups
})

// ─── Computed ───

const agentTools = computed(() => {
  const base = ['file_write', 'directory_read', 'file_read', 'bash']
  if (projectType.value === 'FLUTTER') {
    return [...base, 'grep', 'web_search', 'web_fetch', 'build_app']
  }
  return base
})

// ─── FLUTTER-specific instructions ───

const FLUTTER_BASE_PROMPT = `
==== FLUTTER WORKFLOW — FOLLOW EVERY STEP IN ORDER ====

STEP 1 — SURVEY EXISTING CODE
  - Run \`directory_read lib/\` to see what already exists
  - Run \`file_read pubspec.yaml\` to check current dependencies

STEP 2 — INSTALL DEPENDENCIES
  - Run \`bash "flutter pub add <package>"\` for EACH required package
  - Typical needs: drift, path_provider, intl, provider/riverpod, fl_chart, flutter_secure_storage, flutter_markdown
  - For SQLCipher: drift_sqlcipher, sqlcipher_flutter_libs

STEP 3 — WRITE COMPLETE FILES
  - Use \`file_write\` for EACH file. Write COMPLETE implementations, not stubs.
  - Completely overwrite \`lib/main.dart\` — do NOT extend the counter template
  - File structure: lib/{main.dart, app.dart, screens/*.dart, models/*.dart, services/*.dart, database/*.dart, widgets/*.dart}
  - Read existing files first before modifying them

STEP 4 — VERIFY BUILD
  - Run \`build_app\` to check project compiles
  - Fix any errors found

STEP 5 — REPORT
  - Output a JSON summary: {"generatedFiles": [{"path": "...", "description": "..."}, ...]}

RULES:
  - Write ONE file per file_write call (not all at once)
  - Use bash for flutter commands only
  - If an existing file needs changes, read it first, then overwrite with file_write
`.trim()

const FLUTTER_AGENT_PROMPT = FLUTTER_BASE_PROMPT + `

You are a senior Flutter developer. Implement the application completely from scratch.
Write production-quality Dart code with proper null safety, clean architecture, and error handling.

Application to implement:
{{sourceData}}`

const FLUTTER_PLANNER_PROMPT = FLUTTER_BASE_PROMPT + `

You are a planning agent for a Flutter app. Break down the implementation into steps with dependencies.
Consider: screens needed, data models, database layer (SQLite), state management, navigation, theming.

Create a plan for:
{{sourceData}}`

const FLUTTER_PREP_PROMPT = FLUTTER_BASE_PROMPT + `

You are a preparation agent for a Flutter app. Generate pseudocode contracts, widget trees, and data flow diagrams.
Focus on: screen component hierarchy, database table schemas, service method signatures, route definitions.

Generate pseudocode based on the plan.
`

const FLUTTER_DOC_PROMPT = FLUTTER_BASE_PROMPT + `

You are a documentation agent for a Flutter app. Update project documentation with architecture decisions,
screen descriptions, data model documentation, and setup instructions for the implemented application.
`

const verifierChecks = computed(() => {
  const checks: Record<string, any> = { syntaxCheck: true, premortem: true }
  if (projectType.value === 'FLUTTER') {
    checks.testCommand = 'flutter test'
  }
  return checks
})

// ─── Lifecycle ───

onMounted(async () => {
  if (!settingsStore.providersLoaded) {
    await settingsStore.fetchProviders()
  }
  modelOptions.value = settingsStore.getAllModelOptions()
})

// Reset state when dialog opens
watch(() => props.visible, async (newVal) => {
  if (newVal) {
    loading.value = false
    error.value = null
    if (!settingsStore.providersLoaded) {
      await settingsStore.fetchProviders()
    }
    modelOptions.value = settingsStore.getAllModelOptions()
  }
})

// ─── Presets ───

function applyPreset(presetId: string) {
  const preset = presets.find(p => p.id === presetId)
  if (preset) {
    prompt.value = preset.description
    projectType.value = preset.projectType
    pipelineTemplate.value = preset.pipelineTemplate
    // FLUTTER presets auto-select the Ollama coder model (deepseek-v4 via Zen is unreachable from backend)
    if (preset.projectType === 'FLUTTER') {
      const coder = modelOptions.value.find(m =>
        m.value.includes('qwen2.5-coder:14b') || m.value.includes('qwen2.5-coder:7b')
      )
      if (coder) selectedModel.value = coder.value
    }
  }
}

// ─── Pipeline configs ───

interface PipelineConfig {
  nodes: FlowNode[]
  edges: FlowEdge[]
}

function makeReviewData(config: Record<string, any>, reviewType: string): Record<string, any> {
  return {
    config: {
      checks: config.checks ?? { premortem: true, prism: false, postmortem: false },
      mode: config.mode ?? 'manual',
      maxAutoIterations: config.maxAutoIterations ?? 3,
      generatePlan: config.generatePlan ?? true,
      reviewType,
      premortem: true,
    },
  } as Record<string, any>
}

function makeSourceData(description: string): Record<string, any> {
  return {
    sourceData: description,
    config: { sourceType: 'text' },
  } as Record<string, any>
}

function makeAgentData(systemPrompt: string, userPrompt: string, agentType: string, tools: string[]): Record<string, any> {
  return {
    systemPrompt,
    userPrompt,
    model: selectedModel.value || null,
    agentType,
    enabledTools: tools,
    config: {},
  } as Record<string, any>
}

function makeVerifierData(description: string, checksParam: Record<string, any>): Record<string, any> {
  return {
    config: {
      checks: checksParam,
      rewriteOnFail: true,
      maxRewriteRetries: 3,
      stubDetection: true,
      validationCriteria: description,
    },
  } as Record<string, any>
}

function generateStandardPipeline(description: string): PipelineConfig {
  const agentPrompt = projectType.value === 'FLUTTER' ? FLUTTER_AGENT_PROMPT
    : 'You are a senior developer. Use the tools available to implement the described application. Write production-quality code.\n\nImplement the application described in the Receive node:\n\n{{sourceData}}'
  const agentSystem = projectType.value === 'FLUTTER' ? 'You are a senior Flutter developer. Implement the application completely from scratch. Write production-quality Dart code with proper null safety, clean architecture, and error handling.'
    : 'You are a senior developer. Use the tools available to implement the described application. Write production-quality code.'
  return {
    nodes: [
      { id: 'receive-1', type: 'source' as any, name: 'Receive', position: { x: 100, y: 200 }, data: makeSourceData(description) },
      { id: 'review-1', type: 'review' as any, name: 'Review Plan', position: { x: 350, y: 200 }, data: makeReviewData({}, 'standard') },
      { id: 'think-1', type: 'agent' as any, name: 'Agent', position: { x: 600, y: 200 }, data: makeAgentData(
          agentSystem, agentPrompt, 'coder', agentTools.value) },
      { id: 'verify-1', type: 'verifier' as any, name: 'Verify', position: { x: 850, y: 200 }, data: makeVerifierData(description, verifierChecks.value) },
      { id: 'act-1', type: 'output' as any, name: 'Output', position: { x: 1100, y: 200 }, data: { config: {
            mode: 'summary_report', reportPath: 'pipeline-report.md',
            includeReview: true, includeFiles: true, includeVerification: true, includeMetrics: true,
            generateReadme: true, generateArchitecture: false,
          } } as Record<string, any>,
      },
    ],
    edges: [
      { id: 'e1', source: 'receive-1', target: 'review-1', type: 'data' as any },
      { id: 'e2', source: 'review-1', target: 'think-1', type: 'data' as any },
      { id: 'e3', source: 'think-1', target: 'verify-1', type: 'data' as any },
      { id: 'e4', source: 'verify-1', target: 'act-1', type: 'data' as any },
    ],
  }
}

function generateAppCreationPipeline(description: string): PipelineConfig {
  const isFlutter = projectType.value === 'FLUTTER'
  const plannerPrompt = isFlutter ? FLUTTER_PLANNER_PROMPT
    : 'You are a planning agent. Break down the application into implementation steps with dependencies.\n\nCreate a plan for: {{sourceData}}'
  const plannerSystem = isFlutter ? 'You are a planning agent for a Flutter app. Break down the implementation into steps with dependencies. Consider: screens needed, data models, database layer (SQLite), state management, navigation, theming.'
    : 'You are a planning agent. Break down the application into implementation steps with dependencies.'
  const prepPrompt = isFlutter ? FLUTTER_PREP_PROMPT
    : 'Generate pseudocode and tests based on the plan.'
  const prepSystem = isFlutter ? 'You are a preparation agent for a Flutter app. Generate pseudocode contracts, widget trees, and data flow diagrams. Focus on: screen component hierarchy, database table schemas, service method signatures, route definitions.'
    : 'You are a preparation agent. Generate pseudocode contracts and tests for the planned implementation.'
  const agentPrompt = isFlutter ? FLUTTER_AGENT_PROMPT
    : 'You are a senior developer. Implement the application step by step according to the plan and pseudocode contracts.\n\nImplement the application described in the Receive node:\n\n{{sourceData}}'
  const agentSystem = isFlutter ? 'You are a senior Flutter developer. Implement the application completely from scratch. Write production-quality Dart code with proper null safety, clean architecture, and error handling.'
    : 'You are a senior developer. Implement the application step by step according to the plan and pseudocode contracts.'
  const docPrompt = isFlutter ? FLUTTER_DOC_PROMPT
    : 'Update project documentation for the implemented application.'
  const docSystem = isFlutter ? 'You are a documentation agent for a Flutter app. Update project documentation with architecture decisions, screen descriptions, data model documentation, and setup instructions for the implemented application.'
    : 'You are a documentation agent. Update project docs based on the implementation.'
  return {
    nodes: [
      { id: 'receive-1', type: 'source' as any, name: 'Receive', position: { x: 50, y: 200 }, data: makeSourceData(description) },
      { id: 'review-1', type: 'review' as any, name: 'Design Review', position: { x: 300, y: 200 }, data: makeReviewData(
          { checks: { premortem: true, prism: true, postmortem: true } }, 'design') },
      { id: 'planner-1', type: 'planner' as any, name: 'Planner', position: { x: 550, y: 200 }, data: makeAgentData(
          plannerSystem, plannerPrompt, 'planner', ['file_read', 'file_write', 'directory_read']) },
      { id: 'review-2', type: 'review' as any, name: 'Plan Review', position: { x: 800, y: 200 }, data: makeReviewData(
          { checks: { premortem: true, prism: false, postmortem: false } }, 'plan') },
      { id: 'prep-1', type: 'prep' as any, name: 'Prep', position: { x: 1050, y: 200 }, data: makeAgentData(
          prepSystem, prepPrompt, 'prep', ['file_read', 'file_write', 'directory_read']) },
      { id: 'think-1', type: 'agent' as any, name: 'Agent', position: { x: 1300, y: 200 }, data: makeAgentData(
          agentSystem, agentPrompt, 'coder', agentTools.value) },
      { id: 'verify-1', type: 'verifier' as any, name: 'Verify', position: { x: 1550, y: 200 }, data: makeVerifierData(description, verifierChecks.value) },
      { id: 'doc-1', type: 'doc-agent' as any, name: 'Doc-Agent', position: { x: 1800, y: 200 }, data: makeAgentData(
          docSystem, docPrompt, 'doc-agent', ['file_read', 'file_write', 'directory_read']) },
      { id: 'act-1', type: 'output' as any, name: 'Output', position: { x: 2050, y: 200 }, data: { config: {
            mode: 'summary_report', reportPath: 'pipeline-report.md',
            includeReview: true, includeFiles: true, includeVerification: true, includeMetrics: true,
            generateReadme: true, generateArchitecture: false,
          } } as Record<string, any>,
      },
    ],
    edges: [
      { id: 'e1', source: 'receive-1', target: 'review-1', type: 'data' as any },
      { id: 'e2', source: 'review-1', target: 'planner-1', type: 'data' as any },
      { id: 'e3', source: 'planner-1', target: 'review-2', type: 'data' as any },
      { id: 'e4', source: 'review-2', target: 'prep-1', type: 'data' as any },
      { id: 'e5', source: 'prep-1', target: 'think-1', type: 'data' as any },
      { id: 'e6', source: 'think-1', target: 'verify-1', type: 'data' as any },
      { id: 'e7', source: 'verify-1', target: 'doc-1', type: 'data' as any },
      { id: 'e8', source: 'doc-1', target: 'act-1', type: 'data' as any },
    ],
  }
}

function generateMinimalPipeline(description: string): PipelineConfig {
  const agentPrompt = projectType.value === 'FLUTTER' ? FLUTTER_AGENT_PROMPT
    : 'You are a senior developer. Use the tools to implement the described application.\n\nImplement:\n\n{{sourceData}}'
  const agentSystem = projectType.value === 'FLUTTER' ? 'You are a senior Flutter developer. Implement the application completely from scratch. Write production-quality Dart code with proper null safety, clean architecture, and error handling.'
    : 'You are a senior developer. Use the tools to implement the described application.'
  return {
    nodes: [
      { id: 'receive-1', type: 'source' as any, name: 'Receive', position: { x: 100, y: 200 }, data: makeSourceData(description) },
      { id: 'think-1', type: 'agent' as any, name: 'Agent', position: { x: 400, y: 200 }, data: makeAgentData(
          agentSystem, agentPrompt, 'coder', agentTools.value) },
      { id: 'verify-1', type: 'verifier' as any, name: 'Verify', position: { x: 700, y: 200 }, data: makeVerifierData(description, verifierChecks.value) },
      { id: 'act-1', type: 'output' as any, name: 'Output', position: { x: 1000, y: 200 }, data: { config: {
            mode: 'summary_report', reportPath: 'pipeline-report.md',
            includeReview: true, includeFiles: true, includeVerification: true, includeMetrics: true,
            generateReadme: true, generateArchitecture: false,
          } } as Record<string, any>,
      },
    ],
    edges: [
      { id: 'e1', source: 'receive-1', target: 'think-1', type: 'data' as any },
      { id: 'e2', source: 'think-1', target: 'verify-1', type: 'data' as any },
      { id: 'e3', source: 'verify-1', target: 'act-1', type: 'data' as any },
    ],
  }
}

function getPipelineConfig(description: string): PipelineConfig {
  switch (pipelineTemplate.value) {
    case 'app-creation': return generateAppCreationPipeline(description)
    case 'minimal': return generateMinimalPipeline(description)
    default: return generateStandardPipeline(description)
  }
}

// ─── Main action ───

async function generate() {
  if (!prompt.value.trim()) return
  if (createMode.value && !appName.value.trim()) return

  loading.value = true
  error.value = null

  try {
    let targetId = props.appId

    // Create mode: create a blank schema first
    if (createMode.value) {
      try {
        const pathCheck = await appApi.checkTargetPath(appName.value.trim(), projectType.value)
        if (pathCheck.exists) {
          error.value = `Directory "${pathCheck.targetPath}" already exists. Create the app from the Dashboard to choose a conflict resolution strategy.`
          loading.value = false
          return
        }
      } catch {
        // proceed
      }
      const created = await appApi.createApp({
        name: appName.value.trim(),
        appType: projectType.value,
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
    const description = prompt.value.trim()

    // Generate pipeline from selected template
    const pipeline = getPipelineConfig(description)
    schema.nodes = pipeline.nodes as any
    schema.edges = pipeline.edges as any

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
            Describe what you want to build. Choose project type and pipeline template below.
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

          <!-- Project type -->
          <div class="field-row">
            <label class="input-label" for="quickstart-type">Project Type:</label>
            <select
              id="quickstart-type"
              v-model="projectType"
              class="text-input"
              :disabled="loading"
            >
              <option v-for="pt in projectTypes" :key="pt.value" :value="pt.value">{{ pt.label }}</option>
            </select>
          </div>

          <!-- Pipeline template -->
          <div class="field-row">
            <label class="input-label" for="quickstart-pipeline">Pipeline:</label>
            <select
              id="quickstart-pipeline"
              v-model="pipelineTemplate"
              class="text-input"
              :disabled="loading"
            >
              <option v-for="pt in pipelineTemplates" :key="pt.value" :value="pt.value" :title="pt.desc">
                {{ pt.label }} — {{ pt.desc }}
              </option>
            </select>
          </div>

          <!-- Model selector -->
          <div class="field-row">
            <label class="input-label" for="quickstart-model">Model <span class="hint-muted">(optional, blank = default)</span>:</label>
            <select
              id="quickstart-model"
              v-model="selectedModel"
              class="text-input"
              :disabled="loading"
            >
              <option value="">— System default —</option>
              <optgroup v-for="group in modelGroups" :key="group.label" :label="group.label">
                <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </optgroup>
            </select>
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
            rows="8"
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
  max-width: 600px;
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
  gap: var(--space-3);
}

.input-label {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-secondary);
}

.hint-muted {
  font-weight: 400;
  color: var(--text-tertiary, #888);
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
  min-height: 120px;
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
