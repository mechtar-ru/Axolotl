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
  <div class="modal-overlay" @click.self="$emit('cancel')">
    <div class="modal conflict-modal">
      <h3>📁 Directory Conflict</h3>
      <p class="conflict-desc">
        The target directory <strong>{{ targetPath }}</strong> already exists.
        How would you like to proceed?
      </p>

      <div class="conflict-options">
        <button class="conflict-option" @click="choose('CONTINUE')">
          <span class="option-icon">📝</span>
          <div class="option-content">
            <strong>Continue</strong>
            <span>Keep existing files, add new ones</span>
          </div>
        </button>

        <button class="conflict-option danger" @click="choose('OVERWRITE')">
          <span class="option-icon">🗑️</span>
          <div class="option-content">
            <strong>Overwrite</strong>
            <span>Delete all files and start fresh</span>
          </div>
        </button>

        <div class="conflict-option-input">
          <span class="option-icon">✏️</span>
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
