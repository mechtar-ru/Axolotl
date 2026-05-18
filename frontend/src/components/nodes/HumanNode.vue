<template>
  <div class="node human-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Delete">✕</button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">👤</span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" @blur="finishEditName" @keyup.enter="finishEditName" />
      <span class="execution-icon">{{ executionIcon }}</span>
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>
    <div v-if="expanded" class="node-content">
      <textarea v-model="localPrompt" placeholder="Question/instruction for human...&#10;Example: Confirm result: does the response contain valid data?" rows="3" @mousedown.stop @mouseup.stop />
      <div v-if="props.data.executionStatus === 'running' && !approved" class="approval-panel">
        <div class="approval-context" v-if="props.data.result">{{ props.data.result }}</div>
        <div class="approval-buttons">
          <button class="approve-btn" @click="approve">✅ Approve</button>
          <button class="reject-btn" @click="reject">❌ Reject</button>
        </div>
        <textarea v-model="feedback" placeholder="Comment (optional)..." rows="2" class="feedback-input" />
      </div>
  <div v-if="approved === true" class="status-badge approved">Подтверждено</div>
  <div v-if="approved === false" class="status-badge rejected">Отклонено</div>
      <div v-if="props.data.result && approved !== false" class="node-result">
        <strong>Result:</strong>
        <div>{{ props.data.result }}</div>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}ms</div>
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
const executionIcon = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '⏳';
    case 'completed': return '✅';
    case 'failed': return '❌';
    default: return '';
  }
});

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
.human-node { border-color: #ff7043; }
.human-node.selected { border-color: #ff6b6b; box-shadow: 0 0 0 2px rgba(255,107,107,0.3); }
.node { background: #2d2d44; border-radius: 8px; border: 2px solid #4a4a6a; min-width: 220px; max-width: 300px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); position: relative; }
.delete-btn { position: absolute; top: -10px; right: -10px; width: 24px; height: 24px; background: #dc3545; color: white; border: none; border-radius: 50%; cursor: pointer; font-size: 14px; display: flex; align-items: center; justify-content: center; z-index: 10; }
.node-header { padding: 10px; background: #1e1e2e; border-radius: 6px 6px 0 0; display: flex; align-items: center; gap: 8px; }
.node-icon { font-size: 20px; }
.node-name { flex: 1; font-weight: bold; color: #eee; cursor: pointer; }
.node-name-input { flex: 1; background: #1a1a2e; border: 1px solid #ff7043; color: #eee; border-radius: 4px; padding: 2px 6px; font-size: 14px; font-weight: bold; }
.execution-icon { font-size: 14px; margin-left: 4px; }
.node-expand { background: none; border: none; color: #eee; cursor: pointer; font-size: 12px; }
.node-content { padding: 10px; }
.node-content textarea { width: 100%; background: #1a1a2e; border: 1px solid #4a4a6a; color: #eee; border-radius: 4px; padding: 8px; font-family: monospace; resize: vertical; }
.approval-panel { margin-top: 8px; padding: 8px; background: rgba(255, 112, 67, 0.1); border: 1px solid rgba(255, 112, 67, 0.3); border-radius: 6px; }
.approval-context { font-size: 12px; color: #ccc; margin-bottom: 8px; word-break: break-word; }
.approval-buttons { display: flex; gap: 8px; margin-bottom: 6px; }
.approve-btn { flex: 1; background: #4caf50; border: none; color: white; padding: 8px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.reject-btn { flex: 1; background: #f44336; border: none; color: white; padding: 8px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.feedback-input { width: 100%; background: #1a1a2e; border: 1px solid #4a4a6a; color: #eee; border-radius: 4px; padding: 6px; font-size: 12px; resize: none; }
.status-badge { display: inline-block; padding: 4px 10px; border-radius: 4px; font-size: 12px; font-weight: 600; margin-top: 6px; }
.status-badge.approved { background: rgba(76, 175, 80, 0.2); color: #4caf50; }
.status-badge.rejected { background: rgba(244, 67, 54, 0.2); color: #f44336; }
.node-result { margin-top: 10px; padding: 8px; background: #1a1a2e; border-radius: 4px; font-size: 12px; word-break: break-word; }
.node-time { margin-top: 6px; font-size: 11px; color: #888; text-align: right; }
.node-running { animation: pulse-running 1.5s ease-in-out infinite; }
.node-completed { box-shadow: 0 0 12px rgba(76, 175, 80, 0.5); }
.node-failed { box-shadow: 0 0 12px rgba(255, 0, 0, 0.5); }
@keyframes pulse-running { 0%, 100% { box-shadow: 0 0 4px rgba(255, 165, 0, 0.3); } 50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); } }
</style>
