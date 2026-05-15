---
date: 2026-05-15
topic: "Execution Resilience"
status: draft
---

## Problem Statement

We need executions to survive token limits, server crashes, and browser closes without re-running already completed nodes.

**Why it matters:** long-running workflows (codegen, test harnesses, multi-step refactors) currently lose progress and waste tokens when interrupted.

## Constraints

- Must use existing SQLite operational DB (DbConfig) and follow repository patterns already in repo.
- No changes to Neo4j schema required for run persistence — persistence is additive in SQLite.
- Keep existing in-memory executionHistory for backward compatibility.
- Avoid changing node semantics; resume must skip only nodes with identical config hash.

## Approach

We persist per-Run and per-Node execution metadata in three new SQLite tables and provide a resume flow that creates a child run inheriting completed nodes.

**Key ideas:**
- Each Run = one user click. Resumes create a child Run that references parent via resumes_from.
- Node-level persistence (NodeExecution) stores configHash, input/output snapshots, tokens, timings, and files written.
- Checkpoints saved after each topological wave allow recovery at wave granularity.
- Resume compares current node configHash to latest completed NodeExecution; if equal, node is skipped and its output injected into runtime results map.

## Architecture

High-level components:

- **ExecutionRepository (SQLite)** — raw JDBC repository responsible for runs, node executions, checkpoints.
- **SchemaService (execution orchestration)** — create runs, create nodeExecution placeholders, save checkpoints, implement resumeExecution().
- **NodeExecutor (per-node)** — update NodeExecution records (status, tokens, output, files) at deterministic commit points.
- **WebSocket / UI** — new `paused` event and resume banner; Timeline shows runs and separations.

## Components and Responsibilities

- **ExecutionRun (model):** id, schemaId, status, mode, tokens, cost, timestamps, resumesFrom
- **NodeExecution (model):** id, runId, nodeId, nodeName, nodeType, status, tokens, duration, toolCalls, error, input/output summaries, filesWritten, configHash
- **ExecutionCheckpoint (model):** id, runId, completedNodeIds[], currentWave, createdAt
- **ExecutionRepository:** create/update/get runs, node executions, checkpoints; table creation on startup
- **SchemaService (changes):** inject ExecutionRepository; computeConfigHash(node,schema); createRun + nodeExecution rows; save checkpoints; resumeExecution() implementation; update run status on pause/complete
- **NodeExecutor (changes):** commit node-level updates at stable points (post-tool-parsing, after file_write success, on FAIL); report tokens/duration; handle marking node as failed and call executionRepository.updateNodeExecution()

## Data Flow

1. User clicks Run → SchemaService.executeSchema() creates ExecutionRun and NodeExecution rows.
2. Execution proceeds in waves. After each CompletableFuture wave completes, SchemaService saves an ExecutionCheckpoint of completed node IDs.
3. NodeExecutor persists node results as soon as the node finishes (success/fail/skip). For skipped nodes (on resume) NodeExecutor reads stored outputSummary and injects into runtime results map.
4. On token-limit error (or similar recoverable LLM error) SchemaService updates run status to `paused` and emits WebSocket `paused` event with runId and completedNodes.
5. User resumes via UI → SchemaService.resumeExecution(schemaId) creates child run, copies/skips nodes where configHash matches, and executes remaining nodes.

## Error Handling

- Token exhaustion / provider errors: mark run `paused` with error details; save the last checkpoint. Emit `paused` websocket event.
- Node failures: persist NodeExecution with status `failed` including error; if error is transient and run is resumable, leave run paused.
- Database errors: log and fail fast for run creation; existing in-memory execution continues but with a warning in logs and UI (best-effort persistence).

## Testing Strategy

- Unit tests for ExecutionRepository (create/read/update) — existing ExecutionRepositoryTest pattern.
- SchemaServiceResilienceTest: computeConfigHash behavior, create+resume flow, skipped-node injection, checkpoint saving, run status transitions.
- Integration smoke: run end-to-end Sokoban template execution, intentionally exceed token limit to trigger pause, then resume and verify files generated and no duplicate node runs.

## Open Questions

- UI resume UX: where to place resume button — inline in Timeline or in Studio top bar? (Recommend Timeline run separator with resume CTA.)
- Retention policy for persisted runs & logs: design proposes separate scheduled cleanup (14-day TTL) — implementation detail to schedule a cleanup job.

--

I'm proceeding to create this design doc in the repo. Per your instruction I will NOT spawn subagents/planner in this session. Say "go" if you want me to (a) generate the Batch 2 change list as patch-ready diffs I can hand off to an implementer, or (b) spawn the planner to create a microtask plan automatically.
