// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import QuickStartDialog from '../QuickStartDialog.vue'

// Mock the API module
vi.mock('@/services/api', () => ({
  schemaApi: {
    generateNodes: vi.fn(),
  },
  settingsApi: {
    getProviders: vi.fn().mockResolvedValue([
      {
        name: 'openai',
        available: true,
        baseUrl: 'https://api.openai.com',
        models: ['gpt-4', 'gpt-4o-mini'],
        defaultModel: 'gpt-4o-mini',
      },
      {
        name: 'anthropic',
        available: true,
        baseUrl: 'https://api.anthropic.com',
        models: ['claude-sonnet-4', 'claude-haiku-4'],
        defaultModel: 'claude-sonnet-4',
      },
    ]),
    getUserDefaultModel: vi.fn().mockResolvedValue(''),
  },
}))

import { schemaApi } from '@/services/api'

describe('QuickStartDialog', () => {
  const baseProps = {
    visible: true,
    appId: 'test-app-id',
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    // Clean up teleported content from document.body
    document.body.innerHTML = ''
  })

  it('renders when visible is true', async () => {
    mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    expect(document.body.textContent).toContain('Quick Start')
    expect(document.body.querySelector('textarea')).toBeTruthy()
  })

  it('does not render when visible is false', () => {
    mount(QuickStartDialog, { props: { ...baseProps, visible: false } })

    expect(document.body.querySelector('.quickstart-overlay')).toBeNull()
  })

  it('has disabled generate button when prompt is empty', async () => {
    mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    const generateBtn = document.body.querySelector<HTMLButtonElement>('.generate-btn')
    expect(generateBtn).toBeTruthy()
    expect(generateBtn!.disabled).toBe(true)
  })

  it('enables generate button when prompt has text', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    // Set prompt via wrapper.vm (exposed via defineExpose)
    wrapper.vm.prompt = 'Create a Sokoban game'
    await wrapper.vm.$nextTick()

    const generateBtn = document.body.querySelector<HTMLButtonElement>('.generate-btn')
    expect(generateBtn!.disabled).toBe(false)
  })

  it('calls generateNodes on generate click', async () => {
    const mockSchema = {
      id: 'test-app-id',
      name: 'Test App',
      description: '',
      version: '',
      nodes: [{ id: 'n1', type: 'agent', name: 'Agent 1', position: { x: 100, y: 200 }, data: {} }],
      edges: [],
    }

    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: true,
      schema: mockSchema,
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    // Set prompt and call generate via wrapper.vm
    wrapper.vm.prompt = 'Create a Sokoban game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    // The mock provider returns gpt-4o-mini as default model
    expect(schemaApi.generateNodes).toHaveBeenCalledWith(
      'test-app-id',
      'Create a Sokoban game',
      'gpt-4o-mini'
    )
  })

  it('shows result section after successful generation', async () => {
    const mockSchema = {
      id: 'test-app-id',
      name: 'Test App',
      nodes: [
        { id: 'n1', type: 'agent', name: 'Agent 1', position: { x: 100, y: 200 }, data: {} },
        { id: 'n2', type: 'source', name: 'Source 1', position: { x: 100, y: 300 }, data: {} },
        { id: 'n3', type: 'output', name: 'Output 1', position: { x: 100, y: 400 }, data: {} },
      ],
      edges: [
        { id: 'e1', source: 'n1', target: 'n2' },
      ],
    }

    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: true,
      schema: mockSchema,
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(document.body.textContent).toContain('Generated 3 nodes and 1 edge')
    expect(document.body.querySelector('.add-btn')).toBeTruthy()
    expect(document.body.querySelector('.regenerate-btn')).toBeTruthy()
  })

  it('shows error section on generation failure', async () => {
    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: false,
      error: 'LLM returned empty response',
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(document.body.textContent).toContain('LLM returned empty response')
  })

  it('emits add-to-canvas when Add to Canvas is clicked', async () => {
    const mockSchema = {
      id: 'test-app-id',
      name: 'Test App',
      nodes: [{ id: 'n1', type: 'agent', name: 'Agent 1', position: { x: 100, y: 200 }, data: {} }],
      edges: [],
    }

    vi.mocked(schemaApi.generateNodes).mockResolvedValue({
      success: true,
      schema: mockSchema,
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    // Click Add to Canvas button via document.body
    const addBtn = document.body.querySelector<HTMLButtonElement>('.add-btn')
    addBtn!.click()

    expect(wrapper.emitted('add-to-canvas')).toBeTruthy()
    expect(wrapper.emitted('add-to-canvas')![0]).toEqual([mockSchema])
  })

  it('emits close event on overlay click', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    const overlay = document.body.querySelector<HTMLElement>('.quickstart-overlay')
    overlay!.click()

    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('shows loading state during generation', async () => {
    // Don't resolve the promise immediately so we can see loading state
    vi.mocked(schemaApi.generateNodes).mockReturnValue(new Promise(() => {}))

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(document.body.textContent).toContain('Generating pipeline')
    expect(document.body.querySelector('.spinner')).toBeTruthy()
  })
})
