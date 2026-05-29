import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useReviewStore } from '../useReviewStore'
import { api } from '@/services/api'

vi.mock('@/services/api', () => ({
  api: {
    post: vi.fn(),
  },
  useToast: vi.fn(() => ({ error: vi.fn() })),
}))

describe('useReviewStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(api.post).mockResolvedValue({ data: {} })
  })

  const mockReviewData = {
    executionId: 'exec-1',
    nodeId: 'review-1',
    originalPlan: 'Create a game',
    rewrittenPlan: 'Create Sokoban',
    findings: [{ source: 'premortem', severity: 'HIGH', description: 'Missing validation', suggestion: 'Add validation' }],
    iteration: 2,
    maxIterations: 3,
  }

  it('starts with no pending review', () => {
    const store = useReviewStore()
    expect(store.pendingReview).toBe(false)
    expect(store.reviewData).toBeNull()
  })

  it('handleReviewAwaitingApproval sets review data and pending flag', () => {
    const store = useReviewStore()
    store.handleReviewAwaitingApproval(mockReviewData)
    expect(store.pendingReview).toBe(true)
    expect(store.reviewData).toEqual(mockReviewData)
  })

  it('clearReview resets state', () => {
    const store = useReviewStore()
    store.handleReviewAwaitingApproval(mockReviewData)
    store.clearReview()
    expect(store.pendingReview).toBe(false)
    expect(store.reviewData).toBeNull()
  })

  it('approveReview posts and clears state', async () => {
    const store = useReviewStore()
    store.handleReviewAwaitingApproval(mockReviewData)
    await store.approveReview('exec-1', 'review-1')
    expect(api.post).toHaveBeenCalledWith('/execution/exec-1/approve-review?nodeId=review-1')
    expect(store.pendingReview).toBe(false)
    expect(store.reviewData).toBeNull()
  })

  it('rejectReview posts and clears state', async () => {
    const store = useReviewStore()
    store.handleReviewAwaitingApproval(mockReviewData)
    await store.rejectReview('exec-1', 'review-1')
    expect(api.post).toHaveBeenCalledWith('/execution/exec-1/reject?nodeId=review-1')
    expect(store.pendingReview).toBe(false)
    expect(store.reviewData).toBeNull()
  })

  it('approveReview throws on failure', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('Network error'))
    const store = useReviewStore()
    store.handleReviewAwaitingApproval(mockReviewData)
    await expect(store.approveReview('exec-1', 'review-1')).rejects.toThrow('Network error')
    expect(store.pendingReview).toBe(true)
  })
})
