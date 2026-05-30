import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { WorkflowSchema } from '../types';
import { schemaApi, api } from '../services/api';
import type { SchemaValidationResult } from '../services/api';
import { useToast } from '../composables/useToast';
import { useCanvasStore } from './useCanvasStore';

const { error: toastError } = useToast();

// Re-export types from review store for backward compat
export type { ReviewData, ReviewFinding } from './useReviewStore';

export const useSchemaStore = defineStore('schema', () => {
  const schemas = ref<WorkflowSchema[]>([]);
  const loading = ref(false);

  // ─── Schema CRUD ─────────────────────────────────────────────────

  async function loadSchemas() {
    loading.value = true;
    try {
      const data = await schemaApi.getSchemas();
      schemas.value = data;
    } catch (err) {
      toastError('Failed to load schemas: ' + ((err as Error).message || err))
    } finally {
      loading.value = false;
    }
  }

  async function createSchema(name: string, appType?: string) {
    const newSchema = {
      id: `new-${Date.now()}`,
      name,
      description: '',
      version: '1.0',
      appType: appType || 'CUSTOM',
      nodes: [],
      edges: [],
      createdAt: new Date().toISOString(),
    } as WorkflowSchema;
    try {
      const created = await schemaApi.createSchema(newSchema);
      schemas.value.push(created);
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
    } catch (err: any) {
      if (err?.response?.data?.status === 'validation_error') {
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
    // Schema CRUD
    loadSchemas,
    createSchema,
    updateSchema,
    deleteSchema,
    executeSchema,
    cancelExecution,
    // Re-fetch
    refreshCurrentSchema,
  };
});
