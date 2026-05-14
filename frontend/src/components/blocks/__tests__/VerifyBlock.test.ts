import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import VerifyBlock from '../VerifyBlock.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'verifier-1',
  data: {
    label: 'Check Syntax',
    type: 'verifier',
    config: {},
    status: 'idle',
  },
}

describe('VerifyBlock', () => {
  it('renders block label', () => {
    const wrapper = mount(VerifyBlock, { props: defaultProps })
    expect(wrapper.text()).toContain('Check Syntax')
  })

  it('defaults to "Verify" when no label provided', () => {
    const wrapper = mount(VerifyBlock, { props: { id: 'v1' } })
    expect(wrapper.text()).toContain('Verify')
  })

  it('shows status dot when non-idle status', () => {
    const wrapper = mount(VerifyBlock, {
      props: { ...defaultProps, data: { ...defaultProps.data, status: 'completed' } },
    })
    expect(wrapper.find('.status-dot').exists()).toBe(true)
  })

  it('renders with purple color scheme', () => {
    const wrapper = mount(VerifyBlock, { props: defaultProps })
    const header = wrapper.find('.block-header')
    expect(header.exists()).toBe(true)
    // Browser converts hex #8b5cf6 to rgb(139, 92, 246)
    expect(header.attributes('style')).toContain('rgb(139, 92, 246)')
  })

  it('does not show status dot when idle', () => {
    const wrapper = mount(VerifyBlock, { props: defaultProps })
    expect(wrapper.find('.status-dot').exists()).toBe(false)
  })
})
