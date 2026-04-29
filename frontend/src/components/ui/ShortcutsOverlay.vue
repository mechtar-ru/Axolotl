<template>
  <AppModal v-model="visible" title="⌨️ Горячие клавиши">
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
    title: 'Общие',
    items: [
      { key: `${mod}+K`, label: 'Палитра команд' },
      { key: `${mod}+S`, label: 'Сохранить схему' },
      { key: `${mod}+N`, label: 'Новая схема' },
      { key: '?', label: 'Показать горячие клавиши' },
      { key: 'Escape', label: 'Закрыть панель/модальное окно' },
    ],
  },
  {
    title: 'Редактор',
    items: [
      { key: `${mod}+F`, label: 'Поиск узлов' },
      { key: `${mod}+E`, label: 'Открыть редактор промпта' },
      { key: 'Ctrl+G', label: 'Группировать узлы' },
      { key: 'Delete', label: 'Удалить выбранный узел' },
    ],
  },
  {
    title: 'Выполнение',
    items: [
      { key: `${mod}+Enter`, label: 'Запустить схему' },
      { key: `${mod}.`, label: 'Остановить выполнение' },
    ],
  },
  {
    title: 'Electron',
    items: [
      { key: `${mod}+Shift+A`, label: 'Показать/скрыть окно' },
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
  color: var(--text-muted);
}
.shortcut-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
}
kbd {
  display: inline-block;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 12px;
  font-family: var(--font-mono, monospace);
  color: var(--text-primary);
  min-width: 80px;
  text-align: center;
}
.shortcut-row span {
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
