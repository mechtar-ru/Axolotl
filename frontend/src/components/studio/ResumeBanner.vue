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

async function handleResume() {
  // Re-verify the run is still paused before emitting
  try {
    const run = await schemaApi.getPausedRun(props.schemaId)
    if (!run) {
      pausedRun.value = null
      return
    }
    pausedRun.value = run
    emit('resume')
  } catch {
    pausedRun.value = null
  }
}

async function handleRestart() {
  // Re-verify before restart too
  try {
    const run = await schemaApi.getPausedRun(props.schemaId)
    pausedRun.value = run
    emit('restart')
  } catch {
    pausedRun.value = null
  }
}
</script>

<template>
  <div v-if="pausedRun || loading" class="resume-banner">
    <div class="resume-banner__content">
      <span v-if="loading" class="banner-spinner" />
      <span v-else class="resume-banner__icon">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>
        </svg>
      </span>
      <div class="resume-banner__text">
        <strong>{{ loading ? 'Checking for paused execution...' : 'Выполнение приостановлено' }}</strong>
        <p v-if="!loading && pausedRun.error">{{ pausedRun.error }}</p>
      </div>
    </div>
    <div v-if="!loading" class="resume-banner__actions">
      <button class="btn btn--primary" @click="handleResume">Продолжить</button>
      <button class="btn btn--secondary" @click="handleRestart">Запустить заново</button>
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

.banner-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--warning);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
