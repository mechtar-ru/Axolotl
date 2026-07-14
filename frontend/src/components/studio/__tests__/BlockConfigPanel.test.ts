// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock the API module
vi.mock('@/services/api', () => ({
  settingsApi: {
    getProviders: vi.fn().mockResolvedValue([
      { name: 'ollama', available: true, baseUrl: 'http://localhost:11434', models: ['llama3'], disabledModels: [] },
    ]),
  },
  schemaApi: {
    getSchemas: vi.fn().mockResolvedValue([]),
  },
}))

// Mock the schema store
vi.mock('@/stores/schemaStore', () => ({
  useSchemaStore: vi.fn(() => ({
    currentSchema: {
      id: 'test-1',
      name: 'Test Schema',
      nodes: [],
      edges: [],
      targetPath: '/Users/test/project',
    },
    updateSchema: vi.fn(),
  })),
}))

// Mock the canvas store
vi.mock('@/stores/useCanvasStore', () => ({
  useCanvasStore: vi.fn(() => ({
    currentSchema: {
      id: 'test-1',
      name: 'Test Schema',
      nodes: [],
      edges: [],
      targetPath: '/Users/test/project',
    },
    markDirty: vi.fn(),
    updateSchema: vi.fn(),
    autoSaveDebounceMs: 2000,
  })),
}))

// Mock the settings store
vi.mock('@/stores/settingsStore', () => ({
  useSettingsStore: vi.fn(() => ({
    getAllModelOptions: vi.fn(() => [
      { value: 'gpt-4', label: 'gpt-4', group: 'OpenAI' },
      { value: 'llama3', label: 'llama3', group: 'Ollama' },
    ]),
  })),
}))

// Mock VueFlow with a source node so the Input Type dropdown renders
vi.mock('@vue-flow/core', () => ({
  useVueFlow: vi.fn(() => ({
    nodes: { value: [
      {
        id: 'source-1',
        type: 'source',
        position: { x: 0, y: 0 },
        data: {
          label: 'My Source',
          type: 'source',
          config: {
            sourceType: 'text',
            sourceData: 'hello world',
          },
        },
      },
    ] },
    addNodes: vi.fn(),
    addEdges: vi.fn(),
    onConnect: vi.fn(),
    screenToFlowCoordinate: vi.fn(),
    fitView: vi.fn(),
  })),
}))

import BlockConfigPanel from '../BlockConfigPanel.vue'

describe('BlockConfigPanel', () => {
  const defaultProps = {
    blockId: 'source-1',
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders the panel header', async () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    await flushPromises()
    expect(wrapper.text()).toContain('Configure Block')
  })

  it('renders the block name input', async () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    await flushPromises()
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('renders the Input Type dropdown for source blocks', async () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    await flushPromises()
    const select = wrapper.find('select')
    expect(select.exists()).toBe(true)
    const options = wrapper.findAll('option')
    const optionTexts = options.map(o => o.text())
    expect(optionTexts).toEqual(
      expect.arrayContaining(['Chat / Text', 'File Reference', 'URL Fetch', 'Project Directory'])
    )
  })

  it('emits close when Escape key is pressed', async () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    await flushPromises()
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close when close button is clicked', async () => {
    const wrapper = mount(BlockConfigPanel, { props: defaultProps })
    await flushPromises()
    const closeBtn = wrapper.find('.close-btn')
    await closeBtn.trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
