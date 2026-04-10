<template>
  <div class="comment-node" :style="{ background: commentColor }">
    <button v-if="isSelected" class="delete-btn" @click.stop="handleDelete">✕</button>
    <div class="comment-header">
      <span class="comment-icon">📝</span>
      <div class="comment-colors">
        <button v-for="c in colors" :key="c" class="color-dot" :style="{ background: c }" @click="changeColor(c)"></button>
      </div>
    </div>
    <textarea
      v-model="localText"
      class="comment-text"
      placeholder="Заметка..."
      @mousedown.stop
      @mouseup.stop
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { Handle, Position } from '@vue-flow/core';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    sourceData?: string;
    config?: Record<string, any>;
    onUpdate?: (updates: any) => void;
    onDelete?: () => void;
  };
}>();

const colors = ['#fff9c4', '#c8e6c9', '#bbdefb', '#f8bbd0', '#e1bee7'];
const commentColor = ref(props.data.config?.color || colors[0]);
const localText = ref(props.data.sourceData || '');
const isSelected = ref(props.selected);

watch(localText, (val) => {
  if (props.data.onUpdate) {
    props.data.onUpdate({ sourceData: val });
  }
});

function changeColor(c: string) {
  commentColor.value = c;
  if (props.data.onUpdate) {
    props.data.onUpdate({ config: { ...(props.data.config || {}), color: c } });
  }
}

function handleDelete() {
  if (props.data.onDelete) props.data.onDelete();
}
</script>

<style scoped>
.comment-node {
  border-radius: 8px;
  padding: 8px;
  min-width: 160px;
  max-width: 260px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.2);
  position: relative;
  font-size: 13px;
}
.delete-btn {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 20px;
  height: 20px;
  background: #dc3545;
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}
.comment-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.comment-icon {
  font-size: 14px;
}
.comment-colors {
  display: flex;
  gap: 3px;
}
.color-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1px solid rgba(0,0,0,0.15);
  cursor: pointer;
}
.comment-text {
  width: 100%;
  background: transparent;
  border: none;
  color: #333;
  font-size: 13px;
  resize: none;
  outline: none;
  min-height: 40px;
  font-family: inherit;
}
</style>
