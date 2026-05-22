import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PlanningModelsPicker from './PlanningModelsPicker.vue'
import { settingsApi } from '@/services/api'

vi.mock('@/services/api', () => ({
  settingsApi: {
    getProviders: vi.fn(),
  },
}))

const MOCK_PROVIDERS = [
  {
    name: 'openai',
    available: true,
    baseUrl: 'https://api.openai.com',
    models: ['gpt-4o-mini', 'gpt-4o'],
  },
  {
    name: 'anthropic',
    available: true,
    baseUrl: 'https://api.anthropic.com',
    models: ['claude-haiku', 'claude-sonnet'],
  },
  {
    name: 'deepseek',
    available: true,
    baseUrl: 'https://api.deepseek.com',
    models: ['deepseek-chat', 'deepseek-reasoner'],
  },
  {
    name: 'google',
    available: true,
    baseUrl: 'https://generativelanguage.googleapis.com',
    models: ['gemini-flash', 'gemini-pro'],
  },
]

describe('PlanningModelsPicker', () => {
  beforeEach(() => {
    vi.mocked(settingsApi.getProviders).mockResolvedValue(MOCK_PROVIDERS)
  })

  async function mountPicker(props: Record<string, unknown> = {}) {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
        ...props,
      },
    })
    await flushPromises()
    return wrapper
  }

  it('renders inline with two selects', async () => {
    const wrapper = await mountPicker()
    expect(wrapper.find('.model-picker-inline').exists()).toBe(true)
    const selects = wrapper.findAll('select')
    expect(selects.length).toBe(2)
  })

  it('fetches providers and groups models by optgroup', async () => {
    const wrapper = await mountPicker()
    // Scope to the first select (fast-model) to avoid double-counting
    const optgroups = wrapper.find('#fast-model').findAll('optgroup')
    expect(optgroups.length).toBe(4)
    expect(optgroups[0]!.attributes('label')).toBe('Openai')
    expect(optgroups[1]!.attributes('label')).toBe('Anthropic')
    expect(optgroups[2]!.attributes('label')).toBe('Deepseek')
    expect(optgroups[3]!.attributes('label')).toBe('Google')
  })

  it('preselects values from modelValue', async () => {
    const wrapper = await mountPicker({
      modelValue: { fast: 'gpt-4o-mini', medium: 'deepseek-chat' },
    })
    const selects = wrapper.findAll('select')
    expect((selects[0]!.element as HTMLSelectElement).value).toBe('gpt-4o-mini')
    expect((selects[1]!.element as HTMLSelectElement).value).toBe('deepseek-chat')
  })

  it('uses defaultModel when modelValue is null', async () => {
    const wrapper = await mountPicker({
      modelValue: null,
      defaultModel: 'gpt-4o',
    })
    const selects = wrapper.findAll('select')
    expect((selects[0]!.element as HTMLSelectElement).value).toBe('gpt-4o')
    expect((selects[1]!.element as HTMLSelectElement).value).toBe('gpt-4o')
  })

  it('emits update:modelValue on fast model change', async () => {
    const wrapper = await mountPicker()
    const selects = wrapper.findAll('select')
    await selects[0]!.setValue('claude-haiku')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    // First emit is from ensureValidSelections on mount; last emit is from user change
    const emits = wrapper.emitted('update:modelValue')!
    expect((emits[emits.length - 1] as unknown[])[0]).toEqual({
      fast: 'claude-haiku',
      medium: 'gpt-4o',
    })
  })

  it('emits update:modelValue on medium model change', async () => {
    const wrapper = await mountPicker()
    const selects = wrapper.findAll('select')
    await selects[1]!.setValue('claude-sonnet')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    const emits = wrapper.emitted('update:modelValue')!
    expect((emits[emits.length - 1] as unknown[])[0]).toEqual({
      fast: 'gpt-4o',
      medium: 'claude-sonnet',
    })
  })

  it('falls back to hardcoded options when API fails', async () => {
    vi.mocked(settingsApi.getProviders).mockRejectedValue(new Error('Network error'))
    const wrapper = await mountPicker()
    const selects = wrapper.findAll('select')
    // Should still have working selects with fallback values
    expect(selects.length).toBe(2)
    expect((selects[0]!.element as HTMLSelectElement).value).toBe('gpt-4o')
  })

  it('skips unavailable providers', async () => {
    const mixed = MOCK_PROVIDERS.map((p) =>
      p.name === 'anthropic' ? { ...p, available: false } : p,
    )
    vi.mocked(settingsApi.getProviders).mockResolvedValue(mixed)
    const wrapper = await mountPicker()
    // Scope to first select to avoid double-counting both selects
    const optgroups = wrapper.find('#fast-model').findAll('optgroup')
    // anthropic excluded because unavailable
    expect(optgroups.length).toBe(3)
    expect(optgroups.map((g) => g.attributes('label'))).not.toContain('Anthropic')
  })
})
