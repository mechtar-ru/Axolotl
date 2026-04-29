<template>
  <div v-if="visible" class="prompt-modal-overlay" @click.self="close">
    <div class="prompt-modal">
      <div class="prompt-modal-header">
        <span>Редактор промпта — {{ nodeName }}</span>
        <div class="prompt-modal-actions">
          <button class="template-btn" @click="showTemplates = !showTemplates">Шаблоны</button>
          <button class="close-btn" @click="close">✕</button>
        </div>
      </div>

      <div v-if="showTemplates" class="templates-panel">
        <button
          v-for="tpl in templates"
          :key="tpl.name"
          class="template-item"
          @click="insertTemplate(tpl.text)"
        >
          {{ tpl.emoji }} {{ tpl.name }}
        </button>
      </div>

      <div class="prompt-modal-body">
        <div v-if="systemPrompt !== undefined" class="field-group">
          <label>Системный промпт</label>
          <textarea
            v-model="localSystemPrompt"
            class="prompt-textarea"
            placeholder="Определи роль и поведение агента..."
            rows="6"
          />
        </div>
        <div class="field-group">
          <label>Пользовательский промпт</label>
          <textarea
            ref="promptArea"
            v-model="localPrompt"
            class="prompt-textarea main-prompt"
            placeholder="Введите промпт... Доступны переменные: {{input}}, {{prev_result}}, {{node:имя_узла}}"
            rows="14"
          />
        </div>
        <div class="variables-hint">
          <span class="hint-label">Переменные:</span>
          <button class="var-btn" @click="insertVar('\{\{input\}\}')">&#123;&#123;input&#125;&#125;</button>
          <button class="var-btn" @click="insertVar('\{\{prev_result\}\}')">&#123;&#123;prev_result&#125;&#125;</button>
          <button class="var-btn" @click="insertVar('\{\{node:имя\}\}')">&#123;&#123;node:...&#125;&#125;</button>
        </div>
      </div>

      <div class="prompt-modal-footer">
        <span class="char-count">{{ localPrompt.length }} символов</span>
        <button class="save-btn" @click="save">Сохранить</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';

const templates = [
  { name: 'Анализ', emoji: '🔍', text: 'Проанализируй следующие данные и выдели ключевые моменты:\n\n{{input}}' },
  { name: 'Суммаризация', emoji: '📋', text: 'Сделай краткое резюме следующего текста на русском языке:\n\n{{input}}' },
  { name: 'Перевод', emoji: '🌍', text: 'Переведи следующий текст на русский язык, сохранив стиль и тон оригинала:\n\n{{input}}' },
  { name: 'Извлечение', emoji: '🎯', text: 'Извлеки из текста все ключевые сущности (имена, даты, суммы, организации):\n\n{{input}}' },
  { name: 'Генерация', emoji: '✨', text: 'На основе следующих данных сгенерируй подробный ответ:\n\n{{input}}' },
  { name: 'Код-ревью', emoji: '💻', text: 'Проверь следующий код на ошибки, уязвимости и предложи улучшения:\n\n{{input}}' },
  { name: 'Сравнение', emoji: '⚖️', text: 'Сравни данные из двух источников и найди сходства и различия:\n\nИсточник 1: {{input}}\nИсточник 2: {{prev_result}}' },
  { name: 'FAQ', emoji: '❓', text: 'Ответь на вопрос, основываясь на предоставленном контексте. Если ответа нет в контексте, так и скажи.\n\nКонтекст: {{input}}\nВопрос: ' },
];

const props = defineProps<{
  visible: boolean;
  nodeName: string;
  userPrompt?: string;
  systemPrompt?: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'save', data: { userPrompt: string; systemPrompt?: string }): void;
}>();

const localPrompt = ref(props.userPrompt || '');
const localSystemPrompt = ref(props.systemPrompt || '');
const showTemplates = ref(false);
const promptArea = ref<HTMLTextAreaElement | null>(null);

watch(() => props.visible, (v) => {
  if (v) {
    localPrompt.value = props.userPrompt || '';
    localSystemPrompt.value = props.systemPrompt || '';
  }
});

function close() {
  emit('close');
}

function save() {
  emit('save', {
    userPrompt: localPrompt.value,
    systemPrompt: props.systemPrompt !== undefined ? localSystemPrompt.value : undefined,
  });
}

function insertTemplate(text: string) {
  localPrompt.value = text;
  showTemplates.value = false;
}

function insertVar(variable: string) {
  if (!promptArea.value) {
    localPrompt.value += variable;
    return;
  }
  const ta = promptArea.value;
  const start = ta.selectionStart;
  const end = ta.selectionEnd;
  localPrompt.value = localPrompt.value.substring(0, start) + variable + localPrompt.value.substring(end);
}
</script>

<style scoped>
.prompt-modal-overlay {
  position: fixed;
  inset: 0;
  background: var(--overlay-heavy);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: var(--z-tooltip);
}
.prompt-modal {
  background: var(--bg-secondary);
  border-radius: var(--radius-lg);
  width: 700px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-subtle);
}
.prompt-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-primary);
  font-weight: 600;
  font-size: var(--text-base);
}
.prompt-modal-actions {
  display: flex;
  gap: var(--space-2);
}
.template-btn {
  background: var(--accent);
  border: none;
  color: var(--text-inverse);
  padding: 6px 14px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-base);
}
.close-btn {
  background: rgba(255,255,255,0.1);
  border: none;
  color: var(--text-primary);
  width: 30px;
  height: 30px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-md);
}
.templates-panel {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1-5);
  padding: var(--space-3) var(--space-5);
  background: rgba(0,0,0,0.2);
  border-bottom: 1px solid var(--border-subtle);
}
.template-item {
  background: var(--accent-light);
  border: 1px solid var(--accent-border, rgba(108, 99, 255, 0.3));
  color: var(--violet-light);
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-sm);
  white-space: nowrap;
}
.template-item:hover {
  background: rgba(108, 99, 255, 0.3);
}
.prompt-modal-body {
  flex: 1;
  padding: var(--space-4) var(--space-5);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.field-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.field-group label {
  font-size: var(--text-sm);
  color: var(--text-muted);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.prompt-textarea {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--text-md);
  line-height: 1.6;
  resize: vertical;
  outline: none;
}
.prompt-textarea:focus {
  border-color: var(--accent);
}
.main-prompt {
  flex: 1;
}
.variables-hint {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  flex-wrap: wrap;
}
.hint-label {
  font-size: var(--text-xs);
  color: var(--text-muted);
}
.var-btn {
  background: var(--accent-light);
  border: 1px solid var(--accent-border, rgba(108, 99, 255, 0.2));
  color: var(--accent-hover);
  padding: 3px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: var(--text-xs);
  font-family: monospace;
}
.var-btn:hover {
  background: rgba(108, 99, 255, 0.25);
}
.prompt-modal-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--border-subtle);
}
.char-count {
  font-size: var(--text-sm);
  color: var(--text-muted);
}
.save-btn {
  background: var(--info);
  border: none;
  color: var(--text-inverse);
  padding: var(--space-2) var(--space-6);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--text-md);
  font-weight: 600;
}
.save-btn:hover {
  background: var(--accent-blue);
}
</style>
