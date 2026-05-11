import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const isLoaded = ref(false)

  function applyTheme(val: string) {
    if (val === 'system') {
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
      document.documentElement.setAttribute('data-theme', prefersDark ? 'dark' : 'light')
    } else {
      document.documentElement.setAttribute('data-theme', val)
    }
  }

  function initTheme() {
    const saved = localStorage.getItem('axolotl-theme') as 'light' | 'dark' | 'system' | null
    if (saved) theme.value = saved
    applyTheme(theme.value)

    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      if (theme.value === 'system') applyTheme('system')
    })

    isLoaded.value = true
  }

  function setTheme(val: 'light' | 'dark' | 'system') {
    theme.value = val
    localStorage.setItem('axolotl-theme', val)
    applyTheme(val)
  }

  return { theme, isLoaded, initTheme, setTheme }
})
