import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SourceNode from '../SourceNode.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'source-1',
  data: {
    name: 'Input Data',
    sourceData: 'test source content',
    onUpdate: vi.fn(),
    onRename: vi.fn(),
    onDelete: vi.fn(),
  },
}

describe('SourceNode', () => {
  it('renders node name', () => {
    const wrapper = mount(SourceNode, { props: defaultProps })
    expect(wrapper.text()).toContain('Input Data')
  })

  it('starts expanded with textarea visible', () => {
    const wrapper = mount(SourceNode, { props: defaultProps })
    // SourceNode starts expanded=true
    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  it('calls onDelete when delete clicked', async () => {
    const wrapper = mount(SourceNode, { props: { ...defaultProps, selected: true } })
    await wrapper.find('.delete-btn').trigger('click')
    expect(defaultProps.data.onDelete).toHaveBeenCalled()
  })

  it('updates sourceData on textarea input', async () => {
    const wrapper = mount(SourceNode, { props: defaultProps })
    await wrapper.find('textarea').setValue('new data')
    expect(defaultProps.data.onUpdate).toHaveBeenCalledWith({ sourceData: 'new data' })
  })

  it('shows result when present', () => {
    const wrapper = mount(SourceNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, result: 'output text' } },
    })
    expect(wrapper.text()).toContain('output text')
  })

  it('shows running icon during execution', () => {
    const wrapper = mount(SourceNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, executionStatus: 'running' } },
    })
    expect(wrapper.text()).toContain('⏳')
  })

  it('collapses on toggle click', async () => {
    const wrapper = mount(SourceNode, { props: defaultProps })
    expect(wrapper.find('textarea').exists()).toBe(true) // expanded
    await wrapper.find('.node-expand').trigger('click') // collapse
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
})
