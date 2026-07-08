// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'

// Mock settingsApi.getProviders so model options populate
vi.mock('@/services/api', () => ({
  settingsApi: {
    getProviders: vi.fn(() => Promise.resolve([
      {
        name: 'openai',
        models: ['gpt-4', 'gpt-4o', 'gpt-3.5-turbo'],
        disabledModels: [],
      },
    ])),
  },
}))

// Mock the schema store
vi.mock('@/stores/schemaStore', () => ({
  useSchemaStore: vi.fn(() => ({
    currentSchema: ref({
      id: 'test-1',
      name: 'Test Schema',
      description: 'A test schema',
      targetPath: '/Users/test/project',
      defaultModel: 'gpt-4',
      nodes: [],
      edges: [],
    }),
    updateSchema: vi.fn(),
    markDirty: vi.fn(),
  })),
}))

// Mock the settings store
vi.mock('@/stores/settingsStore', () => ({
  useSettingsStore: vi.fn(() => ({
    projectsFolder: '/Users/test/projects',
    providersLoaded: true,
    fetchProviders: vi.fn(),
    getAllModelOptions: vi.fn(() => [
      { value: 'gpt-4', label: 'gpt-4' },
      { value: 'deepseek-v4-flash-free', label: 'deepseek-v4-flash-free' },
    ]),
  })),
}))

// Mock the canvas store
vi.mock('@/stores/useCanvasStore', () => ({
  useCanvasStore: vi.fn(() => ({
    currentSchema: ref({
      id: 'test-1',
      name: 'Test Schema',
      description: 'A test schema',
      targetPath: '/Users/test/project',
      defaultModel: 'gpt-4',
      nodes: [],
      edges: [],
    }),
    markDirty: vi.fn(),
    updateSchema: vi.fn(),
  })),
}))


import SchemaPropertiesPanel from '../SchemaPropertiesPanel.vue'

describe('SchemaPropertiesPanel', () => {
  it('renders schema name', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    const input = wrapper.find('input')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('Test Schema')
  })

  it('renders schema description', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    expect(textarea.element.value).toBe('A test schema')
  })

  it('renders target path', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('/Users/test/project')
  })

  it('renders model dropdown with current model value', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    const select = wrapper.find('select')
    expect(select.exists()).toBe(true)
    // Wait for onMounted async API call to resolve
    await new Promise(resolve => setTimeout(resolve, 0))
    // gpt-4 should exist as an option from the mocked API
    const gptOption = wrapper.find('option[value="gpt-4"]')
    expect(gptOption.exists()).toBe(true)
  })

  it('renders quick action buttons', () => {
    const wrapper = mount(SchemaPropertiesPanel)
    expect(wrapper.text()).toContain('Add Node')
    expect(wrapper.text()).toContain('Run')
    expect(wrapper.text()).toContain('Quick Start')
  })

  it('emits addNode event when Add Node clicked', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    await wrapper.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('addNode')).toBeTruthy()
  })

  it('emits run event when Run clicked', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    // Find button with "Run" text
    const buttons = wrapper.findAll('button')
    const runBtn = buttons.find(b => b.text().includes('Run'))
    await runBtn?.trigger('click')
    expect(wrapper.emitted('run')).toBeTruthy()
  })

  it('emits quickStart event when Quick Start clicked', async () => {
    const wrapper = mount(SchemaPropertiesPanel)
    const buttons = wrapper.findAll('button')
    const qsBtn = buttons.find(b => b.text().includes('Quick Start'))
    await qsBtn?.trigger('click')
    expect(wrapper.emitted('quickStart')).toBeTruthy()
  })
})
