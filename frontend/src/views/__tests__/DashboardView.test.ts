import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useSchemaStore } from '@/stores/schemaStore'
import DashboardView from '../DashboardView.vue'
import { schemaApi } from '../../services/api'
import { appApi } from '../../services/api'

// Mock localStorage to avoid localStorage issues in test environment
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
}
Object.defineProperty(global, 'localStorage', { value: localStorageMock, writable: true })

// Mock modules
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/services/api', () => ({
  schemaApi: {
    getSchemas: vi.fn(),
    createSchema: vi.fn(),
  },
  appApi: {
    checkTargetPath: vi.fn(),
    createApp: vi.fn(),
  },
}))

vi.mock('@/components/app/AppCard.vue', () => ({
  default: {
    name: 'AppCard',
    template: '<div class="mock-app-card" :data-testid="app.name">{{ app.name }} {{ app.isGenerated ? \'[generated]\' : \'\' }} {{ app.status }}</div>',
    props: ['app', 'onClick'],
  },
}))

vi.mock('@/components/app/TemplateCard.vue', () => ({
  default: {
    name: 'TemplateCard',
    template: '<div class="mock-template-card" @click="$emit(\'select\')">{{ template.name }}</div>',
    props: ['template'],
  },
}))

vi.mock('@/stores/settingsStore', () => ({
  useSettingsStore: vi.fn(() => ({
    fetchProviders: vi.fn().mockResolvedValue(undefined),
    getAllModelOptions: vi.fn(() => []),
    providersLoaded: true,
    loadProjectsFolder: vi.fn().mockResolvedValue(undefined),
    projectsFolder: '',
  })),
}))

vi.mock('@/stores/schemaStore', () => {
  const state = { schemas: [] as any[] }
  return {
    useSchemaStore: vi.fn(() => ({
      get schemas() { return state.schemas },
      set schemas(v: any[]) { state.schemas = v },
      loading: false,
      loadSchemas: vi.fn(() => Promise.resolve()),
    })),
  }
})

const mockSchemas = [
  { id: '1', name: 'Chat App', appType: 'CHAT', targetPath: '/Users/evgenijtihomirov/git/Axolotl/Chat App/', nodes: [], edges: [], description: '', version: '1.0' },
  { id: '2', name: 'Custom Tool', appType: 'CUSTOM', nodes: [], edges: [], description: '', version: '1.0' },
  { id: '3', name: 'Game Project', appType: 'GAME', targetPath: '/Users/evgenijtihomirov/git/Axolotl/Game/', nodes: [], edges: [], description: '', version: '1.0' },
]

describe('DashboardView', () => {
  function setSchemas(schemas: any[]) {
    const store = useSchemaStore()
    ;(store as any).schemas = schemas
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(schemaApi.getSchemas).mockResolvedValue(mockSchemas)
    vi.mocked(appApi.checkTargetPath).mockResolvedValue({ exists: false, targetPath: '' })
  })

  it('renders the header and title', async () => {
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Welcome to Axolotl Studio')
  })

  it('shows search input', () => {
    const wrapper = mount(DashboardView)
    expect(wrapper.find('.search-input').exists()).toBe(true)
    expect(wrapper.find('.search-input').attributes('placeholder')).toBe('Search apps...')
  })

  it('renders all schemas as AppCards', async () => {
    setSchemas(mockSchemas)
    const wrapper = mount(DashboardView)
    await flushPromises()
    await wrapper.vm.$nextTick()
    const cards = wrapper.findAll('.mock-app-card')
    expect(cards.length).toBe(3)
  })

  it('filters apps by search query', async () => {
    setSchemas(mockSchemas)
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()

    const input = wrapper.find('.search-input')
    await input.setValue('Chat')
    await wrapper.vm.$nextTick()

    const cards = wrapper.findAll('.mock-app-card')
    expect(cards.length).toBe(1)
    expect(cards[0]!.text()).toContain('Chat App')
  })

  it('shows empty state when search matches nothing', async () => {
    setSchemas(mockSchemas)
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()

    const input = wrapper.find('.search-input')
    await input.setValue('ZZZZNOTFOUND')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('No apps matching')
    expect(wrapper.text()).toContain('ZZZZNOTFOUND')
  })

  it('shows empty state when no schemas exist', async () => {
    setSchemas([])
    vi.mocked(schemaApi.getSchemas).mockResolvedValue([])
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('No apps yet')
  })

  it('marks generated apps with isGenerated=true and status=active', async () => {
    setSchemas(mockSchemas)
    const wrapper = mount(DashboardView)
    await flushPromises()
    await wrapper.vm.$nextTick()
    const cards = wrapper.findAll('.mock-app-card')

    // Chat App has targetPath + CHAT type → generated
    expect(cards[0]!.text()).toContain('[generated]')
    expect(cards[0]!.text()).toContain('active')

    // Custom Tool has CUSTOM type → not generated
    expect(cards[1]!.text()).not.toContain('[generated]')

    // Game Project has targetPath + GAME type → generated
    expect(cards[2]!.text()).toContain('[generated]')
    expect(cards[2]!.text()).toContain('active')
  })

  it('renders templates section below apps', async () => {
    setSchemas(mockSchemas)
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    const appSection = wrapper.find('.apps-section')
    const templateSection = wrapper.find('.templates-section')

    // Templates come after apps in DOM order
    const appSectionIndex = appSection.element.compareDocumentPosition(templateSection.element)
    expect(appSectionIndex & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('clears search query back to full list', async () => {
    setSchemas(mockSchemas)
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()

    const input = wrapper.find('.search-input')
    await input.setValue('Chat')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.mock-app-card').length).toBe(1)

    await input.setValue('')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.mock-app-card').length).toBe(3)
  })
})
