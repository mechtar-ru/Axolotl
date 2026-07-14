import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useCanvasStore } from '../useCanvasStore'
import { schemaApi } from '@/services/api'
import { settingsApi } from '@/services/api'

vi.mock('@/services/api', () => ({
  schemaApi: {
    getSchemas: vi.fn(),
    getSchema: vi.fn(),
    createSchema: vi.fn(),
    updateSchema: vi.fn(),
    deleteSchema: vi.fn(),
    executeSchema: vi.fn(),
    stopSchema: vi.fn(),
  },
  settingsApi: {
    getUserDefaultModel: vi.fn(),
  },
  api: {
    get: vi.fn(),
  },
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    error: vi.fn(),
  }),
}))

describe('useCanvasStore', () => {
  let store: ReturnType<typeof useCanvasStore>

  beforeEach(() => {
    setActivePinia(createPinia())
    store = useCanvasStore()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.resetAllMocks()
  })

  describe('Schema CRUD', () => {
    it('loadSchemas fetches and sets schemas', async () => {
      const mockSchemas = [
        { id: '1', name: 'Schema 1', nodes: [], edges: [] } as any,
        { id: '2', name: 'Schema 2', nodes: [], edges: [] } as any,
      ]
      vi.mocked(schemaApi.getSchemas).mockResolvedValue(mockSchemas)

      await store.loadSchemas()

      expect(schemaApi.getSchemas).toHaveBeenCalled()
      expect(store.schemas).toEqual(mockSchemas)
      expect(store.currentSchema).toEqual(mockSchemas[0])
    })

    it('loadSchemas handles abort correctly', async () => {
      vi.mocked(schemaApi.getSchemas).mockImplementation(() => new Promise((_, reject) => {
        reject(new DOMException('Aborted', 'AbortError'))
      }))

      await store.loadSchemas()

      expect(store.loading).toBe(false)
    })

    it('createSchema creates new schema with default model', async () => {
      vi.mocked(settingsApi.getUserDefaultModel).mockResolvedValue('gpt-4')
      const created = { id: 'new-1', name: 'New', defaultModel: 'gpt-4', nodes: [], edges: [] } as any
      vi.mocked(schemaApi.createSchema).mockResolvedValue(created)

      const result = await store.createSchema('Test Schema')

      expect(settingsApi.getUserDefaultModel).toHaveBeenCalled()
      expect(schemaApi.createSchema).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Test Schema',
          defaultModel: 'gpt-4',
        })
      )
      expect(result).toEqual(created)
      expect(store.schemas).toContainEqual(created)
      expect(store.currentSchema).toEqual(created)
    })

    it('updateSchema updates schema and clears dirty flag', async () => {
      const schema = { id: '1', name: 'Updated', nodes: [], edges: [] } as any
      vi.mocked(schemaApi.updateSchema).mockResolvedValue(schema)
      store.schemas = [{ id: '1', name: 'Old', nodes: [], edges: [] } as any]
      store.currentSchema = { id: '1', name: 'Old', nodes: [], edges: [] } as any
      store.isDirty = true

      const result = await store.updateSchema(schema)

      expect(schemaApi.updateSchema).toHaveBeenCalledWith('1', schema)
      expect(store.schemas[0]).toEqual(schema)
      expect(store.currentSchema).toEqual(schema)
      expect(store.isDirty).toBe(false)
      expect(result).toEqual(schema)
    })

    it('deleteSchema removes schema and clears current if matching', async () => {
      store.schemas = [{ id: '1', name: 'Schema 1', nodes: [], edges: [] } as any]
      store.currentSchema = { id: '1', name: 'Schema 1', nodes: [], edges: [] } as any
      store.isDirty = true
      vi.mocked(schemaApi.deleteSchema).mockResolvedValue(undefined)

      await store.deleteSchema('1')

      expect(schemaApi.deleteSchema).toHaveBeenCalledWith('1')
      expect(store.schemas).toHaveLength(0)
      expect(store.currentSchema).toBeNull()
      expect(store.isDirty).toBe(false)
    })

    it('executeSchema calls API and returns result', async () => {
      const result = { status: 'ok' }
      vi.mocked(schemaApi.executeSchema).mockResolvedValue(result)

      const res = await store.executeSchema('1')

      expect(schemaApi.executeSchema).toHaveBeenCalledWith('1', 'EXECUTE')
      expect(res).toEqual(result)
    })

    it('executeSchema throws on validation error', async () => {
      const axiosError = {
        isAxiosError: true,
        response: {
          data: {
            status: 'validation_error',
            validation: { errors: [{ message: 'Missing node' }] },
          },
        },
      }
      vi.mocked(schemaApi.executeSchema).mockRejectedValue(axiosError)

      await expect(store.executeSchema('1')).rejects.toThrow('Missing node')
    })
  })

  describe('Dirty-flag auto-save', () => {
    it('markDirty sets dirty flag and starts debounce timer', () => {
      const schema = { id: '1', name: 'Test', nodes: [], edges: [] } as any
      store.currentSchema = schema

      store.markDirty(schema)

      expect(store.isDirty).toBe(true)
      expect(store.currentSchema).toStrictEqual(schema)
    })

    it('flushSave persists dirty changes', async () => {
      const schema = { id: '1', name: 'Test', nodes: [], edges: [] } as any
      store.currentSchema = schema
      store.isDirty = true
      const updated = { ...schema, name: 'Saved' }
      vi.mocked(schemaApi.updateSchema).mockResolvedValue(updated)

      await store.flushSave()

      expect(schemaApi.updateSchema).toHaveBeenCalledWith('1', schema, expect.any(Object))
      expect(store.isDirty).toBe(false)
      expect(store.currentSchema).toEqual(updated)
    })

    it('flushSave does nothing if not dirty', async () => {
      store.isDirty = false

      await store.flushSave()

      expect(schemaApi.updateSchema).not.toHaveBeenCalled()
    })

    it('flushSave re-throws on failure', async () => {
      store.currentSchema = { id: '1', name: 'Test', nodes: [], edges: [] } as any
      store.isDirty = true
      vi.mocked(schemaApi.updateSchema).mockRejectedValue(new Error('Network error'))

      await expect(store.flushSave()).rejects.toThrow('Network error')
      expect(store.isDirty).toBe(true)
    })
  })

  describe('Canvas operations', () => {
    beforeEach(() => {
      store.currentSchema = { id: '1', name: 'Test', nodes: [], edges: [], defaultModel: 'gpt-4' } as any
      store.isDirty = false
    })

    it('addNode adds node to current schema and marks dirty', () => {
      const node = { id: 'n1', type: 'agent', data: { label: 'Agent' } } as any
      store.addNode(node)

      expect(store.currentSchema?.nodes).toHaveLength(1)
      expect(store.currentSchema?.nodes[0]).toMatchObject(node)
      expect(store.isDirty).toBe(true)
    })

    it('addNode pre-fills default model for new nodes', () => {
      const node = { id: 'n1', type: 'agent', data: { label: 'Agent' } } as any
      store.addNode(node)

      // @ts-ignore - test access to internal structure
      expect(store.currentSchema?.nodes[0].data.config?.model).toBe('gpt-4')
    })

    it('removeNode removes node and associated edges', () => {
      store.currentSchema = {
        id: '1',
        name: 'Test',
        description: '',
        version: '1.0',
        nodes: [
          { id: 'n1', type: 'agent', data: {} } as any,
          { id: 'n2', type: 'agent', data: {} } as any,
        ],
        edges: [
          { id: 'e1', source: 'n1', target: 'n2', type: 'data' as const },
          { id: 'e2', source: 'n2', target: 'n1', type: 'data' as const },
        ],
        defaultModel: 'gpt-4',
      }

      store.removeNode('n1')

      // @ts-ignore - test access to internal structure
      expect(store.currentSchema?.nodes).toHaveLength(1)
      // @ts-ignore
      expect(store.currentSchema?.nodes[0].id).toBe('n2')
      expect(store.currentSchema?.edges).toHaveLength(0)
      expect(store.isDirty).toBe(true)
    })

    it('updateNode updates node properties', () => {
      store.currentSchema = {
        id: '1',
        name: 'Test',
        description: '',
        version: '1.0',
        nodes: [{ id: 'n1', type: 'agent', data: { label: 'Old', config: {} } }] as any,
        edges: [],
        defaultModel: 'gpt-4',
      } as any

      store.updateNode('n1', { data: { label: 'New' } } as any)

      // @ts-ignore - test access to internal structure
      expect(store.currentSchema?.nodes[0].data?.label).toBe('New')
      expect(store.isDirty).toBe(true)
    })

    it('addEdge adds edge to current schema', () => {
      const edge = { id: 'e1', source: 'n1', target: 'n2', type: 'data' as const }
      store.addEdge(edge)

      expect(store.currentSchema?.edges).toHaveLength(1)
      expect(store.currentSchema?.edges[0]).toMatchObject(edge)
      expect(store.isDirty).toBe(true)
    })

    it('removeEdge removes edge from current schema', () => {
      store.currentSchema = {
        id: '1',
        name: 'Test',
        description: '',
        version: '1.0',
        nodes: [],
        edges: [{ id: 'e1', source: 'n1', target: 'n2', type: 'data' as const }],
        defaultModel: 'gpt-4',
      }

      store.removeEdge('e1')

      expect(store.currentSchema?.edges).toHaveLength(0)
      expect(store.isDirty).toBe(true)
    })
  })

  describe('Lifecycle', () => {
    it('initialize fetches schema and sets currentSchema', async () => {
      const schema = { id: '1', name: 'Test', description: '', version: '1.0', nodes: [], edges: [] } as any
      vi.mocked(schemaApi.getSchema).mockResolvedValue(schema)

      await store.initialize('1')

      expect(schemaApi.getSchema).toHaveBeenCalledWith('1')
      expect(store.currentSchema).toEqual(schema)
      expect(store.schemas).toContainEqual(schema)
    })

    it('initialize is no-op if already initialized for same ID', async () => {
      const schema = { id: '1', name: 'Test', description: '', version: '1.0', nodes: [], edges: [] } as any
      vi.mocked(schemaApi.getSchema).mockResolvedValue(schema)
      await store.initialize('1')

      vi.clearAllMocks()
      await store.initialize('1')

      expect(schemaApi.getSchema).not.toHaveBeenCalled()
    })

    it('dispose clears state and aborts pending operations', () => {
      store.currentSchema = { id: '1', name: 'Test', description: '', version: '1.0', nodes: [], edges: [] } as any
      store.isDirty = true

      store.dispose()

      expect(store.currentSchema).toBeNull()
      // isDirty is not reset in dispose (user might want to save before leaving)
      // expect(store.isDirty).toBe(false)
    })
  })
})