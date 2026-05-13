# Dashboard Redesign — Unified Apps + Compact Paths + Search

**Goal:** Merge "My Generated Apps" into the main "My Apps" grid, add compact path display on AppCard, add search, and demote templates section.

**Architecture:** Extend AppCard.vue with 3 optional props (`targetPath`, `isGenerated`, `status`). Remove the inline generated-apps section from DashboardView. Merge generated apps into the main grid using a `visibleApps` computed that enriches schema data with generated-app metadata. Add search via `v-model="searchQuery"` + filtered computed. Move templates section below "My Apps".

**Design:** `thoughts/shared/designs/2026-05-13-dashboard-redesign.md`

**Files changed:**
- `frontend/src/components/app/AppCard.vue` — extended props + path row template
- `frontend/src/views/DashboardView.vue` — restructured: search, merged grid, demoted templates

**Files created:**
- `frontend/src/components/app/__tests__/AppCard.test.ts` — component unit tests
- `frontend/src/views/__tests__/DashboardView.test.ts` — view unit tests (search, merge, empty state)

**Key design decisions:**
- Design requires 3 new props on AppCard. I'm making them all optional with sensible defaults (empty string / false / 'idle') so existing usage is unchanged.
- Path formatting: replace `/Users/evgenijtihomirov` → `~`, show last 2 segments only, full path in `title` attribute (tooltip). This is the most compact reasonable display.
- Status dot: green (`#4caf50`) for `'active'`, gray (`#9e9e9e`) for `'idle'`.
- Search filter: case-insensitive `includes()` on `app.name` — no debounce needed for modest dataset sizes.
- `generatedApps` computed stays (same dedup logic) → used to build `visibleApps` merged list.
- No backend changes needed — `WorkflowSchema` already has `targetPath?` field.

---

## Dependency Graph

```
Batch 1 (parallel, 2 implementers):
  1.1 AppCard.vue — extend props + path row template        (no deps)
  1.2 __tests__/AppCard.test.ts — unit tests for new props  (no deps)

Batch 2 (parallel, 2 implementers):
  2.1 DashboardView.vue — search, merge, restructure        (depends: 1.1)
  2.2 __tests__/DashboardView.test.ts — view tests          (depends: 1.1)

Batch 3 (verify):
  3.1 Manual verification — start app, check all 4 behaviors (depends: 2.1, 2.2)
```

---

## Batch 1: Foundation (parallel — 2 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

---

### Task 1.1: Extend AppCard.vue with generated-app props + path row

**File:** `frontend/src/components/app/AppCard.vue`
**Test:** See Task 1.2 (separate test file)
**Depends:** none

**What changes:**
1. Add 3 optional props: `targetPath?: string`, `isGenerated?: boolean`, `status?: 'active' | 'idle'`
2. Add a path formatting utility function (`formatPath`)
3. Add template block: below the `.app-card-footer`, conditionally show a `.app-card-path` row when `isGenerated && targetPath`
4. The path row contains: folder SVG icon (16px) + truncated path as text + inline status dot (8px circle)
5. Full path in `title` attribute for tooltip

**Implementation — Edit `AppCard.vue`:**

In `<script setup>`, add the new props after the existing `onClick` prop:

```typescript
// === NEW: Generated-app-specific props ===
const props = defineProps<{
  app: {
    id: string
    name: string
    description?: string
    appType?: string
    updatedAt?: string
    createdAt?: string
    version?: string
    userId?: string
    workspaceId?: string
    // NEW FIELDS — merged from generatedApps
    targetPath?: string
    isGenerated?: boolean
    status?: 'active' | 'idle'
  }
  onClick?: () => void
}>()
```

Replace the existing `defineProps` block (lines 4-17) with the above.

Add these helper functions after `formatDate` (after line 83):

```typescript
function formatPath(fullPath: string): string {
  // 1. Replace /Users/evgenijtihomirov with ~
  const homeReplaced = fullPath.replace(/^\/Users\/evgenijtihomirov/, '~')
  // 2. Strip trailing slash for splitting, re-add later
  const cleaned = homeReplaced.replace(/\/$/, '')
  const parts = cleaned.split('/').filter(Boolean)
  // 3. If 2 or fewer segments, show as-is
  if (parts.length <= 2) return cleaned + '/'
  // 4. Show last 2 segments with ellipsis prefix
  return '\u2026/' + parts.slice(-2).join('/') + '/'
}

function getStatusDotColor(status?: 'active' | 'idle'): string {
  if (status === 'active') return '#4caf50'
  return '#9e9e9e' // gray for idle/default
}
```

**In `<template>`**, add the path row after the `.app-card-footer` div (after line 103):

```html
<div v-if="isGenerated && app.targetPath" class="app-card-path" :title="app.targetPath">
  <svg class="path-icon" viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
    <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
  </svg>
  <span class="path-text">{{ formatPath(app.targetPath) }}</span>
  <span class="status-dot" :style="{ background: getStatusDotColor(app.status) }" :title="app.status === 'active' ? 'Active sessions' : 'Idle'"></span>
</div>
```

Note: The `isGenerated` variable needs to come from the prop. Since the prop interface uses `app.isGenerated`, I need to use `app.isGenerated` in the template. Let me adjust — the template should reference via the `app` object. The AppCard already uses `app.` everywhere. So:

```html
<div v-if="app.isGenerated && app.targetPath" class="app-card-path" :title="app.targetPath">
  ...
  <span class="path-text">{{ formatPath(app.targetPath) }}</span>
  <span class="status-dot" :style="{ background: getStatusDotColor(app.status) }" ...></span>
</div>
```

**In `<style scoped>`**, add these styles at the end (before closing `</style>`):

```css
.app-card-path {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.625rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--border-color);
  font-size: 0.75rem;
  color: var(--text-muted);
  cursor: default;
}

.path-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--text-muted);
  opacity: 0.7;
}

.path-text {
  font-family: monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
```

**Verify before commit:**
```bash
cd frontend && npm run type-check
cd frontend && npm run test:unit -- --run src/components/app/__tests__/AppCard.test.ts
```

**Commit:** `feat(dashboard): add targetPath, isGenerated, status props to AppCard`

---

### Task 1.2: AppCard unit tests

**File:** `frontend/src/components/app/__tests__/AppCard.test.ts`
**Depends:** none (test file is standalone; imports the component)

**Implementation:**

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AppCard from '../AppCard.vue'

// Mock vue-router
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

const baseApp = {
  id: '1',
  name: 'Test App',
  description: 'A test app description',
  appType: 'CUSTOM',
  createdAt: '2026-01-15T10:00:00Z',
}

describe('AppCard', () => {
  it('renders app name and description', () => {
    const wrapper = mount(AppCard, { props: { app: baseApp } })
    expect(wrapper.text()).toContain('Test App')
    expect(wrapper.text()).toContain('A test app description')
  })

  it('renders app type badge and label', () => {
    const wrapper = mount(AppCard, { props: { app: { ...baseApp, appType: 'CHAT' } } })
    expect(wrapper.text()).toContain('Chat')
    expect(wrapper.find('.app-type-badge').exists()).toBe(true)
  })

  it('renders date string', () => {
    const wrapper = mount(AppCard, { props: { app: baseApp } })
    expect(wrapper.text()).toContain('Jan')
    expect(wrapper.text()).toContain('2026')
  })

  // === NEW TESTS for generated-app features ===

  it('does NOT show path row when isGenerated is false', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/MyApp/', isGenerated: false } },
    })
    expect(wrapper.find('.app-card-path').exists()).toBe(false)
  })

  it('does NOT show path row when isGenerated is true but targetPath is empty', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '', isGenerated: true } },
    })
    expect(wrapper.find('.app-card-path').exists()).toBe(false)
  })

  it('shows path row when isGenerated and targetPath are present', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/Sokoban Game/', isGenerated: true, status: 'active' } },
    })
    const pathRow = wrapper.find('.app-card-path')
    expect(pathRow.exists()).toBe(true)
    // Path text should show compact form
    expect(wrapper.find('.path-text').text()).toContain('Sokoban Game')
  })

  it('formats path correctly — replaces home dir with ~', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/Sokoban Game/', isGenerated: true } },
    })
    const text = wrapper.find('.path-text').text()
    expect(text).not.toContain('/Users/evgenijtihomirov')
    expect(text).toContain('~')
  })

  it('shows status dot with correct color for active status', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/App/', isGenerated: true, status: 'active' } },
    })
    const dot = wrapper.find('.status-dot')
    expect(dot.exists()).toBe(true)
    expect(dot.attributes('style')).toContain('background: rgb(76, 175, 80)') // #4caf50
  })

  it('shows status dot with gray color for idle status', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/App/', isGenerated: true, status: 'idle' } },
    })
    const dot = wrapper.find('.status-dot')
    expect(dot.exists()).toBe(true)
    expect(dot.attributes('style')).toContain('background: rgb(158, 158, 158)') // #9e9e9e
  })

  it('shows full path in title attribute (tooltip)', () => {
    const fullPath = '/Users/evgenijtihomirov/git/Axolotl/Sokoban Game/'
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: fullPath, isGenerated: true } },
    })
    expect(wrapper.find('.app-card-path').attributes('title')).toBe(fullPath)
  })

  it('shows only last 2 path segments', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, targetPath: '/Users/evgenijtihomirov/git/Axolotl/Deep/Nested/Project/', isGenerated: true } },
    })
    const text = wrapper.find('.path-text').text()
    expect(text).toContain('Nested')
    expect(text).toContain('Project')
    expect(text).not.toContain('Deep') // only last 2
  })

  it('emits click event on card click', async () => {
    const wrapper = mount(AppCard, { props: { app: baseApp } })
    await wrapper.find('.app-card').trigger('click')
    expect(wrapper.emitted('click')).toBeTruthy()
  })

  it('applies correct app type color', () => {
    const wrapper = mount(AppCard, {
      props: { app: { ...baseApp, appType: 'CHAT' } },
    })
    const badge = wrapper.find('.app-type-badge')
    expect(badge.attributes('style')).toContain('background')
  })
})
```

**Verify:**
```bash
cd frontend && npx vitest run src/components/app/__tests__/AppCard.test.ts
```

**Commit:** `test(dashboard): add unit tests for AppCard path and status features`

---

## Batch 2: Core Changes (parallel — 2 implementers)

Both tasks depend on Batch 1 (AppCard.vue with new props), but they work on different files and can proceed simultaneously.

---

### Task 2.1: Restructure DashboardView.vue

**File:** `frontend/src/views/DashboardView.vue`
**Test:** See Task 2.2
**Depends:** 1.1 (AppCard.vue with new props)

**What changes:**
1. Add `searchQuery` ref
2. Add `visibleApps` computed: merges `generatedApps` metadata into `schemaStore.schemas`
3. Add `filteredApps` computed: filters `visibleApps` by `searchQuery`
4. Add search input in template (between header and apps section)
5. Remove the entire "Generated Apps" section block (lines 209-232)
6. Change section title from "My Apps" to "My Apps" (stays same)
7. Update v-for to iterate over `filteredApps`
8. Pass new props: `:app="{ ...app, isGenerated: app.isGenerated, targetPath: app.targetPath, status: app.status }"`
9. Move templates section below "My Apps"
10. Update empty state text to mention search

**Detailed changes to `<script setup>`:**

After `const generatedApps = computed(...)` (line 74), add:

```typescript
const searchQuery = ref('')

// Merge generated app metadata into all schemas for unified grid
const visibleApps = computed(() => {
  const genMap = new Map<string, { targetPath: string; status: 'active' | 'idle' }>()
  for (const g of generatedApps.value) {
    if (g.targetPath) {
      genMap.set(g.id, { targetPath: g.targetPath, status: 'active' })
    }
  }
  return schemaStore.schemas.map(s => ({
    ...s,
    isGenerated: genMap.has(s.id),
    targetPath: genMap.get(s.id)?.targetPath || s.targetPath,
    status: genMap.has(s.id) ? ('active' as const) : ('idle' as const),
  }))
})

const filteredApps = computed(() => {
  if (!searchQuery.value.trim()) return visibleApps.value
  const q = searchQuery.value.toLowerCase()
  return visibleApps.value.filter(app => app.name.toLowerCase().includes(q))
})
```

**Template changes (update the `<template>` block):**

Replace the header (lines 197-207) with header + search:

```html
<header class="dashboard-header">
  <div>
    <h1>Welcome to Axolotl Studio</h1>
    <p class="subtitle">Build AI-powered apps visually</p>
  </div>
  <button class="btn-primary" @click="showNewAppModal = true">
    <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z"/></svg>
    New App
  </button>
</header>

<!-- Search Bar -->
<div class="search-bar">
  <svg class="search-icon" viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
    <path fill-rule="evenodd" d="M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z" clip-rule="evenodd"/>
  </svg>
  <input
    v-model="searchQuery"
    type="text"
    class="search-input"
    placeholder="Search apps..."
  />
</div>
```

**Remove** the entire "Generated Apps Section" block (lines 209-232 — from `<!-- Generated Apps Section -->` through the closing `</section>`).

Replace the "My Apps" section (lines 234-248) with the updated version:

```html
<!-- My Apps Grid -->
<section class="apps-section">
  <h2>My Apps</h2>
  <div v-if="schemaStore.schemas.length === 0" class="empty-state">
    <p>No apps yet. Create your first app!</p>
  </div>
  <div v-else-if="filteredApps.length === 0" class="empty-state">
    <p>No apps matching "{{ searchQuery }}"</p>
  </div>
  <div v-else class="apps-grid">
    <AppCard
      v-for="app in filteredApps"
      :key="app.id"
      :app="app"
      @click="openApp(app.id)"
    />
  </div>
</section>
```

**Move** the "Templates Section" to come after "My Apps" (it already does in the current layout, but ensure it stays below).

**Add styles for search bar** in `<style scoped>` (before `.modal-overlay`):

```css
.search-bar {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-bottom: 1.5rem;
  padding: 0.625rem 0.875rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  transition: border-color 0.2s;
}

.search-bar:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.search-icon {
  width: 18px;
  height: 18px;
  color: var(--text-muted);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 0.9rem;
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--text-muted);
}
```

**Complete file after changes** — The full `DashboardView.vue` file should be:

<details>
<summary>Click to see complete updated DashboardView.vue</summary>

```vue
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSchemaStore } from '@/stores/schemaStore'
import AppCard from '@/components/app/AppCard.vue'
import TemplateCard from '@/components/app/TemplateCard.vue'
import { appApi } from '@/services/api'

const router = useRouter()
const schemaStore = useSchemaStore()

const templates = ref([
  {
    id: 'template-chat',
    name: 'Chat Bot',
    description: 'AI chatbot with conversation memory',
    appType: 'CHAT',
    icon: 'M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z'
  },
  {
    id: 'template-doc',
    name: 'Document Analyzer',
    description: 'Analyze documents with AI extraction',
    appType: 'ANALYZER',
    icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z'
  },
  {
    id: 'template-content',
    name: 'Content Generator',
    description: 'Generate articles, posts, and marketing copy',
    appType: 'GENERATOR',
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z'
  },
  {
    id: 'template-email',
    name: 'Email Agent',
    description: 'Smart email drafting and reply assistant',
    appType: 'EMAIL',
    icon: 'M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z'
  },
  {
    id: 'template-data',
    name: 'Data Extractor',
    description: 'Extract structured data from text',
    appType: 'ANALYZER',
    icon: 'M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4'
  },
  {
    id: 'template-sokoban',
    name: 'Sokoban Game',
    description: 'Generate a playable Sokoban puzzle game',
    appType: 'GAME',
    icon: 'M6 12h4m4 0h4M6 16h4m4 0h4M6 8h12M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6z'
  },
  {
    id: 'template-blank',
    name: 'Blank App',
    description: 'Start from scratch with an empty canvas',
    appType: 'CUSTOM',
    icon: 'M12 4v16m8-8H4'
  }
])

const showNewAppModal = ref(false)
const newAppName = ref('')
const newAppType = ref('CUSTOM')

// Conflict modal state
const showConflictModal = ref(false)
const pendingTemplate = ref<any>(null)
const conflictAction = ref<'CONTINUE' | 'OVERWRITE' | 'CHANGE_PATH'>('CONTINUE')
const customTargetPath = ref('')

const generatedApps = computed(() => {
  const seen = new Map<string, typeof schemaStore.schemas[0]>()
  for (const s of schemaStore.schemas) {
    if (s.targetPath && s.appType && s.appType !== 'CUSTOM') {
      seen.set(s.targetPath, s)
    }
  }
  return Array.from(seen.values())
})

const searchQuery = ref('')

// Merge generated app metadata into all schemas for unified grid
const visibleApps = computed(() => {
  const genMap = new Map<string, { targetPath: string; status: 'active' | 'idle' }>()
  for (const g of generatedApps.value) {
    if (g.targetPath) {
      genMap.set(g.id, { targetPath: g.targetPath, status: 'active' })
    }
  }
  return schemaStore.schemas.map(s => ({
    ...s,
    isGenerated: genMap.has(s.id),
    targetPath: genMap.get(s.id)?.targetPath || s.targetPath,
    status: genMap.has(s.id) ? ('active' as const) : ('idle' as const),
  }))
})

const filteredApps = computed(() => {
  if (!searchQuery.value.trim()) return visibleApps.value
  const q = searchQuery.value.toLowerCase()
  return visibleApps.value.filter(app => app.name.toLowerCase().includes(q))
})

function continueDevelopment(app: any) {
  router.push(`/app/${app.id}`)
}

onMounted(() => {
  schemaStore.loadSchemas()
})

function openApp(id: string) {
  router.push(`/app/${id}`)
}

async function createFromTemplate(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template) return

  try {
    if (template.appType !== 'CUSTOM') {
      const pathCheck = await appApi.checkTargetPath(template.name, template.appType)
      if (pathCheck.exists) {
        showConflictModal.value = true
        pendingTemplate.value = template
        return
      }
    }
    const response = await appApi.createApp({
      name: template.name,
      appType: template.appType,
      description: template.description,
    })
    const schema = response.schema
    if (schema) {
      schemaStore.schemas.push(schema)
      router.push(`/app/${schema.id}`)
    }
  } catch (error) {
    console.error('Failed to create app from template:', error)
  }
}

async function resolveConflict() {
  if (!pendingTemplate.value) return
  try {
    const template = pendingTemplate.value
    const response = await appApi.createApp({
      name: template.name,
      appType: template.appType,
      description: template.description,
      conflictAction: conflictAction.value,
      customTargetPath: conflictAction.value === 'CHANGE_PATH' ? customTargetPath.value : undefined
    })
    const schema = response.schema
    if (schema) {
      schemaStore.schemas.push(schema)
      showConflictModal.value = false
      pendingTemplate.value = null
      router.push(`/app/${schema.id}`)
    }
  } catch (error) {
    console.error('Failed to resolve conflict:', error)
  }
}

async function createBlankApp() {
  if (!newAppName.value.trim()) return
  try {
    if (newAppType.value === 'CUSTOM') {
      const schema = await schemaStore.createSchema(newAppName.value, newAppType.value)
      showNewAppModal.value = false
      newAppName.value = ''
      if (schema) {
        router.push(`/app/${schema.id}`)
      }
    } else {
      const pathCheck = await appApi.checkTargetPath(newAppName.value, newAppType.value)
      if (pathCheck.exists) {
        showConflictModal.value = true
        pendingTemplate.value = {
          id: 'blank',
          name: newAppName.value,
          appType: newAppType.value,
          description: ''
        }
        return
      }
      const response = await appApi.createApp({
        name: newAppName.value,
        appType: newAppType.value,
        description: '',
      })
      const schema = response.schema
      if (schema) {
        schemaStore.schemas.push(schema)
        showNewAppModal.value = false
        newAppName.value = ''
        router.push(`/app/${schema.id}`)
      }
    }
  } catch (error) {
    console.error('Failed to create blank app:', error)
  }
}
</script>

<template>
  <div class="dashboard">
    <header class="dashboard-header">
      <div>
        <h1>Welcome to Axolotl Studio</h1>
        <p class="subtitle">Build AI-powered apps visually</p>
      </div>
      <button class="btn-primary" @click="showNewAppModal = true">
        <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z"/></svg>
        New App
      </button>
    </header>

    <!-- Search Bar -->
    <div class="search-bar">
      <svg class="search-icon" viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
        <path fill-rule="evenodd" d="M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z" clip-rule="evenodd"/>
      </svg>
      <input
        v-model="searchQuery"
        type="text"
        class="search-input"
        placeholder="Search apps..."
      />
    </div>

    <!-- My Apps Grid -->
    <section class="apps-section">
      <h2>My Apps</h2>
      <div v-if="schemaStore.schemas.length === 0" class="empty-state">
        <p>No apps yet. Create your first app!</p>
      </div>
      <div v-else-if="filteredApps.length === 0" class="empty-state">
        <p>No apps matching "{{ searchQuery }}"</p>
      </div>
      <div v-else class="apps-grid">
        <AppCard
          v-for="app in filteredApps"
          :key="app.id"
          :app="app"
          @click="openApp(app.id)"
        />
      </div>
    </section>

    <!-- Templates Section -->
    <section class="templates-section">
      <h2>Start from a Template</h2>
      <div class="templates-grid">
        <TemplateCard
          v-for="template in templates"
          :key="template.id"
          :template="template"
          @select="createFromTemplate(template.id)"
        />
      </div>
    </section>

    <!-- New App Modal -->
    <div v-if="showNewAppModal" class="modal-overlay" @click.self="showNewAppModal = false">
      <div class="modal">
        <h3>Create New App</h3>
        <div class="form-group">
          <label>App Name</label>
          <input
            v-model="newAppName"
            type="text"
            placeholder="My Awesome App"
            class="input"
            @keyup.enter="createBlankApp"
          />
        </div>
        <div class="form-group">
          <label>App Type</label>
          <select v-model="newAppType" class="input">
            <option value="CUSTOM">Custom</option>
            <option value="CHAT">Chat Bot</option>
            <option value="ANALYZER">Analyzer</option>
            <option value="GENERATOR">Generator</option>
            <option value="EMAIL">Email Agent</option>
            <option value="GAME">Game</option>
          </select>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showNewAppModal = false">Cancel</button>
          <button class="btn-primary" @click="createBlankApp" :disabled="!newAppName.trim()">Create</button>
        </div>
      </div>
    </div>

    <!-- Conflict Resolution Modal -->
    <div v-if="showConflictModal" class="modal-overlay" @click.self="showConflictModal = false">
      <div class="modal">
        <h3>Directory Conflict</h3>
        <p>The target path already exists for "{{ pendingTemplate?.name }}". Choose how to proceed:</p>
        <div class="conflict-options">
          <label class="conflict-option" :class="{ selected: conflictAction === 'CONTINUE' }">
            <input type="radio" v-model="conflictAction" value="CONTINUE" />
            <div class="option-content">
              <strong>Continue</strong>
              <span>Keep existing files and append new ones</span>
            </div>
          </label>
          <label class="conflict-option" :class="{ selected: conflictAction === 'OVERWRITE' }">
            <input type="radio" v-model="conflictAction" value="OVERWRITE" />
            <div class="option-content">
              <strong>Overwrite</strong>
              <span>Delete existing directory and start fresh</span>
            </div>
          </label>
          <label class="conflict-option" :class="{ selected: conflictAction === 'CHANGE_PATH' }">
            <input type="radio" v-model="conflictAction" value="CHANGE_PATH" />
            <div class="option-content">
              <strong>Change Path</strong>
              <span>Specify a different target path</span>
            </div>
          </label>
        </div>
        <div v-if="conflictAction === 'CHANGE_PATH'" class="form-group">
          <label>Custom Path</label>
          <input v-model="customTargetPath" type="text" placeholder="/Users/.../Axolotl/my-app/" class="input" />
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showConflictModal = false">Cancel</button>
          <button class="btn-primary" @click="resolveConflict">
            {{ conflictAction === 'CONTINUE' ? 'Continue' : conflictAction === 'OVERWRITE' ? 'Overwrite' : 'Change Path' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 2rem;
  max-width: 1200px;
  margin: 0 auto;
  min-height: 100vh;
  background: var(--bg-primary);
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2.5rem;
}

.dashboard-header h1 {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.subtitle {
  color: var(--text-secondary);
  margin: 0.25rem 0 0 0;
  font-size: 0.95rem;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1.25rem;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s, transform 0.1s;
}

.btn-primary:hover {
  background: var(--accent-light);
  transform: translateY(-1px);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.btn-secondary {
  padding: 0.625rem 1.25rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.9rem;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-secondary:hover {
  background: var(--bg-hover);
}

.icon {
  width: 18px;
  height: 18px;
}

h2 {
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 1rem;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-bottom: 1.5rem;
  padding: 0.625rem 0.875rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  transition: border-color 0.2s;
}

.search-bar:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.search-icon {
  width: 18px;
  height: 18px;
  color: var(--text-muted);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 0.9rem;
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--text-muted);
}

.apps-section,
.templates-section {
  margin-bottom: 2.5rem;
}

.apps-grid,
.templates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.empty-state {
  padding: 3rem;
  text-align: center;
  color: var(--text-muted);
  background: var(--bg-secondary);
  border-radius: 12px;
  border: 2px dashed var(--border-color);
}

/* Modal */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: var(--bg-secondary);
  border-radius: 12px;
  padding: 1.5rem;
  width: 90%;
  max-width: 440px;
  box-shadow: var(--shadow-lg);
}

.modal h3 {
  margin: 0 0 1rem 0;
  font-size: 1.1rem;
  color: var(--text-primary);
}

.form-group {
  margin-bottom: 1rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.375rem;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-secondary);
}

.input {
  width: 100%;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.9rem;
  background: var(--bg-primary);
  color: var(--text-primary);
  box-sizing: border-box;
}

.input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

select.input {
  cursor: pointer;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  margin-top: 1.25rem;
}

/* Conflict modal styles */
.conflict-options {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin: 1rem 0;
}

.conflict-option {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.875rem;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
}

.conflict-option:hover {
  border-color: var(--accent-light);
  background: var(--bg-hover);
}

.conflict-option.selected {
  border-color: var(--accent);
  background: var(--accent-bg);
}

.conflict-option input[type="radio"] {
  margin-top: 0.125rem;
  cursor: pointer;
}

.option-content {
  flex: 1;
}

.option-content strong {
  display: block;
  font-size: 0.95rem;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.option-content span {
  font-size: 0.85rem;
  color: var(--text-secondary);
}
</style>
```
</details>

**Verify:**
```bash
cd frontend && npm run type-check
cd frontend && npm run test:unit -- --run src/views/__tests__/DashboardView.test.ts
```

**Commit:** `feat(dashboard): unify generated apps into main grid, add search, demote templates`

---

### Task 2.2: DashboardView unit tests

**File:** `frontend/src/views/__tests__/DashboardView.test.ts`
**Depends:** 1.1 (AppCard new props shape)

**Implementation:**

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useSchemaStore } from '@/stores/schemaStore'
import DashboardView from '../DashboardView.vue'
import { schemaApi } from '../../services/api'
import { appApi } from '../../services/api'

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

describe('DashboardView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()

    vi.mocked(schemaApi.getSchemas).mockResolvedValue([
      { id: '1', name: 'Chat App', appType: 'CHAT', targetPath: '/Users/evgenijtihomirov/git/Axolotl/Chat App/', nodes: [], edges: [], description: '', version: '1.0' },
      { id: '2', name: 'Custom Tool', appType: 'CUSTOM', nodes: [], edges: [], description: '', version: '1.0' },
      { id: '3', name: 'Game Project', appType: 'GAME', targetPath: '/Users/evgenijtihomirov/git/Axolotl/Game/', nodes: [], edges: [], description: '', version: '1.0' },
    ] as any)

    vi.mocked(appApi.checkTargetPath).mockResolvedValue({ exists: false })
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
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    const cards = wrapper.findAll('.mock-app-card')
    expect(cards.length).toBe(3)
  })

  it('filters apps by search query', async () => {
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
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()

    const input = wrapper.find('.search-input')
    await input.setValue('ZZZZNOTFOUND')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('No apps matching')
    expect(wrapper.text()).toContain('ZZZZNOTFOUND')
  })

  it('shows empty state when no schemas exist', async () => {
    vi.mocked(schemaApi.getSchemas).mockResolvedValue([])
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('No apps yet')
  })

  it('marks generated apps with isGenerated=true and status=active', async () => {
    const wrapper = mount(DashboardView)
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
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    const appSection = wrapper.find('.apps-section')
    const templateSection = wrapper.find('.templates-section')

    // Templates come after apps in DOM order
    const appSectionIndex = appSection.element.compareDocumentPosition(templateSection.element)
    expect(appSectionIndex & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('clears search query back to full list', async () => {
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
```

**Verify:**
```bash
cd frontend && npx vitest run src/views/__tests__/DashboardView.test.ts
```

**Commit:** `test(dashboard): add unit tests for search, merge, and section ordering`

---

## Batch 3: Verification (1 implementer)

### Task 3.1: Manual Verification

**Depends:** 2.1, 2.2

Execute these checks manually:

**1. TypeScript compilation:**
```bash
cd frontend && npx vue-tsc --noEmit
```

**2. Run all unit tests:**
```bash
cd frontend && npm run test:unit -- --run
```

**3. Start the app and verify visually:**
```bash
cd frontend && npm run dev
```

Open http://localhost:5173 and verify:

| # | Check | Expected |
|---|-------|----------|
| a | Search bar appearance | Search icon + text input between header and "My Apps" |
| b | No "Generated Apps" section | No separate section — all apps in one grid |
| c | Generated cards show compact path | Cards with `targetPath` show a folder icon + compact path (e.g. `~/Axolotl/Chat App/`) + green status dot |
| d | Regular cards look unchanged | No path row, no status dot, normal card appearance |
| e | Search filters cards | Type in search bar — grid narrows to matching names |
| f | Empty search result | Shows "No apps matching 'query'" |
| g | Templates below apps | "Start from a Template" section is below the apps grid |
| h | "New App" button works | Click opens modal, creating an app works |
| i | Generated app card click navigates | Click on generated app card → opens schema in studio |
| j | Full path in tooltip | Hover over path row → tooltip shows full absolute path |

**4. Responsive check:**
Resize browser — grid still wraps at breakpoints, search bar responsive.

**5. Dark mode check (if applicable):**
Toggle theme — all colors use CSS variables, should adapt.

**Commit:** `chore(dashboard): verify all dashboard redesign features`

---

## Rollback Plan

If anything goes wrong, revert the two changed files:
```bash
git checkout frontend/src/components/app/AppCard.vue frontend/src/views/DashboardView.vue
rm -f frontend/src/components/app/__tests__/AppCard.test.ts frontend/src/views/__tests__/DashboardView.test.ts
```

## Summary

| Batch | Files | Change Type | Parallel |
|-------|-------|-------------|----------|
| 1 | `AppCard.vue` | Modify (add props + path row) | ✅ Yes |
| 1 | `AppCard.test.ts` | Create (unit tests) | ✅ Yes |
| 2 | `DashboardView.vue` | Modify (search, merge, restructure) | ✅ Yes |
| 2 | `DashboardView.test.ts` | Create (view tests) | ✅ Yes |
| 3 | N/A | Verify | No |
