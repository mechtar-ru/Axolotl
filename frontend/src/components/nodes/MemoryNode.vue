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
    const params = new URLSearchParams({ query: searchQuery.value, limit: '5' });
    if (filterWing.value) params.set('wing', filterWing.value);
    if (filterRoom.value) params.set('room', filterRoom.value);
    const response = await fetch(`/api/memory/search?${params}`);
    if (response.ok) {
      const data = await response.json();
      results.value = data.map((r: any) => ({
        wing: r.wing || '',
        room: r.room || '',
        content: r.content || '',
        score: r.score,
      }));
      // Emit results for floating cards on canvas
      if (props.data.onMemoryResults) {
        props.data.onMemoryResults(props.id, results.value);
      }
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
.memory-node {
  border-color: #00bcd4;
}
.memory-node.selected {
  border-color: #ff6b6b;
  box-shadow: 0 0 0 2px rgba(255,107,107,0.3);
}
.node {
  background: #2d2d44;
  border-radius: 8px;
  border: 2px solid #4a4a6a;
  min-width: 220px;
  max-width: 320px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.3);
  position: relative;
}
.delete-btn {
  position: absolute;
  top: -10px;
  right: -10px;
  width: 24px;
  height: 24px;
  background: #dc3545;
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}
.node-header {
  padding: 10px;
  background: #1e1e2e;
  border-radius: 6px 6px 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.node-icon { font-size: 20px; }
.node-name {
  flex: 1;
  font-weight: bold;
  color: #eee;
  cursor: pointer;
}
.node-name-input {
  flex: 1;
  background: #1a1a2e;
  border: 1px solid #00bcd4;
  color: #eee;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 14px;
  font-weight: bold;
}
.execution-icon { font-size: 14px; margin-left: 4px; }
.node-expand {
  background: none;
  border: none;
  color: #eee;
  cursor: pointer;
  font-size: 12px;
}
.node-content { padding: 10px; }
.search-row { display: flex; gap: 4px; }
.search-input {
  flex: 1;
  background: #1a1a2e;
  border: 1px solid #00bcd4;
  color: #eee;
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
  background: #1a1a2e;
  border: 1px solid #4a4a6a;
  color: #eee;
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
.result-text { font-size: 12px; color: #ccc; margin-top: 2px; }
.selected-memory {
  margin-top: 8px;
  padding: 8px;
  background: rgba(0, 188, 212, 0.15);
  border-radius: 6px;
  font-size: 12px;
  color: #eee;
}
.memory-content {
  margin: 4px 0;
  color: #ccc;
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
.node-result {
  margin-top: 10px;
  padding: 8px;
  background: #1a1a2e;
  border-radius: 4px;
  font-size: 12px;
  word-break: break-word;
}
.node-time {
  margin-top: 6px;
  font-size: 11px;
  color: #888;
  text-align: right;
}
.node-running { animation: pulse-running 1.5s ease-in-out infinite; }
.node-completed { box-shadow: 0 0 12px rgba(76, 175, 80, 0.5); }
.node-failed { box-shadow: 0 0 12px rgba(255, 0, 0, 0.5); }
@keyframes pulse-running {
  0%, 100% { box-shadow: 0 0 4px rgba(255, 165, 0, 0.3); }
  50% { box-shadow: 0 0 16px rgba(255, 165, 0, 0.7); }
}
</style>
