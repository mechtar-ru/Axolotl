export interface AppTemplate {
  id: string
  name: string
  description: string
  appType: 'CHAT' | 'ANALYZER' | 'GENERATOR' | 'EMAIL' | 'GAME' | 'CUSTOM'
  defaultNodes: Array<{
    id: string
    type: string
    name: string
    position: { x: number; y: number }
    data?: Record<string, unknown>
    agentType?: string
  }>
  defaultEdges: Array<{
    id: string
    source: string
    target: string
  }>
}

export const templates: AppTemplate[] = [
  {
    id: 'template-chat',
    name: 'Chat Bot',
    description: 'AI chatbot with conversation memory',
    appType: 'CHAT',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'User Message',
        position: { x: 100, y: 200 },
        data: { sourceData: 'User chat input' }
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'AI Chat',
        position: { x: 450, y: 200 },
        data: {
          systemPrompt: 'You are a helpful assistant. Be concise and friendly.',
          userPrompt: '{{input}}',
          model: 'local'
        }
      },
      {
        id: 'memory-1',
        type: 'memory',
        name: 'Conversation Memory',
        position: { x: 450, y: 400 },
        data: { memoryType: 'chat-history' }
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Send Reply',
        position: { x: 800, y: 200 },
        data: {}
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'think-1' },
      { id: 'e2', source: 'think-1', target: 'act-1' },
      { id: 'e3', source: 'think-1', target: 'memory-1' },
      { id: 'e4', source: 'memory-1', target: 'think-1' }
    ]
  },
  {
    id: 'template-doc',
    name: 'Document Analyzer',
    description: 'Analyze documents with AI extraction',
    appType: 'ANALYZER',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'Document Input',
        position: { x: 100, y: 200 },
        data: { sourceData: 'Uploaded document content' }
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'Analyze Content',
        position: { x: 450, y: 200 },
        data: {
          systemPrompt: 'You are a document analyst. Extract key information, summarize, and identify important findings.',
          userPrompt: 'Please analyze this document: {{input}}',
          model: 'local'
        }
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Analysis Result',
        position: { x: 800, y: 200 },
        data: {}
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'think-1' },
      { id: 'e2', source: 'think-1', target: 'act-1' }
    ]
  },
  {
    id: 'template-content',
    name: 'Content Generator',
    description: 'Generate articles, posts, and marketing copy',
    appType: 'GENERATOR',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'Content Brief',
        position: { x: 100, y: 200 },
        data: { sourceData: 'Topic, tone, and requirements' }
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'Generate Content',
        position: { x: 450, y: 200 },
        data: {
          systemPrompt: 'You are a professional content writer. Create engaging, well-structured content based on the brief.',
          userPrompt: 'Write content based on: {{input}}',
          model: 'local'
        }
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Final Content',
        position: { x: 800, y: 200 },
        data: {}
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'think-1' },
      { id: 'e2', source: 'think-1', target: 'act-1' }
    ]
  },
  {
    id: 'template-email',
    name: 'Email Agent',
    description: 'Smart email drafting and reply assistant',
    appType: 'EMAIL',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'Email Context',
        position: { x: 100, y: 200 },
        data: { sourceData: 'Email thread or instructions' }
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'Draft Email',
        position: { x: 450, y: 200 },
        data: {
          systemPrompt: 'You are an email assistant. Draft professional, clear emails based on context and instructions.',
          userPrompt: 'Draft an email based on: {{input}}',
          model: 'local'
        }
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Email Output',
        position: { x: 800, y: 200 },
        data: {}
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'think-1' },
      { id: 'e2', source: 'think-1', target: 'act-1' }
    ]
  },
  {
    id: 'template-sokoban',
    name: 'Sokoban Game',
    description: 'Generate a playable Sokoban puzzle game from grid parameters',
    appType: 'GAME',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'Game Parameters',
        position: { x: 100, y: 200 },
        data: { sourceData: 'Grid size, level layout, and game rules' }
      },
      {
        id: 'review-1',
        type: 'review',
        name: 'Review Plan',
        position: { x: 350, y: 50 },
        data: {
          checks: {
            premortem: true,
            prism: false,
            postmortem: false
          },
          mode: 'manual',
          maxAutoIterations: 3,
          generatePlan: true
        },
        agentType: 'review'
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'Generate Game',
        position: { x: 350, y: 350 },
        data: {
          systemPrompt: 'You are a game developer. Generate a complete playable Sokoban game as HTML with embedded CSS and JavaScript. The game must include: a grid-based level, player character, walls, boxes, target spaces, movement controls (arrow keys), undo functionality, level reset, move counter, and victory detection. Output ONLY the complete HTML file to the project target path using file_write.',
          userPrompt: 'Create a Sokoban game with these parameters:\n\nGrid: {{grid}}\nLevel: {{level}}\n\nGenerate a self-contained HTML file and write it to the project target path.',
          agentType: 'coder',
          enabledTools: ['file_write'],
          model: null
        }
      },
      {
        id: 'verifier-1',
        type: 'verifier',
        name: 'Verify Code',
        position: { x: 600, y: 200 },
        data: {
          checks: {
            syntaxCheck: true,
            testCommand: '',
            premortem: true
          },
          rewriteOnFail: true,
          maxRewriteRetries: 3
        },
        agentType: 'verifier'
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Pipeline Report',
        position: { x: 850, y: 200 },
        data: {
          mode: 'summary_report',
          reportPath: 'pipeline-report.md',
          includeReview: true,
          includeFiles: true,
          includeVerification: true,
          includeMetrics: true
        }
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'review-1' },
      { id: 'e2', source: 'review-1', target: 'think-1' },
      { id: 'e3', source: 'think-1', target: 'verifier-1' },
      { id: 'e4', source: 'verifier-1', target: 'act-1' }
    ]
  },
  {
    id: 'template-data',
    name: 'Data Extractor',
    description: 'Extract structured data from text',
    appType: 'ANALYZER',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'Source Text',
        position: { x: 100, y: 200 },
        data: { sourceData: 'Text to extract data from' }
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'Extract Data',
        position: { x: 450, y: 200 },
        data: {
          systemPrompt: 'You are a data extraction specialist. Extract structured information from unstructured text. Return data in JSON format.',
          userPrompt: 'Extract structured data from: {{input}}',
          model: 'local'
        }
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Structured Data',
        position: { x: 800, y: 200 },
        data: {}
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'think-1' },
      { id: 'e2', source: 'think-1', target: 'act-1' }
    ]
  },
  {
    id: 'template-blank',
    name: 'Blank App',
    description: 'Start from scratch with an empty canvas',
    appType: 'CUSTOM',
    defaultNodes: [],
    defaultEdges: []
  }
]

export function getTemplateById(id: string): AppTemplate | undefined {
  return templates.find(t => t.id === id)
}

export function getTemplatesByType(type: string): AppTemplate[] {
  return templates.filter(t => t.appType === type)
}
