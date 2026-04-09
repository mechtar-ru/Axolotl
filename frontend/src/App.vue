<template>
  <div class="app">
    <div class="sidebar">
      <h2>📋 Схемы</h2>
      <ul>
        <li
          v-for="schema in schemaStore.schemas"
          :key="schema.id"
          :class="{ active: schemaStore.currentSchema?.id === schema.id }"
          @click="schemaStore.updateCurrentSchema(schema)"
        >
          {{ schema.name }}
        </li>
      </ul>
      <button @click="createNewSchema">+ Новая схема</button>
      <button @click="showImport = true" class="import-btn">📥 Импорт</button>
    </div>
    
    <div class="canvas-container">
      <div v-if="!schemaStore.currentSchema" class="placeholder">
        Выберите или создайте схему
      </div>
      <WorkflowCanvas
        v-else
        :schema="schemaStore.currentSchema"
        @update="handleSchemaUpdate"
        @delete="handleDeleteSchema"
      />
    </div>
    
    <div class="toolbar">
      <button @click="handleSave">💾 Сохранить</button>
      <button @click="handleExecute">▶ Выполнить</button>
      <button @click="handleExportMermaid">📊 Экспорт Mermaid</button>
    </div>
    
    <!-- Модальное окно экспорта -->
    <div v-if="showMermaid" class="modal">
      <div class="modal-content">
        <h3>📊 Mermaid диаграмма</h3>
        <pre>{{ mermaidCode }}</pre>
        <div class="modal-buttons">
          <button @click="copyToClipboard">📋 Скопировать</button>
          <button @click="saveToFile">💾 Сохранить</button>
          <button @click="showMermaid = false">❌ Закрыть</button>
        </div>
      </div>
    </div>
    
    <!-- Модальное окно импорта -->
    <div v-if="showImport" class="modal">
      <div class="modal-content">
        <h3>📥 Импорт Mermaid схемы</h3>
        <textarea v-model="importText" placeholder="Вставьте Mermaid код..." rows="10"></textarea>
        <div class="modal-buttons">
          <button @click="importFromMermaid">📥 Импортировать</button>
          <button @click="showImport = false">❌ Отмена</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useSchemaStore } from './stores/schemaStore';
import WorkflowCanvas from './components/canvas/WorkflowCanvas.vue';
import type { WorkflowSchema } from './types';
import { schemaApi } from './services/api';

const schemaStore = useSchemaStore();
const showMermaid = ref(false);
const showImport = ref(false);
const mermaidCode = ref('');
const importText = ref('');

onMounted(() => {
  schemaStore.loadSchemas();
});

async function createNewSchema() {
  await schemaStore.createSchema('Новая схема');
}

async function handleSave() {
  if (schemaStore.currentSchema) {
    await schemaStore.updateSchema(schemaStore.currentSchema);
    alert('Схема сохранена!');
  }
}

async function handleExecute() {
  await schemaStore.executeCurrentSchema();
  alert('Выполнение запущено!');
}

async function handleExportMermaid() {
  if (schemaStore.currentSchema) {
    mermaidCode.value = await schemaApi.exportToMermaid(schemaStore.currentSchema.id);
    showMermaid.value = true;
  }
}

function handleSchemaUpdate(updatedSchema: WorkflowSchema) {
  schemaStore.updateCurrentSchema(updatedSchema);
}

async function handleDeleteSchema(id: string) {
  await schemaStore.deleteSchema(id);
}

async function copyToClipboard() {
  await navigator.clipboard.writeText(mermaidCode.value);
  alert('Скопировано!');
}

function saveToFile() {
  const blob = new Blob([mermaidCode.value], { type: 'text/markdown' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${schemaStore.currentSchema?.name || 'schema'}-mermaid.md`;
  a.click();
  URL.revokeObjectURL(url);
}

async function importFromMermaid() {
  const text = importText.value;
  if (!text.trim()) {
    alert('Введите Mermaid код для импорта');
    return;
  }
  
  const nodes: any[] = [];
  const edges: any[] = [];
  const nodeMap = new Map<string, string>();
  
  const lines = text.split('\n');
  
  // Первый проход: ищем определения узлов
  for (const line of lines) {
    let match = line.match(/([\w\-]+)\s*([\[/\\]+)\s*"([^"]+)"\s*([\]/\\]+)/);
    if (!match) {
      match = line.match(/([\w\-]+)([\[/\\])\s*"([^"]+)"\s*([\]/\\])/);
    }
    if (!match) {
      match = line.match(/([\w\-]+)([\[/\\])([^\]]+)([\]/\\])/);
    }
    
    if (match) {
      const id = match[1];
      const leftDelim = match[2];
      const label = (match[3] || '').replace(/"/g, '').trim();
      const rightDelim = match[4] || '';
      
      let type: 'source' | 'agent' | 'output' = 'agent';
      if (leftDelim === '[/' || (leftDelim === '[' && rightDelim === '/]')) {
        type = 'source';
      } else if (leftDelim === '[\\' || (leftDelim === '[' && rightDelim === '\\]')) {
        type = 'output';
      } else {
        type = 'agent';
      }
      
      if (!nodes.find(n => n.id === id)) {
        nodes.push({
          id,
          type,
          name: label,
          position: { x: 100 + nodes.length * 200, y: 100 + nodes.length * 100 },
          data: type === 'agent' ? { userPrompt: '' } : type === 'source' ? { sourceData: '' } : {},
          status: 'idle',
        });
        nodeMap.set(id, type);
      }
    }
  }
  
  // Второй проход: ищем связи
  for (const line of lines) {
    const edgeMatch = line.match(/([\w\-]+)\s*-->\s*([\w\-]+)/);
    if (edgeMatch) {
      const source = edgeMatch[1];
      const target = edgeMatch[2];
      
      if (nodeMap.has(source) && nodeMap.has(target)) {
        edges.push({
          id: `edge-${Date.now()}-${edges.length}`,
          source,
          target,
          type: 'data',
        });
      }
    }
  }
  
  if (nodes.length === 0) {
    alert('Не удалось распознать узлы в Mermaid коде. Убедитесь, что код содержит определения узлов в формате: id["label"]');
    return;
  }
  
  const newSchema: WorkflowSchema = {
    id: `imported-${Date.now()}`,
    name: `Импортированная схема (${new Date().toLocaleTimeString()})`,
    description: `Импортировано из Mermaid. Узлов: ${nodes.length}, Связей: ${edges.length}`,
    version: '1.0',
    nodes,
    edges,
    createdAt: new Date().toISOString(),
  };
  
  const created = await schemaApi.createSchema(newSchema);
  await schemaStore.loadSchemas();
  schemaStore.updateCurrentSchema(created);
  
  alert(`Импортировано! Узлов: ${nodes.length}, Связей: ${edges.length}`);
  showImport.value = false;
  importText.value = '';
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background: #1a1a2e;
  color: #eee;
}

.app {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 250px;
  background: #2d2d44;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 15px;
  overflow-y: auto;
}

.sidebar h2 {
  font-size: 18px;
  margin-bottom: 10px;
}

.sidebar ul {
  list-style: none;
}

.sidebar li {
  padding: 10px;
  margin: 5px 0;
  background: #1a1a2e;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.sidebar li:hover {
  background: #3d3d5c;
}

.sidebar li.active {
  background: #6c63ff;
}

.sidebar button {
  padding: 10px;
  background: #6c63ff;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
}

.import-btn {
  background: #4caf50 !important;
}

.canvas-container {
  flex: 1;
  position: relative;
  background: #1a1a2e;
}

.placeholder {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  color: #888;
  font-size: 18px;
}

.toolbar {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 10px;
  background: #2d2d44;
  padding: 10px 20px;
  border-radius: 12px;
  z-index: 1000;
}

.toolbar button {
  padding: 8px 16px;
  background: #6c63ff;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
}

.modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 2000;
}

.modal-content {
  background: #2d2d44;
  padding: 20px;
  border-radius: 12px;
  max-width: 80%;
  max-height: 80%;
  overflow: auto;
}

.modal-content pre {
  background: #1a1a2e;
  padding: 15px;
  border-radius: 8px;
  overflow: auto;
  margin: 15px 0;
}

.modal-buttons {
  display: flex;
  gap: 10px;
  justify-content: center;
}

.modal-buttons button {
  padding: 8px 16px;
  background: #6c63ff;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
}

.vue-flow__node {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
}
</style>
