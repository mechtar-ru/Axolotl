import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import DesignWorkspaceUI from './DesignWorkspaceUI.vue'

// Mock the schema API module
vi.mock('@/services/api', () => ({
  schemaApi: {
    getSchema: vi.fn(),
    updateSchema: vi.fn(),
    executeSchema: vi.fn(),
  }
}))

import { schemaApi } from '@/services/api'

const DEFAULT_PROPS = {
  appId: 'test-app-1',
  appType: 'GAME' as const,
  executionResult: null
}

describe('DesignWorkspaceUI', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders with correct props', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    expect(wrapper.exists()).toBe(true)
  })

  it('shows Concept tab by default', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const conceptBtn = wrapper.findAll('.tab-btn').at(0)
    expect(conceptBtn?.text()).toContain('Concept')
    expect(conceptBtn?.classes()).toContain('active')
  })

  it('shows textarea and generate button in Concept tab', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { ...DEFAULT_PROPS, appType: 'GENERATOR' }
    })
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.exists()).toBe(true)
  })

  it('shows Review tab with plan when executionResult arrives after generate', async () => {
    const mockSchema = {
      id: 'test-app-1',
      nodes: [{ id: 'src-1', type: 'source', data: { sourceData: 'old prompt' } }]
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.updateSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.executeSchema).mockResolvedValue(undefined)

    const wrapper = mount(DesignWorkspaceUI, { props: { ...DEFAULT_PROPS, executionResult: null } })

    // Simulate user flow: type prompt → click generate → result arrives
    await wrapper.find('textarea').setValue('Test game idea')
    await wrapper.find('.action-btn').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Now isGenerating is true, simulate result arrival via prop update
    await wrapper.setProps({
      executionResult: { plan: '## Game Design\n\nA tower defense game...' }
    })

    // Re-find .tab-btn after state update
    const allBtns = wrapper.findAll('.tab-btn')
    expect(allBtns.at(1)?.text()).toContain('Review')
    const planContent = wrapper.find('.plan-content')
    expect(planContent.exists()).toBe(true)
  })

  it('shows Output tab with files when executionResult arrives after generate', async () => {
    const mockSchema = {
      id: 'test-app-1',
      nodes: [{ id: 'src-1', type: 'source', data: { sourceData: 'old prompt' } }]
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.updateSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.executeSchema).mockResolvedValue(undefined)

    const wrapper = mount(DesignWorkspaceUI, { props: { ...DEFAULT_PROPS, executionResult: null } })

    // Simulate user flow: type prompt → click generate → result arrives
    await wrapper.find('textarea').setValue('Test game idea')
    await wrapper.find('.action-btn').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Now isGenerating is true, simulate result arrival with files
    await wrapper.setProps({
      executionResult: {
        files: [
          { name: 'game.html', content: '<html></html>', type: 'text/html' },
          { name: 'gdd.md', content: '# GDD', type: 'text/markdown' }
        ]
      }
    })

    const fileItems = wrapper.findAll('.file-item')
    expect(fileItems.length).toBe(2)
    expect(fileItems.at(0)?.text()).toContain('game.html')
    expect(fileItems.at(1)?.text()).toContain('gdd.md')
  })

  it('shows download buttons for each file in Output tab', async () => {
    const mockSchema = {
      id: 'test-app-1',
      nodes: [{ id: 'src-1', type: 'source', data: { sourceData: 'old prompt' } }]
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.updateSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.executeSchema).mockResolvedValue(undefined)

    const wrapper = mount(DesignWorkspaceUI, {
      props: { ...DEFAULT_PROPS, appType: 'GENERATOR', executionResult: null }
    })

    // Simulate user flow: type prompt → click generate → result arrives
    await wrapper.find('textarea').setValue('Test idea')
    await wrapper.find('.action-btn').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Simulate result arrival with single file
    await wrapper.setProps({
      executionResult: {
        files: [
          { name: 'output.txt', content: 'hello', type: 'text/plain' }
        ]
      }
    })

    const downloadBtns = wrapper.findAll('.download-btn')
    expect(downloadBtns.length).toBe(1)
  })

  it('renders for GENERATOR app type', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { ...DEFAULT_PROPS, appType: 'GENERATOR' }
    })
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.design-workspace').exists()).toBe(true)
  })

  it('disables Generate Draft button when empty prompt', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.attributes('disabled')).toBeDefined()
  })

  it('enables Generate Draft button when prompt is filled', async () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('Create a platformer game')
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.attributes('disabled')).toBeUndefined()
  })

  it('calls schema API on Generate Draft', async () => {
    const mockSchema = {
      id: 'test-app-1',
      nodes: [
        { id: 'src-1', type: 'source', data: { sourceData: 'old prompt' } }
      ]
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.updateSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.executeSchema).mockResolvedValue(undefined)

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('Create a platformer game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')

    // Should have: getSchema + updateSchema + executeSchema
    expect(schemaApi.getSchema).toHaveBeenCalledWith('test-app-1')
    expect(schemaApi.updateSchema).toHaveBeenCalled()
    expect(schemaApi.executeSchema).toHaveBeenCalledWith('test-app-1')
  })

  it('shows error when no source node found', async () => {
    const mockSchema = {
      id: 'test-app-1',
      nodes: [
        { id: 'agent-1', type: 'agent', data: {} }
      ]
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('Create a platformer game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')

    const errorText = wrapper.find('.error-text')
    expect(errorText.exists()).toBe(true)
    expect(errorText.text()).toContain('No source node found')
  })
})
