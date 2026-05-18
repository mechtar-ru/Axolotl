import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../authStore'

vi.mock('axios', () => ({
  default: {
    create: () => ({
      post: vi.fn(),
      get: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
      interceptors: {
        request: { use: vi.fn() },
        response: { use: vi.fn() },
      },
    }),
  },
}))

describe('authStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('starts unauthenticated', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
    expect(store.isAdmin).toBe(false)
    expect(store.username).toBeNull()
  })

  it('restores auth from localStorage', () => {
    localStorage.setItem('axolotl_token', 'restored-token')
    localStorage.setItem('axolotl_username', 'admin')
    localStorage.setItem('axolotl_role', 'admin')
    // Re-create pinia to trigger store init from localStorage
    const pinia2 = createPinia()
    setActivePinia(pinia2)
    const store2 = useAuthStore()
    expect(store2.isAuthenticated).toBe(true)
    expect(store2.username).toBe('admin')
    expect(store2.isAdmin).toBe(true)
  })

  it('logout clears all data', () => {
    const store = useAuthStore()
    store.token = 'test'
    store.username = 'admin'
    store.role = 'admin'
    localStorage.setItem('axolotl_token', 'test')

    store.logout()

    expect(store.token).toBeNull()
    expect(store.username).toBeNull()
    expect(store.role).toBeNull()
    expect(localStorage.getItem('axolotl_token')).toBeNull()
  })

  it('getAuthHeaders returns Bearer token', () => {
    const store = useAuthStore()
    store.token = 'my-token'
    expect(store.getAuthHeaders()).toEqual({ Authorization: 'Bearer my-token' })
  })

  it('getAuthHeaders returns empty when no token', () => {
    const store = useAuthStore()
    store.token = null
    expect(store.getAuthHeaders()).toEqual({})
  })
})
