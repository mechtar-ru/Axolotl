<template>
  <div class="node fallback-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg></span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" @blur="finishEditName" @keyup.enter="finishEditName" />
      <span class="execution-icon">
        <svg v-if="props.data.executionStatus === 'running'" class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10" stroke-dasharray="31.4 31.4" stroke-linecap="round"/></svg>
        <svg v-else-if="props.data.executionStatus === 'completed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
        <svg v-else-if="props.data.executionStatus === 'failed'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </span>
      <button class="node-expand" @click="expanded = !expanded">
        <svg :class="['chevron', { expanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg>
      </button>
    </div>
    <div v-if="expanded" class="node-content">
      <textarea v-model="localPrompt" placeholder="Fallback prompt on error...&#10;Executes when preceding node fails." rows="3" @mousedown.stop @mouseup.stop />
      <div class="fallback-hint">Only runs on predecessor error</div>
      <div v-if="props.data.result" class="node-result">
        <strong>Result:</strong>
        <div>{{ props.data.result }}</div>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
        {{ props.data.nodeTimeMs }}ms
      </div>
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

const isSelected = computed(() => props.selected === true);
const executionIcon = computed(() => '');

watch(localPrompt, v => props.data.onUpdate?.({ userPrompt: v }));

function startEditName() { editingName.value = true; nextTick(() => nameInput.value?.focus()); }
function finishEditName() {
  if (localName.value.trim() && props.data.onRename) props.data.onRename(localName.value.trim());
  else localName.value = props.data.name;
  editingName.value = false;
}
function handleDelete() { props.data.onDelete?.(); }
</script>

<style scoped>
.fallback-node { border-color: var(--node-fallback); }
.fallback-node.selected { border-color: var(--accent-light); box-shadow: var(--shadow-glow-accent); }
.node { background: var(--bg-card); border-radius: var(--radius-md); border: 2px solid var(--border-color); min-width: 220px; max-width: 300px; box-shadow: var(--shadow-sm); position: relative; }
.delete-btn { position: absolute; top: -10px; right: -10px; width: 24px; height: 24px; background: var(--error); color: white; border: none; border-radius: 50%; cursor: pointer; font-size: var(--text-sm); display: flex; align-items: center; justify-content: center; z-index: var(--z-canvas); }
.node-header { padding: var(--space-3); background: var(--bg-secondary); border-radius: var(--radius-sm) var(--radius-sm) 0 0; display: flex; align-items: center; gap: var(--space-2); }
.node-icon { font-size: var(--text-lg); }
.node-name { flex: 1; font-weight: bold; color: var(--text-primary); cursor: pointer; }
.node-name-input { flex: 1; background: var(--bg-primary); border: 1px solid var(--node-fallback); color: var(--text-primary); border-radius: var(--radius-sm); padding: 2px 6px; font-size: var(--text-sm); font-weight: bold; }
.execution-icon { font-size: var(--text-sm); margin-left: var(--space-1); }
.node-expand { background: none; border: none; color: var(--text-primary); cursor: pointer; font-size: var(--text-xs); }
.node-content { padding: var(--space-3); }
.node-content textarea { width: 100%; background: var(--bg-input); border: 1px solid var(--border-color); color: var(--text-primary); border-radius: var(--radius-sm); padding: var(--space-2); font-family: var(--font-mono); resize: vertical; }
.fallback-hint { font-size: 11px; color: var(--node-fallback); margin-top: var(--space-1); font-style: italic; }
.node-result { margin-top: var(--space-3); padding: var(--space-2); background: var(--bg-primary); border-radius: var(--radius-sm); font-size: var(--text-xs); word-break: break-word; }
.node-time { margin-top: var(--space-1); font-size: 11px; color: var(--text-muted); text-align: right; }
.node-running { animation: pulse-running 1.5s ease-in-out infinite; }
.node-completed { box-shadow: var(--shadow-glow-success); }
.node-failed { box-shadow: var(--shadow-glow-error); }
@keyframes pulse-running { 0%, 100% { box-shadow: var(--shadow-glow-warning); } 50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); } }
.chevron { transition: transform var(--transition); vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
