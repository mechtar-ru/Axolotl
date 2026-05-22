<template>
  <div class="node source-node" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }" style="position: relative">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete" title="Delete node">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon" v-html="typeIcon" />
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
          <span v-html="t.icon" />
        </button>
      </div>

      <!-- TEXT mode -->
      <template v-if="sourceType === 'text'">
        <textarea v-model="localSourceData" placeholder="Enter data or drop a file..." rows="4" />
      </template>

      <!-- MEMORY mode -->
      <template v-if="sourceType === 'memory'">
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
          <button class="pin-btn" @click="pinMemory">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 2L15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2z"/></svg> Use
          </button>
        </div>
      </template>

      <!-- FILE mode -->
      <template v-if="sourceType === 'file'">
        <div class="file-zone" @click="triggerFileInput">
          <span v-if="!fileName">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> Click or drop a file
          </span>
          <span v-else class="file-loaded">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg> {{ fileName }}
          </span>
        </div>
        <input ref="fileInput" type="file" class="hidden-input" @change="handleFileSelect" />
      </template>

      <!-- URL mode -->
      <template v-if="sourceType === 'url'">
        <div class="url-row">
          <input v-model="urlInput" placeholder="https://example.com/data" class="url-input" @keyup.enter="fetchUrl" />
          <button class="fetch-btn" @click="fetchUrl" :disabled="fetching">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
          </button>
        </div>
        <div v-if="fetching" class="search-status">Loading...</div>
        <div v-if="urlPreview" class="url-preview">
          <button class="result-toggle" @click="urlPreviewExpanded = !urlPreviewExpanded">
            <svg :class="['chevron', { expanded: urlPreviewExpanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg> Preview
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
              <button class="browse-btn" @click="browseProject" title="Browse">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>
              </button>
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
          <svg :class="['chevron', { expanded: resultExpanded }]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="6 9 12 15 18 9"/></svg> Result
        </button>
        <div v-if="resultExpanded" class="node-result">
          <div>{{ props.data.result }}</div>
        </div>
      </template>
      <div v-if="props.data.nodeTimeMs" class="node-time">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> {{ props.data.nodeTimeMs }}ms
      </div>
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
  { value: 'text', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M16 3h5v5"/><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><line x1="8" y1="10" x2="16" y2="10"/><line x1="8" y1="14" x2="14" y2="14"/></svg>', label: 'Text' },
  { value: 'memory', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 4a4 4 0 0 1 4 4c0 2-1.2 3.5-2 4.5V18h-4v-5.5C8.2 11.5 7 10 7 8a4 4 0 0 1 4-4z"/><path d="M9 20h6"/></svg>', label: 'Memory' },
  { value: 'file', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>', label: 'File' },
  { value: 'url', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>', label: 'URL' },
  { value: 'project', icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>', label: 'Project' },
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
  return t ? t.icon : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';
});

const isSelected = computed(() => props.selected === true);
const executionIcon = computed(() => '');

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
      urlPreview.value = 'Error: ' + (data.error || 'Unknown error');
    }
  } catch (e) {
    console.error('URL fetch failed:', e);
    urlPreview.value = 'Fetch error';
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
  flex: 1; background: var(--bg-primary); border: 1px solid #00bcd4;
  color: var(--text-primary); border-radius: 4px; padding: 6px 8px; font-size: 13px; outline: none;
}
.search-btn {
  background: #00bcd4; border: none; color: white; border-radius: 4px; width: 32px; cursor: pointer;
}
.filter-row { display: flex; gap: 4px; margin-top: 6px; }
.filter-input {
  flex: 1; background: var(--bg-primary); border: 1px solid var(--border);
  color: var(--text-primary); border-radius: 4px; padding: 4px 8px; font-size: 12px;
}
.search-status { font-size: 12px; color: #00bcd4; margin-top: 6px; }
.results-list { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.result-card {
  background: rgba(0, 188, 212, 0.1); border: 1px solid rgba(0, 188, 212, 0.3);
  border-radius: 6px; padding: 6px 8px; cursor: pointer; transition: background 0.2s;
}
.result-card:hover { background: rgba(0, 188, 212, 0.2); }
.result-wing { font-size: 10px; color: #00bcd4; text-transform: uppercase; }
.result-text { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.selected-memory {
  margin-top: 8px; padding: 8px; background: rgba(0, 188, 212, 0.15);
  border-radius: 6px; font-size: 12px; color: var(--text-primary);
}
.memory-content { margin: 4px 0; color: var(--text-secondary); word-break: break-word; }
.pin-btn {
  background: #00bcd4; border: none; color: white; padding: 4px 10px;
  border-radius: 4px; cursor: pointer; font-size: 11px;
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
  background: var(--accent); border: none; color: white; border-radius: 4px; width: 32px; cursor: pointer;
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
  background: var(--accent); border: none; color: white; border-radius: 4px; width: 32px; cursor: pointer;
}
.num-input {
  width: 50px; background: var(--bg-primary); border: 1px solid var(--border);
  color: var(--text-primary); border-radius: 4px; padding: 3px 6px; font-size: 12px; text-align: center;
}
.project-hint { font-size: 11px; color: var(--text-secondary); font-style: italic; }

.chevron { transition: transform 0.2s; vertical-align: middle; }
.chevron:not(.expanded) { transform: rotate(-90deg); }
@keyframes spin { to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }
</style>
