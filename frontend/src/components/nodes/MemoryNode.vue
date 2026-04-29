<template>
  <div class="node memory-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Удалить">✕</button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">🧠</span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" @blur="finishEditName" @keyup.enter="finishEditName" />
      <span class="execution-icon">{{ executionIcon }}</span>
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>

    <div v-if="expanded" class="node-content">
      <div class="search-row">
        <input v-model="searchQuery" placeholder="Поиск в памяти..." class="search-input" @keyup.enter="searchMemory" />
        <button class="search-btn" @click="searchMemory" :disabled="searching">🔍</button>
      </div>

      <div class="filter-row">
        <input v-model="filterWing" placeholder="Wing" class="filter-input" />
        <input v-model="filterRoom" placeholder="Room" class="filter-input" />
      </div>

      <div v-if="searching" class="search-status">Поиск...</div>

      <div v-if="results.length > 0" class="results-list">
        <div v-for="(r, i) in results" :key="i" class="result-card" @click="selectResult(r)">
          <div class="result-wing">{{ r.wing }}/{{ r.room }}</div>
          <div class="result-text">{{ truncate(r.content, 120) }}</div>
        </div>
      </div>

      <div v-if="selectedMemory" class="selected-memory">
        <strong>Выбрано:</strong>
        <div class="memory-content">{{ selectedMemory.content }}</div>
        <button class="pin-btn" @click="pinToOutput">📌 Вывести в результат</button>
      </div>

      <div v-if="props.data.result" class="node-result">
        <strong>Результат:</strong>
        <div>{{ props.data.result }}</div>
      </div>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}мс</div>
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
const executionIcon = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '⏳';
    case 'completed': return '✅';
    case 'failed': return '❌';
    default: return '';
  }
});

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
  border-color: var(--memory-color);
}
.memory-node.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}
.search-row { display: flex; gap: var(--space-1); }
.search-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--memory-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  font-size: var(--text-base);
  outline: none;
}
.search-btn {
  background: var(--memory-color);
  border: none;
  color: var(--text-inverse);
  border-radius: var(--radius-sm);
  width: 32px;
  cursor: pointer;
}
.filter-row { display: flex; gap: var(--space-1); margin-top: var(--space-1-5); }
.filter-input {
  flex: 1;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: 4px 8px;
  font-size: var(--text-sm);
}
.search-status { font-size: var(--text-sm); color: var(--memory-color); margin-top: var(--space-1-5); }
.results-list { margin-top: var(--space-2); display: flex; flex-direction: column; gap: var(--space-1); }
.result-card {
  background: var(--memory-light);
  border: 1px solid rgba(0, 188, 212, 0.3);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  cursor: pointer;
  transition: background var(--transition);
}
.result-card:hover { background: rgba(0, 188, 212, 0.2); }
.result-wing { font-size: 10px; color: var(--memory-color); text-transform: uppercase; }
.result-text { font-size: var(--text-sm); color: var(--text-secondary); margin-top: 2px; }
.selected-memory {
  margin-top: var(--space-2);
  padding: var(--space-2);
  background: var(--memory-light);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--text-primary);
}
.memory-content {
  margin: var(--space-1) 0;
  color: var(--text-secondary);
  word-break: break-word;
}
.pin-btn {
  background: var(--memory-color);
  border: none;
  color: var(--text-inverse);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-xs);
}
</style>
