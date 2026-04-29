<template>
  <div
    class="memory-result-card"
    :style="{ left: position.x + 'px', top: position.y + 'px' }"
    draggable="true"
    @dragstart="onDragStart"
    @dragend="onDragEnd"
  >
    <div class="card-header">
      <span class="card-wing">{{ result.wing }}/{{ result.room }}</span>
      <span v-if="result.score !== undefined" class="card-score">score: {{ result.score.toFixed(2) }}</span>
      <button class="card-close" @click="emit('close')" title="Закрыть">✕</button>
    </div>
    <div class="card-body">
      <p class="card-text">{{ result.content }}</p>
    </div>
    <div class="card-actions">
      <button class="card-action" @click="emit('pin', result)">📌 Превратить в узел</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

export interface MemoryResult {
  wing: string;
  room: string;
  content: string;
  score?: number;
}

const props = defineProps<{
  result: MemoryResult;
  initialPosition?: { x: number; y: number };
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'pin', result: MemoryResult): void;
}>();

const position = ref(props.initialPosition ?? { x: 200, y: 200 });
const isDragging = ref(false);
const dragOffset = ref({ x: 0, y: 0 });

function onDragStart(event: DragEvent) {
  if (!event.dataTransfer) return;
  isDragging.value = true;
  const target = event.target as HTMLElement;
  const rect = target.getBoundingClientRect();
  dragOffset.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top,
  };
  event.dataTransfer.effectAllowed = 'move';
  // Set data for Vue Flow to recognize as a new node drop
  event.dataTransfer.setData('application/json', JSON.stringify({
    type: 'memory-result',
    result: props.result,
  }));
}

function onDragEnd() {
  isDragging.value = false;
}
</script>

<style scoped>
.memory-result-card {
  position: fixed;
  width: 320px;
  max-height: 400px;
  background: rgba(22, 33, 62, 0.95);
  border: 1px solid rgba(0, 188, 212, 0.4);
  border-radius: var(--radius-md);
  box-shadow: 0 8px 32px rgba(0, 188, 212, 0.2);
  z-index: var(--z-panel);
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(10px);
  animation: card-appear var(--transition-slow) ease-out;
}

@keyframes card-appear {
  from {
    opacity: 0;
    transform: scale(0.9) translateY(10px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.card-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid rgba(0, 188, 212, 0.2);
  font-size: var(--text-xs);
}

.card-wing {
  color: var(--memory-color);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.card-score {
  color: var(--text-muted);
  margin-left: auto;
}

.card-close {
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  font-size: var(--text-md);
  padding: 0 2px;
}

.card-close:hover {
  color: var(--error);
}

.card-body {
  padding: var(--space-2-5) var(--space-3);
  overflow-y: auto;
  flex: 1;
  max-height: 280px;
}

.card-text {
  margin: 0;
  font-size: var(--text-base);
  color: var(--text-primary);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.card-actions {
  padding: var(--space-2) var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.card-action {
  width: 100%;
  background: var(--memory-light);
  border: 1px solid rgba(0, 188, 212, 0.3);
  color: var(--memory-color);
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-sm);
  transition: background var(--transition);
}

.card-action:hover {
  background: rgba(0, 188, 212, 0.3);
}
</style>
