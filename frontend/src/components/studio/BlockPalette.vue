<script setup lang="ts">
import { ref } from 'vue'

interface BlockType {
  type: string
  label: string
  color: string
  icon: string
}

const blockTypes = ref<BlockType[]>([
  {
    type: 'source',
    label: 'Receive',
    color: '#4caf50',
    icon: 'M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h7a3 3 0 013 3v1'
  },
  {
    type: 'review',
    label: 'Review',
    color: '#f59e0b',
    icon: 'M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 14l2 2 4-4'
  },
  {
    type: 'agent',
    label: 'Think',
    color: '#2196f3',
    icon: 'M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z'
  },
  {
    type: 'verifier',
    label: 'Verify',
    color: '#8b5cf6',
    icon: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z'
  },
  {
    type: 'memory',
    label: 'Remember',
    color: '#9c27b0',
    icon: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10'
  },
  {
    type: 'output',
    label: 'Act',
    color: '#ff9800',
    icon: 'M13 7l5 5m0 0l-5 5m5-5H6'
  }
])

function onDragStart(event: DragEvent, blockType: BlockType) {
  if (event.dataTransfer) {
    try {
      event.dataTransfer.setData('application/json', JSON.stringify({
        type: 'new-block',
        blockType: blockType.type,
        blockLabel: blockType.label
      }))
    } catch {
      console.warn('Failed to serialize drag data for block:', blockType.type)
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
        v-for="block in blockTypes"
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
