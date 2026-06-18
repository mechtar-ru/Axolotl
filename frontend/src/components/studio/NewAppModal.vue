<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import { appApi } from '@/services/api'
import AppModal from '@/components/ui/AppModal.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  created: [schemaId: string]
}>()

const router = useRouter()
const schemaStore = useSchemaStore()

const newAppName = ref('')
const newAppType = ref('CUSTOM')

async function create() {
  if (!newAppName.value.trim()) return
  try {
    if (newAppType.value === 'CUSTOM') {
      const schema = await schemaStore.createSchema(newAppName.value, newAppType.value)
      emit('update:modelValue', false)
      newAppName.value = ''
      if (schema) emit('created', schema.id)
    } else {
      const pathCheck = await appApi.checkTargetPath(newAppName.value, newAppType.value)
      if (pathCheck.exists) {
        // Notify parent about conflict — re-open via template flow
        emit('update:modelValue', false)
        return
      }
      const appInfo = await appApi.createApp({
        name: newAppName.value,
        appType: newAppType.value,
        description: '',
      })
      if (appInfo) {
        schemaStore.schemas.push(appInfo as any)
        emit('update:modelValue', false)
        newAppName.value = ''
        emit('created', appInfo.id)
      }
    }
  } catch (error) {
    console.error('NewAppModal: Failed to create app:', error)
  }
}
</script>

<template>
  <AppModal :model-value="modelValue" title="Create New App" @update:model-value="emit('update:modelValue', $event)">
    <div class="form-group">
      <label>App Name</label>
      <input
        v-model="newAppName"
        type="text"
        placeholder="My Awesome App"
        class="input"
        @keyup.enter="create"
      />
    </div>
    <div class="form-group">
      <label>App Type</label>
      <select v-model="newAppType" class="input">
        <option value="CUSTOM">Custom</option>
        <option value="CHAT">Chat Bot</option>
        <option value="ANALYZER">Analyzer</option>
        <option value="GENERATOR">Generator</option>
        <option value="EMAIL">Email Agent</option>
        <option value="GAME">Game</option>
      </select>
    </div>
    <div class="modal-actions">
      <button class="btn-secondary" @click="emit('update:modelValue', false)">Cancel</button>
      <button class="btn-primary" :disabled="!newAppName.trim()" @click="create">Create</button>
    </div>
  </AppModal>
</template>
