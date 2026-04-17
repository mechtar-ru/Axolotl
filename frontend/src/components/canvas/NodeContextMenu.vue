<template>
  <Teleport to="body">
    <Transition name="ctx">
      <div
        v-if="visible"
        class="ctx-menu"
        :style="{ top: `${y}px`, left: `${x}px` }"
        @click.stop
      >
        <button class="ctx-item" @click="$emit('rename')">
          ✏️ Переименовать
        </button>
        <button class="ctx-item" @click="$emit('duplicate')">
          📋 Дублировать
        </button>
        <button class="ctx-item" @click="$emit('toggleCollapse')">
          {{ collapsed ? '▼ Развернуть' : '▶ Свернуть' }}
        </button>
        <button v-if="canEditPrompt" class="ctx-item" @click="$emit('editPrompt')">
          ✏️ Редактор промпта
        </button>
        <div class="ctx-separator"></div>
        <button class="ctx-item ctx-item-danger" @click="$emit('delete')">
          🗑 Удалить
        </button>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  visible: boolean;
  x: number;
  y: number;
  collapsed?: boolean;
  canEditPrompt?: boolean;
}>();

defineEmits<{
  (e: 'rename'): void;
  (e: 'duplicate'): void;
  (e: 'delete'): void;
  (e: 'toggleCollapse'): void;
  (e: 'editPrompt'): void;
}>();
</script>

<style scoped>
.ctx-menu {
  position: fixed;
  background: var(--bg-card, #1e1e2e);
  border: 1px solid var(--border, #4a4a6a);
  border-radius: var(--radius-sm, 8px);
  box-shadow: var(--shadow-md, 0 8px 30px rgba(0, 0, 0, 0.35));
  padding: 4px;
  min-width: 180px;
  z-index: 2000;
}

.ctx-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  background: none;
  border: none;
  color: var(--text-primary, #eee);
  cursor: pointer;
  border-radius: 4px;
  font-size: 13px;
  text-align: left;
}

.ctx-item:hover {
  background: var(--bg-hover, #3d3d5c);
}

.ctx-item-danger {
  color: var(--error, #dc3545);
}

.ctx-item-danger:hover {
  background: rgba(220, 53, 69, 0.15);
}

.ctx-separator {
  height: 1px;
  background: var(--border, #4a4a6a);
  margin: 4px 0;
}

.ctx-enter-active,
.ctx-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.ctx-enter-from,
.ctx-leave-to {
  opacity: 0;
  transform: scale(0.95);
}
</style>
