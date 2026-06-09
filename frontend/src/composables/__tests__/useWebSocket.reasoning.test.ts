import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useWebSocket, type WebSocketCallbacks } from '../useWebSocket'

describe('useWebSocket reasoning', () => {
  let wsRef: WebSocket | null
  let origWebSocket: typeof WebSocket

  beforeEach(() => {
    // Mock WebSocket to capture instance and prevent real connection
    wsRef = null
    origWebSocket = globalThis.WebSocket
    globalThis.WebSocket = class MockWebSocket {
      url: string
      onopen: (() => void) | null = null
      onmessage: ((event: MessageEvent) => void) | null = null
      onclose: ((event: CloseEvent) => void) | null = null
      onerror: (() => void) | null = null
      readyState: number = WebSocket.OPEN
      constructor(url: string) {
        this.url = url
        wsRef = this
        // Simulate open immediately
        setTimeout(() => this.onopen?.(), 0)
      }
      send() {}
      close() {}
      static readonly CONNECTING = 0
      static readonly OPEN = 1
      static readonly CLOSING = 2
      static readonly CLOSED = 3
    } as any
  })

  afterEach(() => {
    globalThis.WebSocket = origWebSocket
    vi.useRealTimers()
  })

  it('routes reasoning WS messages to onReasoning callback', async () => {
    vi.useFakeTimers()
    const ws = useWebSocket()
    const onReasoning = vi.fn()
    const callbacks: WebSocketCallbacks = {
      onProgress: vi.fn(),
      onResult: vi.fn(),
      onError: vi.fn(),
      onComplete: vi.fn(),
      onReasoning,
    }

    ws.connect('schema-1', callbacks)
    await vi.advanceTimersByTimeAsync(10) // let onopen fire

    // Simulate incoming reasoning message
    wsRef!.onmessage!(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'reasoning',
        schemaId: 'schema-1',
        nodeId: 'agent-1',
        reasoning: 'I think I should check the input first...'
      })
    }))

    expect(onReasoning).toHaveBeenCalledTimes(1)
    expect(onReasoning).toHaveBeenCalledWith({
      type: 'reasoning',
      schemaId: 'schema-1',
      nodeId: 'agent-1',
      reasoning: 'I think I should check the input first...'
    })
  })

  it('does not route non-reasoning messages to onReasoning', async () => {
    vi.useFakeTimers()
    const ws = useWebSocket()
    const onReasoning = vi.fn()
    const callbacks: WebSocketCallbacks = {
      onProgress: vi.fn(),
      onResult: vi.fn(),
      onError: vi.fn(),
      onComplete: vi.fn(),
      onReasoning,
    }

    ws.connect('schema-1', callbacks)
    await vi.advanceTimersByTimeAsync(10)

    // Simulate a progress message
    wsRef!.onmessage!(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'progress',
        schemaId: 'schema-1',
        nodeId: 'agent-1',
        status: 'running',
        progress: 50,
        message: 'Processing...'
      })
    }))

    expect(onReasoning).not.toHaveBeenCalled()
  })

  it('handles missing onReasoning callback gracefully', async () => {
    vi.useFakeTimers()
    const ws = useWebSocket()
    const callbacks: WebSocketCallbacks = {
      onProgress: vi.fn(),
      onResult: vi.fn(),
      onError: vi.fn(),
      onComplete: vi.fn(),
      // onReasoning intentionally omitted
    }

    ws.connect('schema-1', callbacks as any)
    await vi.advanceTimersByTimeAsync(10)

    // Should not throw
    expect(() => {
      wsRef!.onmessage!(new MessageEvent('message', {
        data: JSON.stringify({
          type: 'reasoning',
          schemaId: 'schema-1',
          nodeId: 'agent-1',
          reasoning: 'test reasoning'
        })
      }))
    }).not.toThrow()
  })

  it('calls onProgress for progress messages alongside reasoning', async () => {
    vi.useFakeTimers()
    const ws = useWebSocket()
    const onReasoning = vi.fn()
    const onProgress = vi.fn()
    const callbacks: WebSocketCallbacks = {
      onProgress,
      onResult: vi.fn(),
      onError: vi.fn(),
      onComplete: vi.fn(),
      onReasoning,
    }

    ws.connect('schema-1', callbacks)
    await vi.advanceTimersByTimeAsync(10)

    // Send two messages - progress then reasoning
    wsRef!.onmessage!(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'progress',
        schemaId: 'schema-1',
        nodeId: 'agent-1',
        status: 'running',
        progress: 50,
        message: 'Thinking...'
      })
    }))

    wsRef!.onmessage!(new MessageEvent('message', {
      data: JSON.stringify({
        type: 'reasoning',
        schemaId: 'schema-1',
        nodeId: 'agent-1',
        reasoning: 'My reasoning...'
      })
    }))

    expect(onProgress).toHaveBeenCalledTimes(1)
    expect(onReasoning).toHaveBeenCalledTimes(1)
  })
})
