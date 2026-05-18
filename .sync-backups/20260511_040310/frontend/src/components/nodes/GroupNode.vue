<template>
  <div class="group-node" :class="{ collapsed: props.data.collapsed }">
    <div class="group-header">
      <span class="group-icon">📦</span>
      <span v-if="!editingName" class="group-name" @dblclick="startEditName">
        {{ props.data.name }}
      </span>
      <input
        v-else
        ref="nameInput"
        v-model="localName"
        class="group-name-input"
        @blur="finishEditName"
        @keyup.enter="finishEditName"
      />
      <button class="group-toggle" @click="toggleCollapse">
        {{ props.data.collapsed ? '▶' : '▼' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue';

const props = defineProps<{
  id: string;
  selected?: boolean;
  data: {
    name: string;
    collapsed?: boolean;
    onUpdate?: (updates: any) => void;
    onRename?: (name: string) => void;
  };
}>();

const editingName = ref(false);
const localName = ref(props.data.name);
const nameInput = ref<HTMLInputElement | null>(null);

function startEditName() {
  editingName.value = true;
  nextTick(() => nameInput.value?.focus());
}

function finishEditName() {
  if (localName.value.trim() && props.data.onRename) {
    props.data.onRename(localName.value.trim());
  } else {
    localName.value = props.data.name;
  }
  editingName.value = false;
}

function toggleCollapse() {
  if (props.data.onUpdate) {
    props.data.onUpdate({ collapsed: !props.data.collapsed });
  }
}
</script>

<style scoped>
.group-node {
  background: rgba(108, 99, 255, 0.08);
  border: 2px dashed rgba(108, 99, 255, 0.4);
  border-radius: 12px;
  min-width: 300px;
  min-height: 200px;
  padding: 4px;
}
.group-node.collapsed {
  min-height: auto;
  min-width: auto;
}
.group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  background: rgba(108, 99, 255, 0.15);
  border-radius: 8px;
}
.group-icon {
  font-size: 14px;
}
.group-name {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: #b8b0ff;
  cursor: pointer;
}
.group-name-input {
  flex: 1;
  background: rgba(0,0,0,0.3);
  border: 1px solid #6c63ff;
  color: #eee;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 12px;
  font-weight: 600;
}
.group-toggle {
  background: none;
  border: none;
  color: #b8b0ff;
  cursor: pointer;
  font-size: 11px;
  padding: 2px;
}
</style>
