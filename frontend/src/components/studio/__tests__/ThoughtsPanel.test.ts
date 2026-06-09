import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import ThoughtsPanel from '../ThoughtsPanel.vue'

function getByTestId(id: string): Element | null {
  return document.body.querySelector(`[data-testid="${id}"]`)
}

describe('ThoughtsPanel', () => {
  const baseProps = {
    reasoning: null,
    isOpen: true,
  }

  afterEach(() => {
    vi.restoreAllMocks()
    // Clean up teleported elements from body
    document.body.innerHTML = ''
  })

  it('renders when isOpen is true', () => {
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test reasoning' },
      attachTo: document.body,
    })
    // Teleported content lands in document.body
    expect(document.body.querySelector('.thoughts-panel-overlay')).toBeTruthy()
    expect(document.body.querySelector('.thoughts-panel')).toBeTruthy()
  })

  it('does not render when isOpen is false', () => {
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test reasoning', isOpen: false },
      attachTo: document.body,
    })
    expect(document.body.querySelector('.thoughts-panel-overlay')).toBeFalsy()
  })

  it('displays reasoning text when provided', () => {
    const reasoning = 'I need to analyze the input first. Step 1: check type. Step 2: validate format.'
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning },
      attachTo: document.body,
    })
    expect(document.body.textContent).toContain(reasoning)
  })

  it('shows empty state when reasoning is null', () => {
    mount(ThoughtsPanel, {
      props: baseProps,
      attachTo: document.body,
    })
    expect(document.body.textContent).toContain('No reasoning available')
  })

  it('shows empty state when reasoning is empty string', () => {
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: '' },
      attachTo: document.body,
    })
    expect(document.body.textContent).toContain('No reasoning available')
  })

  it('has a close button that emits close event', async () => {
    const wrapper = mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test' },
      attachTo: document.body,
    })
    const closeBtn = document.body.querySelector('.thoughts-close') as HTMLElement
    expect(closeBtn).toBeTruthy()
    closeBtn.click()
    expect(wrapper.emitted('close')).toBeTruthy()
    expect(wrapper.emitted('close')!.length).toBe(1)
  })

  it('overlay click emits close (backdrop dismiss)', async () => {
    const wrapper = mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test' },
      attachTo: document.body,
    })
    const overlay = document.body.querySelector('.thoughts-panel-overlay') as HTMLElement
    overlay.click()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('panel click does not close (stopPropagation)', async () => {
    const wrapper = mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test' },
      attachTo: document.body,
    })
    const panel = document.body.querySelector('.thoughts-panel') as HTMLElement
    panel.click()
    expect(wrapper.emitted('close')).toBeFalsy()
  })

  it('shows Copy button when reasoning is present', () => {
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test reasoning text' },
      attachTo: document.body,
    })
    const copyBtn = document.body.querySelector('.btn-copy')
    expect(copyBtn).toBeTruthy()
    expect(copyBtn!.textContent).toContain('Copy')
  })

  it('hides Copy button when reasoning is null', () => {
    mount(ThoughtsPanel, {
      props: baseProps,
      attachTo: document.body,
    })
    expect(document.body.querySelector('.btn-copy')).toBeFalsy()
  })

  it('copy button calls clipboard API and shows Copied status', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, {
      clipboard: { writeText },
    })

    vi.useFakeTimers()
    const reasoning = 'Reasoning text to copy'
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning },
      attachTo: document.body,
    })

    const copyBtn = document.body.querySelector('.btn-copy') as HTMLElement
    copyBtn.click()
    // Wait for microtasks (async copy)
    await vi.advanceTimersByTimeAsync(10)
    expect(writeText).toHaveBeenCalledWith(reasoning)
    await vi.advanceTimersByTimeAsync(10)
    expect(document.body.querySelector('.btn-copy')!.textContent).toContain('Copied!')

    // After 2s, should revert to 'Copy'
    await vi.advanceTimersByTimeAsync(2100)
    expect(document.body.querySelector('.btn-copy')!.textContent).toContain('Copy')

    vi.useRealTimers()
  })

  it('copy button shows Failed when clipboard API rejects', async () => {
    Object.assign(navigator, {
      clipboard: {
        writeText: vi.fn().mockRejectedValue(new Error('Permission denied')),
      },
    })

    vi.useFakeTimers()
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test' },
      attachTo: document.body,
    })

    const copyBtn = document.body.querySelector('.btn-copy') as HTMLElement
    copyBtn.click()
    await vi.advanceTimersByTimeAsync(50)
    expect(document.body.querySelector('.btn-copy')!.textContent).toContain('Failed')
    vi.useRealTimers()
  })

  it('has title LLM Thoughts & Reasoning', () => {
    mount(ThoughtsPanel, {
      props: { ...baseProps, reasoning: 'Test' },
      attachTo: document.body,
    })
    expect(document.body.textContent).toContain('LLM Thoughts')
    expect(document.body.textContent).toContain('Reasoning')
  })
})
