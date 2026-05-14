import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ReviewApprovalDialog from '../ReviewApprovalDialog.vue'

describe('ReviewApprovalDialog', () => {
  const baseProps = {
    visible: true,
    schemaId: 'test-id',
    executionId: 'exec-1',
    nodeId: 'review-1',
    originalPlan: 'Create a game',
    rewrittenPlan: 'Create a Sokoban game with 5 levels',
    findings: [
      { source: 'premortem', severity: 'HIGH', description: 'Missing @', suggestion: 'Add @' }
    ],
    iteration: 2,
    maxIterations: 0,
    mode: 'manual',
    feedbackHistory: [{ text: 'Add undo', applied: true }]
  }

  it('renders title with iteration and mode', () => {
    const wrapper = mount(ReviewApprovalDialog, { props: baseProps })
    expect(wrapper.text()).toContain('Iteration 2')
    expect(wrapper.text()).toContain('Manual')
  })

  it('shows findings with severity', () => {
    const wrapper = mount(ReviewApprovalDialog, { props: baseProps })
    expect(wrapper.text()).toContain('HIGH')
    expect(wrapper.text()).toContain('Missing @')
  })

  it('shows feedback history', () => {
    const wrapper = mount(ReviewApprovalDialog, { props: baseProps })
    expect(wrapper.text()).toContain('Add undo')
  })

  it('has three action buttons', () => {
    const wrapper = mount(ReviewApprovalDialog, { props: baseProps })
    expect(wrapper.text()).toContain('Edit Plan')
    expect(wrapper.text()).toContain('Suggest')
    expect(wrapper.text()).toContain('Accept')
  })

  it('emits approve on Accept click', async () => {
    const wrapper = mount(ReviewApprovalDialog, { props: baseProps })
    const buttons = wrapper.findAll('button')
    const acceptBtn = buttons.find(b => b.text().includes('Accept'))
    if (acceptBtn) {
      await acceptBtn.trigger('click')
      expect(wrapper.emitted('approve')).toBeTruthy()
    }
  })
})
