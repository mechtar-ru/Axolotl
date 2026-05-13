# Axolotl Studio — Implementation Plan

**Date:** 2026-05-11
**Based on:** `thoughts/shared/designs/2026-05-11-axolotl-studio-design.md`
**Total estimate:** 6 waves, ~40 micro-tasks

---

## Wave 0: Backend Prep (no frontend changes)

Dependencies: none
Files changed: 6 backend files + 1 new

### Task 0.1 — Add `AppModel` with `appType`

**Create:** `backend/src/main/java/com/agent/orchestrator/model/AppModel.java`
- Extends WorkflowSchema (or wraps it)
- Adds `appType` enum: `CHAT | ANALYZER | GENERATOR | EMAIL | CUSTOM`
- Default: `CUSTOM`

**Modify:** `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java`
- Add `appType` field (nullable, default null → CUSTOM for backward compat)

### Task 0.2 — Add `/api/app` endpoints

**Create/Modify:** `backend/src/main/java/com/agent/orchestrator/controller/AppController.java`
- `GET /api/app` — list apps (same as schemas)
- `POST /api/app` — create app (same as create schema, adds appType)
- `GET /api/app/{id}` — get app
- `PUT /api/app/{id}` — update app
- `DELETE /api/app/{id}` — delete app
- `GET /api/app/templates` — return template list with pre-built node graphs

Existing `/api/schemas` endpoints remain for backward compatibility: AppController delegates to SchemaService.

### Task 0.3 — Add structured WebSocket events

**Modify:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
- Add `step` event emission alongside existing `progress` events
- Add `live_update` event emission alongside existing `result` events
- Step schema: `{ type: "step", stepIndex, blockId, blockType, label, status, details, duration }`
- Live update schema: `{ type: "live_update", appType, payload: { messages? | files? | ... } }`

**Modify:** `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java`
- Pass new event types through the WebSocket pipeline

### Task 0.4 — Remove execution mode logic

**Modify:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
- Remove EXECUTE/ANALYZE/DRY_RUN branching in `execute()`
- Always execute in full mode (old EXECUTE)
- Remove `executionMode` parameter from execute method signature

**Modify:** `backend/src/main/java/com/agent/orchestrator/model/Node.java`
- Remove `executionMode` field if present
- Keep executionStatus (IDLE/RUNNING/COMPLETED/FAILED/BLOCKED)

### Task 0.5 — Add light theme CSS variables

**Modify:** `frontend/src/App.vue`
- Add `[data-theme="light"]` CSS selectors alongside existing dark theme
- Use `var(--bg-primary)`, `var(--bg-secondary)`, `var(--text-primary)`, etc.
- Light values:
  - `--bg-primary`: `#f8f9fa`
  - `--bg-secondary`: `#ffffff`
  - `--bg-card`: `#ffffff`
  - `--text-primary`: `#1a1a2e`
  - `--text-secondary`: `#6b7280`
  - `--border-color`: `#e5e7eb`

**Modify:** `frontend/src/stores/settingsStore.ts` (new or add to existing)
- Add `theme` state: `'light' | 'dark' | 'system'`
- Default: `'system'`
- Apply `data-theme` attribute to `<html>` based on value + `prefers-color-scheme`

### Task 0.6 — Migration helper

**Create:** `scripts/migrate-schemas-to-apps.py`
- Reads all schemas from SQLite
- Adds `appType = 'CUSTOM'` to all existing schemas
- Preserves all existing nodes/edges structure
- Dry-run mode

---

## Wave 1: Dashboard (new first screen)

Dependencies: Wave 0
Files changed: 6 files

### Task 1.1 — Create DashboardView.vue

**Create:** `frontend/src/views/DashboardView.vue`
- Welcome header with username
- App cards grid (3 columns, responsive)
  - Each card: name, appType icon, status badge (draft/ready/live), last run timestamp
  - Click → navigate to `/app/:id`
- "New App" big CTA button
- Template section: 6 template cards (Chat Bot, Doc Analyzer, Content Gen, Email Agent, Data Extractor, Blank)
- Template click → API call to create app from template → navigate to `/app/:id`

**Data:** Uses `schemaStore.schemas` (already loaded in App.vue mount)

### Task 1.2 — Create AppCard component

**Create:** `frontend/src/components/app/AppCard.vue`
- Props: `app: WorkflowSchema`, `onClick: () => void`
- Shows: app name, appType icon (💬📄✉️🛠️), status badge, "Run" button if last status was running
- Hover: subtle lift shadow
- Context menu (right-click): Rename, Duplicate, Delete

### Task 1.3 — Create TemplateCard component

**Create:** `frontend/src/components/app/TemplateCard.vue`
- Props: `template: AppTemplate`, `onSelect: () => void`
- Icon, name, short description, complexity indicator (1-3 blocks)
- Click → creates app + navigates

### Task 1.4 — Update router

**Modify:** `frontend/src/router/index.ts`
- `/` → DashboardView
- `/app/:id` → StudioView (new)
- Keep `/login`, `/settings`, `/about` as-is
- Remove old `/schema/:id` route (redirect to `/app/:id`)

### Task 1.5 — Update App.vue

**Modify:** `frontend/src/App.vue`
- Remove `schemaStore.loadSchemas()` on mount (Dashboard loads it)
- Add theme initialization from settingsStore
- Keep `<router-view />` + `<ToastContainer />`

### Task 1.6 — Template definitions

**Create:** `frontend/src/templates/index.ts`
- Each template = name, description, icon, appType, defaultNodes, defaultEdges
- 6 templates exported as array

---

## Wave 2: Studio — Blueprint Mode

Dependencies: Wave 1
Files changed: 12 files

### Task 2.1 — Create StudioView.vue

**Create:** `frontend/src/views/StudioView.vue`
- Top bar: app name (editable), mode tabs (Blueprint | Live | Timeline), Run/Stop button, user avatar
- Conditional rendering of BlueprintView / LiveView / TimelineView
- Provides `appState` (current app, execution state) via provide/inject
- Handles Run/Stop via WebSocket composable

**Test:** StudioView.test.ts — renders correct mode, Run button triggers execution

### Task 2.2 — Create StudioTopBar component

**Create:** `frontend/src/components/studio/StudioTopBar.vue`
- App name (inline edit, v-model)
- Mode tabs: three buttons, active state
- Run button: green when idle → red pulsing when running → "■ Stop"
- User menu: avatar → dropdown (Settings, Logout)

### Task 2.3 — Create BlueprintView.vue

**Create:** `frontend/src/components/studio/BlueprintView.vue`
- Wraps the canvas (VueFlow)
- Block palette on left or bottom: 4 draggable block types
- Canvas fills remaining space
- Configuration slide-over on block click (replaces RightPanel)

**Test:** BlueprintView.test.ts — blocks render, palette items drag onto canvas

### Task 2.4 — Create 4 block components

**Create:** `frontend/src/components/blocks/BlockBase.vue`
- Base component: position, selection state, status overlay, connection handles (top center in, bottom center out)
- No emoji — clean icon per type

**Create:** `frontend/src/components/blocks/ReceiveBlock.vue`
- Green `#4caf50`, rounded rectangle
- Icon: arrow-in
- Config: input type (chat/file/webhook/schedule)

**Create:** `frontend/src/components/blocks/ThinkBlock.vue`
- Blue `#2196f3`, hexagon
- Icon: brain/sparkle
- Config: NL description, model selector, tools toggle

**Create:** `frontend/src/components/blocks/RememberBlock.vue`
- Purple `#9c27b0`, cylinder
- Icon: database/book
- Config: memory type (chat-history/knowledge/facts)

**Create:** `frontend/src/components/blocks/ActBlock.vue`
- Orange `#ff9800`, rectangle with notch
- Icon: arrow-out
- Config: action type (reply/save/call-api/send-email)

**Test:** each block renders with correct color, shape, config panel opens on click

### Task 2.5 — Create BlockConfigPanel component

**Create:** `frontend/src/components/studio/BlockConfigPanel.vue`
- Slide-over from right side (not modal, not tab panel)
- Context-sensitive: shows different fields based on block type
- Primary field: "What should this block do?" — large textarea
- Secondary: model selector, tools, etc.
- Save on blur/auto-save

### Task 2.6 — Create BlockPalette component

**Create:** `frontend/src/components/studio/BlockPalette.vue`
- Horizontal strip at bottom of canvas (or vertical on left)
- 4 draggable items: Receive, Think, Remember, Act
- Drag + drop onto canvas creates new block
- Each shows: colored dot + label

### Task 2.7 — Remove old components (part 1)

**Delete:**
- `frontend/src/views/HomeView.vue` (replaced by DashboardView + StudioView)
- `frontend/src/components/canvas/WorkflowCanvas.vue` (replaced by BlueprintView)
- `frontend/src/components/panels/RightPanel.vue` (replaced by BlockConfigPanel)
- `frontend/src/components/panels/PlanPanel.vue` (removed)
- `frontend/src/components/panels/MemoryPanel.vue` (removed)
- `frontend/src/components/ui/OnboardingModal.vue` (removed)
- `frontend/src/components/ui/CoachmarkOverlay.vue` (removed)
- `frontend/src/components/ui/SchemaBuilderModal.vue` (removed)
- `frontend/src/components/ui/CommandPalette.vue` (removed)

**Note:** Old node components (nodes/) kept for backward compat — existing schemas in "advanced mode" still use them

### Task 2.8 — Update schemaStore for new data model

**Modify:** `frontend/src/stores/schemaStore.ts`
- Rename methods or add aliases: `createApp()`, `loadApps()`, etc.
- Add `appType` to App model
- Add `updateBlockConfig(blockId, config)` — updates NL config on block data
- Auto-save on every change (debounced 500ms)

**Test:** schemaStore.test.ts — CRUD operations work with new App model

### Task 2.9 — Update panelStore

**Modify:** `frontend/src/stores/panelStore.ts`
- Remove old PanelTab types (exec/plan/memory/history/templates)
- Keep only: `visible` (for BlockConfigPanel), `activeBlockId`
- Rename or simplify

---

## Wave 3: Studio — Live Mode

Dependencies: Wave 2
Files changed: 6 files

### Task 3.1 — Create LiveView.vue

**Create:** `frontend/src/components/studio/LiveView.vue`
- Reads `appType` from current app
- Renders appropriate AppUI component (or GenericAppUI as fallback)
- Canvas dims to 50% opacity in background
- Blocks show execution status as colored borders
- "Back to Blueprint" subtle link

### Task 3.2 — Create ChatAppUI.vue

**Create:** `frontend/src/components/live/ChatAppUI.vue`
- Chat message list (user left, assistant right)
- Streaming: text appears character by character
- Input field at bottom with send button
- Keyboard: Enter to send, Shift+Enter for newline
- Auto-scroll to bottom on new messages

### Task 3.3 — Create DocAnalyzerAppUI.vue

**Create:** `frontend/src/components/live/DocAnalyzerAppUI.vue`
- File dropzone (drag & drop + click to browse)
- Progress bar during processing
- Results area: text preview + download button
- Supported file types indicator

### Task 3.4 — Create GenericAppUI.vue

**Create:** `frontend/src/components/live/GenericAppUI.vue`
- Simple I/O console
- Left: input panel (form generated from Receive block config)
- Right: output panel showing latest Act block result
- Fallback when appType is CUSTOM or unknown

### Task 3.5 — Wire WebSocket live updates

**Modify:** `frontend/src/composables/useWebSocket.ts`
- Add handler for `live_update` events
- Pass structured data (messages, files, etc.) to LiveView
- Keep existing handlers for backward compat

### Task 3.6 — Wire execution state for Live

**Modify:** `frontend/src/composables/useExecutionState.ts`
- Add `liveData` ref (structured data from `live_update` events)
- Add `stepEvents` ref (array of step events for Timeline)
- Provide/inject: same pattern as before but with new data shapes

---

## Wave 4: Studio — Timeline Mode

Dependencies: Wave 2
Files changed: 3 files

### Task 4.1 — Create TimelineView.vue

**Create:** `frontend/src/components/studio/TimelineView.vue`
- Scrollable vertical timeline
- Each entry: timestamp, block icon, label, status (running/done/error), duration
- Color-coded by block type (green/blue/purple/orange)
- Click entry → emit `select-block` → switch to Blueprint + select block
- Empty state: "Run your app to see what happened"

### Task 4.2 — Create TimelineEntry component

**Create:** `frontend/src/components/studio/TimelineEntry.vue`
- Props: `event: StepEvent`, `isSelected: boolean`, `onClick`
- Left: vertical line + dot (colored by status)
- Right: icon + label + duration + details expandable

### Task 4.3 — Add Timeline footer bar

**Create:** `frontend/src/components/studio/TimelineBar.vue`
- Compact bar at bottom of screen (when not in Timeline mode)
- Shows latest event snippet + count of total events
- Click → switches to Timeline tab
- Auto-dismisses after 30s of inactivity

---

## Wave 5: Settings + Theme

Dependencies: Wave 0 (light theme CSS)
Files changed: 3 files

### Task 5.1 — Update SettingsView.vue

**Modify:** `frontend/src/views/SettingsView.vue`
- Add theme selector: Light / Dark / System
- Remove or hide Plan/MCP settings (moved to advanced)
- Keep LLM provider settings as-is
- Keep API key management as-is

### Task 5.2 — Theme toggle component

**Create:** `frontend/src/components/ui/ThemeToggle.vue`
- Sun/moon icon toggle
- Three states: light, dark, system
- Saves preference to localStorage + applies via data-theme attribute

### Task 5.3 — Responsive light theme polish

**Modify:** `frontend/src/App.vue` (light theme CSS)
- Audit all components for light theme compatibility
- Fix contrast ratios, borders, shadows
- Test with common screens: Dashboard, Blueprint, Live, Settings

---

## Wave 6: Cleanup + Migration

Dependencies: Wave 1-5
Files changed: various

### Task 6.1 — Remove deprecated stores

**Delete:**
- `frontend/src/stores/counter.ts` (unused boilerplate)

**Modify:** `frontend/src/stores/panelStore.ts` — simplify to only BlockConfigPanel state

### Task 6.2 — Remove unused composables

- Audit `useElectron.ts` — keep if Electron still supported
- Remove or update `useExecutionState.ts` to new event model

### Task 6.3 — E2E test: full app creation flow

**Create:** `e2e/studio-flow.spec.ts`
- Login → Dashboard loads
- Click "New App" → navigate to Studio
- Blueprint shows empty canvas + block palette
- Drag blocks onto canvas, connect them
- Click block → config panel opens
- Press Run → switch to Live
- Interact with app in Live
- Switch to Timeline → see execution trace
- Click trace entry → back to Blueprint, block selected

### Task 6.4 — E2E test: existing schema migration

**Create:** `e2e/migration.spec.ts`
- Load existing saved schema (pre-redesign)
- Verify it opens in Studio (CUSTOM appType, full node graph visible)
- Verify all nodes/edges preserved
- Verify execution still works

---

## Execution Order Summary

```
Wave 0: Backend Prep (Task 0.1 → 0.2 → 0.3 → 0.4 → 0.5 → 0.6)
                             ↓
Wave 1: Dashboard (Task 1.1|1.2|1.3|1.6 → 1.4|1.5)
                             ↓
Wave 2: Blueprint (Task 2.1|2.2|2.3|2.6 → 2.4 → 2.5 → 2.7|2.8|2.9)
                             ↓
Wave 3: Live (Task 3.1 → 3.2|3.3|3.4 → 3.5|3.6)
                             ↓
Wave 4: Timeline (Task 4.1 → 4.2 → 4.3)
                             ↓
Wave 5: Settings (Task 5.1|5.2 → 5.3)
                             ↓
Wave 6: Cleanup (Task 6.1|6.2 → 6.3|6.4)
```

Within each wave, tasks without arrows can run in parallel (same batch).
