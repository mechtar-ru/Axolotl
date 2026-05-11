import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GameAppUI from './GameAppUI.vue'

describe('GameAppUI', () => {
  it('renders the game interface', () => {
    const wrapper = mount(GameAppUI)
    expect(wrapper.exists()).toBe(true)
  })

  it('shows input section for game parameters', () => {
    const wrapper = mount(GameAppUI)
    expect(wrapper.text()).toContain('Level Configuration')
  })

  it('shows level input by default', () => {
    const wrapper = mount(GameAppUI)
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
  })

  it('shows a run button to start the game', () => {
    const wrapper = mount(GameAppUI)
    const button = wrapper.find('button')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('Start')
  })
})
