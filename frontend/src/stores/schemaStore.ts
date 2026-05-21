import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { WorkflowSchema, FlowNode, FlowEdge } from '../types';
import type { Pipeline, Stage, PipelineStatus } from '../types/pipeline';
import { schemaApi, settingsApi } from '../services/api';
import { api } from '../services/api';

export interface ReviewFinding {
  source: string;
  severity: string;
  description: string;
  suggestion: string;
}

export interface ReviewData {
  executionId: string;
  nodeId: string;
  originalPlan: string;
  rewrittenPlan: string;
  findings: ReviewFinding[];
  iteration: number;
  maxIterations: number;
}

export const useSchemaStore = defineStore('schema', () => {
  const schemas = ref<WorkflowSchema[]>([]);
  const currentSchema = ref<WorkflowSchema | null>(null);
  const loading = ref(false);
  const pendingReview = ref(false);
  const reviewData = ref<ReviewData | null>(null);

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
    } catch (error) {
      console.error('Flush save failed:', error)
      isDirty.value = true // Keep dirty so next markDirty retries
      throw error
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
    } catch (error) {
      console.error('Ошибка загрузки схем:', error);
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
    } catch {}

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
    } catch (error) {
      console.error('Ошибка создания схемы:', error);
      throw error;
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
    } catch (error) {
      console.error('Ошибка обновления схемы:', error);
      throw error;
    }
  }
  
  async function deleteSchema(id: string) {
    try {
      await schemaApi.deleteSchema(id);
      schemas.value = schemas.value.filter(s => s.id !== id);
      if (currentSchema.value?.id === id) {
        currentSchema.value = schemas.value[0] || null;
      }
      // If deleted schema was dirty, clean up
      isDirty.value = false
      if (saveTimer) {
        clearTimeout(saveTimer)
        saveTimer = null
      }
    } catch (error) {
      console.error('Ошибка удаления схемы:', error);
      throw error;
    }
  }
  
  async function executeSchema(id: string) {
    if (!id) return;
    try {
      await schemaApi.executeSchema(id, 'EXECUTE');
    } catch (error) {
      console.error('Ошибка выполнения схемы:', error);
      throw error;
    }
  }

  async function cancelExecution(id: string) {
    try {
      await schemaApi.stopSchema(id);
    } catch (error) {
      console.error('Ошибка остановки схемы:', error);
    }
  }

  function updateCurrentSchema(schema: WorkflowSchema) {
    currentSchema.value = schema;
  }

  // Review approval state
  function handleReviewAwaitingApproval(data: ReviewData) {
    reviewData.value = data;
    pendingReview.value = true;
  }

  function clearReview() {
    pendingReview.value = false;
    reviewData.value = null;
  }

  async function approveReview(executionId: string, nodeId: string) {
    try {
      await api.post(`/execution/${executionId}/approve-review?nodeId=${nodeId}`);
      pendingReview.value = false;
      reviewData.value = null;
    } catch (error) {
      console.error('Ошибка при approve review:', error);
      throw error;
    }
  }

  async function rejectReview(executionId: string, nodeId: string) {
    try {
      await api.post(`/execution/${executionId}/reject?nodeId=${nodeId}`);
      pendingReview.value = false;
      reviewData.value = null;
    } catch (error) {
      console.error('Ошибка при reject review:', error);
      throw error;
    }
  }

  // ─── Pipeline state ────────────────────────────────────────────
  const pipelineStatus = ref<PipelineStatus>({ running: false, stageResults: {} })
  const pipelineExpanded = ref(false)

  async function buildPipelineNodes(schemaId: string) {
    const res = await api.post(`/schemas/${schemaId}/pipeline/build`)
    return res.data
  }

  async function executePipeline(schemaId: string) {
    await api.post(`/schemas/${schemaId}/pipeline/execute`)
    pipelineStatus.value = { running: true, stageResults: {} }
  }

  async function cancelPipelineExecution(schemaId: string) {
    await api.post(`/schemas/${schemaId}/pipeline/cancel`)
    pipelineStatus.value.running = false
  }

  async function retryPipeline(schemaId: string) {
    await api.post(`/schemas/${schemaId}/pipeline/retry`)
    pipelineStatus.value = { running: true, stageResults: {} }
  }

  async function refreshPipelineStatus(schemaId: string) {
    const res = await api.get(`/schemas/${schemaId}/pipeline/status`)
    pipelineStatus.value = res.data
  }

  async function createDefaultPipeline(schemaId: string, appType?: string, description?: string, tddEnabled?: boolean) {
    await api.post(`/schemas/${schemaId}/pipeline/default`, {
      appType: appType || 'custom',
      description: description || '',
      tddEnabled: !!tddEnabled
    })
    // Re-fetch the schema to get the persisted pipeline and update currentSchema
    const resp = await api.get(`/schemas/${schemaId}`)
    if (resp.data && currentSchema.value) {
      currentSchema.value = resp.data
    }
  }

  function setPipeline(pipeline: Pipeline | undefined) {
    if (currentSchema.value) {
      currentSchema.value.pipeline = pipeline
    }
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
    // Review
    pendingReview,
    reviewData,
    handleReviewAwaitingApproval,
    clearReview,
    approveReview,
    rejectReview,
    // Pipeline
    pipelineStatus,
    pipelineExpanded,
    buildPipelineNodes,
    executePipeline,
    cancelPipelineExecution,
    retryPipeline,
    refreshPipelineStatus,
    createDefaultPipeline,
    setPipeline,
  };
});
