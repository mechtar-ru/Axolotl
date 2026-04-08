export interface Position {
  x: number;
  y: number;
}

export interface Message {
  role: 'user' | 'agent' | 'system';
  content: string;
  timestamp: number;
}

export interface NodeData {
  systemPrompt?: string;
  userPrompt?: string;
  sourceData?: string;  // Добавляем sourceData
  model?: string;
  config?: Record<string, any>;
  messages?: Message[];
  result?: string;
}

export interface Node {
  id: string;
  type: 'source' | 'agent' | 'output' | 'condition';
  name: string;
  position: Position;
  data: NodeData;
  status?: 'idle' | 'running' | 'completed' | 'failed';
}

export interface Edge {
  id: string;
  source: string;
  target: string;
  sourcePort?: string;
  targetPort?: string;
  type: 'data' | 'control' | 'condition';
}

export interface WorkflowSchema {
  id: string;
  name: string;
  description: string;
  version: string;
  nodes: Node[];
  edges: Edge[];
  metadata?: Record<string, any>;
  createdAt?: string;
  updatedAt?: string;
}

export interface Agent {
  id: string;
  name: string;
  emoji: string;
  connection: {
    type: string;
    url: string;
    apiKey?: string;
    timeout: number;
  };
}
