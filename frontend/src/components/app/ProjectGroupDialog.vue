<script setup lang="ts">
import { ref } from 'vue'
import AppModal from '@/components/ui/AppModal.vue'
import { schemaApi } from '@/services/api'
import type { WorkflowSchema } from '@/types'

const props = defineProps<{
  schema: WorkflowSchema
}>()

const emit = defineEmits<{
  saved: [schema: WorkflowSchema]
  close: []
}>()

const groupName = ref(props.schema.projectGroup || '')
const saving = ref(false)
const error = ref('')

async function save() {
  saving.value = true
  error.value = ''
  try {
    const updated = await schemaApi.updateSchema(props.schema.id, {
      ...props.schema,
      projectGroup: groupName.value.trim() || null,
    })
    emit('saved', updated)
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || 'Failed to save'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <AppModal title="Project Group" @close="emit('close')">
    <p class="dialog-desc">
      Assign "{{ schema.name }}" to a project group for easier navigation.
    </p>
    <div class="form-group">
      <label>Group Name</label>
      <input
        v-model="groupName"
        type="text"
        placeholder="e.g. EIOS, Berezhno"
        class="input"
        @keyup.enter="save"
      />
    </div>
    <p v-if="error" class="dialog-error">{{ error }}</p>
    <div class="modal-actions">
      <button class="btn-secondary" @click="emit('close')">Cancel</button>
      <button class="btn-primary" :disabled="saving" @click="save">
        {{ saving ? 'Saving...' : 'Save' }}
      </button>
    </div>
  </AppModal>
</template>

<style scoped>
.dialog-desc {
  color: var(--text-secondary);
  margin-bottom: var(--space-4);
}
.form-group {
  margin-bottom: var(--space-4);
}
.form-group label {
  display: block;
  margin-bottom: var(--space-2);
  font-size: 0.875rem;
  color: var(--text-secondary);
}
.input {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: 0.9rem;
}
.input:focus {
  outline: none;
  border-color: var(--accent);
}
.dialog-error {
  color: var(--danger);
  font-size: 0.85rem;
  margin-bottom: var(--space-3);
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
</style>
