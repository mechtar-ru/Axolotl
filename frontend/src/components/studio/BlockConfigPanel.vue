<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useVueFlow } from '@vue-flow/core'
import { useSchemaStore } from '@/stores/schemaStore'
import { settingsApi } from '@/services/api'

const props = defineProps<{
  blockId: string
}>()

const emit = defineEmits<{
  close: []
}>()

const schemaStore = useSchemaStore()
const { nodes } = useVueFlow({ id: 'blueprint-flow' })

// Look up the VueFlow node by blockId
const node = computed(() => nodes.value.find(n => n.id === props.blockId))

// Block config state — populated from node data on change
const blockLabel = ref('')
const blockDescription = ref('')
const model = ref('')
const prompt = ref('')
const blockType = ref('agent')

// Determine config sections based on block type
const showModelSelector = computed(() => blockType.value === 'agent')
const showPrompt = computed(() => blockType.value === 'agent')
const showMemoryType = computed(() => blockType.value === 'memory')
const showActionType = computed(() => blockType.value === 'output')
const showInputType = computed(() => blockType.value === 'source')
const showVerifierConfig = computed(() => blockType.value === 'verifier')
const showReviewConfig = computed(() => blockType.value === 'review')
const showAutoConfig = computed(() => blockType.value === 'review' && (reviewMode.value === 'auto' || reviewMode.value === 'hybrid'))

const syntaxCheck = ref(true)
const requiredPatterns = ref<string[]>([])
const testCommand = ref('')
const maxFileSizeKb = ref(500)

const reviewPremortem = ref(true)
const reviewPrism = ref(false)
const reviewPostmortem = ref(false)
const reviewMode = ref('manual')
const reviewMaxIterations = ref(3)
const reviewMaxAutoIterations = ref(3)
const reviewGeneratePlan = ref(true)

const providerOptions = ref<{ value: string; label: string; group: string }[]>([])

const providerGroups = computed(() => {
  const groups: Record<string, { value: string; label: string }[]> = {}
  for (const opt of providerOptions.value) {
    if (!groups[opt.group]) groups[opt.group] = []
    groups[opt.group].push({ value: opt.value, label: opt.label })
  }
  return groups
})

onMounted(async () => {
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
  } catch {
    providerOptions.value = []
  }
})

const requiredPatternsText = computed({
  get: () => requiredPatterns.value.join('\n'),
  set: (val: string) => {
    requiredPatterns.value = val.split('\n').filter(s => s.trim().length > 0)
  }
})

// Populate form fields when the panel opens for a different block
// NOTE: must be after all ref() declarations to avoid TDZ errors
watch(() => props.blockId, () => {
  if (!node.value) return
  blockLabel.value = (node.value.data?.label as string) || ''
  blockType.value = (node.value.data?.type as string) || 'agent'
  const config = (node.value.data?.config as Record<string, any>) || {}
  blockDescription.value = (node.value.data?.description as string) || (config.description as string) || ''
  model.value = (node.value.data?.model as string) || (config.model as string) || 'local'
  prompt.value = (node.value.data?.systemPrompt as string) || (config.systemPrompt as string) || (config.prompt as string) || ''
  // Verifier fields
  const checks = config.checks as Record<string, any> | undefined
  syntaxCheck.value = checks?.syntaxCheck ?? true
  requiredPatterns.value = (checks?.requiredPatterns as string[]) ?? []
  testCommand.value = checks?.testCommand ?? ''
  maxFileSizeKb.value = checks?.maxFileSizeKb ?? 500
  // Review fields
  reviewPremortem.value = checks?.premortem ?? true
  reviewPrism.value = checks?.prism ?? false
  reviewPostmortem.value = checks?.postmortem ?? false
  reviewMode.value = config?.mode ?? 'manual'
  reviewMaxIterations.value = config?.maxIterations ?? 3
  reviewMaxAutoIterations.value = config?.maxAutoIterations ?? 3
  reviewGeneratePlan.value = config?.generatePlan ?? true
}, { immediate: true })

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
    },
  }

  // Verifier-specific config
  if (showVerifierConfig.value) {
    const checks = {
      syntaxCheck: syntaxCheck.value,
      requiredPatterns: requiredPatterns.value,
      testCommand: testCommand.value,
      maxFileSizeKb: maxFileSizeKb.value
    }
    // Merge checks into config
    if (!node.value.data.config) node.value.data.config = {}
    ;(node.value.data.config as Record<string, any>).checks = checks
  }

  // Review-specific config
  if (showReviewConfig.value) {
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
  if (schemaStore.currentSchema?.nodes) {
    const updatedNodes = schemaStore.currentSchema.nodes.map(n => {
      if (n.id !== props.blockId) return n
    const baseData: Record<string, any> = {
      ...(n.data || {}),
      config: {
        ...((n.data?.config as Record<string, any>) || {}),
        description: blockDescription.value,
        model: model.value,
        systemPrompt: prompt.value,
      },
      // Top-level fields for backend NodeData deserialization
      model: model.value,
      systemPrompt: prompt.value,
    }
      if (showReviewConfig.value) {
        baseData.checks = {
          premortem: reviewPremortem.value,
          prism: reviewPrism.value,
          postmortem: reviewPostmortem.value
        }
        baseData.mode = reviewMode.value
        baseData.maxIterations = reviewMaxIterations.value
        baseData.maxAutoIterations = reviewMaxAutoIterations.value
        baseData.generatePlan = reviewGeneratePlan.value
      }
      return {
        ...n,
        name: blockLabel.value,
        type: blockType.value as any,
        data: baseData
      }
    })
    schemaStore.updateSchema({
      ...schemaStore.currentSchema,
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

      <!-- Input Type (Receive blocks) -->
      <div v-if="showInputType" class="config-section">
        <label class="config-label">Input Type</label>
        <select v-model="blockType" class="config-select">
          <option value="source">Chat / Text</option>
          <option value="source-file">File Upload</option>
          <option value="source-webhook">Webhook</option>
          <option value="source-schedule">Schedule / Timer</option>
        </select>
      </div>

      <!-- Model Selector (Think blocks) -->
      <div v-if="showModelSelector" class="config-section">
        <label class="config-label">Model</label>
        <select v-model="model" class="config-select">
          <option value="">Auto (user default)</option>
          <template v-for="(models, group) in providerGroups" :key="group">
            <optgroup :label="group">
              <option v-for="m in models" :key="m.value" :value="m.value">{{ m.label }}</option>
            </optgroup>
          </template>
        </select>
      </div>

      <!-- Prompt (Think blocks) -->
      <div v-if="showPrompt" class="config-section">
        <label class="config-label">System Prompt</label>
        <textarea
          v-model="prompt"
          class="config-textarea config-textarea--large"
          placeholder="Describe what this AI should do..."
          rows="6"
          @input="saveConfig"
        />
      </div>

      <!-- Memory Type (Remember blocks) -->
      <div v-if="showMemoryType" class="config-section">
        <label class="config-label">Memory Type</label>
        <select v-model="blockType" class="config-select">
          <option value="memory">Chat History</option>
          <option value="memory-knowledge">Knowledge Base</option>
          <option value="memory-facts">Structured Facts</option>
        </select>
      </div>

      <!-- Verification Checks (Verify blocks) -->
      <div v-if="showVerifierConfig" class="config-section">
        <label class="config-label">Verification Checks</label>

        <label class="config-checkbox">
          <input type="checkbox" v-model="syntaxCheck" @change="saveConfig" />
          Syntax Check
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

        <div class="config-field config-info">
          <span class="config-label">Tools: file_read, bash, grep (fixed)</span>
        </div>
      </div>

      <!-- Review Checks (Review blocks) -->
      <div v-if="showReviewConfig" class="config-section">
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
            <option value="manual">Manual (human review)</option>
            <option value="auto">Auto (AI review only)</option>
            <option value="hybrid">Hybrid (auto + human gate)</option>
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
        <template v-if="showAutoConfig">
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
      <div v-if="showActionType" class="config-section">
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
  z-index: 20;
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
</style>
