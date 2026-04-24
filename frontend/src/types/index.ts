export interface Position {
  x: number;
  y: number;
}

export interface Message {
  role: 'user' | 'agent' | 'system';
  content: string;
  timestamp: number;
}

export type ExecutionMode = 'EXECUTE' | 'ANALYZE' | 'DRY_RUN';

export interface NodeData {
  systemPrompt?: string;
  userPrompt?: string;
  sourceData?: string;
  sources?: SourceItem[];
  model?: string;
  config?: Record<string, any>;
  messages?: Message[];
  result?: string;
  condition?: string;
  loopCondition?: string;
  maxIterations?: number;
  nodeTimeMs?: number;
  outputType?: 'log' | 'file';
  filePath?: string;
  fileFormat?: 'text' | 'json' | 'markdown';
  subagentSchemaId?: string;
  inputMapping?: Record<string, string>;
  outputMapping?: Record<string, string>;
}

export interface SourceItem {
  id: string;
  type: 'file' | 'database' | 'text';
  name: string;
  content: string;
}

export interface FlowNode {
  id: string;
  type: 'source' | 'agent' | 'output' | 'condition' | 'loop' | 'group' | 'comment' | 'memory' | 'guardrail' | 'human' | 'fallback' | 'webhook' | 'schedule' | 'subagent' | 'schemabuilder';
  parentId?: string;
  collapsed?: boolean;
  name: string;
  position: Position;
  data: NodeData & { isStreaming?: boolean };
  status?: 'idle' | 'running' | 'completed' | 'failed';
  progress?: number;
  executionStatus?: 'idle' | 'running' | 'completed' | 'failed';
}

export interface FlowEdge {
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
  nodes: FlowNode[];
  edges: FlowEdge[];
  defaultModel?: string;
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

export interface ElectronAPI {
  showNotification: (options: { title: string; body: string }) => Promise<boolean>;
  getAppVersion: () => Promise<string>;
  getAppPath: () => Promise<string>;
  showSaveDialog: (options: {
    title?: string;
    defaultPath?: string;
    filters?: Array<{ name: string; extensions: string[] }>;
  }) => Promise<{ canceled: boolean; filePath?: string }>;
  showOpenDialog: (options: {
    title?: string;
    defaultPath?: string;
    filters?: Array<{ name: string; extensions: string[] }>;
    properties?: Array<'openFile' | 'openDirectory' | 'multiSelections'>;
  }) => Promise<{ canceled: boolean; filePaths: string[] }>;
  readFile: (filePath: string) => Promise<string>;
  writeFile: (options: { filePath: string; content: string }) => Promise<boolean>;
  openExternal: (url: string) => Promise<void>;
  windowMinimize: () => Promise<void>;
  windowMaximize: () => Promise<void>;
  windowClose: () => Promise<void>;
  windowIsMaximized: () => Promise<boolean>;
  onCreateNewWorkflow: (callback: () => void) => void;
  onOpenWorkflow: (callback: () => void) => void;
  onSaveWorkflow: (callback: () => void) => void;
  onExportPng: (callback: () => void) => void;
  onExportJson: (callback: () => void) => void;
  removeAllListeners: (channel: string) => void;
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI;
  }
}
