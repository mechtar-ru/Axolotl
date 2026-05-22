import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(defineConfig({
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
        },
        {
          text: 'C4 Diagrams',
          items: [
            { text: 'System Context', link: '/architecture/c4-context' },
            { text: 'Containers', link: '/architecture/c4-containers' },
            { text: 'Frontend Components', link: '/architecture/c4-components-frontend' },
            { text: 'Backend Components', link: '/architecture/c4-components-backend' },
            { text: 'Pipeline Execution', link: '/architecture/c4-dynamic-execution' },
            { text: 'Deployment', link: '/architecture/c4-deployment' }
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
        },
        {
          text: 'C4 Диаграммы',
          items: [
            { text: 'System Context', link: '/architecture/c4-context' },
            { text: 'Containers', link: '/architecture/c4-containers' },
            { text: 'Frontend Components', link: '/architecture/c4-components-frontend' },
            { text: 'Backend Components', link: '/architecture/c4-components-backend' },
            { text: 'Pipeline Execution', link: '/architecture/c4-dynamic-execution' },
            { text: 'Deployment', link: '/architecture/c4-deployment' }
          ]
        }
      ]
    }
  }
}))
