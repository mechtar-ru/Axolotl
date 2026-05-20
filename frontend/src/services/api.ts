import axios from 'axios';
import type { WorkflowSchema, Agent, ExecutionMode, PlanningModels, PlanRequest, PlanResponse } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Auto-attach JWT token to all requests
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('axolotl_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * Decode JWT payload without verification (client-side).
 * Returns null for malformed tokens.
 */
function decodeJwt(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    return JSON.parse(atob(parts[1]));
  } catch {
    return null;
  }
}

/**
 * Check if the stored JWT is expired by decoding its exp claim.
 * Returns false if no token, malformed token, or token has no exp.
 */
function isTokenExpired(): boolean {
  const token = localStorage.getItem('axolotl_token');
  if (!token) return false;
  const payload = decodeJwt(token);
  if (!payload || typeof payload.exp !== 'number') return false;
  return Date.now() >= payload.exp * 1000;
}

/**
 * Clear auth state and redirect to login page.
 */
function clearAuthAndRedirect(): void {
  localStorage.removeItem('axolotl_token');
  localStorage.removeItem('axolotl_username');
  localStorage.removeItem('axolotl_role');
  if (window.location.pathname !== '/login') {
    window.location.href = '/login';
  }
}

/**
 * On 401: always clear auth (real auth failure).
 * On 403: only clear auth if the token is provably expired client-side.
 *   Spring Security returns 403 (not 401) for anonymous users hitting
 *   non-permitAll endpoints with an expired/stale token.  Clearing auth
 *   on every 403 would cause a redirect loop (open schema → 403 on some
 *   endpoint → login → repeat).  But ignoring 403 entirely means expired
 *   tokens silently fail.  Checking exp client-side splits the difference.
 * On other errors: pass through.
 */
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status;
    if (status === 401) {
      clearAuthAndRedirect();
    } else if (status === 403 && isTokenExpired()) {
      clearAuthAndRedirect();
    }
    return Promise.reject(error);
  }
);

export const schemaApi = {
  // Схемы
  async getSchemas(): Promise<WorkflowSchema[]> {
    const response = await api.get('/schemas');
    return response.data;
  },
  
  async getSchema(id: string): Promise<WorkflowSchema> {
    const response = await api.get(`/schemas/${id}`);
    return response.data;
  },
  
  async createSchema(schema: WorkflowSchema): Promise<WorkflowSchema> {
    const response = await api.post('/schemas', schema);
    return response.data;
  },
  
  async updateSchema(id: string, schema: WorkflowSchema): Promise<WorkflowSchema> {
    const response = await api.put(`/schemas/${id}`, schema);
    return response.data;
  },
  
  async deleteSchema(id: string): Promise<void> {
    await api.delete(`/schemas/${id}`);
  },
  
  async executeSchema(id: string, mode: ExecutionMode = 'EXECUTE'): Promise<void> {
    await api.post(`/schemas/${id}/execute`, {}, { params: { mode } });
  },

  async stopSchema(id: string): Promise<void> {
    await api.post(`/schemas/${id}/stop`);
  },
  
  async exportToMermaid(id: string): Promise<string> {
    const response = await api.get(`/schemas/${id}/export/mermaid`);
    return response.data.mermaid;
  },

  async generateFromPrompt(prompt: string, model?: string): Promise<{ success: boolean; schema?: WorkflowSchema; error?: string; planExplanation?: string }> {
    const response = await api.post('/schemas/generate-from-prompt', { prompt, model });
    return response.data;
  },


  async plan(id: string, request: PlanRequest): Promise<PlanResponse> {
    const response = await api.post(`/schemas/${id}/plan`, request);
    return response.data;
  },

  async updatePlanningModels(id: string, models: PlanningModels): Promise<WorkflowSchema> {
    const schema = await this.getSchema(id);
    schema.planningModels = models;
    return this.updateSchema(id, schema);
  },

  async updatePlanningOutline(id: string, outline: string | null): Promise<WorkflowSchema> {
    const schema = await this.getSchema(id);
    schema.planningOutline = outline || undefined;
    return this.updateSchema(id, schema);
  },

  async updatePlanningRefinedPlan(id: string, plan: string | null): Promise<WorkflowSchema> {
    const schema = await this.getSchema(id);
    schema.planningRefinedPlan = plan || undefined;
    return this.updateSchema(id, schema);
  },

  async updatePlanningContext(id: string, context: string | null): Promise<WorkflowSchema> {
    const schema = await this.getSchema(id);
    schema.planningContext = context || undefined;
    return this.updateSchema(id, schema);
  },

  async clearPlanningContext(id: string): Promise<WorkflowSchema> {
    const schema = await this.getSchema(id);
    schema.planningContext = undefined;
    return this.updateSchema(id, schema);
  },

  async getRuns(schemaId: string): Promise<ExecutionRun[]> {
    const response = await api.get(`/schemas/${schemaId}/runs`);
    return response.data;
  },

  async getPausedRun(schemaId: string): Promise<ExecutionRun | null> {
    const response = await api.get(`/schemas/${schemaId}/runs/paused`);
    return response.data;
  },

  async getRunNodes(schemaId: string, runId: string): Promise<NodeExecution[]> {
    const response = await api.get(`/schemas/${schemaId}/runs/${runId}/nodes`);
    return response.data;
  },

  async resumeSchema(schemaId: string): Promise<void> {
    await api.post(`/schemas/${schemaId}/resume`);
  },
};

export interface AppInfo {
  id: string;
  name: string;
  description: string;
  appType: string;
  targetPath?: string;
  targetPathConflictAction?: string;
  workspaceId?: string;
  createdAt?: string;
  updatedAt?: string;
}

export const appApi = {
  /** Get single app by ID */
  async getApp(id: string): Promise<AppInfo> {
    const response = await api.get(`/app/${id}`);
    return response.data;
  },

  /** Check if targetPath directory exists for a given app name */
  async checkTargetPath(name: string, appType: string): Promise<{
    exists: boolean;
    targetPath: string;
  }> {
    const response = await api.get('/app/check-target-path', {
      params: { name, appType }
    });
    return response.data;
  },

  /** Create app with conflict resolution action */
  async createApp(data: {
    name: string;
    appType: string;
    description?: string;
    conflictAction?: 'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH';
    customTargetPath?: string;
    templateId?: string;
  }): Promise<AppInfo> {
    const response = await api.post('/app', data);
    return response.data;
  },

  /** Update app */
  async updateApp(id: string, data: {
    name?: string;
    description?: string;
  }): Promise<AppInfo> {
    const response = await api.put(`/app/${id}`, data);
    return response.data;
  },

  /** Get generated files for a completed execution task */
  async getGeneratedFiles(schemaId: string): Promise<Array<{
    path: string;
    description: string;
  }>> {
    const response = await api.get(`/app/${schemaId}/generated-files`);
    return response.data;
  }
};

export const agentApi = {
  async getAgents(): Promise<Agent[]> {
    const response = await api.get('/agents');
    return response.data;
  },

  async sendMessage(agentId: string, message: string, sessionKey?: string): Promise<{ reply: string; sessionKey: string }> {
    const response = await api.post(`/agents/${agentId}/chat`, { message, sessionKey });
    return response.data;
  },
};

export interface ProviderInfo {
  name: string;
  available: boolean;
  baseUrl: string;
  models: string[];
  disabledModels?: string[];
  defaultModel?: string;
  custom?: boolean;
  id?: string;
  authType?: string;
  enabled?: boolean;
}

export const settingsApi = {
  async getProviders(): Promise<ProviderInfo[]> {
    const response = await api.get('/settings/providers');
    return response.data;
  },

  async getProviderModels(providerName: string): Promise<string[]> {
    const response = await api.get(`/settings/providers/${providerName}/models`);
    return response.data;
  },

  async updateProvider(provider: string, data: { apiKey?: string; baseUrl?: string; defaultModel?: string }) {
    const response = await api.put(`/settings/${provider}`, data);
    return response.data;
  },

  async testProvider(provider: string, apiKey?: string, baseUrl?: string): Promise<{ provider: string; apiKeyConfigured: boolean; baseUrl: string; available: boolean; models: string[]; error?: string }> {
    let url = `/settings/${provider}/health`;
    const params = new URLSearchParams();
    if (apiKey !== undefined && apiKey !== '') params.set('apiKey', apiKey);
    if (baseUrl !== undefined && baseUrl !== '') params.set('baseUrl', baseUrl);
    const qs = params.toString();
    if (qs) url += '?' + qs;
    const response = await api.get(url);
    return response.data;
  },

  async getDefaultModel(): Promise<string> {
    const response = await api.get('/settings/default-model');
    return response.data.defaultModel || '';
  },

  async setDefaultModel(model: string): Promise<void> {
    await api.put('/settings/default-model', { defaultModel: model });
  },

  async getUserDefaultModel(): Promise<string> {
    const response = await api.get('/settings/user/default-model');
    return response.data.defaultModel || '';
  },

  async setUserDefaultModel(model: string): Promise<void> {
    await api.put('/settings/user/default-model', { defaultModel: model });
  },

  async getProviderApiKey(provider: string): Promise<string> {
    const response = await api.get(`/settings/${provider}/key`);
    return response.data.apiKey || '';
  },

  async getDisabledModels(provider: string): Promise<string[]> {
    const response = await api.get(`/settings/${provider}/models/disabled`);
    return response.data;
  },

  async setDisabledModels(provider: string, models: string[]): Promise<void> {
    await api.put(`/settings/${provider}/models/disabled`, models);
  },
};

export interface CustomLlmEndpoint {
  id?: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  modelName: string;
  authType: string;
  enabled: boolean;
  priority: number;
}

export const customEndpointApi = {
  async list(): Promise<CustomLlmEndpoint[]> {
    const response = await api.get('/settings/endpoints');
    return response.data;
  },

  async create(endpoint: CustomLlmEndpoint): Promise<CustomLlmEndpoint> {
    const response = await api.post('/settings/endpoints', endpoint);
    return response.data;
  },

  async update(id: string, endpoint: Partial<CustomLlmEndpoint>): Promise<CustomLlmEndpoint> {
    const response = await api.put(`/settings/endpoints/${id}`, endpoint);
    return response.data;
  },

  async remove(id: string): Promise<void> {
    await api.delete(`/settings/endpoints/${id}`);
  },

  async test(endpoint: CustomLlmEndpoint): Promise<{ success: boolean; message: string }> {
    const response = await api.post('/settings/endpoints/test', endpoint);
    return response.data;
  },
};

export interface ExecutionRecord {
  id: string;
  schemaId: string;
  schemaName: string;
  startTime: number;
  endTime: number;
  totalTimeMs: number;
  totalNodes: number;
  completedNodes: number;
  status: string;
  nodeResults?: Record<string, {
    nodeId: string;
    nodeName: string;
    result: string | null;
    durationMs: number;
    status: string;
  }>;
}

export interface ExecutionRun {
  id: string;
  schemaId: string;
  status: string;
  mode: string;
  totalTokens: number;
  estimatedCost: number;
  error: string | null;
  resumesFrom: string | null;
  startedAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface NodeExecution {
  id: string;
  runId: string;
  nodeId: string;
  nodeName: string;
  nodeType: string;
  status: string;
  tokensUsed: number;
  durationMs: number;
  toolCalls: number;
  error: string | null;
  inputSummary: string | null;
  outputSummary: string | null;
  filesWritten: string | null;
  configHash: string;
  startedAt: string;
  completedAt: string | null;
}

export const historyApi = {
  async getSchemaHistory(schemaId: string): Promise<ExecutionRecord[]> {
    const response = await api.get(`/schemas/${schemaId}/history`);
    return response.data;
  },

  async getAllHistory(): Promise<ExecutionRecord[]> {
    const response = await api.get('/history');
    return response.data;
  },
};
