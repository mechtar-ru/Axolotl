<script setup lang="ts">
import { computed } from 'vue'
import { useExecutionState } from '@/composables/useExecutionState'

const emit = defineEmits<{
  'open-timeline': []
}>()

const execState = useExecutionState()

const events = computed(() => execState?.stepEvents.value || [])
const lastEvent = computed(() => events.value.length > 0 ? events.value[events.value.length - 1] : null)

const visible = computed(() => events.value.length > 0)

const statusColors: Record<string, string> = {
  idle: 'var(--text-muted)',
  running: '#2196f3',
  completed: '#4caf50',
  failed: '#ef4444',
  blocked: '#ff9800'
}

function getStatusColor(status: string): string {
  return statusColors[status] || 'var(--text-muted)'
}
</script>

<template>
  <div v-if="visible" class="timeline-bar" @click="emit('open-timeline')">
    <div class="bar-left">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
        <circle cx="12" cy="12" r="10" />
        <polyline points="12 6 12 12 16 14" />
      </svg>
      <span class="bar-label">Timeline</span>
      <span class="bar-count">{{ events.length }} events</span>
    </div>
    <div v-if="lastEvent" class="bar-right">
      <span class="bar-last-event">
        Latest: <strong>{{ lastEvent.label }}</strong>
      </span>
      <span class="bar-status-dot" :style="{ background: getStatusColor(lastEvent.status) }" />
    </div>
  </div>
</template>

<style scoped>
.timeline-bar {
  position: absolute;
  bottom: 1rem;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  padding: 0.5rem 1rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  box-shadow: var(--shadow-lg);
  cursor: pointer;
  transition: border-color 0.15s, transform 0.15s;
  z-index: 50;
  min-width: 280px;
}

.timeline-bar:hover {
  border-color: var(--accent);
  transform: translateX(-50%) translateY(-2px);
}

.bar-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-secondary);
}

.bar-label {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
}

.bar-count {
  font-size: 0.75rem;
  color: var(--text-muted);
  background: var(--bg-hover);
  padding: 0.125rem 0.5rem;
  border-radius: 8px;
}

.bar-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.bar-last-event {
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.bar-last-event strong {
  color: var(--text-primary);
  font-weight: 500;
}

.bar-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
</style>
