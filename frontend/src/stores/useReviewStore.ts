import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/services/api'
import { useToast } from '@/composables/useToast'

const { error: toastError } = useToast()

export interface ReviewFinding {
  source: string
  severity: string
  description: string
  suggestion: string
}

export interface ReviewData {
  executionId: string
  nodeId: string
  originalPlan: string
  rewrittenPlan: string
  findings: ReviewFinding[]
  iteration: number
  maxIterations: number
}

export const useReviewStore = defineStore('review', () => {
  const pendingReview = ref(false)
  const reviewData = ref<ReviewData | null>(null)

  function handleReviewAwaitingApproval(data: ReviewData) {
    reviewData.value = data
    pendingReview.value = true
  }

  function clearReview() {
    pendingReview.value = false
    reviewData.value = null
  }

  async function approveReview(executionId: string, nodeId: string) {
    try {
      await api.post(`/execution/${executionId}/approve-review?nodeId=${nodeId}`)
      pendingReview.value = false
      reviewData.value = null
    } catch (err) {
      toastError('Failed to approve review: ' + ((err as Error).message || err))
      throw err
    }
  }

  async function rejectReview(executionId: string, nodeId: string) {
    try {
      await api.post(`/execution/${executionId}/reject?nodeId=${nodeId}`)
      pendingReview.value = false
      reviewData.value = null
    } catch (err) {
      toastError('Failed to reject review: ' + ((err as Error).message || err))
      throw err
    }
  }

  return {
    pendingReview,
    reviewData,
    handleReviewAwaitingApproval,
    clearReview,
    approveReview,
    rejectReview,
  }
})
