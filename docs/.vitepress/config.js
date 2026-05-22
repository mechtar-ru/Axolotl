import { defineConfig } from 'vitepress'

export default defineConfig({
  ignoreDeadLinks: true,
  base: '/Axolotl/',
  title: 'Axolotl',
  description: 'Visual AI-agent orchestration app',
  themeConfig: {
    nav: [
      { text: 'EN', link: '/en/' },
      { text: 'RU', link: '/ru/' }
    ],
    sidebar: {
      '/en/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Installation', link: '/en/getting-started' }
          ]
        },
        {
          text: 'Documentation',
          items: [
            { text: 'Architecture', link: '/en/architecture' },
            { text: 'Pipeline System', link: '/en/pipeline' },
            { text: 'Node Types', link: '/en/nodes' }
          ]
        }
      ],
      '/ru/': [
        {
          text: 'Начало работы',
          items: [
            { text: 'Установка', link: '/ru/getting-started' }
          ]
        },
        {
          text: 'Документация',
          items: [
            { text: 'Архитектура', link: '/ru/architecture' },
            { text: 'Pipeline System', link: '/ru/pipeline' },
            { text: 'Типы узлов', link: '/ru/nodes' }
          ]
        }
      ]
    }
  }
})
