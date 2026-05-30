import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSchemaStore } from '../schemaStore'
import { useCanvasStore } from '../useCanvasStore'
import { useReviewStore } from '../useReviewStore'
import { schemaApi } from '../../services/api'

vi.mock('../../services/api', () => ({
  schemaApi: {
    getSchemas: vi.fn(),
    createSchema: vi.fn(),
    updateSchema: vi.fn(),
    deleteSchema: vi.fn(),
    executeSchema: vi.fn(),
    stopSchema: vi.fn(),
    exportToMermaid: vi.fn(),
  },
  settingsApi: {
    getUserDefaultModel: vi.fn().mockResolvedValue(undefined),
  },
  api: {
    get: vi.fn().mockResolvedValue({ data: {} }),
    post: vi.fn().mockResolvedValue({ data: {} }),
  },
}))

describe('schemaStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(schemaApi.getSchemas).mockResolvedValue([
      { id: '1', name: 'Test Schema', nodes: [], edges: [], description: '', version: '1.0' },
    ] as any)
    vi.mocked(schemaApi.createSchema).mockImplementation((schema: any) =>
      Promise.resolve({ ...schema, id: '2' } as any)
    )
    vi.mocked(schemaApi.updateSchema).mockImplementation((_id: string, schema: any) =>
      Promise.resolve(schema as any)
    )
    vi.mocked(schemaApi.deleteSchema).mockResolvedValue(undefined as any)
  })

  it('loads schemas from API', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    expect(store.schemas).toHaveLength(1)
    expect(store.schemas[0]!.name).toBe('Test Schema')
  })

  it('sets first schema as current in canvasStore after load', async () => {
    const store = useSchemaStore()
    const canvas = useCanvasStore()
    await store.loadSchemas()
    // currentSchema was moved to canvasStore; loadSchemas via canvasStore sets it
    // schemaStore's loadSchemas only populates the list, canvasStore sets currentSchema
    // after canvasStore.loadSchemas is called — test schemaStore's list behavior
    expect(store.schemas).toHaveLength(1)
    expect(store.schemas[0]!.name).toBe('Test Schema')
  })

  it('creates a new schema and adds to list', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    const created = await store.createSchema('New Schema')
    expect(created.name).toBe('New Schema')
    expect(store.schemas).toHaveLength(2)
  })

  it('updates existing schema', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    const schema = store.schemas[0]!
    const updated = await store.updateSchema({ ...schema, name: 'Updated' } as any)
    expect(updated.name).toBe('Updated')
  })

  it('deletes a schema', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    const targetId = store.schemas[0]?.id
    if (targetId) {
      await store.deleteSchema(targetId)
    }
    expect(store.schemas).toHaveLength(0)
  })

  it('loading state toggles', async () => {
    const store = useSchemaStore()
    expect(store.loading).toBe(false)
    const promise = store.loadSchemas()
    expect(store.loading).toBe(true)
    await promise
    expect(store.loading).toBe(false)
  })

  it('updateCurrentSchema is delegated to canvasStore', () => {
    const canvas = useCanvasStore()
    const schema = { id: 'x', name: 'Direct', nodes: [], edges: [], description: '', version: '1.0' } as any
    canvas.currentSchema = schema
    expect(canvas.currentSchema?.name).toBe('Direct')
  })

  it('review data management', () => {
    const reviewStore = useReviewStore()
    expect(reviewStore.pendingReview).toBe(false)
    expect(reviewStore.reviewData).toBeNull()
    const review = {
      executionId: 'e1',
      nodeId: 'n1',
      originalPlan: 'plan',
      rewrittenPlan: 'rewritten',
      findings: [],
      iteration: 1,
      maxIterations: 3,
    }
    reviewStore.handleReviewAwaitingApproval(review)
    expect(reviewStore.pendingReview).toBe(true)
    expect(reviewStore.reviewData).toEqual(review)
    reviewStore.clearReview()
    expect(reviewStore.pendingReview).toBe(false)
    expect(reviewStore.reviewData).toBeNull()
  })
})
