import { describe, it, expect } from 'vitest'
import { BLOCK_REGISTRY, getBlockByType, getBlockLabels } from '@/blockRegistry'

describe('blockRegistry', () => {
  it('has all 7 block definitions', () => {
    expect(BLOCK_REGISTRY).toHaveLength(7)
    const types = BLOCK_REGISTRY.map(b => b.type)
    expect(types).toContain('source')
    expect(types).toContain('draft')
    expect(types).toContain('review')
    expect(types).toContain('agent')
    expect(types).toContain('verifier')
    expect(types).toContain('memory')
    expect(types).toContain('output')
  })

  it('has required fields on every definition', () => {
    for (const block of BLOCK_REGISTRY) {
      expect(block.type).toBeTruthy()
      expect(block.label).toBeTruthy()
      expect(block.category).toBeTruthy()
      expect(block.color).toMatch(/^#[0-9a-f]{6}$/)
      expect(block.icon).toBeTruthy()
      expect(block.defaultConfig).toBeTruthy()
      expect(Array.isArray(block.configPanels)).toBe(true)
    }
  })

  it('categories are valid', () => {
    for (const block of BLOCK_REGISTRY) {
      expect(['receive', 'execute', 'analyze', 'output']).toContain(block.category)
    }
  })

  it('getBlockByType finds existing types', () => {
    const agent = getBlockByType('agent')
    expect(agent).toBeDefined()
    expect(agent!.label).toBe('Think')

    const output = getBlockByType('output')
    expect(output).toBeDefined()
    expect(output!.label).toBe('Act')
  })

  it('getBlockByType returns undefined for missing type', () => {
    expect(getBlockByType('nonexistent')).toBeUndefined()
  })

  it('getBlockLabels returns correct map', () => {
    const labels = getBlockLabels()
    expect(labels.source).toBe('Receive')
    expect(labels.agent).toBe('Think')
    expect(labels.review).toBe('Review')
    expect(labels.verifier).toBe('Verify')
    expect(labels.output).toBe('Act')
    expect(labels.draft).toBe('Draft')
    expect(labels.memory).toBe('Remember')
    expect(Object.keys(labels)).toHaveLength(7)
  })
})
