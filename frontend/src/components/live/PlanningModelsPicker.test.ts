import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PlanningModelsPicker from './PlanningModelsPicker.vue'

describe('PlanningModelsPicker', () => {
  it('renders with default values', () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    expect(wrapper.find('.picker-modal').exists()).toBe(true)
    expect(wrapper.find('h3').text()).toBe('Planning Models')
  })

  it('shows model inputs with provided values', () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: { fast: 'gpt-4o-mini', medium: 'deepseek-chat' },
        defaultModel: 'gpt-4o',
      },
    })
    const inputs = wrapper.findAll('input')
    expect(inputs.at(0)?.element.value).toBe('gpt-4o-mini')
    expect(inputs.at(1)?.element.value).toBe('deepseek-chat')
  })

  it('emits update:modelValue and close on save', async () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    await wrapper.find('.save-btn').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual({
      fast: 'gpt-4o',
      medium: 'gpt-4o',
    })
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close on cancel', async () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    await wrapper.find('.cancel-btn').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close on overlay click', async () => {
    const wrapper = mount(PlanningModelsPicker, {
      props: {
        modelValue: null,
        defaultModel: 'gpt-4o',
      },
    })
    await wrapper.find('.picker-overlay').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
