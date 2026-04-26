<template>
  <div v-if="visible" class="template-gallery-overlay" @click.self="$emit('close')">
    <div class="template-gallery">
      <div class="gallery-header">
        <span class="gallery-title">Workflow Templates</span>
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
          Create Workflow
        </button>
      </div>
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
            node.data[parts[0]] = { ...(node.data[parts[0]] as any || {}), [parts[1]]: variables.value[v.name] };
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
.template-gallery-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center;
  z-index: 1000;
}
.template-gallery {
  background: #1a1a2e; border: 1px solid #333; border-radius: 12px;
  width: 600px; max-height: 80vh; overflow-y: auto; color: #e0e0e0;
}
.gallery-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid #333;
}
.gallery-title { font-size: 16px; font-weight: 600; }
.close-btn { background: none; border: none; color: #888; font-size: 18px; cursor: pointer; }
.close-btn:hover { color: #fff; }

.gallery-loading, .gallery-error {
  padding: 40px; text-align: center; color: #888;
}

.gallery-grid { padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.template-card {
  display: flex; align-items: center; gap: 14px; padding: 14px;
  border: 1px solid #333; border-radius: 8px; cursor: pointer;
  transition: background 0.2s;
}
.template-card:hover { background: rgba(255,255,255,0.05); border-color: #00bcd4; }
.template-icon { font-size: 28px; }
.template-info { flex: 1; }
.template-name { font-size: 14px; font-weight: 600; }
.template-desc { font-size: 12px; color: #aaa; margin-top: 2px; }
.template-meta { font-size: 11px; color: #666; margin-top: 4px; }
.use-btn {
  background: #00bcd4; border: none; color: white; padding: 6px 14px;
  border-radius: 6px; cursor: pointer; font-size: 12px;
}
.use-btn:hover { background: #0097a7; }

.variables-form {
  padding: 16px; border-top: 1px solid #333;
}
.var-header { font-size: 14px; font-weight: 600; margin-bottom: 12px; }
.var-field { margin-bottom: 10px; }
.var-label { font-size: 12px; font-weight: 600; color: #00bcd4; }
.var-label span { color: #ff5252; }
.var-desc { font-size: 11px; color: #888; margin-bottom: 4px; }
.var-input {
  width: 100%; background: #12121e; border: 1px solid #333; color: #e0e0e0;
  border-radius: 6px; padding: 8px 10px; font-size: 13px; box-sizing: border-box;
  font-family: inherit;
}
.var-input:focus { border-color: #00bcd4; outline: none; }
.create-btn {
  width: 100%; background: #00bcd4; border: none; color: white; padding: 10px;
  border-radius: 6px; cursor: pointer; font-size: 14px; margin-top: 8px;
}
.create-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.create-btn:not(:disabled):hover { background: #0097a7; }
</style>
