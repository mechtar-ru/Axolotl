import { ref, computed, onMounted, onUnmounted, type Ref } from 'vue'

/**
 * Selective clone — only the fields needed for undo/restore.
 * Avoids cloning VueFlow internal state (reactive proxies, _marker, __vnode).
 */
function snapshotNodes(nodes: any[]): any[] {
  return nodes.map(n => ({
    id: n.id,
    type: n.type,
    position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
    data: JSON.parse(JSON.stringify(n.data ?? {})),
    selected: !!n.selected,
  }))
}

function snapshotEdges(edges: any[]): any[] {
  return edges.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle,
    targetHandle: e.targetHandle,
    type: e.type,
    selected: !!e.selected,
  }))
}

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
  let captureTimer: ReturnType<typeof setTimeout> | null = null

  const canUndo = computed(() => past.value.length > 0)
  const canRedo = computed(() => future.value.length > 0)

  function capture() {
    if (skipCapture) return
    if (captureTimer) {
      clearTimeout(captureTimer)
    }
    captureTimer = setTimeout(() => {
      past.value.push({
        nodes: snapshotNodes(nodesRef.value),
        edges: snapshotEdges(edgesRef.value),
      })
      if (past.value.length > maxHistory) {
        past.value.shift()
      }
      future.value = []
      captureTimer = null
    }, 500)
  }

  function flushCapture() {
    if (captureTimer) {
      clearTimeout(captureTimer)
      captureTimer = null
      past.value.push({
        nodes: snapshotNodes(nodesRef.value),
        edges: snapshotEdges(edgesRef.value),
      })
      if (past.value.length > maxHistory) {
        past.value.shift()
      }
      future.value = []
    }
  }

  function undo() {
    flushCapture()
    if (!canUndo.value) return
    future.value.push({
      nodes: snapshotNodes(nodesRef.value),
      edges: snapshotEdges(edgesRef.value),
    })
    const prev = past.value.pop()!
    skipCapture = true
    setNodes(prev.nodes)
    setEdges(prev.edges)
    skipCapture = false
  }

  function redo() {
    if (!canRedo.value) return
    past.value.push({
      nodes: snapshotNodes(nodesRef.value),
      edges: snapshotEdges(edgesRef.value),
    })
    const next = future.value.pop()!
    skipCapture = true
    setNodes(next.nodes)
    setEdges(next.edges)
    skipCapture = false
  }

  function reset() {
    past.value = []
    future.value = []
    skipCapture = false
    if (captureTimer) {
      clearTimeout(captureTimer)
      captureTimer = null
    }
  }

  // ─── Keyboard Handler ────────────────────────────────────────
  function onKeydown(e: KeyboardEvent) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'z') {
      if (e.shiftKey) {
        // Ctrl+Shift+Z = redo
        e.preventDefault()
        redo()
      } else {
        // Ctrl+Z = undo
        e.preventDefault()
        undo()
      }
    }
    if ((e.ctrlKey || e.metaKey) && e.key === 'y') {
      // Ctrl+Y = redo (Windows alternate)
      e.preventDefault()
      redo()
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', onKeydown)
  })

  onUnmounted(() => {
    window.removeEventListener('keydown', onKeydown)
    if (captureTimer) {
      clearTimeout(captureTimer)
      captureTimer = null
    }
  })

  return { canUndo, canRedo, undo, redo, capture, flushCapture, reset }
}
