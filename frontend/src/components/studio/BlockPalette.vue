<script setup lang="ts">
import { BLOCK_REGISTRY } from '@/blockRegistry'
import type { BlockDefinition } from '@/types/blockRegistry'

function onDragStart(event: DragEvent, block: BlockDefinition) {
  if (event.dataTransfer) {
    try {
      event.dataTransfer.setData('application/json', JSON.stringify({
        type: 'new-block',
        blockType: block.type,
        blockLabel: block.label
      }))
    } catch {
      console.warn('Failed to serialize drag data for block:', block.type)
    }
    event.dataTransfer.effectAllowed = 'copy'
  }
}
</script>

<template>
  <div class="block-palette">
    <div class="palette-label">Blocks</div>
    <div class="palette-items">
      <div
        v-for="block in BLOCK_REGISTRY"
        :key="block.type"
        class="palette-item"
        draggable="true"
        @dragstart="onDragStart($event, block)"
      >
        <div class="block-icon" :style="{ background: block.color }">
          <svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" width="16" height="16">
            <path :d="block.icon" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <span class="block-label">{{ block.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.block-palette {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  box-shadow: var(--shadow-md);
}

.palette-label {
  font-size: var(--text-xs);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-muted);
  margin-bottom: var(--space-2);
  padding: 0 var(--space-1);
}

.palette-items {
  display: flex;
  gap: var(--space-1);
}

.palette-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  cursor: grab;
  transition: background var(--transition);
  user-select: none;
}

.palette-item:hover {
  background: var(--bg-hover);
}

.palette-item:active {
  cursor: grabbing;
}

.block-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.block-label {
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
}
</style>
