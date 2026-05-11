import { describe, it, expect } from 'vitest'
import { templates, getTemplateById, getTemplatesByType } from './index'

describe('templates index', () => {
  it('includes the Sokoban game template', () => {
    const sokoban = getTemplateById('template-sokoban')
    expect(sokoban).toBeDefined()
    expect(sokoban?.name).toBe('Sokoban Game')
  })

  it('has GAME appType on Sokoban template', () => {
    const sokoban = getTemplateById('template-sokoban')
    expect(sokoban?.appType).toBe('GAME')
  })

  it('filters templates by GAME type', () => {
    const gameTemplates = getTemplatesByType('GAME')
    expect(gameTemplates.length).toBeGreaterThanOrEqual(1)
    expect(gameTemplates[0]!.id).toBe('template-sokoban')
  })

  it('does not break existing CHAT templates', () => {
    const chat = getTemplateById('template-chat')
    expect(chat).toBeDefined()
    expect(chat?.appType).toBe('CHAT')
  })
})
