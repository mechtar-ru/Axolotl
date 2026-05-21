import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AgentNode from '../AgentNode.vue'

vi.mock('@vue-flow/core', () => ({
  Handle: { template: '<div class="handle"><slot /></div>', props: ['type', 'position'] },
  Position: { Top: 'top', Bottom: 'bottom' },
}))

const defaultProps = {
  id: 'agent-1',
  data: {
    name: 'Test Agent',
    userPrompt: 'Hello',
    model: 'ollama',
    onUpdate: vi.fn(),
    onRename: vi.fn(),
    onDelete: vi.fn(),
    onOpenPromptEditor: vi.fn(),
  },
}

describe('AgentNode', () => {
  it('renders node name', () => {
    const wrapper = mount(AgentNode, { props: defaultProps })
    expect(wrapper.text()).toContain('Test Agent')
  })

  it('shows delete button when selected', () => {
    const wrapper = mount(AgentNode, { props: { ...defaultProps, selected: true } })
    expect(wrapper.find('.delete-btn').exists()).toBe(true)
  })

  it('hides delete button when not selected', () => {
    const wrapper = mount(AgentNode, { props: defaultProps })
    expect(wrapper.find('.delete-btn').exists()).toBe(false)
  })

  it('starts collapsed, expands on toggle click', async () => {
    const wrapper = mount(AgentNode, { props: defaultProps })
    // AgentNode starts collapsed
    expect(wrapper.find('.node-content').exists()).toBe(false)
    await wrapper.find('.node-expand').trigger('click')
    expect(wrapper.find('.node-content').exists()).toBe(true)
  })

  it('calls onDelete when delete button clicked', async () => {
    const wrapper = mount(AgentNode, { props: { ...defaultProps, selected: true } })
    await wrapper.find('.delete-btn').trigger('click')
    expect(defaultProps.data.onDelete).toHaveBeenCalled()
  })

  it('shows execution status icons', () => {
    const wrapper = mount(AgentNode, {
      props: { ...defaultProps, data: { ...defaultProps.data, executionStatus: 'running' } },
    })
    expect(wrapper.find('.execution-icon svg').exists()).toBe(true)
  })

  it('updates prompt on input', async () => {
    const wrapper = mount(AgentNode, { props: defaultProps })
    await wrapper.find('.node-expand').trigger('click') // expand first
    const textarea = wrapper.find('textarea')
    await textarea.setValue('New prompt text')
    expect(defaultProps.data.onUpdate).toHaveBeenCalledWith({ userPrompt: 'New prompt text' })
  })

  it('allows renaming on double-click', async () => {
    const wrapper = mount(AgentNode, { props: defaultProps })
    await wrapper.find('.node-name').trigger('dblclick')
    expect(wrapper.find('input.node-name-input').exists()).toBe(true)
  })
})
