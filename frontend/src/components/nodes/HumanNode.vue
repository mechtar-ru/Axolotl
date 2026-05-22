<template>
  <div class="node human-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Delete">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg></span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" @blur="finishEditName" @keyup.enter="finishEditName" />
      <span class="execution-icon">
        <svg v-if="props.data.executionStatus === 'running'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" class="spin"><circle cx="12" cy="12" r="10"/></svg>
        <svg v-else-if="props.data.executionStatus === 'completed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
        <svg v-else-if="props.data.executionStatus === 'failed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </span>
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>
    <div v-if="expanded" class="node-content">
      <textarea v-model="localPrompt" placeholder="Question/instruction for human...&#10;Example: Confirm result: does the response contain valid data?" rows="3" @mousedown.stop @mouseup.stop />
      <div v-if="props.data.executionStatus === 'running' && !approved" class="approval-panel">
        <div class="approval-context" v-if="props.data.result">{{ props.data.result }}</div>
        <div class="approval-buttons">
          <button class="approve-btn" @click="approve"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" style="vertical-align:middle;margin-right:4px"><polyline points="20 6 9 17 4 12"/></svg>Approve</button>
          <button class="reject-btn" @click="reject"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14" style="vertical-align:middle;margin-right:4px"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>Reject</button>
        </div>
        <textarea v-model="feedback" placeholder="Comment (optional)..." rows="2" class="feedback-input" />
      </div>
  <div v-if="approved === true" class="status-badge approved">Подтверждено</div>
  <div v-if="approved === false" class="status-badge rejected">Отклонено</div>
      <div v-if="props.data.result && approved !== false" class="node-result">
        <strong>Result:</strong>
        <div>{{ props.data.result }}</div>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12" style="vertical-align:middle;margin-right:2px"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> {{ props.data.nodeTimeMs }}ms</div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue';
import { Handle, Position } from '@vue-flow/core';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    userPrompt?: string;
    result?: string;
    config?: Record<string, any>;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    nodeTimeMs?: number;
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
    onDelete?: () => void;
  };
}>();

const editingName = ref(false);
const localName = ref(props.data.name);
const localPrompt = ref(props.data.userPrompt || '');
const nameInput = ref<HTMLInputElement | null>(null);
const expanded = ref(true);
const feedback = ref('');
const approved = ref<boolean | null>(null);

const isSelected = computed(() => props.selected === true);
watch(localPrompt, v => props.data.onUpdate?.({ userPrompt: v }));

function startEditName() { editingName.value = true; nextTick(() => nameInput.value?.focus()); }
function finishEditName() {
  if (localName.value.trim() && props.data.onRename) props.data.onRename(localName.value.trim());
  else localName.value = props.data.name;
  editingName.value = false;
}
function handleDelete() { props.data.onDelete?.(); }

function approve() {
  approved.value = true;
  props.data.onUpdate?.({ config: { ...(props.data.config || {}), approved: true, feedback: feedback.value } });
}
function reject() {
  approved.value = false;
  props.data.onUpdate?.({ config: { ...(props.data.config || {}), approved: false, feedback: feedback.value }, result: 'Rejected: ' + (feedback.value || 'no comment') });
}
</script>

<style scoped>
.human-node { border-color: var(--node-human); }
.human-node.selected { border-color: var(--accent-light); box-shadow: var(--shadow-glow-accent); }
.node { background: var(--bg-card); border-radius: var(--radius-md); border: 2px solid var(--border-color); min-width: 220px; max-width: 300px; box-shadow: var(--shadow-sm); position: relative; }
.delete-btn { position: absolute; top: -10px; right: -10px; width: 24px; height: 24px; background: var(--error); color: white; border: none; border-radius: 50%; cursor: pointer; font-size: var(--text-sm); display: flex; align-items: center; justify-content: center; z-index: var(--z-canvas); }
.node-header { padding: var(--space-3); background: var(--bg-secondary); border-radius: var(--radius-sm) var(--radius-sm) 0 0; display: flex; align-items: center; gap: var(--space-2); }
.node-icon { font-size: var(--text-lg); }
.node-name { flex: 1; font-weight: bold; color: var(--text-primary); cursor: pointer; }
.node-name-input { flex: 1; background: var(--bg-primary); border: 1px solid var(--node-human); color: var(--text-primary); border-radius: var(--radius-sm); padding: 2px 6px; font-size: var(--text-sm); font-weight: bold; }
.execution-icon { font-size: var(--text-sm); margin-left: var(--space-1); }
.node-expand { background: none; border: none; color: var(--text-primary); cursor: pointer; font-size: var(--text-xs); }
.node-content { padding: var(--space-3); }
.node-content textarea { width: 100%; background: var(--bg-input); border: 1px solid var(--border-color); color: var(--text-primary); border-radius: var(--radius-sm); padding: var(--space-2); font-family: var(--font-mono); resize: vertical; }
.approval-panel { margin-top: var(--space-2); padding: var(--space-2); background: var(--warning-light); border: 1px solid rgba(255, 152, 0, 0.3); border-radius: var(--radius-sm); }
.approval-context { font-size: var(--text-xs); color: var(--text-secondary); margin-bottom: var(--space-2); word-break: break-word; }
.approval-buttons { display: flex; gap: var(--space-2); margin-bottom: var(--space-2); }
.approve-btn { flex: 1; background: var(--success); border: none; color: white; padding: var(--space-2); border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-xs); }
.reject-btn { flex: 1; background: var(--error); border: none; color: white; padding: var(--space-2); border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-xs); }
.feedback-input { width: 100%; background: var(--bg-input); border: 1px solid var(--border-color); color: var(--text-primary); border-radius: var(--radius-sm); padding: var(--space-1); font-size: var(--text-xs); resize: none; }
.status-badge { display: inline-block; padding: var(--space-1) var(--space-3); border-radius: var(--radius-sm); font-size: var(--text-xs); font-weight: 600; margin-top: var(--space-1); }
.status-badge.approved { background: var(--success-light); color: var(--success); }
.status-badge.rejected { background: var(--error-light); color: var(--error); }
.node-result { margin-top: var(--space-3); padding: var(--space-2); background: var(--bg-primary); border-radius: var(--radius-sm); font-size: var(--text-xs); word-break: break-word; }
.node-time { margin-top: var(--space-1); font-size: 11px; color: var(--text-muted); text-align: right; }
.node-running { animation: pulse-running 1.5s ease-in-out infinite; }
.node-completed { box-shadow: var(--shadow-glow-success); }
.node-failed { box-shadow: var(--shadow-glow-error); }
@keyframes pulse-running { 0%, 100% { box-shadow: var(--shadow-glow-warning); } 50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); } }
</style>
