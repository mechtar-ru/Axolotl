import axios from 'axios';
import type { WorkflowSchema, Agent, ExecutionMode } from '../types';

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

// Auto-login as tech user on 401/403, redirect to /login on second failure
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    const status = error.response?.status;
    if ((status === 401 || status === 403) && !originalRequest._retried) {
      originalRequest._retried = true;
      try {
        const res = await api.post('/auth/login', { username: 'tech', password: 'tech' });
        const token = res.data.token;
        localStorage.setItem('axolotl_token', token);
        localStorage.setItem('axolotl_username', 'tech');
        localStorage.setItem('axolotl_role', 'tech');
        originalRequest.headers.Authorization = `Bearer ${token}`;
        return api(originalRequest);
      } catch {
        localStorage.removeItem('axolotl_token');
        localStorage.removeItem('axolotl_username');
        localStorage.removeItem('axolotl_role');
        window.location.href = '/login';
      }
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

  async testProvider(provider: string): Promise<{ provider: string; apiKeyConfigured: boolean; baseUrl: string; available: boolean }> {
    const response = await api.get(`/settings/${provider}/health`);
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
