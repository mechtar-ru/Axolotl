<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  id: string
  label: string
  type: string
  color: string
  icon: string
  status?: string
  selected?: boolean
  data?: {
    config?: Record<string, unknown>
    hasReasoning?: boolean
  }
}>()

const emit = defineEmits<{
  'config-click': []
  'reasoning-click': []
}>()

const statusColors: Record<string, string> = {
  idle: 'transparent',
  running: '#2196f3',
  completed: '#4caf50',
  failed: '#ef4444',
  blocked: '#ff9800'
}

const borderColor = computed(() => {
  if (props.status && props.status !== 'idle') {
    return statusColors[props.status] || 'transparent'
  }
  if (props.selected) {
    return 'var(--accent)'
  }
  return 'var(--border-color)'
})

const pulseAnim = computed(() => props.status === 'running')

function handleDoubleClick() {
  emit('config-click')
}
</script>

<template>
  <div
    :class="['block-base', { selected, 'is-running': pulseAnim }]"
    :style="{ borderColor }"
    @dblclick="handleDoubleClick"
  >
    <!-- Top handle (input) -->
    <Handle type="target" :position="Position.Top" class="block-handle" />

    <!-- Block header -->
    <div class="block-header" :style="{ background: color }">
      <svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" width="18" height="18">
        <path :d="icon" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </div>

    <!-- Block label -->
    <div class="block-body">
      <span class="block-label">{{ label }}</span>
    </div>

    <!-- Status indicator -->
    <div v-if="status && status !== 'idle'" class="status-dot" :style="{ background: statusColors[status] }" />

    <!-- Reasoning badge -->
    <button
      v-if="data?.hasReasoning"
      class="reasoning-badge"
      title="View reasoning"
      @click.stop="emit('reasoning-click')"
    >
      <svg viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z"/>
      </svg>
    </button>

    <!-- Bottom handle (output) -->
    <Handle type="source" :position="Position.Bottom" class="block-handle" />
  </div>
</template>

<style scoped>
.block-base {
  background: var(--bg-secondary);
  border: 2px solid var(--border-color);
  border-radius: 10px;
  width: 140px;
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
  position: relative;
}

.block-base:hover {
  box-shadow: var(--shadow-md);
}

.block-base.selected {
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.block-base.is-running {
  animation: pulse-border 1.5s ease-in-out infinite;
}

@keyframes pulse-border {
  0%, 100% { box-shadow: 0 0 0 0 rgba(33, 150, 243, 0.4); }
  50% { box-shadow: 0 0 0 4px rgba(33, 150, 243, 0.15); }
}

.block-header {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.5rem;
  min-height: 40px;
}

.block-body {
  padding: 0.5rem 0.75rem;
  text-align: center;
}

.block-label {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-primary);
  word-break: break-word;
}

.status-dot {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 2px solid var(--bg-secondary);
}

.reasoning-badge {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 24px;
  height: 24px;
  padding: 2px;
  border: none;
  background: var(--accent);
  border-radius: 50%;
  cursor: pointer;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, transform 0.15s;
  z-index: 10;
}

.reasoning-badge:hover {
  background: var(--accent-hover, var(--accent));
  transform: scale(1.1);
}

.reasoning-badge:active {
  transform: scale(0.95);
}

.block-handle {
  width: 10px !important;
  height: 10px !important;
  background: var(--text-muted) !important;
  border: 2px solid var(--bg-secondary) !important;
  transition: background 0.15s;
}

.block-handle:hover {
  background: var(--accent) !important;
}
</style>
