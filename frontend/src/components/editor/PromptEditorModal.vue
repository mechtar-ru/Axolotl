<template>
  <div v-if="visible" class="prompt-modal-overlay" @click.self="close" role="dialog" aria-modal="true" aria-labelledby="prompt-editor-title">
    <div ref="modalEl" class="prompt-modal" @keydown.esc="close" @keydown.tab="onTab">
      <div class="prompt-modal-header">
        <span id="prompt-editor-title">Prompt Editor — {{ nodeName }}</span>
        <div class="prompt-modal-actions">
          <button class="template-btn" @click="showTemplates = !showTemplates">Templates</button>
          <button class="close-btn" @click="close" aria-label="Close"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
        </div>
      </div>
      </div>

      <div v-if="showTemplates" class="templates-panel">
        <button
          v-for="tpl in templates"
          :key="tpl.name"
          class="template-item"
          @click="insertTemplate(tpl.text)"
        >
          <SvgIcon :svg="tpl.emoji" /> {{ tpl.name }}
        </button>
      </div>

      <div class="prompt-modal-body">
        <div v-if="systemPrompt !== undefined" class="field-group">
          <label>System Prompt</label>
          <textarea
            v-model="localSystemPrompt"
            class="prompt-textarea"
            placeholder="Define the agent's role and behavior..."
            rows="6"
          />
        </div>
        <div class="field-group">
          <label>User Prompt</label>
          <textarea
            ref="promptArea"
            v-model="localPrompt"
            class="prompt-textarea main-prompt"
            placeholder="Enter prompt... Available: {{input}}, {{prev_result}}, {{node:node_name}}"
            rows="14"
          />
        </div>
<div class="variables-hint">
          <span class="hint-label">Variables:</span>
          <button class="var-btn" @click="insertVar('{{input}}')" v-text="'{{input}}'"></button>
          <button class="var-btn" @click="insertVar('{{prev_result}}')" v-text="'{{prev_result}}'"></button>
          <button class="var-btn" @click="insertVar('{{node:name}}')" v-text="'{{node:...}}'"></button>
        </div>
      </div>

      <div class="prompt-modal-footer">
        <span class="char-count">{{ localPrompt.length }} chars</span>
        <button class="save-btn" @click="save">Save</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';

const templates = [
  { name: 'Analysis', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>', text: 'Analyze the following data and extract key points:\n\n{{input}}' },
  { name: 'Summarization', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/></svg>', text: 'Make a brief summary of the following text:\n\n{{input}}' },
  { name: 'Translation', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>', text: 'Translate the following text, preserving style and tone:\n\n{{input}}' },
  { name: 'Extraction', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>', text: 'Extract all key entities (names, dates, amounts, organizations):\n\n{{input}}' },
  { name: 'Generation', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M12 3l1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5L12 3z"/><path d="M18 15c-1.5 1-3 1.5-5 1.5s-3.5-.5-5-1.5"/></svg>', text: 'Generate a detailed response based on the following data:\n\n{{input}}' },
  { name: 'Code Review', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>', text: 'Review the following code for bugs, vulnerabilities, and improvements:\n\n{{input}}' },
  { name: 'Comparison', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><line x1="12" y1="2" x2="12" y2="22"/><path d="M3 8c1.5 0 3-1 3-3"/><path d="M21 8c-1.5 0-3-1-3-3"/><line x1="3" y1="12" x2="21" y2="12"/></svg>', text: 'Compare data from two sources and find similarities and differences:\n\nSource 1: {{input}}\nSource 2: {{prev_result}}' },
  { name: 'FAQ', emoji: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>', text: 'Answer the question based on the provided context. If the answer is not in the context, say so.\n\nContext: {{input}}\nQuestion: ' },
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
const modalEl = ref<HTMLElement | null>(null);
let previousFocus: HTMLElement | null = null;

watch(() => props.visible, (v) => {
  if (v) {
    localPrompt.value = props.userPrompt || '';
    localSystemPrompt.value = props.systemPrompt || '';
    previousFocus = document.activeElement as HTMLElement;
    nextTick(() => {
      modalEl.value?.querySelector<HTMLElement>('textarea, button:not([disabled])')?.focus();
    });
    document.addEventListener('keydown', onKey);
  } else {
    document.removeEventListener('keydown', onKey);
    previousFocus?.focus();
  }
});

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') close();
}

function onTab(e: KeyboardEvent) {
  if (!props.visible) return;
  const focusableElements = modalEl.value?.querySelectorAll<HTMLElement>(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  );
  if (!focusableElements?.length) return;
  const firstElement = focusableElements[0];
  const lastElement = focusableElements[focusableElements.length - 1];
  if (!firstElement || !lastElement) return;
  if (e.shiftKey && document.activeElement === firstElement) {
    e.preventDefault();
    lastElement.focus();
  } else if (!e.shiftKey && document.activeElement === lastElement) {
    e.preventDefault();
    firstElement.focus();
  }
}

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
  background: rgba(0,0,0,0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 3000;
}
.prompt-modal {
  background: #1e1e2e;
  border-radius: 16px;
  width: 700px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
  border: 1px solid rgba(255,255,255,0.1);
}
.prompt-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  color: #eee;
  font-weight: 600;
  font-size: 15px;
}
.prompt-modal-actions {
  display: flex;
  gap: 8px;
}
.template-btn {
  background: #6c63ff;
  border: none;
  color: white;
  padding: 6px 14px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.close-btn {
  background: rgba(255,255,255,0.1);
  border: none;
  color: #eee;
  width: 30px;
  height: 30px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}
.templates-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 20px;
  background: rgba(0,0,0,0.2);
  border-bottom: 1px solid rgba(255,255,255,0.05);
}
.template-item {
  background: rgba(108, 99, 255, 0.15);
  border: 1px solid rgba(108, 99, 255, 0.3);
  color: #c8c0ff;
  padding: 6px 12px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
}
.template-item:hover {
  background: rgba(108, 99, 255, 0.3);
}
.prompt-modal-body {
  flex: 1;
  padding: 16px 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.field-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field-group label {
  font-size: 12px;
  color: #888;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.prompt-textarea {
  width: 100%;
  background: #13131f;
  border: 1px solid #3a3a5a;
  color: #eee;
  border-radius: 8px;
  padding: 12px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 14px;
  line-height: 1.6;
  resize: vertical;
  outline: none;
}
.prompt-textarea:focus {
  border-color: #6c63ff;
}
.main-prompt {
  flex: 1;
}
.variables-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.hint-label {
  font-size: 11px;
  color: #666;
}
.var-btn {
  background: rgba(108, 99, 255, 0.1);
  border: 1px solid rgba(108, 99, 255, 0.2);
  color: #9e96ff;
  padding: 3px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
  font-family: var(--font-mono);
}
.var-btn:hover {
  background: rgba(108, 99, 255, 0.25);
}
.prompt-modal-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-top: 1px solid rgba(255,255,255,0.08);
}
.char-count {
  font-size: 12px;
  color: #666;
}
.save-btn {
  background: #4f7cff;
  border: none;
  color: white;
  padding: 8px 24px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}
.save-btn:hover {
  background: #3d6bef;
}
</style>
