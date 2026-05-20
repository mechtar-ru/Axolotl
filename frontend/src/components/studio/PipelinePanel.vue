<script setup lang="ts">
import { computed } from 'vue'
import { useSchemaStore } from '../../stores/schemaStore'

const store = useSchemaStore()

const pipeline = computed(() => store.currentSchema?.pipeline)
const stages = computed(() => pipeline.value?.stages ?? [])
const running = computed(() => store.pipelineStatus.running)

const nodeTypeIcons: Record<string, string> = {
  source: '📥',
  review: '🔍',
  agent: '🤖',
  verifier: '✅',
  output: '📤',
  transform: '🔄',
  custom: '⚙️',
}

function getIcon(type: string | undefined) {
  return nodeTypeIcons[type ?? ''] ?? '⬜'
}

function getTypeColor(type: string | undefined) {
  const colors: Record<string, string> = {
    source: '#4CAF50',
    review: '#FF9800',
    agent: '#2196F3',
    verifier: '#9C27B0',
    output: '#607D8B',
    transform: '#795548',
  }
  return colors[type ?? ''] ?? '#999'
}

function stageLevels() {
  if (!stages.value.length) return []
  const deps = new Map<string, Set<string>>()
  for (const s of stages.value) {
    deps.set(s.id, new Set(s.dependencies ?? []))
  }
  const levels: typeof stages.value[] = []
  const processed = new Set<string>()
  while (processed.size < stages.value.length) {
    const level = stages.value.filter(s => {
      if (processed.has(s.id)) return false
      const remaining = [...(deps.get(s.id) ?? [])].filter(d => !processed.has(d))
      return remaining.length === 0
    })
    if (!level.length) break
    for (const s of level) processed.add(s.id)
    levels.push(level)
  }
  return levels
}

function handleExecute() {
  if (store.currentSchema) {
    store.executePipeline(store.currentSchema.id)
  }
}

function handleCancel() {
  if (store.currentSchema) {
    store.cancelPipelineExecution(store.currentSchema.id)
  }
}

function handleBuildNodes() {
  if (store.currentSchema) {
    store.buildPipelineNodes(store.currentSchema.id)
  }
}

function handleCreateDefault() {
  if (store.currentSchema) {
    store.createDefaultPipeline(store.currentSchema.id)
  }
}
</script>

<template>
  <div class="pipeline-panel">
    <div class="panel-header">
      <h3>Pipeline</h3>
      <div class="header-actions">
        <button
          v-if="!pipeline"
          class="btn btn-sm btn-outline"
          @click="handleCreateDefault"
        >
          + Create Default Pipeline
        </button>
        <button
          v-if="pipeline && !running"
          class="btn btn-sm btn-primary"
          @click="handleExecute"
        >
          ▶ Execute Pipeline
        </button>
        <button
          v-if="running"
          class="btn btn-sm btn-danger"
          @click="handleCancel"
        >
          ⏹ Cancel
        </button>
        <button
          v-if="pipeline"
          class="btn btn-sm btn-outline"
          @click="handleBuildNodes"
        >
          Build Nodes
        </button>
      </div>
    </div>

    <div v-if="!pipeline" class="empty-state">
      <p>No pipeline defined. Create a default pipeline or define stages in the schema JSON.</p>
    </div>

    <div v-else class="pipeline-content">
      <div class="pipeline-info">
        <span class="pipeline-name">{{ pipeline.name }}</span>
        <span class="pipeline-strategy">{{ pipeline.parallelStrategy ?? 'sequential' }}</span>
        <span class="pipeline-stages-count">{{ pipeline.stages?.length ?? 0 }} stages</span>
      </div>

      <div class="stages-flow">
        <div v-for="(level, li) in stageLevels()" :key="li" class="stage-level">
          <div class="level-label">Level {{ li }}</div>
          <div class="level-stages">
            <div
              v-for="stage in level"
              :key="stage.id"
              class="stage-card"
              :style="{ borderLeftColor: getTypeColor(stage.nodeType) }"
            >
              <div class="stage-icon">{{ getIcon(stage.nodeType) }}</div>
              <div class="stage-body">
                <div class="stage-name">{{ stage.name }}</div>
                <div class="stage-type">{{ stage.nodeType }}</div>
                <div class="stage-model" v-if="stage.model">Model: {{ stage.model }}</div>
              </div>
              <div class="stage-status" v-if="store.pipelineStatus.stageResults[stage.id]">
                ✔
              </div>
            </div>
          </div>
          <div v-if="li < stageLevels().length - 1" class="level-arrow">↓</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pipeline-panel {
  padding: 12px;
  height: 100%;
  overflow-y: auto;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.panel-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #e0e0e0;
}

.header-actions {
  display: flex;
  gap: 6px;
}

.btn {
  padding: 4px 10px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition: opacity 0.2s;
}

.btn:hover { opacity: 0.85; }

.btn-sm { padding: 3px 8px; font-size: 11px; }

.btn-primary { background: #4CAF50; color: white; }
.btn-danger { background: #f44336; color: white; }
.btn-outline { background: transparent; border: 1px solid #555; color: #ccc; }

.empty-state {
  padding: 24px;
  text-align: center;
  color: #888;
  font-size: 13px;
}

.pipeline-info {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
  padding: 8px 12px;
  background: #1e1e1e;
  border-radius: 6px;
  font-size: 12px;
}

.pipeline-name { color: #e0e0e0; font-weight: 600; }
.pipeline-strategy {
  color: #888;
  background: #2a2a2a;
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 10px;
  text-transform: uppercase;
}
.pipeline-stages-count { color: #888; margin-left: auto; }

.stages-flow {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.stage-level {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.level-label {
  font-size: 10px;
  color: #666;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.level-stages {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}

.stage-card {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #1e1e1e;
  border: 1px solid #333;
  border-left: 3px solid #999;
  border-radius: 6px;
  padding: 10px 14px;
  min-width: 160px;
  cursor: default;
  transition: border-color 0.2s, background 0.2s;
}

.stage-card:hover {
  border-color: #555;
  background: #252525;
}

.stage-icon { font-size: 20px; }

.stage-body {
  flex: 1;
  min-width: 0;
}

.stage-name {
  font-size: 13px;
  font-weight: 500;
  color: #e0e0e0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stage-type {
  font-size: 11px;
  color: #888;
  text-transform: uppercase;
}

.stage-model {
  font-size: 10px;
  color: #666;
}

.stage-status {
  font-size: 16px;
}

.level-arrow {
  font-size: 18px;
  color: #555;
  padding: 2px 0;
}
</style>
