<template>
  <div v-if="visible" class="plan-panel">
    <div class="plan-header">
      <span>📋 План</span>
      <button class="close-btn" @click="$emit('close')">✕</button>
    </div>

    <div v-if="loading" class="plan-loading">Загрузка...</div>

    <div v-else-if="error" class="plan-error">
      {{ error }}
      <button class="retry-btn" @click="loadPlan">🔄</button>
    </div>

    <div v-else>
      <div class="plan-add">
        <textarea
          v-model="newTask"
          placeholder="Задачи (каждая с новой строки)...&#10;Напр: Сделать тесты | HIGH&#10;      Обновить docs | LOW"
          class="plan-textarea"
          rows="3"
          @keydown.ctrl.enter="addBatchTasks"
        />
        <button class="add-btn" @click="addBatchTasks" :disabled="!parsedTaskCount">
          +{{ parsedTaskCount > 1 ? parsedTaskCount : '' }}
        </button>
      </div>

      <!-- Node link dropdown -->
      <div v-if="linkingTaskIndex !== null && schemaNodes && schemaNodes.length > 0" class="node-picker-dropdown">
        <div class="node-picker-header">
          <span>Связать задачу с узлом:</span>
          <button class="node-picker-close" @click="linkingTaskIndex = null">✕</button>
        </div>
        <div class="node-picker-list">
          <div
            v-for="node in schemaNodes"
            :key="node.id"
            class="node-picker-item"
            :class="{ selected: tasks[linkingTaskIndex]?.nodeId === node.id }"
            @click="linkNodeToTask(linkingTaskIndex, node.id)"
          >
            <span class="node-icon">{{ nodeIcon(node.type) }}</span>
            <span class="node-name">{{ node.name }}</span>
          </div>
        </div>
        <button class="unlink-btn" @click="unlinkNode(linkingTaskIndex)" v-if="tasks[linkingTaskIndex]?.nodeId">
          Убрать связь
        </button>
      </div>

      <!-- Acceptance criteria editor -->
      <div v-if="criteriaEditIndex !== null" class="criteria-editor">
        <div class="criteria-editor-header">
          <span>Критерии приёмки: {{ tasks[criteriaEditIndex]?.title }}</span>
          <button class="criteria-close" @click="criteriaEditIndex = null">✕</button>
        </div>
        <div class="criteria-list">
          <div
            v-for="(c, i) in tasks[criteriaEditIndex]?.acceptanceCriteria || []"
            :key="i"
            class="criteria-item"
            :class="{ met: tasks[criteriaEditIndex]?.acceptanceCriteriaMet?.[i] }"
          >
            <input
              type="checkbox"
              :checked="tasks[criteriaEditIndex]?.acceptanceCriteriaMet?.[i]"
              @change="toggleCriterionMet(criteriaEditIndex, i, ($event.target as HTMLInputElement).checked)"
            />
            <input
              v-if="tasks[criteriaEditIndex]"
              v-model="tasks[criteriaEditIndex]!.acceptanceCriteria![i]"
              class="criteria-input"
              placeholder="Критерий..."
              @change="saveCriteria(criteriaEditIndex)"
            />
            <button class="criteria-delete" @click="removeCriterion(criteriaEditIndex, i)">✕</button>
          </div>
        </div>
        <div class="criteria-add">
          <input
            v-model="newCriterion"
            class="criteria-new-input"
            placeholder="Новый критерий..."
            @keyup.enter="addCriterion(criteriaEditIndex)"
          />
          <button class="criteria-add-btn" @click="addCriterion(criteriaEditIndex)">+ Добавить</button>
        </div>
      </div>

      <div class="plan-list">
        <div
          v-for="(task, i) in tasks"
          :key="task.id"
          class="plan-task"
          :class="'status-' + task.status"
          draggable="true"
          @dragstart="dragTaskId = task.id"
          @dragover.prevent
          @drop="dropTask(i)"
        >
          <div class="task-left">
            <button class="task-status-btn" @click="cycleStatus(i)">
              {{ statusIcon(task.status) }}
            </button>
            <span class="task-text" :class="{ done: task.status === 'DONE' }">{{ task.title }}</span>
            <!-- Acceptance criteria indicator -->
            <span v-if="task.acceptanceCriteria && task.acceptanceCriteria.length > 0" class="criteria-indicator" :title="`${criteriaMetCount(i)}/${task.acceptanceCriteria.length} критериев выполнено`">
              <span class="criteria-bar">
                <span class="criteria-fill" :style="{ width: criteriaPercent(i) + '%' }"></span>
              </span>
              <span class="criteria-text">{{ criteriaMetCount(i) }}/{{ task.acceptanceCriteria.length }}</span>
            </span>
          </div>
          <div class="task-right">
            <span class="task-priority" @click="cyclePriority(i)" :title="task.priority">
              {{ priorityIcon(task.priority) }}
            </span>
            <span class="task-link" @click="toggleNodeLink(i)" :title="task.nodeId ? 'Перейти к узлу' : 'Связать с узлом'">
              {{ task.nodeId ? '🔗' : '🔗' }}
            </span>
            <button class="task-criteria-btn" @click="criteriaEditIndex = i" title="Критерии приёмки">📋</button>
            <button class="task-delete" @click="deleteTask(i)" title="Удалить">✕</button>
          </div>
        </div>
      </div>

      <div class="plan-footer">
        <button class="export-plan-btn" @click="exportPlan">📄 Экспорт Markdown</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted, computed } from 'vue';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

const visibleProp = defineProps<{ visible: boolean; schemaNodes?: Array<{ id: string; name: string; type: string }> }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'highlight-node', nodeId: string): void }>();

interface PlanTask {
  id: string;
  title: string;
  status: 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED';
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  nodeId?: string;
  dependencies?: string[];
  acceptanceCriteria?: string[];
  acceptanceCriteriaMet?: boolean[];
}

const tasks = ref<PlanTask[]>([]);
const newTask = ref('');
const loading = ref(false);
const error = ref('');
let dragTaskId = ref<string | null>(null);
const linkingTaskIndex = ref<number | null>(null);
const criteriaEditIndex = ref<number | null>(null);
const newCriterion = ref('');

// Batch task parsing
const parsedTasks = computed(() => {
  const lines = newTask.value.split('\n').map(l => l.trim()).filter(Boolean);
  return lines.map(line => {
    const parts = line.split('|').map(p => p.trim());
    const title = parts[0] || '';
    const priorityRaw = (parts[1] || '').toUpperCase();
    const priority: PlanTask['priority'] = ['HIGH', 'MEDIUM', 'LOW'].includes(priorityRaw)
      ? priorityRaw as PlanTask['priority']
      : 'MEDIUM';
    return { title, priority };
  }).filter(t => t.title);
});

const parsedTaskCount = computed(() => parsedTasks.value.length);

let ws: WebSocket | null = null;

async function loadPlan() {
  loading.value = true;
  error.value = '';
  try {
    const response = await fetch(`${API_BASE_URL}/plan`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const plan = await response.json();
    tasks.value = (plan.tasks || []).map((t: any) => ({
      id: t.id,
      title: t.title,
      status: t.status || 'TODO',
      priority: t.priority || 'MEDIUM',
      nodeId: t.nodeId,
      dependencies: t.dependencies || [],
      acceptanceCriteria: t.acceptanceCriteria || [],
      acceptanceCriteriaMet: t.acceptanceCriteriaMet || [],
    }));
  } catch (e: any) {
    error.value = 'Ошибка загрузки плана: ' + (e.message || e);
  } finally {
    loading.value = false;
  }
}

async function addBatchTasks() {
  const batch = parsedTasks.value;
  if (batch.length === 0) return;
  try {
    for (const task of batch) {
      const response = await fetch(`${API_BASE_URL}/plan/tasks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: task.title, priority: task.priority }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const created = await response.json();
      tasks.value.push({
        id: created.id,
        title: created.title,
        status: created.status || 'TODO',
        priority: created.priority || task.priority,
        nodeId: created.nodeId,
      });
    }
    newTask.value = '';
  } catch (e: any) {
    error.value = 'Ошибка добавления: ' + (e.message || e);
  }
}

async function updateTaskRemote(taskId: string, updates: { status?: string; priority?: string }) {
  try {
    let url: string;
    let body: Record<string, any>;
    if (updates.status) {
      url = `${API_BASE_URL}/plan/tasks/${taskId}/status`;
      body = { status: updates.status };
    } else if (updates.priority) {
      url = `${API_BASE_URL}/plan/tasks/${taskId}/priority`;
      body = { priority: updates.priority };
    } else {
      return;
    }
    const response = await fetch(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const err = await response.json().catch(() => ({ message: 'Unknown error' }));
      throw new Error(err.message || `HTTP ${response.status}`);
    }
  } catch (e: any) {
    error.value = 'Ошибка обновления: ' + (e.message || e);
  }
}

async function cycleStatus(i: number) {
  const task = tasks.value[i];
  if (!task) return;
  const order: PlanTask['status'][] = ['TODO', 'IN_PROGRESS', 'DONE', 'BLOCKED'];
  const current = order.indexOf(task.status);
  const nextStatus = order[(current + 1) % order.length] ?? 'TODO';

  // Check acceptance criteria before allowing DONE
  if (nextStatus === 'DONE' && task.acceptanceCriteria && task.acceptanceCriteria.length > 0) {
    const metCount = criteriaMetCount(i);
    if (metCount < task.acceptanceCriteria.length) {
      if (!confirm(`Не все критерии выполнены (${metCount}/${task.acceptanceCriteria.length}). Всё равно отметить как DONE?`)) {
        return;
      }
    }
  }

  // Optimistic update
  task.status = nextStatus;

  await updateTaskRemote(task.id, { status: nextStatus }).catch(() => {
    // Rollback on failure
    task.status = order[current] ?? 'TODO';
  });
}

async function cyclePriority(i: number) {
  const task = tasks.value[i];
  if (!task) return;
  const order: PlanTask['priority'][] = ['HIGH', 'MEDIUM', 'LOW'];
  const current = order.indexOf(task.priority);
  const nextPriority = order[(current + 1) % order.length] ?? 'MEDIUM';

  task.priority = nextPriority;

  await updateTaskRemote(task.id, { priority: nextPriority }).catch(() => {
    task.priority = order[current] ?? 'MEDIUM';
  });
}

async function deleteTask(i: number) {
  const task = tasks.value[i];
  if (!task) return;
  try {
    const response = await fetch(`${API_BASE_URL}/plan/tasks/${task.id}`, {
      method: 'DELETE',
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    tasks.value.splice(i, 1);
  } catch (e: any) {
    error.value = 'Ошибка удаления: ' + (e.message || e);
  }
}

function dropTask(targetIndex: number) {
  if (dragTaskId.value === null) return;
  const dragIdx = tasks.value.findIndex(t => t.id === dragTaskId.value);
  if (dragIdx < 0) { dragTaskId.value = null; return; }

  const item = tasks.value.splice(dragIdx, 1)[0];
  if (item) {
    tasks.value.splice(targetIndex, 0, item);
    // Reorder on server
    fetch(`${API_BASE_URL}/plan/tasks/${item.id}/move`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ position: { type: 'index', value: String(targetIndex) } }),
    }).catch(console.error);
  }
  dragTaskId.value = null;
}

function statusIcon(status: PlanTask['status']) {
  return { TODO: '⬜', IN_PROGRESS: '🔄', DONE: '✅', BLOCKED: '🚫' }[status];
}

function priorityIcon(priority: PlanTask['priority']) {
  return { HIGH: '🔴', MEDIUM: '🟡', LOW: '🟢' }[priority];
}

function nodeIcon(type: string) {
  const icons: Record<string, string> = {
    source: '📥', agent: '🤖', output: '📤', condition: '⚖️',
    loop: '🔄', memory: '🧠', guardrail: '🛡️', human: '👤',
    fallback: '🔄', comment: '📝', group: '📦',
  };
  return icons[type] || '📄';
}

function toggleNodeLink(i: number) {
  const task = tasks.value[i];
  if (!task) return;
  if (task.nodeId) {
    // Already linked — navigate to node
    emit('highlight-node', task.nodeId);
  } else {
    // Not linked — show picker
    linkingTaskIndex.value = i;
  }
}

async function linkNodeToTask(taskIndex: number | null, nodeId: string) {
  if (taskIndex === null) return;
  const task = tasks.value[taskIndex];
  if (!task) return;
  task.nodeId = nodeId;
  linkingTaskIndex.value = null;
  // Save to backend
  try {
    await fetch(`${API_BASE_URL}/plan/tasks/${task.id}/link`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId }),
    });
  } catch (e) {
    console.error('Failed to link task to node:', e);
  }
}

async function unlinkNode(taskIndex: number | null) {
  if (taskIndex === null) return;
  const task = tasks.value[taskIndex];
  if (!task) return;
  task.nodeId = undefined;
  linkingTaskIndex.value = null;
  try {
    await fetch(`${API_BASE_URL}/plan/tasks/${task.id}/link`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId: null }),
    });
  } catch (e) {
    console.error('Failed to unlink task from node:', e);
  }
}

// === Acceptance Criteria ===
function criteriaMetCount(i: number): number {
  const task = tasks.value[i];
  if (!task || !task.acceptanceCriteriaMet) return 0;
  return task.acceptanceCriteriaMet.filter(Boolean).length;
}

function criteriaPercent(i: number): number {
  const task = tasks.value[i];
  if (!task || !task.acceptanceCriteria || task.acceptanceCriteria.length === 0) return 0;
  return Math.round((criteriaMetCount(i) / task.acceptanceCriteria.length) * 100);
}

function toggleCriterionMet(taskIndex: number, criterionIndex: number, met: boolean) {
  const task = tasks.value[taskIndex];
  if (!task) return;
  if (!task.acceptanceCriteriaMet) task.acceptanceCriteriaMet = [];
  task.acceptanceCriteriaMet[criterionIndex] = met;
  saveCriteria(taskIndex);
}

async function saveCriteria(taskIndex: number | null) {
  if (taskIndex === null) return;
  const task = tasks.value[taskIndex];
  if (!task) return;
  try {
    await fetch(`${API_BASE_URL}/plan/tasks/${task.id}/criteria`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        criteria: task.acceptanceCriteria || [],
        met: task.acceptanceCriteriaMet || [],
      }),
    });
  } catch (e) {
    console.error('Failed to save criteria:', e);
  }
}

function addCriterion(taskIndex: number | null) {
  if (taskIndex === null || !newCriterion.value.trim()) return;
  const task = tasks.value[taskIndex];
  if (!task) return;
  if (!task.acceptanceCriteria) task.acceptanceCriteria = [];
  if (!task.acceptanceCriteriaMet) task.acceptanceCriteriaMet = [];
  task.acceptanceCriteria.push(newCriterion.value.trim());
  task.acceptanceCriteriaMet.push(false);
  newCriterion.value = '';
  saveCriteria(taskIndex);
}

function removeCriterion(taskIndex: number | null, criterionIndex: number) {
  if (taskIndex === null) return;
  const task = tasks.value[taskIndex];
  if (!task) return;
  task.acceptanceCriteria?.splice(criterionIndex, 1);
  task.acceptanceCriteriaMet?.splice(criterionIndex, 1);
  saveCriteria(taskIndex);
}

function exportPlan() {
  const lines = tasks.value.map(t =>
    `- [${t.status === 'DONE' ? 'x' : ' '}] ${t.title}${t.nodeId ? ` → ${t.nodeId}` : ''}`
  );
  const text = `# План\n\n${lines.join('\n')}`;
  const blob = new Blob([text], { type: 'text/markdown' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'plan.md';
  a.click();
  URL.revokeObjectURL(url);
}

function connectWebSocket() {
  const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws/execution';
  // Connect without schemaId for plan updates (broadcast)
  try {
    ws = new WebSocket(WS_URL);
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'plan_updated') {
          loadPlan();
        }
      } catch {
        // Ignore parse errors
      }
    };
  } catch {
    // WebSocket not available, ignore
  }
}

watch(() => visibleProp.visible, (v) => {
  if (v) {
    loadPlan();
    connectWebSocket();
  } else {
    ws?.close();
    ws = null;
  }
});

onUnmounted(() => {
  ws?.close();
  ws = null;
});

// Initial load if visible
loadPlan();
</script>

<style scoped>
.plan-panel {
  position: fixed;
  left: 250px;
  top: 0;
  width: 280px;
  height: 100vh;
  background: rgba(22, 33, 62, 0.95);
  border-right: 1px solid rgba(255,255,255,0.08);
  z-index: 900;
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(10px);
}

.plan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  font-weight: 600;
  color: #eee;
}

.close-btn {
  background: rgba(255,255,255,0.1);
  border: none;
  color: #eee;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
}

.plan-loading,
.plan-error {
  padding: 30px 16px;
  text-align: center;
  color: #888;
  font-size: 14px;
}

.plan-error {
  color: #ff6b6b;
}

.retry-btn {
  margin-top: 8px;
  background: #6c63ff;
  border: none;
  color: white;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
}

.plan-add {
  display: flex;
  gap: 4px;
  padding: 10px 12px;
}

.plan-textarea {
  flex: 1;
  background: #0f3460;
  border: 1px solid #4a4a6a;
  color: #eee;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  outline: none;
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
  min-height: 48px;
  max-height: 150px;
}

.plan-textarea:focus {
  border-color: #6c63ff;
}

.plan-textarea::placeholder {
  color: #666;
}

.add-btn {
  background: #e94560;
  border: none;
  color: white;
  width: 36px;
  min-height: 48px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 16px;
  font-weight: 600;
  align-self: flex-end;
}

.add-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.plan-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.plan-task {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px;
  border-radius: 6px;
  margin-bottom: 4px;
  cursor: grab;
  transition: background 0.2s;
}

.plan-task:hover { background: rgba(255,255,255,0.05); }
.plan-task.status-done { opacity: 0.6; }
.plan-task.status-blocked { background: rgba(244, 67, 54, 0.1); }

.task-left { display: flex; align-items: center; gap: 6px; flex: 1; }
.task-status-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  padding: 0;
}
.task-text { font-size: 13px; color: #ddd; }
.task-text.done { text-decoration: line-through; color: #888; }

.task-right { display: flex; gap: 4px; align-items: center; }
.task-priority {
  cursor: pointer;
  font-size: 12px;
}
.task-link {
  cursor: pointer;
  font-size: 12px;
  user-select: none;
}
.task-link:hover {
  filter: brightness(1.3);
}
.task-delete {
  background: none;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 12px;
}
.task-delete:hover { color: #ff6b6b; }

.plan-footer {
  padding: 10px 12px;
  border-top: 1px solid rgba(255,255,255,0.08);
}

.export-plan-btn {
  width: 100%;
  background: #0f3460;
  border: none;
  color: #eee;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
}

.node-picker-dropdown {
  margin: 4px 12px 8px;
  background: rgba(30, 30, 46, 0.98);
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 8px;
  padding: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.node-picker-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: #888;
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.node-picker-close {
  background: none;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 12px;
}

.node-picker-close:hover {
  color: #ff6b6b;
}

.node-picker-list {
  max-height: 200px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.node-picker-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: #ddd;
  transition: background 0.15s;
}

.node-picker-item:hover {
  background: rgba(108, 99, 255, 0.15);
}

.node-picker-item.selected {
  background: rgba(108, 99, 255, 0.25);
  color: #b8b0ff;
}

.unlink-btn {
  width: 100%;
  margin-top: 6px;
  background: rgba(244, 67, 54, 0.15);
  border: 1px solid rgba(244, 67, 54, 0.3);
  color: #ff6b6b;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
}

.unlink-btn:hover {
  background: rgba(244, 67, 54, 0.25);
}

.criteria-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 6px;
}

.criteria-bar {
  width: 40px;
  height: 4px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 2px;
  overflow: hidden;
}

.criteria-fill {
  height: 100%;
  background: #4caf50;
  border-radius: 2px;
  transition: width 0.3s;
}

.criteria-text {
  font-size: 10px;
  color: #888;
}

.task-criteria-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  padding: 0 2px;
}

.task-criteria-btn:hover {
  filter: brightness(1.3);
}

.criteria-editor {
  margin: 4px 12px 8px;
  background: rgba(30, 30, 46, 0.98);
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 8px;
  padding: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.criteria-editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: #888;
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.criteria-close {
  background: none;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 12px;
}

.criteria-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 250px;
  overflow-y: auto;
}

.criteria-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.03);
}

.criteria-item.met {
  background: rgba(76, 175, 80, 0.1);
}

.criteria-item input[type="checkbox"] {
  accent-color: #4caf50;
}

.criteria-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #ddd;
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
  outline: none;
}

.criteria-input:focus {
  border-color: #6c63ff;
}

.criteria-delete {
  background: none;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 12px;
}

.criteria-delete:hover {
  color: #ff6b6b;
}

.criteria-add {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.criteria-new-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #ddd;
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
  outline: none;
}

.criteria-add-btn {
  background: rgba(108, 99, 255, 0.2);
  border: 1px solid rgba(108, 99, 255, 0.3);
  color: #b8b0ff;
  padding: 4px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
}

.criteria-add-btn:hover {
  background: rgba(108, 99, 255, 0.3);
}
</style>
