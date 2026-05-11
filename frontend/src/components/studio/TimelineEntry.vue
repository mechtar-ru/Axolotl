<script setup lang="ts">
defineProps<{
  event: {
    stepIndex: number
    blockId: string
    blockType: string
    label: string
    status: string
    details: string
    duration: number
    timestamp: number
  }
  color: string
  isLast: boolean
  timestamp: string
}>()

defineEmits<{
  click: [blockId: string]
}>()
</script>

<template>
  <div class="timeline-entry" :style="{ '--dot-color': color }" @click="$emit('click', event.blockId)">
    <div class="timeline-dot" :style="{ background: color }" />
    <div class="timeline-content">
      <div class="timeline-label">{{ event.label }}</div>
      <div class="timeline-meta">
        <span class="timeline-status" :class="event.status">{{ event.status }}</span>
        <span class="timeline-duration" v-if="event.duration">{{ event.duration }}ms</span>
        <span class="timeline-time">{{ timestamp }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.timeline-entry {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.5rem;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.timeline-entry:hover {
  background: var(--bg-hover);
}

.timeline-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 4px;
}

.timeline-content {
  flex: 1;
  min-width: 0;
}

.timeline-label {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.timeline-meta {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.75rem;
}

.timeline-status {
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  text-transform: uppercase;
  font-weight: 600;
  font-size: 0.65rem;
}

.timeline-duration {
  color: var(--text-muted);
}

.timeline-time {
  color: var(--text-muted);
  margin-left: auto;
}
</style>
