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
  background: var(--accent-light);
  border: 2px dashed rgba(108, 99, 255, 0.4);
  border-radius: var(--radius-md);
  min-width: 300px;
  min-height: 200px;
  padding: var(--space-1);
}
.group-node.collapsed {
  min-height: auto;
  min-width: auto;
}
.group-header {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  padding: 4px 8px;
  background: rgba(108, 99, 255, 0.15);
  border-radius: var(--radius-sm);
}
.group-icon {
  font-size: 14px;
}
.group-name {
  flex: 1;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--violet-light);
  cursor: pointer;
}
.group-name-input {
  flex: 1;
  background: var(--overlay);
  border: 1px solid var(--accent);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: 2px 6px;
  font-size: var(--text-sm);
  font-weight: 600;
}
.group-toggle {
  background: none;
  border: none;
  color: var(--violet-light);
  cursor: pointer;
  font-size: var(--text-xs);
  padding: 2px;
}
</style>
