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
.fallback-node { border-color: #78909c; }
.fallback-node.selected { border-color: #ff6b6b; box-shadow: 0 0 0 2px rgba(255,107,107,0.3); }
.node { background: #2d2d44; border-radius: 8px; border: 2px solid #4a4a6a; min-width: 220px; max-width: 300px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); position: relative; }
.delete-btn { position: absolute; top: -10px; right: -10px; width: 24px; height: 24px; background: #dc3545; color: white; border: none; border-radius: 50%; cursor: pointer; font-size: 14px; display: flex; align-items: center; justify-content: center; z-index: 10; }
.node-header { padding: 10px; background: #1e1e2e; border-radius: 6px 6px 0 0; display: flex; align-items: center; gap: 8px; }
.node-icon { font-size: 20px; }
.node-name { flex: 1; font-weight: bold; color: #eee; cursor: pointer; }
.node-name-input { flex: 1; background: #1a1a2e; border: 1px solid #78909c; color: #eee; border-radius: 4px; padding: 2px 6px; font-size: 14px; font-weight: bold; }
.execution-icon { font-size: 14px; margin-left: 4px; }
.node-expand { background: none; border: none; color: #eee; cursor: pointer; font-size: 12px; }
.node-content { padding: 10px; }
.node-content textarea { width: 100%; background: #1a1a2e; border: 1px solid #4a4a6a; color: #eee; border-radius: 4px; padding: 8px; font-family: monospace; resize: vertical; }
.fallback-hint { font-size: 11px; color: #78909c; margin-top: 6px; font-style: italic; }
.node-result { margin-top: 10px; padding: 8px; background: #1a1a2e; border-radius: 4px; font-size: 12px; word-break: break-word; }
.node-time { margin-top: 6px; font-size: 11px; color: #888; text-align: right; }
.node-running { animation: pulse-running 1.5s ease-in-out infinite; }
.node-completed { box-shadow: 0 0 12px rgba(76, 175, 80, 0.5); }
.node-failed { box-shadow: 0 0 12px rgba(255, 0, 0, 0.5); }
@keyframes pulse-running { 0%, 100% { box-shadow: 0 0 4px rgba(255, 165, 0, 0.3); } 50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); } }
.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
