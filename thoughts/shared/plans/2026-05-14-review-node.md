# Review Node Implementation Plan

## Batch 1 — Foundation (parallel)

### Task 1: Create ReviewBlock.vue
**File:** `frontend/src/components/blocks/ReviewBlock.vue`
**Type:** CREATE
**Dependencies:** None
**Description:** New VueFlow node component, amber `#f59e0b`, clipboard icon (two paths: clipboard outline + checkmark inside). Extends BlockBase.vue like ThinkBlock.vue. Type string `"review"`. Accepts `iteration` prop for badge display.
**Verification:** `vue-tsc --noEmit` passes, component renders in isolation test.

### Task 2: Add palette entry
**File:** `frontend/src/components/studio/BlockPalette.vue`
**Type:** MODIFY
**Dependencies:** None
**Description:** Add `{type:'review', label:'Review', color:'#f59e0b', icon:'M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 14l2 2 4-4'}` entry after `source`, before `agent`.
**Verification:** `vue-tsc --noEmit` passes, palette renders 5 entries.

### Task 3: Add review to SchemaExporter
**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaExporter.java`
**Type:** MODIFY
**Dependencies:** None
**Description:** Add `case "review" -> "[/" + label + "/]"` (hexagon shape) in `getNodeShape()` switch.
**Verification:** `mvn compile -q` passes.

## Batch 2 — Core Logic (parallel)

### Task 4: Register review node in BlueprintView
**File:** `frontend/src/components/studio/BlueprintView.vue`
**Type:** MODIFY
**Dependencies:** Task 1
**Description:** Import ReviewBlock, add `review: ReviewBlock` to `nodeTypes`, add to compatible block types.
**Verification:** `vue-tsc --noEmit` passes.

### Task 5: Add review config panel
**File:** `frontend/src/components/studio/BlockConfigPanel.vue`
**Type:** MODIFY
**Dependencies:** None
**Description:** When `blockType === 'review'`, show check toggles (premortem ON, prism OFF, postmortem OFF), mode select (pass-through/rewrite), max iterations input (default 3). Hide agentType and tool config.
**Verification:** `vue-tsc --noEmit` passes.

### Task 6: Add review to timeline
**File:** `frontend/src/components/studio/TimelineView.vue`
**Type:** MODIFY
**Dependencies:** None
**Description:** Add `review: '#f59e0b'` to `blockColors`. Show findings count and iteration badge.
**Verification:** `vue-tsc --noEmit` passes.

### Task 7: Add AWAITING_APPROVAL status
**File:** `backend/src/main/java/com/agent/orchestrator/model/Node.java`
**Type:** MODIFY
**Dependencies:** None
**Description:** Add `AWAITING_APPROVAL` to `NodeStatus` enum. Search for the enum definition (may be inside Node class or standalone).
**Verification:** `mvn compile -q` passes.

### Task 8: Add review branch to NodeExecutor
**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java`
**Type:** MODIFY
**Dependencies:** Task 7
**Description:** Add `"review"` branch after `"verifier"`. Add `executeReviewNode()` method: collects upstream plan, runs checks based on config, builds structured prompt, calls LLM, handles approve/reject flow. Emits WS event for approval.
**Verification:** `mvn compile -q` passes.

### Task 9: Add approve/reject to SchemaService
**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
**Type:** MODIFY
**Dependencies:** Task 7
**Description:** Add `approveNode()` and `rejectNode()` methods that transition AWAITING_APPROVAL → RUNNING or AWAITING_APPROVAL → FAILED after max iterations. Add REST endpoints or reuse existing execution controller.
**Verification:** `mvn compile -q` passes.

## Batch 3 — UI Approval Dialog (parallel)

### Task 10: Create ReviewApprovalDialog.vue
**File:** `frontend/src/components/studio/ReviewApprovalDialog.vue`
**Type:** CREATE
**Dependencies:** None
**Description:** Modal showing original vs rewritten plan diff, findings list with severity, iteration counter, Approve/Reject buttons. POSTs to approve/reject endpoints.
**Verification:** `vue-tsc --noEmit` passes.

### Task 11: Add WebSocket handler for review events
**File:** `frontend/src/stores/schemaStore.ts`
**Type:** MODIFY
**Dependencies:** Task 10
**Description:** Handle `review_awaiting_approval` WS event in the existing WebSocket handler. Show ReviewApprovalDialog. Add `approveReview()` and `rejectReview()` actions.
**Verification:** `vue-tsc --noEmit` passes.

## Batch 4 — Tests (parallel)

### Task 12: ReviewBlock test
**File:** `frontend/src/components/blocks/__tests__/ReviewBlock.test.ts`
**Type:** CREATE
**Dependencies:** Task 1
**Description:** Mount ReviewBlock, check amber color, label, iteration badge.
**Verification:** `npx vitest run` passes.

### Task 13: Backend review tests
**File:** `backend/src/test/java/com/agent/orchestrator/service/NodeExecutorTest.java`
**Type:** MODIFY
**Dependencies:** Task 8
**Description:** Test `executeReviewNode` with no checks → PASS, premortem only → findings returned.
**Verification:** `mvn compile -q` passes.
