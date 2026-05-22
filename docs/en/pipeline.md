# Pipeline System

The pipeline system is Axolotl's core execution engine. It orchestrates multi-stage code generation and verification workflows.

## Overview

A pipeline is a sequence of stages with explicit dependency ordering. Stages are executed via topological sort: stages at the same dependency level run in parallel; a stage starts only after all its dependencies complete.

```
        ┌──────────┐
        │ Receive  │
        └────┬─────┘
             │
        ┌────▼─────┐
        │  Review   │◄──── Human approval gate
        └────┬─────┘
             │
        ┌────▼─────┐
        │  Agent    │
        └────┬─────┘
             │
        ┌────▼─────┐
        │  Verify   │
        └────┬─────┘
             │
        ┌────▼─────┐
        │  Output   │
        └──────────┘
```

## Default Pipeline

The Quick Start dialog creates a 5-stage pipeline:

1. **Receive** — collects source input (text, file, URL, or project directory)
2. **Review** — generates a development plan, runs premortem/prism/postmortem checks
3. **Agent** — tool-enabled LLM that writes code using file_write/read/bash tools
4. **Verify** — runs syntax checks, test commands, quality validation
5. **Output** — produces a summary report (or writes to stdout/log)

## Stage Types

| Type | Description |
|------|-------------|
| `receive` | Input collection — text, file reference, URL fetch, or project directory listing |
| `review` | Plan generation with three checks: premortem, prism, postmortem. Always requires human approval |
| `agent` | LLM with tools (file_write, file_read, directory_read, bash, web, grep) for code generation |
| `verifier` | Validation — syntax checks, required patterns, test commands, file size limits |
| `output` | Report generation — modes: `stdout`, `log`, `summary_report` |

## Execution Flow

### Build

```bash
POST /api/schemas/{schemaId}/pipeline/build
```

Generates pipeline nodes from the schema's stage definitions. Creates Vue Flow nodes and edges ready for execution.

### Execute

```bash
POST /api/schemas/{schemaId}/pipeline/execute
```

1. Creates an `ExecutionRun` with status `running`
2. Performs topological sort of stages
3. Executes stages level by level (parallel within a level)
4. Pauses on `AWAITING_APPROVAL` (review nodes)
5. Resumes on human approval

### Status

```bash
GET /api/schemas/{schemaId}/pipeline/status
```

Returns current pipeline status, stage states, and stage outputs.

### Retry

```bash
POST /api/schemas/{schemaId}/pipeline/retry
```

Creates a child `ExecutionRun` that resumes from the first failed stage. Clears only the failed and dependent stages.

### Cancel

```bash
POST /api/schemas/{schemaId}/pipeline/cancel
```

Sets a cancellation flag; in-progress stages detect it and stop. Records status as `cancelled`.

## Review Stage Approval

Review nodes always pause the pipeline and show an approval dialog with the generated plan.

The dialog presents:
- The development plan (extracted from the review output)
- Accept — approves and continues execution
- Reject — marks the node as failed

The pipeline waits indefinitely at the review gate until the user responds.

API endpoints:

```bash
POST /api/execution/{executionId}/approve-review?nodeId={nodeId}
POST /api/execution/{executionId}/reject?nodeId={nodeId}
```

## Stage Outputs

Each stage produces outputs that can be consumed by downstream stages via `inputMapping`:

| Stage | Output Key | Description |
|-------|-----------|-------------|
| Receive | `sourceContent` | Collected input content |
| Review | `plan` | Generated development plan |
| Agent | `generatedFiles` | List of created/updated files |
| Verify | `verdict` | JSON with `status` (PASS/FAIL) and `checks` |
| Output | `report` | Generated report content |

Cross-stage references use dot notation: `{{agent.generatedFiles}}`.

## Backend Implementation

Key classes:

- `PipelineService.java` — execution orchestration, build, retry, cancel, resume
- `Pipeline.java` — model class with stages, TDD mode flag
- `Stage.java` — model class with name, type, dependencies, input mappings

The pipeline service uses `CompletableFuture` for stage execution with a 5-minute per-stage timeout. Stages are executed on virtual threads.

## Frontend Implementation

Key components:

- `PipelinePanel.vue` — sidebar component showing stage levels with status indicators
- `schemaStore.ts` — Pinia store with pipeline state (status, stages, outputs)
- `StudioView.vue` — main view with collapsible pipeline sidebar

## Configuration

The default pipeline model is `deepseek-v4-flash-free` (available via the free Zen API tier). This can be configured per-stage in the PipelinePanel.
