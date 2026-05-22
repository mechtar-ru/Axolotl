<template>
  <div v-if="visible" class="review-overlay">
    <div class="review-modal">
      <div class="review-header">
        <span class="review-title">▲ Plan Review — Iteration {{ iteration }} of {{ maxIterLabel }} ({{ modeLabel }})</span>
        <button class="close-btn" title="Close">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
        </button>
      </div>

      <div class="review-body">
        <!-- Final Plan Section -->
        <div class="review-section">
          <h4 class="section-title">Final Plan</h4>
          <pre v-if="!editing" class="plan-text">{{ plan }}</pre>
          <textarea v-else v-model="editedPlan" class="plan-textarea" />
        </div>

        <!-- Findings Section -->
        <div v-if="findings.length > 0" class="review-section">
          <h4 class="section-title">Findings ({{ findings.length }})</h4>
          <div class="findings-list">
            <div
              v-for="(finding, idx) in findings"
              :key="idx"
              class="finding-item"
            >
              <div class="finding-header">
                <span class="finding-icon" :class="severityClass(finding.severity)">
                  {{ severityIcon(finding.severity) }}
                </span>
                <span
                  class="severity-badge"
                  :class="severityClass(finding.severity)"
                >
                  {{ finding.severity }}
                </span>
                <span class="finding-source">{{ finding.source }}</span>
              </div>
              <p class="finding-description">{{ finding.description }}</p>
              <p v-if="finding.suggestion" class="finding-suggestion">
                <span class="suggestion-label">Suggestion:</span> {{ finding.suggestion }}
              </p>
            </div>
          </div>
        </div>

        <!-- Feedback History Section -->
        <div v-if="feedbackHistory.length > 0" class="review-section">
          <h4 class="section-title">Feedback History</h4>
          <div class="feedback-list">
            <div
              v-for="(item, idx) in feedbackHistory"
              :key="idx"
              class="feedback-item"
            >
              <span class="feedback-index">#{{ idx + 1 }}</span>
              <span class="feedback-text">"{{ item.text }}"</span>
              <span v-if="item.applied" class="feedback-applied">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><polyline points="20 6 9 17 4 12"/></svg> applied
              </span>
              <span v-else class="feedback-not-applied">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg> not applied
              </span>
            </div>
          </div>
        </div>

        <!-- Your Feedback Section -->
        <div class="review-section">
          <h4 class="section-title">Your Feedback</h4>
          <textarea
            v-model="feedbackText"
            class="feedback-textarea"
            placeholder="Type your feedback or revision request..."
            rows="3"
          />
          <button class="add-feedback-btn" @click="addFeedback">+ Add more</button>
          <div v-if="feedbackItems.length > 0" class="feedback-items-list">
            <div
              v-for="(item, idx) in feedbackItems"
              :key="idx"
              class="feedback-tag"
            >
              {{ item }}
            </div>
          </div>
        </div>
      </div>

      <div class="review-footer">
        <div class="footer-actions">
          <button v-if="!showRejectConfirm" class="btn-reject" @click="showRejectConfirm = true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg> Reject
          </button>
          <span v-else class="confirm-reject-inline">
            <span class="confirm-text">Sure?</span>
            <button class="btn-confirm-yes" @click="handleReject">Yes, reject</button>
            <button class="btn-confirm-cancel" @click="showRejectConfirm = false">Cancel</button>
          </span>
          <button class="btn-edit" @click="startEdit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg> Edit Plan
          </button>
          <button class="btn-suggest" @click="emitSuggest">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg> Suggest &amp; Regenerate
          </button>
          <button class="btn-approve" @click="emitApprove">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg> Accept
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';

interface Finding {
  source: string;
  severity: string;
  description: string;
  suggestion: string;
}

interface FeedbackItem {
  text: string;
  applied: boolean;
}

const props = withDefaults(defineProps<{
  visible: boolean;
  schemaId: string;
  executionId: string;
  nodeId: string;
  originalPlan: string;
  rewrittenPlan: string;
  findings: Finding[];
  iteration: number;
  maxIterations: number;
  mode: string;
  feedbackHistory: FeedbackItem[];
}>(), {
  visible: false,
  schemaId: '',
  executionId: '',
  nodeId: '',
  originalPlan: '',
  rewrittenPlan: '',
  findings: () => [],
  iteration: 1,
  maxIterations: 3,
  mode: 'manual',
  feedbackHistory: () => [],
});

const emit = defineEmits<{
  (e: 'approve', plan?: string): void;
  (e: 'suggest', payload: { feedback: string[]; history: FeedbackItem[] }): void;
  (e: 'edit'): void;
  (e: 'reject'): void;
}>();

const editing = ref(false);
const editedPlan = ref(props.rewrittenPlan);
const feedbackText = ref('');
const feedbackItems = ref<string[]>([]);
const showRejectConfirm = ref(false);

const plan = computed(() => {
  if (editing.value) return editedPlan.value
  return props.rewrittenPlan || props.originalPlan || ''
});

const modeLabel = computed(() => {
  return props.mode.charAt(0).toUpperCase() + props.mode.slice(1);
});

const maxIterLabel = computed(() => {
  return props.maxIterations === 0 ? '∞' : String(props.maxIterations);
});

watch(() => props.rewrittenPlan, (val) => {
  if (!editing.value) {
    editedPlan.value = val;
  }
});

watch(() => props.visible, (v) => {
  if (v) {
    editing.value = false;
    editedPlan.value = props.rewrittenPlan;
    feedbackText.value = '';
    feedbackItems.value = [];
  }
});

function severityClass(severity: string): string {
  switch (severity.toUpperCase()) {
    case 'HIGH': return 'severity-high';
    case 'MEDIUM': return 'severity-medium';
    case 'LOW': return 'severity-low';
    case 'INFO': return 'severity-info';
    default: return 'severity-info';
  }
}

function severityIcon(_severity: string): string {
  return '';
}

function startEdit() {
  editing.value = !editing.value;
  if (editing.value) {
    editedPlan.value = props.rewrittenPlan;
  }
  emit('edit');
}

function addFeedback() {
  if (feedbackText.value.trim()) {
    feedbackItems.value.push(feedbackText.value.trim());
    feedbackText.value = '';
  }
}

function emitApprove() {
  emit('approve', plan.value);
}

function emitSuggest() {
  emit('suggest', {
    feedback: feedbackItems.value,
    history: props.feedbackHistory,
  });
}

function handleReject() {
  showRejectConfirm.value = false;
  emit('reject');
}
</script>

<style scoped>
.review-overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay-heavy);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-tooltip);
}

.review-modal {
  background: var(--bg-primary);
  border-radius: var(--radius-lg);
  width: 760px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-color);
  overflow: hidden;
}

.review-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-color);
}

.review-title {
  color: var(--text-primary);
  font-weight: 600;
  font-size: var(--text-sm);
}

.close-btn {
  background: var(--bg-hover);
  border: none;
  color: var(--text-primary);
  width: 30px;
  height: 30px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-sm);
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  background: var(--bg-active);
}

/* Body */
.review-body {
  flex: 1;
  padding: var(--space-4) var(--space-5);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.review-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.section-title {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* Plan display */
.plan-text {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
}

.plan-textarea {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  line-height: 1.6;
  resize: vertical;
  outline: none;
  min-height: 120px;
  box-sizing: border-box;
}

.plan-textarea:focus {
  border-color: var(--border-focus);
}

/* Findings */
.findings-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.finding-item {
  background: var(--bg-hover);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
}

.finding-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
}

.finding-icon {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.finding-icon.severity-high { background: var(--error); }
.finding-icon.severity-medium { background: var(--warning); }
.finding-icon.severity-low { background: var(--text-muted); }
.finding-icon.severity-info { background: var(--info); }

.finding-source {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  font-weight: 600;
  text-transform: uppercase;
  margin-left: auto;
}

.severity-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px var(--space-2);
  border-radius: var(--radius-full);
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.severity-high {
  background: color-mix(in srgb, var(--error) 20%, transparent);
  color: var(--error);
  border: 1px solid color-mix(in srgb, var(--error) 30%, transparent);
}

.severity-medium {
  background: color-mix(in srgb, var(--warning) 20%, transparent);
  color: var(--warning);
  border: 1px solid color-mix(in srgb, var(--warning) 30%, transparent);
}

.severity-low {
  background: color-mix(in srgb, var(--text-muted) 20%, transparent);
  color: var(--text-muted);
  border: 1px solid color-mix(in srgb, var(--text-muted) 30%, transparent);
}

.severity-info {
  background: color-mix(in srgb, var(--info) 20%, transparent);
  color: var(--info);
  border: 1px solid color-mix(in srgb, var(--info) 30%, transparent);
}

.finding-description {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--text-secondary);
  line-height: var(--leading-normal);
}

.finding-suggestion {
  margin: var(--space-1) 0 0;
  font-size: var(--text-xs);
  color: var(--text-muted);
  line-height: 1.4;
}

.suggestion-label {
  color: var(--accent);
  font-weight: 600;
}

/* Feedback History */
.feedback-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.feedback-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
}

.feedback-index {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-weight: 600;
  min-width: 20px;
}

.feedback-text {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  flex: 1;
}

.feedback-applied {
  font-size: var(--text-xs);
  color: var(--success);
  font-weight: 600;
  white-space: nowrap;
}

.feedback-not-applied {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-weight: 600;
  white-space: nowrap;
}

/* Your Feedback */
.feedback-textarea {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
  font-family: inherit;
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
  resize: vertical;
  outline: none;
  box-sizing: border-box;
}

.feedback-textarea:focus {
  border-color: var(--border-focus);
}

.feedback-textarea::placeholder {
  color: var(--text-muted);
}

.add-feedback-btn {
  background: var(--accent-light);
  border: 1px solid var(--border-accent);
  color: var(--accent);
  padding: 6px 14px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-xs);
  font-weight: 600;
  align-self: flex-start;
  transition: background var(--transition);
}

.add-feedback-btn:hover {
  background: var(--accent-bg);
}

.feedback-items-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.feedback-tag {
  background: var(--accent-light);
  border: 1px solid var(--border-accent);
  color: var(--accent);
  padding: var(--space-1) 10px;
  border-radius: var(--radius-full);
  font-size: var(--text-xs);
}

/* Footer */
.review-footer {
  padding: 14px var(--space-5);
  border-top: 1px solid var(--border-color);
}

.footer-actions {
  display: flex;
  gap: var(--space-2);
}

.btn-edit {
  background: var(--bg-hover);
  border: none;
  color: var(--text-primary);
  padding: var(--space-2) 18px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-xs);
  font-weight: 500;
  transition: background var(--transition);
}

.btn-edit:hover {
  background: var(--bg-active);
}

.btn-suggest {
  background: var(--accent);
  border: none;
  color: white;
  padding: var(--space-2) 18px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-xs);
  font-weight: 500;
  transition: background var(--transition);
}

.btn-suggest:hover {
  background: var(--accent-hover);
}

.btn-reject {
  background: var(--bg-hover);
  border: 1px solid var(--error);
  color: var(--error);
  padding: var(--space-2) 18px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-xs);
  font-weight: 500;
  transition: background var(--transition);
  margin-right: auto;
}

.btn-reject:hover {
  background: color-mix(in srgb, var(--error) 15%, transparent);
}

.btn-approve {
  background: var(--success);
  border: none;
  color: white;
  padding: var(--space-2) var(--space-5);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-sm);
  font-weight: 600;
  transition: background var(--transition);
}

.btn-approve:hover {
  background: var(--success-hover);
}

.inline-vbuttons { display: flex; gap: var(--space-1); }

.btn-reject,
.confirm-reject-inline {
  margin-right: auto;
}

.confirm-reject-inline {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) 0;
}

.confirm-text {
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-weight: 600;
}

.btn-confirm-yes {
  background: var(--error);
  border: none;
  color: white;
  padding: var(--space-1) 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-xs);
  font-weight: 600;
}

.btn-confirm-cancel {
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  padding: var(--space-1) 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--text-xs);
}
</style>
