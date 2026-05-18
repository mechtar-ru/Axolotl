<template>
  <AppModal v-model="visible" title="Keyboard Shortcuts">
    <div class="shortcuts-list">
      <div v-for="group in groups" :key="group.title" class="shortcut-group">
        <h4>{{ group.title }}</h4>
        <div v-for="s in group.items" :key="s.key" class="shortcut-row">
          <kbd>{{ s.key }}</kbd>
          <span>{{ s.label }}</span>
        </div>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import AppModal from './AppModal.vue';

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

const isMac = typeof navigator !== 'undefined' && /Mac/.test(navigator.userAgent);
const mod = isMac ? '⌘' : 'Ctrl';

const groups = [
  {
    title: 'General',
    items: [
      { key: `${mod}+K`, label: 'Command Palette' },
      { key: `${mod}+S`, label: 'Save Schema' },
      { key: `${mod}+N`, label: 'New Schema' },
      { key: '?', label: 'Show Keyboard Shortcuts' },
      { key: 'Escape', label: 'Close Panel / Modal' },
    ],
  },
  {
    title: 'Editor',
    items: [
      { key: `${mod}+F`, label: 'Search Nodes' },
      { key: `${mod}+E`, label: 'Open Prompt Editor' },
      { key: 'Ctrl+G', label: 'Group Nodes' },
      { key: 'Delete', label: 'Delete Selected Node' },
    ],
  },
  {
    title: 'Execution',
    items: [
      { key: `${mod}+Enter`, label: 'Run Schema' },
      { key: `${mod}.`, label: 'Stop Execution' },
    ],
  },
  {
    title: 'Electron',
    items: [
      { key: `${mod}+Shift+A`, label: 'Show/Hide Window' },
    ],
  },
];
</script>

<style scoped>
.shortcuts-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.shortcut-group h4 {
  margin: 0 0 8px;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted, #666);
}
.shortcut-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
}
kbd {
  display: inline-block;
  background: var(--bg-primary, #13131f);
  border: 1px solid var(--border, #4a4a6a);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 12px;
  font-family: var(--font-mono, monospace);
  color: var(--text-primary, #eee);
  min-width: 80px;
  text-align: center;
}
.shortcut-row span {
  font-size: 13px;
  color: var(--text-secondary, #aaa);
}
</style>
