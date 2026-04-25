import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Axolotl',
  description: 'Visual AI Agent Orchestration Platform',

  outDir: '../.vitepress/dist',

  head: [
    ['link', { rel: 'icon', href: '/logo.svg' }]
  ],

  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: 'Getting Started', link: '/en/getting-started' },
      { text: 'Nodes', link: '/en/nodes/' },
      { text: 'Начало работы', link: '/ru/getting-started' },
      { text: 'GitHub', link: 'https://github.com/mechtar-ru/Axolotl' }
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
          text: 'Nodes',
          items: [
            { text: 'Overview', link: '/en/nodes/' },
            { text: 'Source', link: '/en/nodes/source' },
            { text: 'Agent', link: '/en/nodes/agent' },
            { text: 'Output', link: '/en/nodes/output' },
            { text: 'Condition', link: '/en/nodes/condition' }
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
          text: 'Узлы',
          items: [
            { text: 'Обзор', link: '/ru/nodes/' },
            { text: 'Source', link: '/ru/nodes/source' },
            { text: 'Agent', link: '/ru/nodes/agent' },
            { text: 'Output', link: '/ru/nodes/output' },
            { text: 'Condition', link: '/ru/nodes/condition' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/mechtar-ru/Axolotl' }
    ]
  }
})