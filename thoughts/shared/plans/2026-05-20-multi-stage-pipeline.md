# Multi-Stage Pipeline Implementation Plan

**Goal:** Transform the linear pipeline (Receive → Review → Agent → Verify → Output) into a recursive multi-stage pipeline where each Review node can produce a branch structure JSON that spawns a child `ExecutionRun` with dynamically-created sub-nodes.

**Architecture:** The execution engine gains a stage loop around `executeWorkflow()`. When a Review node produces a branch structure JSON, `spawnStageRun()` pauses the parent, creates a child `ExecutionRun` with spawned sub-nodes (agent/review → consolidation → gate), executes the sub-graph, and resumes the parent with compressed artifacts. No node type changes — only the Review node's output format expands. Single VueFlow instance with `group` type for stage visualization. Canvas freezes during execution.

**Design:** `thoughts/shared/designs/2026-05-20-multi-stage-pipeline-design.md`

---

## Dependency Graph

```
Batch 1 (parallel — 6 implementers): 1.1, 1.2, 1.3, 1.4, 1.5, 1.6  [foundation — no deps]
Batch 2 (parallel — 5 implementers): 2.1, 2.2, 2.3, 2.4, 2.5      [services — depends on batch 1 models]
Batch 3 (single task):                3.1                           [SchemaService — depends on batch 2]
Batch 4 (parallel — 5 implementers): 4.1, 4.2, 4.3, 4.4, 4.5      [frontend — depends on batch 1 types]
Batch 5 (parallel — 4 implementers): 5.1, 5.2, 5.3, 5.4            [unit tests — depends on batch 2-3]
```

---

## Batch 1: Backend Foundation Models + Parser (parallel — 6 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: BranchDefinition Model
**File:** `backend/src/main/java/com/agent/orchestrator/model/BranchDefinition.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/model/BranchDefinitionTest.java`
**Depends:** none

Design requires a branch model with id, name, description, type, dependsOn, artifactType. Following the POJO pattern from `Edge.java` — no Lombok, explicit getters/setters, Russian comments.

```java
package com.agent.orchestrator.model;

import java.util.List;

/**
 * Определение одной ветки внутри стадии.
 * Каждая ветка — это один spec/draft/implementation, который выполняется как агентский узел.
 */
public class BranchDefinition {
    private String id;
    private String name;
    private String description;
    private String type;
    private List<String> dependsOn;
    private String artifactType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
    public String getArtifactType() { return artifactType; }
    public void setArtifactType(String artifactType) { this.artifactType = artifactType; }
}
```

Test (`BranchDefinitionTest.java`): serialization + deserialization round-trip via Jackson, dependency list test.

**Verify:** `cd backend && mvn test -pl . -Dtest=BranchDefinitionTest`
**Commit:** `feat(model): add BranchDefinition POJO for stage branches`

---

### Task 1.2: BranchStructure Model
**File:** `backend/src/main/java/com/agent/orchestrator/model/BranchStructure.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/model/BranchStructureTest.java`
**Depends:** none (self-contained, imports BranchDefinition via list)

Design requires container: stage id/name, nextStagePrompt, list of branches.

```java
package com.agent.orchestrator.model;

import java.util.List;

public class BranchStructure {
    private StageInfo stage;
    private String nextStagePrompt;
    private List<BranchDefinition> branches;

    public static class StageInfo {
        private String id;
        private String name;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    // Standard getters/setters for all fields...
}
```

Test (`BranchStructureTest.java`): full example deserialization (matching design JSON spec), minimal structure test, null nextStagePrompt handling.

**Verify:** `cd backend && mvn test -Dtest=BranchStructureTest`
**Commit:** `feat(model): add BranchStructure container for Review node output`

---

### Task 1.3: BranchStructureParser
**File:** `backend/src/main/java/com/agent/orchestrator/service/BranchStructureParser.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/BranchStructureParserTest.java`
**Depends:** 1.1, 1.2 (uses BranchStructure, BranchDefinition)

Design specifies multi-layer fallback parsing matching `parseToolCalls()` pattern:
1. Strict Jackson parsing of JSON (or JSON extracted from markdown fences)
2. Lenient parsing (ALLOW_SINGLE_QUOTES, ALLOW_TRAILING_COMMA, ALLOW_UNQUOTED_CONTROL_CHARS)
3. Regex-based extraction (last resort — find `branches` array with pattern matching)
4. On ALL failures: throws `BranchParseException` with the raw LLM output — does NOT silently fall back

Validation: cycle detection via Kahn's algorithm, all `dependsOn` IDs must exist, no duplicate branch IDs.

Custom exception `BranchParseException` with `getRawOutput()` for error display.

**Verify:** `cd backend && mvn test -Dtest=BranchStructureParserTest`
**Commit:** `feat(service): add BranchStructureParser with multi-layer fallback + cycle detection`

---

### Task 1.4: ExecutionRun — add stageTokens field
**File:** `backend/src/main/java/com/agent/orchestrator/model/ExecutionRun.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/model/ExecutionRunTest.java`
**Depends:** none

ADD field: `private Map<String, Long> stageTokens;` (stageId → tokens used). Getter + setter.

Design requires per-stage token tracking for budget enforcement. `totalTokens` already exists.

**Verify:** `cd backend && mvn test -Dtest=ExecutionRunTest`
**Commit:** `feat(model): add stageTokens map to ExecutionRun for budget tracking`

---

### Task 1.5: WorkflowSchema — add stageBudget fields
**File:** `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/model/WorkflowSchemaStageBudgetTest.java`
**Depends:** none

ADD field: `private Map<String, Object> stageBudget;` containing: `maxLLMCalls`, `maxTotalTokens`, `warnAtCalls`, `warnAtTokens`, `maxStageRetries`. Getter + setter.

Design specifies budget enforcement config per schema. As `Map<String, Object>` for flexibility (matching existing `metadata` pattern).

**Verify:** `cd backend && mvn test -Dtest=WorkflowSchemaStageBudgetTest`
**Commit:** `feat(model): add stageBudget config map to WorkflowSchema`

---

### Task 1.6: Frontend stage types
**File:** `frontend/src/types/index.ts`
**Test:** none (type-only; tested by component compilation)
**Depends:** none

Add TypeScript interfaces: `StageInfo`, `BranchDefinition`, `BranchStructure`, `StageState`, plus `stageBudget?` field on `WorkflowSchema`.

```typescript
export interface StageInfo { id: string; name: string; }
export interface BranchDefinition { id: string; name: string; description: string; type: string; dependsOn: string[]; artifactType: string; }
export interface BranchStructure { stage: StageInfo; nextStagePrompt?: string; branches: BranchDefinition[]; }
export interface StageState { id: string; name: string; status: 'pending'|'running'|'completed'|'failed'; branchCount: number; completedBranchCount: number; totalTokens: number; error?: string; executionRunId?: string; nextStagePrompt?: string; }
// ADD to WorkflowSchema: stageBudget?: { maxLLMCalls?: number; maxTotalTokens?: number; warnAtCalls?: number; warnAtTokens?: number; maxStageRetries?: number; }
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(types): add multi-stage pipeline TypeScript interfaces`

---

## Batch 2: Backend Orchestration Services (parallel — 5 implementers)

All tasks depend on Batch 1 completing.

### Task 2.1: StageSpawner
**File:** `backend/src/main/java/com/agent/orchestrator/service/StageSpawner.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/StageSpawnerTest.java`
**Depends:** 1.1, 1.2

Creates a sub-graph (nodes + edges) from a `BranchStructure`:
- One `agent` node per branch (with description as userPrompt, branchId in config)
- Dependency edges based on `dependsOn`
- Consolidation node (`agent` type with merge prompt)
- Gate review node (`review` type with nextStagePrompt)
- Returns `SpawnedGraph` with nodes, edges, consolidationNodeId, gateReviewNodeId, stageGroupId

Layout: branches stacked vertically (y = 100 + i*200), offset by stage (x = 200 + stageIndex*400). Consolidation at bottom-center of the column. Gate to the right.

**Verify:** `cd backend && mvn test -Dtest=StageSpawnerTest`
**Commit:** `feat(service): add StageSpawner for creating sub-graph from branch structure`

---

### Task 2.2: ConsolidationService
**File:** `backend/src/main/java/com/agent/orchestrator/service/ConsolidationService.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/ConsolidationServiceTest.java`
**Depends:** 1.1, 1.2 (uses BranchDefinition)

Design specifies: "Summarize to 1/3 size preserving all decisions."
1. Collect branch outputs → build concatenated artifact with TOC
2. Call LLM with compression prompt
3. Return compressed artifact
4. If LLM fails → fallback truncation to 1/3
5. If content < 500 chars → skip compression (no LLM call)

Uses `LlmService.chat()` for compression. Dependency-injected.

**Verify:** `cd backend && mvn test -Dtest=ConsolidationServiceTest`
**Commit:** `feat(service): add ConsolidationService for merging and compressing branch outputs`

---

### Task 2.3: TruncationVerifier
**File:** `backend/src/main/java/com/agent/orchestrator/service/TruncationVerifier.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/TruncationVerifierTest.java`
**Depends:** none (uses LlmService)

Design specifies: ~50-token verification per branch output — "Does this output appear complete? PASS or FAIL."
1. Quick heuristic checks first (unclosed fences, brace balance, mid-sentence ending)
2. For outputs ≥100 chars, send last 500 chars to LLM with verification prompt
3. LLM returns PASS → complete; FAIL → truncated (retry branch)
4. If LLM errors → assume complete (don't block on this)

**Verify:** `cd backend && mvn test -Dtest=TruncationVerifierTest`
**Commit:** `feat(service): add TruncationVerifier for lightweight output completeness check`

---

### Task 2.4: ReviewNodeStrategy — stage-aware methods
**File:** `backend/src/main/java/com/agent/orchestrator/service/ReviewNodeStrategy.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/ReviewNodeStrategyTest.java`
**Depends:** 1.2, 1.3 (BranchStructure model + parser)

ADD three new public methods:

1. `executeStageReviewNode(node, schemaId, resolvedModel, stagePrompt, priorArtifacts, isGateReview)`:
   - Uses provided `stagePrompt` instead of hardcoded plan prompt
   - Appends `priorArtifacts` to review context
   - If `isGateReview == false`: includes branch structure output format in the prompt spec (`branchStructure` and `nextStagePrompt` fields)
   - If `isGateReview == true`: expects PASS/REWRITE with `nextStagePrompt`

2. `extractBranchStructureJson(reviewOutput)`: returns `branchStructure` field from review JSON, or null

3. `extractNextStagePrompt(reviewOutput)`: returns `nextStagePrompt` field, or null

**Design decision:** The existing `executeReviewNode()` is unchanged for backward compatibility. New methods are additive.

**Verify:** `cd backend && mvn test -Dtest=ReviewNodeStrategyTest`
**Commit:** `feat(service): add stage-aware review execution with branch structure output format`

---

### Task 2.5: ExecutionWebSocketHandler — stage lifecycle events
**File:** `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandlerTest.java`
**Depends:** none (standalone new methods on existing handler)

ADD four new methods following existing `sendXxx()` pattern:
- `sendStageStarted(schemaId, stageId, stageName, branchCount)` → type: `stage_started`
- `sendStageProgress(schemaId, stageId, completedBranches, totalBranches, tokensUsed)` → type: `stage_progress`
- `sendStageCompleted(schemaId, stageId, gateStatus, totalTokens)` → type: `stage_completed`
- `sendStageFailed(schemaId, stageId, error)` → type: `stage_failed`

All use existing `baseMsg()` + `sendMessage()` pattern with `toJson()` serialization.

**Verify:** `cd backend && mvn test -Dtest=ExecutionWebSocketHandlerTest`
**Commit:** `feat(ws): add stage lifecycle WebSocket events`

---

## Batch 3: SchemaService Integration (single task)

### Task 3.1: SchemaService — spawnStageRun(), stage loop, budget tracking
**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/service/SchemaServiceStageTest.java`
**Depends:** 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5

**New dependencies injected:** `BranchStructureParser`, `StageSpawner`, `ConsolidationService`, `TruncationVerifier`

**New inner classes:**
- `StageResult` — stageId, stageName, gateStatus, consolidatedArtifact, nextStagePrompt, totalTokens, error
- `BudgetTracker` — config, totalLLMCalls, totalTokens; `recordCall(tokens)`, `check()` returning `BudgetCheck`
- `BudgetCheck` — remainingLLMCalls, remainingTokens, canProceed

**Modifications to `executeWorkflow()`:**
After the existing wave execution, detect if any Review node produced a branch structure:
1. Call `detectBranchStructure(schema)` — iterates nodes, finds review node output, calls `branchStructureParser.parse()`
2. If `null` → continue existing linear path (backward compatible — same as today)
3. If found → enter stage loop:
   - Create `BudgetTracker` from `schema.getStageBudget()`
   - For each stage iteration:
     - `budgetTracker.check()` → fail-fast if exceeded
     - `spawnStageRun()` → create child run, execute sub-graph, collect results
     - If failed → break with error
     - Store consolidated artifact, advance nextStagePrompt
     - Send WS stage events
   - On budget exceeded → fail-fast with clear message

**`spawnStageRun()` implementation:**
1. Create child `ExecutionRun` (resumesFrom=parentRunId)
2. Send `stage_started` WS event
3. Call `stageSpawner.spawnSubGraph()` → get spawned nodes + edges
4. Clone schema for child run (parent nodes + spawned nodes)
5. Create `NodeExecution` records for spawned nodes
6. Call `executeStageSubGraph()` → runs wave execution on child schema
7. Collect branch outputs from node results
8. Run `truncationVerifier.verify()` on each branch output
9. Run `consolidationService.consolidate()` on all branch outputs
10. Extract gate output (status + nextStagePrompt)
11. Complete child `ExecutionRun`
12. Return `StageResult`

**`detectBranchStructure()` helper:**
Iterates schema nodes, finds review node with result, calls `branchStructureParser.parse()`. Returns null on failure (legacy mode) or if no review node found.

**`cloneSchemaForStage()` helper:**
Creates a copy of parent schema with spawned nodes + edges appended.

**Backward compatibility guarantee:**
The entire stage path is gated on `if (detectBranchStructure(schema) != null)`. If no branch structure is found (current single-stage pipelines), the existing wave loop runs identically to today's code.

**Verify:** `cd backend && mvn test -Dtest=SchemaServiceStageTest`
**Commit:** `feat(core): add stage loop and spawnStageRun to SchemaService`

---

## Batch 4: Frontend Components (parallel — 5 implementers)

### Task 4.1: StageGroup.vue — VueFlow group node
**File:** `frontend/src/components/stage/StageGroup.vue`
**Test:** `frontend/src/components/stage/__tests__/StageGroup.test.ts`
**Depends:** none (uses StageState type from 1.6)

Custom VueFlow node using `group` type. Uses VueFlow's built-in group behavior — no nested VueFlow instance.

**Props:**
- `id: string` — node ID
- `data: StageState & { onUpdate?: Function; onRetry?: Function; onAcceptPartial?: Function }`

**States:**
- **Collapsed:** Shows stage name + branch count badge (e.g., "Frontend Architecture (4)") + circular progress ring
- **Expanded:** Shows sub-nodes inside group boundaries with dependency edges (handled by VueFlow `group` type natively)

**Template:** Uses `<Handle>` for connection points (target only). Left-handle for inbound from parent Review node. Body renders stage name, status badge, progress ring. Collapse/expand toggle.

**Styling:** Rounded rectangle with dashed border (`stage-group` class). Semi-transparent when status is pending/dimmed. Error state with red border + error text. Completed state with green accent.

**Verify:** `cd frontend && npx vitest run --reporter=verbose stage/StageGroup`
**Commit:** `feat(ui): add StageGroup VueFlow group node component`

---

### Task 4.2: StageProgressBar.vue
**File:** `frontend/src/components/stage/StageProgressBar.vue`
**Test:** `frontend/src/components/stage/__tests__/StageProgressBar.test.ts`
**Depends:** none (uses StageState type from 1.6)

GitHub Actions-style progress bar above canvas.

**Props:**
- `stages: StageState[]`
- `currentStageId: string`

**Template:** Horizontal bar with stage segments. Each segment shows stage name + status icon. Current stage highlighted with pulsing indicator. Completed stages show green ✓. Failed stages show red ✗. Pending stages dimmed. Clicking a stage emits `scroll-to-stage` event.

**Styling:** Compact (40px height). Flex layout with equal-width segments. Status colors: green (completed), yellow/animating (running), gray (pending), red (failed).

**Verify:** `cd frontend && npx vitest run --reporter=verbose stage/StageProgressBar`
**Commit:** `feat(ui): add StageProgressBar for pipeline stage visualization`

---

### Task 4.3: BlueprintView — register stage-group, freeze, progress bar
**File:** `frontend/src/components/studio/BlueprintView.vue`
**Test:** `frontend/src/components/studio/__tests__/BlueprintViewStage.test.ts`
**Depends:** 4.1, 4.2 (StageGroup, StageProgressBar), 1.6 (StageState type)

**Changes:**

1. **Import `StageGroup.vue`** and register in `nodeTypes`:
```typescript
import StageGroup from '@/components/stage/StageGroup.vue'
const nodeTypes = {
  // ...existing types...
  'stage-group': markRaw(StageGroup),
}
```

2. **Add freeze logic** — bind `:nodes-draggable` and `:edges-updatable` props on `<VueFlow>` to a reactive ref:
```typescript
const isCanvasEditable = ref(true) // false during execution
```
```html
<VueFlow :nodes-draggable="isCanvasEditable" :edges-updatable="isCanvasEditable" ...>
```
Injected from parent (`isRunning` from StudioView).

3. **Add StageProgressBar** above the canvas (or at the top of the blueprint view):
```html
<StageProgressBar v-if="stages.length > 0" :stages="stages" :current-stage-id="currentStageId" @scroll-to-stage="scrollToStage" />
```

4. **Add `stages` reactive state** — array of `StageState` populated from WS events. Injected via `provide/inject` or direct prop.

5. **Add `scrollToStage(stageId)` method** using `useVueFlow().fitView()` with focused nodes.

**Verify:** `cd frontend && npx vitest run --reporter=verbose studio/BlueprintViewStage`
**Commit:** `feat(ui): register stage-group node type with canvas freeze + progress bar`

---

### Task 4.4: StudioView — stage lifecycle WS handling
**File:** `frontend/src/views/StudioView.vue`
**Test:** none (integration view — test via E2E)
**Depends:** 2.5 (WS event types), 1.6 (StageState)

**Changes:**

1. **Add stage state reactive array:**
```typescript
const stages = ref<StageState[]>([])
const currentStageId = ref<string>('')
```

2. **Provide stages to child components:**
```typescript
provide('stages', stages)
provide('currentStageId', currentStageId)
```

3. **Handle new WebSocket events in the `connect()` callback:**
- `stage_started`: add new stage state, set as current
- `stage_progress`: update branch completion count, tokens
- `stage_completed`: mark stage as completed, update tokens
- `stage_failed`: mark stage as failed, set error message

4. **Add stage error handling UI** — show retry/accept-partial/cancel actions when a stage fails

5. **Update `startExecution`** to reset stages array

**Verify:** Manual — run dev server, execute a pipeline, verify stage events appear
**Commit:** `feat(ui): handle stage lifecycle events in StudioView`

---

### Task 4.5: useWebSocket — stage event callbacks
**File:** `frontend/src/composables/useWebSocket.ts`
**Test:** `frontend/src/composables/__tests__/useWebSocketStage.test.ts`
**Depends:** none (standalone composable extension)

Extend `WebSocketCallbacks` interface:

```typescript
onStageStarted?: (data: { schemaId: string; stageId: string; stageName: string; branchCount: number; status: string }) => void;
onStageProgress?: (data: { schemaId: string; stageId: string; completedBranches: number; totalBranches: number; tokensUsed: number }) => void;
onStageCompleted?: (data: { schemaId: string; stageId: string; gateStatus: string; totalTokens: number; status: string }) => void;
onStageFailed?: (data: { schemaId: string; stageId: string; error: string; status: string }) => void;
```

Add dispatch in `onmessage` switch:
```typescript
case 'stage_started':   callbacks?.onStageStarted?.(data); break;
case 'stage_progress':  callbacks?.onStageProgress?.(data); break;
case 'stage_completed': callbacks?.onStageCompleted?.(data); break;
case 'stage_failed':    callbacks?.onStageFailed?.(data); break;
```

**Verify:** `cd frontend && npx vitest run --reporter=verbose composables/useWebSocketStage`
**Commit:** `feat(composable): add stage lifecycle callbacks to useWebSocket`

---

## Batch 5: Tests (parallel — 4 implementers)

All tests verify components from Batches 2-3.

### Task 5.1: Stage pipeline integration test
**File:** `backend/src/test/java/com/agent/orchestrator/service/StagePipelineIntegrationTest.java`
**Depends:** 2.1, 2.2, 2.3, 2.4, 3.1

Integration test that wires together StageSpawner + ConsolidationService + TruncationVerifier + ReviewNodeStrategy in a realistic scenario. Does NOT require Spring Boot test context — uses Mockito for LLM calls and execution repo.

Test scenarios:
- Full stage pipeline: mock Review output → parse → spawn → consolidate → verify
- Error on branch parse: bad JSON → exception
- Backward compatible: no branch structure → linear path
- Budget tracking: exceeds limit → fails before stage starts

**Verify:** `cd backend && mvn test -Dtest=StagePipelineIntegrationTest`
**Commit:** `test: add stage pipeline integration test`

---

### Task 5.2: Frontend StageGroup rendering test
**File:** `frontend/src/components/stage/__tests__/StageGroup.test.ts`
**Depends:** 4.1

Use `@vue/test-utils` + `vi.mock('@vue-flow/core')` for Handle/Position. Test:
- Renders stage name
- Shows branch count badge
- Shows progress ring
- Collapse/expand toggle
- Error state styling
- Completion state styling

**Verify:** `cd frontend && npx vitest run --reporter=verbose stage/StageGroup`
**Commit:** `test: add StageGroup component tests`

---

### Task 5.3: Frontend StageProgressBar rendering test
**File:** `frontend/src/components/stage/__tests__/StageProgressBar.test.ts`
**Depends:** 4.2

Test:
- Renders all stages
- Highlights current stage
- Shows status icons (pending/running/completed/failed)
- Emits scroll-to-stage event on click

**Verify:** `cd frontend && npx vitest run --reporter=verbose stage/StageProgressBar`
**Commit:** `test: add StageProgressBar component tests`

---

### Task 5.4: Frontend useWebSocket stage events test
**File:** `frontend/src/composables/__tests__/useWebSocketStage.test.ts`
**Depends:** 4.5

Mock WebSocket, verify:
- `stage_started` event dispatches `onStageStarted` callback
- `stage_completed` event dispatches `onStageCompleted` callback
- `stage_failed` event dispatches `onStageFailed` callback
- Unknown event types are ignored

**Verify:** `cd frontend && npx vitest run --reporter=verbose composables/useWebSocketStage`
**Commit:** `test: add useWebSocket stage event tests`

---

## Execution Sequence

### Recommended implementation order (by developer count):

| Phase | Parallel Devs | Tasks | Description |
|-------|--------------|-------|-------------|
| 1 | 6 | 1.1-1.6 | Models + parser + types |
| 2 | 5 | 2.1-2.5 | Services + WS events |
| 3 | 1 | 3.1 | SchemaService integration |
| 4 | 5 | 4.1-4.5 | Frontend components |
| 5 | 4 | 5.1-5.4 | Tests |

Total: ~21 micro-tasks across 5 batches, 10-15 simultaneous developers at peak.

### Key constraints for implementers:

1. **No Lombok** — all models use explicit getters/setters
2. **Java 21 features** — records, pattern matching, text blocks where appropriate
3. **Constructor injection** — no @Autowired on fields
4. **SLF4J logging** with Russian messages
5. **Virtual threads** — use `Executors.newVirtualThreadPerTaskExecutor()` matching existing pattern
6. **Vue Composition API** with `<script setup lang="ts">` for all components
7. **Single VueFlow** — no nested instances for stage groups
8. **Backward compatibility** — all existing tests must pass; stage code is gated

### File paths summary:

**New files (backend):**
- `backend/src/main/java/com/agent/orchestrator/model/BranchDefinition.java`
- `backend/src/main/java/com/agent/orchestrator/model/BranchStructure.java`
- `backend/src/main/java/com/agent/orchestrator/service/BranchStructureParser.java`
- `backend/src/main/java/com/agent/orchestrator/service/StageSpawner.java`
- `backend/src/main/java/com/agent/orchestrator/service/ConsolidationService.java`
- `backend/src/main/java/com/agent/orchestrator/service/TruncationVerifier.java`

**New files (frontend):**
- `frontend/src/components/stage/StageGroup.vue`
- `frontend/src/components/stage/__tests__/StageGroup.test.ts`
- `frontend/src/components/stage/StageProgressBar.vue`
- `frontend/src/components/stage/__tests__/StageProgressBar.test.ts`

**Modified files (backend):**
- `backend/src/main/java/com/agent/orchestrator/model/ExecutionRun.java` (+stageTokens)
- `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java` (+stageBudget)
- `backend/src/main/java/com/agent/orchestrator/service/ReviewNodeStrategy.java` (+stage-aware methods)
- `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java` (+stage loop, spawnStageRun)
- `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java` (+stage events)

**Modified files (frontend):**
- `frontend/src/types/index.ts` (+stage interfaces)
- `frontend/src/components/studio/BlueprintView.vue` (+stage-group type, freeze, progress bar)
- `frontend/src/views/StudioView.vue` (+stage lifecycle handling)
- `frontend/src/composables/useWebSocket.ts` (+stage callbacks)
