<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { PlanningModels } from '@/types'
import { settingsApi } from '@/services/api'

const props = defineProps<{
  modelValue: PlanningModels | null
  defaultModel: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: PlanningModels]
}>()

interface ModelOption {
  value: string
  label: string
  group: string
}

const providerModels = ref<ModelOption[]>([])
const loading = ref(true)

// Fallback when API is unreachable — matches old hardcoded list
const FALLBACK_OPTIONS: ModelOption[] = [
  { value: 'gpt-4o-mini', label: 'gpt-4o-mini', group: 'OpenAI' },
  { value: 'gpt-4o', label: 'gpt-4o', group: 'OpenAI' },
  { value: 'claude-haiku', label: 'claude-haiku', group: 'Anthropic' },
  { value: 'claude-sonnet', label: 'claude-sonnet', group: 'Anthropic' },
  { value: 'deepseek-chat', label: 'deepseek-chat', group: 'DeepSeek' },
  { value: 'deepseek-reasoner', label: 'deepseek-reasoner', group: 'DeepSeek' },
  { value: 'gemini-flash', label: 'gemini-flash', group: 'Google' },
  { value: 'gemini-pro', label: 'gemini-pro', group: 'Google' },
]

const options = computed(() =>
  providerModels.value.length > 0 ? providerModels.value : FALLBACK_OPTIONS,
)

const groupedOptions = computed(() => {
  const groups = new Map<string, ModelOption[]>()
  for (const opt of options.value) {
    if (!groups.has(opt.group)) {
      groups.set(opt.group, [])
    }
    groups.get(opt.group)!.push(opt)
  }
  return Array.from(groups.entries()).map(([group, opts]) => ({
    group,
    options: opts,
  }))
})

const fastModel = ref(props.modelValue?.fast || props.defaultModel || 'gpt-4o-mini')
const mediumModel = ref(props.modelValue?.medium || props.defaultModel || 'deepseek-chat')

function ensureValidSelections() {
  const validValues = new Set(options.value.map((o) => o.value))
  if (fastModel.value && !validValues.has(fastModel.value)) {
    fastModel.value = options.value[0]?.value || props.defaultModel || 'gpt-4o-mini'
  }
  if (mediumModel.value && !validValues.has(mediumModel.value)) {
    mediumModel.value = options.value[0]?.value || props.defaultModel || 'deepseek-chat'
  }
  emitValue()
}

onMounted(async () => {
  try {
    const providers = await settingsApi.getProviders()
    const opts: ModelOption[] = []
    for (const p of providers) {
      if (!p.available) continue
      const group = p.name.charAt(0).toUpperCase() + p.name.slice(1)
      if (p.models.length > 0) {
        const disabled = p.disabledModels ?? [];
        for (const model of p.models) {
          if (disabled.includes(model)) continue;
          opts.push({ value: model, label: model, group })
        }
      } else {
        opts.push({ value: p.name, label: `${group} (default)`, group })
      }
    }
    providerModels.value = opts
    ensureValidSelections()
  } catch {
    // Fallback to FALLBACK_OPTIONS via computed
  } finally {
    loading.value = false
  }
})

function onFastChange(event: Event) {
  fastModel.value = (event.target as HTMLSelectElement).value
  emitValue()
}

function onMediumChange(event: Event) {
  mediumModel.value = (event.target as HTMLSelectElement).value
  emitValue()
}

function emitValue() {
  emit('update:modelValue', {
    fast: fastModel.value,
    medium: mediumModel.value,
  })
}
</script>

<template>
  <div class="model-picker-inline">
    <div class="model-field">
      <label for="fast-model">Fast Model (Outline)</label>
      <select
        id="fast-model"
        class="model-select"
        :value="fastModel"
        :disabled="loading"
        @change="onFastChange"
      >
        <option
          v-if="loading"
          value=""
          disabled
        >
          Loading models...
        </option>
        <template
          v-for="g in groupedOptions"
          :key="g.group"
        >
          <optgroup :label="g.group">
            <option
              v-for="m in g.options"
              :key="m.value"
              :value="m.value"
              :selected="m.value === fastModel"
            >{{ m.label }}</option>
          </optgroup>
        </template>
      </select>
    </div>
    <div class="model-field">
      <label for="medium-model">Medium Model (Refine)</label>
      <select
        id="medium-model"
        class="model-select"
        :value="mediumModel"
        :disabled="loading"
        @change="onMediumChange"
      >
        <option
          v-if="loading"
          value=""
          disabled
        >
          Loading models...
        </option>
        <template
          v-for="g in groupedOptions"
          :key="g.group"
        >
          <optgroup :label="g.group">
            <option
              v-for="m in g.options"
              :key="m.value"
              :value="m.value"
              :selected="m.value === mediumModel"
            >{{ m.label }}</option>
          </optgroup>
        </template>
      </select>
    </div>
  </div>
</template>

<style scoped>
.model-picker-inline {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  margin-top: 0.75rem;
  padding: 0.75rem;
  background: var(--bg-secondary, #2a2a3e);
  border: 1px solid var(--border-color, #333);
  border-radius: 8px;
}

.model-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex: 1;
}

.model-field label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-secondary, #ccc);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.model-select {
  padding: 0.4rem 0.5rem;
  border: 1px solid var(--border-color, #333);
  border-radius: 6px;
  font-size: 0.8rem;
  background: var(--bg-primary, #1e1e2e);
  color: var(--text-primary, #eee);
  font-family: monospace;
  cursor: pointer;
  min-width: 160px;
}

.model-select:focus {
  outline: none;
  border-color: var(--accent, #6c63ff);
  box-shadow: 0 0 0 2px rgba(108, 99, 255, 0.2);
}
</style>
