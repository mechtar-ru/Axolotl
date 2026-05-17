<script setup lang="ts">
import { ref, computed } from 'vue'
import { useExecutionState, type ExecutionState } from '@/composables/useExecutionState'
import TimelineEntry from '@/components/studio/TimelineEntry.vue'

const emit = defineEmits<{
  'select-block': [blockId: string]
}>()

const execState = useExecutionState()

const events = computed(() => execState?.stepEvents.value || [])

const blockColors: Record<string, string> = {
  source: '#4caf50',
  agent: '#2196f3',
  verifier: '#8b5cf6',
  memory: '#9c27b0',
  output: '#ff9800',
  review: '#f59e0b'
}

function getBlockColor(type: string): string {
  return blockColors[type] || '#6c63ff'
}

function handleSelectBlock(blockId: string) {
  emit('select-block', blockId)
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}
</script>

<template>
  <div class="timeline-view">
    <div class="timeline-header">
      <h2>Execution Timeline</h2>
      <span class="event-count">{{ events.length }} events</span>
    </div>
    
    <div v-if="events.length === 0" class="timeline-empty">
      <div class="empty-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="48" height="48">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
      </div>
      <p>Run your app to see what happened</p>
    </div>
    
    <div v-else class="timeline-list">
      <div
        v-for="(event, index) in events"
        :key="event.blockId + '-' + event.stepIndex"
        class="timeline-item-wrapper"
      >
        <div class="timeline-line" :class="{ last: index === events.length - 1 }" />
        <TimelineEntry
          :event="event"
          :color="getBlockColor(event.blockType)"
          :is-last="index === events.length - 1"
          :timestamp="formatTime(event.timestamp || Date.now())"
          @click="handleSelectBlock(event.blockId)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.timeline-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-primary);
  overflow: hidden;
}

.timeline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
  flex-shrink: 0;
}

.timeline-header h2 {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-primary);
}

.event-count {
  font-size: var(--text-xs);
  color: var(--text-muted);
  background: var(--bg-hover);
  padding: 0.2rem 0.6rem;
  border-radius: 10px;
}

.timeline-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--text-muted);
  gap: var(--space-3);
}

.empty-icon {
  opacity: 0.3;
}

.timeline-empty p {
  font-size: var(--text-sm);
  margin: 0;
}

.timeline-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6);
}

.timeline-item-wrapper {
  position: relative;
  padding-left: var(--space-8);
}

.timeline-line {
  position: absolute;
  left: 11px;
  top: 24px;
  bottom: 0;
  width: 2px;
  background: var(--border-color);
}

.timeline-line.last {
  display: none;
}
</style>
