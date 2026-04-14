import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import HumanNode from '../HumanNode.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'human-1',
  data: {
    name: 'Approval',
    userPrompt: 'Confirm this result',
    onUpdate: vi.fn(),
    onRename: vi.fn(),
    onDelete: vi.fn(),
  },
}

describe('HumanNode', () => {
  it('renders with orange border', () => {
    const wrapper = mount(HumanNode, { props: defaultProps })
    expect(wrapper.find('.human-node').exists()).toBe(true)
    expect(wrapper.text()).toContain('Approval')
  })

  it('shows approval buttons when running (starts expanded)', () => {
    const wrapper = mount(HumanNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, executionStatus: 'running' } },
    })
    // HumanNode starts expanded=true, so approval panel should be visible
    expect(wrapper.find('.approve-btn').exists()).toBe(true)
    expect(wrapper.find('.reject-btn').exists()).toBe(true)
  })

  it('shows approved status after approve', async () => {
    const wrapper = mount(HumanNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, executionStatus: 'running' } },
    })
    await wrapper.find('.approve-btn').trigger('click')
    expect(wrapper.text()).toContain('Подтверждено')
    expect(defaultProps.data.onUpdate).toHaveBeenCalled()
  })

  it('shows rejected status after reject', async () => {
    const wrapper = mount(HumanNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, executionStatus: 'running' } },
    })
    await wrapper.find('.reject-btn').trigger('click')
    expect(wrapper.text()).toContain('Отклонено')
  })

  it('shows prompt textarea when expanded', () => {
    const wrapper = mount(HumanNode, { props: defaultProps })
    expect(wrapper.find('textarea').exists()).toBe(true)
  })
})
