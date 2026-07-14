<template>
  <div v-if="visible" class="diff-overlay" @click.self="!processing && emit('close')" @keydown.esc="emit('close')" role="dialog" aria-modal="true" aria-labelledby="diff-title">
    <div class="diff-modal">
      <div class="diff-header">
        <span id="diff-title" class="diff-title">File Changes Review</span>
        <span class="diff-subtitle">{{ diffs.length }} file(s) modified — review each change below</span>
        <button class="close-btn" @click="emit('close')" :disabled="processing" aria-label="Close">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
            <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>

      <div class="diff-body">
        <div v-for="(d, idx) in diffs" :key="idx" class="diff-entry">
          <div class="diff-file-header">
            <span class="diff-file-path">{{ d.filePath }}</span>
            <span class="diff-stats">
              -{{ removedLines(d.diff) }} +{{ addedLines(d.diff) }}
            </span>
          </div>
          <pre class="diff-content"><code>{{ d.diff }}</code></pre>
          <div class="diff-file-actions">
            <button class="btn-file-reject" :disabled="processing" @click="rejectFile(idx)">
              Reject
            </button>
            <button class="btn-file-approve" :disabled="processing" @click="approveFile(idx)">
              Approve
            </button>
          </div>
        </div>
      </div>

      <div class="diff-footer">
        <div class="footer-actions">
          <button class="btn-reject" :disabled="processing" @click="handleReject">
            {{ processing ? 'Processing...' : 'Reject All' }}
          </button>
          <button class="btn-approve" :disabled="processing" @click="handleApprove">
            {{ processing ? 'Processing...' : 'Accept All' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { api } from '@/services/api';

interface DiffItem {
  filePath: string;
  diff: string;
  originalLength: number;
  newLength: number;
}

const props = withDefaults(defineProps<{
  visible: boolean;
  schemaId: string;
  executionId: string;
  nodeId: string;
  diffs: DiffItem[];
}>(), {
  visible: false,
  schemaId: '',
  executionId: '',
  nodeId: '',
  diffs: () => [],
});

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'file-action', action: 'approve' | 'reject', fileIdx: number): void;
}>();

const processing = ref(false);
const focusedIdx = ref(0);

function removedLines(diff: string): number {
  return (diff.match(/^-/gm) || []).length;
}
function addedLines(diff: string): number {
  return (diff.match(/^\+/gm) || []).length;
}

async function approveFile(idx: number) {
  if (processing.value) return;
  processing.value = true;
  try {
    await api.post(`/execution/${props.executionId}/approve-diffs`, null, {
      params: { nodeId: props.nodeId, fileIdx: idx },
    });
  } catch (e) {
    console.error('DiffReviewDialog: Approve file failed:', e);
  } finally {
    processing.value = false;
  }
  emit('file-action', 'approve', idx);
}

async function rejectFile(idx: number) {
  if (processing.value) return;
  processing.value = true;
  try {
    await api.post(`/execution/${props.executionId}/reject-diffs`, null, {
      params: { nodeId: props.nodeId, fileIdx: idx },
    });
  } catch (e) {
    console.error('DiffReviewDialog: Reject file failed:', e);
  } finally {
    processing.value = false;
  }
  emit('file-action', 'reject', idx);
}

async function handleApprove() {
  processing.value = true;
  try {
    await api.post(`/execution/${props.executionId}/approve-diffs`, null, {
      params: { nodeId: props.nodeId },
    });
  } catch (e) {
    console.error('DiffReviewDialog: Approve diffs failed:', e);
  }
  processing.value = false;
  emit('close');
}

async function handleReject() {
  processing.value = true;
  try {
    await api.post(`/execution/${props.executionId}/reject-diffs`, null, {
      params: { nodeId: props.nodeId },
    });
  } catch (e) {
    console.error('DiffReviewDialog: Reject diffs failed:', e);
  }
  processing.value = false;
  emit('close');
}

// Keyboard navigation between files
function onKeyDown(e: KeyboardEvent) {
  if (!props.visible || processing.value) {
    // Allow escape even during processing
    if (e.key === 'Escape') emit('close')
    return
  }
  // Ctrl+A / Cmd+A: approve all
  if ((e.metaKey || e.ctrlKey) && e.key === 'a') {
    e.preventDefault()
    handleApprove()
    return
  }
  // Ctrl+R / Cmd+R: reject all
  if ((e.metaKey || e.ctrlKey) && e.key === 'r') {
    e.preventDefault()
    handleReject()
    return
  }
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
    e.preventDefault();
    focusedIdx.value = Math.min(focusedIdx.value + 1, props.diffs.length - 1);
    nextTick(() => focusDiffEntry());
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
    e.preventDefault();
    focusedIdx.value = Math.max(focusedIdx.value - 1, 0);
    nextTick(() => focusDiffEntry());
  } else if (e.key === 'Enter' || e.key === ' ') {
    // Quick approve on Enter
    const idx = focusedIdx.value;
    if (idx < props.diffs.length) {
      approveFile(idx);
    }
  } else if (e.key === 'Backspace' || e.key === 'Delete') {
    // Quick reject on Backspace/Delete
    const idx = focusedIdx.value;
    if (idx < props.diffs.length) {
      rejectFile(idx);
    }
  }
}

function focusDiffEntry() {
  const entries = document.querySelectorAll('.diff-entry');
  entries[focusedIdx.value]?.querySelector('button')?.focus();
}

onMounted(() => {
  document.addEventListener('keydown', onKeyDown);
});

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeyDown);
});
</script>

<style scoped>
.diff-overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay-heavy, rgba(0,0,0,0.5));
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-tooltip, 1000);
}

.diff-modal {
  background: var(--bg-primary, #1a1a2e);
  border-radius: var(--radius-lg, 12px);
  width: 720px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg, 0 8px 32px rgba(0,0,0,0.3));
  border: 1px solid var(--border-color, #2a2a4a);
  overflow: hidden;
}

.diff-header {
  padding: var(--space-4, 16px) var(--space-5, 20px);
  border-bottom: 1px solid var(--border-color, #2a2a4a);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.diff-title {
  color: var(--text-primary, #e0e0e0);
  font-weight: 600;
  font-size: var(--text-sm, 14px);
}

.diff-subtitle {
  color: var(--text-muted, #707080);
  font-size: var(--text-xs, 12px);
}

.diff-body {
  flex: 1;
  padding: var(--space-4, 16px) var(--space-5, 20px);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4, 16px);
}

.diff-entry {
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: var(--radius-md, 8px);
  overflow: hidden;
}

.diff-file-header {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background: var(--bg-surface, rgba(255,255,255,0.03));
  border-bottom: 1px solid var(--border-subtle, rgba(255,255,255,0.08));
}

.diff-file-path {
  flex: 1;
  color: var(--accent, #7c5cfc);
  font-size: var(--text-xs, 12px);
  font-family: var(--font-mono, monospace);
}

.diff-stats {
  color: var(--text-muted, #707080);
  font-size: 11px;
}

.diff-content {
  margin: 0;
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background: var(--bg-canvas, #0d0d1a);
  overflow-x: auto;
  font-size: 11px;
  line-height: 1.5;
  max-height: 300px;
  overflow-y: auto;
}

.diff-content code {
  color: var(--text-secondary, #a0a0b0);
  font-family: var(--font-mono, monospace);
  white-space: pre;
}

.diff-footer {
  padding: 14px var(--space-5, 20px);
  border-top: 1px solid var(--border-color, #2a2a4a);
}

.footer-actions {
  display: flex;
  gap: var(--space-2, 8px);
  justify-content: flex-end;
}

.btn-approve {
  background: var(--success, #4caf50);
  border: none;
  color: white;
  padding: var(--space-2, 8px) var(--space-5, 20px);
  border-radius: var(--radius-md, 8px);
  cursor: pointer;
  font-size: var(--text-xs, 12px);
  font-weight: 600;
}

.btn-reject {
  background: transparent;
  border: 1px solid var(--error, #f44336);
  color: var(--error, #f44336);
  padding: var(--space-2, 8px) var(--space-5, 20px);
  border-radius: var(--radius-md, 8px);
  cursor: pointer;
  font-size: var(--text-xs, 12px);
  font-weight: 600;
}

.btn-approve:disabled,
.btn-reject:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.close-btn {
  background: var(--bg-hover, rgba(255,255,255,0.05));
  border: none;
  color: var(--text-primary);
  width: 30px;
  height: 30px;
  border-radius: var(--radius-sm, 4px);
  cursor: pointer;
  font-size: var(--text-sm, 14px);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.close-btn:hover:not(:disabled) {
  background: var(--bg-active, rgba(255,255,255,0.1));
}

.close-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
