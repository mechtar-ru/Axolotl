import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSelectionStore } from '../useSelectionStore'

describe('useSelectionStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const mockNode = {
    id: 'node-1',
    type: 'agent',
    position: { x: 100, y: 200 },
    data: { model: 'test-model' },
    selected: true,
  }

  const mockEdge = {
    id: 'edge-1',
    source: 'node-1',
    target: 'node-2',
  }

  it('starts with no selection', () => {
    const store = useSelectionStore()
    expect(store.selectedNode).toBeNull()
    expect(store.selectedEdge).toBeNull()
  })

  it('setSelectedNode stores node data', () => {
    const store = useSelectionStore()
    store.setSelectedNode(mockNode as any)
    expect(store.selectedNode).toEqual({
      id: 'node-1',
      type: 'agent',
      data: { model: 'test-model' },
      position: { x: 100, y: 200 },
    })
  })

  it('setSelectedNode clears edge selection', () => {
    const store = useSelectionStore()
    store.setSelectedEdge(mockEdge as any)
    store.setSelectedNode(mockNode as any)
    expect(store.selectedEdge).toBeNull()
  })

  it('setSelectedNode with null clears node', () => {
    const store = useSelectionStore()
    store.setSelectedNode(mockNode as any)
    store.setSelectedNode(null)
    expect(store.selectedNode).toBeNull()
  })

  it('setSelectedEdge stores edge data and clears node', () => {
    const store = useSelectionStore()
    store.setSelectedNode(mockNode as any)
    store.setSelectedEdge(mockEdge as any)
    expect(store.selectedEdge).toEqual({ id: 'edge-1', source: 'node-1', target: 'node-2' })
    expect(store.selectedNode).toBeNull()
  })

  it('setSelectedEdge with null clears edge', () => {
    const store = useSelectionStore()
    store.setSelectedEdge(mockEdge as any)
    store.setSelectedEdge(null)
    expect(store.selectedEdge).toBeNull()
  })

  it('clearSelection clears both', () => {
    const store = useSelectionStore()
    store.setSelectedNode(mockNode as any)
    store.setSelectedEdge(mockEdge as any)
    store.clearSelection()
    expect(store.selectedNode).toBeNull()
    expect(store.selectedEdge).toBeNull()
  })

  it('handles node without position', () => {
    const store = useSelectionStore()
    store.setSelectedNode({ id: 'no-pos', type: 'draft' } as any)
    expect(store.selectedNode!.position).toEqual({ x: 0, y: 0 })
  })

  it('handles edge with minimal fields', () => {
    const store = useSelectionStore()
    store.setSelectedEdge({ id: 'min-edge', source: 'a', target: 'b' } as any)
    expect(store.selectedEdge).toEqual({ id: 'min-edge', source: 'a', target: 'b' })
  })
})
