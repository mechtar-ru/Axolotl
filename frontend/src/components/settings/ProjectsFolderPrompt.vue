<template>
  <AppModal :modelValue="visible" title="Welcome to Axolotl" @update:modelValue="onClose">
    <div class="prompt-body">
      <p class="prompt-text">
        Before you start, please set your <strong>projects folder</strong> — this is where Axolotl will create your generated applications.
      </p>
      <div class="input-group">
        <label class="input-label">Projects Folder Path</label>
        <div class="path-row">
          <input
            ref="pathInput"
            :value="folderPath"
            @input="folderPath = ($event.target as HTMLInputElement).value"
            type="text"
            class="path-input"
            placeholder="e.g. /Users/name/git/Axolotl"
          />
          <input ref="folderPickerRef" type="file" webkitdirectory style="display:none" @change="onFolderPicked" />
          <button class="browse-btn" @click="folderPickerRef?.click()" title="Browse">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
            </svg>
          </button>
        </div>
        <p class="hint">You can change this later in Settings.</p>
      </div>
      <div class="actions">
        <button class="btn btn--secondary" @click="skip">Skip</button>
        <button class="btn btn--primary" :disabled="!folderPath.trim()" @click="save">Save &amp; Continue</button>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import AppModal from '@/components/ui/AppModal.vue'
import { useSettingsStore } from '@/stores/settingsStore'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ done: [] }>()

const settingsStore = useSettingsStore()
const folderPath = ref('')
const pathInput = ref<HTMLInputElement | null>(null)
const folderPickerRef = ref<HTMLInputElement | null>(null)

watch(() => props.visible, (v) => {
  if (v) {
    folderPath.value = settingsStore.projectsFolder || ''
    nextTick(() => pathInput.value?.focus())
  }
})

function onFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  input.value = ''
  if (!files || files.length === 0) return
  const dirName = files[0].webkitRelativePath.split('/')[0]
  if (!dirName) return
  // Browser can't give full path, so show detected name for reference
  folderPath.value = `~/Axolotl/${dirName}`
  nextTick(() => pathInput.value?.select())
}

function onClose(v: boolean) {
  if (!v) emit('done')
}

function skip() {
  emit('done')
}

async function save() {
  const trimmed = folderPath.value.trim()
  if (!trimmed) return
  await settingsStore.saveProjectsFolder(trimmed)
  emit('done')
}
</script>

<style scoped>
.prompt-body {
  padding: var(--space-4);
}

.prompt-text {
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--text-primary);
  margin: 0 0 var(--space-5) 0;
}

.input-group {
  margin-bottom: var(--space-5);
}

.input-label {
  display: block;
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: var(--space-1);
}

.path-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.path-input {
  flex: 1;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-family: monospace;
  background: var(--bg-primary);
  color: var(--text-primary);
  outline: none;
}

.path-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.browse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-2);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--transition);
}

.browse-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.hint {
  font-size: var(--text-xs);
  color: var(--text-muted);
  margin: var(--space-1) 0 0 0;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
}

.btn {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: all var(--transition);
  border: 1px solid transparent;
}

.btn--primary {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
}

.btn--primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn--primary:not(:disabled):hover {
  opacity: 0.9;
}

.btn--secondary {
  background: transparent;
  color: var(--text-secondary);
  border-color: var(--border-color);
}

.btn--secondary:hover {
  background: var(--bg-hover);
}
</style>
