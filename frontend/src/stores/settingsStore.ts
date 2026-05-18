import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { settingsApi, type ProviderInfo } from '../services/api'

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const isLoaded = ref(false)
  const providers = ref<ProviderInfo[]>([])
  const providersLoaded = ref(false)

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

  /** Pre-fetch provider info on app startup — populates model lists early */
  async function fetchProviders() {
    if (providersLoaded.value) return
    try {
      providers.value = await settingsApi.getProviders()
      providersLoaded.value = true
    } catch {
      // Non-critical — providers will be fetched on Settings page too
    }
  }

  /** Force-refresh provider list from backend */
  async function refreshProviders() {
    try {
      providers.value = await settingsApi.getProviders()
      providersLoaded.value = true
    } catch {
      // Silent failure
    }
  }

  /** Return models for a provider, filtered by disabledModels */
  function getModelsForProvider(name: string): string[] {
    const p = providers.value.find(p => p.name === name)
    if (!p?.models) return []
    const disabled = p.disabledModels ?? []
    if (disabled.length === 0) return p.models
    return p.models.filter(m => !disabled.includes(m))
  }

  /** Return ALL enabled models from available, non-disabled providers */
  function getAllModelOptions(): { value: string; label: string; group: string }[] {
    const opts: { value: string; label: string; group: string }[] = []
    for (const p of providers.value) {
      if (!p.available) continue
      const group = p.name.charAt(0).toUpperCase() + p.name.slice(1)
      const disabled = p.disabledModels ?? []
      if (p.models?.length > 0) {
        for (const model of p.models) {
          if (disabled.length > 0 && disabled.includes(model)) continue
          opts.push({ value: model, label: model, group })
        }
      }
    }
    return opts
  }

  /** Toggle a model for a provider: add/remove from disabled list */
  async function setModelDisabled(providerName: string, model: string, disabled: boolean) {
    const p = providers.value.find(p => p.name === providerName)
    if (!p) return

    const currentDisabled = p.disabledModels ?? []
    let newDisabled: string[]

    if (disabled) {
      // Add to disabled list
      if (!currentDisabled.includes(model)) {
        newDisabled = [...currentDisabled, model]
      } else {
        newDisabled = currentDisabled
      }
    } else {
      // Remove from disabled list
      newDisabled = currentDisabled.filter(m => m !== model)
    }

    // Persist to backend
    await settingsApi.setDisabledModels(providerName, newDisabled)

    // Update local cache
    p.disabledModels = newDisabled.length > 0 ? newDisabled : undefined
  }

  return {
    theme, isLoaded, providers, providersLoaded,
    initTheme, setTheme, fetchProviders, refreshProviders,
    getModelsForProvider, getAllModelOptions, setModelDisabled,
  }
})
