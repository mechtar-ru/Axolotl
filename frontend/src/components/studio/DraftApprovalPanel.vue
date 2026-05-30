<template>
  <div class="draft-panel">
    <!-- Artifact Cards -->
    <div
      v-for="(artifact, idx) in artifacts"
      :key="idx"
      class="draft-artifact-card"
    >
      <div
        class="draft-artifact-header"
        :class="'draft-type-' + artifact.type"
        @click="toggleArtifact(idx)"
      >
        <svg
          class="draft-chevron"
          :class="{ expanded: collapsedPanels[idx] }"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          width="14"
          height="14"
        >
          <polyline points="9 18 15 12 9 6"/>
        </svg>
        <span class="draft-artifact-badge" :class="'draft-type-' + artifact.type">
          {{ artifact.label }}
        </span>
        <span class="draft-artifact-name">{{ artifact.name }}</span>
      </div>
      <div v-if="collapsedPanels[idx]" class="draft-artifact-body">
        <pre v-if="!editingDraft[idx]" class="plan-text">{{ artifact.content }}</pre>
        <textarea
          v-else
          v-model="editedDraftContent[idx]"
          class="plan-textarea"
          rows="6"
        />
        <button
          v-if="!editingDraft[idx]"
          class="draft-edit-btn"
          @click.stop="startDraftEdit(idx, artifact.content)"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg> Edit
        </button>
        <div v-else class="draft-edit-actions">
          <button class="btn-confirm-cancel" @click.stop="cancelDraftEdit(idx)">Cancel</button>
          <button class="btn-confirm-yes" @click.stop="saveDraftEdit(idx)">Save</button>
        </div>
      </div>
    </div>

    <!-- Feedback Section -->
    <div class="draft-summary">
      <h4 class="section-title">Your Feedback</h4>
      <textarea
        v-model="feedbackText"
        class="feedback-textarea"
        placeholder="Type feedback on the drafts or revision request..."
        rows="3"
      />
      <button class="add-feedback-btn" @click="addFeedback">+ Add more</button>
      <div v-if="feedbackItems.length > 0" class="feedback-items-list">
        <div v-for="(item, idx) in feedbackItems" :key="idx" class="feedback-tag">{{ item }}</div>
      </div>
    </div>

    <!-- Action Footer -->
    <div class="draft-footer">
      <div class="footer-actions">
        <button v-if="!showRejectConfirm" class="btn-reject" @click="showRejectConfirm = true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg> Reject Drafts
        </button>
        <span v-else class="confirm-reject-inline">
          <span class="confirm-text">Sure?</span>
          <button class="btn-confirm-yes" @click="handleReject">Yes, reject</button>
          <button class="btn-confirm-cancel" @click="showRejectConfirm = false">Cancel</button>
        </span>
        <button class="btn-suggest" @click="emitRegenerate">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg> Regenerate with Feedback
        </button>
        <button class="btn-approve" @click="emitApprove">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg> Approve &amp; Implement
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';

export interface DraftArtifact {
  type: string;
  name: string;
  label: string;
  content: string;
  expanded?: boolean;
}

const props = withDefaults(defineProps<{
  artifacts: DraftArtifact[];
  isSubmitting?: boolean;
}>(), {
  artifacts: () => [],
  isSubmitting: false,
});

const emit = defineEmits<{
  (e: 'approve', artifactsJson: string): void;
  (e: 'regenerate', feedback: string[]): void;
  (e: 'reject'): void;
}>();

const collapsedPanels = ref<boolean[]>([]);
const editingDraft = ref<boolean[]>([]);
const editedDraftContent = ref<string[]>([]);
const feedbackText = ref('');
const feedbackItems = ref<string[]>([]);
const showRejectConfirm = ref(false);

watch(() => props.artifacts, (val) => {
  collapsedPanels.value = val.map((_, i) => i === 0);
  editingDraft.value = val.map(() => false);
  editedDraftContent.value = val.map(a => a.content);
  feedbackText.value = '';
  feedbackItems.value = [];
  showRejectConfirm.value = false;
}, { immediate: true });

function toggleArtifact(idx: number) {
  collapsedPanels.value[idx] = !collapsedPanels.value[idx];
}

function startDraftEdit(idx: number, content: string) {
  if (editedDraftContent.value[idx] === undefined) {
    editedDraftContent.value[idx] = content;
  }
  editingDraft.value[idx] = true;
}

function cancelDraftEdit(idx: number) {
  editingDraft.value[idx] = false;
  editedDraftContent.value[idx] = props.artifacts[idx]?.content || '';
}

function saveDraftEdit(idx: number) {
  editingDraft.value[idx] = false;
}

function addFeedback() {
  if (feedbackText.value.trim()) {
    feedbackItems.value.push(feedbackText.value.trim());
    feedbackText.value = '';
  }
}

function emitApprove() {
  const editedArtifacts = props.artifacts.map((a, i) => ({
    type: a.type,
    name: a.name,
    content: editingDraft.value[i] ? editedDraftContent.value[i] : a.content,
  }));
  emit('approve', JSON.stringify(editedArtifacts));
}

function emitRegenerate() {
  emit('regenerate', feedbackItems.value);
}

function handleReject() {
  showRejectConfirm.value = false;
  emit('reject');
}
</script>

<style scoped>
.draft-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* Artifact Cards */
.draft-artifact-card {
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.draft-artifact-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  user-select: none;
  transition: background var(--transition);
}

.draft-artifact-header:hover {
  background: var(--bg-hover);
}

.draft-chevron {
  transition: transform 0.15s ease;
  flex-shrink: 0;
}

.draft-chevron.expanded {
  transform: rotate(90deg);
}

.draft-artifact-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  text-transform: uppercase;
  letter-spacing: 0.3px;
  flex-shrink: 0;
}

.draft-type-spec {
  background: color-mix(in srgb, #6366f1 20%, transparent);
  color: #a5b4fc;
  border: 1px solid color-mix(in srgb, #6366f1 30%, transparent);
}

.draft-type-plan {
  background: color-mix(in srgb, #f59e0b 20%, transparent);
  color: #fcd34d;
  border: 1px solid color-mix(in srgb, #f59e0b 30%, transparent);
}

.draft-type-ui {
  background: color-mix(in srgb, #ec4899 20%, transparent);
  color: #f9a8d4;
  border: 1px solid color-mix(in srgb, #ec4899 30%, transparent);
}

.draft-type-backend {
  background: color-mix(in srgb, #3b82f6 20%, transparent);
  color: #93c5fd;
  border: 1px solid color-mix(in srgb, #3b82f6 30%, transparent);
}

.draft-artifact-name {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  font-weight: 500;
}

.draft-artifact-body {
  padding: 0 var(--space-3) var(--space-3);
}

.draft-edit-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: var(--space-2);
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
  transition: background var(--transition);
}

.draft-edit-btn:hover {
  background: var(--bg-active);
  color: var(--text-primary);
}

.draft-edit-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

/* Feedback */
.draft-summary {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border-color);
}

.section-title {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--text-muted);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

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
.draft-footer {
  border-top: 1px solid var(--border-color);
  padding: 14px 0 0;
}

.footer-actions {
  display: flex;
  gap: var(--space-2);
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

.confirm-reject-inline {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) 0;
  margin-right: auto;
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
