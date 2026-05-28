export interface Stage {
  id: string
  name: string
  nodeType: 'source' | 'review' | 'agent' | 'verifier' | 'output' | 'transform' | 'custom' | 'draft'
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
  /** When true, each branch expands to 4 stages: test → verify-test → impl → verify (TDD mode). */
  tddEnabled?: boolean
}

export interface StageResult {
  [stageId: string]: string
}

export interface PipelineStatus {
  running: boolean
  stageResults: StageResult
  /** Status of the most recent completed/cancelled/failed execution run: 'completed' | 'failed' | 'cancelled' | null */
  lastRunStatus?: string | null
  /** Error message from the most recent failed execution run */
  lastRunError?: string | null
}
