// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import TimelineView from '../TimelineView.vue'
import { schemaApi } from '@/services/api'
import { useExecutionState } from '@/composables/useExecutionState'

vi.mock('@/services/api', () => ({
  schemaApi: {
    getRuns: vi.fn(),
    getRunNodes: vi.fn(),
    cleanupRuns: vi.fn(),
    deleteRun: vi.fn(),
    resumeSchema: vi.fn(),
  },
}))

vi.mock('@/composables/useExecutionState', () => ({
  useExecutionState: vi.fn(() => ({
    stepEvents: { value: [] },
  })),
}))

describe('TimelineView', () => {
  const mockRuns = [
    {
      id: 'run-1',
      schemaId: 'schema-1',
      status: 'completed',
      mode: 'EXECUTE',
      totalTokens: 1000,
      estimatedCost: 0.01,
      error: null,
      resumesFrom: null,
      startedAt: '2024-01-15T10:00:00Z',
      updatedAt: '2024-01-15T10:05:00Z',
      completedAt: '2024-01-15T10:05:00Z',
      sessionInput: 'Build a todo app',
    },
    {
      id: 'run-2',
      schemaId: 'schema-1',
      status: 'failed',
      mode: 'EXECUTE',
      totalTokens: 500,
      estimatedCost: 0.005,
      error: 'Node execution failed',
      resumesFrom: null,
      startedAt: '2024-01-14T10:00:00Z',
      updatedAt: '2024-01-14T10:02:00Z',
      completedAt: '2024-01-14T10:02:00Z',
      sessionInput: null,
    },
  ]

  const mockNodeExecutions = [
    {
      id: 'ne-1',
      runId: 'run-1',
      nodeId: 'source-1',
      nodeName: 'Receive',
      nodeType: 'source',
      status: 'completed',
      tokensUsed: 100,
      durationMs: 500,
      toolCalls: 0,
      error: null,
      inputSummary: null,
      outputSummary: 'Received input',
      filesWritten: null,
      configHash: 'abc123',
      startedAt: '2024-01-15T10:00:00Z',
      completedAt: '2024-01-15T10:00:00Z',
    },
    {
      id: 'ne-2',
      runId: 'run-1',
      nodeId: 'agent-1',
      nodeName: 'Think',
      nodeType: 'agent',
      status: 'completed',
      tokensUsed: 900,
      durationMs: 5000,
      toolCalls: 3,
      error: null,
      inputSummary: 'Plan: Build todo app',
      outputSummary: 'Created files',
      filesWritten: '["app.py"]',
      configHash: 'def456',
      startedAt: '2024-01-15T10:00:00Z',
      completedAt: '2024-01-15T10:05:00Z',
    },
  ]

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(schemaApi.getRuns).mockResolvedValue(mockRuns as any)
    vi.mocked(schemaApi.getRunNodes).mockResolvedValue(mockNodeExecutions as any)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders run history header', async () => {
    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Run History')
    expect(wrapper.text()).toContain('2') // count badge
  })

  it('shows loading state initially', async () => {
    vi.mocked(schemaApi.getRuns).mockImplementation(() => new Promise(() => {}))

    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.tl-loading').exists()).toBe(true)
  })

  it('runs fetchRuns on mount', async () => {
    mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(schemaApi.getRuns).toHaveBeenCalledWith('schema-1')
  })

  it('displays run cards with correct status colors', async () => {
    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    const cards = wrapper.findAll('.tl-card')
    expect(cards).toHaveLength(2)

    // First run - completed (green)
    expect(cards[0]!.find('.tl-dot').attributes('style')).toContain('rgb(76, 175, 80)')
    expect(cards[0]!.text()).toContain('Run 2: "Build a todo app"')

    // Second run - failed (red)
    expect(cards[1]!.find('.tl-dot').attributes('style')).toContain('rgb(239, 68, 68)')
    expect(cards[1]!.text()).toContain('error')
  })

  it('expands run card on click', async () => {
    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    // Directly set the expandedRunId to test the rendering
    ;(wrapper.vm as any).expandedRunId = 'run-1'
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.tl-card-body').exists()).toBe(true)
    expect(wrapper.findAll('.tl-card-body')).toHaveLength(1)
  })

  it('fetches node details when expanding run', async () => {
    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    await wrapper.find('.tl-card-header').trigger('click')
    await vi.waitFor(() => {
      expect(schemaApi.getRunNodes).toHaveBeenCalledWith('schema-1', 'run-1')
    })
    await flushPromises()
  })

  it('shows empty state when no runs', async () => {
    vi.mocked(schemaApi.getRuns).mockResolvedValue([])

    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.tl-empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('No runs yet')
  })

  it('shows error message on fetch failure', async () => {
    vi.mocked(schemaApi.getRuns).mockRejectedValue(new Error('Network error'))

    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.tl-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('Failed to load run history')
  })

  it('formats duration correctly', async () => {
    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    // First run: 5 minutes
    expect(wrapper.text()).toContain('5m')
  })

  it('shows Release Stale Runs button when stale runs exist', async () => {
    vi.mocked(schemaApi.getRuns).mockResolvedValue([
      { ...mockRuns[0], status: 'resuming' } as any,
    ])

    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.tl-btn-stale').exists()).toBe(true)
    expect(wrapper.text()).toContain('Release Stale Runs')
  })

  it('shows more button when more runs than display limit', async () => {
    const manyRuns = Array.from({ length: 15 }, (_, i) => ({
      ...mockRuns[0],
      id: `run-${i}`,
    })) as any
    vi.mocked(schemaApi.getRuns).mockResolvedValue(manyRuns)

    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.tl-more').exists()).toBe(true)
    expect(wrapper.text()).toContain('Show more')
  })

  it('increases display limit when show more clicked', async () => {
    const manyRuns = Array.from({ length: 15 }, (_, i) => ({
      ...mockRuns[0],
      id: `run-${i}`,
    })) as any
    vi.mocked(schemaApi.getRuns).mockResolvedValue(manyRuns)

    const wrapper = mount(TimelineView, {
      props: { schemaId: 'schema-1' },
      global: {
        provide: {
          isRunning: { value: false },
          startExecution: vi.fn(),
        },
      },
    })
    await flushPromises()

    await wrapper.find('.tl-more').trigger('click')
    await flushPromises()

    // Should show all 15 runs now
    expect(wrapper.findAll('.tl-card')).toHaveLength(15)
  })
})