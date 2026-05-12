import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import DesignWorkspaceUI from './DesignWorkspaceUI.vue'

vi.mock('@/services/api', () => ({
  schemaApi: {
    getSchema: vi.fn(),
    updateSchema: vi.fn(),
    executeSchema: vi.fn(),
    plan: vi.fn(),
    updatePlanningModels: vi.fn(),
    updatePlanningContext: vi.fn(),
    clearPlanningContext: vi.fn(),
  }
}))

import { schemaApi } from '@/services/api'

const DEFAULT_PROPS = {
  appId: 'test-app-1',
  appType: 'GAME' as const,
  executionResult: null,
}

describe('DesignWorkspaceUI', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(schemaApi.getSchema).mockResolvedValue({
      id: 'test-app-1',
      nodes: [],
      planningModels: null,
    } as any)
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

  it('disables Generate Draft when prompt is empty', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.attributes('disabled')).toBeDefined()
  })

  it('enables Generate Draft when prompt is filled', async () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a platformer game')
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.attributes('disabled')).toBeUndefined()
  })

  it('calls schemaApi.plan on Generate Draft', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValue({
      type: 'outline',
      content: '- Game plan',
      questions: [{ id: 'q1', text: 'Theme?', defaultAnswer: 'fantasy' }],
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a platformer game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()

    expect(schemaApi.plan).toHaveBeenCalledWith('test-app-1', {
      prompt: 'Create a platformer game',
      level: 'outline',
      model: '',
    })
  })

  it('shows outline in Review tab after generation', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValue({
      type: 'outline',
      content: '- Game plan\n- More items',
      questions: [],
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.plan-content').exists()).toBe(true)
    expect(wrapper.find('.plan-content').text()).toContain('Game plan')
  })

  it('shows clarifying questions with editable fields', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValue({
      type: 'outline',
      content: '- Game plan',
      questions: [
        { id: 'q1', text: 'Theme?', defaultAnswer: 'fantasy', options: ['fantasy', 'sci-fi'] },
        { id: 'q2', text: 'Difficulty?', defaultAnswer: 'medium' },
      ],
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const optionLabels = wrapper.findAll('.option-label')
    expect(optionLabels.length).toBe(2)

    const textInputs = wrapper.findAll('.question-input')
    expect(textInputs.length).toBe(1)
  })

  it('calls schemaApi.plan on Refine Plan with context', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValueOnce({
      type: 'outline',
      content: '- Outline plan',
      questions: [],
    }).mockResolvedValueOnce({
      type: 'refine',
      content: '# Detailed Design',
    })

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const refineBtn = wrapper.findAll('button').find(b => b.text().includes('Refine Plan'))
    await refineBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(schemaApi.plan).toHaveBeenCalledTimes(2)
    expect(schemaApi.plan).toHaveBeenLastCalledWith('test-app-1', expect.objectContaining({
      level: 'refine',
      context: expect.objectContaining({
        outline: '- Outline plan',
      }),
    }))
  })

  it('calls executePlan flow: getSchema → updateSchema → executeSchema', async () => {
    vi.mocked(schemaApi.plan).mockResolvedValueOnce({
      type: 'outline',
      content: '- Outline',
      questions: [],
    }).mockResolvedValueOnce({
      type: 'refine',
      content: '# Refined plan with details',
    })

    const mockSchema = {
      id: 'test-app-1',
      nodes: [{ id: 'src-1', type: 'source', data: { sourceData: '' } }],
    }
    vi.mocked(schemaApi.getSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.updateSchema).mockResolvedValue(mockSchema as any)
    vi.mocked(schemaApi.executeSchema).mockResolvedValue(undefined)

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns1 = wrapper.findAll('button')
    const generateBtn = allBtns1.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const allBtns2 = wrapper.findAll('button')
    const refineBtn = allBtns2.find(b => b.text().includes('Refine Plan'))
    await refineBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const allBtns3 = wrapper.findAll('button')
    const executeBtn = allBtns3.find(b => b.text().includes('Execute Plan'))
    await executeBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(schemaApi.getSchema).toHaveBeenCalledWith('test-app-1')
    expect(schemaApi.updateSchema).toHaveBeenCalled()
    expect(schemaApi.executeSchema).toHaveBeenCalledWith('test-app-1')
  })

  it('shows generated files when executionResult arrives', async () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })

    await wrapper.setProps({
      executionResult: {
        files: [
          { name: 'game.html', content: '<html></html>', type: 'text/html' },
        ],
      },
    })

    const fileItems = wrapper.findAll('.file-item')
    expect(fileItems.length).toBe(1)
    expect(fileItems.at(0)?.text()).toContain('game.html')
  })

  it('shows inline PlanningModelsPicker in Concept tab', () => {
    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    expect(wrapper.findComponent({ name: 'PlanningModelsPicker' }).exists()).toBe(true)
  })

  it('error on failed outline generation', async () => {
    vi.mocked(schemaApi.plan).mockRejectedValue(new Error('API timeout'))

    const wrapper = mount(DesignWorkspaceUI, { props: DEFAULT_PROPS })
    await wrapper.find('textarea').setValue('Create a game')

    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    await generateBtn?.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const errorText = wrapper.find('.error-text')
    expect(errorText.exists()).toBe(true)
    expect(errorText.text()).toContain('API timeout')
  })

  it('renders for GENERATOR app type', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { ...DEFAULT_PROPS, appType: 'GENERATOR' },
    })
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.design-workspace').exists()).toBe(true)
  })
})
