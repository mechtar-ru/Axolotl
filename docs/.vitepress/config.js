import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/Axolotl/',
  title: 'Axolotl',
  description: 'Visual AI-agent orchestration app',
  themeConfig: {
    nav: [
      { text: 'EN', link: '/en/' },
      { text: 'RU', link: '/ru/' }
    ],
    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Installation', link: '/en/getting-started' },
          { text: 'Installation (RU)', link: '/ru/getting-started' }
        ]
      }
    ]
  }
})