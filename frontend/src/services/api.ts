import axios from 'axios';
import type { WorkflowSchema, Agent } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

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
