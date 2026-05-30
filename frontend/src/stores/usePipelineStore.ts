import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/services/api'
import type { PipelineStatus } from '@/types/pipeline'
import { useToast } from '@/composables/useToast'
import { useCanvasStore } from './useCanvasStore'

const { error: toastError } = useToast()

export const usePipelineStore = defineStore('pipeline', () => {
  const pipelineStatus = ref<PipelineStatus>({ running: false, stageResults: {} })
  const pipelineExpanded = ref(false)

  // buildPipelineNodes is deprecated — all schemas use canvas-derived execution
  async function buildPipelineNodes(_schemaId: string) {
    return { nodes: 0, edges: 0 }
  }

  // executePipeline is deprecated — delegates to executeSchema which goes through PipelineService
  async function executePipeline(schemaId: string) {
    try {
      const result = await api.post(`/schemas/${schemaId}/execute`, { action: 'EXECUTE' })
      pipelineStatus.value = { running: true, stageResults: {} }
      return result.data
    } catch (err: any) {
      if (err?.response?.data?.status === 'validation_error') {
        const validation = err.response.data.validation
        const msgs = validation?.errors?.map((e: any) => e.message).join('; ') || 'Pipeline validation failed'
        toastError(msgs)
        throw new Error(msgs)
      }
      throw err
    }
  }

  async function cancelPipelineExecution(schemaId: string) {
    await api.post(`/schemas/${schemaId}/pipeline/cancel`)
    pipelineStatus.value = { running: false, stageResults: {} }
  }

  async function retryPipeline(schemaId: string) {
    await api.post(`/schemas/${schemaId}/pipeline/retry`)
    pipelineStatus.value = { running: true, stageResults: {} }
  }

  async function refreshPipelineStatus(schemaId: string) {
    const res = await api.get(`/schemas/${schemaId}/pipeline/status`)
    pipelineStatus.value = res.data
  }

  async function createDefaultPipeline(schemaId: string, appType?: string, description?: string, tddEnabled?: boolean) {
    await api.post(`/schemas/${schemaId}/pipeline/default`, {
      appType: appType || 'custom',
      description: description || '',
      tddEnabled: !!tddEnabled
    })
    // Re-fetch the schema to get the persisted pipeline
    const resp = await api.get(`/schemas/${schemaId}`)
    const canvasStore = useCanvasStore()
    if (resp.data) {
      canvasStore.currentSchema = resp.data
    }
    // Reset execution state — no run in progress after creation
    pipelineStatus.value = { running: false, stageResults: {} }
  }

  return {
    pipelineStatus,
    pipelineExpanded,
    buildPipelineNodes,
    executePipeline,
    cancelPipelineExecution,
    retryPipeline,
    refreshPipelineStatus,
    createDefaultPipeline,
  }
})
