import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePipelineStore } from '../usePipelineStore'
import { api } from '@/services/api'

vi.mock('@/services/api', () => ({
  api: {
    post: vi.fn(),
    get: vi.fn(),
  },
  useToast: vi.fn(() => ({ error: vi.fn() })),
}))

describe('usePipelineStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.post).mockResolvedValue({ data: {} })
    vi.mocked(api.get).mockResolvedValue({ data: { running: false, stageResults: {} } })
  })

  it('starts with idle pipeline status', () => {
    const store = usePipelineStore()
    expect(store.pipelineStatus.running).toBe(false)
    expect(store.pipelineExpanded).toBe(false)
  })

  it('buildPipelineNodes returns stub', async () => {
    const store = usePipelineStore()
    const result = await store.buildPipelineNodes('schema-1')
    expect(result).toEqual({ nodes: 0, edges: 0 })
  })

  it('executePipeline calls POST and sets running', async () => {
    const store = usePipelineStore()
    await store.executePipeline('schema-1')
    expect(api.post).toHaveBeenCalledWith('/schemas/schema-1/execute', { action: 'EXECUTE' })
    expect(store.pipelineStatus.running).toBe(true)
  })

  it('retryPipeline calls POST and sets running', async () => {
    const store = usePipelineStore()
    await store.retryPipeline('schema-1')
    expect(api.post).toHaveBeenCalledWith('/schemas/schema-1/pipeline/retry')
    expect(store.pipelineStatus.running).toBe(true)
  })

  it('cancelPipelineExecution calls POST and clears status', async () => {
    const store = usePipelineStore()
    await store.cancelPipelineExecution('schema-1')
    expect(api.post).toHaveBeenCalledWith('/schemas/schema-1/pipeline/cancel')
    expect(store.pipelineStatus.running).toBe(false)
  })

  it('refreshPipelineStatus fetches and sets status', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { running: true, stageResults: { stage1: 'completed' } } })
    const store = usePipelineStore()
    await store.refreshPipelineStatus('schema-1')
    expect(api.get).toHaveBeenCalledWith('/schemas/schema-1/pipeline/status')
    expect(store.pipelineStatus.running).toBe(true)
    expect(store.pipelineStatus.stageResults!.stage1).toBe('completed')
  })

  it('createDefaultPipeline POSTs and fetches schema', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { id: 'schema-1', name: 'Updated', nodes: [], edges: [] } })
    const store = usePipelineStore()
    await store.createDefaultPipeline('schema-1', 'webapp', 'My app', true)
    expect(api.post).toHaveBeenCalledWith('/schemas/schema-1/pipeline/default', {
      appType: 'webapp',
      description: 'My app',
      tddEnabled: true,
    })
    expect(api.get).toHaveBeenCalledWith('/schemas/schema-1')
    expect(store.pipelineStatus.running).toBe(false)
  })

  it('executePipeline throws on validation error', async () => {
    vi.mocked(api.post).mockRejectedValue({
      response: {
        data: {
          status: 'validation_error',
          validation: { errors: [{ message: 'Missing edge' }] },
        },
      },
    })
    const store = usePipelineStore()
    await expect(store.executePipeline('schema-1')).rejects.toThrow('Missing edge')
  })
})
