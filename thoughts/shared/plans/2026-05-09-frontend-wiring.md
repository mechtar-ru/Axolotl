# Implementation Plan: Wire Frontend Execution State

## Problem Statement

`WorkflowCanvas.vue` and `RightPanel.vue` share execution state via `provideExecutionState`/`useExecutionState` in `HomeView.vue`. However, the wiring is broken:

- `HomeView` creates `executionState` and provides it via `provideExecutionState(executionState)`
- `RightPanel` injects it via `useExecutionState()` ✅
- `WorkflowCanvas` injects it via `useExecutionState()` ✅ — but it syncs to its own local `isExecuting` ref, not the provided state

**The gap:** `WorkflowCanvas` never receives the `executionState` object as a prop. It only gets `schema` as a prop. So its `watch(isExecuting, v => execState.isExecuting.value = v)` writes to `.value` (undefined) instead of the parent's refs.

## Goals

1. Pass `executionState` from `HomeView` → `WorkflowCanvas` as a prop
2. Simplify `WorkflowCanvas` — use provided state directly, remove local refs
3. Verify all state syncs work: WebSocket updates → logs, progress, tokens → RightPanel

---

## Tasks

### Task 1: Add `executionState` prop to `WorkflowCanvas`

**File:** `frontend/src/components/canvas/WorkflowCanvas.vue`

- Add `executionState` prop (type `ExecutionState`)
- Import `useExecutionState` from `../../composables/useExecutionState`
- If prop is passed, use it instead of local `isExecuting`
- Keep local ref as fallback for existing behavior (backward compat)

### Task 2: Pass `executionState` prop from `HomeView`

**File:** `frontend/src/views/HomeView.vue`

- Add `:execution-state="executionState"` to `<WorkflowCanvas>` component usage
- This is the single line that fixes the broken wiring

### Task 3: Verify WebSocket → ExecutionPanel data flow

**Files:**
- `frontend/src/composables/useWebSocket.ts` — confirm it writes to refs
- `frontend/src/components/panels/RightPanel.vue` — confirm it passes to ExecutionPanel
- `frontend/src/components/execution/ExecutionPanel.vue` — confirm it receives all props

**Verification checklist:**
- [ ] `progress` updates show in ExecutionPanel progress bar
- [ ] `logs` stream into ExecutionPanel logs tab
- [ ] `totalTokens` / `estimatedCost` display
- [ ] `elapsedSeconds` timer runs
- [ ] Stop button triggers `axolotl:stop-execution` event

### Task 4: Test in browser

Run both frontend instances and verify:
- Start execution → RightPanel auto-opens with exec tab
- Logs stream in real-time
- Stop button works
- Completion shows metrics
