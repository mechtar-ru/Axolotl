<template>
  <div class="node human-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Удалить">✕</button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">👤</span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" @blur="finishEditName" @keyup.enter="finishEditName" />
      <span class="execution-icon">{{ executionIcon }}</span>
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>
    <div v-if="expanded" class="node-content">
      <textarea v-model="localPrompt" placeholder="Вопрос/инструкция для человека...&#10;Например: Подтвердите результат: содержит ли ответ корректные данные?" rows="3" @mousedown.stop @mouseup.stop />
      <div v-if="props.data.executionStatus === 'running' && !approved" class="approval-panel">
        <div class="approval-context" v-if="props.data.result">{{ props.data.result }}</div>
        <div class="approval-buttons">
          <button class="approve-btn" @click="approve">✅ Подтвердить</button>
          <button class="reject-btn" @click="reject">❌ Отклонить</button>
        </div>
        <textarea v-model="feedback" placeholder="Комментарий (опционально)..." rows="2" class="feedback-input" />
      </div>
      <div v-if="approved === true" class="status-badge approved">Подтверждено</div>
      <div v-if="approved === false" class="status-badge rejected">Отклонено</div>
      <div v-if="props.data.result && approved !== false" class="node-result">
        <strong>Результат:</strong>
        <div>{{ props.data.result }}</div>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}мс</div>
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
  props.data.onUpdate?.({ config: { ...(props.data.config || {}), approved: false, feedback: feedback.value }, result: 'Отклонено: ' + (feedback.value || 'без комментария') });
}
</script>

<style scoped>
.human-node { border-color: var(--node-human); }
.human-node.selected { border-color: var(--accent); box-shadow: var(--shadow-glow-accent); }
.node { background: var(--bg-card); border-radius: var(--radius-sm); border: 2px solid var(--border); min-width: 220px; max-width: 300px; box-shadow: var(--shadow-sm); position: relative; }
.delete-btn { position: absolute; top: -10px; right: -10px; width: 24px; height: 24px; background: var(--error); color: var(--text-inverse); border: none; border-radius: 50%; cursor: pointer; font-size: 14px; display: flex; align-items: center; justify-content: center; z-index: 10; }
.node-header { padding: var(--space-2-5); background: var(--bg-secondary); border-radius: 6px 6px 0 0; display: flex; align-items: center; gap: var(--space-2); }
.node-icon { font-size: 20px; }
.node-name { flex: 1; font-weight: bold; color: var(--text-primary); cursor: pointer; }
.node-name-input { flex: 1; background: var(--bg-primary); border: 1px solid var(--node-human); color: var(--text-primary); border-radius: var(--radius-sm); padding: 2px 6px; font-size: var(--text-md); font-weight: bold; }
.execution-icon { font-size: 14px; margin-left: 4px; }
.node-expand { background: none; border: none; color: var(--text-primary); cursor: pointer; font-size: 12px; }
.node-content { padding: var(--space-2-5); }
.node-content textarea { width: 100%; background: var(--bg-primary); border: 1px solid var(--border); color: var(--text-primary); border-radius: var(--radius-sm); padding: 8px; font-family: var(--font-mono); resize: vertical; }
.approval-panel { margin-top: var(--space-2); padding: 8px; background: var(--warning-light); border: 1px solid rgba(255, 112, 67, 0.3); border-radius: var(--radius-sm); }
.approval-context { font-size: var(--text-sm); color: var(--text-secondary); margin-bottom: 8px; word-break: break-word; }
.approval-buttons { display: flex; gap: var(--space-2); margin-bottom: var(--space-1-5); }
.approve-btn { flex: 1; background: var(--success); border: none; color: var(--text-inverse); padding: 8px; border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-base); }
.reject-btn { flex: 1; background: var(--error); border: none; color: var(--text-inverse); padding: 8px; border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-base); }
.feedback-input { width: 100%; background: var(--bg-primary); border: 1px solid var(--border); color: var(--text-primary); border-radius: var(--radius-sm); padding: 6px; font-size: var(--text-sm); resize: none; }
.status-badge { display: inline-block; padding: 4px 10px; border-radius: var(--radius-sm); font-size: var(--text-sm); font-weight: 600; margin-top: var(--space-1-5); }
.status-badge.approved { background: var(--success-light); color: var(--success); }
.status-badge.rejected { background: var(--error-light); color: var(--error); }
.node-result { margin-top: var(--space-2-5); padding: 8px; background: var(--bg-primary); border-radius: var(--radius-sm); font-size: var(--text-sm); word-break: break-word; }
.node-time { margin-top: var(--space-1-5); font-size: var(--text-xs); color: var(--text-muted); text-align: right; }
.node-running { animation: pulse-running var(--transition-slow) ease-in-out infinite; }
.node-completed { box-shadow: var(--shadow-glow-success); }
.node-failed { box-shadow: var(--shadow-glow-error); }
@keyframes pulse-running { 0%, 100% { box-shadow: 0 0 4px var(--warning-light); } 50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); } }
</style>
