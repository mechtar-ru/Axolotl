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
  padding: 12px 16px;
  background: #fff3cd;
  border: 1px solid #ffc107;
  border-radius: 8px;
  margin-bottom: 12px;
}
.resume-banner__content {
  display: flex;
  align-items: center;
  gap: 12px;
}
.resume-banner__icon {
  font-size: 24px;
}
.resume-banner__text p {
  margin: 2px 0 0;
  font-size: 13px;
  color: #856404;
}
.resume-banner__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.btn {
  padding: 6px 14px;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
}
.btn--primary {
  background: #ffc107;
  color: #333;
}
.btn--secondary {
  background: #e9ecef;
  color: #333;
}
.btn--ghost {
  background: transparent;
  color: #999;
  font-size: 18px;
  padding: 2px 8px;
}
</style>
