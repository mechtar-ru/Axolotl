<template>
  <Teleport to="body">
    <div v-if="visible" class="command-palette-overlay" @click.self="close" @keydown.escape="close" @keydown.arrow-down="focusNext" @keydown.arrow-up="focusPrev" @keydown.enter="selectFocused">
      <div class="command-palette">
        <div class="command-palette__input-wrap">
          <span class="command-palette__search-icon">🔍</span>
          <input
            ref="inputRef"
            v-model="query"
            class="command-palette__input"
            placeholder="Type a command or find a node..."
            autofocus
            @input="filterCommands"
          />
          <kbd class="command-palette__kbd">ESC</kbd>
        </div>
        <div class="command-palette__results" ref="resultsRef">
          <div
            v-for="(cmd, i) in filteredCommands"
            :key="cmd.id"
            class="command-palette__item"
            :class="{ focused: i === focusedIndex }"
            @click="executeCommand(cmd)"
          >
            <span class="command-palette__item-icon">{{ cmd.icon }}</span>
            <div class="command-palette__item-text">
              <span class="command-palette__item-title">{{ cmd.title }}</span>
              <span v-if="cmd.description" class="command-palette__item-desc">{{ cmd.description }}</span>
            </div>
            <kbd v-if="cmd.shortcut" class="command-palette__shortcut">{{ cmd.shortcut }}</kbd>
          </div>
          <div v-if="filteredCommands.length === 0" class="command-palette__empty">
            Nothing found
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';

const visible = defineModel<boolean>({ default: false });
const emit = defineEmits<{
  (e: 'execute', command: string): void;
}>();

const query = ref('');
const focusedIndex = ref(0);
const inputRef = ref<HTMLInputElement | null>(null);
const resultsRef = ref<HTMLElement | null>(null);

interface Command {
  id: string;
  icon: string;
  title: string;
  description?: string;
  shortcut?: string;
  action: string;
}

const allCommands = computed<Command[]>(() => [
  { id: 'new-schema', icon: '✨', title: 'New Schema', description: 'Create empty schema', shortcut: '⌘N', action: 'new-schema' },
  { id: 'execute', icon: '▶️', title: 'Execute Schema', description: 'Run the current schema', shortcut: '⌘↵', action: 'execute' },
  { id: 'save', icon: '💾', title: 'Save Schema', shortcut: '⌘S', action: 'save' },
  { id: 'plan', icon: '📋', title: 'Open Plan', action: 'toggle-plan' },
  { id: 'settings', icon: '⚙️', title: 'Settings', action: 'settings' },
  { id: 'export-mermaid', icon: '📊', title: 'Export Mermaid', action: 'export-mermaid' },
  { id: 'export-json', icon: '📦', title: 'Export JSON', action: 'export-json' },
  { id: 'import', icon: '📥', title: 'Import Schema', action: 'import' },
  { id: 'memory', icon: '🧠', title: 'Search MemPalace', action: 'memory-search' },
  { id: 'zoom-fit', icon: '🔍', title: 'Fit All', shortcut: '⌘0', action: 'zoom-fit' },
]);

const filteredCommands = ref<Command[]>(allCommands.value);

function filterCommands() {
  const q = query.value.toLowerCase().trim();
  if (!q) {
    filteredCommands.value = allCommands.value;
  } else {
    filteredCommands.value = allCommands.value.filter(cmd =>
      cmd.title.toLowerCase().includes(q) ||
      (cmd.description && cmd.description.toLowerCase().includes(q)) ||
      cmd.action.toLowerCase().includes(q)
    );
  }
  focusedIndex.value = 0;
}

function focusNext() {
  if (focusedIndex.value < filteredCommands.value.length - 1) {
    focusedIndex.value++;
    scrollToFocused();
  }
}

function focusPrev() {
  if (focusedIndex.value > 0) {
    focusedIndex.value--;
    scrollToFocused();
  }
}

async function scrollToFocused() {
  await nextTick();
  const el = resultsRef.value?.querySelector('.focused') as HTMLElement;
  el?.scrollIntoView({ block: 'nearest' });
}

function selectFocused() {
  const cmd = filteredCommands.value[focusedIndex.value];
  if (cmd) executeCommand(cmd);
}

function executeCommand(cmd: Command) {
  emit('execute', cmd.action);
  close();
}

function close() {
  visible.value = false;
  query.value = '';
  focusedIndex.value = 0;
}

function handleKeyDown(e: KeyboardEvent) {
  // Cmd+K or Ctrl+K to open
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault();
    visible.value = true;
    nextTick(() => inputRef.value?.focus());
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeyDown);
});

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown);
});
</script>

<style scoped>
.command-palette-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 20vh;
  z-index: 10000;
  backdrop-filter: blur(4px);
}

.command-palette {
  width: 560px;
  max-height: 400px;
  background: #1e1e2e;
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 12px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.command-palette__input-wrap {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  gap: 8px;
}

.command-palette__search-icon {
  font-size: 16px;
  opacity: 0.5;
}

.command-palette__input {
  flex: 1;
  background: transparent;
  border: none;
  color: #e0e0e0;
  font-size: 16px;
  outline: none;
}

.command-palette__input::placeholder {
  color: #666;
}

.command-palette__kbd {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 10px;
  color: #888;
  font-family: inherit;
}

.command-palette__results {
  max-height: 340px;
  overflow-y: auto;
  padding: 4px;
}

.command-palette__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.command-palette__item:hover,
.command-palette__item.focused {
  background: rgba(108, 99, 255, 0.15);
}

.command-palette__item-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.command-palette__item-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.command-palette__item-title {
  font-size: 14px;
  color: #e0e0e0;
  font-weight: 500;
}

.command-palette__item-desc {
  font-size: 11px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.command-palette__shortcut {
  margin-left: auto;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 11px;
  color: #888;
  font-family: inherit;
  flex-shrink: 0;
}

.command-palette__empty {
  padding: 24px;
  text-align: center;
  color: #666;
  font-size: 14px;
}
</style>
