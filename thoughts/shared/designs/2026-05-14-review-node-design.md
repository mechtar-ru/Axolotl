---
date: 2026-05-14
topic: "Review Node — Add premortem/prism/postmortem planning checks to Axolotl Studio"
status: validated
---

## Problem Statement

AI agents write code confidently even when their plan is flawed. The Sokoban generation repeated the same `@`-missing bug across multiple executions because the agent had no mechanism to check its own approach before writing code. Existing workflow nodes either generate (agent) or verify after the fact (verifier) — nothing reviews the **plan itself** before execution.

We need a node that:
- Checks whether the plan fits the existing codebase (prism)
- Checks whether the plan repeats past failures (postmortem)
- Imagines what could go wrong and pre-emptively adds guardrails (premortem)
- Rewrites the plan with concrete constraints when issues are found
- Loops with human approval until the plan is sound

## Constraints

- Must work within the existing execution wave infrastructure
- Must reuse the existing tool-agent execution path (like verifier does)
- All three checks (premortem, prism, postmortem) run in **a single LLM call** with shared context
- Inline human approval via WebSocket events — no separate Human node required
- On max iterations: **FAIL** the node (blocks workflow)
- Must accept an upstream plan (typically from a Source or prior Agent node)

## Approach

A **new `review` node type** that:
1. Collects context from all enabled checks before calling the LLM
2. Calls the LLM once with all context + the upstream plan
3. The LLM analyzes, identifies issues, and optionally rewrites the plan
4. On rewrite → emits a WebSocket event for human approval
5. On reject → loops back (up to max iterations)
6. On approve or pass-through → outputs the reviewed plan

## Architecture

### Workflow position

```
Source → Review (generate + analyze + loop) → Agent → Verifier (test + rewrite) → Output
           ↑______human approval gate______|         ↑______auto retry loop______|

```

The review node has **two phases**: plan generation (Phase 1) and plan analysis (Phase 2). 
Phase 1 creates the initial plan from the upstream description. Phase 2 runs premortem/prism/postmortem checks and enters the approval loop.

The verifier also gets a rewrite loop: on FAIL, it can fix and retest up to N times internally.

The review node examines the **agent's system prompt** (the "plan") that flows through from upstream. It does NOT run the agent — it checks whether the agent is about to do something wrong.

### Two-phase flow inside the Review node

Phase 1 creates the plan. Phase 2 analyzes it. Both are powered by the same LLM call pattern but with different prompts.

**Phase 1 — Plan Generation**: Takes upstream description, generates a detailed plan.
- Only uses `file_read` tool (to read templates/examples if configured)
- Default prompt: "Create a detailed implementation plan for: [description]"
- Mode: always runs once on node start, not part of the loop

**Phase 2 — Plan Analysis**: Runs checks on the generated plan.
- Premortem, prism, postmortem checks all in one LLM call
- If issues found + mode=rewrite → rewrites the plan
- Enters the human approval loop

### Iteration modes

| Mode | Behavior | Max iterations | When human is shown |
|------|----------|---------------|-------------------|
| **Manual** | Generate plan → show to human → approve or give feedback → regenerate → loop | ∞ (no limit) | Every iteration — human must approve before proceeding |
| **Auto** | Generate plan → analyze → auto-rewrite if needed → re-analyze → repeat until PASS or max hit | Configurable (default 3) | Never — fully automatic |
| **Hybrid** | Auto-analyze and auto-rewrite up to N iterations, then show final gate for approval | Configurable auto (default 3) + 1 manual gate | After auto iterations complete |

### Single LLM call structure

The review node builds one structured prompt with sections:

```
[PLAN]
<the upstream agent's system prompt>

[PREMORTEM — always active]
Context: None needed (pure reasoning)
Task: Imagine this plan failed. List the 3-5 most likely failure scenarios.
Output: Concrete constraints to prevent each scenario.

[PRISM — if enabled]
Context: [codebase scan results — grep matches, file excerpts, graph query results]
Task: Does this plan fit the existing codebase patterns? 
Output: Pattern violations + suggested corrections.

[POSTMORTEM — if enabled]
Context: [execution history, milestone artifacts, known issues]
Task: Does this plan repeat past mistakes?
Output: Past failures + constraints to prevent recurrence.

YOUR OUTPUT:
If issues found → Return REWRITTEN plan with guardrails prepended + list of findings
If no issues → Return: PASS
```

### Human approval flow

1. Review node calls LLM → LLM returns REWRITTEN plan
2. Node **pauses execution**, emits `review_awaiting_approval` WebSocket event with:
   - Original plan
   - Rewritten plan (with diff highlighting)
   - List of findings (per check)
   - Current iteration / max iterations
3. Studio UI shows an approval dialog (inline in the canvas or as a banner)
4. User clicks Approve → node resumes, COMPLETES with rewritten plan
5. User clicks Reject → node resumes, loops back to step 1 (re-run LLM)
6. User clicks Reject on max iteration → node FAILS

## Components

### Frontend

#### 1. `ReviewBlock.vue` — new VueFlow node component
- Maps node type `review` to a visual block
- Amber/warning color (`#f59e0b`) — distinct from Think (blue), Verify (purple), Act (orange)
- Clipboard/checklist icon
- Extends `BlockBase.vue`
- Shows iteration badge ("2/3") during review loop

#### 2. BlockPalette — add "Review" entry
- Type: `review`
- Label: "Review"
- Color: `#f59e0b` (amber)
- Icon: clipboard with checklist SVG
- Insert after Receive, before Think

#### 3. BlueprintView — register review node type
- Import `ReviewBlock`
- Add `review: ReviewBlock` to `nodeTypes`
- Add `review` to compatible block types

#### 4. BlockConfigPanel — review config section
When `blockType === 'review'`:
- **Phase 1: Plan Generation toggle** — on/off. When on, generates the plan from upstream input (default ON). When off, expects plan from upstream.
- **Phase 2: Checks section:**
  - ☑ Premortem (toggle, default ON)
  - ☐ Prism (toggle, default OFF)
  - ☐ Postmortem (toggle, default OFF)
- **Mode selector:** `manual` | `auto` | `hybrid`
  - Manual: ∞ iterations, human always gates
  - Auto: configurable max iterations, no human
  - Hybrid: configurable auto iterations + 1 final human gate
- **Max auto iterations:** number input (default 3, hidden when mode=manual)
- **Target node ID:** optional select of downstream agent node (auto-detected from edges if left empty)
- No agentType selector (always `"review"`)
- No tool config (tools are auto-selected based on checks)

#### 5. Execution UI — approval dialog

The approval dialog is the core human interaction point. It supports three actions: Accept, Suggest & Regenerate, and Edit & Accept.

**Layout:**
```
┌────────────────────────────────────────────────────────────┐
│  ▲ Plan Review — Iteration 2 of 3 (Manual)           [✕]  │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌─ Final Plan ──────────────────────────────────────────┐ │
│  │  [editable textarea when in edit mode]                 │ │
│  │                                                        │ │
│  │  1. Create Sokoban with pygame, 5 levels               │ │
│  │  2. Each level: 15x15 grid, walls #, boxes O,          │ │
│  │     targets *, player @                                 │ │
│  │  3. Arrow keys move player, undo with Z                │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                            │
│  ┌─ Premortem Findings ────────────────────────────────┐  │
│  │  🔴 HIGH  Level '@' position must be set             │  │
│  │  🟡 MED   Undo with overlapping box movements        │  │
│  │  ℹ️ INFO  5 levels may be too many for v1            │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌─ Feedback History ──────────────────────────────────┐  │
│  │  #1: "Add undo support with Z key" — applied         │  │
│  │  #2: "Add level transition animation" — not applied  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌─ Your Feedback ──────────────────────────────────────┐ │
│  │  [What would you change?                       ]      │ │
│  │  [+ Add more]                                          │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  [✏️ Edit Plan]  [↻ Suggest & Regenerate]  [✓ Accept]│ │
│  └──────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

**Three action paths:**

| Action | Button | What happens | Loop behavior |
|--------|--------|-------------|--------------|
| **Accept** | `✓ Accept` | Node completes with current plan, flows to Agent | Exit loop |
| **Suggest & Regenerate** | `↻ Suggest & Regenerate` | Collects feedback text → POST to `/api/execution/{id}/feedback` → backend re-runs LLM with feedback → new plan + findings → new WS event → dialog updates | Stay in loop, iteration++ |
| **Edit Plan** | `✏️ Edit Plan` | Plan textarea becomes editable → user modifies directly → Accept sends modified plan as-is | Exit loop (no re-generation) |

**Feedback field behavior:**
- Multi-line textarea, placeholder "What would you change about this plan?"
- "+ Add more" appends another input row
- Past feedback shown as scrollable history with status (applied / not applied)
- Feedback is aggregated across iterations — each regeneration appends past feedback + new suggestions

**Mode-specific behavior:**

| Mode | When dialog is shown | Feedback field | Edit Plan | Auto-advance on PASS |
|------|---------------------|----------------|-----------|---------------------|
| **Manual** | After every Phase 2 iteration | Always shown | Always | No — human always approves |
| **Hybrid** | After auto iterations complete (gate step) | Shown at gate | Shown at gate | Yes — auto-pass if no findings after auto iterations |
| **Auto** | Never — no human interaction | Hidden | Hidden | Yes — completes or FAILs after max iterations |

**WebSocket events sent per iteration:**

```json
{
  "type": "review_awaiting_approval",
  "nodeId": "review-1",
  "schemaId": "...",
  "executionId": "...",
  "originalPlan": "...",
  "rewrittenPlan": "...",
  "findings": [...],
  "iteration": 2,
  "maxIterations": 3,
  "mode": "manual",
  "feedbackHistory": [
    {"text": "Add undo with Z", "applied": true},
    {"text": "Add animations", "applied": false}
  ]
}
```

**API endpoints:**

| Endpoint | Purpose |
|----------|---------|
| `POST /api/execution/{id}/approve?nodeId={nodeId}` | Resume with rewritten plan |
| `POST /api/execution/{id}/reject?nodeId={nodeId}` | Loop back or FAIL |
| `POST /api/execution/{id}/feedback?nodeId={nodeId}` | Submit feedback, trigger re-generation with `{feedback: "...", history: [...]}` |

#### 6. TimelineView — review result display
- Amber block color
- Show findings count, iteration count
- Expand to show per-check details

### Backend

#### 1. NodeExecutor — new `"review"` branch
Add after the `"verifier"` branch:

```java
} else if ("review".equals(node.getType())) {
    result = executeReviewNode(node, schemaId, resolvedModel);
}
```

#### 2. `executeReviewNode()` method

1. **Read upstream plan**: Collects the result from upstream node(s) — typically the system prompt or task description that will flow to the agent
2. **Determine enabled checks**: From `node.getData().getConfig()`:
   - `checks.premortem` (boolean, default true)
   - `checks.prism` (boolean, default false)
   - `checks.postmortem` (boolean, default false)
   - `mode` (string, "pass-through" or "rewrite")
   - `maxIterations` (number, default 3)
3. **Collect context per check**:
   - If prism: run codebase scan (grep for relevant patterns, query Neo4j graph)
   - If postmortem: fetch execution history + milestone artifacts
4. **Build structured prompt** with plan + all enabled check contexts
5. **Call LLM** with `agentType = "review"`, tools auto-selected based on checks
6. **Parse result**: PASS or REWRITTEN plan + findings list
7. **If mode=pass-through**: Complete with findings as metadata
8. **If mode=rewrite + issues found**: 
   - Emit `review_awaiting_approval` WebSocket event
   - Pause execution (wait for external approval/reject HTTP callback)
   - On approve → COMPLETED with rewritten plan
   - On reject → loop back to step 5 (call LLM again) or FAIL if max iterations
9. **If mode=rewrite + no issues**: Complete with PASS + plan unchanged

#### 3. WebSocket events

New event type: `review_awaiting_approval`

```json
{
  "type": "review_awaiting_approval",
  "nodeId": "review-1",
  "schemaId": "...",
  "executionId": "...",
  "originalPlan": "...",
  "rewrittenPlan": "...",
  "findings": [
    {"source": "premortem", "severity": "HIGH", "description": "Level missing @", "suggestion": "Add constraint: levels must include @"}
  ],
  "iteration": 1,
  "maxIterations": 3
}
```

#### 4. API endpoints for approval

- `POST /api/execution/{id}/approve?nodeId={nodeId}` — resumes execution with rewritten plan
- `POST /api/execution/{id}/reject?nodeId={nodeId}` — loops back or fails

These endpoints modify the execution state in SchemaService, allowing the paused review node to proceed.

### Execution state management

The review node's pause/resume requires tracking "paused" nodes in the execution state. Current state enum:

```
PENDING → RUNNING → COMPLETED | FAILED
```

Add `AWAITING_APPROVAL`:
```
PENDING → RUNNING → AWAITING_APPROVAL → RUNNING → COMPLETED | FAILED
```

When a node enters AWAITING_APPROVAL and the approve endpoint is called, it transitions back to RUNNING and the node executor picks up where it left off.

## Data Model

### Node config

```json
{
  "config": {
    "generatePlan": true,
    "checks": {
      "premortem": true,
      "prism": false,
      "postmortem": false
    },
    "mode": "manual",
    "maxAutoIterations": 3
  }
}
```

### Node result (COMPLETED)

```json
{
  "plan": "the reviewed/rewritten plan string",
  "review": {
    "verdict": "REWRITTEN",
    "findings": [
      {"source": "premortem", "severity": "HIGH", "description": "Level @ missing", "suggestion": "Add constraint: levels must include @"}
    ],
    "iterations": 2,
    "approvedBy": "human",
    "feedbackHistory": [
      {"text": "Add undo with Z", "iterationApplied": 2, "result": "applied"},
      {"text": "Add animations", "iterationApplied": null, "result": "not_applied"}
    ]
  }
}
```

### Verifier node config additions

```json
{
  "config": {
    "checks": {
      "syntaxCheck": true,
      "requiredPatterns": ["@"],
      "testCommand": "python3 -m py_compile {{filepath}}",
      "premortem": true
    },
    "rewriteOnFail": true,
    "maxRewriteRetries": 3
  }
}
```

### Verifier node result (COMPLETED with rewrite)

```json
{
  "verdict": "PASS_AFTER_REWRITE",
  "premortem": {
    "predictions": [
      {"issue": "Missing @ in level", "confirmed": true, "fixed": true}
    ]
  },
  "checks": [
    {"name": "syntax", "passed": true},
    {"name": "premortem", "passed": true, "issuesFound": 1, "issuesFixed": 1}
  ],
  "rewriteRetries": 1,
  "summary": "Syntax OK. 1 premortem issue found and fixed."
}
```

### Output node config (summary report)

```json
{
  "config": {
    "mode": "summary_report",
    "reportPath": "pipeline-report.md",
    "includeReview": true,
    "includeFiles": true,
    "includeVerification": true,
    "includeMetrics": true
  }
}
```

## Verifier Rewrite Loop

The existing Verifier node gets an upgrade: when a check fails, it can internally loop — fix the code and re-test — instead of failing immediately.

### Config additions

```
┌─ Verifier ──────────────────────────────────────┐
│  Checks:                                         │
│  ☑ Syntax    ☐ Required Patterns                 │
│  ☐ Test Command   ☐ Max File Size                │
│  ☑ Premortem (predict runtime failures)          │
│                                                   │
│  [✓] Rewrite on FAIL (up to [3] retries)         │
└──────────────────────────────────────────────────┘
```

### Internal flow

```
1. Read generated file(s)
2. Run premortem: predict what could fail
3. Run configured checks (syntax, patterns, test command)
4. If all PASS → COMPLETED
5. If any FAIL + rewrite on → collect errors → call LLM to fix code → rewrite file → go to step 2
6. If max retries hit without all passing → FAILED
```

The LLM receives: the original prompt, current errors, and the file content. It produces a fixed version. The node rewrites the file with the fix and re-runs checks.

### Premortem in Verifier

When premortem is enabled:
- Before running syntax/test, the LLM reads the code and predicts failure scenarios
- After running tests, it compares: were the predictions correct? Any unexpected failures?
- Both predicted and unpredicted issues are included in the final report

This provides a **before vs after** picture: "We found 3 issues (2 predicted, 1 unexpected) — 2 fixed, 1 remaining."

## Output Node — Summary Report Mode

The Output node gets a new mode: `summary_report`. When selected:

### Config

```
┌─ Output ────────────────────────────────────────┐
│  Mode: [Simple ▼] [Summary Report]               │
│                                                   │
│  When Summary Report:                             │
│  - Target path: [auto / custom]                  │
│  - Filename: [pipeline-report.md]                │
│  - Include:                                      │
│    ☑ Review findings                             │
│    ☑ Generated files list                        │
│    ☑ Verification results                        │
│    ☑ Execution metrics (time, tokens)             │
└──────────────────────────────────────────────────┘
```

### Report content

The Output node collects results from ALL upstream nodes (not just immediate predecessor) and assembles a markdown report:

```markdown
# Pipeline Summary

## Plan (from Review-1)
- Iterations: 3 (2 auto, 1 manual approval)
- Final plan: ...

## Agent (from Agent-1)
- Model: big-pickle
- Generated files:
  - sokoban.py (380 lines)
  - levels.py (120 lines)

## Verification (from Verifier-1)
- Premortem predictions: 3, Confirmed: 2, Fixed: 2
- Syntax check: PASS
- Test command: PASS
- Rewrite iterations: 1

## Execution
- Total time: 47s
- Tokens used: 19,383
```

This information is available from the execution state — all node results are stored in `SchemaService.executionResults`. The Output node queries this map to build the report.

## Quick Start — Replacing the Live Tab

The Live tab is currently a hidden, GAME-only chat flow (Concept → Review → Output). Instead of a separate tab, the Blueprint canvas gets a **Quick Start** button.

### How it works

1. User opens Studio at `/app/:id`
2. In the empty canvas toolbar, there's a "Quick Start" button alongside "Run"
3. Clicking it shows a modal: *"Describe what you want to build..."*
4. User types: *"A Sokoban game with 5 levels and undo support, written in Python using pygame"*
5. The system auto-generates the full pipeline as nodes on the canvas:
   ```
   Source "Game Description" → Review (manual mode) → Agent → Verifier → Output (summary)
   ```
6. The Review dialog immediately opens for the first planning iteration
7. User iterates on the plan → approves → pipeline runs

### Technical approach

The Quick Start button:
1. Creates a Source node with the user's description as its output text
2. Creates a Review node (mode=manual, premortem=on, plan generation=on)
3. Creates an Agent node (model=big-pickle, agentType=coder, tools=file_write)
4. Creates a Verifier node (syntax=on, premortem=on, rewrite=on, 3 retries)
5. Creates an Output node (mode=summary_report)
6. Creates the 5 edges connecting them in sequence
7. Calls `updateSchema()` to persist
8. Triggers execution

### Live tab deprecation

- The Live tab remains visible for backwards compatibility with existing GAME schemas
- New schemas default to the Blueprint tab
- In a future version, Live tab content becomes a "history" view that shows past Quick Start runs
- The legacy Live chat flow (Concept → Review → Output) is replaced by the Review node's manual mode dialog

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Prism enabled but codebase scan fails | Postmortem note in findings, premortem + postmortem still run |
| Postmortem enabled but no history | Graceful: "No past execution data found" — other checks still run |
| LLM call fails on retry (reject) | Node FAILED after max iterations |
| Human timeout (no approve/reject) | Node stays AWAITING_APPROVAL indefinitely — manual cancel on UI |
| No upstream plan available | Review node FAILS with "No plan to review" |
| Mode=pass-through, issues found | Node COMPLETES with findings in metadata — agent receives unchanged plan |

## Testing Strategy

**Frontend:**
- ReviewBlock.vue renders with correct color/icon/badge
- BlockPalette includes Review entry
- ConfigPanel shows correct fields for review mode
- Approval dialog renders diff view and captures user action

**Backend:**
- `executeReviewNode()` with no checks enabled → always PASS
- `executeReviewNode()` with premortem only → returns findings
- `executeReviewNode()` with rewrite mode → returns rewritten plan
- Approval endpoint transitions from AWAITING_APPROVAL → COMPLETED
- Reject endpoint triggers re-execution (or FAIL at max iterations)
- Prism check: mock codebase scan → LLM receives scan context
- Postmortem check: mock execution history → LLM receives history context

**E2E:**
- Full workflow: Source → Review (premortem) → Agent → Verifier → Output
- Simulate human approval via API
- Verify rewritten plan reaches agent
- Verify reject + retry loop up to max iterations

## Open Questions

1. **Review node with generation disabled** — When `generatePlan=false`, the review expects the plan from upstream (e.g., from a Source node with a pre-written plan). This is useful for workflows where the plan is provided externally.

2. **Quick Start UX** — Should Quick Start create the pipeline on the canvas (visible, editable) or run it invisibly? Current design: visible on canvas so the user can inspect and customize. Future option: "instant run" mode that creates the pipeline and immediately executes.

3. **Sokoban template replacement** — The old 3-node template (Source → Think → Act) should be replaced with the 5-node pipeline: Source → Review → Agent → Verifier → Output. Need to update `frontend/src/templates/index.ts` and the Sokoban template definition.

4. **Live tab future** — Hide Live tab for new schemas, keep visible for existing GAME schemas. In v2, Live tab becomes a "history" view.

5. **Should approved rewritten plans be stored as artifacts?** — Yes, in v2. For v1, the rewritten plan is ephemeral (part of execution state).
