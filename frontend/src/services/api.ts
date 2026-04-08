import { WorkflowSchema, Agent } from '../types';

const API_BASE_URL = 'http://localhost:8080/api';

export const api = {
  // Схемы
  async getSchemas(): Promise<WorkflowSchema[]> {
    const response = await fetch(`${API_BASE_URL}/schemas`);
    return response.json();
  },
  
  async getSchema(id: string): Promise<WorkflowSchema> {
    const response = await fetch(`${API_BASE_URL}/schemas/${id}`);
    return response.json();
  },
  
  async createSchema(schema: WorkflowSchema): Promise<WorkflowSchema> {
    const response = await fetch(`${API_BASE_URL}/schemas`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(schema),
    });
    return response.json();
  },
  
  async updateSchema(id: string, schema: WorkflowSchema): Promise<WorkflowSchema> {
    const response = await fetch(`${API_BASE_URL}/schemas/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(schema),
    });
    return response.json();
  },
  
  async deleteSchema(id: string): Promise<void> {
    await fetch(`${API_BASE_URL}/schemas/${id}`, { method: 'DELETE' });
  },
  
  async executeSchema(id: string): Promise<void> {
    await fetch(`${API_BASE_URL}/schemas/${id}/execute`, { method: 'POST' });
  },
  
  async exportToMermaid(id: string): Promise<string> {
    const response = await fetch(`${API_BASE_URL}/schemas/${id}/export/mermaid`);
    const data = await response.json();
    return data.mermaid;
  },
  
  // Агенты
  async getAgents(): Promise<Agent[]> {
    const response = await fetch(`${API_BASE_URL}/agents`);
    return response.json();
  },
  
  async sendMessage(agentId: string, message: string, sessionKey?: string): Promise<{ reply: string; sessionKey: string }> {
    const response = await fetch(`${API_BASE_URL}/agents/${agentId}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, sessionKey }),
    });
    return response.json();
  },
};
