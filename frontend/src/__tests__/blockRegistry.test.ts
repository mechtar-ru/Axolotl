// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { 
  BLOCK_REGISTRY, 
  dynamicBlockRegistry, 
  registerBlock, 
  unregisterBlock, 
  getBlockByType, 
  getBlockByTypeAny, 
  allBlocks,
  getBlockLabels 
} from '@/blockRegistry'
import type { BlockDefinition } from '@/types/blockRegistry'

describe('blockRegistry', () => {
  beforeEach(() => {
    dynamicBlockRegistry.value = []
    vi.clearAllMocks()
  })

  describe('static registry', () => {
    it('contains all expected block types', () => {
      const types = BLOCK_REGISTRY.map(b => b.type)
      expect(types).toContain('source')
      expect(types).toContain('draft')
      expect(types).toContain('review')
      expect(types).toContain('agent')
      expect(types).toContain('verifier')
      expect(types).toContain('memory')
      expect(types).toContain('planner')
      expect(types).toContain('prep')
      expect(types).toContain('doc-agent')
      expect(types).toContain('output')
    })

    it('getBlockByType returns correct block', () => {
      const agent = getBlockByType('agent')
      expect(agent).toBeDefined()
      expect(agent?.type).toBe('agent')
      expect(agent?.label).toBe('Think')
    })

    it('getBlockByType returns undefined for unknown type', () => {
      const block = getBlockByType('nonexistent')
      expect(block).toBeUndefined()
    })

    it('getBlockLabels returns map of type to label', () => {
      const labels = getBlockLabels()
      expect(labels.source).toBe('Receive')
      expect(labels.agent).toBe('Think')
      expect(labels.review).toBe('Review')
    })
  })

  describe('dynamic registry', () => {
    it('starts empty', () => {
      expect(dynamicBlockRegistry.value).toHaveLength(0)
    })

    it('registerBlock adds new block', () => {
      const customBlock: BlockDefinition = {
        type: 'custom-block',
        label: 'Custom Block',
        category: 'execute',
        color: '#ff0000',
        icon: 'M0 0h24v24H0z',
        defaultConfig: {},
        configPanels: [{ id: 'model' }],
      }
      
      registerBlock(customBlock)
      
      expect(dynamicBlockRegistry.value).toHaveLength(1)
      expect(dynamicBlockRegistry.value[0]!.type).toBe('custom-block')
    })

    it('registerBlock replaces existing block with same type', () => {
      const block1: BlockDefinition = { 
        type: 'test', 
        label: 'Block 1', 
        category: 'execute', 
        color: '#000', 
        icon: '', 
        defaultConfig: {}, 
        configPanels: [] 
      }
      const block2: BlockDefinition = { 
        type: 'test', 
        label: 'Block 2', 
        category: 'execute', 
        color: '#fff', 
        icon: '', 
        defaultConfig: {}, 
        configPanels: [] 
      }
      
      registerBlock(block1)
      registerBlock(block2)
      
      expect(dynamicBlockRegistry.value).toHaveLength(1)
      expect(dynamicBlockRegistry.value[0]!.label).toBe('Block 2')
    })

    it('unregisterBlock removes block by type', () => {
      const block: BlockDefinition = { 
        type: 'test', 
        label: 'Test', 
        category: 'execute', 
        color: '#000', 
        icon: '', 
        defaultConfig: {}, 
        configPanels: [] 
      }
      
      registerBlock(block)
      expect(dynamicBlockRegistry.value).toHaveLength(1)
      
      unregisterBlock('test')
      expect(dynamicBlockRegistry.value).toHaveLength(0)
    })

    it('unregisterBlock is idempotent', () => {
      unregisterBlock('nonexistent')
      expect(dynamicBlockRegistry.value).toHaveLength(0)
    })
  })

  describe('combined registry (allBlocks)', () => {
    it('includes static and dynamic blocks', () => {
      const customBlock: BlockDefinition = { 
        type: 'custom-block', 
        label: 'Custom', 
        category: 'execute', 
        color: '#000', 
        icon: '', 
        defaultConfig: {}, 
        configPanels: [] 
      }
      
      registerBlock(customBlock)
      
      const combined = allBlocks.value
      const staticCount = BLOCK_REGISTRY.length
      
      expect(combined.length).toBe(staticCount + 1)
      expect(combined.some(b => b.type === 'custom-block')).toBe(true)
      expect(combined.some(b => b.type === 'agent')).toBe(true)
    })
  })

  describe('getBlockByTypeAny', () => {
    it('returns static block by type', () => {
      const block = getBlockByTypeAny('agent')
      expect(block).toBeDefined()
      expect(block?.type).toBe('agent')
    })

    it('returns dynamic block by type', () => {
      const customBlock: BlockDefinition = { 
        type: 'dynamic-test', 
        label: 'Dynamic', 
        category: 'execute', 
        color: '#000', 
        icon: '', 
        defaultConfig: {}, 
        configPanels: [] 
      }
      
      registerBlock(customBlock)
      
      const block = getBlockByTypeAny('dynamic-test')
      expect(block).toBeDefined()
      expect(block?.type).toBe('dynamic-test')
    })

    it('prefers dynamic block over static with same type', () => {
      const overrideBlock: BlockDefinition = { 
        type: 'agent', 
        label: 'Overridden Agent', 
        category: 'execute', 
        color: '#000', 
        icon: '', 
        defaultConfig: {}, 
        configPanels: [] 
      }
      
      registerBlock(overrideBlock)
      
      const block = getBlockByTypeAny('agent')
      expect(block).toBeDefined()
      expect(block?.label).toBe('Overridden Agent')
    })
  })
})