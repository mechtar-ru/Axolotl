import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { settingsApi, type ProviderInfo } from '../services/api'

const PROVIDERS_CACHE_KEY = 'axolotl-providers-cache'
const PROVIDERS_CACHE_TTL = 24 * 60 * 60 * 1000 // 24 hours

function loadProvidersCache(): ProviderInfo[] | null {
  try {
    const stored = localStorage.getItem(PROVIDERS_CACHE_KEY)
    if (!stored) return null
    const { data, timestamp } = JSON.parse(stored)
    if (Date.now() - timestamp > PROVIDERS_CACHE_TTL) return null
    return data
  } catch {
    return null
  }
}

function saveProvidersCache(providers: ProviderInfo[]) {
  try {
    localStorage.setItem(PROVIDERS_CACHE_KEY, JSON.stringify({
      data: providers,
      timestamp: Date.now()
    }))
  } catch {
    // Ignore storage errors
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const isLoaded = ref(false)
  const providers = ref<ProviderInfo[]>([])
  const providersLoaded = ref(false)
  const projectsFolder = ref('')
  const projectsFolderLoaded = ref(false)

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
    
    // Try to load from cache first
    const cached = loadProvidersCache()
    if (cached) {
      providers.value = cached
      providersLoaded.value = true
    }
    
    try {
      providers.value = await settingsApi.getProviders()
      providersLoaded.value = true
      saveProvidersCache(providers.value)
    } catch {
      // Non-critical — providers will be fetched on Settings page too
    }
  }

  /** Force-refresh provider list from backend */
  async function refreshProviders() {
    try {
      providers.value = await settingsApi.getProviders()
      providersLoaded.value = true
      saveProvidersCache(providers.value)
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

    // Invalidate cache so next access refetches fresh data
    providersLoaded.value = false
  }

  // ──── Projects folder ────

  async function loadProjectsFolder() {
    if (projectsFolderLoaded.value) return
    try {
      const folder = await settingsApi.getProjectsFolder()
      projectsFolder.value = folder
    } catch {
      // non-critical
    }
    projectsFolderLoaded.value = true
  }

  async function saveProjectsFolder(folder: string) {
    try {
      await settingsApi.setProjectsFolder(folder)
      projectsFolder.value = folder
    } catch {
      // non-critical
    }
  }

  // ─── Providers cache ──────────────────────────────────────────────
  const PROVIDERS_CACHE_KEY = 'axolotl_providers_cache'
  const PROVIDERS_CACHE_TTL = 5 * 60 * 1000 // 5 minutes

  function saveProvidersCache(providers: ProviderInfo[]) {
    try {
      const cache = {
        data: providers,
        timestamp: Date.now()
      }
      localStorage.setItem(PROVIDERS_CACHE_KEY, JSON.stringify(cache))
    } catch {
      // Ignore localStorage errors
    }
  }

  function loadProvidersCache(): ProviderInfo[] | null {
    try {
      const raw = localStorage.getItem(PROVIDERS_CACHE_KEY)
      if (!raw) return null
      const cache = JSON.parse(raw)
      if (Date.now() - cache.timestamp > PROVIDERS_CACHE_TTL) {
        localStorage.removeItem(PROVIDERS_CACHE_KEY)
        return null
      }
      return cache.data
    } catch {
      localStorage.removeItem(PROVIDERS_CACHE_KEY)
      return null
    }
  }

  function clearProvidersCache() {
    localStorage.removeItem(PROVIDERS_CACHE_KEY)
  }

  return {
    theme, isLoaded, providers, providersLoaded,
    projectsFolder, projectsFolderLoaded,
    initTheme, setTheme, fetchProviders, refreshProviders,
    getModelsForProvider, getAllModelOptions, setModelDisabled,
    loadProjectsFolder, saveProjectsFolder,
    saveProvidersCache, loadProvidersCache, clearProvidersCache,
  }
})
