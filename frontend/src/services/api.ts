import axios from 'axios';
import type { WorkflowSchema, Agent } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

const api = axios.create({
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

// Redirect to login on 401
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('axolotl_token');
      localStorage.removeItem('axolotl_username');
      localStorage.removeItem('axolotl_role');
      window.location.href = '/login';
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
  
  async executeSchema(id: string): Promise<void> {
    await api.post(`/schemas/${id}/execute`);
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
