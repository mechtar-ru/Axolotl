<template>
  <div class="node memory-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M12 4a4 4 0 0 1 4 4c0 2-1.2 3.5-2 4.5V18h-4v-5.5C8.2 11.5 7 10 7 8a4 4 0 0 1 4-4z"/><path d="M9 20h6"/></svg></span>
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
      <div class="search-row">
        <input v-model="searchQuery" placeholder="Search memory..." class="search-input" @keyup.enter="searchMemory" />
        <button class="search-btn" @click="searchMemory" :disabled="searching">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </button>
      </div>

      <div class="filter-row">
        <input v-model="filterWing" placeholder="Wing" class="filter-input" />
        <input v-model="filterRoom" placeholder="Room" class="filter-input" />
      </div>

      <div v-if="searching" class="search-status">Searching...</div>

      <div v-if="results.length > 0" class="results-list">
        <div v-for="(r, i) in results" :key="i" class="result-card" @click="selectResult(r)">
          <div class="result-wing">{{ r.wing }}/{{ r.room }}</div>
          <div class="result-text">{{ truncate(r.content, 120) }}</div>
        </div>
      </div>

      <div v-if="selectedMemory" class="selected-memory">
        <strong>Selected:</strong>
        <div class="memory-content">{{ selectedMemory.content }}</div>
        <button class="pin-btn" @click="pinToOutput">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 2L15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2z"/></svg>
          Output to result
        </button>
      </div>

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
import { ref, computed, nextTick } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { api } from '../../services/api';

interface MemoryResult {
  wing: string;
  room: string;
  content: string;
  score?: number;
}

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    sourceData?: string;
    result?: string;
    config?: Record<string, any>;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    nodeTimeMs?: number;
    position?: { x: number; y: number };
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
    onDelete?: () => void;
    onMemoryResults?: (nodeId: string, results: MemoryResult[]) => void;
  };
}>();

const editingName = ref(false);
const localName = ref(props.data.name);
const nameInput = ref<HTMLInputElement | null>(null);
const expanded = ref(true);
const searchQuery = ref('');
const filterWing = ref('');
const filterRoom = ref('');
const searching = ref(false);
const results = ref<MemoryResult[]>([]);
const selectedMemory = ref<MemoryResult | null>(null);

const isSelected = computed(() => props.selected === true);
const executionIcon = computed(() => '');

function startEditName() {
  editingName.value = true;
  nextTick(() => nameInput.value?.focus());
}

function finishEditName() {
  if (localName.value.trim() && props.data.onRename) {
    props.data.onRename(localName.value.trim());
  } else {
    localName.value = props.data.name;
  }
  editingName.value = false;
}

function handleDelete() {
  if (props.data.onDelete) props.data.onDelete();
}

async function searchMemory() {
  if (!searchQuery.value.trim()) return;
  searching.value = true;
  results.value = [];
  try {
    const params: Record<string, string> = { query: searchQuery.value, limit: '5' };
    if (filterWing.value) params.wing = filterWing.value;
    if (filterRoom.value) params.room = filterRoom.value;
    const { data } = await api.get('/memory/search', { params });
    results.value = data.map((r: any) => ({
      wing: r.wing || '',
      room: r.room || '',
      content: r.content || '',
      score: r.score,
    }));
    if (props.data.onMemoryResults) {
      props.data.onMemoryResults(props.id, results.value);
    }
  } catch (e) {
    console.error('Memory search failed:', e);
  } finally {
    searching.value = false;
  }
}

function selectResult(r: MemoryResult) {
  selectedMemory.value = r;
}

function pinToOutput() {
  if (!selectedMemory.value) return;
  if (props.data.onUpdate) {
    props.data.onUpdate({ result: selectedMemory.value.content, sourceData: selectedMemory.value.content });
  }
}

function truncate(str: string, len: number): string {
  return str.length > len ? str.substring(0, len) + '...' : str;
}
</script>

<style scoped>
@import './node-base.css';

.memory-node {
  border-color: #00bcd4;
}
.memory-node.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}
.search-row { display: flex; gap: 4px; }
.search-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid #00bcd4;
  color: var(--text-primary);
  border-radius: 4px;
  padding: 6px 8px;
  font-size: 13px;
  outline: none;
}
.search-btn {
  background: #00bcd4;
  border: none;
  color: white;
  border-radius: 4px;
  width: 32px;
  cursor: pointer;
}
.filter-row { display: flex; gap: 4px; margin-top: 6px; }
.filter-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
}
.search-status { font-size: 12px; color: #00bcd4; margin-top: 6px; }
.results-list { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.result-card {
  background: rgba(0, 188, 212, 0.1);
  border: 1px solid rgba(0, 188, 212, 0.3);
  border-radius: 6px;
  padding: 6px 8px;
  cursor: pointer;
  transition: background 0.2s;
}
.result-card:hover { background: rgba(0, 188, 212, 0.2); }
.result-wing { font-size: 10px; color: #00bcd4; text-transform: uppercase; }
.result-text { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.selected-memory {
  margin-top: 8px;
  padding: 8px;
  background: rgba(0, 188, 212, 0.15);
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-primary);
}
.memory-content {
  margin: 4px 0;
  color: var(--text-secondary);
  word-break: break-word;
}
.pin-btn {
  background: #00bcd4;
  border: none;
  color: white;
  padding: 4px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
}
.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
