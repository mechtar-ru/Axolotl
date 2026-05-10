# Implementation Plan: Wire Frontend Execution State

## Problem Statement

`WorkflowCanvas.vue` and `RightPanel.vue` both inject execution state via `useExecutionState()` in `HomeView.vue`. But `WorkflowCanvas` maintains **7 duplicate local refs** (`isExecuting`, `executionProgress`, `totalNodes`, etc.) and syncs them one-way to the provided state via 7 watchers.

This creates **two sources of truth** — the template and execute functions use local refs, while `RightPanel` and the inject/provide contract expect the shared state. The sync bridge is fragile and adds complexity for zero benefit.

No crash occurs (there's a `if (execState)` guard), but the wiring is misleading: the local refs drift if any path mutates only one side.

**Root cause:** Local state was written before the inject/provide pattern existed, and the sync watchers were added on top instead of removing the old state.

## Goals

1. Delete all 7 local refs + 7 sync watchers from `WorkflowCanvas`
2. Use the injected `ExecutionState` directly everywhere
3. Verify: WebSocket events → execution state refs → RightPanel + canvas bindings

---

## Tasks

### Task 1: Delete local refs and sync watchers from WorkflowCanvas

**File:** `frontend/src/components/canvas/WorkflowCanvas.vue`

- Delete lines 276-289: all local refs (`isExecuting`, `executionProgress`, `totalNodes`, `completedNodes`, `executionLogs`, `executionTotalTokens`, `executionEstimatedCost`, `elapsedSeconds`, `timerInterval`)
- Delete lines 291-302: entire `if (execState)` sync watcher block
- Keep `const execState = useExecutionState()` — we still need the injection

**Verification:** `grep` confirms no `ref(` for the deleted variables remain outside of node components.

### Task 2: Replace local ref references with injected state

**File:** `frontend/src/components/canvas/WorkflowCanvas.vue`

Search for every usage of the deleted local refs and redirect to `execState`. Grep targets (non-exhaustive — run a full search):

| Old local ref | Replace with | Notes |
|---|---|---|
| `isExecuting.value` | `execState.isExecuting.value` | Safe: execState is non-null at runtime since HomeView provides |
| `executionProgress.value` | `execState.progress.value` | |
| `executionLogs.value` | `execState.logs.value` | |
| `totalNodes.value` | `execState.totalNodes.value` | |
| `completedNodes.value` | `execState.completedNodes.value` | |
| `executionTotalTokens.value` | `execState.totalTokens.value` | |
| `executionEstimatedCost.value` | `execState.estimatedCost.value` | |
| `elapsedSeconds.value` | `execState.elapsedSeconds.value` | |
| `timerInterval` | No replacement — move to `execState` or local let | Timer is a mutable interval ID, not reactive state; keep as `let` in the function scope |
| `executionMode` | Keep as-is | Not part of ExecutionState interface |

Template bindings to update (grep for each):
- `:disabled="isExecuting"` → `:disabled="execState.isExecuting.value"` (or bind directly)
- Any `v-if`/`v-for` referencing the deleted refs

**Important:** Add `!` non-null assertion or optional chaining where TypeScript complains since the template/methods execute after injection is guaranteed.

### Task 3: Verify full data flow compiles

```bash
cd frontend && npm run type-check
```

Fix any type errors:
- Expected: `execState` might be `null` according to the composable return type
- Fix either: add `const execState = useExecutionState()!` or wrap access in a non-null helper
- Consider updating `useExecutionState()` signature if it's always used in a provided context

### Task 4: Verify WebSocket → ExecutionPanel flow

**Files to audit (read-only):**
- `frontend/src/composables/useWebSocket.ts` — confirm message handlers write to `execState` refs (progress, logs, tokens)
- `frontend/src/components/panels/RightPanel.vue` — confirm it uses `useExecutionState()` and passes values to `ExecutionPanel`
- `frontend/src/components/execution/ExecutionPanel.vue` — confirm it binds to the same ref properties

**Verification checklist in browser:**
- [ ] Start execution → RightPanel auto-opens with exec tab
- [ ] Progress bar advances as nodes complete
- [ ] Logs stream into ExecutionPanel logs tab
- [ ] `totalTokens` / `estimatedCost` display on completion
- [ ] `elapsedSeconds` timer runs
- [ ] Canvas button shows correct execution state
- [ ] Stop button triggers `axolotl:stop-execution` event

---

## Files Affected (total: 1)

| File | Change |
|---|---|
| `frontend/src/components/canvas/WorkflowCanvas.vue` | Delete ~25 lines, update ~10 references |
