# Plan 45: Multi-Phase Draft Pipeline

> **Status:** Planned  
> **Phase:** Phase 2 (Design & Drafting) per ROADMAP.md  
> **Depends on:** Pipeline unification (41-pipeline-unification) — canvas nodes ARE the pipeline

---

## 1. Problem

The current Review node is overloaded — it generates a plan AND analyzes it (premortem/prism/postmortem) AND optionally rewrites, all in one LLM call. The output is a single unstructured text blob. The downstream implementation agent gets vague context with no structured decomposition.

## 2. Solution

Replace the monolithic Review node with a **series of focused draft stages**, each producing a structured artifact on disk. An approval gate precedes implementation. The sequence:

```
Source Prompt
  → Draft: Broad Spec (spec.md, 1 A4 page)
  → Draft: Detailed Plan (plan.md, per-section breakdown)
  → Draft: UI (openui.yaml via OpenUISpec)
  → Draft: Backend Modules (modules.md, api-contracts.md)
  → Approval Gate (Review node, manual or auto)
  → Agent: Implementation (reads all artifacts, writes code)
  → Verify → Output
```

## 3. New Node Type: `draft`

### 3.1 Config

```yaml
type: draft
draftType: spec | plan | ui | backend
model: <provider/model>
systemPrompt: >-
  <auto-populated from draftType template, overridable>
outputFile: <path relative to targetPath>
```

```typescript
interface DraftNodeData extends NodeData {
  config: {
    draftType: 'spec' | 'plan' | 'ui' | 'backend'
    model: string
  }
}
```

### 3.2 Draft Type Behaviors

| Draft Type | System Prompt | Output File | Max Tokens | Tool Access |
|---|---|---|---|---|
| `spec` | "Write a 1-page functional spec: purpose, users, features, constraints, success criteria" | `targetPath/.axolotl/spec.md` | 4096 | file_write only |
| `plan` | "Expand each spec section into implementation breakdown: components, routes, data flow, file structure" | `targetPath/.axolotl/plan.md` | 8192 | file_write, file_read (read spec.md) |
| `ui` | "Design the UI in OpenUISpec format. Define component tree, props, states, events." | `targetPath/.axolotl/openui.yaml` | 8192 | file_write, file_read, directory_read |
| `backend` | "Design backend modules: services, data models, API endpoints, DB schema" | `targetPath/.axolotl/modules.md` | 8192 | file_write, file_read, directory_read |

All draft artifacts write to `.axolotl/` subdirectory under the schema's `targetPath` for clean separation from generated code.

### 3.3 Execution

`DraftNodeStrategy.java` extends `BaseNodeStrategy`:
1. Read source data (from upstream nodes / source data / previous artifacts)
2. Populate a focused system prompt based on `draftType`
3. Call LLM via `LlmService` (Agent node with no tools — just text generation)
4. Write structured output to `outputFile`
5. Return artifact path and summary in node result

No tool calls needed for draft nodes — they produce text artifacts, not code. This keeps them fast and cheap.

## 4. Approval Gate

The existing **Review node** is repurposed as the approval gate:

- **Before drafts:** not present (no plan to review yet)
- **After all drafts complete:** Review node shows all 4 draft artifacts side-by-side
- **Actions:** Accept (proceed to implementation) / Suggest & Regenerate (re-run all drafts with feedback) / Reject (fail pipeline)

### Auto-Approve

Schema-level setting `autoApproveDrafts: boolean` (default `false`):

- When `true`: Review node auto-accepts after drafts complete (no human pause). Equivalent to the old Review's Auto iteration mode.
- When `false`: Review pauses for human approval, showing all artifacts in the ApprovalDialog.

Stored on `WorkflowSchema`:
```java
private boolean autoApproveDrafts = false;
```

## 5. Backend Changes

### 5.1 New Files

| File | Purpose |
|---|---|
| `backend/src/main/java/.../service/strategy/DraftNodeStrategy.java` | Handles all 4 draft types |
| `backend/src/main/java/.../service/strategy/DraftResult.java` | Result model (path, type, summary) |

### 5.2 Modified Files

| File | Change |
|---|---|
| `NodeRouter.java` | Register `draft` → `DraftNodeStrategy` routing |
| `NodeData.java` | Add `draftType` field to config |
| `SchemaService.java` | Build draft stages in `createStagesFromNodes()` — sequential edges enforced |
| `ReviewNodeStrategy.java` | Accept draft artifacts as input; auto-pass when `autoApproveDrafts=true` |
| `ReviewApprovalDialog.vue` | Show draft artifacts (4 cards with expandable markdown) alongside plan |
| `AgentController.java` | Expose `autoApproveDrafts` on schema CRUD |
| `PipelineService.java` | Support stage-level sequential dependency for drafts |

### 5.3 Quick Start Template

The default pipeline template changes from:
```
Receive → Review → Agent → Verify → Output
```

To:
```
Receive → Draft(spec) → Draft(plan) → Draft(ui) → Draft(backend) → Review → Agent → Verify → Output
```

When a schema has `autoApproveDrafts=true`, the Review node auto-passes.

When `pipeline.tddEnabled=true`, extends as before: each `impl` stage wraps `test → verify-test → impl → verify`.

## 6. Frontend Changes

### 6.1 Block Palette

Add `draft` to the block palette. On drop/paste, user selects `draftType` from a dropdown (spec/plan/ui/backend).

### 6.2 BlockConfigPanel

When `blockType === 'draft'`:
- Show `draftType` selector (spec | plan | ui | backend) with descriptions
- Hide fields that don't apply: checks, mode, iterations, tools (draft has no tools)
- Model selector (same as agent)

### 6.3 Draft Node Canvas Block

`DraftBlock.vue`:
- Compact visual with `draftType` badge (color-coded)
- Shows output artifact path when available
- No tool call UI (draft has no tools)
- Expand/collapse for full output text

### 6.4 Schema Properties Panel

Add checkbox: "Auto-approve drafts" (bound to `schema.autoApproveDrafts`)

### 6.5 ReviewApprovalDialog

When review node is in draft-gate mode:
- Show 4 collapsible sections: Spec, Plan, UI Design, Backend Design
- Each section shows full markdown content
- Accept = proceed to implementation
- Suggest & Regenerate = feedback applied to ALL drafts (re-run from Draft(spec))

## 7. OpenUISpec Integration

The UI draft (`draftType: ui`) generates a valid `openui.yaml` file:

```yaml
name: Generated App UI
version: 1.0.0
description: <from spec>
components:
  Layout:
    description: Root layout component
    props:
      theme:
        type: string
        enum: [light, dark]
        default: light
  LoginForm:
    description: Authentication form
    props:
      onSubmit:
        type: function
      error:
        type: string
  # ... etc
```

The implementation agent reads `openui.yaml` and generates actual component code matching each component definition. This ensures UI output is structure-faithful to the design.

**Future:**
- Validator node that checks generated code against `openui.yaml` contracts
- Auto-generate Storybook stories from `openui.yaml`

## 8. Data Flow

```
1. User creates schema (Quick Start / blank)
   → autoApproveDrafts: false (default)
   → Pipeline: Receive → Draft×4 → Review → Agent → Verify → Output

2. User runs pipeline
   → Draft(spec) writes spec.md
   → Draft(plan) reads spec.md, writes plan.md
   → Draft(ui) reads spec.md + plan.md, writes openui.yaml
   → Draft(backend) reads all, writes modules.md
   → Review: pauses for approval (or auto-passes)
   → Agent: reads all artifacts from .axolotl/, writes code
   → Verify → Output
```

## 9. Implementation Batches

### Batch 1: Backend — DraftNodeStrategy + routing
- [ ] `DraftResult.java` model
- [ ] `DraftNodeStrategy.java` for all 4 types
- [ ] Register in `NodeRouter`
- [ ] `NodeData.java` — add `draftType` to config

### Batch 2: Backend — Approval gate + Quick Start template
- [ ] `WorkflowSchema.autoApproveDrafts` field
- [ ] `ReviewNodeStrategy` — draft-gate mode (accept draft artifacts as input)
- [ ] `SchemaService.createStagesFromNodes()` — draft stages sequential
- [ ] Update Quick Start template: Receive → Draft×4 → Review → Agent → Verify → Output
- [ ] `AgentController` — expose `autoApproveDrafts`

### Batch 3: Frontend — Draft block
- [ ] `DraftBlock.vue` (canvas node)
- [ ] Block palette registration
- [ ] `BlockConfigPanel` — draft type selector, hide irrelevant fields
- [ ] Studio view: auto-approve toggle in SchemaPropertiesPanel
- [ ] `BlueprintView` — add edge creation for Draft→next nodes

### Batch 4: Frontend — Approval dialog
- [ ] `ReviewApprovalDialog` — draft-gate mode (4 artifact cards)
- [ ] Accept/Suggest/Reject actions

### Batch 5: PipelineService — Stage dependencies
- [ ] Ensure sequential stage execution for draft chain
- [ ] Cross-stage artifact passing (`Stage.inputMapping` — Draft output files flow to downstream Agent)
- [ ] Retry from failed draft stage (keep completed drafts, re-run from failed one)

### Batch 6: Tests
- [ ] `DraftNodeStrategyTest` — all 4 types
- [ ] `PipelineServiceTest` — draft chain in topological sort
- [ ] Frontend component tests — DraftBlock, approval dialog
- [ ] E2E — full draft → approval → implementation flow

### Batch 7: OpenUISpec integration
- [ ] Validator for generated code vs `openui.yaml`
- [ ] (Optional) Storybook story generator from `openui.yaml`

## 10. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Draft chain is slow (4 sequential LLM calls) | High | Medium | Each draft is a single LLM call with modest token budget. Total ≈ 2x a single Review call — acceptable for structured output. Consider parallelizing UI + Backend drafts (they depend only on spec+plan, not each other). |
| OpenUISpec output is wrong/incomplete | Medium | Medium | Implementation agent reads spec as guidance, not enforcement. Validator catches mismatches. User can manually edit `openui.yaml` between draft and impl. |
| Users want custom draft types | Low | Low | `draftType` is an enum now, easily extensible. Future: custom draft type via system prompt override. |
| Auto-approve means no human checks | Medium | Medium | Auto-approve is opt-in per schema. Even with auto-approve, failed verification blocks output. |

## 11. Future Work (Post-Implementation)

- **Parallel drafts**: UI + Backend drafts can run in parallel (no cross-dependency between them). Requires stage-level branch parallelism.
- **Custom draft types**: Let users define their own draft stages with custom system prompts.
- **Diff view on drafts**: Show versioned diffs when regenerating after feedback.
- **OpenUISpec component library registry**: Axolotl ships with OpenUISpec definitions for common component libraries, making the UI draft more accurate.
