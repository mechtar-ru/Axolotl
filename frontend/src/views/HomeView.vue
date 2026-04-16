<template>
  <div class="app">
    <div class="sidebar" :style="{ width: sidebarWidth + 'px' }">
      <div class="sidebar-header">
        <img src="../assets/axolotl-symbol.svg" alt="Axolotl" class="sidebar-logo" />
        <span class="sidebar-brand">Axolotl</span>
      </div>
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
      <button @click="exportJson" class="export-json-btn">📦 JSON экспорт</button>
      <button @click="triggerJsonImport" class="import-json-btn">📂 JSON импорт</button>
      <input ref="jsonFileInput" type="file" accept=".json" style="display:none" @change="importJson" />
      <button @click="goToSettings" class="settings-btn">⚙️ Настройки</button>
      <button @click="showPlan = !showPlan" class="plan-btn">📋 План</button>
      <div class="user-info">
        <span class="user-name">👤 {{ authStore.username }}</span>
        <button @click="logout" class="logout-btn">Выйти</button>
      </div>

      <!-- Resize handle -->
      <div class="sidebar-resize-handle" @mousedown="startResize"></div>
    </div>

    <div class="main-content">
      <div class="canvas-container">
        <div v-if="!schemaStore.currentSchema" class="empty-state">
          <img src="../assets/logo.svg" alt="Axolotl" class="empty-state__logo" />
          <h1 class="empty-state__title">Визуальная оркестрация AI-агентов</h1>
          <p class="empty-state__subtitle">Создавайте схемы выполнения AI-агентов визуально</p>

          <!-- Templates section -->
          <div class="templates-section">
            <h3 class="templates-title">Начать с шаблона</h3>
            <div class="template-cards">
              <button class="template-card" @click="createFromTemplate('ai-pipeline')">
                <span class="template-card__icon">🤖</span>
                <span class="template-card__title">AI Pipeline</span>
                <span class="template-card__desc">Source → Agent → Output</span>
              </button>
              <button class="template-card" @click="createFromTemplate('rag')">
                <span class="template-card__icon">🧠</span>
                <span class="template-card__title">RAG</span>
                <span class="template-card__desc">Source → Memory → Agent → Output</span>
              </button>
              <button class="template-card" @click="createFromTemplate('condition')">
                <span class="template-card__icon">⚖️</span>
                <span class="template-card__title">Condition Branch</span>
                <span class="template-card__desc">Source → Condition → Agent A / Agent B</span>
              </button>
            </div>
          </div>

          <!-- Recent schemas -->
          <div v-if="recentSchemas.length > 0" class="recent-section">
            <h3 class="recent-title">Недавние схемы</h3>
            <div class="recent-cards">
              <button v-for="s in recentSchemas" :key="s.id" class="recent-card" @click="selectSchema(s)">
                <span class="recent-card__name">{{ s.name }}</span>
                <span class="recent-card__nodes">{{ (s.nodes || []).length }} узлов</span>
              </button>
            </div>
          </div>

          <div class="empty-state__actions">
            <button class="action-card" @click="createNewSchema">
              <span class="action-card__icon">✨</span>
              <span class="action-card__title">Новая схема</span>
              <span class="action-card__desc">Пустой холст для вашей идеи</span>
            </button>
            <button class="action-card" @click="createDemoSchema">
              <span class="action-card__icon">📖</span>
              <span class="action-card__title">Демо-схема</span>
              <span class="action-card__desc">Source → Agent → Output с Memory</span>
            </button>
            <button class="action-card" @click="showImport = true">
              <span class="action-card__icon">📥</span>
              <span class="action-card__title">Импорт</span>
              <span class="action-card__desc">JSON или Mermaid формат</span>
            </button>
          </div>
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
          <button @click="exportPython" class="btn-python">🐍 Python</button>
          <button @click="showMermaid = false">❌ Закрыть</button>
        </div>
      </div>
    </div>

    <!-- Модальное окно Python экспорта -->
    <div v-if="showPython" class="modal">
      <div class="modal-content modal-large">
        <h3>🐍 Python скрипт</h3>
        <pre class="python-code">{{ pythonCode }}</pre>
        <div class="modal-buttons">
          <button @click="copyPythonToClipboard">📋 Скопировать</button>
          <button @click="savePythonToFile">💾 Сохранить .py</button>
          <button @click="showPython = false">❌ Закрыть</button>
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

    <PlanPanel
      :visible="showPlan"
      :schema-nodes="(schemaStore.currentSchema?.nodes || []).map(n => ({ id: n.id, name: n.name, type: n.type }))"
      @close="showPlan = false"
      @highlight-node="highlightCanvasNode"
    />

    <CommandPalette v-model="showCommandPalette" @execute="handleCommand" />

    <OnboardingModal
      :visible="showOnboarding"
      @close="showOnboarding = false"
      @complete="handleOnboardingComplete"
      @skip="handleOnboardingSkip"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useSchemaStore } from '../stores/schemaStore';
import { useAuthStore } from '../stores/authStore';
import { useToast } from '../composables/useToast';
import WorkflowCanvas from '../components/canvas/WorkflowCanvas.vue';
import PlanPanel from '../components/plan/PlanPanel.vue';
import CommandPalette from '../components/ui/CommandPalette.vue';
import OnboardingModal from '../components/ui/OnboardingModal.vue';
import type { WorkflowSchema } from '../types';
import { schemaApi } from '../services/api';

const router = useRouter();
const route = useRoute();
const schemaStore = useSchemaStore();
const authStore = useAuthStore();
const toast = useToast();
const showMermaid = ref(false);
const showImport = ref(false);
const mermaidCode = ref('');
const showPython = ref(false);
const pythonCode = ref('');
const importText = ref('');
const showPlan = ref(false);
const showCommandPalette = ref(false);
const showSearch = ref(false);
const showOnboarding = ref(false);

// Resizable sidebar
const sidebarWidth = ref(250);
const MIN_SIDEBAR = 180;
const MAX_SIDEBAR = 400;
let isResizing = false;

function startResize() {
  isResizing = true;
  document.addEventListener('mousemove', onResize);
  document.addEventListener('mouseup', stopResize);
}

function onResize(e: MouseEvent) {
  if (!isResizing) return;
  sidebarWidth.value = Math.max(MIN_SIDEBAR, Math.min(MAX_SIDEBAR, e.clientX));
}

function stopResize() {
  isResizing = false;
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
}

// Recent schemas (last 5)
const recentSchemas = computed(() => {
  return schemaStore.schemas.slice(-5).reverse();
});

// Templates
async function createFromTemplate(template: string) {
  const templates: Record<string, { name: string; nodes: any[]; edges: any[] }> = {
    'ai-pipeline': {
      name: '🤖 AI Pipeline',
      nodes: [
        { id: 'source-1', type: 'source', name: 'Входные данные', position: { x: 100, y: 200 }, data: { sourceData: 'Введите текст для анализа' } },
        { id: 'agent-1', type: 'agent', name: 'AI Агент', position: { x: 400, y: 200 }, data: { userPrompt: 'Проанализируйте следующие данные:', provider: 'ollama' } },
        { id: 'output-1', type: 'output', name: 'Результат', position: { x: 700, y: 200 }, data: {} },
      ],
      edges: [
        { id: 'e1', source: 'source-1', target: 'agent-1', type: 'data' },
        { id: 'e2', source: 'agent-1', target: 'output-1', type: 'data' },
      ],
    },
    'rag': {
      name: '🧠 RAG Pipeline',
      nodes: [
        { id: 'source-1', type: 'source', name: 'Запрос', position: { x: 100, y: 200 }, data: { sourceData: 'Ваш вопрос' } },
        { id: 'memory-1', type: 'memory', name: 'Поиск в памяти', position: { x: 100, y: 400 }, data: { sourceData: 'Контекст по запросу' } },
        { id: 'agent-1', type: 'agent', name: 'AI Агент', position: { x: 450, y: 300 }, data: { userPrompt: 'Ответьте на вопрос, используя контекст:', provider: 'ollama' } },
        { id: 'output-1', type: 'output', name: 'Ответ', position: { x: 750, y: 300 }, data: {} },
      ],
      edges: [
        { id: 'e1', source: 'source-1', target: 'agent-1', type: 'data' },
        { id: 'e2', source: 'memory-1', target: 'agent-1', type: 'data' },
        { id: 'e3', source: 'agent-1', target: 'output-1', type: 'data' },
      ],
    },
    'condition': {
      name: '⚖️ Condition Branch',
      nodes: [
        { id: 'source-1', type: 'source', name: 'Вход', position: { x: 100, y: 250 }, data: { sourceData: 'Текст для классификации' } },
        { id: 'condition-1', type: 'condition', name: 'Классификатор', position: { x: 350, y: 250 }, data: { condition: 'input.length > 50' } },
        { id: 'agent-a', type: 'agent', name: 'Детальный анализ', position: { x: 650, y: 100 }, data: { userPrompt: 'Выполни детальный анализ:', provider: 'ollama' } },
        { id: 'agent-b', type: 'agent', name: 'Быстрый ответ', position: { x: 650, y: 400 }, data: { userPrompt: 'Дай краткий ответ:', provider: 'ollama' } },
        { id: 'output-1', type: 'output', name: 'Результат', position: { x: 950, y: 250 }, data: {} },
      ],
      edges: [
        { id: 'e1', source: 'source-1', target: 'condition-1', type: 'data' },
        { id: 'e2', source: 'condition-1', target: 'agent-a', type: 'data', label: 'true' },
        { id: 'e3', source: 'condition-1', target: 'agent-b', type: 'data', label: 'false' },
        { id: 'e4', source: 'agent-a', target: 'output-1', type: 'data' },
        { id: 'e5', source: 'agent-b', target: 'output-1', type: 'data' },
      ],
    },
  };

  const t = templates[template];
  if (!t) return;

  const created = await schemaStore.createSchema(t.name);
  await schemaApi.updateSchema(created.id, { ...created, nodes: t.nodes, edges: t.edges });
  await schemaStore.loadSchemas();
  router.push({ name: 'schema', params: { id: created.id } });
}

onMounted(() => {
  // schemaStore.loadSchemas(); // Уже вызывается в App.vue

  // Show onboarding if first time
  const onboardingStatus = localStorage.getItem('axolotl:onboarding');
  if (!onboardingStatus) {
    showOnboarding.value = true;
  }
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

async function createDemoSchema() {
  const created = await schemaStore.createSchema('🤖 Демо: AI Pipeline');
  
  // Add demo nodes and edges
  const nodes = [
    {
      id: 'source-1',
      type: 'source' as const,
      name: 'Входные данные',
      position: { x: 100, y: 200 },
      data: { prompt: 'Опиши задачу: написать документацию для API' }
    },
    {
      id: 'agent-1',
      type: 'agent' as const,
      name: 'AI Агент',
      position: { x: 400, y: 200 },
      data: { prompt: 'Создай подробную документацию для API', provider: 'openai', model: 'gpt-4' }
    },
    {
      id: 'memory-1',
      type: 'memory' as const,
      name: 'MemPalace',
      position: { x: 400, y: 400 },
      data: { query: 'AI documentation best practices', wing: 'ai-docs' }
    },
    {
      id: 'output-1',
      type: 'output' as const,
      name: 'Результат',
      position: { x: 700, y: 200 },
      data: {}
    }
  ];
  const edges = [
    { id: 'e1', source: 'source-1', target: 'agent-1', type: 'data' as const },
    { id: 'e2', source: 'memory-1', target: 'agent-1', type: 'data' as const },
    { id: 'e3', source: 'agent-1', target: 'output-1', type: 'data' as const }
  ];
  
  // Update the schema with nodes and edges
  await schemaApi.updateSchema(created.id, { ...created, nodes, edges });
  await schemaStore.loadSchemas();
  router.push({ name: 'schema', params: { id: created.id } });
}

function goToSettings() {
  router.push({ name: 'settings' });
}

async function handleSave() {
  if (schemaStore.currentSchema) {
    await schemaStore.updateSchema(schemaStore.currentSchema);
    toast.success('Схема сохранена');
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

async function exportPython() {
  if (!schemaStore.currentSchema) return;
  try {
    const res = await fetch(`/api/schemas/${schemaStore.currentSchema.id}/export/python`);
    const data = await res.json();
    pythonCode.value = data.python || '';
    showPython.value = true;
    showMermaid.value = false;
  } catch (e) {
    console.error('Python export failed:', e);
  }
}

async function copyPythonToClipboard() {
  await navigator.clipboard.writeText(pythonCode.value);
  alert('Скопировано!');
}

function savePythonToFile() {
  const blob = new Blob([pythonCode.value], { type: 'text/x-python' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${schemaStore.currentSchema?.name || 'schema'}.py`;
  a.click();
  URL.revokeObjectURL(url);
}

function handleSchemaUpdate(updatedSchema: WorkflowSchema) {
  schemaStore.updateCurrentSchema(updatedSchema);
}

async function handleDeleteSchema(id: string) {
  await schemaStore.deleteSchema(id);
  router.push({ name: 'home' });
}

function highlightCanvasNode(nodeId: string) {
  window.dispatchEvent(new CustomEvent('axolotl:highlight-node', { detail: { nodeId } }));
}

async function handleCommand(action: string) {
  switch (action) {
    case 'new-schema':
      await createNewSchema();
      break;
    case 'execute':
      await handleExecute();
      break;
    case 'save':
      await handleSave();
      break;
    case 'toggle-plan':
      showPlan.value = !showPlan.value;
      break;
    case 'settings':
      goToSettings();
      break;
    case 'export-mermaid':
      if (schemaStore.currentSchema) {
        mermaidCode.value = await schemaApi.exportToMermaid(schemaStore.currentSchema.id);
        showMermaid.value = true;
      }
      break;
    case 'export-json':
      exportJson();
      break;
    case 'import':
      showImport.value = true;
      break;
    case 'memory-search':
      showSearch.value = true;
      break;
    case 'zoom-fit':
      window.dispatchEvent(new CustomEvent('axolotl:zoom-fit'));
      break;
  }
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

  for (const line of lines) {
    let match = line.match(/([\w\-]+)\s*([\[/\\]+)\s*"([^"]+)"\s*([\]/\\]+)/);
    if (!match) match = line.match(/([\w\-]+)([\[/\\])\s*"([^"]+)"\s*([\]/\\])/);
    if (!match) match = line.match(/([\w\-]+)([\[/\\])([^\]]+)([\]/\\])/);

    if (match) {
      const id = match[1]!;
      const leftDelim = match[2];
      const label = (match[3] || '').replace(/"/g, '').trim();
      const rightDelim = match[4] || '';

      let type: 'source' | 'agent' | 'output' = 'agent';
      if (leftDelim === '[/' || (leftDelim === '[' && rightDelim === '/]')) type = 'source';
      else if (leftDelim === '[\\' || (leftDelim === '[' && rightDelim === '\\]')) type = 'output';

      if (!nodes.find(n => n.id === id)) {
        nodes.push({
          id, type, name: label,
          position: { x: 100 + nodes.length * 200, y: 100 + nodes.length * 100 },
          data: type === 'agent' ? { userPrompt: '' } : type === 'source' ? { sourceData: '' } : {},
          status: 'idle',
        });
        nodeMap.set(id, type);
      }
    }
  }

  for (const line of lines) {
    const edgeMatch = line.match(/([\w\-]+)\s*-->\s*([\w\-]+)/);
    if (edgeMatch) {
      const source = edgeMatch[1]!;
      const target = edgeMatch[2]!;
      if (nodeMap.has(source) && nodeMap.has(target)) {
        edges.push({ id: `edge-${Date.now()}-${edges.length}`, source, target, type: 'data' });
      }
    }
  }

  if (nodes.length === 0) { alert('Не удалось распознать узлы'); return; }

  const newSchema: WorkflowSchema = {
    id: `imported-${Date.now()}`,
    name: `Импортированная схема (${new Date().toLocaleTimeString()})`,
    description: `Импортировано из Mermaid. Узлов: ${nodes.length}, Связей: ${edges.length}`,
    version: '1.0', nodes, edges, createdAt: new Date().toISOString(),
  };

  const created = await schemaApi.createSchema(newSchema) as WorkflowSchema;
  await schemaStore.loadSchemas();
  schemaStore.updateCurrentSchema(created);
  showImport.value = false;
  importText.value = '';
}

// JSON export/import
function exportJson() {
  if (!schemaStore.currentSchema) return;
  const json = JSON.stringify(schemaStore.currentSchema, null, 2);
  const blob = new Blob([json], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${schemaStore.currentSchema.name || 'schema'}.json`;
  a.click();
  URL.revokeObjectURL(url);
}

const jsonFileInput = ref<HTMLInputElement | null>(null);
function triggerJsonImport() { jsonFileInput.value?.click(); }

async function importJson(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const text = await file.text();
    const schema = JSON.parse(text) as WorkflowSchema;
    delete (schema as any).id;
    delete (schema as any).createdAt;
    delete (schema as any).updatedAt;
    const created = await schemaStore.createSchema(schema.name || 'Импортированная');
    Object.assign(created, { ...schema, id: created.id, name: created.name });
    await schemaStore.updateSchema(created);
    router.push({ name: 'schema', params: { id: created.id } });
  } catch (e) {
    alert('Ошибка импорта JSON: ' + (e as Error).message);
  }
}

function logout() {
  authStore.logout();
  router.push('/login');
}

function handleOnboardingComplete(provider: string, model: string) {
  console.log(`Onboarding: selected ${provider} with model ${model}`);
  // Store default model preference
  localStorage.setItem('axolotl:default-model', `${provider}/${model}`);
}

function handleOnboardingSkip() {
  console.log('Onboarding skipped');
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
  background: #0b0f2a;
  padding: 20px;
  border-right: 1px solid rgba(123, 92, 255, 0.2);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(123, 92, 255, 0.2);
}

.sidebar-logo {
  width: 32px;
  height: 32px;
}

.sidebar-brand {
  font-size: 18px;
  font-weight: 600;
  background: linear-gradient(135deg, #7b5cff, #4facfe);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.sidebar h2 {
  margin-bottom: 20px;
  color: #7b5cff;
}

.sidebar ul {
  list-style: none;
  padding: 0;
}

.sidebar li {
  padding: 10px;
  margin-bottom: 5px;
  background: rgba(123, 92, 255, 0.1);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.sidebar li:hover {
  background: rgba(123, 92, 255, 0.2);
}

.sidebar li.active {
  background: linear-gradient(135deg, #7b5cff, #4facfe);
}

.sidebar button {
  width: 100%;
  padding: 10px;
  margin-bottom: 10px;
  background: linear-gradient(135deg, #7b5cff, #4facfe);
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  font-weight: 500;
}

.sidebar button:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

.import-btn {
  background: rgba(79, 172, 254, 0.2);
  color: #4facfe;
}

.import-btn:hover {
  background: rgba(79, 172, 254, 0.3);
}

.settings-btn {
  background: #2d2d44 !important;
  margin-top: auto;
}

.settings-btn:hover {
  background: #3d3d5c !important;
}

.export-json-btn {
  background: #0f3460 !important;
}
.export-json-btn:hover {
  background: #16213e !important;
}
.import-json-btn {
  background: #0f3460 !important;
}
.import-json-btn:hover {
  background: #16213e !important;
}
.user-info {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid rgba(255,255,255,0.1);
}
.user-name {
  flex: 1;
  font-size: 13px;
  color: #aaa;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.logout-btn {
  background: #dc3545 !important;
  padding: 6px 10px !important;
  font-size: 12px !important;
  width: auto !important;
  margin: 0 !important;
}
.logout-btn:hover {
  background: #c82333 !important;
}
.plan-btn {
  background: #4a4a6a !important;
}
.plan-btn:hover {
  background: #5a5a7a !important;
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

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 40px;
  text-align: center;
}

.empty-state__logo {
  width: 120px;
  height: 120px;
  margin-bottom: 24px;
  animation: pulse 3s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.empty-state__title {
  font-size: 28px;
  background: linear-gradient(135deg, #7b5cff, #4facfe);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin: 0 0 12px;
  font-weight: 700;
}

.empty-state__subtitle {
  font-size: 16px;
  color: #888;
  margin: 0 0 40px;
}

.empty-state__actions {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  justify-content: center;
}

.action-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 32px;
  background: rgba(30, 30, 46, 0.8);
  border: 1px solid rgba(108, 99, 255, 0.2);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 180px;
}

.action-card:hover {
  border-color: #7b5cff;
  background: rgba(123, 92, 255, 0.1);
  transform: translateY(-2px);
  box-shadow: 0 8px 30px rgba(123, 92, 255, 0.2);
}

.action-card__icon {
  font-size: 36px;
}

.action-card__title {
  font-size: 16px;
  font-weight: 600;
  color: #e0e0e0;
}

.action-card__desc {
  font-size: 12px;
  color: #888;
}

/* Templates section */
.templates-section {
  margin-bottom: 32px;
}
.templates-title {
  font-size: 18px;
  color: #ccc;
  margin: 0 0 16px;
  font-weight: 600;
}
.template-cards {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 24px;
}
.template-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 16px 24px;
  background: rgba(30, 30, 46, 0.6);
  border: 1px solid rgba(108, 99, 255, 0.15);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 140px;
}
.template-card:hover {
  border-color: #6c63ff;
  background: rgba(108, 99, 255, 0.1);
  transform: translateY(-2px);
}
.template-card__icon {
  font-size: 28px;
}
.template-card__title {
  font-size: 14px;
  font-weight: 600;
  color: #e0e0e0;
}
.template-card__desc {
  font-size: 11px;
  color: #777;
}

/* Recent schemas section */
.recent-section {
  margin-bottom: 32px;
}
.recent-title {
  font-size: 16px;
  color: #aaa;
  margin: 0 0 12px;
  font-weight: 600;
}
.recent-cards {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 24px;
}
.recent-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px 20px;
  background: rgba(30, 30, 46, 0.4);
  border: 1px solid rgba(74, 74, 106, 0.3);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  min-width: 120px;
}
.recent-card:hover {
  border-color: #6c63ff;
  background: rgba(108, 99, 255, 0.08);
}
.recent-card__name {
  font-size: 13px;
  color: #ddd;
  font-weight: 500;
}
.recent-card__nodes {
  font-size: 11px;
  color: #666;
}

/* Sidebar resize handle */
.sidebar-resize-handle {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  background: transparent;
  transition: background 0.2s;
}
.sidebar-resize-handle:hover {
  background: rgba(108, 99, 255, 0.3);
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

.btn-python {
  background: #4a4a6a !important;
}
.btn-python:hover {
  background: #5a5a7a !important;
}

.modal-large {
  max-width: 800px;
}

.python-code {
  font-family: 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 13px;
  white-space: pre;
  max-height: 500px;
  overflow: auto;
}
</style>
