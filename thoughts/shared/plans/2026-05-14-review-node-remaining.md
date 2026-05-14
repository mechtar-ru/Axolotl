# Review Node — Remaining Implementation Plan

## Batch 1 — Approval Dialog Redesign (parallel)

### Task 1.1: Redesign ReviewApprovalDialog.vue
**File:** `frontend/src/components/studio/ReviewApprovalDialog.vue`
**Type:** MODIFY
**Description:** Replace current approval dialog with full-featured version:
- Editable plan section (toggle between `<pre>` and `<textarea>` on "Edit Plan" click)
- Feedback input field with "+ Add more" button
- Feedback history display (previous iterations' feedback with applied/not-applied status)
- Three action buttons: `✏️ Edit Plan` | `↻ Suggest & Regenerate` | `✓ Accept`
- Severity badges (HIGH=red, MEDIUM=yellow, LOW=gray, INFO=blue)
- Mode indicator: "Review — Iteration 2 of ∞ (Manual)"
- Past findings from previous iterations shown
**Verification:** `vue-tsc --noEmit`

### Task 1.2: Update BlockConfigPanel — iteration modes
**File:** `frontend/src/components/studio/BlockConfigPanel.vue`
**Type:** MODIFY
**Description:** Update the review config section:
- Replace mode selector (pass-through/rewrite) with: `manual` | `auto` | `hybrid`
- Add `maxAutoIterations` number input (default 3, shown only for auto/hybrid)
- Add `generatePlan` toggle (default ON)
- Keep existing premortem/prism/postmortem toggles
**Verification:** `vue-tsc --noEmit`

## Batch 2 — Backend Updates (parallel)

### Task 2.1: Update executeReviewNode() with modes + feedback
**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java`
**Type:** MODIFY
**Description:** Update the existing `executeReviewNode()` method:
- **Phase 1 (Plan Generation):** If `generatePlan=true`, call LLM to create plan from upstream description. If false, use upstream result as plan.
- **Phase 2 (Analysis):** Run premortem/prism/postmortem checks in single LLM call.
- **Mode handling:**
  - Manual: Always show human dialog after Phase 2
  - Auto: Skip dialog, auto-rewrite up to maxAutoIterations, then PASS or FAIL
  - Hybrid: Auto-rewrite up to maxAutoIterations, then show human gate
- **Feedback handling:** When `/api/execution/{id}/feedback` is received, append feedback text to the LLM prompt and re-run Phase 1 + 2
- **Feedback history:** Track all feedback submissions across iterations in execution state
**Verification:** `mvn compile -q`

### Task 2.2: Add feedback endpoint to SchemaService
**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
**Type:** MODIFY
**Description:** Add:
- `handleReviewFeedback(executionId, nodeId, feedback, history)` — receives feedback from UI, stores in execution state, triggers re-generation
- Add REST endpoint: `POST /api/execution/{executionId}/feedback?nodeId={nodeId}` with body `{feedback: "...", history: [...]}`
- Wire up to re-run the review node's LLM call with feedback appended
**Verification:** `mvn compile -q`

### Task 2.3: Add Verifier rewrite loop
**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java`
**Type:** MODIFY
**Description:** Update `executeVerifierNode()` (existing in v1):
- Read `rewriteOnFail` and `maxRewriteRetries` from config
- If a check fails + rewriteOnFail=true:
  1. Collect all errors
  2. Call LLM with: original prompt + error messages + file content → return fixed code
  3. Write fixed code to file
  4. Re-run checks
  5. Repeat up to maxRewriteRetries
- Add premortem check: before running syntax/tests, call LLM to predict failure scenarios
- After tests, compare predictions vs actual failures in output
- Return structured result with rewrite count and premortem predictions
**Verification:** `mvn compile -q`

### Task 2.4: Add Output node summary report mode
**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java`
**Type:** MODIFY
**Description:** Update `executeOutputNode()` (or add new method):
- When `config.mode === "summary_report"`:
  1. Query `SchemaService.executionResults` for ALL completed nodes' results
  2. Collect review findings, verifier results, agent metadata from upstream nodes
  3. Build a markdown report string with sections for each node
  4. Write report to file at `config.reportPath` (default: pipeline-report.md)
- When `config.mode === "simple"` (default): existing behavior (log final result)
**Verification:** `mvn compile -q`

## Batch 3 — Template + Tests (parallel)

### Task 3.1: Update Sokoban template to 5-node pipeline
**File:** `frontend/src/templates/index.ts`
**Type:** MODIFY
**Description:** Replace the Sokoban Game template definition:
- Old: Source → Think → Act (3 nodes, 2 edges)
- New: Source → Review (manual, premortem) → Agent (coder, file_write) → Verifier (syntax, premortem, rewrite=3) → Output (summary_report)
- Set reviewer node premortem=ON, mode=manual, generatePlan=ON
- Set verifier node rewriteOnFail=true, maxRewriteRetries=3, premortem=ON
- Set output node mode=summary_report
- Remove duplicate Sokoban entries if they exist
**Verification:** `vue-tsc --noEmit`

### Task 3.2: Frontend tests
**File:** `frontend/src/components/studio/__tests__/ReviewApprovalDialog.test.ts` (new)
**File:** `frontend/src/components/blocks/__tests__/ReviewBlock.test.ts` (update)
**Type:** CREATE + MODIFY
**Description:**
- New test: ReviewApprovalDialog renders, editable plan, suggest & regenerate emits feedback event
- Update: ReviewBlock shows iteration badge on auto mode
**Verification:** `npx vitest run`

### Task 3.3: Backend tests
**File:** `backend/src/test/java/com/agent/orchestrator/service/NodeExecutorTest.java`
**Type:** MODIFY
**Description:** Add test methods:
- executeReviewNode with manual mode → returns AWAITING_APPROVAL state
- executeReviewNode with auto mode → auto-rewrites up to max, then PASS or FAIL
- executeVerifierNode with rewrite → fixes code on FAIL
- Output node with summary_report mode → produces report
**Verification:** `mvn compile -q` (don't run `mvn test` — needs Neo4j)
