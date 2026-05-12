<script setup lang="ts">
import { ref, computed } from 'vue'
import type { PlanningModels } from '@/types'

const props = defineProps<{
  modelValue: PlanningModels | null
  defaultModel: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: PlanningModels]
  close: []
}>()

const fastModel = ref(props.modelValue?.fast || props.defaultModel || 'gpt-4o-mini')
const mediumModel = ref(props.modelValue?.medium || props.defaultModel || 'deepseek-chat')

function save() {
  emit('update:modelValue', {
    fast: fastModel.value,
    medium: mediumModel.value,
  })
  emit('close')
}

function close() {
  emit('close')
}
</script>

<template>
  <div class="picker-overlay" @click.self="close">
    <div class="picker-modal">
      <div class="picker-header">
        <h3>Planning Models</h3>
        <button class="close-btn" @click="close">&times;</button>
      </div>
      <div class="picker-body">
        <p class="picker-hint">
          Choose which LLM models to use for each planning stage.
          Fast model generates the initial outline; Medium model refines it into a detailed plan.
        </p>
        <div class="model-field">
          <label for="fast-model">Fast Model (Outline)</label>
          <input
            id="fast-model"
            v-model="fastModel"
            type="text"
            class="model-input"
            placeholder="e.g. gpt-4o-mini"
          />
          <span class="field-desc">Used for the first draft outline. Should be cheap & fast.</span>
        </div>
        <div class="model-field">
          <label for="medium-model">Medium Model (Refine)</label>
          <input
            id="medium-model"
            v-model="mediumModel"
            type="text"
            class="model-input"
            placeholder="e.g. deepseek-chat"
          />
          <span class="field-desc">Used to refine the outline into a detailed design document.</span>
        </div>
      </div>
      <div class="picker-footer">
        <button class="cancel-btn" @click="close">Cancel</button>
        <button class="save-btn" @click="save">Save</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.picker-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.picker-modal {
  background: var(--bg-primary, #1e1e2e);
  border: 1px solid var(--border-color, #333);
  border-radius: 12px;
  width: 480px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}

.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border-color, #333);
}

.picker-header h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-primary, #eee);
}

.close-btn {
  background: none;
  border: none;
  color: var(--text-muted, #888);
  font-size: 1.5rem;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}

.close-btn:hover {
  color: var(--text-primary, #eee);
}

.picker-body {
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.picker-hint {
  font-size: 0.8rem;
  color: var(--text-muted, #888);
  line-height: 1.5;
  margin: 0;
}

.model-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.model-field label {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary, #ccc);
}

.model-input {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color, #333);
  border-radius: 6px;
  font-size: 0.85rem;
  background: var(--bg-secondary, #2a2a3e);
  color: var(--text-primary, #eee);
  font-family: monospace;
}

.model-input:focus {
  outline: none;
  border-color: var(--accent, #6c63ff);
  box-shadow: 0 0 0 2px rgba(108, 99, 255, 0.2);
}

.field-desc {
  font-size: 0.75rem;
  color: var(--text-muted, #888);
}

.picker-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--border-color, #333);
}

.cancel-btn,
.save-btn {
  padding: 0.5rem 1rem;
  border-radius: 6px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.cancel-btn {
  background: transparent;
  border: 1px solid var(--border-color, #333);
  color: var(--text-secondary, #ccc);
}

.cancel-btn:hover {
  background: var(--accent-bg, rgba(108, 99, 255, 0.1));
  border-color: var(--accent, #6c63ff);
}

.save-btn {
  background: var(--accent, #6c63ff);
  border: none;
  color: white;
}

.save-btn:hover {
  background: var(--accent-light, #7c73ff);
}
</style>
