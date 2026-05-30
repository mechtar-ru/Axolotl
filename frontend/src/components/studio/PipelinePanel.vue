<script setup lang="ts">
import { computed } from 'vue'
import { useCanvasStore } from '../../stores/useCanvasStore'
import { usePipelineStore } from '../../stores/usePipelineStore'

const canvasStore = useCanvasStore()
const pipelineStore = usePipelineStore()

const pipeline = computed(() => canvasStore.currentSchema?.pipeline)
const stages = computed(() => pipeline.value?.stages ?? [])

const nodeTypeColors: Record<string, string> = {
  source: 'var(--success)',
  review: 'var(--warning)',
  agent: 'var(--accent)',
  verifier: 'var(--info)',
  output: 'var(--text-muted)',
  transform: 'var(--text-secondary)',
}

function getTypeColor(type: string | undefined) {
  return nodeTypeColors[type ?? ''] ?? 'var(--text-muted)'
}

function getStageTag(stageId: string): string | null {
  if (stageId.startsWith('test-')) return 'Test'
  if (stageId.startsWith('verify-test-')) return 'Verify Test'
  if (stageId.startsWith('impl-')) return 'Impl'
  return null
}

const stageLevels = computed(() => {
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
})
</script>

<template>
  <div class="pipeline-panel">
    <div class="panel-header">
      <h3>Pipeline</h3>
    </div>

    <template v-if="pipeline && pipeline.stages && pipeline.stages.length > 0">
      <div class="pipeline-info">
        <span class="pipeline-name">{{ pipeline.name }}</span>
        <span v-if="pipeline.tddEnabled" class="tdd-badge">TDD</span>
      </div>

      <div class="stages-flow">
        <div v-for="(level, li) in stageLevels" :key="li" class="stage-level">
          <div class="level-label">Level {{ li }}</div>
          <div class="level-stages">
            <div
              v-for="stage in level"
              :key="stage.id"
              class="stage-card"
              :style="{ borderLeftColor: getTypeColor(stage.nodeType) }"
            >
              <div class="stage-icon">
                <svg v-if="stage.nodeType === 'source'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M22 12A10 10 0 1 1 2 12a10 10 0 0 1 20 0z"/><path d="M12 6v6l4 2"/>
                </svg>
                <svg v-else-if="stage.nodeType === 'review'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>
                </svg>
                <svg v-else-if="stage.nodeType === 'verifier'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
                <svg v-else-if="stage.nodeType === 'output'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M22 12A10 10 0 1 1 2 12a10 10 0 0 1 20 0z"/><path d="M16 12H8"/><path d="m12 8 4 4-4 4"/>
                </svg>
                <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="2" y="6" width="20" height="12" rx="2"/><path d="M12 6V4"/><path d="M6 6V2"/><path d="M18 6V2"/>
                </svg>
              </div>
              <div class="stage-body">
                <div class="stage-name">{{ stage.name }}</div>
                <div class="stage-type">{{ stage.nodeType }}</div>
                <div v-if="getStageTag(stage.id)" class="stage-tag" :class="'tag-' + getStageTag(stage.id)!.toLowerCase().replace(' ', '-')">{{ getStageTag(stage.id) }}</div>
                <div class="stage-model" v-if="stage.model">Model: {{ stage.model }}</div>
              </div>
              <div class="stage-status completed" v-if="stage.id in pipelineStore.pipelineStatus.stageResults">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--success)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="20 6 9 17 4 12"/>
                </svg>
              </div>
            </div>
          </div>
          <div v-if="li < stageLevels.length - 1" class="level-arrow">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/>
            </svg>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="empty-state">
      <svg class="empty-icon" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
      </svg>
      <p>Canvas nodes are executed directly. No separate pipeline definition needed.</p>
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
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-primary);
}

.empty-state {
  padding: 24px;
  text-align: center;
  color: var(--text-muted);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.empty-icon {
  opacity: 0.3;
}

.pipeline-info {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
  padding: 8px 12px;
  background: var(--bg-surface);
  border-radius: 6px;
  font-size: var(--text-xs);
}

.pipeline-name { color: var(--text-primary); font-weight: 600; }

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
  color: var(--text-muted);
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
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-left: 3px solid var(--text-muted);
  border-radius: 6px;
  padding: 10px 14px;
  min-width: 160px;
  cursor: default;
  transition: border-color 0.2s, background 0.2s;
}

.stage-card:hover {
  border-color: var(--text-muted);
  background: var(--bg-hover);
}

.stage-icon {
  display: flex;
  align-items: center;
  color: var(--text-secondary);
}

.stage-body {
  flex: 1;
  min-width: 0;
}

.stage-name {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stage-type {
  font-size: var(--text-xs);
  color: var(--text-muted);
  text-transform: uppercase;
}

.stage-model {
  font-size: 10px;
  color: var(--text-muted);
}

.stage-tag {
  display: inline-block;
  font-size: 9px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 1px 5px;
  border-radius: 3px;
  margin-top: 2px;
}
.tag-test { --tag-bg: rgba(33, 150, 243, 0.15); --tag-fg: #2196f3; }
.tag-verify-test { --tag-bg: rgba(255, 152, 0, 0.15); --tag-fg: #ff9800; }
.tag-impl { --tag-bg: rgba(76, 175, 80, 0.15); --tag-fg: #4caf50; }
.stage-tag { background: var(--tag-bg); color: var(--tag-fg); }

.tdd-badge {
  font-size: 9px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 2px 6px;
  border-radius: 3px;
  background: rgba(156, 39, 176, 0.15);
  color: #9c27b0;
  margin-left: auto;
}

.stage-status {
  display: flex;
  align-items: center;
}

.level-arrow {
  display: flex;
  align-items: center;
  color: var(--text-muted);
  opacity: 0.5;
  padding: 2px 0;
}
</style>
