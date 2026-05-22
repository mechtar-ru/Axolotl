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
      <button class="card-close" @click="emit('close')" title="Close"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
    </div>
    <div class="card-body">
      <p class="card-text">{{ result.content }}</p>
    </div>
    <div class="card-actions">
      <button class="card-action" @click="emit('pin', result)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 2L15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2z"/></svg> Turn into node</button>
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
  background: var(--bg-card);
  border: 1px solid var(--border-accent);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  z-index: var(--z-dropdown);
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(10px);
  animation: card-appear 0.3s ease-out;
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
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(0, 188, 212, 0.2);
  font-size: 11px;
}

.card-wing {
  color: #00bcd4;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.card-score {
  color: #888;
  margin-left: auto;
}

.card-close {
  background: none;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 14px;
  padding: 0 2px;
}

.card-close:hover {
  color: #ff6b6b;
}

.card-body {
  padding: 10px 12px;
  overflow-y: auto;
  flex: 1;
  max-height: 280px;
}

.card-text {
  margin: 0;
  font-size: 13px;
  color: #ddd;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.card-actions {
  padding: 8px 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.card-action {
  width: 100%;
  background: rgba(0, 188, 212, 0.2);
  border: 1px solid rgba(0, 188, 212, 0.3);
  color: #00bcd4;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.2s;
}

.card-action:hover {
  background: rgba(0, 188, 212, 0.3);
}
</style>
