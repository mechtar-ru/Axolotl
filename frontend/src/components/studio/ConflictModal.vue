<script setup lang="ts">
import { ref } from 'vue'
import AppModal from '@/components/ui/AppModal.vue'

defineProps<{
  modelValue: boolean
  template: { id: string; name: string; appType: string; description?: string } | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  resolve: [action: 'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH', customPath?: string]
}>()

const conflictAction = ref<'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH'>('CONTINUE')
const customTargetPath = ref('')

function resolve() {
  emit('resolve', conflictAction.value, conflictAction.value === 'CHANGE_PATH' ? customTargetPath.value : undefined)
}
</script>

<template>
  <AppModal :model-value="modelValue" title="Directory Conflict" @update:model-value="emit('update:modelValue', $event)">
    <p>The target path already exists for "{{ template?.name }}". Choose how to proceed:</p>
    <div class="conflict-options">
      <label class="conflict-option" :class="{ selected: conflictAction === 'CONTINUE' }">
        <input type="radio" v-model="conflictAction" value="CONTINUE" />
        <div class="option-content">
          <strong>Continue</strong>
          <span>Keep existing files and append new ones</span>
        </div>
      </label>
      <label class="conflict-option" :class="{ selected: conflictAction === 'OVERWRITE' }">
        <input type="radio" v-model="conflictAction" value="OVERWRITE" />
        <div class="option-content">
          <strong>Overwrite</strong>
          <span>Delete existing directory and start fresh</span>
        </div>
      </label>
      <label class="conflict-option" :class="{ selected: conflictAction === 'CHANGE_PATH' }">
        <input type="radio" v-model="conflictAction" value="CHANGE_PATH" />
        <div class="option-content">
          <strong>Change Path</strong>
          <span>Specify a different target path</span>
        </div>
      </label>
    </div>
    <div v-if="conflictAction === 'CHANGE_PATH'" class="form-group">
      <label>Custom Path</label>
      <input v-model="customTargetPath" type="text" placeholder="/Users/.../Axolotl/my-app/" class="input" />
    </div>
    <div class="modal-actions">
      <button class="btn-secondary" @click="emit('update:modelValue', false)">Cancel</button>
      <button class="btn-primary" @click="resolve">
        {{ conflictAction === 'CONTINUE' ? 'Continue' : conflictAction === 'OVERWRITE' ? 'Overwrite' : 'Change Path' }}
      </button>
    </div>
  </AppModal>
</template>
