---
session: ses_1ec2
updated: 2026-05-10T21:49:23.173Z
---

# Session Summary

## Goal
Analyze the complete Axolotl frontend app structure covering routing, HomeView layout, modals/overlays, RightPanel tabs, Pinia stores, WorkflowCanvas toolbar actions, and node types/rendering.

## Constraints & Preferences
- Use exact file paths from `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/`
- Do NOT write code — only analyze and describe
- Preserve exact function names, component names, type names

## Progress
### Done
- [x] **Routing** (`frontend/src/router/index.ts`): 5 routes mapped — login (lazy), home (eager), schema/:id (eager, loads HomeView), settings (lazy), about (lazy). Auth guard checks `axolotl_token` in localStorage. Guard redirects to `/login` if missing on `requiresAuth` routes; redirects to `/home` if token present on login page.
- [x] **App.vue** (`frontend/src/App.vue`): Root template is `<router-view />` + `<ToastContainer />`. On mount calls `schemaStore.loadSchemas()`. Defines global CSS for sidebar, canvas-container, toolbar, placeholder, node, dialog, mention, etc. — all as global styles (no scoped).
- [x] **HomeView.vue structure** (`frontend/src/views/HomeView.vue`): Full-page split into 4 zones:
  - **Sidebar**: Brand zone (logo + "Axolotl"), Schemas zone (schema list with `+` create, import/export buttons), User zone (user info, settings gear, logout)
  - **Canvas area**: Renders `WorkflowCanvas` component when `currentSchema` exists; otherwise shows a placeholder CTA card
  - **Right panel**: `RightPanel` component rendered conditionally based on `panelStore.visible`
  - **Modals/overlays**: OnboardingModal, SchemaBuilderModal, CoachmarkOverlay, ShortcutsOverlay, CommandPalette, TemplateGallery, AppModal (for delete confirmation)
- [x] **Modals/Overlays**:
  - **OnboardingModal** (`frontend/src/components/ui/OnboardingModal.vue`): Multi-step wizard (step 1: choose LLM provider, step 2: pick starting point (scratch/template), step 3: tour prompt). Manages `window.__onboardingComplete__` flag.
  - **CoachmarkOverlay** (`frontend/src/components/ui/CoachmarkOverlay.vue`): Step-by-step tour with spotlight and tooltip card. Steps defined in component: Canvas, Nodes, Connections, Run, Panel. Has Back/Next/Skip buttons.
  - **SchemaBuilderModal** (`frontend/src/components/ui/SchemaBuilderModal.vue`): AI-powered schema generation. States: input (textarea for prompt + model selector), generating (spinner + context log), result (preview tree + accept/discard). Calls `POST /api/schema/generate` via `schemaApi`.
  - **ShortcutsOverlay** (`frontend/src/components/ui/ShortcutsOverlay.vue`): Wraps `AppModal` with keyboard shortcut groups — General (⌘K, ⌘S, ⌘N, ?, Esc), Editor (⌘F, ⌘E, Ctrl+G, Delete), Execution (⌘Enter, ⌘.), Electron (⌘W, ⌘R, ⌘I, F11).
  - **AppModal** (`frontend/src/components/ui/AppModal.vue`): Generic modal wrapper with Transition, overlay click-to-close, Escape key listener, focus trap to first input/button, optional `large` prop.
  - **CommandPalette** (`frontend/src/components/ui/CommandPalette.vue`): Not read in detail — appears to be a ⌘K command palette.
  - **TemplateGallery** (`frontend/src/components/ui/TemplateGallery.vue`): Not read in detail — appears in onboarding step 2.
- [x] **RightPanel** (`frontend/src/components/panels/RightPanel.vue`): Visible when `panelStore.visible` is true. Width controlled by `panelStore.width` (default 380, min 0, max 600). Has a drag resize handle. Tabs: `exec` (ExecutionPanel), `plan` (PlanPanel), `memory` (MemoryPanel), `history` (ExecutionHistory), `templates` (TemplatePanel). Emits: `stop-execution`, `highlight-node`, `highlight-wave`, `add-tool-call`, `execution-pin`.
- [x] **Pinia Stores**:
  - **schemaStore** (`frontend/src/stores/schemaStore.ts`): State — `schemas`, `currentSchema`, `loading`, `executionMode`. Actions — `loadSchemas()`, `createSchema(name)`, `updateSchema(id, data)`, `deleteSchema(id)`, `selectSchema(schema)`, `setExecutionMode(mode)`. Uses `schemaApi` and `settingsApi`.
  - **panelStore** (`frontend/src/stores/panelStore.ts`): State — `visible`, `activeTab` (type `PanelTab` = 'exec' | 'plan' | 'memory' | 'history' | 'templates'), `width`. Actions — `open(tab?)`, `close()`, `toggle(tab?)`, `setWidth(w)`.
  - **authStore** (`frontend/src/stores/authStore.ts`): State — `token`, `username`, `role` (all synced to localStorage). Computed — `isAuthenticated`, `isAdmin`. Actions — `login(user, pass)`, `register(user, pass)`, `logout()`, `checkBackendHealth()`, `checkAuth()`.
  - **counter** (`frontend/src/stores/counter.ts`): Boilerplate — `count`, `doubleCount`, `increment()`. Likely unused.
- [x] **WorkflowCanvas toolbar** (`frontend/src/components/canvas/WorkflowCanvas.vue`): Top bar inside canvas with:
  - Schema name (clickable text or rename input with `v-model`)
  - Execution mode dropdown: `EXECUTE` (▶), `ANALYZE` (🔍), `DRY_RUN` (🎭)
  - Action buttons: `▶ Run` (or Analyze/Dry Run), `💾 Save`, `📊 Export`, `🗑 Delete schema`
  - Provider warning banner when no LLM provider configured (links to Settings)
- [x] **Node types and rendering** — from `frontend/src/types/index.ts` type `FlowNode.type` has union of 16 types: `source | agent | output | condition | transform | loop | group | comment | memory | guardrail | human | fallback | webhook | schedule | subagent | schemabuilder`. Additionally, actual `.vue` node components found: AgentNode, SourceNode, OutputNode, ConditionNode, TransformNode, LoopNode, GroupNode, CommentNode, MemoryNode, GuardrailNode, HumanNode, FallbackNode, SubagentNode, CommandNode, FileWriteNode, SchemaBuilderNode. Nodes are registered in WorkflowCanvas via `nodeTypes` object mapping to `@vue-flow/core`. Common pattern: each node uses Vue Flow's `<Handle>` component (target top, source bottom), has delete button when selected, editable name (dblclick), expand/collapse, and execution status classes (`node-running`, `node-completed`, `node-failed`). Shared CSS in `frontend/src/components/nodes/node-base.css`.

### In Progress
- [ ] (none — analysis complete)

### Blocked
- (none)

## Key Decisions
- **Auth via localStorage token**: `authStore` and router guard both read `axolotl_token` — simple token-based auth, no refresh token mechanism observed.
- **HomeView is the schema editor**: Both `/` and `/schema/:id` routes load HomeView, with `:id` allowing direct deep-linking to a schema.
- **Global (unscoped) CSS**: `App.vue` and `HomeView.vue` use global `<style>` blocks with class names like `.sidebar`, `.canvas-container`, `.node-base` — no scoped or CSS modules for layout.
- **Vue Flow as canvas engine**: `WorkflowCanvas` uses `@vue-flow/core` with `v-model` on elements, custom `node-types` and `edge-types` registration.
- **Panel system driven by `panelStore`**: RightPanel visibility and active tab controlled centrally, not per-view.

## Next Steps
(No active work — this was a read-only analysis.)

## Critical Context
- **Router file**: `frontend/src/router/index.ts` — 5 routes with `createWebHistory`, auth guard reads `localStorage.getItem('axolotl_token')`
- **App.vue**: `frontend/src/App.vue` — `<router-view />` + `<ToastContainer />`, on mount calls `schemaStore.loadSchemas()`
- **HomeView.vue**: `frontend/src/views/HomeView.vue` — full SPA layout with sidebar (brand + schemas list + user zone), WorkflowCanvas, RightPanel, and 7+ modals/overlays
- **RightPanel tabs**: `frontend/src/components/panels/RightPanel.vue` — 5 tabs from `PanelTab` type: `exec` | `plan` | `memory` | `history` | `templates`
- **Stores**:
  - `frontend/src/stores/schemaStore.ts` — `loadSchemas()`, `createSchema(name)`, `updateSchema(id)`, `deleteSchema(id)`, `selectSchema(schema)`, `setExecutionMode(mode)`
  - `frontend/src/stores/panelStore.ts` — `open(tab?)`, `close()`, `toggle(tab?)`, `setWidth(w)`
  - `frontend/src/stores/authStore.ts` — `login(user, pass)`, `register(user, pass)`, `logout()`, `checkBackendHealth()`, `checkAuth()`
- **WorkflowCanvas toolbar** (`frontend/src/components/canvas/WorkflowCanvas.vue`): schema rename input, mode selector (EXECUTE/ANALYZE/DRY_RUN), Run/Save/Export/Delete buttons, provider warning banner
- **Node types (`FlowNode.type`)** from `frontend/src/types/index.ts`: 16 types — `source | agent | output | condition | transform | loop | group | comment | memory | guardrail | human | fallback | webhook | schedule | subagent | schemabuilder`
- **Node components** in `frontend/src/components/nodes/` — each has `.vue` file matching type name (AgentNode.vue, SourceNode.vue, etc.) plus additional `CommandNode.vue`, `FileWriteNode.vue`, `SchemaBuilderNode.vue`. Shared base styles at `frontend/src/components/nodes/node-base.css`. All use Vue Flow's `<Handle>` and follow same template pattern (delete btn, editable name, expand/collapse, execution status classes).
- **Modals/Overlays** in `frontend/src/components/ui/`:
  - `AppModal.vue` — generic wrapper with v-model, title, large prop, focus trap
  - `OnboardingModal.vue` — 3-step wizard (provider → template → tour), manages `window.__onboardingComplete__`
  - `CoachmarkOverlay.vue` — spotlight + tooltip card, 5 steps (Canvas/Nodes/Connections/Run/Panel)
  - `SchemaBuilderModal.vue` — 3 states (input/generating/result), calls `POST /api/schema/generate`
  - `ShortcutsOverlay.vue` — wraps AppModal, documents 10+ keyboard shortcuts in 4 groups
  - `CommandPalette.vue` — ⌘K command palette
  - `TemplateGallery.vue` — template picker

## File Operations
### Read
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/App.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/canvas/WorkflowCanvas.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/execution`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/AgentNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/nodes/SourceNode.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/panels/RightPanel.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/AppModal.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/CoachmarkOverlay.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/OnboardingModal.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/SchemaBuilderModal.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/components/ui/ShortcutsOverlay.vue`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/router/index.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores/authStore.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores/counter.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores/panelStore.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/stores/schemaStore.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/types/index.ts`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/src/views/HomeView.vue`

### Modified
- (none)
