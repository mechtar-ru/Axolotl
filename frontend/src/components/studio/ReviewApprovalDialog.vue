<template>
  <div v-if="visible" class="review-overlay">
    <div class="review-modal">
      <div class="review-header">
        <span class="review-title">▲ Plan Review — Iteration {{ iteration }} of {{ maxIterLabel }} ({{ modeLabel }})</span>
        <button class="close-btn" title="Close">✕</button>
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
              <span v-if="item.applied" class="feedback-applied">✓ applied</span>
              <span v-else class="feedback-not-applied">✗ not applied</span>
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
          <button class="btn-edit" @click="startEdit">
            ✏️ Edit Plan
          </button>
          <button class="btn-suggest" @click="emitSuggest">
            ↻ Suggest &amp; Regenerate
          </button>
          <button class="btn-approve" @click="emitApprove">
            ✓ Accept
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
  (e: 'approve', plan: string): void;
  (e: 'suggest', payload: { feedback: string[]; history: FeedbackItem[] }): void;
  (e: 'edit'): void;
}>();

const editing = ref(false);
const editedPlan = ref(props.rewrittenPlan);
const feedbackText = ref('');
const feedbackItems = ref<string[]>([]);

const plan = computed(() => {
  return editing.value ? editedPlan.value : props.rewrittenPlan;
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

function severityIcon(severity: string): string {
  switch (severity.toUpperCase()) {
    case 'HIGH': return '🔴';
    case 'MEDIUM': return '🟡';
    case 'LOW': return '🟢';
    case 'INFO': return '🔵';
    default: return '⚪';
  }
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
</script>

<style scoped>
.review-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
}

.review-modal {
  background: #1e1e2e;
  border-radius: 16px;
  width: 760px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.1);
  overflow: hidden;
}

.review-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.review-title {
  color: #eee;
  font-weight: 600;
  font-size: 15px;
}

.close-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #eee;
  width: 30px;
  height: 30px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

/* Body */
.review-body {
  flex: 1;
  padding: 16px 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.review-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-title {
  margin: 0;
  font-size: 13px;
  color: #888;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* Plan display */
.plan-text {
  background: #13131f;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 10px 12px;
  margin: 0;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #ccc;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
}

.plan-textarea {
  width: 100%;
  background: #13131f;
  border: 1px solid #3a3a5a;
  color: #eee;
  border-radius: 8px;
  padding: 12px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 14px;
  line-height: 1.6;
  resize: vertical;
  outline: none;
  min-height: 120px;
  box-sizing: border-box;
}

.plan-textarea:focus {
  border-color: #6c63ff;
}

/* Findings */
.findings-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.finding-item {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  padding: 10px 12px;
}

.finding-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.finding-icon {
  font-size: 12px;
  line-height: 1;
}

.finding-source {
  font-size: 11px;
  color: #aaa;
  font-weight: 600;
  text-transform: uppercase;
  margin-left: auto;
}

.severity-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 10px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.severity-high {
  background: rgba(239, 68, 68, 0.2);
  color: #ef4444;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

.severity-medium {
  background: rgba(234, 179, 8, 0.2);
  color: #eab308;
  border: 1px solid rgba(234, 179, 8, 0.3);
}

.severity-low {
  background: rgba(107, 114, 128, 0.2);
  color: #6b7280;
  border: 1px solid rgba(107, 114, 128, 0.3);
}

.severity-info {
  background: rgba(59, 130, 246, 0.2);
  color: #3b82f6;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

.finding-description {
  margin: 0;
  font-size: 13px;
  color: #ccc;
  line-height: 1.5;
}

.finding-suggestion {
  margin: 4px 0 0;
  font-size: 12px;
  color: #999;
  line-height: 1.4;
}

.suggestion-label {
  color: #6c63ff;
  font-weight: 600;
}

/* Feedback History */
.feedback-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.feedback-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.02);
}

.feedback-index {
  font-size: 11px;
  color: #666;
  font-weight: 600;
  min-width: 20px;
}

.feedback-text {
  font-size: 13px;
  color: #ccc;
  flex: 1;
}

.feedback-applied {
  font-size: 11px;
  color: #22c55e;
  font-weight: 600;
  white-space: nowrap;
}

.feedback-not-applied {
  font-size: 11px;
  color: #888;
  font-weight: 600;
  white-space: nowrap;
}

/* Your Feedback */
.feedback-textarea {
  width: 100%;
  background: #13131f;
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #ccc;
  border-radius: 8px;
  padding: 10px 12px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.5;
  resize: vertical;
  outline: none;
  box-sizing: border-box;
}

.feedback-textarea:focus {
  border-color: #6c63ff;
}

.feedback-textarea::placeholder {
  color: #555;
}

.add-feedback-btn {
  background: rgba(108, 99, 255, 0.15);
  border: 1px solid rgba(108, 99, 255, 0.3);
  color: #c8c0ff;
  padding: 6px 14px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  align-self: flex-start;
  transition: background 0.2s;
}

.add-feedback-btn:hover {
  background: rgba(108, 99, 255, 0.3);
}

.feedback-items-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.feedback-tag {
  background: rgba(108, 99, 255, 0.15);
  border: 1px solid rgba(108, 99, 255, 0.3);
  color: #c8c0ff;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
}

/* Footer */
.review-footer {
  padding: 14px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.footer-actions {
  display: flex;
  gap: 10px;
}

.btn-edit {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #eee;
  padding: 8px 18px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.2s;
}

.btn-edit:hover {
  background: rgba(255, 255, 255, 0.2);
}

.btn-suggest {
  background: #6c63ff;
  border: none;
  color: white;
  padding: 8px 18px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.2s;
}

.btn-suggest:hover {
  background: #5b52e0;
}

.btn-approve {
  background: #22c55e;
  border: none;
  color: white;
  padding: 8px 20px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: background 0.2s;
}

.btn-approve:hover {
  background: #16a34a;
}
</style>
