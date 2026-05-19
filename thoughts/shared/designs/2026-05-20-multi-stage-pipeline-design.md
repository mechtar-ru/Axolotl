---
date: 2026-05-20
topic: "Multi-Stage Structured Pipeline"
status: draft
---

## Problem Statement

The current Quick Start pipeline is a fixed linear chain: **Receive → Review → Agent → Verify → Output**. This works for simple apps (single HTML file, basic Python script) but breaks down for complex multi-screen applications like Бережно (auth, calendar, stats, notifications). The single agent node holds the entire app in one context window, producing inconsistent code and forcing the LLM to juggle UI, business logic, data model, and routing as one undifferentiated task.

There is no structural decomposition — the pipeline cannot branch, stage, or sequence work by concern. Users get a black-box agent that "does everything" instead of a deliberate multi-phase process with review gates and parallel spec generation.

## Goals

1. **Structured multi-stage execution** — pipelines decompose into stages (frontend architecture → tech decisions → backend draft → implementation) with explicit gates between them
2. **Branch-by-concern per stage** — each stage branches differently (by screen, by layer, by deliverable), determined at runtime by the Review node
3. **Foldable hierarchy** — branches are invisible by default, visible on click; current stage visible, next stage shown as placeholder
4. **No Quick Start changes** — single input → canvas remains. The Review node produces the decomposition
5. **Artifact-driven** — each branch produces a structured spec (not code) that feeds the next stage
6. **Review node is stage-aware and dynamic** — its prompt changes per gate, AND it produces the next stage's prompt template in its artifact

## Non-Goals

- Not a general-purpose sub-pipeline editor (no "create nested pipeline manually")
- Not replacing the existing linear pipeline for simple/review use cases
- Not adding new node types — reusing review, agent, verify with stage context
- No changes to Quick Start dialog — input→canvas stays identical

## Approach

The pipeline evolves from a static linear chain to a **recursive stage tree** where execution is itself recursive — each stage runs as a **child execution** of the parent run:

1. Quick Start creates exactly what it does today — a single pipeline seed with 5 nodes
2. The first Review node produces a **structured branch definition** — parseable JSON listing branches, dependencies, descriptions, AND the next stage's review prompt
3. The parent execution **pauses**, spawns a **child execution run** with the stage's spawned nodes, and **resumes** when the child completes
4. The child run's gate review node either signals PASS (→ spawn next stage) or triggers iteration
5. Each stage is a full `executeWorkflow` call, with its own `ExecutionRun`, `ExecutionCheckpoint`s, and `NodeExecution` records

This is not a refactor of the execution engine — it's a **layering** on top. The engine already supports multiple concurrent runs. Recursive stages are just runs that are **created and awaited** by the parent run.

## Architecture

### Recursive Execution Model

```
Parent execution run (schema-level):
  Stage 1:
    ↓ Review node produces branch structure
    ↓ Pause parent, create child run
    ↓ executeWorkflow(child_run, child_nodes)
      ↓ Branches execute, consolidate, gate
      ↓ Gate PASS → mark child completed
    ↓ Parent resumes with Stage 1 artifacts
  Stage 2:
    ↓ Review node produces branch structure
    ↓ Pause parent, create child run
    ↓ ...repeat...
  Stage N:
    ↓ ...repeat...
  ↓ Output node collects all artifacts
```

Key property: **the parent run never deals with sub-nodes directly**. The child run owns the stage's entire execution graph. This keeps the spawning logic isolated to a single `spawnStageRun()` method rather than threading through the entire execution loop.

### Stage Model

A stage is a self-contained execution unit with:

| Property | Description |
|---|---|
| `id` | Unique stage identifier (UUID) |
| `name` | Human-readable name (e.g., "Frontend Architecture") |
| `branches` | List of branch definitions (decided by Review node per stage) |
| `reviewNodePrompt` | Custom prompt for this stage's Review node (produced by prior gate) |
| `nextStagePrompt` | Prompt template for the next stage's Review node (produced by this stage's gate) |
| `executionRunId` | Child `ExecutionRun` ID |
| `status` | `pending`, `running`, `completed`, `failed` |

Stages sequence linearly. Within a stage, branches run in parallel (subject to dependency edges via the existing topological sort).

### Branch Model

A branch is a mini-pipeline within a stage:

| Property | Description |
|---|---|
| `id` | Unique branch identifier within the stage |
| `name` | Human-readable name (e.g., "Auth Screen Spec") |
| `description` | Full spec description — becomes the sub-agent's system prompt |
| `type` | What kind of work: `spec`, `draft`, `implementation` |
| `dependsOn` | Other branch IDs in the same stage |
| `artifactKey` | What this branch produces (for consolidation) |

### Stage Lifecycle

```
┌──────────────────────────────────────────────────────────────┐
│  Stage N: Frontend Architecture                              │
│                                                              │
│  Review node runs (stage-aware prompt):                      │
│    → Produces JSON branch structure                          │
│    → Produces nextStagePrompt for the gate                   │
│                                                              │
│  Parent execution pauses current run, spawns child run:      │
│    1. Create ExecutionRun (resumesFrom=parent.id)            │
│    2. Create NodeExecution for each spawned node             │
│    3. Run executeWorkflow(childSchema, childRunId)           │
│                                                              │
│  Child run:                                                  │
│    [Auth Spec] [Calendar] [Stats] [Notif]                    │
│      ↓(dep)      ↓                                           │
│    [Calendar]  [Stats]  [Notif]                              │
│                                                              │
│  All branches run (parallel where deps allow)                │
│  Consolidation: artifacts merged → summarized spec           │
│                                                              │
│  Gate: Review node evaluates consolidated output:            │
│    PASS → mark child completed, resume parent                │
│    REWRITE → iterate failed branches, re-gate                │
│    (mode: manual/semi/auto, same as current review node)     │
│                                                              │
│  Parent resumes: nextStagePrompt becomes the template        │
│  for the next stage's Review node                            │
└──────────────────────────────────────────────────────────────┘
```

## Components

### 1. Stage-Aware Review Node (backend)

**What changes**: The Review node receives its prompt from the execution context rather than being hardcoded. The initial prompt comes from the Quick Start template; subsequent prompts come from the `nextStagePrompt` produced by the prior stage's gate.

**Dynamic prompt resolution**:

1. For the **first stage**: prompt from Quick Start's system template (hardcoded)
2. For **subsequent stages**: prompt from the prior stage's gate output (`nextStagePrompt` field)
3. If no `nextStagePrompt` is provided: fall back to a default based on stage index (backward compatible)

**Stage-specific prompts** (shown as defaults, overridable by prior gate):

| Stage | Review Prompt |
|---|---|
| Stage 1 (Frontend Architecture) | *"Given the app description, decompose it into screens/components. For each screen, describe: UI elements, user interactions, data dependencies, state management needs. Output a JSON branch structure with one branch per screen."* |
| Stage 2 (Tech Decision) | *"Given the frontend architecture spec, determine: language, framework, key libraries, state management approach, routing strategy, build tooling. Single node — no branching."* |
| Stage 3 (Backend Draft) | *"Given the frontend spec and tech decisions, decompose by concern: data model, API routes, auth flow, notification service. For each, produce pseudocode handler signatures and data schemas."* |
| Stage 4 (Implementation) | *"Given all prior artifacts, decompose by deliverable: one branch per (screen + backend handler pair). Each branch writes actual code."* |

The general pattern: **early stages decompose by screen/UI, middle stages by concern/layer, later stages by deliverable/module.**

### 2. Branch Structure Artifact

The Review node outputs this parseable JSON alongside its standard findings AND the next stage's prompt:

```json
{
  "stage": {
    "id": "frontend-arch",
    "name": "Frontend Architecture"
  },
  "nextStagePrompt": "Given the frontend architecture spec, determine: language, framework, key libraries, state management approach, routing strategy, build tooling. Single node — no branching.",
  "branches": [
    {
      "id": "auth-screen",
      "name": "Auth Screen Spec",
      "description": "Login and registration screens with email/password and biometric...",
      "dependsOn": [],
      "artifactType": "frontend-spec"
    },
    {
      "id": "calendar-screen",
      "name": "Calendar View Spec",
      "description": "Monthly calendar with mood entries, color-coded by emotion...",
      "dependsOn": ["auth-screen"],
      "artifactType": "frontend-spec"
    },
    {
      "id": "stats-screen",
      "name": "Analytics Dashboard Spec",
      "description": "Charts showing mood trends, streaks, most common emotions...",
      "dependsOn": ["calendar-screen"],
      "artifactType": "frontend-spec"
    },
    {
      "id": "notifications",
      "name": "Notification System Spec",
      "description": "Push notification scheduling, daily reminders, tips...",
      "dependsOn": [],
      "artifactType": "frontend-spec"
    }
  ]
}
```

The system extracts this, creates sub-nodes for each branch, and routes the appropriate `description` as each sub-agent's system prompt.

### 3. Review Node Output Parser (backend)

A new utility component that:

1. Detects the branch structure section in the Review node's output
2. Extracts and validates the JSON (with multi-layer fallback parsing matching the existing `parseToolCalls` pattern)
3. Validates branch dependencies (no cycles, all referenced IDs exist)
4. Extracts `nextStagePrompt` field
5. Returns a `BranchDefinition` object the execution engine can act on

**On parse failure**: DOES NOT fall back silently. Instead:
- Logs the full error with the raw LLM output
- Emits an error WebSocket event visible in the canvas
- The stage transitions to a visible "branch parsing failed" state
- User can choose: retry the Review node, or continue as linear pipeline

This surfaces failures instead of hiding them.

### 4. Sub-node Spawner + Stage Executor (backend, in SchemaService)

This is the core new infrastructure. Two methods:

**`spawnStageRun(parentRun, stageDefinition, schema)`**:

1. Validate branch structure (no cycles, valid dependency references)
2. Create agent/review nodes for each branch definition
3. Create dependency edges based on `dependsOn`
4. Create a consolidation node (receives all branch outputs)
5. Create a gate review node (receives consolidated output)
6. Persist the new nodes and edges to the schema under a stage group
7. Create a child `ExecutionRun` with `resumesFrom = parentRun.id`
8. Run `executeWorkflow(schema, cancelFlag, childRunId)` on the spawned sub-graph only
9. **Block** the parent's execution thread on the child's completion (`CompletableFuture.join()`)
10. Return the child run's consolidated artifact and gate result

**`resumeParentAfterStage(parentRun, stageResult)`**:

1. Read the gate's output to determine PASS/REWRITE
2. If PASS: extract `nextStagePrompt`, collect consolidated artifacts, advance to next stage
3. If REWRITE and iterations remain: reset failed branches, re-run the stage
4. Merge the child run's artifacts into the parent's artifact store
5. Continue the parent execution loop

The parent run's execution loop becomes:

```java
for (StageDefinition stage : pipelineStages) {
    StageResult result = spawnStageRun(parentRun, stage, schema);
    if (result.status == FAILED) {
        // Gate rejected, user feedback or max iterations exceeded
        break;
    }
    artifacts.putAll(result.consolidatedArtifacts);
}
```

### 5. Foldable Stage Group (frontend, VueFlow)

A custom node type `stage-group` using VueFlow's built-in `group` node type (single VueFlow instance, no nesting):

- **Collapsed state**: Renders as a single block with stage name + branch count badge + progress ring
- **Expanded state**: Reveals sub-nodes inside the group boundaries with dependency edges visible
- **Semi-transparent next stage**: A dimmed placeholder node with no label until the gate resolves. When the gate produces the actual `nextStagePrompt`, the label and branch count update.

**Critical decision: single VueFlow.** Sub-nodes are regular VueFlow nodes with their positions bounded within the group node's boundaries. VueFlow's `group` type handles containment and drag boundaries natively. No nested VueFlow instances.

### 6. Stage Gate (backend + frontend)

Each stage ends with a **gate** — the existing review node, no changes:

| Mode | Behavior |
|---|---|
| **Manual** (default) | Present all branch artifacts + consolidation to user. Approve/feedback/reject. |
| **Semi-auto** | Auto-pass if all branches pass and confidence > threshold. Otherwise escalate to manual. |
| **Auto** | Auto-pass if consolidated review returns PASS. Fail if max iterations exceeded. |

The gate review node has a special additional output: `nextStagePrompt`. This is extracted alongside the branch structure by the parser and becomes the system prompt for the next stage's review node.

### 7. Artifact Consolidation with Compression (backend)

After all branches in a stage complete, consolidation merges branch outputs **and compresses them** to control context window growth:

1. Collect each branch's output (structured JSON spec)
2. Concatenate with a table of contents
3. Run a **compression pass** via the LLM: *"Summarize the following architecture spec to 1/3 its size while preserving all key decisions, interfaces, and dependencies."*
4. The compressed summary becomes the input to the gate review node

The same compression happens between stages — the gate's output is compressed before passing to the next stage's review node. This prevents unbounded context growth through the pipeline.

### 8. Stage Context Propagation (backend)

Each stage's Review node receives:

- The original app description (from Quick Start input)
- Compressed consolidated artifacts from all prior stages
- The current stage's `nextStagePrompt` (from the prior stage's gate)
- The current stage's prompt (from the Quick Start system or prior gate)

This context is passed via the existing `collectPredecessorResults` mechanism — no new context distribution needed. The compressed artifacts keep the total manageable.

### 9. Cost Budget (backend, new config)

**Why**: A 4-stage pipeline with branching can hit 20+ LLM calls per full pass. On free tiers with rate limits this becomes impractical.

A new schema-level config:

```yaml
stageBudget:
  maxLLMCalls: 30          # Hard limit per full pipeline run
  maxStageCost: 8          # Warn/gate approval beyond this
  warnAtCalls: 15          # Show warning before starting
```

The system tracks LLM calls per run. When approaching the limit, it warns the user. The gate can auto-reject if budget would be exceeded.

## Data Flow

```
Quick Start input: "Create emotion tracking app with auth, calendar, stats, notifications..."

↓ Quick Start creates initial pipeline (unchanged, same as today)

[Receive] → [Review] → [Agent] → [Verify] → [Output]

↓ Execute pipeline. First Review node runs.

Review node (Stage 1) produces:
  - Findings, summary, plan (same as today)
  - PLUS: Branch structure JSON + nextStagePrompt

↓ Parent pauses execution

↓ spawnStageRun() called:
  - Creates 4 agent nodes (Auth, Calendar, Stats, Notif)
  - Creates edges based on dependsOn
  - Creates consolidation node
  - Creates gate review node
  - Creates child ExecutionRun
  - Calls executeWorkflow() on spawned sub-graph

↓ Stage 1 child run executes:

[Auth Spec] → [Calendar Spec] → [Stats Spec]
[Notif Spec] (independent)
       ↓
[Consolidation] → [Gate Review]

↓ If gate PASS → parent resumes
↓ If gate REWRITE → iterate failed branches, re-gate

↓ Stage 2 Review node runs with nextStagePrompt from Stage 1
↓ ...repeat through stages...

↓ Final stage completes
↓ Parent's Output node collects all artifacts + metrics
```

## Canvas Visualization

### Default View

```
[Receive] ──→ [Review] ──→ [Frontend Architecture (4)] ──→ [Gate FAILED]

Stage groups are shown as single blocks. Gate status visible immediately.
```

### Expanded View (clicked on stage group)

```
[Receive] ──→ [Review] ──→ ╭─────────────────────────────────────╮    → [Gate FAILED]
                            │  [Auth Spec]      ✓ completed      │
                            │    ↓(dep)                           │
                            │  [Calendar Spec]   ✗ failed        │
                            │    ↓                                │
                            │  [Stats Spec]      ⏳ pending       │
                            │                                     │
                            │  [Notif]           ✓ completed      │
                            ╰─────────────────────────────────────╯
```

Single VueFlow. Sub-nodes are bounded within the group node. `group` type handles containment.

### Next Stage Placeholder

Before the gate resolves, the next stage position shows a dimmed rounded rectangle with "Next Stage" and no label. After the gate produces the `nextStagePrompt`, it updates to show the stage name and branch count.

### Canvas Freeze During Execution

During execution (including sub-executions), the canvas is read-only. VueFlow's `nodesDraggable` and `edgesUpdatable` props are set to `false`. The user sees progress but cannot edit. On completion, editing re-enables. This prevents desync between spawned nodes and user modifications.

## Error Handling

| Scenario | Behavior |
|---|---|
| Review node produces invalid branch JSON | **Visible error in canvas** + log warning. User can retry the Review node or continue as linear pipeline. |
| Branch has circular dependency | `spawnStageRun` validates before creating nodes. Error returned with details. Stage doesn't start. |
| Branch agent fails | Mark branch as FAILED in the UI. Consolidation receives partial results + status flags. Gate review node sees which branches failed and decides. |
| Consolidation receives mixed results | Pass all to gate review with status flags. Gate decides PASS/REWRITE per standard logic. |
| Gate outputs no `nextStagePrompt` | Fall back to default prompt based on stage index (backward compatible). |
| No branch structure in Review output | Treat as legacy single-stage pipeline — no change from current behavior. |
| Child execution run fails entirely | Parent detects failure, surfaces error, user retries the stage. |
| LLM call budget exceeded | Gate auto-rejects. Warning shown in canvas. User can increase budget and retry. |

## Token Exhaustion & Fail-Fast Strategy

Multi-stage pipelines multiply LLM calls. When tokens run out, the pipeline must respond predictably rather than producing silently broken artifacts.

### Per-Stage Token Tracking

Each child `ExecutionRun` reports its total token usage back to the parent run upon completion. The parent maintains a rolling sum:

```
Parent ExecutionRun:
  totalTokens: 45000
  stageTokens: {
    "stage-1-frontend-arch": 22000,
    "stage-2-tech-decision": 8000,
    "stage-3-backend-draft": 15000
  }
```

Before spawning the next stage, the parent checks: "If this stage runs N branches with worst-case Y iterations, will the remaining budget suffice?" If not, it fails fast before the stage starts.

### Fail-Fast on Stage Failure

When a stage fails (429 rate limit, context overflow, budget exceeded, internal error):

1. The child run fails immediately with a clear error code
2. The parent receives the error and **does not continue** to the next stage
3. The pipeline surfaces the error in the canvas: *"Stage 3 (Backend Draft) failed: token budget exceeded. Remaining stages (Implementation) skipped."*
4. The user can: increase budget and retry the failed stage, or accept partial results

**No cripple mode.** The pipeline does not guess around missing artifacts. Every stage's output is validated before advancing. If a stage fails, the pipeline stops.

### Truncation Verification

After each branch agent completes, before its artifact reaches consolidation, a lightweight verification step runs:

1. Same LLM, one cheap call: *"Does this output appear complete? Check for truncated sentences, undefined references, missing closing brackets. Reply PASS or FAIL with reason."*
2. If FAIL → the branch is retried (up to `maxIterations`)
3. If PASS → artifact proceeds to consolidation

This is a ~50-token verification call. It adds marginal cost per branch but catches the silent-truncation case that would otherwise cascade through subsequent stages.

### Budget Enforcement Sequence

```
Before Stage 1:
  Estimate: 5 branches × 3 iterations × 2 calls each = ~30 calls
  Check against maxLLMCalls (30): 30 ≤ 30 → proceed

During Stage 1:
  Branch Auth: 2000 tokens ✓
  Branch Calendar: 1200 tokens ✓
  ...all pass verification, consolidation, gate PASS
  Report: stage-1 = 22 calls, 15000 tokens

Before Stage 2:
  Budget remaining: 8 calls / 30000 tokens
  Estimate: 1 branch × 1 iteration × 1 call = 1 call ✓
  Proceed

During Stage 2:
  Tech Decision LLM call → HTTP 429 rate limit
  Retry with backoff... still 429 after 3 retries
  → Stage 2 FAILED

Pipeline stops.
Canvas shows: "Stage 2 (Tech Decision) failed after 3 retries: Zen API rate limit exceeded."
User actions: Retry Stage / Accept Partial / Cancel
```

### Cost Budget Config (updated)

```yaml
stageBudget:
  maxLLMCalls: 30              # Hard limit per full pipeline run
  maxTotalTokens: 250000       # Hard token limit
  warnAtCalls: 15              # Show warning before starting
  warnAtTokens: 100000         # Show warning at this threshold
  maxStageRetries: 3           # Max retries per failed stage
```

These values become schema-level metadata fields, read by the execution engine at run start.

## Testing Strategy

- **Unit tests**: `BranchStructureParser` (valid/invalid/missing JSON, missing `nextStagePrompt`), `SubNodeSpawner` (correct node creation from valid structure, cycle detection), `ConsolidationEngine` (merge + compress of N specs)
- **Integration tests**: Recursive execution — parent spawns child run, child completes, parent resumes. Gate review PASS/REWRITE cycles with compressed artifacts.
- **E2E tests**: Quick Start → execute → verify stage group appears on canvas → expand → see sub-nodes → approve gate → verify next stage placeholder updates
- **Existing tests must not break**: Single-stage linear pipelines must still work identically (no branch structure in Review output = legacy path)
- **Cost budget tests**: Verify hard limit enforcement, warning thresholds, auto-reject at limit

## Open Questions

1. **Stage count vs complexity** — Dynamic based on the Review node's analysis. Simple app → 2 stages (architecture + code), complex app → 4+ stages. The first Review node decides and encodes it in the branch structure.

2. **Execution tree depth** — Yes, branches can contain sub-branches. Max depth of 3 enforced by `spawnStageRun`. Hit depth → show warning, flatten to the deepest level.

3. **Multi-stage visualization** — Progress bar above canvas (GitHub Actions style) showing all stages with their status. The current stage is highlighted. Clicking a completed stage scrolls the canvas to its group.

4. **Review node context window** — **Resolved via compression**. Consolidation compresses to 1/3 size before passing to the gate. Gate output compressed before passing to next stage. This keeps total context manageable even for large pipelines.

5. **User modifies canvas during execution** — **Resolved via freeze**. Canvas is read-only during execution. Edits are deferred and applied only when execution completes. This prevents desync.

6. **Total LLM call pricing** — Should the system estimate cost before starting a complex pipeline and ask for confirmation? Yes — a confirmation dialog showing estimated calls × max cost before the first stage runs.
