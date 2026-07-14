<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { appApi } from '@/services/api'

const props = defineProps<{
  appName: string
  appType: string
  targetPath: string
}>()

const emit = defineEmits<{
  resolve: [action: 'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH', customPath?: string]
  cancel: []
}>()

const customPath = ref(props.targetPath)

onMounted(async () => {
  try {
    const result = await appApi.checkTargetPath(props.appName, props.appType)
    if (!result.exists) {
      emit('resolve', 'CONTINUE')
    }
  } catch (e) {
    console.warn('Conflict check failed:', e)
  }
})

function choose(action: 'CONTINUE' | 'OVERWRITE') {
  emit('resolve', action)
}

function chooseChangePath() {
  if (customPath.value.trim()) {
    emit('resolve', 'CHANGE_PATH', customPath.value.trim())
  }
}
</script>

<template>
  <div class="modal-overlay" @click.self="$emit('cancel')" role="dialog" aria-modal="true" aria-labelledby="conflict-title">
    <div class="modal conflict-modal">
      <h3 id="conflict-title"><svg style="vertical-align:middle;margin-right:6px" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> Directory Conflict</h3>
      <p class="conflict-desc">
        The target directory <strong>{{ targetPath }}</strong> already exists.
        How would you like to proceed?
      </p>

      <div class="conflict-options">
        <button class="conflict-option" @click="choose('CONTINUE')">
          <span class="option-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" style="vertical-align:middle;margin-right:4px"><path d="M16 3h5v5"/><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><line x1="8" y1="10" x2="16" y2="10"/><line x1="8" y1="14" x2="14" y2="14"/></svg></span>
          <div class="option-content">
            <strong>Continue</strong>
            <span>Keep existing files, add new ones</span>
          </div>
        </button>

        <button class="conflict-option danger" @click="choose('OVERWRITE')">
          <span class="option-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" style="vertical-align:middle;margin-right:4px"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></span>
          <div class="option-content">
            <strong>Overwrite</strong>
            <span>Delete all files and start fresh</span>
          </div>
        </button>

        <div class="conflict-option-input">
          <span class="option-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" style="vertical-align:middle;margin-right:4px"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg></span>
          <div class="option-content">
            <strong>Change Path</strong>
            <input
              v-model="customPath"
              type="text"
              class="input"
              placeholder="Enter new target path"
            />
            <button class="btn-primary btn-sm" @click="chooseChangePath" :disabled="!customPath.trim()">
              Use This Path
            </button>
          </div>
        </div>
      </div>

      <button class="btn-secondary" @click="$emit('cancel')" style="margin-top: 1rem; width: 100%;">
        Cancel
      </button>
    </div>
  </div>
</template>

<style scoped>
.conflict-modal { max-width: 500px; }
.conflict-desc { color: var(--text-secondary); line-height: 1.5; margin-bottom: 1.25rem; }
.conflict-options { display: flex; flex-direction: column; gap: 0.75rem; }
.conflict-option {
  display: flex; align-items: center; gap: 0.75rem;
  padding: 0.875rem; background: var(--bg-primary);
  border: 1px solid var(--border-color); border-radius: 8px;
  cursor: pointer; text-align: left; transition: all 0.2s;
}
.conflict-option:hover { border-color: var(--accent); background: var(--bg-hover); }
.conflict-option.danger:hover { border-color: #ef4444; }
.option-icon { font-size: 1.25rem; flex-shrink: 0; }
.option-content { display: flex; flex-direction: column; gap: 0.2rem; width: 100%; }
.option-content strong { font-size: 0.9rem; color: var(--text-primary); }
.option-content span { font-size: 0.8rem; color: var(--text-muted); }
.conflict-option-input {
  display: flex; align-items: flex-start; gap: 0.75rem;
  padding: 0.875rem; background: var(--bg-primary);
  border: 1px solid var(--border-color); border-radius: 8px;
}
.conflict-option-input .input { margin: 0.5rem 0; }
</style>
