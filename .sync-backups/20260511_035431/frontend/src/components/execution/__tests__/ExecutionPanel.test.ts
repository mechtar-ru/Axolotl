import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ExecutionPanel from '../ExecutionPanel.vue'

describe('ExecutionPanel', () => {
  const defaultProps = {
    visible: true,
    isExecuting: true,
    progress: 50,
    elapsedSeconds: 10,
    totalNodes: 5,
    completedNodes: 2,
    logs: [
      { timestamp: Date.now(), message: 'Started', level: 'info' as const },
      { timestamp: Date.now(), message: 'Error occurred', level: 'error' as const },
      { timestamp: Date.now(), message: 'Node completed', level: 'success' as const },
    ],
  }

  it('renders when visible', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    expect(wrapper.find('.execution-panel').exists()).toBe(true)
  })

  it('does not render when not visible', () => {
    const wrapper = mount(ExecutionPanel, { props: { ...defaultProps, visible: false } })
    expect(wrapper.find('.execution-panel').exists()).toBe(false)
  })

  it('displays progress percentage', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    expect(wrapper.text()).toContain('50%')
  })

  it('displays node counts', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    expect(wrapper.text()).toContain('2/5')
  })

  it('displays formatted timer', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    expect(wrapper.text()).toContain('00:00:10')
  })

  it('renders log entries', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    const entries = wrapper.findAll('.execution-panel__log-entry')
    expect(entries.length).toBe(3)
  })

  it('highlights error logs', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    expect(wrapper.find('.log-error').exists()).toBe(true)
  })

  it('highlights success logs', () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    expect(wrapper.find('.log-success').exists()).toBe(true)
  })

  it('emits stop on stop button click', async () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    await wrapper.find('.stop-btn').trigger('click')
    expect(wrapper.emitted('stop')).toBeTruthy()
  })

  it('emits close on close button click', async () => {
    const wrapper = mount(ExecutionPanel, { props: defaultProps })
    await wrapper.find('.close-btn').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('disables stop button when not executing', () => {
    const wrapper = mount(ExecutionPanel, { props: { ...defaultProps, isExecuting: false } })
    expect(wrapper.find('.stop-btn').attributes('disabled')).toBeDefined()
  })

  it('shows token count when provided', () => {
    const wrapper = mount(ExecutionPanel, {
      props: { ...defaultProps, totalTokens: 1500, estimatedCost: 0.003 },
    })
    // Locale may format 1500 as "1,500" or "1 500" — check rendered output
    const text = wrapper.text()
    expect(text).toContain('токенов')
    expect(text).toContain('0.003')
  })
})
