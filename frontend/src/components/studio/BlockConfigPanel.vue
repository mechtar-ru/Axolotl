<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useVueFlow } from '@vue-flow/core'
import { useSchemaStore } from '@/stores/schemaStore'
import { useCanvasStore } from '@/stores/useCanvasStore'
import { settingsApi } from '@/services/api'
import { personas } from '@/data/personas'
import { getBlockByType } from '@/blockRegistry'

const props = defineProps<{
  blockId: string
}>()

const emit = defineEmits<{
  close: []
}>()

const schemaStore = useSchemaStore()
const canvasStore = useCanvasStore()
const { nodes } = useVueFlow({ id: 'blueprint-flow' })

// Look up the VueFlow node by blockId
const node = computed(() => nodes.value.find(n => n.id === props.blockId))

// Block config state — populated from node data on change
const blockLabel = ref('')
const blockDescription = ref('')
const model = ref('')
const prompt = ref('')
const selectedPersona = ref('')
const blockType = ref('agent')
const agentType = ref('code-agent')

// Source type refs (for source/receive blocks)
const sourceType = ref('text')
const sourceContent = ref('')
const filePath = ref('')
const url = ref('')
const projectPath = ref('')
const maxDepth = ref(4)
const maxFiles = ref(50)
const fetching = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const dirInputRef = ref<HTMLInputElement>()

// Config sections driven by BlockRegistry — which panels to show
const activePanels = computed(() => {
  const def = getBlockByType(blockType.value)
  if (!def) return new Set<string>()
  return new Set(def.configPanels.map(s => s.id))
})

const isReview = computed(() => blockType.value === 'review')
const isAutoMode = computed(() => reviewMode.value === 'auto' || reviewMode.value === 'hybrid')
const isAgentBlock = computed(() =>
  ['agent', 'planner', 'prep', 'doc-agent'].includes(blockType.value)
)

const syntaxCheck = ref(true)
const requiredPatterns = ref<string[]>([])
const testCommand = ref('')
const maxFileSizeKb = ref(500)
const rewriteOnFail = ref(true)
const maxRewriteRetries = ref(3)
const stubDetection = ref(true)
const expectedFileCount = ref<number | null>(null)

const reviewPremortem = ref(true)
const reviewPrism = ref(false)
const reviewPostmortem = ref(false)
const reviewMode = ref('manual')
const reviewMaxIterations = ref(3)
const reviewMaxAutoIterations = ref(3)
const reviewGeneratePlan = ref(true)

// Pipeline resilience: auto retry count and fallback models
const autoRetryCount = ref(0)
const fallbackModels = ref<string[]>([])
const timeoutSeconds = ref(300)

// Draft type selector
const draftType = ref('spec')

const providerOptions = ref<{ value: string; label: string; group: string }[]>([])

const providerGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {}
  for (const opt of providerOptions.value) {
    if (!groups[opt.group]) groups[opt.group] = []
    groups[opt.group]!.push({ value: opt.value, label: opt.label })
  }
  return groups
})

onMounted(() => loadProviders())

// Module-level cache: fetch providers once per 30s instead of on every block click
let cachedProviders: { value: string; label: string; group: string }[] | null = null
let providersLastFetch = 0
const PROVIDERS_CACHE_TTL = 30000

async function loadProviders() {
  const now = Date.now()
  if (cachedProviders && (now - providersLastFetch) < PROVIDERS_CACHE_TTL) {
    providerOptions.value = cachedProviders
    return
  }
  try {
    const providers = await settingsApi.getProviders()
    const opts: { value: string; label: string; group: string }[] = []
    for (const p of providers) {
      const group = p.name.charAt(0).toUpperCase() + p.name.slice(1)
      if (p.models.length > 0) {
        const disabled = p.disabledModels ?? []
        for (const model of p.models) {
          if (disabled.includes(model)) continue
          opts.push({ value: model, label: model, group })
        }
      } else {
        opts.push({ value: p.name, label: `${group} (default)`, group })
      }
    }
    providerOptions.value = opts
    cachedProviders = opts
    providersLastFetch = Date.now()
  } catch {
    providerOptions.value = []
  }
}

const requiredPatternsText = computed({
  get: () => requiredPatterns.value.join('\n'),
  set: (val: string) => {
    requiredPatterns.value = val.split('\n').filter(s => s.trim().length > 0)
  }
})

function applyPersona() {
  if (!selectedPersona.value) return
  const persona = personas.find(p => p.id === selectedPersona.value)
  if (persona) {
    prompt.value = persona.systemPrompt
    saveConfig()
  }
}

function resetRefs() {
  blockLabel.value = ''
  blockDescription.value = ''
  model.value = ''
  prompt.value = ''
  selectedPersona.value = ''
  blockType.value = 'agent'
  sourceType.value = 'text'
  sourceContent.value = ''
  filePath.value = ''
  url.value = ''
  projectPath.value = ''
  maxDepth.value = 4
  maxFiles.value = 50
  syntaxCheck.value = true
  requiredPatterns.value = []
  testCommand.value = ''
  maxFileSizeKb.value = 500
  rewriteOnFail.value = true
  maxRewriteRetries.value = 3
  stubDetection.value = true
  expectedFileCount.value = null
  autoRetryCount.value = 0
  fallbackModels.value = []
  timeoutSeconds.value = 300
  reviewPremortem.value = true
  reviewPrism.value = false
  reviewPostmortem.value = false
  reviewMode.value = 'manual'
  reviewMaxIterations.value = 3
  reviewMaxAutoIterations.value = 3
  reviewGeneratePlan.value = true
}

// Populate form fields when the panel opens for a different block
// NOTE: must be after all ref() declarations to avoid TDZ errors
watch(() => props.blockId, () => {
  if (!node.value) {
    resetRefs()
    return
  }
  blockLabel.value = (node.value.data?.label as string) || ''
  blockType.value = (node.value.type as string) || (node.value.data?.type as string) || 'agent'
  const config = (node.value.data?.config as Record<string, any>) || {}
  blockDescription.value = (node.value.data?.description as string) || (config.description as string) || ''
  model.value = (node.value.data?.model as string) || (config.model as string) || 'local'
  prompt.value = (node.value.data?.systemPrompt as string) || (config.systemPrompt as string) || (config.prompt as string) || ''
  agentType.value = (config.agentType as string) || 'code-agent'
  // Detect persona match
  selectedPersona.value = ''
  if (prompt.value) {
    const matched = personas.find(p => p.systemPrompt === prompt.value)
    if (matched) selectedPersona.value = matched.id
  }
  // Verifier fields
  const checks = config.checks as Record<string, any> | undefined
  syntaxCheck.value = checks?.syntaxCheck ?? true
  requiredPatterns.value = (checks?.requiredPatterns as string[]) ?? []
  testCommand.value = checks?.testCommand ?? ''
  maxFileSizeKb.value = checks?.maxFileSizeKb ?? 500
  // rewriteOnFail/maxRewriteRetries live at config top level (not inside checks)
  rewriteOnFail.value = (config.rewriteOnFail as boolean) ?? true
  maxRewriteRetries.value = (config.maxRewriteRetries as number) ?? 3
  stubDetection.value = (config.stubDetection as boolean) ?? true
  expectedFileCount.value = (config.expectedFileCount as number) ?? null
  autoRetryCount.value = (config.autoRetryCount as number) ?? 0
  fallbackModels.value = (config.fallbackModels as string[]) ?? []
  timeoutSeconds.value = (config.timeoutSeconds as number) ?? 300
  // Draft type
  draftType.value = (config.draftType as string) || 'spec'
  // Review fields
  reviewPremortem.value = checks?.premortem ?? true
  reviewPrism.value = checks?.prism ?? false
  reviewPostmortem.value = checks?.postmortem ?? false
  reviewMode.value = config?.mode ?? 'manual'
  reviewMaxIterations.value = config?.maxIterations ?? 3
  reviewMaxAutoIterations.value = config?.maxAutoIterations ?? 3
  reviewGeneratePlan.value = config?.generatePlan ?? true
  // Source type fields
  const srcType = (node.value.data?.config as Record<string, any>)?.sourceType as string | undefined
  sourceType.value = srcType || 'text'
  sourceContent.value = (node.value.data?.config as Record<string, any>)?.sourceData as string
    || (node.value.data?.sourceData as string) || ''
  filePath.value = (node.value.data?.config as Record<string, any>)?.filePath as string || ''
  url.value = (node.value.data?.config as Record<string, any>)?.url as string || ''
  projectPath.value = (node.value.data?.config as Record<string, any>)?.projectPath as string || ''
  maxDepth.value = (node.value.data?.config as Record<string, any>)?.maxDepth as number || 4
  maxFiles.value = (node.value.data?.config as Record<string, any>)?.maxFiles as number || 50
}, { immediate: true })

function browseFilePath() {
  fileInputRef.value?.click()
}

function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const file = input.files[0]!
  filePath.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    sourceContent.value = reader.result as string
    saveConfig()
  }
  reader.onerror = () => {
    console.error('Failed to read file:', file.name)
  }
  reader.readAsText(file)
  input.value = ''
}

async function browseProjectDir() {
  try {
    if ('showDirectoryPicker' in window) {
      const handle = await (window as any).showDirectoryPicker()
      const dirName = handle.name
      if (dirName) {
        projectPath.value = dirName
        saveConfig()
      }
    } else {
      dirInputRef.value?.click()
    }
  } catch {
    // user cancelled — do nothing
  }
}

function onDirPicked(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const file = input.files[0]!
  projectPath.value = file.webkitRelativePath?.split('/')[0] || file.name
  saveConfig()
  input.value = ''
}

function fetchUrlPreview() {
  // For MVP, URL preview not implemented — config is saved, backend handles at execution
}

function toggleFallbackModel(model: string) {
  if (fallbackModels.value.includes(model)) {
    fallbackModels.value = fallbackModels.value.filter(m => m !== model)
  } else {
    fallbackModels.value = [...fallbackModels.value, model]
  }
  saveConfig()
}

function saveConfig() {
  if (!node.value) return

  // Update VueFlow node data
  node.value.data = {
    ...node.value.data,
    label: blockLabel.value,
    config: {
      ...((node.value.data?.config as Record<string, any>) || {}),
      description: blockDescription.value,
      model: model.value,
      systemPrompt: prompt.value,
      agentType: agentType.value,
      autoRetryCount: autoRetryCount.value,
      fallbackModels: fallbackModels.value,
      timeoutSeconds: timeoutSeconds.value,
    },
  }

  // Source/receive block specific config
  if (blockType.value === 'source') {
    const sourceConfigUpdates: Record<string, any> = {
      sourceType: sourceType.value,
      sourceData: sourceContent.value,
      filePath: filePath.value,
      url: url.value,
      projectPath: projectPath.value,
      maxDepth: maxDepth.value,
      maxFiles: maxFiles.value,
    }
    Object.assign(node.value.data.config || {}, sourceConfigUpdates)
  }

  // Draft-specific config
  if (activePanels.value.has('draftType')) {
    if (!node.value.data.config) node.value.data.config = {}
    ;(node.value.data.config as Record<string, any>).draftType = draftType.value
  }

  // Agent-specific config
  if (isAgentBlock.value) {
    if (!node.value.data.config) node.value.data.config = {}
    if (expectedFileCount.value != null && expectedFileCount.value > 0) {
      ;(node.value.data.config as Record<string, any>).expectedFileCount = expectedFileCount.value
    } else {
      delete (node.value.data.config as Record<string, any>).expectedFileCount
    }
  }

  // Pipeline resilience config (applies to any LLM-using node)
  if (activePanels.value.has('model')) {
    if (!node.value.data.config) node.value.data.config = {}
    ;(node.value.data.config as Record<string, any>).autoRetryCount = autoRetryCount.value
    ;(node.value.data.config as Record<string, any>).fallbackModels = fallbackModels.value
    ;(node.value.data.config as Record<string, any>).timeoutSeconds = timeoutSeconds.value
  }

  // Verifier-specific config
  if (activePanels.value.has('checks') && !isReview.value) {
    const checks = {
      syntaxCheck: syntaxCheck.value,
      requiredPatterns: requiredPatterns.value,
      testCommand: testCommand.value,
      maxFileSizeKb: maxFileSizeKb.value
    }
    // Merge checks into config
    if (!node.value.data.config) node.value.data.config = {}
    ;(node.value.data.config as Record<string, any>).checks = checks
    // rewriteOnFail/maxRewriteRetries/stubDetection live at config top level (not in checks)
    ;(node.value.data.config as Record<string, any>).rewriteOnFail = rewriteOnFail.value
    ;(node.value.data.config as Record<string, any>).maxRewriteRetries = maxRewriteRetries.value
    ;(node.value.data.config as Record<string, any>).stubDetection = stubDetection.value
  }

  // Review-specific config
  if (isReview.value) {
    const checks = {
      premortem: reviewPremortem.value,
      prism: reviewPrism.value,
      postmortem: reviewPostmortem.value
    }
    if (!node.value.data.config) node.value.data.config = {}
    ;(node.value.data.config as Record<string, any>).checks = checks
    ;(node.value.data.config as Record<string, any>).mode = reviewMode.value
    ;(node.value.data.config as Record<string, any>).maxIterations = reviewMaxIterations.value
    ;(node.value.data.config as Record<string, any>).maxAutoIterations = reviewMaxAutoIterations.value
    ;(node.value.data.config as Record<string, any>).generatePlan = reviewGeneratePlan.value
  }

  // Sync to schemaStore for persistence
  if (canvasStore.currentSchema?.nodes) {
    const updatedNodes = canvasStore.currentSchema.nodes.map(n => {
      if (n.id !== props.blockId) return n
    const baseData: Record<string, any> = {
      ...(n.data || {}),
      config: {
        ...((n.data?.config as Record<string, any>) || {}),
        description: blockDescription.value,
        model: model.value,
        systemPrompt: prompt.value,
        autoRetryCount: autoRetryCount.value,
        fallbackModels: fallbackModels.value,
        timeoutSeconds: timeoutSeconds.value,
      },
      // Top-level fields for backend NodeData deserialization
      model: model.value,
      systemPrompt: prompt.value,
    }
      if (blockType.value === 'source') {
        Object.assign(baseData.config || {}, {
          sourceType: sourceType.value,
          sourceData: sourceContent.value,
          filePath: filePath.value,
          url: url.value,
          projectPath: projectPath.value,
          maxDepth: maxDepth.value,
          maxFiles: maxFiles.value,
        })
        baseData.sourceData = sourceContent.value
      }
      if (activePanels.value.has('checks') && !isReview.value) {
        baseData.config = {
          ...baseData.config,
          checks: {
            syntaxCheck: syntaxCheck.value,
            requiredPatterns: requiredPatterns.value,
            testCommand: testCommand.value,
            maxFileSizeKb: maxFileSizeKb.value,
          },
          rewriteOnFail: rewriteOnFail.value,
          maxRewriteRetries: maxRewriteRetries.value,
        }
      }
      if (activePanels.value.has('draftType')) {
        baseData.config = {
          ...baseData.config,
          draftType: draftType.value,
        }
      }
      if (isReview.value) {
        baseData.config = {
          ...baseData.config,
          checks: {
            premortem: reviewPremortem.value,
            prism: reviewPrism.value,
            postmortem: reviewPostmortem.value
          },
          mode: reviewMode.value,
          maxIterations: reviewMaxIterations.value,
          maxAutoIterations: reviewMaxAutoIterations.value,
          generatePlan: reviewGeneratePlan.value,
        }
      }
      return {
        ...n,
        name: blockLabel.value,
        type: blockType.value as any,
        data: baseData
      }
    })
    canvasStore.markDirty({
      ...canvasStore.currentSchema,
      nodes: updatedNodes
    })
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
  }
}
</script>

<template>
  <div class="config-panel" @keydown="handleKeydown">
    <div class="panel-header">
      <h3>Configure Block</h3>
      <button class="close-btn" @click="emit('close')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
          <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <!-- Block Name -->
      <div class="config-section">
        <label class="config-label">Block Name</label>
        <input
          v-model="blockLabel"
          type="text"
          class="config-input"
          placeholder="Name this block"
          @input="saveConfig"
        />
      </div>

      <!-- Description (all blocks) -->
      <div class="config-section">
        <label class="config-label">Description</label>
        <textarea
          v-model="blockDescription"
          class="config-textarea"
          placeholder="What should this block do?"
          rows="3"
          @input="saveConfig"
        />
      </div>

      <!-- Input Type / Source Type (Receive blocks) -->
      <div v-if="activePanels.has('sourceType')" class="config-section">
        <label class="config-label">Input Type</label>
        <select v-model="sourceType" class="config-select" @change="saveConfig">
          <option value="text">Chat / Text</option>
          <option value="file">File Reference</option>
          <option value="url">URL Fetch</option>
          <option value="project">Project Directory</option>
        </select>
      </div>

      <!-- Text mode -->
      <div v-if="activePanels.has('sourceType') && sourceType === 'text'" class="config-section">
        <label class="config-label">Source Content</label>
        <textarea
          v-model="sourceContent"
          class="config-textarea config-textarea--large"
          placeholder="Enter input for your app..."
          rows="6"
          @input="saveConfig"
        />
      </div>

      <!-- File Reference mode -->
      <div v-if="activePanels.has('sourceType') && sourceType === 'file'" class="config-section">
        <label class="config-label">File Path</label>
        <div class="path-row">
          <input
            v-model="filePath"
            type="text"
            class="config-input"
            placeholder=".ideas/002.md"
            @input="saveConfig"
          />
          <button class="icon-btn" @click="browseFilePath" title="Browse">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
            </svg>
          </button>
          <input ref="fileInputRef" type="file" style="display:none" @change="onFilePicked" />
        </div>
        <div v-if="sourceType === 'file' && filePath" class="config-hint">
          Path is relative to schema target directory
        </div>
      </div>

      <!-- URL Fetch mode -->
      <div v-if="activePanels.has('sourceType') && sourceType === 'url'" class="config-section">
        <label class="config-label">URL</label>
        <div class="url-row">
          <input
            v-model="url"
            type="text"
            class="config-input"
            placeholder="https://..."
            @input="saveConfig"
          />
          <button class="icon-btn" @click="fetchUrlPreview" :disabled="fetching" title="Fetch preview">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <circle cx="12" cy="12" r="10"/>
              <line x1="2" y1="12" x2="22" y2="12"/>
              <path d="M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- Project Directory mode -->
      <div v-if="activePanels.has('sourceType') && sourceType === 'project'" class="config-section">
        <label class="config-label">Project Path</label>
        <div class="path-row">
          <input
            v-model="projectPath"
            type="text"
            class="config-input"
            placeholder="/path/to/project"
            @input="saveConfig"
          />
          <button class="icon-btn" @click="browseProjectDir" title="Browse">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
            </svg>
          </button>
          <input ref="dirInputRef" type="file" webkitdirectory style="display:none" @change="onDirPicked" />
        </div>
        <div class="inline-fields">
          <label class="config-label-inline">
            Depth:
            <input v-model.number="maxDepth" type="number" min="1" max="10" class="num-input" @input="saveConfig" />
          </label>
          <label class="config-label-inline">
            Max files:
            <input v-model.number="maxFiles" type="number" min="1" max="200" class="num-input" @input="saveConfig" />
          </label>
        </div>
      </div>

      <!-- Model Selector (Think blocks) -->
      <div v-if="activePanels.has('model')" class="config-section">
        <label class="config-label">Model</label>
        <select v-model="model" class="config-select">
          <option value="">Auto (user default)</option>
          <template v-for="(models, group) in providerGroups" :key="group">
            <optgroup :label="group">
              <option v-for="m in models" :key="m.value" :value="m.value">{{ m.label }}</option>
            </optgroup>
          </template>
        </select>

        <!-- Pipeline Resilience: Auto Retry -->
        <label class="config-label" style="margin-top: 8px;">Auto Retry Count</label>
        <input
          v-model.number="autoRetryCount"
          type="number"
          min="0"
          max="5"
          class="num-input"
          style="width: 80px;"
          @input="saveConfig"
        />

        <!-- Pipeline Resilience: Fallback Models -->
        <label class="config-label" style="margin-top: 8px;">Fallback Models</label>
        <div class="fallback-models-list">
          <label v-for="opt in providerOptions" :key="opt.value" class="fallback-model-item">
            <input
              type="checkbox"
              :value="opt.value"
              :checked="fallbackModels.includes(opt.value)"
              @change="toggleFallbackModel(opt.value)"
            />
            <span class="fallback-model-label">{{ opt.label }}</span>
            <span class="fallback-model-group">{{ opt.group }}</span>
          </label>
          <div v-if="providerOptions.length === 0" class="fallback-empty">
            No models available
          </div>
        </div>

        <!-- Execution Timeout -->
        <label class="config-label" style="margin-top: 8px;">Timeout (seconds)</label>
        <input
          v-model.number="timeoutSeconds"
          type="number"
          min="10"
          max="3600"
          step="10"
          class="num-input"
          style="width: 100px;"
          @input="saveConfig"
        />
        <span class="config-hint">Per-node execution limit (10–3600s)</span>
      </div>

      <!-- Draft Type (Draft blocks) -->
      <div v-if="activePanels.has('draftType')" class="config-section">
        <label class="config-label">Draft Type</label>
        <select v-model="draftType" class="config-select" @change="saveConfig">
          <option value="spec">Spec — 1-page functional spec</option>
          <option value="plan">Plan — Implementation breakdown</option>
          <option value="ui">UI — OpenUISpec component design</option>
          <option value="backend">Backend — Modules, API, DB schema</option>
        </select>
      </div>

      <!-- Agent Type selector (agent-like blocks) -->
      <div v-if="isAgentBlock" class="config-section">
        <label class="config-label">Agent Type</label>
        <select v-model="agentType" class="config-select" @change="saveConfig">
          <option value="code-agent">Code Agent — implements features</option>
          <option value="planner">Planner — creates implementation plans</option>
          <option value="prep">Prep — pseudocode + tests</option>
          <option value="doc-agent">Doc-Agent — updates documentation</option>
        </select>
      </div>

      <!-- Prompt (Think blocks) -->
      <div v-if="activePanels.has('systemPrompt')" class="config-section">
        <label class="config-label">Persona</label>
        <select v-model="selectedPersona" class="config-select" @change="applyPersona">
          <option value="">Custom — write your own prompt</option>
          <option v-for="p in personas" :key="p.id" :value="p.id">
            {{ p.name }} — {{ p.description }}
          </option>
        </select>
        <label class="config-label">System Prompt</label>
        <textarea
          v-model="prompt"
          class="config-textarea config-textarea--large"
          placeholder="Describe what this AI should do..."
          rows="6"
          @input="saveConfig"
        />
      </div>

      <!-- Expected File Count (Agent blocks) -->
      <div v-if="isAgentBlock" class="config-section">
        <label class="config-label">Expected Files (optional)</label>
        <input
          v-model="expectedFileCount"
          type="number"
          class="config-input"
          min="0"
          max="100"
          placeholder="0 = no minimum"
          @input="saveConfig"
        />
        <span class="config-hint">Warn if agent creates fewer files than this number</span>
      </div>

      <!-- Memory Type (Remember blocks) -->
      <div v-if="activePanels.has('memory')" class="config-section">
        <label class="config-label">Memory Type</label>
        <select v-model="blockType" class="config-select">
          <option value="memory">Chat History</option>
          <option value="memory-knowledge">Knowledge Base</option>
          <option value="memory-facts">Structured Facts</option>
        </select>
      </div>

      <!-- Verification Checks (Verify blocks) -->
      <div v-if="activePanels.has('checks') && !isReview" class="config-section">
        <label class="config-label">Verification Checks</label>

        <label class="config-checkbox">
          <input type="checkbox" v-model="syntaxCheck" @change="saveConfig" />
          Syntax Check
        </label>

        <label class="config-checkbox">
          <input type="checkbox" v-model="stubDetection" @change="saveConfig" />
          Stub Detection
          <span class="config-hint">Detect // TODO, empty bodies, return null placeholders</span>
        </label>

        <div class="config-field">
          <label class="config-label">Required Patterns (one per line)</label>
          <textarea
            v-model="requiredPatternsText"
            class="config-textarea"
            placeholder="player&#10;move()&#10;check_victory"
            rows="3"
            @input="saveConfig"
          />
        </div>

        <div class="config-field">
          <label class="config-label">Test Command</label>
          <input
            v-model="testCommand"
            type="text"
            class="config-input"
            placeholder="python3 -m py_compile {{filepath}}"
            @input="saveConfig"
          />
        </div>

        <div class="config-field">
          <label class="config-label">Max File Size (KB)</label>
          <input
            v-model="maxFileSizeKb"
            type="number"
            class="config-input"
            min="1"
            max="10000"
            @input="saveConfig"
          />
        </div>

        <label class="config-checkbox">
          <input type="checkbox" v-model="rewriteOnFail" @change="saveConfig" />
          Auto-rewrite on failure
        </label>

        <div class="config-field">
          <label class="config-label">Max Rewrite Retries</label>
          <input
            v-model="maxRewriteRetries"
            type="number"
            class="config-input"
            min="0"
            max="10"
            @input="saveConfig"
          />
        </div>

        <div class="config-field config-info">
          <span class="config-label">Tools: file_read, bash, grep (fixed)</span>
        </div>
      </div>

      <!-- Review Checks (Review blocks) -->
      <div v-if="activePanels.has('checks') && isReview" class="config-section">
        <label class="config-label">Review Checks</label>

        <label class="config-checkbox">
          <input type="checkbox" v-model="reviewPremortem" @change="saveConfig" />
          Premortem (review plan before execution)
        </label>

        <label class="config-checkbox">
          <input type="checkbox" v-model="reviewPrism" @change="saveConfig" />
          Prism (scan codebase context)
        </label>

        <label class="config-checkbox">
          <input type="checkbox" v-model="reviewPostmortem" @change="saveConfig" />
          Postmortem (analyze execution history)
        </label>

        <div class="config-field">
          <label class="config-label">Mode</label>
          <select v-model="reviewMode" class="config-select" @change="saveConfig">
            <option value="manual">Manual — Always require my approval</option>
            <option value="auto">Auto — Fully automatic, no human</option>
            <option value="hybrid">Hybrid — Auto-fix, then ask me if stuck</option>
          </select>
        </div>

        <div class="config-field">
          <label class="config-label">Max Iterations</label>
          <input
            v-model="reviewMaxIterations"
            type="number"
            class="config-input"
            min="1"
            max="20"
            @input="saveConfig"
          />
        </div>

        <!-- Auto-iteration config: only shown for auto/hybrid -->
        <template v-if="isAutoMode">
          <div class="config-field">
            <label class="config-label">Max Auto Iterations</label>
            <input
              v-model="reviewMaxAutoIterations"
              type="number"
              class="config-input"
              min="1"
              max="50"
              @input="saveConfig"
            />
          </div>

          <label class="config-checkbox">
            <input type="checkbox" v-model="reviewGeneratePlan" @change="saveConfig" />
            Generate Plan (AI creates plan from upstream)
          </label>
        </template>
      </div>

      <!-- Action Type (Act blocks) -->
      <div v-if="activePanels.has('output')" class="config-section">
        <label class="config-label">Action Type</label>
        <select v-model="blockType" class="config-select">
          <option value="output">Reply / Output</option>
          <option value="output-save">Save to file</option>
          <option value="output-api">Call External API</option>
          <option value="output-email">Send Email</option>
        </select>
      </div>
    </div>
  </div>
</template>

<style scoped>
.config-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 320px;
  height: 100%;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  box-shadow: var(--shadow-lg);
  z-index: var(--z-panel);
  display: flex;
  flex-direction: column;
  animation: slideIn 0.2s ease-out;
}

@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.panel-header h3 {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--transition);
}

.close-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
}

.config-section {
  margin-bottom: var(--space-5);
}

.config-label {
  display: block;
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.config-input,
.config-select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
  transition: border-color var(--transition);
}

.config-input:focus,
.config-select:focus,
.config-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.config-textarea {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
  line-height: 1.5;
}

.config-textarea--large {
  min-height: 120px;
  font-size: var(--text-xs);
}

.config-checkbox {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--text-primary);
  margin-bottom: var(--space-3);
  cursor: pointer;
}

.config-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.config-field {
  margin-bottom: var(--space-3);
}

.config-info {
  padding: var(--space-1) var(--space-2);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

textarea { scrollbar-width: thin; scrollbar-color: var(--border-color) transparent; }
.path-row {
  display: flex;
  gap: 4px;
}
.path-row .config-input {
  flex: 1;
}
.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 16px;
  transition: background var(--transition), border-color var(--transition);
  flex-shrink: 0;
}
.icon-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent);
}
.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.url-row {
  display: flex;
  gap: 4px;
}
.url-row .config-input {
  flex: 1;
}
.inline-fields {
  display: flex;
  gap: var(--space-4);
  margin-top: var(--space-2);
}
.config-label-inline {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
}
.num-input {
  width: 60px;
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  text-align: center;
}
.config-hint {
  margin-top: var(--space-1);
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-style: italic;
}

.fallback-models-list {
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: var(--space-1);
  margin-top: var(--space-1);
  background: var(--bg-primary);
}
.fallback-model-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  font-size: var(--text-xs);
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.fallback-model-item:hover {
  background: var(--bg-hover);
}
.fallback-model-item input[type="checkbox"] {
  width: 14px;
  height: 14px;
  cursor: pointer;
}
.fallback-model-label {
  flex: 1;
  color: var(--text-primary);
}
.fallback-model-group {
  color: var(--text-muted);
  font-size: 10px;
}
.fallback-empty {
  padding: var(--space-2);
  color: var(--text-muted);
  font-size: var(--text-xs);
  text-align: center;
}
</style>
