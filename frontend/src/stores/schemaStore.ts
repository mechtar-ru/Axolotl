import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { WorkflowSchema } from '../types';
import { api } from '../services/api';
import type { SchemaValidationResult } from '../services/api';
import { useCanvasStore } from './useCanvasStore';

// Re-export types from review store for backward compat
export type { ReviewData, ReviewFinding } from './useReviewStore';

export const useSchemaStore = defineStore('schema', () => {
  const schemas = ref<WorkflowSchema[]>([]);
  const loading = ref(false);

  // ─── Schema CRUD (delegated to canvasStore) ──────────────────────

  async function loadSchemas() {
    const canvasStore = useCanvasStore();
    loading.value = true;
    try {
      await canvasStore.loadSchemas();
      schemas.value = canvasStore.schemas;
    } finally {
      loading.value = false;
    }
  }

  async function createSchema(name: string, appType?: string) {
    const canvasStore = useCanvasStore();
    const result = await canvasStore.createSchema(name, appType);
    schemas.value = canvasStore.schemas;
    return result;
  }

  async function updateSchema(schema: WorkflowSchema) {
    const canvasStore = useCanvasStore();
    const result = await canvasStore.updateSchema(schema);
    schemas.value = canvasStore.schemas;
    return result;
  }

  async function deleteSchema(id: string) {
    const canvasStore = useCanvasStore();
    await canvasStore.deleteSchema(id);
    schemas.value = canvasStore.schemas;
  }

  async function executeSchema(id: string): Promise<{ status: string; validation?: SchemaValidationResult } | void> {
    const canvasStore = useCanvasStore();
    return await canvasStore.executeSchema(id);
  }

  async function cancelExecution(id: string) {
    const canvasStore = useCanvasStore();
    return await canvasStore.cancelExecution(id);
  }

  // ─── Re-fetch (kept local for backward compat) ──────────────────

  async function refreshCurrentSchema(schemaId: string) {
    try {
      const resp = await api.get(`/schemas/${schemaId}`)
      if (resp.data) {
        const canvasStore = useCanvasStore()
        canvasStore.currentSchema = resp.data
        const idx = schemas.value.findIndex(s => s.id === schemaId)
        if (idx !== -1) {
          schemas.value[idx] = resp.data
        }
      }
    } catch {
      console.warn('[schemaStore] Failed to refresh schema ' + schemaId);
    }
  }

  return {
    schemas,
    loading,
    loadSchemas,
    createSchema,
    updateSchema,
    deleteSchema,
    executeSchema,
    cancelExecution,
    refreshCurrentSchema,
  };
});
