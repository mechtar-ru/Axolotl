import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AppCard from '../AppCard.vue'

// Mock vue-router
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

const baseApp = {
  id: '1',
  name: 'Test App',
  description: 'A test app description',
  appType: 'CUSTOM',
  createdAt: '2026-01-15T10:00:00Z',
}

describe('AppCard', () => {
  it('renders app name and description', () => {
    const wrapper = mount(AppCard, { props: { app: baseApp } })
    expect(wrapper.text()).toContain('Test App')
    expect(wrapper.text()).toContain('A test app description')
  })

  it('renders app type badge and label', () => {
    const wrapper = mount(AppCard, { props: { app: { ...baseApp, appType: 'CHAT' } } })
    expect(wrapper.text()).toContain('Chat')
    expect(wrapper.find('.app-type-badge').exists()).toBe(true)
  })

  it('renders date string', () => {
    const wrapper = mount(AppCard, { props: { app: baseApp } })
    expect(wrapper.text()).toContain('Jan')
    expect(wrapper.text()).toContain('2026')
  })

  // === NEW TESTS for generated-app features ===

  it('does NOT show path row when isGenerated is false', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/MyApp/', isGenerated: false } },
    })
    expect(wrapper.find('.app-card-path').exists()).toBe(false)
  })

  it('does NOT show path row when isGenerated is true but targetPath is empty', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '', isGenerated: true } },
    })
    expect(wrapper.find('.app-card-path').exists()).toBe(false)
  })

  it('shows path row when isGenerated and targetPath are present', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/Sokoban Game/', isGenerated: true, status: 'active' } },
    })
    const pathRow = wrapper.find('.app-card-path')
    expect(pathRow.exists()).toBe(true)
    // Path text should show compact form
    expect(wrapper.find('.path-text').text()).toContain('Sokoban Game')
  })

  it('formats path correctly — replaces home dir with ~', () => {
    // Shallow path so formatPath shows all segments including ~
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/MyApp/', isGenerated: true } },
    })
    const text = wrapper.find('.path-text').text()
    expect(text).not.toContain('/Users/evgenijtihomirov')
    expect(text).toContain('~')
  })

  it('shows status dot with correct color for active status', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/App/', isGenerated: true, status: 'active' } },
    })
    const dot = wrapper.find('.status-dot')
    expect(dot.exists()).toBe(true)
    expect(dot.attributes('style')).toContain('background: rgb(76, 175, 80)') // #4caf50
  })

  it('shows status dot with gray color for idle status', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/App/', isGenerated: true, status: 'idle' } },
    })
    const dot = wrapper.find('.status-dot')
    expect(dot.exists()).toBe(true)
    expect(dot.attributes('style')).toContain('background: rgb(158, 158, 158)') // #9e9e9e
  })

  it('shows full path in title attribute (tooltip)', () => {
    const fullPath = '/Users/evgenijtihomirov/git/Axolotl/Sokoban Game/'
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: fullPath, isGenerated: true } },
    })
    expect(wrapper.find('.app-card-path').attributes('title')).toBe(fullPath)
  })

  it('shows only last 2 path segments', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/Deep/Nested/Project/', isGenerated: true } },
    })
    const text = wrapper.find('.path-text').text()
    expect(text).toContain('Nested')
    expect(text).toContain('Project')
    expect(text).not.toContain('Deep') // only last 2
  })

  it('emits click event on card click', async () => {
    const wrapper = mount(AppCard, { props: { app: baseApp } })
    await wrapper.find('.app-card').trigger('click')
    expect(wrapper.emitted('click')).toBeTruthy()
  })

  it('applies correct app type color', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, appType: 'CHAT' } },
    })
    const badge = wrapper.find('.app-type-badge')
    expect(badge.attributes('style')).toContain('background')
  })
})
