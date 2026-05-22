<template>
  <div v-if="visible" class="template-gallery">
    <div class="gallery-header">
      <span class="gallery-title">Schema Templates</span>
      <button class="close-btn" @click="$emit('close')">✕</button>
    </div>

    <div v-if="loading" class="gallery-loading">Loading templates...</div>

    <div v-else-if="error" class="gallery-error">
      {{ error }}
      <button @click="loadTemplates">Retry</button>
    </div>

    <div v-else class="gallery-grid">
      <div
        v-for="t in templates"
        :key="t.id"
        class="template-card"
        @click="selectTemplate(t)"
      >
        <div class="template-icon">{{ t.icon }}</div>
        <div class="template-info">
          <div class="template-name">{{ t.name }}</div>
          <div class="template-desc">{{ t.description }}</div>
          <div class="template-meta">
            {{ (t.nodes || []).length }} nodes · {{ (t.edges || []).length }} connections
          </div>
        </div>
        <button class="use-btn" @click.stop="selectTemplate(t)">Use</button>
      </div>
    </div>

    <!-- Variables form -->
    <div v-if="selectedTemplate?.variables?.length" class="variables-form">
      <div class="var-header">Configure: {{ selectedTemplate.name }}</div>
      <div v-for="v in selectedTemplate.variables" :key="v.name" class="var-field">
        <label class="var-label">{{ v.name }} <span v-if="v.required">*</span></label>
        <div class="var-desc">{{ v.description }}</div>
        <textarea
          v-if="v.name === 'features'"
          v-model="variables[v.name]"
          :placeholder="'Describe features to plan...'"
          rows="4"
          class="var-input"
        />
        <input
          v-else
          v-model="variables[v.name]"
          :placeholder="v.description"
          class="var-input"
        />
      </div>
      <button class="create-btn" @click="createFromTemplate" :disabled="!allRequiredFilled">
        Create Schema
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { api } from '../../services/api';

interface TemplateVariable {
  name: string;
  description: string;
  required: boolean;
  nodeId?: string;
  field?: string;
}

interface Template {
  id: string;
  name: string;
  description: string;
  icon: string;
  nodes: any[];
  edges: any[];
  variables?: TemplateVariable[];
}

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'create', schema: any): void;
}>();

const loading = ref(false);
const error = ref('');
const templates = ref<Template[]>([]);
const selectedTemplate = ref<Template | null>(null);
const variables = ref<Record<string, string>>({});

watch(() => props.visible, (v) => {
  if (v && templates.value.length === 0) loadTemplates();
  if (!v) {
    selectedTemplate.value = null;
    variables.value = {};
  }
});

async function loadTemplates() {
  loading.value = true;
  error.value = '';
  try {
    const { data } = await api.get('/templates');
    templates.value = data;
  } catch (e: any) {
    error.value = 'Failed to load templates';
  } finally {
    loading.value = false;
  }
}

function selectTemplate(t: Template) {
  selectedTemplate.value = t;
  variables.value = {};
  for (const v of (t.variables || [])) {
    variables.value[v.name] = '';
  }
}

const allRequiredFilled = computed(() => {
  if (!selectedTemplate.value?.variables) return true;
  return selectedTemplate.value.variables
    .filter(v => v.required)
    .every(v => variables.value[v.name]?.trim());
});

function createFromTemplate() {
  if (!selectedTemplate.value) return;
  const t = selectedTemplate.value;

  const nodes = t.nodes.map((n: any) => {
    const node = { ...n, data: { ...(n.data || {}) } };

    // Apply variables to nodes
    if (t.variables) {
      for (const v of t.variables) {
        if (v.nodeId === node.id && v.field && variables.value[v.name]) {
          const parts = v.field.split('.');
          if (parts.length === 2) {
            const key = parts[0] as string;
            const subKey = parts[1] as string;
            node.data[key] = { ...(node.data[key] as any || {}), [subKey]: variables.value[v.name] };
          } else if (v.field === 'userPrompt') {
            // Replace {{features}} placeholder
            node.data.userPrompt = node.data.userPrompt?.replace(
              /\{\{features\}\}/g, variables.value[v.name]
            );
          } else {
            (node.data as any)[v.field] = variables.value[v.name];
          }
        }
      }
    }

    return node;
  });

  emit('create', {
    name: t.name,
    description: t.description,
    nodes,
    edges: t.edges,
    version: '1.0',
  });

  selectedTemplate.value = null;
  variables.value = {};
}
</script>

<style scoped>
.template-gallery {
  width: 100%;
  height: 100%;
  background: var(--bg-primary);
  overflow-y: auto;
  color: var(--text-primary);
  display: flex;
  flex-direction: column;
}
.gallery-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: var(--space-4) var(--space-5); border-bottom: 1px solid var(--border-color);
}
.gallery-title { font-size: var(--text-base); font-weight: 600; }
.close-btn { background: none; border: none; color: var(--text-muted); font-size: var(--text-lg); cursor: pointer; }
.close-btn:hover { color: var(--text-primary); }

.gallery-loading, .gallery-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8);
  text-align: center;
  color: var(--text-muted);
}

.gallery-loading::before {
  content: '';
  width: 16px;
  height: 16px;
  border: 2px solid var(--text-muted);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  display: inline-block;
}
@keyframes spin { to { transform: rotate(360deg); } }

.gallery-grid { padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-2); }
.template-card {
  display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3);
  border: 1px solid var(--border-color); border-radius: var(--radius-sm); cursor: pointer;
  transition: background var(--transition);
}
.template-card:hover { background: var(--bg-hover); border-color: var(--accent); }
.template-icon { font-size: 28px; }
.template-info { flex: 1; }
.template-name { font-size: var(--text-sm); font-weight: 600; }
.template-desc { font-size: var(--text-xs); color: var(--text-secondary); margin-top: 2px; }
.template-meta { font-size: 11px; color: var(--text-muted); margin-top: var(--space-1); }
.use-btn {
  background: var(--accent); border: none; color: white; padding: 6px 14px;
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-xs);
}
.use-btn:hover { background: var(--accent-hover); }

.variables-form {
  padding: var(--space-4); border-top: 1px solid var(--border-color);
}
.var-header { font-size: var(--text-sm); font-weight: 600; margin-bottom: var(--space-3); }
.var-field { margin-bottom: var(--space-2); }
.var-label { font-size: var(--text-xs); font-weight: 600; color: var(--accent); }
.var-label span { color: var(--error); }
.var-desc { font-size: 11px; color: var(--text-muted); margin-bottom: var(--space-1); }
.var-input {
  width: 100%; background: var(--bg-input); border: 1px solid var(--border-color); color: var(--text-primary);
  border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); font-size: var(--text-sm); box-sizing: border-box;
  font-family: var(--font-sans);
}
.var-input:focus { border-color: var(--accent); outline: none; }
.create-btn {
  width: 100%; background: var(--accent); border: none; color: white; padding: var(--space-2);
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-sm); margin-top: var(--space-2);
}
.create-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.create-btn:not(:disabled):hover { background: var(--accent-hover); }
</style>
