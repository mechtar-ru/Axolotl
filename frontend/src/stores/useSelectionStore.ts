import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Node, Edge } from '@vue-flow/core'

export interface SelectedNode {
  id: string
  type: string
  data: Record<string, unknown>
  position: { x: number; y: number }
}

export interface SelectedEdge {
  id: string
  source: string
  target: string
}

export const useSelectionStore = defineStore('selection', () => {
  const selectedNode = ref<SelectedNode | null>(null)
  const selectedEdge = ref<SelectedEdge | null>(null)

  function setSelectedNode(node: Node | null) {
    if (!node) {
      selectedNode.value = null
      return
    }
    selectedNode.value = {
      id: node.id,
      type: node.type || '',
      data: (node.data as Record<string, unknown>) || {},
      position: { x: node.position?.x || 0, y: node.position?.y || 0 },
    }
    // Clear edge selection when a node is selected
    selectedEdge.value = null
  }

  function setSelectedEdge(edge: Edge | null) {
    if (!edge) {
      selectedEdge.value = null
      return
    }
    selectedEdge.value = {
      id: edge.id,
      source: edge.source,
      target: edge.target,
    }
    // Clear node selection when an edge is selected
    selectedNode.value = null
  }

  function clearSelection() {
    selectedNode.value = null
    selectedEdge.value = null
  }

  return {
    selectedNode,
    selectedEdge,
    setSelectedNode,
    setSelectedEdge,
    clearSelection,
  }
})
