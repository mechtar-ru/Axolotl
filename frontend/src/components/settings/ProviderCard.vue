<template>
  <div class="provider-card" :class="{ collapsed }">
    <div class="provider-header" @click="collapsed = !collapsed">
      <span class="status-dot" :class="provider.available ? 'online' : 'offline'"></span>
      <h2>
        <svg v-if="provider.name === 'ollama'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2C8.13 2 5 5.13 5 9c0 3.87 3.13 7 7 7s7-3.13 7-7c0-3.87-3.13-7-7-7z"/><path d="M8 9c0-2.2 1.8-4 4-4s4 1.8 4 4"/><circle cx="9" cy="9" r="1"/><circle cx="15" cy="9" r="1"/></svg>
        <svg v-else-if="provider.name === 'openai'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a4 4 0 0 1 4 4c0 .89-.3 1.73-.8 2.4A4 4 0 0 1 16 12a4 4 0 0 1-4 4 4 4 0 0 1-.8-3.6A4 4 0 0 1 8 12a4 4 0 0 1 4-4c.3 0 .58.03.86.1"/><circle cx="12" cy="12" r="10"/></svg>
        <svg v-else-if="provider.name === 'anthropic'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a10 10 0 1 0 10 10"/><path d="M12 12 8 8l4 4 4-4-4 4Z"/><path d="M12 2v10"/></svg>
        <svg v-else-if="provider.name === 'deepseek'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 2v4m0 12v4m-6-8H2m20 0h-4"/></svg>
        {{ getProviderLabel(provider.name) }}
      </h2>
      <span class="status-pill" :class="provider.available ? 'pill-online' : 'pill-offline'">
        {{ provider.available ? 'Connected' : 'Unavailable' }}
      </span>
      <button class="refresh-btn" @click.stop="$emit('refresh')" title="Refresh">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
      </button>
      <svg class="collapse-chevron" :class="{ rotated: !collapsed }" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
    </div>

    <div v-show="!collapsed" class="provider-fields">
      <div class="field">
        <label>API Key</label>
        <div class="field-row">
          <input
            :type="showKey ? 'text' : 'password'"
            :value="editedKey ?? ''"
            :placeholder="editedKey !== undefined ? '' : 'sk-...'"
            class="field-input"
            @input="editedKey = ($event.target as HTMLInputElement).value"
          />
          <button class="icon-btn" @click="toggleShowKey" aria-label="Show/Hide">
            <svg v-if="showKey" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
      </div>

      <div class="field">
        <label>Base URL</label>
        <input
          :value="editedUrl ?? provider.baseUrl ?? ''"
          class="field-input"
          placeholder="https://api.example.com/v1"
          @input="editedUrl = ($event.target as HTMLInputElement).value"
        />
      </div>

      <div class="field">
        <label>Default Model</label>
        <select
          :value="editedModel ?? provider.defaultModel ?? ''"
          class="field-input"
          @change="editedModel = ($event.target as HTMLSelectElement).value"
        >
          <option value="">Auto</option>
          <option v-for="m in provider.models" :key="m" :value="m">{{ m }}</option>
        </select>
      </div>

      <div class="field-actions">
        <button class="save-btn" @click="onSave" :disabled="saving">
          <svg v-if="!saving" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
          {{ saving ? 'Saving...' : 'Save' }}
        </button>
        <button class="test-btn" @click="onTest" :disabled="testing">
          <svg v-if="!testing" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          {{ testing ? 'Testing...' : 'Test' }}
        </button>
        <span v-if="testResult" class="test-result" :class="testResult.ok ? 'test-ok' : 'test-fail'">
          <svg v-if="testResult.ok" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          {{ testResult.ok ? 'Available' : testResult.msg }}
        </span>
      </div>

      <div v-if="provider.models.length > 0" class="field">
        <div class="models-header">
          <label>Models</label>
          <div class="model-bulk-actions">
            <button class="bulk-toggle-btn" @click="$emit('enableAllModels', provider.name)">All</button>
            <button class="bulk-toggle-btn bulk-off" @click="$emit('disableAllModels', provider.name)">None</button>
          </div>
        </div>
        <div class="model-search-wrap">
          <svg class="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <input
            class="model-search-input"
            type="text"
            placeholder="Search models..."
            :value="modelSearchText"
            @input="modelSearchText = ($event.target as HTMLInputElement).value"
          />
        </div>
        <div class="model-toggles">
          <template v-for="grp in groupedModels()" :key="grp.group">
            <div v-if="grp.models.length > 0" class="model-group-header" @click="toggleGroupCollapse(grp.group)">
              <span class="model-group-arrow">{{ isGroupCollapsed(grp.group) ? '▶' : '▼' }}</span>
              <span class="model-group-label-text">{{ grp.group }}</span>
              <span class="model-group-count">{{ enabledCount(grp) }} / {{ grp.models.length }}</span>
              <span class="model-group-actions" @click.stop>
                <button class="group-toggle-btn" @click="$emit('enableAllInGroup', provider.name, grp.models)">All</button>
                <button class="group-toggle-btn group-off" @click="$emit('disableAllInGroup', provider.name, grp.models)">None</button>
              </span>
            </div>
            <template v-if="!isGroupCollapsed(grp.group)">
              <label
                v-for="model in grp.models"
                :key="model"
                class="model-toggle"
                :class="{ disabled: provider.disabledModels?.includes(model) }"
              >
                <input
                  type="checkbox"
                  :checked="!provider.disabledModels?.includes(model)"
                  @change="$emit('toggleModel', provider.name, model, ($event.target as HTMLInputElement).checked)"
                />
                <span class="model-name">{{ model }}</span>
              </label>
            </template>
          </template>
          <div v-if="searching() && groupedModels().every(g => g.models.length === 0)" class="search-empty">
            No models match
          </div>
          <div v-else class="model-summary">
            {{ summaryCount() }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { useSettingsStore } from '@/stores/settingsStore'
import { settingsApi, type ProviderInfo } from '../../services/api'

const props = defineProps<{
  provider: ProviderInfo
  userDefaultModel: string
}>()

const emit = defineEmits<{
  save: [providerName: string, data: { apiKey?: string; baseUrl?: string; defaultModel?: string }]
  test: [providerName: string, apiKey?: string, baseUrl?: string]
  refresh: []
  toggleModel: [providerName: string, model: string, enabled: boolean]
  enableAllModels: [providerName: string]
  disableAllModels: [providerName: string]
  enableAllInGroup: [providerName: string, models: string[]]
  disableAllInGroup: [providerName: string, models: string[]]
}>()

const settingsStore = useSettingsStore()

const saving = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; msg: string } | null>(null)
const showKey = ref(false)
const editedKey = ref<string | undefined>(undefined)
const editedUrl = ref<string | undefined>(undefined)
const editedModel = ref<string | undefined>(undefined)
const collapsed = ref(!props.provider.available)
const modelSearchText = ref('')
const groupCollapsed = reactive<Record<string, boolean>>({})

/** Clear cached edit state when provider data changes (e.g., after model toggle) */
watch(() => props.provider, () => {
  editedKey.value = undefined
  editedUrl.value = undefined
  editedModel.value = undefined
  showKey.value = false
  testResult.value = null
})

function getProviderLabel(name: string): string {
  const labels: Record<string, string> = {
    ollama: 'Ollama (Local)',
    openai: 'OpenAI',
    anthropic: 'Anthropic',
    deepseek: 'DeepSeek',
  }
  return labels[name] || name
}

function groupedModels(): { group: string; models: string[] }[] {
  const models = props.provider.models || []
  const search = modelSearchText.value.toLowerCase()
  const filtered = search ? models.filter(m => m.toLowerCase().includes(search)) : models

  const groups: Record<string, string[]> = {}
  for (const m of filtered) {
    let group = 'Other'
    if (m.startsWith('claude-')) group = 'Claude'
    else if (m.startsWith('gpt-') || m.startsWith('o1-') || m.startsWith('o3-')) group = 'GPT'
    else if (m.startsWith('gemini-')) group = 'Gemini'
    else if (m.startsWith('deepseek-')) group = 'DeepSeek'
    else if (m.startsWith('qwen')) group = 'Qwen'
    else if (m.startsWith('minimax-')) group = 'MiniMax'
    else if (m.startsWith('kimi-')) group = 'Kimi'
    else if (m.startsWith('glm-')) group = 'GLM'
    else if (m.startsWith('trinity-') || m.startsWith('hy3-') || m.startsWith('ling-') || m.startsWith('nemotron-') || m.endsWith('-free') || m === 'big-pickle') group = 'Free'
    else if (m.startsWith('llama') || m.startsWith('mistral') || m.startsWith('gemma')) group = 'Open Source'

    if (!groups[group]) groups[group] = []
    groups[group]!.push(m)
  }
  return Object.entries(groups).map(([g, ms]) => ({ group: g, models: ms }))
}

function searching(): boolean {
  return modelSearchText.value.length > 0
}

function toggleGroupCollapse(group: string) {
  groupCollapsed[group] = !(groupCollapsed[group] ?? true)
}

function isGroupCollapsed(group: string): boolean {
  if (searching()) return false
  return groupCollapsed[group] ?? true
}

function enabledCount(grp: { models: string[] }): number {
  return grp.models.filter(m => !props.provider.disabledModels?.includes(m)).length
}

function summaryCount(): string {
  const total = props.provider.models?.length ?? 0
  const enabled = props.provider.models?.filter(m => !props.provider.disabledModels?.includes(m)).length ?? 0
  return `${enabled} / ${total} models enabled`
}

async function toggleShowKey() {
  showKey.value = !showKey.value
  if (showKey.value && !editedKey.value) {
    try {
      const key = await settingsApi.getProviderApiKey(props.provider.name)
      if (key) editedKey.value = key
    } catch (e) {
      console.error('ProviderCard: Failed to fetch API key:', e)
    }
  }
}

function onSave() {
  const data: { apiKey?: string; baseUrl?: string; defaultModel?: string } = {}
  if (editedKey.value !== undefined) data.apiKey = editedKey.value
  if (editedUrl.value !== undefined) data.baseUrl = editedUrl.value
  if (editedModel.value !== undefined) data.defaultModel = editedModel.value
  emit('save', props.provider.name, data)
}

function onTest() {
  emit('test', props.provider.name, editedKey.value || undefined, editedUrl.value || undefined)
}
</script>

<style scoped>
.provider-card {
  background: var(--bg-card);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  margin-bottom: var(--space-4);
  border: 1px solid var(--border-color);
}

.provider-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  cursor: pointer;
  user-select: none;
}

.collapsed .provider-header {
  margin-bottom: 0;
}

.collapsed .provider-fields {
  display: none;
}

.collapse-chevron {
  flex-shrink: 0;
  transition: transform 0.2s;
  color: var(--text-muted);
}

.collapse-chevron.rotated {
  transform: rotate(180deg);
}

.status-pill {
  font-size: var(--text-xs);
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 99px;
  white-space: nowrap;
}

.pill-online {
  background: rgba(34, 197, 94, 0.15);
  color: var(--success);
}

.pill-offline {
  background: rgba(239, 68, 68, 0.15);
  color: var(--error);
}

.provider-header h2 {
  font-size: var(--text-lg);
  color: var(--text-primary);
  flex: 1;
}

.status-dot {
  width: var(--icon-sm);
  height: var(--icon-sm);
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.online {
  background: var(--success);
  box-shadow: var(--shadow-glow-success);
}

.status-dot.offline {
  background: var(--error);
  box-shadow: var(--shadow-glow-error);
}

.refresh-btn {
  background: none;
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2);
  cursor: pointer;
  font-size: var(--text-sm);
}

.refresh-btn:hover {
  background: var(--bg-hover);
}

.provider-fields {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.field label {
  display: block;
  font-size: var(--text-xs);
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.field-row {
  display: flex;
  gap: var(--space-2);
}

.field-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-sm);
  outline: none;
}

.field-input:focus {
  border-color: var(--border-focus);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  padding: var(--space-2);
  line-height: 1;
}

.icon-btn:hover {
  background: var(--bg-hover);
}

.field-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-1);
}

.save-btn, .test-btn, .icon-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.save-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
}

.save-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.save-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-btn {
  padding: var(--space-2) var(--space-4);
  background: var(--bg-hover);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
}

.test-btn:hover:not(:disabled) {
  background: var(--bg-active);
}

.test-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-result {
  font-size: var(--text-xs);
}

.test-ok {
  color: var(--success);
}

.test-fail {
  color: var(--error);
}

.model-toggles {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: var(--space-2);
}

.model-group-header {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  margin: 0 calc(-1 * var(--space-2)) var(--space-1);
  border-top: 1px solid var(--border-color);
  cursor: pointer;
  user-select: none;
  position: sticky;
  top: 0;
  background: var(--bg-primary);
  z-index: 1;
}

.model-group-header:first-of-type {
  border-top: none;
  margin-top: 0;
}

.model-group-header:hover {
  background: var(--bg-hover);
}

.model-group-arrow {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 10px;
  width: 12px;
  text-align: center;
}

.model-group-label-text {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: var(--text-muted);
  flex: 1;
}

.model-group-count {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-secondary);
  white-space: nowrap;
  margin-right: var(--space-2);
}

.model-group-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s;
}

.model-group-header:hover .model-group-actions {
  opacity: 1;
}

.group-toggle-btn {
  padding: 1px 6px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  border-radius: 3px;
  cursor: pointer;
  font-size: 9px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  line-height: 1.3;
}

.group-toggle-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.group-toggle-btn.group-off:hover {
  background: var(--error);
  border-color: var(--error);
  color: white;
}

.model-summary {
  text-align: center;
  padding: var(--space-2) var(--space-2) var(--space-1);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 500;
  border-top: 1px solid var(--border-color);
  margin: var(--space-1) calc(-1 * var(--space-2)) calc(-1 * var(--space-1));
}

.model-toggle {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
  font-family: var(--font-mono);
  color: var(--text-primary);
  transition: background 0.15s;
}

.model-toggle:hover {
  background: var(--bg-hover);
}

.model-toggle.disabled {
  color: var(--text-muted);
  text-decoration: line-through;
}

.model-toggle input[type="checkbox"] {
  accent-color: var(--accent);
  width: 14px;
  height: 14px;
}

.model-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.models-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}

.models-header label {
  margin-bottom: 0 !important;
}

.model-bulk-actions {
  display: flex;
  gap: var(--space-1);
}

.bulk-toggle-btn {
  padding: 2px 8px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  line-height: 1.4;
}

.bulk-toggle-btn:hover {
  background: var(--bg-active);
  color: var(--text-primary);
}

.bulk-toggle-btn.bulk-off:hover {
  color: var(--error);
  border-color: var(--error);
}

.model-search-wrap {
  position: relative;
  margin-bottom: var(--space-2);
}

.search-icon {
  position: absolute;
  left: var(--space-2);
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-muted);
  pointer-events: none;
}

.model-search-input {
  width: 100%;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2) var(--space-1) 28px;
  font-size: var(--text-xs);
  outline: none;
  box-sizing: border-box;
}

.model-search-input:focus {
  border-color: var(--border-focus);
}

.model-search-input::placeholder {
  color: var(--text-muted);
}

.search-empty {
  padding: var(--space-3) var(--space-2);
  text-align: center;
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-style: italic;
}

.inline-error {
  background: var(--error-light);
  color: var(--error);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  margin-bottom: var(--space-3);
}
</style>
