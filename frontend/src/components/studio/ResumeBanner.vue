<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { schemaApi, type ExecutionRun } from '@/services/api'

const props = defineProps<{
  schemaId: string
}>()

const emit = defineEmits<{
  resume: []
  restart: []
  dismiss: []
}>()

const pausedRun = ref<ExecutionRun | null>(null)
const loading = ref(true)

onMounted(async () => {
  if (!props.schemaId) {
    loading.value = false
    return
  }
  try {
    pausedRun.value = await schemaApi.getPausedRun(props.schemaId)
  } catch (e) {
    console.warn('Failed to check paused run:', e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-if="pausedRun && !loading" class="resume-banner">
    <div class="resume-banner__content">
      <span class="resume-banner__icon">⏸</span>
      <div class="resume-banner__text">
        <strong>Выполнение приостановлено</strong>
        <p v-if="pausedRun.error">{{ pausedRun.error }}</p>
      </div>
    </div>
    <div class="resume-banner__actions">
      <button class="btn btn--primary" @click="emit('resume')">Продолжить</button>
      <button class="btn btn--secondary" @click="emit('restart')">Запустить заново</button>
      <button class="btn btn--ghost" @click="emit('dismiss')">×</button>
    </div>
  </div>
</template>

<style scoped>
.resume-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  background: var(--warning-light);
  border: 1px solid var(--warning);
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-3);
}
.resume-banner__content {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.resume-banner__icon {
  font-size: var(--text-2xl);
}
.resume-banner__text p {
  margin: 2px 0 0;
  font-size: var(--text-sm);
  color: var(--text-primary);
}
.resume-banner__actions {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}
.btn {
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  border: none;
  cursor: pointer;
  font-size: var(--text-sm);
  font-weight: 500;
}
.btn--primary {
  background: var(--warning);
  color: var(--text-primary);
}
.btn--secondary {
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
}
.btn--ghost {
  background: transparent;
  color: var(--text-muted);
  font-size: var(--text-lg);
  padding: var(--space-1) var(--space-2);
}
</style>
