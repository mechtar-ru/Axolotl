import type { BlockDefinition } from '@/types/blockRegistry'

export const BLOCK_REGISTRY: BlockDefinition[] = [
  {
    type: 'source',
    label: 'Receive',
    category: 'receive',
    color: '#4caf50',
    icon: 'M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h7a3 3 0 013 3v1',
    defaultConfig: { sourceType: 'text', sourceData: '' },
    configPanels: [
      { id: 'sourceType' },
      { id: 'sourceData' },
    ],
  },
  {
    type: 'draft',
    label: 'Draft',
    category: 'analyze',
    color: '#14b8a6',
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
    defaultConfig: { draftType: 'draft-spec' },
    configPanels: [
      { id: 'draftType' },
    ],
  },
  {
    type: 'review',
    label: 'Review',
    category: 'analyze',
    color: '#f59e0b',
    icon: 'M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 14l2 2 4-4',
    defaultConfig: { mode: 'manual', maxIterations: 3 },
    configPanels: [
      { id: 'model' },
      { id: 'mode' },
      { id: 'checks' },
    ],
  },
  {
    type: 'agent',
    label: 'Think',
    category: 'execute',
    color: '#2196f3',
    icon: 'M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z',
    defaultConfig: { model: '', systemPrompt: '', tools: [] },
    configPanels: [
      { id: 'model' },
      { id: 'systemPrompt' },
      { id: 'tools' },
    ],
  },
  {
    type: 'verifier',
    label: 'Verify',
    category: 'analyze',
    color: '#8b5cf6',
    icon: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
    defaultConfig: { checks: [], maxRewriteRetries: 3 },
    configPanels: [
      { id: 'model' },
      { id: 'checks' },
    ],
  },
  {
    type: 'memory',
    label: 'Remember',
    category: 'execute',
    color: '#9c27b0',
    icon: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10',
    defaultConfig: { memoryType: 'knowledge', namespace: '' },
    configPanels: [
      { id: 'memory' },
    ],
  },
  {
    type: 'planner',
    label: 'Planner',
    category: 'execute',
    color: '#6366f1',
    icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4',
    defaultConfig: { agentType: 'planner', tools: ['file_read', 'file_write', 'directory_read'] },
    configPanels: [
      { id: 'model' },
      { id: 'systemPrompt' },
    ],
  },
  {
    type: 'prep',
    label: 'Prep',
    category: 'execute',
    color: '#06b6d4',
    icon: 'M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z',
    defaultConfig: { agentType: 'prep', tools: ['file_read', 'file_write', 'directory_read'] },
    configPanels: [
      { id: 'model' },
      { id: 'systemPrompt' },
    ],
  },
  {
    type: 'doc-agent',
    label: 'Doc-Agent',
    category: 'output',
    color: '#22c55e',
    icon: 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253',
    defaultConfig: { agentType: 'doc-agent', tools: ['file_read', 'file_write', 'directory_read'] },
    configPanels: [
      { id: 'model' },
      { id: 'systemPrompt' },
    ],
  },
  {
    type: 'output',
    label: 'Act',
    category: 'output',
    color: '#ff9800',
    icon: 'M13 7l5 5m0 0l-5 5m5-5H6',
    defaultConfig: { outputType: 'log', generateReadme: true, generateArchitecture: false },
    configPanels: [
      { id: 'output' },
    ],
  },
]

export function getBlockByType(type: string): BlockDefinition | undefined {
  return BLOCK_REGISTRY.find(b => b.type === type)
}

export function getBlockLabels(): Record<string, string> {
  const map: Record<string, string> = {}
  for (const b of BLOCK_REGISTRY) {
    map[b.type] = b.label
  }
  return map
}
