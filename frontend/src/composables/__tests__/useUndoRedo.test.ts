import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { ref, type Ref } from 'vue'
import { useUndoRedo } from '../useUndoRedo'

describe('useUndoRedo', () => {
  let nodes: Ref<any[]>
  let edges: Ref<any[]>
  let setNodes: (nodes: any[]) => void
  let setEdges: (edges: any[]) => void
  let setNodesMock: ReturnType<typeof vi.fn>
  let setEdgesMock: ReturnType<typeof vi.fn>
  let undoRedo: ReturnType<typeof useUndoRedo>

  beforeEach(() => {
    vi.useFakeTimers()
    nodes = ref<any[]>([])
    nodes.value = [
      { id: 'a', type: 'source', position: { x: 0, y: 0 }, data: {}, selected: false },
    ]
    edges = ref<any[]>([])
    setNodesMock = vi.fn()
    setEdgesMock = vi.fn()
    setNodes = setNodesMock as (nodes: any[]) => void
    setEdges = setEdgesMock as (edges: any[]) => void
    undoRedo = useUndoRedo(setNodes, setEdges, nodes, edges)
  })

  afterEach(() => {
    vi.useRealTimers()
    undoRedo.reset()
  })

  it('starts with no undo/redo', () => {
    expect(undoRedo.canUndo.value).toBe(false)
    expect(undoRedo.canRedo.value).toBe(false)
  })

  it('captures after debounce delay', async () => {
    undoRedo.capture()
    expect(undoRedo.canUndo.value).toBe(false)
    await vi.advanceTimersByTimeAsync(500)
    expect(undoRedo.canUndo.value).toBe(true)
  })

  it('undo restores previous state', async () => {
    // Capture initial state
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)
    expect(undoRedo.canUndo.value).toBe(true)

    // Move node (no second capture — undo should go back to initial)
    nodes.value = [
      { id: 'a', type: 'source', position: { x: 100, y: 100 }, data: {}, selected: false },
    ]

    // Undo
    undoRedo.undo()
    expect(setNodesMock).toHaveBeenCalled()
    const calls = setNodesMock.mock.calls
    const restoredNodes = calls[calls.length - 1]![0]
    expect(restoredNodes[0].position.x).toBe(0)
  })

  it('redo restores forward state', async () => {
    // Capture initial
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)

    // Move and capture
    nodes.value = [{ id: 'a', type: 'source', position: { x: 100, y: 100 }, data: {}, selected: false }]
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)

    // Undo — now move is captured; first undo pops the {x:100} back out
    undoRedo.undo()
    // Second undo pops the initial {x:0}
    undoRedo.undo()
    const calls1 = setNodesMock.mock.calls
    const restored = calls1[calls1.length - 1]![0]
    expect(restored[0].position.x).toBe(0)

    // Redo twice
    undoRedo.redo()
    undoRedo.redo()
    const calls2 = setNodesMock.mock.calls
    const forward = calls2[calls2.length - 1]![0]
    expect(forward[0].position.x).toBe(100)
  })

  it('future clears on new capture after undo', async () => {
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)

    nodes.value = [{ id: 'a', type: 'source', position: { x: 100, y: 100 }, data: {}, selected: false }]
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)

    undoRedo.undo()
    undoRedo.undo()

    undoRedo.undo()
    expect(undoRedo.canRedo.value).toBe(true)

    // New capture clears future
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)
    expect(undoRedo.canRedo.value).toBe(false)
  })

  it('skipCapture prevents recording on undo/redo', () => {
    undoRedo.capture()
    vi.advanceTimersByTime(500)

    undoRedo.undo()
    // Undo shouldn't create a new history entry that's immediately consumed
    expect(undoRedo.canUndo.value).toBe(false)
  })

  it('flushCapture forces immediate capture', () => {
    undoRedo.capture()
    // Before debounce fires
    expect(undoRedo.canUndo.value).toBe(false)
    undoRedo.flushCapture()
    expect(undoRedo.canUndo.value).toBe(true)
  })

  it('reset clears all history', async () => {
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)
    expect(undoRedo.canUndo.value).toBe(true)
    undoRedo.reset()
    expect(undoRedo.canUndo.value).toBe(false)
    expect(undoRedo.canRedo.value).toBe(false)
  })

  it('handles multiple captures with 50-entry max', () => {
    for (let i = 0; i < 55; i++) {
      undoRedo.capture()
      vi.advanceTimersByTime(500)
    }
    // Should keep at most 50
    expect(undoRedo.canUndo.value).toBe(true)
    // Can undo 50 times (past is ring buffer)
  })

  it('snapshot preserves only selective fields', async () => {
    nodes.value = [
      {
        id: 'n1',
        type: 'agent',
        position: { x: 10, y: 20 },
        data: { model: 'test' },
        selected: true,
        __vnode: {}, // VueFlow internal — should be stripped
        reactiveProxy: {}, // Another internal
      },
    ]
    undoRedo.capture()
    await vi.advanceTimersByTimeAsync(500)
    undoRedo.undo()
    const calls4 = setNodesMock.mock.calls
    const restored = calls4[calls4.length - 1]![0]
    expect(restored[0]).not.toHaveProperty('__vnode')
    expect(restored[0]).not.toHaveProperty('reactiveProxy')
    expect(restored[0].id).toBe('n1')
    expect(restored[0].data.model).toBe('test')
  })
})
