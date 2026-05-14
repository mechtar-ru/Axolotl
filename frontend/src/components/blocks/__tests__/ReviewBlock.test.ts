import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReviewBlock from '../ReviewBlock.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'review-1',
  data: {
    label: 'Review Code',
    type: 'review',
    config: {},
    status: 'idle',
  },
}

describe('ReviewBlock', () => {
  it('renders block label', () => {
    const wrapper = mount(ReviewBlock, { props: defaultProps })
    expect(wrapper.text()).toContain('Review Code')
  })

  it('defaults to "Review" when no label provided', () => {
    const wrapper = mount(ReviewBlock, { props: { id: 'r1' } })
    expect(wrapper.text()).toContain('Review')
  })

  it('shows status dot when non-idle status', () => {
    const wrapper = mount(ReviewBlock, {
      props: { ...defaultProps, data: { ...defaultProps.data, status: 'completed' } },
    })
    expect(wrapper.find('.status-dot').exists()).toBe(true)
  })

  it('renders with amber color scheme', () => {
    const wrapper = mount(ReviewBlock, { props: defaultProps })
    const header = wrapper.find('.block-header')
    expect(header.exists()).toBe(true)
    // Amber #f59e0b converts to rgb(245, 158, 11)
    expect(header.attributes('style')).toContain('rgb(245, 158, 11)')
  })

  it('does not show status dot when idle', () => {
    const wrapper = mount(ReviewBlock, { props: defaultProps })
    expect(wrapper.find('.status-dot').exists()).toBe(false)
  })

  it('shows iteration badge when prop provided', () => {
    const wrapper = mount(ReviewBlock, { props: { id: 'r1', iteration: '2/3' } })
    expect(wrapper.text()).toContain('2/3')
  })
})
