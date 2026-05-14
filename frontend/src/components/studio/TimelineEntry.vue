<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
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

const parsedChecks = computed(() => {
  try {
    const details = props.event.details
    if (!details) return []
    const parsed = JSON.parse(details)
    return parsed.checks || []
  } catch {
    return []
  }
})
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
      <div v-if="event.blockType === 'verifier' && event.details" class="verifier-details">
        <div class="verifier-detail-row" v-for="check in parsedChecks" :key="check.name">
          <span :class="['check-status', check.passed ? 'pass' : 'fail']">
            {{ check.passed ? '✓' : '✗' }}
          </span>
          <span class="check-name">{{ check.name }}</span>
        </div>
      </div>
      <div v-if="event.blockType === 'review' && event.details" class="review-details">
        <div class="review-detail-row">
          <span class="review-findings">{{ event.details }}</span>
        </div>
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

.verifier-details {
  margin-top: 0.5rem;
  padding: 0.4rem;
  background: var(--bg-hover);
  border-radius: 4px;
}

.verifier-detail-row {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.75rem;
  padding: 0.15rem 0;
}

.check-status.pass { color: #4caf50; }
.check-status.fail { color: #ef4444; }

.review-details {
  margin-top: 0.5rem;
  padding: 0.4rem;
  background: var(--bg-hover);
  border-radius: 4px;
}

.review-detail-row {
  font-size: 0.75rem;
  padding: 0.15rem 0;
}

.review-findings {
  color: var(--text-primary);
}
</style>
