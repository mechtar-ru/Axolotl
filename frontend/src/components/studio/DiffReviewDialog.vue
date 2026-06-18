<template>
  <div v-if="visible" class="diff-overlay">
    <div class="diff-modal">
      <div class="diff-header">
        <span class="diff-title">File Changes Review</span>
        <span class="diff-subtitle">{{ diffs.length }} file(s) modified — review each change below</span>
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
import { ref } from 'vue';

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
}>();

const processing = ref(false);
const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8082/api';

function removedLines(diff: string): number {
  return (diff.match(/^-/gm) || []).length;
}
function addedLines(diff: string): number {
  return (diff.match(/^\+/gm) || []).length;
}

async function handleApprove() {
  processing.value = true;
  try {
    await fetch(`${API_BASE}/execution/${props.executionId}/approve-diffs?nodeId=${props.nodeId}`, {
      method: 'POST',
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
    await fetch(`${API_BASE}/execution/${props.executionId}/reject-diffs?nodeId=${props.nodeId}`, {
      method: 'POST',
    });
  } catch (e) {
    console.error('DiffReviewDialog: Reject diffs failed:', e);
  }
  processing.value = false;
  emit('close');
}
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
</style>
