<template>
  <div v-if="visible" class="template-gallery-overlay" @click.self="$emit('close')">
    <div class="template-gallery">
      <div class="gallery-header">
        <span class="gallery-title">Workflow Templates</span>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- Search and Filter -->
      <div class="gallery-filters">
        <input
          v-model="searchQuery"
          placeholder="Search templates..."
          class="search-input"
        />
        <select v-model="categoryFilter" class="category-select">
          <option value="">All Categories</option>
          <option value="api">API Integration</option>
          <option value="data">Data Processing</option>
          <option value="ai">AI/LLM</option>
          <option value="automation">Automation</option>
          <option value="analysis">Analysis</option>
        </select>
        <select v-model="sortBy" class="sort-select">
          <option value="name">Sort by Name</option>
          <option value="nodes">Most Nodes</option>
          <option value="recent">Recently Used</option>
        </select>
      </div>

      <!-- Popular / Recent Tabs -->
      <div class="gallery-tabs">
        <button
          class="tab-btn"
          :class="{ active: viewTab === 'all' }"
          @click="viewTab = 'all'"
        >
          All Templates
        </button>
        <button
          class="tab-btn"
          :class="{ active: viewTab === 'popular' }"
          @click="viewTab = 'popular'"
        >
          🔥 Popular
        </button>
        <button
          class="tab-btn"
          :class="{ active: viewTab === 'recent' }"
          @click="viewTab = 'recent'"
        >
          🕐 Recent
        </button>
      </div>

      <div v-if="loading" class="gallery-loading">Loading templates...</div>

      <div v-else-if="error" class="gallery-error">
        {{ error }}
        <button @click="loadTemplates">Retry</button>
      </div>

      <div v-else class="gallery-grid">
        <div v-if="filteredTemplates.length === 0" class="gallery-empty">
          No templates found. Try a different search or filter.
        </div>
        <div
          v-else
          v-for="t in filteredTemplates"
          :key="t.id"
          class="template-card"
          :class="{ 'popular': popularIds.includes(t.id), 'recent': recentTemplateIds.includes(t.id) }"
          @click="selectTemplate(t)"
        >
          <div class="template-icon">{{ t.icon }}</div>
          <div class="template-info">
            <div class="template-name">{{ t.name }}</div>
            <div class="template-desc">{{ t.description }}</div>
            <div class="template-meta">
              <span v-if="t.category" class="template-category">{{ t.category }}</span>
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
  category?: string;
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
const searchQuery = ref('');
const categoryFilter = ref('');
const sortBy = ref('name');
const viewTab = ref<'all' | 'popular' | 'recent'>('all');

// Recently used from localStorage
const recentTemplateIds = ref<string[]>([]);

// Initialize from localStorage on mount
function initRecentTemplates() {
  try {
    recentTemplateIds.value = JSON.parse(localStorage.getItem('recentTemplates') || '[]');
  } catch { recentTemplateIds.value = []; }
}

// Popular templates (hardcoded for now)
const popularIds = ref<string[]>(['agent-basic', 'api-pipeline', 'data-pipeline', 'rag']);

const filteredTemplates = computed(() => {
  let result = templates.value;

  // Filter by view tab
  if (viewTab.value === 'popular') {
    result = result.filter(t => popularIds.value.includes(t.id));
  } else if (viewTab.value === 'recent') {
    result = result.filter(t => recentTemplateIds.value.includes(t.id));
  }

  // Filter by search query
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase();
    result = result.filter(t =>
      t.name.toLowerCase().includes(q) ||
      t.description.toLowerCase().includes(q)
    );
  }

  // Filter by category
  if (categoryFilter.value) {
    result = result.filter(t => t.category === categoryFilter.value);
  }

  // Sort
  if (sortBy.value === 'name') {
    result = [...result].sort((a, b) => a.name.localeCompare(b.name));
  } else if (sortBy.value === 'nodes') {
    result = [...result].sort((a, b) => (b.nodes?.length || 0) - (a.nodes?.length || 0));
  }

  return result;
});

watch(() => props.visible, (v) => {
  if (v) {
    initRecentTemplates();
    if (templates.value.length === 0) loadTemplates();
  }
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

  // Save to recent templates
  const recent = recentTemplateIds.value.filter(id => id !== t.id);
  recent.unshift(t.id);
  recentTemplateIds.value = recent.slice(0, 5);
  try {
    localStorage.setItem('recentTemplates', JSON.stringify(recentTemplateIds.value));
  } catch {}
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
.template-gallery-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: var(--overlay); display: flex; align-items: center; justify-content: center;
  z-index: var(--z-modal);
}
.template-gallery {
  background: var(--bg-primary); border: 1px solid var(--border); border-radius: var(--radius-md);
  width: 650px; max-height: 80vh; overflow-y: auto; color: var(--text-primary);
}
.gallery-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: var(--space-4) var(--space-5); border-bottom: 1px solid var(--border);
}
.gallery-title { font-size: var(--text-lg); font-weight: 600; }
.close-btn { background: none; border: none; color: var(--text-muted); font-size: var(--text-xl); cursor: pointer; }
.close-btn:hover { color: var(--text-primary); }

.gallery-filters {
  display: flex; gap: var(--space-2); padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--border);
}
.search-input {
  flex: 1; background: var(--bg-input); border: 1px solid var(--border); color: var(--text-primary);
  border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); font-size: var(--text-base);
}
.search-input:focus { border-color: var(--accent); outline: none; }
.category-select, .sort-select {
  background: var(--bg-input); border: 1px solid var(--border); color: var(--text-primary);
  border-radius: var(--radius-sm); padding: var(--space-2); font-size: var(--text-sm);
  cursor: pointer;
}

.gallery-tabs {
  display: flex; gap: var(--space-1); padding: var(--space-2) var(--space-5); border-bottom: 1px solid var(--border);
  background: rgba(0,0,0,0.2);
}
.tab-btn {
  padding: var(--space-1-5) var(--space-3); background: transparent; border: none; color: var(--text-muted);
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-sm); transition: all var(--transition);
}
.tab-btn.active {
  background: var(--accent-light);   color: var(--violet-light);
}
.tab-btn:hover:not(.active) {
  background: rgba(255,255,255,0.05); color: var(--text-secondary);
}

.gallery-loading, .gallery-error, .gallery-empty {
  padding: var(--space-8); text-align: center; color: var(--text-muted);
}

.gallery-grid { padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-2-5); }
.template-card {
  display: flex; align-items: center; gap: var(--space-3-5); padding: var(--space-3-5);
  border: 1px solid var(--border); border-radius: var(--radius-sm); cursor: pointer;
  transition: all var(--transition);
}
.template-card:hover { background: rgba(255,255,255,0.05); border-color: var(--accent); }
.template-card.popular { border-left: 3px solid var(--node-human); }
.template-card.recent { border-left: 3px solid var(--node-source); }
.template-icon { font-size: 28px; }
.template-info { flex: 1; }
.template-name { font-size: var(--text-md); font-weight: 600; }
.template-desc { font-size: var(--text-sm); color: var(--text-secondary); margin-top: 2px; }
.template-meta { font-size: var(--text-xs); color: var(--text-muted); margin-top: var(--space-1); display: flex; align-items: center; gap: var(--space-2); }
.template-category {
  background: var(--accent-light);   color: var(--violet-light);
  padding: 2px 6px; border-radius: 4px; font-size: 10px; text-transform: uppercase;
}
.use-btn {
  background: var(--memory-color); border: none; color: var(--text-inverse); padding: 6px 14px;
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-sm);
}
.use-btn:hover { background: var(--cyan-dark); }

.variables-form {
  padding: var(--space-4); border-top: 1px solid var(--border);
}
.var-header { font-size: var(--text-md); font-weight: 600; margin-bottom: var(--space-3); }
.var-field { margin-bottom: var(--space-2-5); }
.var-label { font-size: var(--text-sm); font-weight: 600; color: var(--memory-color); }
.var-label span { color: var(--error); }
.var-desc { font-size: var(--text-xs); color: var(--text-muted); margin-bottom: var(--space-1); }
.var-input {
  width: 100%; background: var(--bg-input); border: 1px solid var(--border); color: var(--text-primary);
  border-radius: var(--radius-sm); padding: var(--space-2); font-size: var(--text-base); box-sizing: border-box;
  font-family: inherit;
}
.var-input:focus { border-color: var(--memory-color); outline: none; }
.create-btn {
  width: 100%; background: var(--memory-color); border: none; color: var(--text-inverse); padding: var(--space-2-5);
  border-radius: var(--radius-sm); cursor: pointer; font-size: var(--text-md); margin-top: var(--space-2);
}
.create-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.create-btn:not(:disabled):hover { background: var(--cyan-dark); }
</style>
