import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import DraftApprovalPanel from '../DraftApprovalPanel.vue'

const mockArtifacts = [
  { type: 'spec', name: 'App Spec', label: 'SPEC', content: 'Build a todo app' },
  { type: 'plan', name: 'Impl Plan', label: 'PLAN', content: '1. Create model\n2. Add routes' },
  { type: 'ui', name: 'UI Mock', label: 'UI', content: 'Two screens: list + form' },
]

describe('DraftApprovalPanel', () => {
  it('renders all artifact cards', () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    expect(wrapper.text()).toContain('SPEC')
    expect(wrapper.text()).toContain('PLAN')
    expect(wrapper.text()).toContain('UI')
    expect(wrapper.text()).toContain('App Spec')
    expect(wrapper.text()).toContain('Impl Plan')
  })

  it('shows first artifact content by default', () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    expect(wrapper.text()).toContain('Build a todo app')
  })

  it('toggles artifact collapse on header click', async () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    // First artifact is expanded by default, click to collapse
    const headers = wrapper.findAll('.draft-artifact-header')
    await headers[0]!.trigger('click')
    expect(wrapper.text()).not.toContain('Build a todo app')
  })

  it('has three action buttons', () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    expect(wrapper.text()).toContain('Reject Drafts')
    expect(wrapper.text()).toContain('Regenerate with Feedback')
    expect(wrapper.text()).toContain('Approve & Implement')
  })

  it('emits approve with artifact content', async () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    const approveBtn = wrapper.find('.btn-approve')
    await approveBtn.trigger('click')
    const emitted = wrapper.emitted('approve')
    expect(emitted).toBeTruthy()
    const payload = JSON.parse(emitted![0]![0] as string)
    expect(payload).toHaveLength(3)
    expect(payload[0]!.content).toBe('Build a todo app')
  })

  it('emits regenerate with empty feedback array', async () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    const regenBtn = wrapper.find('.btn-suggest')
    await regenBtn.trigger('click')
    const emitted = wrapper.emitted('regenerate')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toEqual([])
  })

  it('emits reject when confirmed', async () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    const rejectBtn = wrapper.find('.btn-reject')
    await rejectBtn.trigger('click')
    // Wait for confirm state
    expect(wrapper.text()).toContain('Sure?')

    const yesBtn = wrapper.findAll('button').find(b => b.text().includes('Yes, reject'))
    await yesBtn!.trigger('click')
    expect(wrapper.emitted('reject')).toBeTruthy()
  })

  it('adds feedback items', async () => {
    const wrapper = mount(DraftApprovalPanel, {
      props: { artifacts: mockArtifacts },
    })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('Add dark mode')
    const addBtn = wrapper.find('.add-feedback-btn')
    await addBtn.trigger('click')
    expect(wrapper.text()).toContain('Add dark mode')
  })
})
