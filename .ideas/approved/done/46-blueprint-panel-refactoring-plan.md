# Plan 46 — Blueprint Panel Refactoring

**Status:** implementing | **Iterator:** 1
**Design doc:** `thoughts/shared/designs/2026-06-02-blueprint-panel-refactoring-design.md`
**Total phases:** 5 | **Total tasks:** 18

---

## Summary

Decompose `schemaStore.ts` (350 lines), `BlockConfigPanel.vue` (949 lines), `ReviewApprovalDialog.vue` (871 lines), `SchemaPropertiesPanel.vue` (478 lines), `BlueprintView.vue` (408 lines), and `useUndoRedo.ts` (66 lines) into focused composables and a shared Block Registry.

**Key deliverables:**
- 5 Pinia stores/composables instead of 1 god store
- Dynamic BlockConfigPanel driven by Block Registry instead of 7 `v-if` blocks
- `DraftApprovalPanel.vue` extracted from `ReviewApprovalDialog.vue`
- Full undo/redo for config changes (not just position/selection)
- Schema model default pre-fills new nodes only
- No emoji regressions (all SVGs)

---

## Phase 1 — Foundation (5 tasks)

**Goal:** Extract canvas state from `schemaStore.ts` into `useCanvasStore` without changing external behavior.

### Task 1.1 — Create `useCanvasStore.ts` with CRUD + dirty-flag

**Files:**
- `frontend/src/stores/useCanvasStore.ts` (NEW)
- `frontend/src/stores/schemaStore.ts` (MODIFY — strip canvas state)

**What:**
- Copy `schemaStore.ts` lines ~87–212 (schema CRUD) into new store
- Add `markDirty()`, `flushSave()`, `isDirty` ref
- Keep 2s debounce, `flushSave` on execution/navigation/route change
- Reference design doc Section 6 for exact store contract

**Verification:**
- `vue-tsc --noEmit` clean
- `npm run test:unit` all pass
- `mvn compile -q` clean

### Task 1.2 — Strip canvas state from `schemaStore.ts`

**What:**
- Remove `nodes`, `edges`, `currentSchema`, `markDirty`, `flushSave`, `isDirty`, `addNode`, `removeNode`, `updateNode`, `addEdge`, `removeEdge` from schemaStore
- Keep only `pipelineState`, `reviewState`, `executionState`
- `schemaStore` imports `useCanvasStore` for shared operations

**Verification:**
- `vue-tsc --noEmit` clean
- `npm run test:unit` all pass

### Task 1.3 — Wire StudioView to useCanvasStore

**What:**
- Replace `schemaStore.nodes` → `canvasStore.nodes` in `BlueprintView.vue`
- Replace `schemaStore.markDirty` → `canvasStore.markDirty` in all 7 block components
- `startExecution()` still calls `canvasStore.flushSave()` before POST

**Verification:**
- `npm run test:e2e` passes (`studio-persist.spec.ts`)
- Verifies: add node, connect edge, change prompt, save, navigate to Dashboard and back — data persists

### Task 1.4 — Add `onActivated` guard against overwriting unsaved changes

**What:**
- In `useCanvasStore.initialize()`, check `isDirty` before re-fetching schema on `onActivated`
- If dirty, skip re-fetch and keep local state
- If not dirty, re-fetch from backend per current behavior

**Verification:**
- Manual: navigate Studio → change model → Dashboard → back — model is still changed
- Manual: navigate Studio → Dashboard → back (no change) — fresh data from backend

### Task 1.5 — Move model default pre-fill to node creation time

**What:**
- In `useCanvasStore.addNode()` (called from palette drop), read `currentSchema.defaultModel` and assign to `node.data.config.model`
- Remove any `watch`/`computed` that retroactively changes existing nodes when defaultModel changes
- Document in code: "Default applied at creation only. Existing nodes are not retroactively updated."

**Verification:**
- Create node A with default `big-pickle`
- Change schema default to `deepseek-v4-flash-free`
- Create node B — B has new default, A still has `big-pickle`

---

## Phase 2 — Undo/Redo (3 tasks)

**Goal:** Full undo/redo for both structure (node/edge add/delete/move) and config (model/prompt/tool changes).

### Task 2.1 — Rewrite `useUndoRedo.ts` with config capture

**Files:**
- `frontend/src/composables/useUndoRedo.ts` (REWRITE)

**What:**
- Capture full snapshot: `{ nodes: deepClone(nodes.value), edges: deepClone(edges.value) }`
- Selective deep-clone: only `id`, `type`, `position`, `data`, `selected`, `source`, `target` (not VueFlow internal state)
- 50-entry ring buffer, oldest dropped on overflow
- 500ms debounce via `watchEffect` + `setTimeout` (not per-keystroke)
- Expose `undo()`, `redo()`, `canUndo`, `canRedo`, `capture()` (for explicit save points)
- Keyboard handler: `Ctrl+Z` = undo, `Ctrl+Shift+Z` / `Ctrl+Y` = redo

**Verification:**
- `vue-tsc --noEmit` clean
- `npm run test:unit` passes

### Task 2.2 — Integrate undo/redo with canvas

**What:**
- In `BlueprintView.vue`: import `useUndoRedo`, call `capture()` on VueFlow `onNodesChange` and `onEdgesChange` (debounced)
- In `BlockConfigPanel.vue`: call `capture()` on model/prompt/tool changes (debounced)
- Register `Ctrl+Z`/`Ctrl+Shift+Z` keyboard handler in `BlueprintView`

**Verification:**
- Manual: move node → Ctrl+Z → node returns to original position
- Manual: change model dropdown → Ctrl+Z → model reverts
- Manual: change system prompt text → Ctrl+Z → prompt reverts

### Task 2.3 — Visual undo/redo button in toolbar

**What:**
- Add undo/redo SVG icon buttons to `PipelinePanel.vue` toolbar (next to Run/Stop)
- Disabled state when `canUndo`/`canRedo` is false
- Tooltip: "Undo (Ctrl+Z)" / "Redo (Ctrl+Shift+Z)"

**Verification:**
- Buttons visible, disabled when no history
- Button click triggers undo/redo
- `vue-tsc --noEmit` clean

---

## Phase 3 — Block Registry + Dynamic Config Panel (5 tasks)

**Goal:** Replace 7 `v-if` blocks in `BlockConfigPanel.vue` with a registry-driven loop. Highest risk — verify each node type after change.

### Task 3.1 — Create `blockRegistry.ts` and `BlockDefinition` type

**Files:**
- `frontend/src/types/blockRegistry.ts` (NEW)
- `frontend/src/blockRegistry.ts` (NEW)

**What:**
- `BlockDefinition` interface: `type`, `label`, `category`, `color`, `icon` (inline SVG string), `configPanels: ConfigPanelSection[]`, `defaultConfig: Record<string, unknown>`
- Register all 7 types: source, agent, verifier, review, draft, memory, output
- Each configPanels array lists which config sections to show (references section IDs from Task 3.2)

**Verification:**
- `vue-tsc --noEmit` clean

### Task 3.2 — Create config panel section components

**Files:**
- `frontend/src/components/studio/config-sections/` (NEW directory)
  - `ModelSection.vue`
  - `PromptSection.vue`
  - `ToolsSection.vue`
  - `ChecksSection.vue`
  - `SourceTypeSection.vue`
  - `SourceDataSection.vue`
  - `ReviewModeSection.vue`
  - `DraftTypeSection.vue`
  - `OutputToggleSection.vue`
  - `MemoryConfigSection.vue`

**What:**
- Extract each `v-if` block from `BlockConfigPanel.vue` into its own component
- Each section receives `config` prop, emits `update:config`
- Sections are standalone testable components

**Verification:**
- `vue-tsc --noEmit` clean
- `npm run test:unit` passes

### Task 3.3 — Replace `v-if` chain with registry loop in `BlockConfigPanel.vue`

**Files:**
- `frontend/src/components/studio/BlockConfigPanel.vue` (MODIFY)

**What:**
- Remove all 7 `v-if` blocks
- Replace with:
```vue
<template v-for="section in currentBlock.configPanels" :key="section.id">
  <component :is="sectionComponents[section.id]" v-bind="section.props" />
</template>
```
- Map section IDs to components via a simple lookup
- Keep `loadProviders` at module level (30s cache already present)

**Critical — compare rendered HTML:**
- After change, render each of the 7 node types in the config panel
- Count `<input>`, `<select>`, `<textarea>` elements — must match pre-change count

**Verification:**
- `vue-tsc --noEmit` clean
- Manual: all 7 node types render correct config fields
- `npm run test:e2e` passes

### Task 3.4 — Refactor `BlockPalette.vue` to use registry

**Files:**
- `frontend/src/components/studio/BlockPalette.vue` (MODIFY)

**What:**
- Replace hardcoded palette items with `BLOCK_REGISTRY` loop
- Categories from `BlockDefinition.category` (execute, analyze, receive, output)
- Color/icon from registry

**Verification:**
- All 7 types appear in palette
- Drag-to-create still works
- `npm run test:e2e` passes

### Task 3.5 — Update `SchemaPropertiesPanel.vue` to use registry model dropdown

**Files:**
- `frontend/src/components/studio/SchemaPropertiesPanel.vue` (MODIFY)

**What:**
- Replace the duplicate `loadProviders()` call with shared provider cache (from BlockConfigPanel)
- Keep `autoApproveDrafts` toggle (review node check unchanged)
- Remove 400ms debounce — rely on store's 2s debounce

**Verification:**
- `vue-tsc --noEmit` clean
- Manual: model dropdown still shows dynamic options grouped by provider
- `autoApproveDrafts` toggle still visible when schema has review node

---

## Phase 4 — Pipeline Store Extraction (3 tasks)

**Goal:** Extract pipeline state (polling, status, execute/retry/cancel) from `schemaStore.ts`.

### Task 4.1 — Create `usePipelineStore.ts`

**Files:**
- `frontend/src/stores/usePipelineStore.ts` (NEW)
- `frontend/src/stores/schemaStore.ts` (MODIFY — strip pipeline state)

**What:**
- Copy pipeline state from `schemaStore.ts` lines ~239–292: `pipelineStatus`, `lastRunStatus`, `lastRunError`, `isRunning`
- Add `startPolling()`, `stopPolling()`, `execute()`, `retry()`, `cancel()`
- `startPolling` receives `flushSave` as dependency — calls it before every poll cycle
- `stopPolling` called on unmount via `onUnloaded`

**Verification:**
- `vue-tsc --noEmit` clean
- `npm run test:unit` passes

### Task 4.2 — Wire StudioView lifecycle

**Files:**
- `frontend/src/views/StudioView.vue` (MODIFY)

**What:**
- `onActivated`: `pipelineStore.fetchInitialStatus()` (no polling on mount — only after execute)
- `onDeactivated`: `pipelineStore.stopPolling()`
- `startExecution()`: `await canvasStore.flushSave()` then `pipelineStore.execute()`
- Expose `isRunning` to `TimelineView` via `provide/inject` (existing pattern — no change needed)

**Verification:**
- Execute pipeline → polling starts
- Navigate away → polling stops
- Navigate back → no polling (until next execute)

### Task 4.3 — Remove duplicate Pipeline Panel execution buttons

**Files:**
- `frontend/src/components/studio/PipelinePanel.vue` (MODIFY — already read-only per pipeline unification)

**What:**
- PipelinePanel was already stripped of execution controls in pipeline unification (batch 1beb09d)
- Verify: PipelinePanel shows only stage cards, status dots, level arrows
- Verify: All execution controls (Run/Stop, Retry) live only in StudioView toolbar

**Verification:**
- `vue-tsc --noEmit` clean
- Manual: PipelinePanel is read-only topology viewer
- StudioView toolbar has Run/Stop/Retry

---

## Phase 5 — Polish & Cleanup (2 tasks)

**Goal:** Extract `DraftApprovalPanel`, create `useSelectionStore`, final verification.

### Task 5.1 — Extract `DraftApprovalPanel.vue` from `ReviewApprovalDialog.vue`

**Files:**
- `frontend/src/components/studio/DraftApprovalPanel.vue` (NEW)
- `frontend/src/components/studio/ReviewApprovalDialog.vue` (MODIFY)

**What:**
- Extract draft-mode render branch (artifact cards, inline edit, feedback, three-action buttons) into `DraftApprovalPanel.vue`
- Standard mode stays in `ReviewApprovalDialog.vue`
- Both modes communicate via same props/emits interface:
  - Props: `artifacts`, `draftMode`, `isSubmitting`
  - Emits: `approve`, `regenerate`, `reject`

**Verification:**
- `vue-tsc --noEmit` clean
- `npm run test:unit` passes
- Manual: draft mode dialog (from Quick Start) — Approve/Regenerate/Reject all work
- Manual: standard mode dialog (from review node) — Accept/Edit/Suggest/Reject all work

### Task 5.2 — Create `useSelectionStore.ts` and final cleanup

**Files:**
- `frontend/src/stores/useSelectionStore.ts` (NEW)
- `frontend/src/views/StudioView.vue` (MODIFY)

**What:**
- Extract `selectedNode`, `selectedEdge`, `setSelectedNode`, `setSelectedEdge`, `clearSelection` from inline StudioView state
- `onClick` on empty canvas section calls `clearSelection()`
- SchemaPropertiesPanel shows when nothing selected (existing behavior — no change)

**Verification:**
- `vue-tsc --noEmit` clean
- Click node → config panel shows
- Click empty canvas → SchemaPropertiesPanel shows
- `npm run test:e2e` passes

---

## Verification Gate (Final)

Before marking plan 46 as done:

- [ ] `vue-tsc --noEmit` — zero errors
- [ ] `npm run test:unit` — all 125+ pass
- [ ] `npm run test:e2e` — all Playwright tests pass (incl `studio-persist.spec.ts`, `pipeline-review.spec.ts`)
- [ ] `mvn compile -q` — backend clean
- [ ] All 7 node types render correct config panel fields (manual spot check)
- [ ] Undo/redo works for: node position, node add/delete, edge connect/disconnect, model change, prompt change
- [ ] Schema model default pre-fills new nodes only (existing unchanged)
- [ ] `flushSave()` called before every pipeline execution (no stale state)
- [ ] Polling stops on StudioView unmount, starts after `flushSave`
- [ ] Review dialog: standard mode (Accept/Edit/Suggest) and draft mode (Approve & Implement/Regenerate) both work
- [ ] No emoji regressions in studio components (grep check — all SVGs)
- [ ] `mvn test` — all backend tests pass
