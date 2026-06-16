import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { WorkflowSchema, FlowNode, FlowEdge } from '../types';
import { schemaApi, settingsApi } from '../services/api';
import type { SchemaValidationResult } from '../services/api';
import { isAxiosError } from 'axios';
import { useToast } from '../composables/useToast';

const { error: toastError } = useToast();

export const useCanvasStore = defineStore('canvas', () => {
  const schemas = ref<WorkflowSchema[]>([]);
  const currentSchema = ref<WorkflowSchema | null>(null);
  const loading = ref(false);

  // ─── Dirty-flag auto-save state ──────────────────────────────────
  const isDirty = ref(false)
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let isFlushing = false

  /**
   * Mark the schema as having unsaved changes.
   * Updates `currentSchema` in-memory immediately, starts a 2s debounce
   * timer to persist to backend. Callers should NOT call updateSchema()
   * directly for normal edits — use markDirty() instead.
   */
  function markDirty(schema: WorkflowSchema) {
    currentSchema.value = schema
    isDirty.value = true
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(flushSave, 2000)
  }

  /**
   * Persist dirty changes to backend immediately.
   * Flushes the debounce timer and calls updateSchema. Skip if nothing
   * is dirty (no-op). Re-throws on failure so callers can handle errors.
   */
  async function flushSave() {
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    if (!isDirty.value || !currentSchema.value || isFlushing) return
    isFlushing = true

    try {
      const updated = await schemaApi.updateSchema(currentSchema.value.id, currentSchema.value)
      // Sync returned data back so we stay consistent with backend
      const idx = schemas.value.findIndex(s => s.id === currentSchema.value!.id)
      if (idx !== -1) {
        schemas.value[idx] = updated
      }
      currentSchema.value = updated
      isDirty.value = false
    } catch (err) {
      toastError('Failed to save schema: ' + ((err as Error).message || err))
      isDirty.value = true
      throw err
    } finally {
      isFlushing = false
    }
  }

  // ─── Schema CRUD ─────────────────────────────────────────────────

  async function loadSchemas() {
    loading.value = true;
    try {
      const data = await schemaApi.getSchemas();
      schemas.value = data;
      if (schemas.value.length > 0 && !currentSchema.value) {
        currentSchema.value = schemas.value[0]!;
      }
    } catch (err) {
      toastError('Failed to load schemas: ' + ((err as Error).message || err))
    } finally {
      loading.value = false;
    }
  }

  async function createSchema(name: string, appType?: string) {
    // Pre-fill user default model if available
    let defaultModel: string | undefined;
    try {
      defaultModel = await settingsApi.getUserDefaultModel();
      if (!defaultModel) defaultModel = undefined;
    } catch {
      console.warn('[canvasStore] Failed to fetch user default model, continuing without it');
    }

    const newSchema = {
      id: `new-${Date.now()}`,
      name,
      description: '',
      version: '1.0',
      appType: appType || 'CUSTOM',
      nodes: [],
      edges: [],
      defaultModel,
      createdAt: new Date().toISOString(),
    } as WorkflowSchema;
    try {
      const created = await schemaApi.createSchema(newSchema);
      schemas.value.push(created);
      currentSchema.value = created;
      return created;
    } catch (err) {
      toastError('Failed to create schema: ' + ((err as Error).message || err))
      throw err;
    }
  }

  async function updateSchema(schema: WorkflowSchema) {
    try {
      const updated = await schemaApi.updateSchema(schema.id, schema);
      const index = schemas.value.findIndex(s => s.id === schema.id);
      if (index !== -1) {
        schemas.value[index] = updated;
      }
      if (currentSchema.value?.id === schema.id) {
        currentSchema.value = updated;
      }
      isDirty.value = false
      return updated;
    } catch (err) {
      toastError('Failed to update schema: ' + ((err as Error).message || err))
      throw err;
    }
  }

  async function deleteSchema(id: string) {
    try {
      await schemaApi.deleteSchema(id);
      schemas.value = schemas.value.filter(s => s.id !== id);
      if (currentSchema.value?.id === id) {
        currentSchema.value = schemas.value[0] || null;
      }
      isDirty.value = false
      if (saveTimer) {
        clearTimeout(saveTimer)
        saveTimer = null
      }
    } catch (err) {
      toastError('Failed to delete schema: ' + ((err as Error).message || err))
      throw err;
    }
  }

  async function executeSchema(id: string): Promise<{ status: string; validation?: SchemaValidationResult } | void> {
    if (!id) return;
    try {
      const result = await schemaApi.executeSchema(id, 'EXECUTE');
      if (result.status === 'validation_error' && result.validation) {
        const msgs = result.validation.errors.map(e => e.message).join('; ');
        toastError('Schema validation failed: ' + msgs);
        throw new Error('Validation failed: ' + msgs);
      }
      return result;
    } catch (err: unknown) {
      if (isAxiosError(err) && err.response?.data?.status === 'validation_error') {
        const validation = err.response.data.validation;
        const msgs = validation?.errors?.map((e: any) => e.message).join('; ') || 'Schema validation failed';
        toastError(msgs);
        throw new Error(msgs);
      }
      toastError('Failed to execute schema: ' + ((err as Error).message || err))
      throw err;
    }
  }

  async function cancelExecution(id: string) {
    try {
      await schemaApi.stopSchema(id);
    } catch (err) {
      toastError('Failed to stop execution: ' + ((err as Error).message || err))
    }
  }

  function updateCurrentSchema(schema: WorkflowSchema) {
    currentSchema.value = schema;
  }

  // ─── Initialize / Dispose ──────────────────────────────────────
  let initializedId: string | null = null

  /**
   * Initialize canvas state for the given schema ID.
   * Fetches the schema from the API and sets currentSchema.
   * If already initialized for this ID, this is a no-op.
   * Does NOT build VueFlow nodes/edges — that's handled by BlueprintView.
   */
  async function initialize(id: string) {
    if (initializedId === id) return
    if (isDirty.value) {
      console.debug('[canvasStore] Skipping initialize — unsaved changes exist')
      return
    }

    try {
      const schema = await schemaApi.getSchema(id)
      if (schema) {
        currentSchema.value = schema
        // Sync schemas list
        const idx = schemas.value.findIndex(s => s.id === id)
        if (idx !== -1) {
          schemas.value[idx] = schema
        } else {
          schemas.value.push(schema)
        }
        initializedId = id
      }
    } catch (err) {
      toastError('Failed to initialize canvas: ' + ((err as Error).message || err))
    }
  }

  /**
   * Dispose of canvas state — clears currentSchema and any pending save.
   */
  function dispose() {
    initializedId = null
    currentSchema.value = null
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
  }

  // ─── Canvas helpers ──────────────────────────────────────────

  function addNode(node: FlowNode) {
    if (!currentSchema.value) return
    // Pre-fill schema default model for new nodes (existing nodes unchanged)
    if (!node.data?.config?.model && currentSchema.value.defaultModel) {
      node = {
        ...node,
        data: {
          ...node.data,
          config: { ...node.data?.config, model: currentSchema.value.defaultModel },
        },
      } as FlowNode
    }
    currentSchema.value = {
      ...currentSchema.value,
      nodes: [...(currentSchema.value.nodes || []), node],
    }
    markDirty(currentSchema.value)
  }

  function removeNode(nodeId: string) {
    if (!currentSchema.value) return
    currentSchema.value = {
      ...currentSchema.value,
      nodes: (currentSchema.value.nodes || []).filter(n => n.id !== nodeId),
      edges: (currentSchema.value.edges || []).filter(e => e.source !== nodeId && e.target !== nodeId),
    }
    markDirty(currentSchema.value)
  }

  function updateNode(nodeId: string, updates: Partial<FlowNode>) {
    if (!currentSchema.value) return
    currentSchema.value = {
      ...currentSchema.value,
      nodes: (currentSchema.value.nodes || []).map(n =>
        n.id === nodeId ? { ...n, ...updates } as FlowNode : n
      ),
    }
    markDirty(currentSchema.value)
  }

  function addEdge(edge: FlowEdge) {
    if (!currentSchema.value) return
    currentSchema.value = {
      ...currentSchema.value,
      edges: [...(currentSchema.value.edges || []), edge],
    }
    markDirty(currentSchema.value)
  }

  function removeEdge(edgeId: string) {
    if (!currentSchema.value) return
    currentSchema.value = {
      ...currentSchema.value,
      edges: (currentSchema.value.edges || []).filter(e => e.id !== edgeId),
    }
    markDirty(currentSchema.value)
  }

  return {
    schemas,
    currentSchema,
    loading,
    // Dirty-flag auto-save
    isDirty,
    markDirty,
    flushSave,
    // Schema CRUD
    loadSchemas,
    createSchema,
    updateSchema,
    deleteSchema,
    executeSchema,
    cancelExecution,
    updateCurrentSchema,
    // Lifecycle
    initialize,
    dispose,
    // Canvas operations
    addNode,
    removeNode,
    updateNode,
    addEdge,
    removeEdge,
  };
});
