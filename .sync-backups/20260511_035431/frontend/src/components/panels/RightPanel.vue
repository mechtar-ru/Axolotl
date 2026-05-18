<template>
  <div v-if="panelStore.visible" class="right-panel" :style="{ width: panelStore.width + 'px' }">
    <div class="right-panel__resize-handle" @mousedown="startResize"></div>

    <div class="right-panel__tabs">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        class="right-panel__tab"
        :class="{ active: panelStore.activeTab === tab.id }"
        @click="panelStore.open(tab.id)"
        :title="tab.label"
      >
        <span class="right-panel__tab-icon">{{ tab.icon }}</span>
        <span class="right-panel__tab-label">{{ tab.label }}</span>
      </button>
      <button class="right-panel__tab right-panel__tab--close" @click="panelStore.close()" title="Close">✕</button>
    </div>

    <div class="right-panel__content">
      <!-- Exec tab -->
      <div v-show="panelStore.activeTab === 'exec'" class="right-panel__pane">
        <ExecutionPanel
          ref="execPanelRef"
          :visible="panelStore.activeTab === 'exec'"
          :is-executing="execState?.isExecuting.value ?? false"
          :progress="execState?.progress.value ?? 0"
          :elapsed-seconds="execState?.elapsedSeconds.value ?? 0"
          :total-nodes="execState?.totalNodes.value ?? 0"
          :completed-nodes="execState?.completedNodes.value ?? 0"
          :logs="execState?.logs.value ?? []"
          :total-tokens="execState?.totalTokens.value"
          :estimated-cost="execState?.estimatedCost.value"
          :node-name-map="nodeNameMap"
          @stop="$emit('stop-execution')"
          @close="panelStore.close()"
          @highlight-node="$emit('highlight-node', $event)"
          @highlight-wave="$emit('highlight-wave', $event)"
          @add-tool-call="$emit('add-tool-call', $event)"
          @add-iteration="$emit('add-iteration', $event)"
        />
      </div>

      <!-- Plan tab -->
      <div v-show="panelStore.activeTab === 'plan'" class="right-panel__pane">
        <PlanPanel
          :visible="panelStore.activeTab === 'plan'"
          :schema-nodes="schemaNodes"
          :schema-id="schemaId"
          @close="panelStore.close()"
          @highlight-node="$emit('highlight-node', $event)"
        />
      </div>

      <!-- Memory tab -->
      <div v-show="panelStore.activeTab === 'memory'" class="right-panel__pane">
        <MemoryGraphView
          :visible="panelStore.activeTab === 'memory'"
          @close="panelStore.close()"
        />
      </div>

      <!-- History tab -->
      <div v-show="panelStore.activeTab === 'history'" class="right-panel__pane">
        <ExecutionHistory
          :visible="panelStore.activeTab === 'history'"
          :schema-id="schemaId ?? ''"
          @close="panelStore.close()"
        />
      </div>

      <!-- Templates tab -->
      <div v-show="panelStore.activeTab === 'templates'" class="right-panel__pane">
        <TemplateGallery
          :visible="panelStore.activeTab === 'templates'"
          @close="panelStore.close()"
          @create="$emit('template-create', $event)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { usePanelStore } from '../../stores/panelStore';
import { useExecutionState } from '../../composables/useExecutionState';
import ExecutionPanel from '../execution/ExecutionPanel.vue';
import PlanPanel from '../plan/PlanPanel.vue';
import MemoryGraphView from '../memory/MemoryGraphView.vue';
import ExecutionHistory from '../execution/ExecutionHistory.vue';
import TemplateGallery from '../ui/TemplateGallery.vue';

const panelStore = usePanelStore();
const execState = useExecutionState();

defineProps<{
  schemaNodes?: Array<{ id: string; name: string; type: string }>;
  schemaId?: string;
  nodeNameMap?: Record<string, string>;
}>();

defineEmits<{
  (e: 'stop-execution'): void;
  (e: 'highlight-node', nodeId: string): void;
  (e: 'highlight-wave', nodeIds: string[]): void;
  (e: 'add-tool-call', data: any): void;
  (e: 'add-iteration', data: any): void;
  (e: 'template-create', schema: any): void;
}>();

const execPanelRef = ref<InstanceType<typeof ExecutionPanel> | null>(null);

const tabs = [
  { id: 'exec' as const, icon: '▶', label: 'Exec' },
  { id: 'plan' as const, icon: '📋', label: 'Plan' },
  { id: 'memory' as const, icon: '🧠', label: 'Memory' },
  { id: 'history' as const, icon: '📜', label: 'History' },
  { id: 'templates' as const, icon: '🏗', label: 'Templates' },
];

// Resize handle
let resizing = false;
let startX = 0;
let startWidth = 0;

function startResize(e: MouseEvent) {
  resizing = true;
  startX = e.clientX;
  startWidth = panelStore.width;
  document.addEventListener('mousemove', onResize);
  document.addEventListener('mouseup', stopResize);
  e.preventDefault();
}

function onResize(e: MouseEvent) {
  if (!resizing) return;
  const delta = startX - e.clientX;
  panelStore.setWidth(startWidth + delta);
}

function stopResize() {
  resizing = false;
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
}
</script>

<style scoped>
.right-panel {
  display: flex;
  flex-direction: row;
  height: 100%;
  background: rgba(22, 33, 62, 0.95);
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  position: relative;
  flex-shrink: 0;
  backdrop-filter: blur(10px);
  min-width: 200px;
}

.right-panel__resize-handle {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  cursor: col-resize;
  z-index: 10;
}
.right-panel__resize-handle:hover {
  background: rgba(0, 188, 212, 0.3);
}

.right-panel__tabs {
  display: flex;
  flex-direction: column;
  width: 72px;
  flex-shrink: 0;
  background: rgba(15, 20, 40, 0.6);
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  padding-top: 4px;
}

.right-panel__tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 72px;
  padding: 6px 0;
  background: none;
  border: none;
  cursor: pointer;
  opacity: 0.5;
  transition: all 0.15s;
  border-left: 2px solid transparent;
  gap: 2px;
}

.right-panel__tab-icon { font-size: 14px; }
.right-panel__tab-label { font-size: 9px; color: var(--text-secondary, #aaa); text-transform: uppercase; letter-spacing: 0.3px; }
.right-panel__tab:hover {
  opacity: 0.8;
  background: rgba(255, 255, 255, 0.05);
}
.right-panel__tab.active {
  opacity: 1;
  border-left-color: var(--accent);
  background: var(--accent-light);
}

.right-panel__tab--close {
  margin-top: auto;
  opacity: 0.3;
  font-size: 12px;
}
.right-panel__tab--close:hover {
  opacity: 0.7;
}

.right-panel__content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.right-panel__pane {
  flex: 1;
  overflow-y: auto;
  height: 100%;
}
</style>
