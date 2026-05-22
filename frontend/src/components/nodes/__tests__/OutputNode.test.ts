import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import OutputNode from '../OutputNode.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'output-1',
  data: {
    name: 'Result',
    result: 'Final output',
    onRename: vi.fn(),
    onDelete: vi.fn(),
  },
}

describe('OutputNode', () => {
  it('renders node name', () => {
    const wrapper = mount(OutputNode, { props: defaultProps })
    expect(wrapper.text()).toContain('Result')
  })

  it('shows result when present (starts expanded)', () => {
    const wrapper = mount(OutputNode, { props: defaultProps })
    // OutputNode starts expanded=true, and shows result when result exists
    expect(wrapper.text()).toContain('Final output')
  })

  it('shows execution status icon', () => {
    const wrapper = mount(OutputNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, executionStatus: 'completed' } },
    })
    expect(wrapper.find('.execution-icon svg').exists()).toBe(true)
  })
})
