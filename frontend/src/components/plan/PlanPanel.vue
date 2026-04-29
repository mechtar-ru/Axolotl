<template>
  <div v-if="visible" class="plan-panel">
    <div class="plan-header">
      <span>📋 План</span>
      <button class="close-btn" @click="$emit('close')">✕</button>
    </div>

    <!-- Workspace selector -->
    <div class="workspace-selector">
      <select v-model="selectedWorkspace" @change="onWorkspaceChange" class="workspace-select">
        <option v-for="ws in workspaces" :key="ws" :value="ws">{{ ws }}</option>
      </select>
      <button class="add-workspace-btn" @click="showCreateWorkspace = true" title="Создать workspace">➕</button>
    </div>

    <!-- Create workspace modal -->
    <div v-if="showCreateWorkspace" class="create-workspace-modal">
      <div class="create-workspace-content">
        <div class="create-workspace-header">
          <span>Новый workspace</span>
          <button @click="showCreateWorkspace = false">✕</button>
        </div>
        <input v-model="newWorkspaceName" placeholder="Имя workspace" class="workspace-input" @keyup.enter="createWorkspace" />
        <button @click="createWorkspace" class="create-btn">Создать</button>
      </div>
    </div>

    <!-- Level filter tabs -->
    <div class="level-tabs">
      <button
        v-for="lvl in levels"
        :key="lvl.value"
        class="level-tab"
        :class="{ active: selectedLevel === lvl.value }"
        @click="selectLevel(lvl.value)"
      >
        {{ lvl.label }}
      </button>
    </div>

    <div v-if="loading" class="plan-loading">Загрузка...</div>

    <div v-else-if="error" class="plan-error">
      {{ error }}
      <button class="retry-btn" @click="loadPlan">🔄</button>
    </div>

    <div v-else>
      <div class="plan-add">
        <div class="plan-add-row">
          <textarea
            v-model="newTask"
            placeholder="Задачи (каждая с новой строки)...&#10;Напр: Сделать тесты | HIGH&#10;      Обновить docs | LOW"
            class="plan-textarea"
            rows="3"
            @keydown.ctrl.enter="addBatchTasks"
          />
          <div class="plan-add-actions">
            <button class="add-btn" @click="addBatchTasks" :disabled="!parsedTaskCount">
              +{{ parsedTaskCount > 1 ? parsedTaskCount : '' }}
            </button>
            <button class="browse-btn" @click="browseFolder" title="Выбрать папку">📂</button>
          </div>
        </div>
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
          <div class="task-main" @click="expandedTask = expandedTask === i ? null : i">
            <div class="task-left">
              <button class="task-status-btn" @click.stop="cycleStatus(i)">
                {{ statusIcon(task.status) }}
              </button>
              <div class="task-content">
                <span class="task-text" :class="{ done: task.status === 'DONE' }">{{ task.title }}</span>
                <span v-if="task.description && expandedTask !== i" class="task-desc">{{ task.description }}</span>
                <span v-if="task.dependencies && task.dependencies.length > 0 && expandedTask !== i" class="task-deps" :title="task.dependencies.join(', ')">
                  ↓ {{ task.dependencies.length }}
                </span>
              </div>
              <!-- Acceptance criteria indicator -->
              <span v-if="task.acceptanceCriteria && task.acceptanceCriteria.length > 0" class="criteria-indicator" :title="`${criteriaMetCount(i)}/${task.acceptanceCriteria.length} критериев выполнено`">
                <span class="criteria-bar">
                  <span class="criteria-fill" :style="{ width: criteriaPercent(i) + '%' }"></span>
                </span>
                <span class="criteria-text">{{ criteriaMetCount(i) }}/{{ task.acceptanceCriteria.length }}</span>
              </span>
            </div>
            <div class="task-right">
              <span class="task-expand-icon">{{ expandedTask === i ? '▼' : '▶' }}</span>
              <span class="task-priority" @click.stop="cyclePriority(i)" :title="task.priority">
                {{ priorityIcon(task.priority) }}
              </span>
              <span class="task-link" @click.stop="toggleNodeLink(i)" :title="task.nodeId ? 'Перейти к узлу' : 'Связать с узлом'">
                🔗
              </span>
              <button class="task-criteria-btn" @click.stop="criteriaEditIndex = i" title="Критерии приёмки">📋</button>
              <button class="task-delete" @click.stop="deleteTask(i)" title="Удалить">✕</button>
            </div>
          </div>
          <!-- Expanded detail -->
          <div v-if="expandedTask === i" class="task-detail">
            <div v-if="task.description" class="detail-section">
              <div class="detail-label">Description</div>
              <div class="detail-text">{{ task.description }}</div>
            </div>
            <div v-if="task.dependencies && task.dependencies.length > 0" class="detail-section">
              <div class="detail-label">Dependencies</div>
              <div class="detail-deps">
                <span v-for="dep in task.dependencies" :key="dep" class="dep-chip">{{ dep }}</span>
              </div>
            </div>
            <div v-if="task.acceptanceCriteria && task.acceptanceCriteria.length > 0" class="detail-section">
              <div class="detail-label">Acceptance Criteria</div>
              <div class="detail-criteria">
                <div v-for="(c, ci) in task.acceptanceCriteria" :key="ci" class="detail-criterion" :class="{ met: task.acceptanceCriteriaMet?.[ci] }">
                  <span class="criterion-check">{{ task.acceptanceCriteriaMet?.[ci] ? '☑' : '☐' }}</span>
                  {{ c }}
                </div>
              </div>
            </div>
            <div v-if="task.schemaId" class="detail-section">
              <div class="detail-label">Schema</div>
              <div class="detail-schema">{{ task.schemaId.substring(0, 8) }}...</div>
            </div>
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

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = extra || {};
  const token = localStorage.getItem('axolotl_token');
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

const visibleProp = defineProps<{ visible: boolean; schemaNodes?: Array<{ id: string; name: string; type: string }>; schemaId?: string }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'highlight-node', nodeId: string): void }>();

interface PlanTask {
  id: string;
  title: string;
  description?: string;
  status: 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED';
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  nodeId?: string;
  schemaId?: string;
  dependencies?: string[];
  acceptanceCriteria?: string[];
  acceptanceCriteriaMet?: boolean[];
}

const tasks = ref<PlanTask[]>([]);
const newTask = ref('');
const loading = ref(false);
const error = ref('');
const childPlans = ref<Array<{ id: string; name: string; level: string }>>([]);
let dragTaskId = ref<string | null>(null);
const linkingTaskIndex = ref<number | null>(null);
const criteriaEditIndex = ref<number | null>(null);
const newCriterion = ref('');
const expandedTask = ref<number | null>(null);

// Level filter
type PlanLevel = 'all' | 'project' | 'current' | 'child';
const levels = [
  { value: 'project' as PlanLevel, label: 'Общее' },
  { value: 'current' as PlanLevel, label: 'Текущий' },
  { value: 'child' as PlanLevel, label: 'Дочерние' },
  { value: 'all' as PlanLevel, label: 'Все' },
];
const selectedLevel = ref<PlanLevel>('project');

// Workspace management
const workspaces = ref<string[]>(['default']);
const selectedWorkspace = ref<string>('default');
const showCreateWorkspace = ref(false);
const newWorkspaceName = ref('');

async function loadWorkspaces() {
  try {
    const res = await fetch(`${API_BASE_URL}/plan/workspaces`, { headers: authHeaders() });
    if (res.ok) {
      const list = await res.json();
      if (list.length > 0) {
        workspaces.value = list;
      }
    }
  } catch (e) {
    console.error('Failed to load workspaces:', e);
  }
}

async function createWorkspace() {
  if (!newWorkspaceName.value.trim()) return;
  try {
    const res = await fetch(`${API_BASE_URL}/plan/workspaces`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ name: newWorkspaceName.value.trim() }),
    });
    if (res.ok) {
      await loadWorkspaces();
      selectedWorkspace.value = newWorkspaceName.value.trim();
      newWorkspaceName.value = '';
      showCreateWorkspace.value = false;
      loadPlan();
    }
  } catch (e) {
    console.error('Failed to create workspace:', e);
  }
}

async function onWorkspaceChange() {
  loadPlan();
}

function selectLevel(lvl: PlanLevel) {
  selectedLevel.value = lvl;
  loadPlan();
}

async function browseFolder() {
  if (window.electronAPI?.showOpenDialog) {
    const result = await window.electronAPI.showOpenDialog({
      title: 'Select project folder',
      properties: ['openDirectory'],
    });
    if (!result.canceled && result.filePaths.length > 0) {
      newTask.value = 'Analyze project: ' + result.filePaths[0];
    }
  } else {
    const path = prompt('Enter project path:');
    if (path) {
      newTask.value = 'Analyze project: ' + path;
    }
  }
}

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
  const schemaId = visibleProp.schemaId;

  // Load child plans from the current schema
  if (selectedLevel.value === 'child' && schemaId) {
    try {
      const schemaPlanResp = await fetch(`${API_BASE_URL}/plan/by-schema/${schemaId}?workspaceId=${selectedWorkspace.value}`, { headers: authHeaders() });
      if (schemaPlanResp.ok) {
        const schemaPlan = await schemaPlanResp.json();
        if (schemaPlan) {
          tasks.value = (schemaPlan.tasks || []).map((t: any) => ({
            id: t.id,
            title: t.title,
            description: t.description || '',
            status: t.status || 'TODO',
            priority: t.priority || 'MEDIUM',
            nodeId: t.nodeId,
            schemaId: t.schemaId,
            dependencies: t.dependencies || [],
            acceptanceCriteria: t.acceptanceCriteria || [],
            acceptanceCriteriaMet: t.acceptanceCriteriaMet || [],
          }));
        } else {
          tasks.value = [];
        }
      } else {
        tasks.value = [];
      }
    } catch {
      tasks.value = [];
    }
    loading.value = false;
    return;
  }

  // Load all subplans for children tab
  if (selectedLevel.value === 'child') {
    try {
      const response = await fetch(`${API_BASE_URL}/plan?workspaceId=${selectedWorkspace.value}`, { headers: authHeaders() });
      if (response.ok) {
        const plan = await response.json();
        const childResp = await fetch(`${API_BASE_URL}/plan/${plan.id}/children`, { headers: authHeaders() });
        if (childResp.ok) {
          childPlans.value = await childResp.json();
        }
      }
    } catch {}
    tasks.value = [];
    loading.value = false;
    return;
  }

  // Default: load main plan
  try {
    const response = await fetch(`${API_BASE_URL}/plan?workspaceId=${selectedWorkspace.value}`, { headers: authHeaders() });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const plan = await response.json();

    // For 'project' level, filter out tasks that have schemaId
    if (selectedLevel.value === 'project') {
      tasks.value = (plan.tasks || [])
        .filter((t: any) => !t.schemaId)
        .map((t: any) => ({
          id: t.id,
          title: t.title,
          description: t.description || '',
          status: t.status || 'TODO',
          priority: t.priority || 'MEDIUM',
          nodeId: t.nodeId,
          schemaId: t.schemaId,
          dependencies: t.dependencies || [],
          acceptanceCriteria: t.acceptanceCriteria || [],
          acceptanceCriteriaMet: t.acceptanceCriteriaMet || [],
        }));
    // For 'current' level, filter tasks with current schemaId
    } else if (selectedLevel.value === 'current' && schemaId) {
      tasks.value = (plan.tasks || [])
        .filter((t: any) => t.schemaId === schemaId)
        .map((t: any) => ({
          id: t.id,
          title: t.title,
          description: t.description || '',
          status: t.status || 'TODO',
          priority: t.priority || 'MEDIUM',
          nodeId: t.nodeId,
          schemaId: t.schemaId,
          dependencies: t.dependencies || [],
          acceptanceCriteria: t.acceptanceCriteria || [],
          acceptanceCriteriaMet: t.acceptanceCriteriaMet || [],
        }));
    } else {
      tasks.value = (plan.tasks || []).map((t: any) => ({
        id: t.id,
        title: t.title,
        description: t.description || '',
        status: t.status || 'TODO',
        priority: t.priority || 'MEDIUM',
        nodeId: t.nodeId,
        schemaId: t.schemaId,
        dependencies: t.dependencies || [],
        acceptanceCriteria: t.acceptanceCriteria || [],
        acceptanceCriteriaMet: t.acceptanceCriteriaMet || [],
      }));
    }
  } catch (e: any) {
    error.value = 'Ошибка загрузки плана: ' + (e.message || e);
  } finally {
    loading.value = false;
  }
}

async function addBatchTasks() {
  const batch = parsedTasks.value;
  if (batch.length === 0) return;
  const schemaId = visibleProp.schemaId;
  try {
    for (const task of batch) {
      const body: Record<string, any> = { title: task.title, priority: task.priority, workspaceId: selectedWorkspace.value };
      if (selectedLevel.value === 'current' && schemaId) {
        body.schemaId = schemaId;
      }
      const response = await fetch(`${API_BASE_URL}/plan/tasks`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const created = await response.json();
      tasks.value.push({
        id: created.id,
        title: created.title,
        description: created.description || '',
        status: created.status || 'TODO',
        priority: created.priority || task.priority,
        nodeId: created.nodeId,
        schemaId: created.schemaId,
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
      url = `${API_BASE_URL}/plan/tasks/${taskId}/status?workspaceId=${selectedWorkspace.value}`;
      body = { status: updates.status, workspaceId: selectedWorkspace.value };
    } else if (updates.priority) {
      url = `${API_BASE_URL}/plan/tasks/${taskId}/priority?workspaceId=${selectedWorkspace.value}`;
      body = { priority: updates.priority, workspaceId: selectedWorkspace.value };
    } else {
      return;
    }
    const response = await fetch(url, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
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
    const response = await fetch(`${API_BASE_URL}/plan/tasks/${task.id}?workspaceId=${selectedWorkspace.value}`, {
      method: 'DELETE',
      headers: authHeaders(),
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
    fetch(`${API_BASE_URL}/plan/tasks/${item.id}/move?workspaceId=${selectedWorkspace.value}`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
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
    await fetch(`${API_BASE_URL}/plan/tasks/${task.id}/link?workspaceId=${selectedWorkspace.value}`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
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
    await fetch(`${API_BASE_URL}/plan/tasks/${task.id}/link?workspaceId=${selectedWorkspace.value}`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
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
      headers: authHeaders({ 'Content-Type': 'application/json' }),
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
    loadWorkspaces();
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
  color: var(--text-primary);
}

.workspace-selector {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.workspace-select {
  flex: 1;
  background: var(--bg-code);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-primary);
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
}

.add-workspace-btn {
  background: var(--memory-color);
  border: none;
  color: white;
  width: 28px;
  height: 28px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.create-workspace-modal {
  position: absolute;
  top: 60px;
  left: 10px;
  right: 10px;
  background: rgba(15, 52, 96, 0.98);
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 8px;
  padding: 12px;
  z-index: 100;
}

.create-workspace-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--text-primary);
  font-size: 13px;
}

.create-workspace-header button {
  background: none;
  border: none;
  color: var(--text-muted-alt);
  cursor: pointer;
}

.workspace-input {
  width: 100%;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  color: var(--text-primary);
  padding: 8px;
  border-radius: 4px;
  margin-bottom: 8px;
}

.create-btn {
  width: 100%;
  background: var(--memory-color);
  border: none;
  color: white;
  padding: 8px;
  border-radius: 4px;
  cursor: pointer;
}

.level-tabs {
  display: flex;
  padding: 8px 12px;
  gap: 6px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.level-tab {
  background: transparent;
  border: 1px solid rgba(255,255,255,0.15);
  color: var(--text-muted-alt);
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.level-tab.active {
  background: var(--memory-color);
  border-color: var(--accent);
  color: var(--text-inverse);
}

.task-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.task-desc {
  font-size: 11px;
  color: var(--text-muted-alt);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-deps {
  font-size: 10px;
  color: var(--text-muted);
  background: rgba(255,255,255,0.1);
  padding: 1px 4px;
  border-radius: 3px;
}

.close-btn {
  background: rgba(255,255,255,0.1);
  border: none;
  color: var(--text-primary);
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
}

.plan-loading,
.plan-error {
  padding: 30px 16px;
  text-align: center;
  color: var(--text-muted-alt);
  font-size: 14px;
}

.plan-error {
  color: var(--danger);
}

.retry-btn {
  margin-top: 8px;
  background: var(--accent);
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

.plan-add-row {
  display: flex;
  gap: 6px;
  width: 100%;
}

.plan-add-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.browse-btn {
  background: var(--memory-color);
  border: none;
  color: white;
  border-radius: 4px;
  width: 36px;
  height: 36px;
  cursor: pointer;
  font-size: 16px;
}

.plan-textarea {
  flex: 1;
  background: var(--bg-code);
  border: 1px solid var(--border);
  color: var(--text-primary);
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
  border-color: var(--accent);
}

.plan-textarea::placeholder {
  color: var(--text-muted);
}

.add-btn {
  background: var(--node-loop);
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
  overflow-x: hidden;
  padding: 8px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255,255,255,0.3) transparent;
}

.plan-task {
  border-radius: 6px;
  margin-bottom: 4px;
  cursor: grab;
  transition: background 0.2s;
  border: 1px solid transparent;
}

.plan-task:hover { border-color: rgba(255,255,255,0.1); }
.plan-task.status-done { opacity: 0.6; }
.plan-task.status-blocked { background: rgba(244, 67, 54, 0.1); }

.task-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px;
  cursor: pointer;
}

.task-expand-icon {
  font-size: 10px;
  color: var(--text-muted);
}

.task-detail {
  padding: 0 12px 10px 32px;
  border-top: 1px solid rgba(255,255,255,0.05);
}

.detail-section {
  margin-top: 6px;
}

.detail-label {
  font-size: 10px;
  color: var(--memory-color);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 2px;
}

.detail-text {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.4;
}

.detail-deps {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.dep-chip {
  font-size: 10px;
  background: rgba(108, 99, 255, 0.15);
  border: 1px solid rgba(108, 99, 255, 0.3);
  color: var(--violet-light);
  padding: 2px 6px;
  border-radius: 3px;
}

.detail-criteria {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-criterion {
  font-size: 11px;
  color: var(--text-muted-alt);
  display: flex;
  gap: 4px;
}

.detail-criterion.met {
  color: var(--success);
  text-decoration: line-through;
}

.criterion-check {
  flex-shrink: 0;
}

.detail-schema {
  font-size: 11px;
  color: var(--text-muted);
  font-family: monospace;
}

.task-left { display: flex; align-items: center; gap: 6px; flex: 1; }
.task-status-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  padding: 0;
}
.task-text { font-size: 13px; color: var(--text-primary); }
.task-text.done { text-decoration: line-through; color: var(--text-muted-alt); }

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
  color: var(--text-muted-alt);
  cursor: pointer;
  font-size: 12px;
}
.task-delete:hover { color: var(--danger); }

.plan-footer {
  padding: 10px 12px;
  border-top: 1px solid rgba(255,255,255,0.08);
}

.export-plan-btn {
  width: 100%;
  background: var(--bg-code);
  border: none;
  color: var(--text-primary);
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
  color: var(--text-muted-alt);
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.node-picker-close {
  background: none;
  border: none;
  color: var(--text-muted-alt);
  cursor: pointer;
  font-size: 12px;
}

.node-picker-close:hover {
  color: var(--danger);
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
  color: var(--text-primary);
  transition: background 0.15s;
}

.node-picker-item:hover {
  background: rgba(108, 99, 255, 0.15);
}

.node-picker-item.selected {
  background: rgba(108, 99, 255, 0.25);
  color: var(--violet-light);
}

.unlink-btn {
  width: 100%;
  margin-top: 6px;
  background: rgba(244, 67, 54, 0.15);
  border: 1px solid rgba(244, 67, 54, 0.3);
  color: var(--danger);
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
  background: var(--success);
  border-radius: 2px;
  transition: width 0.3s;
}

.criteria-text {
  font-size: 10px;
  color: var(--text-muted-alt);
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
  color: var(--text-muted-alt);
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.criteria-close {
  background: none;
  border: none;
  color: var(--text-muted-alt);
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
  accent-color: var(--success);
}

.criteria-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-primary);
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
  outline: none;
}

.criteria-input:focus {
  border-color: var(--accent);
}

.criteria-delete {
  background: none;
  border: none;
  color: var(--text-muted-alt);
  cursor: pointer;
  font-size: 12px;
}

.criteria-delete:hover {
  color: var(--danger);
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
  color: var(--text-primary);
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
  outline: none;
}

.criteria-add-btn {
  background: rgba(108, 99, 255, 0.2);
  border: 1px solid rgba(108, 99, 255, 0.3);
  color: var(--violet-light);
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
