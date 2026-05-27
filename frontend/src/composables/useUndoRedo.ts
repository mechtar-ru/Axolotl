import { ref, computed, type Ref } from 'vue'

export function useUndoRedo(
  setNodes: (nodes: any[]) => void,
  setEdges: (edges: any[]) => void,
  nodesRef: Ref<any[]>,
  edgesRef: Ref<any[]>
) {
  const past = ref<{ nodes: any[]; edges: any[] }[]>([])
  const future = ref<{ nodes: any[]; edges: any[] }[]>([])
  const maxHistory = 50
  let skipCapture = false

  const canUndo = computed(() => past.value.length > 0)
  const canRedo = computed(() => future.value.length > 0)

  function clone(val: any): any {
    return JSON.parse(JSON.stringify(val))
  }

  function capture() {
    if (skipCapture) return
    past.value.push({
      nodes: clone(nodesRef.value),
      edges: clone(edgesRef.value)
    })
    if (past.value.length > maxHistory) {
      past.value.shift()
    }
    future.value = []
  }

  function undo() {
    if (!canUndo.value) return
    future.value.push({
      nodes: clone(nodesRef.value),
      edges: clone(edgesRef.value)
    })
    const prev = past.value.pop()!
    skipCapture = true
    setNodes(clone(prev.nodes))
    setEdges(clone(prev.edges))
    skipCapture = false
  }

  function redo() {
    if (!canRedo.value) return
    past.value.push({
      nodes: clone(nodesRef.value),
      edges: clone(edgesRef.value)
    })
    const next = future.value.pop()!
    skipCapture = true
    setNodes(clone(next.nodes))
    setEdges(clone(next.edges))
    skipCapture = false
  }

  function reset() {
    past.value = []
    future.value = []
    skipCapture = false
  }

  return { canUndo, canRedo, undo, redo, capture, reset }
}
