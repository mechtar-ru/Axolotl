# Execution Resilience — Implementation Plan

Date: 2026-05-15
Scope: implement persisted execution runs, node-level persistence, checkpoints, resume flow using SQLite ExecutionRepository already present in repo.

Assumptions (stated):
- Batch 1 (models + ExecutionRepository) is already implemented and passing tests.
- We must NOT change Neo4j schema; persistence is additive in SQLite.
- In-memory executionHistory remains unchanged for backward compatibility.
- All changes are backend-only in this rollout; frontend ResumeBanner and Timeline integration follow in Batch 3.

Overview
--------
We implement four batches:

- Batch 1 — Foundation: (ALREADY DONE)
  - Models: ExecutionRun, NodeExecution, ExecutionCheckpoint
  - ExecutionRepository (raw JDBC) with table creation and CRUD

- Batch 2 — Core wiring (this batch implements the essential runtime persistence and resume behavior)
  - Persist node lifecycle events from NodeExecutor to NodeExecution rows
  - Ensure SchemaService creates runs/node placeholders, saves checkpoints after each wave, and respects skipped-node injection during resume
  - Emit WebSocket `paused` event and mark run PAUSED on recoverable LLM errors (402/429)
  - Expose controller endpoints (get runs, get paused run, resume) already exist — ensure they return persisted data

- Batch 3 — Frontend & UX (separate follow-up)
  - ResumeBanner.vue, API wiring, Timeline run separators, Resume flow UI

- Batch 4 — Tests & Cleanup
  - Unit tests, integration smoke, DB migration safety checks, rollback plan

Micro-tasks (Batch 2) — actionable file-per-task list
-------------------------------------------------

Priority order: 2.1 → 2.2 → 2.3 → 2.4 → tests

2.1 NodeExecutor: persist node updates (smallest, low-risk)
- Files to modify:
  - backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java
- Changes:
  - After node completes (success/fail/skip), call executionRepository.updateNodeExecution(...) with tokens/duration/toolCalls/error/outputSummary and filesWritten.
  - On node start, set NodeExecution.status="running" via executionRepository.updateNodeExecution(...)
  - If executionRepository.updateNodeExecution signature lacks filesWritten, add an overloaded method `updateNodeExecutionWithFiles(...)` or extend method to accept filesWritten (backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java).
  - Ensure NodeExecutor catches SQL exceptions and logs but does not break runtime (best-effort persistence).
- Tests:
  - Add backend/src/test/java/com/agent/orchestrator/service/NodeExecutorPersistenceTest.java
  - Use a TempDir and DbConfig to create ExecutionRepository; mock LlmService/Provider to simulate node execution finishing; assert node row updated.
  - Run: cd backend && mvn -Dtest=NodeExecutorPersistenceTest test
  - Estimate: 3–4 hours

2.2 SchemaService: fix executeWorkflow ordering and checkpoint saving
- Files to modify:
  - backend/src/main/java/com/agent/orchestrator/service/SchemaService.java
- Changes:
  - Ensure resumeExecution() injects skipped node outputs into nodeExecutor.getNodeResults() before calling executeWorkflow OR ensure executeWorkflow does NOT clear results map early. Fix ordering bug where `nodeExecutor.getNodeResults().remove(schema.getId())` is called prematurely.
  - Create ExecutionRun at start of executeSchema() (if not already) and create NodeExecution placeholders with computeConfigHash() values.
  - After each wave (after CompletableFuture.allOf(...).join()), call saveCheckpoint(schema, runId, completedCount, waveNum) which uses ExecutionRepository.saveCheckpoint().
  - On completion: executionRepository.updateRunCompleted(runId, "completed", totalTokens, estimatedCost)
  - On user cancel: executionRepository.updateRunStatus(runId, "cancelled", "Cancelled by user")
  - On recoverable LLM provider error (HTTP 402/429 detection), call executionRepository.updateRunStatus(runId, "paused", errorMessage) and call executionWebSocketHandler.sendPaused(runId, completedNodes, totalNodes).
- Tests:
  - backend/src/test/java/com/agent/orchestrator/service/SchemaServiceResilienceTest.java (exists — extend to cover persisted run creation, checkpoint saving, resume behavior)
  - Run: cd backend && mvn -Dtest=SchemaServiceResilienceTest test
  - Estimate: 6–8 hours

2.3 ExecutionWebSocketHandler: emit paused events
- Files to modify:
  - backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java
- Changes:
  - Add/verify method sendPaused(runId, completedNodes, totalNodes) to broadcast the paused event with {type: "paused", runId, completedNodes, totalNodes} to subscribed clients.
  - Ensure SchemaService calls it on pause (see 2.2).
- Tests:
  - Add a unit test that webSocketHandler sends correct JSON (mock session). Use existing websocket test utilities.
  - Run: cd backend && mvn -Dtest=ExecutionWebSocketHandlerTest test
  - Estimate: 2 hours

2.4 ExecutionRepository: minor signature change if needed for filesWritten
- Files to modify:
  - backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java
- Changes:
  - If updateNodeExecution() cannot accept `filesWritten`, add overloaded method `updateNodeExecutionWithFiles(id, status, outputSummary, tokensUsed, durationMs, toolCalls, filesWritten, error)` and implement SQL update for files_written column.
  - Ensure createNodeExecution() and updateNodeExecution() both handle nulls and use prepared statements.
- Tests:
  - ExecutionRepositoryTest already exists — extend a test to assert files_written round-trip.
  - Run: cd backend && mvn -Dtest=ExecutionRepositoryTest test
  - Estimate: 1–2 hours

Batch 4 — Tests & Integration
--------------------------------
- Add an integration smoke test for resume flow:
  - backend/src/test/java/com/agent/orchestrator/integration/ExecutionResumeIntegrationTest.java
  - Flow: create minimal schema with 3 nodes (A→B→C), run executeSchema() with mocked LLM provider that fails with token error at B to cause pause; verify run status=paused and checkpoint saved; call resumeExecution() and verify child run created, nodes skipped where configHash matched, final run status=completed and generated files present.
  - Run: cd backend && mvn -Dtest=ExecutionResumeIntegrationTest test
  - Estimate: 6–10 hours (integration tests take longer)

Rollback & Safety
-----------------
- Table creation is idempotent (`CREATE TABLE IF NOT EXISTS`) — safe to deploy.
- Before changing production DB, ensure backup of SQLite file (scripts/backup-sqlite.sh) — add small script if missing.
- ExecutionRepository methods swallow SQL exceptions (log) to avoid runtime crash; if updating method signatures, keep old methods as overloads for backward compatibility.
- Feature toggle: Add a `settings.execution.persistence.enabled` flag read from SettingsService; when false, SchemaService will not create ExecutionRun/NodeExecution rows (keep toggling during rollout).

Files to add (tests + helpers)
------------------------------
- backend/src/test/java/com/agent/orchestrator/service/NodeExecutorPersistenceTest.java
- backend/src/test/java/com/agent/orchestrator/service/SchemaServiceResilienceTest.java (extended)
- backend/src/test/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandlerTest.java
- backend/src/test/java/com/agent/orchestrator/integration/ExecutionResumeIntegrationTest.java
- Optional helper: backend/src/main/java/com/agent/orchestrator/util/ConfigHashUtil.java (static helper to compute SHA-256; to be used by SchemaService and tests)

Commands to run during development
---------------------------------
- Run repository tests: cd backend && mvn -Dtest=ExecutionRepositoryTest test
- Run unit tests: cd backend && mvn -Dtest=NodeExecutorPersistenceTest,SchemaServiceResilienceTest test
- Run full backend test suite: cd backend && mvn test

Estimates & priorities (per task)
---------------------------------
- 2.1 NodeExecutor persistence — 3–4h — HIGH
- 2.4 ExecutionRepository signature (if needed) — 1–2h — MEDIUM
- 2.2 SchemaService wiring, checkpoint logic, pause handling — 6–8h — HIGH
- 2.3 WebSocket paused event — 2h — MEDIUM
- Batch 4 integration test & polish — 6–10h — MEDIUM
- Contingency & test fixes — 4h

Total engineering estimate: 22–30 hours (across two engineers in parallel: NodeExecutor + SchemaService)

Notes for implementer
---------------------
- Keep changes small and focused — prefer adding calls to existing ExecutionRepository methods rather than heavy refactors.
- Use TempDir in tests to avoid touching developer DB files.
- When adding new method signatures, keep backward-compatible overloads to avoid compile-time churn.

Next step (implementation)
--------------------------
I will not modify code yet. Confirm if you want me to:

1. Generate patch-ready diffs for tasks 2.1–2.4 (one file per patch) so you or an implementer can apply them, OR
2. Proceed to run an implementer agent to apply the smallest changes (this will create commits and run tests). This is a destructive action and requires confirmation.
