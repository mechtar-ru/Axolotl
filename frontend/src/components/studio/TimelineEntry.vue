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
    if (!details) return [] as any[]
    const parsed = JSON.parse(details)
    const checks = parsed.checks || []
    if (checks.length > 0) return checks
    // If JSON parsed but has no 'checks' key, use the full object as fallback
    return [{ name: parsed.summary || 'Unknown', passed: parsed.status === 'PASS' }]
  } catch {
    // Not valid JSON — show raw details as a single unpased check
    return [{ name: props.event.details.slice(0, 100), passed: undefined }]
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
        <div class="verifier-detail-row" v-for="check in parsedChecks" :key="check.name || Math.random()">
          <span :class="['check-status', check.passed === true ? 'pass' : check.passed === false ? 'fail' : 'unknown']">
            {{ check.passed === true ? '✓' : check.passed === false ? '✗' : '?' }}
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
  gap: var(--space-3);
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
}

.timeline-entry:hover {
  background: var(--bg-hover);
}

.timeline-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: var(--space-1);
}

.timeline-content {
  flex: 1;
  min-width: 0;
}

.timeline-label {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.timeline-meta {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  font-size: var(--text-xs);
}

.timeline-status {
  padding: 2px var(--space-1);
  border-radius: var(--radius-sm);
  text-transform: uppercase;
  font-weight: 600;
  font-size: var(--text-xs);
}

.timeline-duration {
  color: var(--text-muted);
}

.timeline-time {
  color: var(--text-muted);
  margin-left: auto;
}

.verifier-details {
  margin-top: var(--space-2);
  padding: var(--space-2);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
}

.verifier-detail-row {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--text-xs);
  padding: 2px 0;
}

.check-status.pass { color: var(--success); }
.check-status.fail { color: var(--error); }
.check-status.unknown { color: var(--text-muted); }

.review-details {
  margin-top: var(--space-2);
  padding: var(--space-2);
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
}

.review-detail-row {
  font-size: var(--text-xs);
  padding: 2px 0;
}

.review-findings {
  color: var(--text-primary);
}
</style>
