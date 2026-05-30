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
