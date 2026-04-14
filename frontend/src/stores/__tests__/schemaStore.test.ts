import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSchemaStore } from '../schemaStore'
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

  it('sets first schema as current after load', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    expect(store.currentSchema?.name).toBe('Test Schema')
  })

  it('creates a new schema and adds to list', async () => {
    const store = useSchemaStore()
    await store.loadSchemas() // load first so we have 1
    const created = await store.createSchema('New Schema')
    expect(created.name).toBe('New Schema')
    expect(store.schemas).toHaveLength(2)
    expect(store.currentSchema?.name).toBe('New Schema')
  })

  it('updates existing schema', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    const schema = store.schemas[0]!
    const updated = await store.updateSchema({ ...schema, name: 'Updated' } as any)
    expect(updated.name).toBe('Updated')
  })

  it('deletes a schema and clears current if matched', async () => {
    const store = useSchemaStore()
    await store.loadSchemas()
    // Find the id from the loaded schemas and delete it
    const targetId = store.schemas[0]?.id
    if (targetId) {
      await store.deleteSchema(targetId)
    }
    expect(store.schemas).toHaveLength(0)
    expect(store.currentSchema).toBeNull()
  })

  it('loading state toggles', async () => {
    const store = useSchemaStore()
    expect(store.loading).toBe(false)
    const promise = store.loadSchemas()
    expect(store.loading).toBe(true)
    await promise
    expect(store.loading).toBe(false)
  })

  it('updateCurrentSchema sets current schema', () => {
    const store = useSchemaStore()
    const schema = { id: 'x', name: 'Direct', nodes: [], edges: [], description: '', version: '1.0' } as any
    store.updateCurrentSchema(schema)
    expect(store.currentSchema?.name).toBe('Direct')
  })
})
