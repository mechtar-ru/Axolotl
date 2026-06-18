import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import type { WorkflowSchema } from '../types';
import { api } from '../services/api';
import type { SchemaValidationResult } from '../services/api';
import { useCanvasStore } from './useCanvasStore';

// Re-export types from review store for backward compat
export type { ReviewData, ReviewFinding } from './useReviewStore';

export const useSchemaStore = defineStore('schema', () => {
  const schemas = ref<WorkflowSchema[]>([]);
  const loading = ref(false);
  let schemaWatcher: (() => void) | null = null;

  // ─── Schema CRUD (delegated to canvasStore) ──────────────────────

  async function loadSchemas() {
    const canvasStore = useCanvasStore();
    loading.value = true;
    try {
      await canvasStore.loadSchemas();
      // Use watch for live sync instead of one-time copy
      // Stop old watcher before creating a new one to prevent leaks
      if (schemaWatcher) {
        schemaWatcher();
      }
      schemaWatcher = watch(
        () => canvasStore.schemas,
        (val) => { schemas.value = val },
        { deep: true, immediate: true }
      );
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
    const canvasStore = useCanvasStore()
    await canvasStore.refreshCurrentSchema(schemaId)
    // schemaStore's schemas are synced via the watcher from loadSchemas
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
