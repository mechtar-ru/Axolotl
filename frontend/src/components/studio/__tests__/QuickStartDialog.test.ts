// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import QuickStartDialog from '../QuickStartDialog.vue'

// Mock data — use vi.hoisted for vars accessed in vi.mock factory
const mockGetSchema = vi.hoisted(() => ({
  id: 'test-app-id',
  name: 'Test App',
  description: '',
  version: '',
  nodes: [],
  edges: [],
}))

vi.mock('@/stores/settingsStore', () => ({
  useSettingsStore: vi.fn(() => ({
    projectsFolder: '/Users/test/projects',
    providersLoaded: true,
    fetchProviders: vi.fn().mockResolvedValue(undefined),
    getAllModelOptions: vi.fn(() => [
      { value: 'gpt-4', label: 'gpt-4' },
      { value: 'deepseek-v4-flash-free', label: 'deepseek-v4-flash-free' },
    ]),
  })),
}))

vi.mock('@/services/api', () => ({
  appApi: {
    createApp: vi.fn().mockResolvedValue({ id: 'new-app-id', name: 'New App' }),
  },
  schemaApi: {
    getSchema: vi.fn().mockResolvedValue(mockGetSchema),
    updateSchema: vi.fn().mockImplementation((_id, schema) => Promise.resolve(schema)),
  },
}))

import { appApi, schemaApi } from '@/services/api'

describe('QuickStartDialog', () => {
  const baseProps = {
    visible: true,
    appId: 'test-app-id',
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
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

    wrapper.vm.prompt = 'Create a Sokoban game'
    await wrapper.vm.$nextTick()

    const generateBtn = document.body.querySelector<HTMLButtonElement>('.generate-btn')
    expect(generateBtn!.disabled).toBe(false)
  })

  it('shows app name field only in create mode', async () => {
    // Create mode (no appId)
    const wrapper = mount(QuickStartDialog, { props: { visible: true, appId: '' } })
    await flushPromises()

    expect(document.body.querySelector('#quickstart-name')).toBeTruthy()

    // Studio mode (has appId)
    document.body.innerHTML = ''
    mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    expect(document.body.querySelector('#quickstart-name')).toBeNull()
  })

  it('applies fixed 5-node pipeline on generate', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Build a mood diary app'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    // Should fetch schema and update it
    expect(schemaApi.getSchema).toHaveBeenCalledWith('test-app-id')
    expect(schemaApi.updateSchema).toHaveBeenCalledWith('test-app-id', expect.any(Object))

    // Should emit the updated schema
    expect(wrapper.emitted('add-to-canvas')).toBeTruthy()
    const emitted = (wrapper.emitted('add-to-canvas')![0] as any[])[0] as any
    expect(emitted.nodes).toHaveLength(5)
    expect(emitted.edges).toHaveLength(4)

    // Verify node types in order
    const types = emitted.nodes.map((n: any) => n.type)
    expect(types).toEqual(['source', 'review', 'agent', 'verifier', 'output'])

    // Verify edge connections
    const edgeSources = emitted.edges.map((e: any) => e.source)
    const edgeTargets = emitted.edges.map((e: any) => e.target)
    expect(edgeSources).toEqual(['receive-1', 'review-1', 'think-1', 'verify-1'])
    expect(edgeTargets).toEqual(['review-1', 'think-1', 'verify-1', 'act-1'])
  })

  it('passes description to Receive node sourceData', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Build a Sokoban game in Python'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    const emitted = (wrapper.emitted('add-to-canvas')![0] as any[])[0] as any
    const receiveNode = emitted.nodes.find((n: any) => n.id === 'receive-1')
    expect(receiveNode.data.sourceData).toBe('Build a Sokoban game in Python')
    expect(receiveNode.data.config?.sourceType).toBe('text')
  })

  it('passes description to Verify node validationCriteria', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Build a Sokoban game in Python'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    const emitted = (wrapper.emitted('add-to-canvas')![0] as any[])[0] as any
    const verifyNode = emitted.nodes.find((n: any) => n.id === 'verify-1')
    expect(verifyNode.data.config?.validationCriteria).toBe('Build a Sokoban game in Python')
  })

  it('creates new app in create mode before applying pipeline', async () => {
    const wrapper = mount(QuickStartDialog, { props: { visible: true, appId: '' } })
    await flushPromises()

    wrapper.vm.prompt = 'Build a chat bot'
    wrapper.vm.appName = 'My Chat Bot'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(appApi.createApp).toHaveBeenCalledWith({
      name: 'My Chat Bot',
      appType: 'CUSTOM',
      description: '',
    })
    expect(schemaApi.getSchema).toHaveBeenCalledWith('new-app-id')
  })

  it('shows error when createApp returns no id', async () => {
    vi.mocked(appApi.createApp).mockResolvedValueOnce({} as any)

    const wrapper = mount(QuickStartDialog, { props: { visible: true, appId: '' } })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    wrapper.vm.appName = 'My Game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(document.body.textContent).toContain('Failed to create app')
  })

  it('shows error section on schema update failure', async () => {
    vi.mocked(schemaApi.updateSchema).mockRejectedValueOnce({
      isAxiosError: true,
      response: { data: { error: 'Backend error' } },
    })

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(document.body.textContent).toContain('Backend error')
  })

  it('emits close event on overlay click', async () => {
    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    const overlay = document.body.querySelector<HTMLElement>('.quickstart-overlay')
    overlay!.click()

    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('shows loading state during generation', async () => {
    // Don't resolve so we see loading
    vi.mocked(schemaApi.getSchema).mockReturnValueOnce(new Promise(() => {}))

    const wrapper = mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    wrapper.vm.prompt = 'Create a game'
    await wrapper.vm.$nextTick()
    wrapper.vm.generate()
    await flushPromises()

    expect(document.body.textContent).toContain('Creating pipeline')
    expect(document.body.querySelector('.spinner')).toBeTruthy()
  })

  it('presets fill prompt with pure app description', async () => {
    mount(QuickStartDialog, { props: baseProps })
    await flushPromises()

    const presetSelect = document.body.querySelector<HTMLSelectElement>('#quickstart-preset')
    expect(presetSelect).toBeTruthy()

    // Verify presets don't contain pipeline language
    const options = Array.from(presetSelect!.options).map(o => o.text)
    expect(options).toContain('EIOS (Flutter)')
    expect(options).toContain('Chat Bot')
    expect(options).toContain('Content Generator')
    expect(options).toContain('Sokoban Game')
  })
})
