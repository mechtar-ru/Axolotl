<template>
  <div class="node source-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Удалить узел">✕</button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">{{ typeIcon }}</span>
      <span v-if="!editingName" class="node-name" @dblclick="startEditName">{{ props.data.name }}</span>
      <input v-else ref="nameInput" v-model="localName" class="node-name-input" @blur="finishEditName" @keyup.enter="finishEditName" />
      <span class="execution-icon">{{ executionIcon }}</span>
      <button class="node-expand" @click="expanded = !expanded">{{ expanded ? '▼' : '▶' }}</button>
    </div>

    <div v-if="expanded" class="node-content"
      @dragover.prevent="isDragging = true"
      @dragleave="isDragging = false"
      @drop.prevent="handleDrop"
      :class="{ 'drop-zone': isDragging }">

      <div class="type-selector">
        <button v-for="t in sourceTypes" :key="t.value"
          :class="['type-btn', { active: sourceType === t.value }]"
          @click="setSourceType(t.value)"
          :title="t.label">
          {{ t.icon }}
        </button>
      </div>

      <!-- TEXT mode -->
      <template v-if="sourceType === 'text'">
        <textarea v-model="localSourceData" placeholder="Введите данные или перетащите файл..." rows="4" />
      </template>

      <!-- MEMORY mode -->
      <template v-if="sourceType === 'memory'">
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
          <button class="pin-btn" @click="pinMemory">📌 Использовать</button>
        </div>
      </template>

      <!-- FILE mode -->
      <template v-if="sourceType === 'file'">
        <div class="file-zone" @click="triggerFileInput">
          <span v-if="!fileName">📁 Нажмите или перетащите файл</span>
          <span v-else class="file-loaded">📄 {{ fileName }}</span>
        </div>
        <input ref="fileInput" type="file" class="hidden-input" @change="handleFileSelect" />
      </template>

      <!-- URL mode -->
      <template v-if="sourceType === 'url'">
        <div class="url-row">
          <input v-model="urlInput" placeholder="https://example.com/data" class="url-input" @keyup.enter="fetchUrl" />
          <button class="fetch-btn" @click="fetchUrl" :disabled="fetching">🌐</button>
        </div>
        <div v-if="fetching" class="search-status">Загрузка...</div>
        <div v-if="urlPreview" class="url-preview">
          <button class="result-toggle" @click="urlPreviewExpanded = !urlPreviewExpanded">
            {{ urlPreviewExpanded ? '▼ Предпросмотр' : '▶ Предпросмотр' }}
          </button>
          <div v-if="urlPreviewExpanded" class="preview-content">{{ truncate(urlPreview, 500) }}</div>
        </div>
      </template>

      <!-- PROJECT mode -->
      <template v-if="sourceType === 'project'">
        <div class="project-config">
          <div class="config-row">
            <label class="config-label">Project path:</label>
            <div class="path-row">
              <input v-model="projectPath" placeholder="/path/to/project" class="path-input" />
              <button class="browse-btn" @click="browseProject" title="Browse">📂</button>
            </div>
          </div>
          <div class="config-row inline">
            <label class="config-sm">
              Depth: <input v-model.number="maxDepth" type="number" min="1" max="10" class="num-input" />
            </label>
            <label class="config-sm">
              Max files: <input v-model.number="maxFiles" type="number" min="1" max="200" class="num-input" />
            </label>
          </div>
          <div v-if="projectPath && !localSourceData" class="project-hint">
            Path saved. Backend reads files at execution time.
          </div>
        </div>
      </template>

      <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
        <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
        <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
      </div>
      <template v-if="props.data.result">
        <button class="result-toggle" @click="resultExpanded = !resultExpanded">
          {{ resultExpanded ? '▼ Результат' : '▶ Результат' }}
        </button>
        <div v-if="resultExpanded" class="node-result">
          <div>{{ props.data.result }}</div>
        </div>
      </template>
      <div v-if="props.data.nodeTimeMs" class="node-time">⏱ {{ props.data.nodeTimeMs }}мс</div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { api } from '../../services/api';

interface MemoryResult {
  wing: string;
  room: string;
  content: string;
  score?: number;
}

const sourceTypes = [
  { value: 'text', icon: '📝', label: 'Текст' },
  { value: 'memory', icon: '🧠', label: 'Память' },
  { value: 'file', icon: '📁', label: 'Файл' },
  { value: 'url', icon: '🌐', label: 'URL' },
  { value: 'project', icon: '📂', label: 'Проект' },
];

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    sourceData?: string;
    progress?: number;
    result?: string;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
    onDelete?: () => void;
    nodeTimeMs?: number;
    config?: Record<string, any>;
  };
}>();

const emit = defineEmits<{ (e: 'delete'): void }>();

const editingName = ref(false);
const localName = ref(props.data.name);
const localSourceData = ref(props.data.sourceData || '');
const nameInput = ref<HTMLInputElement | null>(null);
const expanded = ref(true);
const resultExpanded = ref(true);
const isDragging = ref(false);

const sourceType = ref<string>(
  (props.data.config?.sourceType as string) || 'text'
);

// Memory search state
const searchQuery = ref('');
const filterWing = ref('');
const filterRoom = ref('');
const searching = ref(false);
const results = ref<MemoryResult[]>([]);
const selectedMemory = ref<MemoryResult | null>(null);

// File state
const fileName = ref('');
const fileInput = ref<HTMLInputElement | null>(null);

// URL state
const urlInput = ref((props.data.config?.url as string) || '');
const fetching = ref(false);
const urlPreview = ref('');
const urlPreviewExpanded = ref(false);

// Project state
const projectPath = ref((props.data.config?.projectPath as string) || '');
const maxDepth = ref((props.data.config?.maxDepth as number) || 4);
const maxFiles = ref((props.data.config?.maxFiles as number) || 50);

const typeIcon = computed(() => {
  const t = sourceTypes.find(s => s.value === sourceType.value);
  return t ? t.icon : '📥';
});

const isSelected = computed(() => props.selected === true);
const executionIcon = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '⏳';
    case 'completed': return '✅';
    case 'failed': return '❌';
    default: return '';
  }
});

function setSourceType(type: string) {
  sourceType.value = type;
  if (props.data.onUpdate) {
    props.data.onUpdate({ config: { ...(props.data.config || {}), sourceType: type } });
  }
}

watch(localSourceData, (newVal) => {
  if (props.data.onUpdate) props.data.onUpdate({ sourceData: newVal });
});

watch([projectPath, maxDepth, maxFiles], () => {
  if (props.data.onUpdate) {
    props.data.onUpdate({
      config: {
        ...(props.data.config || {}),
        sourceType: 'project',
        projectPath: projectPath.value,
        maxDepth: maxDepth.value,
        maxFiles: maxFiles.value,
      }
    });
  }
});

async function browseProject() {
  if (window.electronAPI?.showOpenDialog) {
    const result = await window.electronAPI.showOpenDialog({
      title: 'Select project directory',
      properties: ['openDirectory'],
    });
    if (!result.canceled && result.filePaths.length > 0) {
      projectPath.value = result.filePaths[0] ?? '';
    }
  }
}

// Memory search
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
      wing: r.wing || '', room: r.room || '', content: r.content || '', score: r.score,
    }));
  } catch (e) {
    console.error('Memory search failed:', e);
  } finally {
    searching.value = false;
  }
}

function selectResult(r: MemoryResult) { selectedMemory.value = r; }

function pinMemory() {
  if (!selectedMemory.value) return;
  localSourceData.value = selectedMemory.value.content;
  if (props.data.onUpdate) {
    props.data.onUpdate({ sourceData: selectedMemory.value.content });
  }
}

// File handling
function triggerFileInput() { fileInput.value?.click(); }

function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  fileName.value = file.name;
  const reader = new FileReader();
  reader.onload = (e) => {
    const text = e.target?.result as string;
    localSourceData.value = text;
  };
  reader.readAsText(file as Blob);
}

function handleDrop(event: DragEvent) {
  isDragging.value = false;
  const files = event.dataTransfer?.files;
  if (!files || files.length === 0) return;
  const file = files[0];
  if (!file) return;
  fileName.value = file.name;
  sourceType.value = 'file';
  const reader = new FileReader();
  reader.onload = (e) => {
    localSourceData.value = e.target?.result as string;
  };
  reader.readAsText(file as Blob);
}

// URL fetch
async function fetchUrl() {
  if (!urlInput.value.trim()) return;
  fetching.value = true;
  urlPreview.value = '';
  try {
    const { data } = await api.post('/fetch-url', { url: urlInput.value });
    if (data.status === 'ok') {
      localSourceData.value = data.content;
      urlPreview.value = data.content;
    } else {
      urlPreview.value = 'Ошибка: ' + (data.error || 'Unknown error');
    }
  } catch (e) {
    console.error('URL fetch failed:', e);
    urlPreview.value = 'Ошибка загрузки';
  } finally {
    fetching.value = false;
  }
}

function truncate(str: string, len: number): string {
  return str.length > len ? str.substring(0, len) + '...' : str;
}

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
  else emit('delete');
}
</script>

<style scoped>
@import './node-base.css';

.source-node { border-color: var(--node-source); }
.source-node.selected { border-color: var(--accent); box-shadow: var(--shadow-glow-accent); }

.type-selector {
  display: flex;
  gap: 2px;
  margin-bottom: 8px;
  background: var(--bg-primary);
  border-radius: 6px;
  padding: 2px;
  border: 1px solid var(--border);
}
.type-btn {
  flex: 1;
  background: transparent;
  border: none;
  padding: 4px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.2s;
}
.type-btn:hover { background: var(--bg-secondary); }
.type-btn.active { background: var(--accent); }

/* Memory search styles */
.search-row { display: flex; gap: 4px; }
.search-input {
  flex: 1; background: var(--bg-primary); border: 1px solid var(--memory-color);
  color: var(--text-primary); border-radius: var(--radius-sm); padding: 6px 8px; font-size: var(--text-base); outline: none;
}
.search-btn {
  background: var(--memory-color); border: none; color: var(--text-inverse); border-radius: var(--radius-sm); width: 32px; cursor: pointer;
}
.filter-row { display: flex; gap: var(--space-1); margin-top: var(--space-1-5); }
.filter-input {
  flex: 1; background: var(--bg-primary); border: 1px solid var(--border);
  color: var(--text-primary); border-radius: var(--radius-sm); padding: 4px 8px; font-size: var(--text-sm);
}
.search-status { font-size: var(--text-sm); color: var(--memory-color); margin-top: var(--space-1-5); }
.results-list { margin-top: var(--space-2); display: flex; flex-direction: column; gap: var(--space-1); }
.result-card {
  background: var(--memory-light); border: 1px solid rgba(0, 188, 212, 0.3);
  border-radius: var(--radius-sm); padding: 6px 8px; cursor: pointer; transition: background var(--transition);
}
.result-card:hover { background: rgba(0, 188, 212, 0.2); }
.result-wing { font-size: 10px; color: var(--memory-color); text-transform: uppercase; }
.result-text { font-size: var(--text-sm); color: var(--text-secondary); margin-top: 2px; }
.selected-memory {
  margin-top: var(--space-2); padding: var(--space-2); background: var(--memory-light);
  border-radius: var(--radius-sm); font-size: var(--text-sm); color: var(--text-primary);
}
.memory-content { margin: var(--space-1) 0; color: var(--text-secondary); word-break: break-word; }
.pin-btn {
  background: var(--memory-color); border: none; color: var(--text-inverse); padding: 4px 10px;
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-xs);
}

/* File styles */
.file-zone {
  border: 2px dashed var(--border); border-radius: 8px; padding: 16px;
  text-align: center; cursor: pointer; color: var(--text-secondary); font-size: 13px;
  transition: border-color 0.2s;
}
.file-zone:hover { border-color: var(--accent); }
.file-loaded { color: var(--success); }
.hidden-input { display: none; }

/* URL styles */
.url-row { display: flex; gap: 4px; }
.url-input {
  flex: 1; background: var(--bg-primary); border: 1px solid var(--border);
  color: var(--text-primary); border-radius: 4px; padding: 6px 8px; font-size: 13px; outline: none;
}
.fetch-btn {
  background: var(--accent); border: none; color: var(--text-inverse); border-radius: var(--radius-sm); width: 32px; cursor: pointer;
}
.url-preview { margin-top: 6px; }
.preview-content {
  font-size: 11px; color: var(--text-secondary); background: var(--bg-primary);
  border-radius: 4px; padding: 6px; max-height: 100px; overflow-y: auto;
  word-break: break-all;
}

.drop-zone { border: 2px dashed var(--accent); border-radius: 8px; }

/* Project mode styles */
.project-config { display: flex; flex-direction: column; gap: 6px; }
.config-row { display: flex; flex-direction: column; gap: 3px; }
.config-row.inline { flex-direction: row; gap: 10px; }
.config-label { font-size: 11px; color: var(--text-secondary); }
.config-sm { font-size: 11px; color: var(--text-secondary); display: flex; align-items: center; gap: 4px; }
.path-row { display: flex; gap: 4px; }
.path-input {
  flex: 1; background: var(--bg-primary); border: 1px solid var(--border);
  color: var(--text-primary); border-radius: 4px; padding: 6px 8px; font-size: 13px; outline: none;
  font-family: monospace;
}
.browse-btn {
  background: var(--accent); border: none; color: var(--text-inverse); border-radius: var(--radius-sm); width: 32px; cursor: pointer;
}
.num-input {
  width: 50px; background: var(--bg-primary); border: 1px solid var(--border);
  color: var(--text-primary); border-radius: 4px; padding: 3px 6px; font-size: 12px; text-align: center;
}
.project-hint { font-size: 11px; color: var(--text-secondary); font-style: italic; }
</style>
