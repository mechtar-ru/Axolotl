export interface Stage {
  id: string
  name: string
  nodeType: 'source' | 'review' | 'agent' | 'verifier' | 'output' | 'transform' | 'custom'
  subagentSchemaId?: string
  model?: string
  systemPrompt?: string
  userPrompt?: string
  config?: Record<string, any>
  dependencies?: string[]
  inputMapping?: Record<string, string>
  outputMapping?: Record<string, string>
  condition?: string
  loopCondition?: string
  maxIterations?: number
  maxRetries?: number
  timeoutMs?: number
  parallel?: boolean
  positionX?: number
  positionY?: number
}

export interface Pipeline {
  id: string
  name: string
  description?: string
  stages: Stage[]
  config?: Record<string, any>
  parallelStrategy?: 'sequential' | 'parallel-stages'
  maxConcurrentStages?: number
}

export interface StageResult {
  [stageId: string]: string
}

export interface PipelineStatus {
  running: boolean
  stageResults: StageResult
}
