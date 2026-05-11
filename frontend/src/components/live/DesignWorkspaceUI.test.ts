import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DesignWorkspaceUI from './DesignWorkspaceUI.vue'

describe('DesignWorkspaceUI', () => {
  it('renders with correct appType prop', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { appType: 'GAME', executionResult: null }
    })
    expect(wrapper.exists()).toBe(true)
  })

  it('shows Concept tab by default', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { appType: 'GAME', executionResult: null }
    })
    const conceptBtn = wrapper.findAll('.tab-btn').at(0)
    expect(conceptBtn?.text()).toContain('Concept')
    expect(conceptBtn?.classes()).toContain('active')
  })

  it('shows textarea and generate button in Concept tab', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { appType: 'GENERATOR', executionResult: null }
    })
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
    const allBtns = wrapper.findAll('button')
    const generateBtn = allBtns.find(b => b.text().includes('Generate Draft'))
    expect(generateBtn?.exists()).toBe(true)
  })

  it('shows Review tab with plan when executionResult contains plan', async () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: {
        appType: 'GAME',
        executionResult: { plan: '## Game Design\n\nA tower defense game...' }
      }
    })
    // Should auto-switch to review tab
    const reviewBtn = wrapper.findAll('.tab-btn').at(1)
    expect(reviewBtn?.text()).toContain('Review')
    const planContent = wrapper.find('.plan-content')
    expect(planContent.exists()).toBe(true)
  })

  it('shows Output tab with files when executionResult contains files', async () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: {
        appType: 'GAME',
        executionResult: {
          files: [
            { name: 'game.html', content: '<html></html>', type: 'text/html' },
            { name: 'gdd.md', content: '# GDD', type: 'text/markdown' }
          ]
        }
      }
    })
    // Should auto-switch to output tab
    const fileItems = wrapper.findAll('.file-item')
    expect(fileItems.length).toBe(2)
    expect(fileItems.at(0)?.text()).toContain('game.html')
    expect(fileItems.at(1)?.text()).toContain('gdd.md')
  })

  it('shows download buttons for each file in Output tab', async () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: {
        appType: 'GENERATOR',
        executionResult: {
          files: [
            { name: 'output.txt', content: 'hello', type: 'text/plain' }
          ]
        }
      }
    })
    const downloadBtns = wrapper.findAll('.download-btn')
    expect(downloadBtns.length).toBe(1)
  })

  it('renders for GENERATOR app type', () => {
    const wrapper = mount(DesignWorkspaceUI, {
      props: { appType: 'GENERATOR', executionResult: null }
    })
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.design-workspace').exists()).toBe(true)
  })
})
