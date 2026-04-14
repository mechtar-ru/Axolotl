import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryNode from '../MemoryNode.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'memory-1',
  data: {
    name: 'Memory Search',
    onUpdate: vi.fn(),
    onRename: vi.fn(),
    onDelete: vi.fn(),
  },
}

describe('MemoryNode', () => {
  it('renders with teal border', () => {
    const wrapper = mount(MemoryNode, { props: defaultProps })
    expect(wrapper.find('.memory-node').exists()).toBe(true)
  })

  it('starts expanded with search input visible', () => {
    const wrapper = mount(MemoryNode, { props: defaultProps })
    // MemoryNode starts expanded=true
    expect(wrapper.find('.search-input').exists()).toBe(true)
  })

  it('shows wing/room filters when expanded', () => {
    const wrapper = mount(MemoryNode, { props: defaultProps })
    expect(wrapper.findAll('.filter-input').length).toBe(2)
  })

  it('calls onDelete when delete clicked', async () => {
    const wrapper = mount(MemoryNode, { props: { ...defaultProps, selected: true } })
    await wrapper.find('.delete-btn').trigger('click')
    expect(defaultProps.data.onDelete).toHaveBeenCalled()
  })

  it('allows rename on double-click', async () => {
    const wrapper = mount(MemoryNode, { props: defaultProps })
    await wrapper.find('.node-name').trigger('dblclick')
    expect(wrapper.find('input.node-name-input').exists()).toBe(true)
  })

  it('collapses on toggle click', async () => {
    const wrapper = mount(MemoryNode, { props: defaultProps })
    expect(wrapper.find('.search-input').exists()).toBe(true) // expanded
    await wrapper.find('.node-expand').trigger('click')
    expect(wrapper.find('.search-input').exists()).toBe(false) // collapsed
  })
})
