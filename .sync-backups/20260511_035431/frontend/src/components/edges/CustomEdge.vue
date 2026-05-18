<template>
  <g class="custom-edge" :class="{ selected: selected }">
    <path
      :d="path"
      :style="edgeStyle"
      fill="none"
      :marker-end="markerEnd"
      class="vue-flow__edge-path"
    />
    <!-- Прозрачный слой для кликов по всей связи -->
    <!-- <path
      :d="path"
      fill="none"
      stroke="transparent"
      stroke-width="20"
      class="edge-click-area"
      @click.stop="onEdgeClick"
    /> -->
    <!-- Кнопка удаления для выбранного ребра - поверх всего -->
    <g 
      v-if="selected" 
      class="delete-button-group"
      :transform="`translate(${edgeCenterX}, ${edgeCenterY})`"
      @click.stop="handleDelete"
    >
      <circle
        r="14"
        fill="#dc3545"
        class="edge-delete-btn"
        stroke="white"
        stroke-width="2"
      />
      <text
        text-anchor="middle"
        dominant-baseline="central"
        fill="white"
        font-size="14"
        font-weight="bold"
        pointer-events="none"
      >✕</text>
    </g>
  </g>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { type EdgeProps, getBezierPath } from '@vue-flow/core';

const props = defineProps<EdgeProps>();

const emit = defineEmits<{
  (e: 'delete'): void;
  (e: 'click'): void;
}>();

const edgeCenterX = computed(() => {
  return (props.sourceX + props.targetX) / 2;
});

const edgeCenterY = computed(() => {
  return (props.sourceY + props.targetY) / 2;
});

const path = computed(() => {
  const [pathString] = getBezierPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    targetX: props.targetX,
    targetY: props.targetY,
    sourcePosition: props.sourcePosition,
    targetPosition: props.targetPosition,
  });
  return pathString;
});

const markerEnd = computed(() => {
  return props.markerEnd;
});

const edgeStyle = computed(() => ({
  stroke: props.selected ? '#ff6b6b' : '#4a9eff',
  strokeWidth: props.selected ? 3 : 2,
  cursor: 'pointer',
}));

const selected = computed(() => props.selected === true);

function onEdgeClick() {
  console.log('Edge clicked:', props.id);
  emit('click');
}

function handleDelete(event: MouseEvent) {
  event.stopPropagation();
  event.preventDefault();
  console.log('Delete button clicked for edge:', props.id);
  if (props.data?.onDelete) {
    props.data.onDelete();
  } else {
    emit('delete');
  }
}
</script>

<style scoped>
.custom-edge {
  cursor: pointer;
}
.custom-edge.selected .vue-flow__edge-path {
  stroke: #ff6b6b;
  stroke-width: 3;
}
.edge-click-area {
  cursor: pointer;
  pointer-events: stroke;
}
.delete-button-group {
  cursor: pointer;
  z-index: 1000;
  pointer-events: all;
}
.delete-button-group circle {
  transition: all 0.2s;
  cursor: pointer;
  pointer-events: all;
}
.delete-button-group circle:hover {
  transform: scale(1.2);
  filter: brightness(1.1);
}
</style>
