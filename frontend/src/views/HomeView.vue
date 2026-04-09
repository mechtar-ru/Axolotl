<template>
  <div class="app">
    <div class="sidebar">
      <h2>📋 Схемы</h2>
      <ul>
        <li
          v-for="schema in schemaStore.schemas"
          :key="schema.id"
          :class="{ active: schemaStore.currentSchema?.id === schema.id }"
          @click="selectSchema(schema)"
        >
          {{ schema.name }}
        </li>
      </ul>
      <button @click="createNewSchema">+ Новая схема</button>
      <button @click="showImport = true" class="import-btn">📥 Импорт</button>
    </div>

    <div class="main-content">
      <div class="canvas-container">
        <div v-if="!schemaStore.currentSchema" class="placeholder">
          Выберите или создайте схему
        </div>
        <WorkflowCanvas
          v-else
          :schema="schemaStore.currentSchema"
          @update="handleSchemaUpdate"
          @delete="handleDeleteSchema"
          @save="handleSave"
          @export="handleExportMermaid"
        />
      </div>
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
import { ref, onMounted, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useSchemaStore } from '../stores/schemaStore';
import WorkflowCanvas from '../components/canvas/WorkflowCanvas.vue';
import type { WorkflowSchema } from '../types';
import { schemaApi } from '../services/api';

const router = useRouter();
const route = useRoute();
const schemaStore = useSchemaStore();
const showMermaid = ref(false);
const showImport = ref(false);
const mermaidCode = ref('');
const importText = ref('');

onMounted(() => {
  // schemaStore.loadSchemas(); // Уже вызывается в App.vue
});

watch(() => route.params.id, (newId) => {
  if (newId && typeof newId === 'string') {
    const schema = schemaStore.schemas.find(s => s.id === newId);
    if (schema) {
      schemaStore.updateCurrentSchema(schema);
    }
  }
}, { immediate: true });

function selectSchema(schema: WorkflowSchema) {
  router.push({ name: 'schema', params: { id: schema.id } });
}

async function createNewSchema() {
  const created = await schemaStore.createSchema('Новая схема');
  router.push({ name: 'schema', params: { id: created.id } });
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
  router.push({ name: 'home' });
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
      const id = match[1]!;
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
      const source = edgeMatch[1]!;
      const target = edgeMatch[2]!;

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

  const created = await schemaApi.createSchema(newSchema) as WorkflowSchema;
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
}

.sidebar {
  width: 250px;
  background: #16213e;
  padding: 20px;
  border-right: 1px solid #0f3460;
  overflow-y: auto;
}

.sidebar h2 {
  margin-bottom: 20px;
  color: #e94560;
}

.sidebar ul {
  list-style: none;
  padding: 0;
}

.sidebar li {
  padding: 10px;
  margin-bottom: 5px;
  background: #0f3460;
  border-radius: 5px;
  cursor: pointer;
  transition: background 0.3s;
}

.sidebar li:hover {
  background: #e94560;
}

.sidebar li.active {
  background: #e94560;
}

.sidebar button {
  width: 100%;
  padding: 10px;
  margin-bottom: 10px;
  background: #e94560;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  transition: background 0.3s;
}

.sidebar button:hover {
  background: #d43d51;
}

.import-btn {
  background: #0f3460;
}

.import-btn:hover {
  background: #16213e;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #1a1a2e;
}

.canvas-container {
  flex: 1;
  background: #1a1a2e;
  position: relative;
  overflow: hidden;
}

.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #888;
  font-size: 18px;
}

.modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.modal-content {
  background: #16213e;
  padding: 20px;
  border-radius: 10px;
  max-width: 600px;
  width: 90%;
  max-height: 80vh;
  overflow-y: auto;
}

.modal-content h3 {
  margin-bottom: 15px;
  color: #e94560;
}

.modal-content pre {
  background: #0f3460;
  padding: 10px;
  border-radius: 5px;
  overflow-x: auto;
  margin-bottom: 15px;
}

.modal-content textarea {
  width: 100%;
  padding: 10px;
  background: #0f3460;
  color: white;
  border: none;
  border-radius: 5px;
  resize: vertical;
  font-family: monospace;
}

.modal-buttons {
  display: flex;
  gap: 10px;
  margin-top: 15px;
}

.modal-buttons button {
  padding: 8px 12px;
  background: #e94560;
  color: white;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  transition: background 0.3s;
}

.modal-buttons button:hover {
  background: #d43d51;
}
</style>
