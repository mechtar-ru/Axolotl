<template>
  <div class="transform-wrapper">
    <button
      v-if="isSelected"
      class="delete-btn"
      @click.stop="handleDelete"
      title="Удалить узел"
    >
      ✕
    </button>
    <div class="transform-box" :class="{ selected: isSelected, 'node-running': props.data.executionStatus === 'running', 'node-completed': props.data.executionStatus === 'completed', 'node-failed': props.data.executionStatus === 'failed' }">
      <Handle type="target" :position="Position.Top" />

      <div class="box-inner">
        <div class="node-header">
          <span class="node-icon">⚡</span>
          <span
            v-if="!editingName"
            class="node-name"
            @dblclick="startEditName"
          >
            {{ props.data.name }}
          </span>
          <input
            v-else
            ref="nameInput"
            v-model="localName"
            class="node-name-input"
            @blur="finishEditName"
            @keyup.enter="finishEditName"
          />
          <span class="node-status" :style="{ background: statusColor }"></span>
          <span class="execution-icon">{{ executionIcon }}</span>
          <button class="node-expand" @click="toggleExpand">
            {{ expanded ? '▼' : '▶' }}
          </button>
        </div>

        <div v-if="expanded" class="node-content" @mousedown.stop @mouseup.stop>
          <div class="transform-section">
            <div class="section-header">Transforms</div>
            <div v-for="(step, idx) in localTransforms" :key="idx" class="transform-step">
              <select v-model="step.type" @change="updateTransforms">
                <option value="">Select...</option>
                <option value="jsonField">JSON Field</option>
                <option value="jsonPath">JSONPath</option>
                <option value="regex">Regex</option>
                <option value="delimited">Delimited</option>
                <option value="replace">Replace</option>
                <option value="prepend">Prepend</option>
                <option value="append">Append</option>
                <option value="lower">Lower</option>
                <option value="upper">Upper</option>
                <option value="trim">Trim</option>
                <option value="template">Template</option>
                <option value="jsonParse">JSON Parse</option>
                <option value="jsonStringify">JSON Stringify</option>
                <option value="ifEmpty">If Empty</option>
              </select>
              <input
                v-if="step.type === 'jsonField'"
                v-model="step.config.field"
                placeholder="field name"
                @input="updateTransforms"
              />
              <input
                v-if="step.type === 'jsonPath'"
                v-model="step.config.path"
                placeholder="$.data.field"
                @input="updateTransforms"
              />
              <input
                v-if="step.type === 'regex'"
                v-model="step.config.pattern"
                placeholder="pattern (capture group)"
                @input="updateTransforms"
              />
              <div v-if="step.type === 'delimited'" class="step-config">
                <input
                  v-model="step.config.delimiter"
                  placeholder=","
                  class="small"
                  @input="updateTransforms"
                />
                <input
                  v-model.number="step.config.index"
                  type="number"
                  placeholder="0"
                  class="small"
                  @input="updateTransforms"
                />
              </div>
              <div v-if="step.type === 'replace'" class="step-config">
                <input
                  v-model="step.config.find"
                  placeholder="find"
                  class="small"
                  @input="updateTransforms"
                />
                <input
                  v-model="step.config.replace"
                  placeholder="replace"
                  class="small"
                  @input="updateTransforms"
                />
              </div>
              <input
                v-if="step.type === 'prepend' || step.type === 'append' || step.type === 'ifEmpty'"
                v-model="step.config.text"
                placeholder="text"
                @input="updateTransforms"
              />
              <input
                v-if="step.type === 'template'"
                v-model="step.config.template"
                placeholder="{{value}} - result"
                @input="updateTransforms"
              />
              <button class="step-remove" @click="removeTransform(idx)">✕</button>
            </div>
            <button class="add-step-btn" @click="addTransform">+ Add Transform</button>
          </div>

          <div class="routes-section">
            <div class="section-header">Routes</div>
            <div v-for="(route, idx) in localRoutes" :key="idx" class="route-row">
              <input
                v-model="route.condition"
                placeholder="not empty / empty / iserror"
                @input="updateRoutes"
              />
              <input
                v-model="route.targetNodeId"
                placeholder="target node id"
                @input="updateRoutes"
              />
              <button class="step-remove" @click="removeRoute(idx)">✕</button>
            </div>
            <button class="add-step-btn" @click="addRoute">+ Add Route</button>
          </div>

          <div class="fallback-section">
            <label>Fallback:</label>
            <input
              v-model="localFallback"
              placeholder="default value if no route matches"
              @input="updateFallback"
            />
          </div>

          <div v-if="props.data.executionStatus === 'running' && props.data.progress !== undefined" class="progress-bar">
            <div class="progress-fill" :style="{ width: `${props.data.progress}%` }"></div>
            <span class="progress-text">{{ Math.round(props.data.progress) }}%</span>
          </div>
          <div v-if="props.data.result !== undefined && props.data.result !== null" class="node-result">
            <strong>Result:</strong> {{ props.data.result }}
          </div>
        </div>
      </div>

      <Handle type="source" :position="Position.Bottom" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue';
import { Handle, Position } from '@vue-flow/core';

interface TransformStep {
  type: string;
  config: Record<string, any>;
}

interface TransformRoute {
  condition: string;
  targetNodeId: string;
  targetPort?: string;
}

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    transforms?: TransformStep[];
    routes?: TransformRoute[];
    fallbackValue?: string;
    condition?: string;
    status?: string;
    result?: string;
    progress?: number;
    executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
    onDelete?: () => void;
  };
}>();

const emit = defineEmits<{
  (e: 'delete'): void;
}>();

const expanded = ref(false);
const editingName = ref(false);
const localName = ref(props.data.name);
const localTransforms = ref<TransformStep[]>(props.data.transforms || []);
const localRoutes = ref<TransformRoute[]>(props.data.routes || []);
const localFallback = ref(props.data.fallbackValue || '');
const nameInput = ref<HTMLInputElement | null>(null);

const isSelected = computed(() => props.selected === true);
const statusColor = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '#ffa500';
    case 'completed': return '#00ff00';
    case 'failed': return '#ff0000';
    default: return '#888';
  }
});
const executionIcon = computed(() => {
  switch (props.data.executionStatus) {
    case 'running': return '⏳';
    case 'completed': return '✅';
    case 'failed': return '❌';
    default: return '';
  }
});

function updateTransforms() {
  if (props.data.onUpdate) {
    props.data.onUpdate({ transforms: localTransforms.value });
  }
}

function addTransform() {
  localTransforms.value.push({ type: '', config: {} });
}

function removeTransform(idx: number) {
  localTransforms.value.splice(idx, 1);
  updateTransforms();
}

function updateRoutes() {
  if (props.data.onUpdate) {
    props.data.onUpdate({ routes: localRoutes.value });
  }
}

function addRoute() {
  localRoutes.value.push({ condition: 'not empty', targetNodeId: '' });
}

function removeRoute(idx: number) {
  localRoutes.value.splice(idx, 1);
  updateRoutes();
}

function updateFallback() {
  if (props.data.onUpdate) {
    props.data.onUpdate({ fallbackValue: localFallback.value });
  }
}

function toggleExpand() {
  expanded.value = !expanded.value;
}

function startEditName() {
  editingName.value = true;
  nextTick(() => {
    nameInput.value?.focus();
  });
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
  if (props.data.onDelete) {
    props.data.onDelete();
  } else {
    emit('delete');
  }
}
</script>

<style scoped>
@import './node-base.css';

.transform-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.transform-box {
  background: var(--bg-card);
  border: 2px solid #9c27b0;
  border-radius: var(--radius-sm);
  min-width: 280px;
  max-width: 400px;
  box-shadow: var(--shadow-sm);
  position: relative;
}
.transform-box.selected {
  border-color: var(--accent);
  box-shadow: var(--shadow-glow-accent);
}
.node-content {
  padding: 12px;
  max-height: 400px;
  overflow-y: auto;
}
.section-header {
  font-size: 12px;
  font-weight: bold;
  color: #9c27b0;
  margin-bottom: 8px;
  text-transform: uppercase;
}
.transform-section, .routes-section {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.transform-step {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.transform-step select {
  min-width: 120px;
}
.transform-step input {
  flex: 1;
  min-width: 100px;
}
.step-config {
  display: flex;
  gap: 8px;
}
.step-config input.small {
  width: 80px;
  flex: none;
}
.step-remove {
  background: none;
  border: none;
  color: var(--error);
  cursor: pointer;
  font-size: 14px;
  padding: 4px;
}
.add-step-btn {
  background: var(--bg-hover);
  border: 1px dashed var(--border);
  color: var(--text-muted);
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  width: 100%;
}
.add-step-btn:hover {
  border-color: #9c27b0;
  color: #9c27b0;
}
.route-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.route-row input {
  flex: 1;
}
.fallback-section {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fallback-section label {
  font-size: 12px;
  color: var(--text-muted);
}
.fallback-section input {
  flex: 1;
}
</style>