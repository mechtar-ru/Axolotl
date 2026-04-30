import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { WorkflowSchema, FlowNode, FlowEdge, ExecutionMode } from '../types';
import { schemaApi, settingsApi } from '../services/api';

export const useSchemaStore = defineStore('schema', () => {
  const schemas = ref<WorkflowSchema[]>([]);
  const currentSchema = ref<WorkflowSchema | null>(null);
  const loading = ref(false);
  const executionMode = ref<ExecutionMode>('EXECUTE');
  const currentWorkspace = ref<string>('default');
  const schemaWorkspaces = ref<string[]>(['default']);

  async function loadSchemas(workspaceId?: string) {
    loading.value = true;
    try {
      const ws = workspaceId ?? currentWorkspace.value;
      const data = await schemaApi.getSchemas(ws);
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

  async function loadWorkspaces() {
    try {
      const wsList = await schemaApi.getSchemaWorkspaces();
      if (wsList.length > 0) {
        schemaWorkspaces.value = wsList;
        if (!wsList.includes(currentWorkspace.value)) {
          currentWorkspace.value = wsList[0]!;
        }
      }
    } catch (error) {
      console.error('Ошибка загрузки workspaces:', error);
    }
  }

  async function setWorkspace(workspaceId: string) {
    currentWorkspace.value = workspaceId;
    currentSchema.value = null;
    await loadSchemas(workspaceId);
  }
  
  async function createSchema(name: string) {
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
      nodes: [],
      edges: [],
      defaultModel,
      createdAt: new Date().toISOString(),
    } as WorkflowSchema;
    try {
      const created = await schemaApi.createSchema(newSchema, currentWorkspace.value);
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
    } catch (error) {
      console.error('Ошибка удаления схемы:', error);
      throw error;
    }
  }
  
  async function executeCurrentSchema() {
    if (currentSchema.value) {
      try {
        await updateSchema(currentSchema.value);
        await schemaApi.executeSchema(currentSchema.value.id, executionMode.value);
      } catch (error) {
        console.error('Ошибка выполнения схемы:', error);
        throw error;
      }
    }
  }

  function updateCurrentSchema(schema: WorkflowSchema) {
    currentSchema.value = schema;
  }

  function setExecutionMode(mode: ExecutionMode) {
    executionMode.value = mode;
  }

  return {
    schemas,
    currentSchema,
    loading,
    executionMode,
    currentWorkspace,
    schemaWorkspaces,
    loadSchemas,
    loadWorkspaces,
    setWorkspace,
    createSchema,
    updateSchema,
    deleteSchema,
    executeCurrentSchema,
    updateCurrentSchema,
    setExecutionMode,
  };
});
