# Axolotl Roadmap — Stability First

> Approved directions for Axolotl development. Each section is a concrete,
> orderable workstream with rationale, milestones, and risk notes.
>
> **Priority principle:** Production hardening before new R&D. Every workstream
> must either fix a known reliability gap or improve a production metric.
>
> **Reassess after each workstream** — if a workstream overruns by >50%, cut
> the lowest-ranked remaining item. Premortem again after any major update.

---

## 1. Production Hardening

**Goal**: Make Axolotl robust against network failures, misconfiguration,
edge cases, and silent errors. Every pipeline run should either succeed with
a clear result or fail with an actionable error message.

### Why
The Бережно E2E exposed recurring failure modes:
- Silent failures (429 rate limit → empty plan, no user-visible error)
- Schema corruption from partial PUT updates (fixed but untested)
- Crashes on missing node config fields, null model, empty sourceData
- WebSocket disconnect without state recovery
- No schema validation before execution — user discovers misconfig mid-run

### Breakdown

#### 1a. Unified schema validation
**Mechanism**: `SchemaValidator` component called before any execution:
- All required node fields present (model, prompt, tools for agent type)
- Edge source/target nodes exist in the node map
- Pipeline stages reference valid node IDs
- Default model resolves to an available provider
- Provided API keys are non-empty for remote providers
- Source node has content (sourceData, file path, or URL)
- No duplicate node IDs

Returns structured `ValidationResult` with `errors` (blocking) and `warnings`
(suggestions). Errors displayed in BlockConfigPanel inline; execution blocked
until resolved.

**Effort**: ~2 days (new class + integration into executeWorkflow + frontend display)
**Risk**: Validation may be overly strict — allow execution with warnings, block only on errors.
**Test**: Schema with missing model → blocked. Schema with full config → passes.

#### 1b. Error message overhaul
**Mechanism**: Every `catch` block across SchemaService, PipelineService,
NodeRouter, ToolExecutor, ExecutionWebSocketHandler must produce a
user-facing error string (not `e.getMessage()` which may be null/technical).

Standard categories:
- `CONFIG_ERROR` — misconfiguration (missing model, bad node config)
- `PROVIDER_ERROR` — LLM API failure (timeout, rate limit, auth)
- `NETWORK_ERROR` — WebSocket drop, Neo4j connection loss
- `INTERNAL_ERROR` — unexpected exception (includes stack trace in logs)
- `VALIDATION_ERROR` — schema validation failure

Error format: `{ "type": "CONFIG_ERROR", "message": "human readable", "detail": "technical context" }`

Frontend: error banner in TimelineView + toast for operational errors.
StudioView: inline error on the affected node in BlueprintView.

**Effort**: ~2 days (backend error taxonomy + frontend error display components)
**Risk**: Adding error typing everywhere may miss some paths — focus on the 10 most common failure
modes first (missing model, provider timeout, empty plan, file read error, Neo4j down, WS disconnect).
**Test**: Inject each error type via mock and verify frontend displays the correct message.

#### 1c. WebSocket resilience
**Mechanism**:
- Auto-reconnect with exponential backoff (1s/2s/4s/8s, max 30s)
- On reconnect, re-query `GET /api/schemas/{id}/pipeline/status` and
  `GET /api/schemas/{id}/runs/latest` to recover execution state
- `isRunning` flag auto-resets if backend reports no active execution
- Pending `await` during execution (approveReview, rejectReview) catches
  WS disconnect and shows toast "Connection lost — execution state uncertain"

**Effort**: ~1.5 days (useWebSocket.ts + ExecutionStateManager)
**Risk**: Auto-reconnect could mask real network issues. Add max retry limit (5 attempts)
with permanent failure state + user-visible banner.
**Test**: Kill backend during pipeline → verify reconnect → verify state recovery.

#### 1d. Concurrent execution guard
**Mechanism**: Backend rejects `POST /pipeline/execute` if schema already has
an active run (status = 'running' or 'resuming'). Frontend disables Execute
button while `isRunning === true`.

Check at both levels:
- Frontend: `isRunning` guard in StudioView (already exists, verify)
- Backend: `SchemaService.executeWorkflow` checks for active run before starting

**Effort**: ~0.5 day (SchemaService + PipelineService guard)
**Risk**: Low — pure hardening of existing guard.
**Test**: Double-click Execute → second call returns 409.

#### 1e. Input validation on all API endpoints
**Mechanism**: Add `@Valid` + Jakarta Validation annotations to all controller
request bodies. Reject with 400 + structured error response instead of 500.

Endpoints needing validation:
- `POST /api/schemas` — name, targetPath required
- `PUT /api/schemas/{id}` — validate non-null fields (no corruption)
- `POST /api/schemas/{id}/pipeline/execute` — validate schema readiness
- `POST /api/auth/login` — username/password required
- `PUT /api/settings/{provider}` — validate provider config fields

**Effort**: ~1 day (annotations + custom validation messages)
**Risk**: Some existing endpoints may need refactoring to support validation groups.
**Test**: Send empty POST body → 400 with field-level errors shown in UI.

#### 1f. Edge case coverage for existing features
**Mechanism**: Systematically handle:
- Empty runs list (already done via TimelineView empty state)
- Schema with 0 nodes (block execution with clear message)
- Execution that produces 0 output files (show "No files generated" not crash)
- Provider returns empty model list (show "No models available" not empty dropdown)
- Agent iterates 0 times (skip with warning, not block)
- Review node with empty plan (shows "No content" not stale/blank dialog)
- Diff review with 0 diffs (skip AWAITING_DIFF_APPROVAL, continue)
- Build check when SDK not installed (show "SDK not found" not crash)

**Effort**: ~2 days (systematic audit of all Feature + Edge states in UI and backend)
**Risk**: Scope creep — limit to edge cases encountered in real use or premortems.
**Test**: Create schema with 0 nodes → execute → see error, not crash.

### Total effort: ~9 days
### Success criteria: Run the full Бережно E2E 5 times without any silent failure,
unhelpful error, or crash. Every error message is actionable.

---

## 2. Infrastructure & CI

**Goal**: Keep CI green, Neo4j clean, and the build reproducible.

### Why
CI is the project's immune system. If it breaks silently, every commit is
suspect. Neo4j data grows without bound. No integration tests run in CI.

### Breakdown

#### 2a. CI hardening
**Mechanism**:
- Actions bumped to v5 (Node.js 24) before June 2, 2026 deprecation
- `save-always: true` on all cache actions for branch caching
- Backend CI includes `mvn test -Pintegration` with Testcontainers Neo4j
- Frontend CI includes `npm run type-check` (vue-tsc --build) and `npm run build`
- Fail on any warning (`continue-on-error` removed from all steps)
- Weekly CI audit check (Dependabot or manual: review action deprecations)

**Effort**: ~1 day
**Risk**: Integration tests with Testcontainers may be slow (~2min per run).
Mitigate: cache Neo4j image, run in parallel with frontend.
**Test**: Push to main → CI green with integration tests passing.

#### 2b. Neo4j data lifecycle
**Mechanism**:
- TTL index on `NodeExecution.createdAt` (90-day auto-delete via Neo4j TTL)
- App-level fallback: `ExecutionRepository.cleanupOldRuns()` deletes runs older
  than 180 days (triggered on schema load or via a cron-like on-start check)
- Log warning if TTL is not supported (Neo4j Community Edition)
- Add Cypher indexes for common query patterns: `runId`, `schemaId`, `status`

**Effort**: ~1 day (application.yml + repository methods + startup health check)
**Risk**: TTL/index creation may fail silently. Add explicit log confirmation.
**Test**: Query `CALL db.indexes()` before and after startup.

#### 2c. Backend integration test suite
**Mechanism**: Testcontainers-based tests for:
- `PipelineService.executePipeline()` — full 5-stage run with mocked LLM
- `SchemaService.executeWorkflow()` — wave loop with approval pause/resume
- `ExecutionRepository` CRUD — create run, add nodes, update status
- `AuthController` — JWT issue, validation, rejection

Use `@Testcontainers` + Spring Boot test slice. Run in `mvn verify -Pintegration`.
LLM responses mocked via WireMock or simple stub provider.

**Effort**: ~3 days (test infrastructure + 8-10 test methods)
**Risk**: Testcontainers requires Docker. CI must have Docker socket available.
Mitigate: GitHub Actions Ubuntu runner has Docker by default.
**Test**: `mvn verify -Pintegration` passes in CI.

### Total effort: ~5 days
### Success criteria: `mvn verify -Pintegration` passes. Neo4j has TTL + indexes.
CI fails on warning. All actions on v5.

---

## 3. Quality Gates

**Goal**: Ensure generated code compiles and passes static analysis before
the user sees it.

### Why
Post-write syntax validation catches individual file errors. But cross-file
issues (import resolution, type mismatch) need project-level verification.
Currently the user finds these only by manually running `dart analyze`.

### Breakdown

#### 3a. Project-level verify stage
**Mechanism**: New `batch_verify` node type. After all agent nodes complete,
runs `dart analyze` (or LanguageTarget equivalent) on the entire project.
Results feed into a new agent turn to fix all issues in one batch.

**Effort**: ~4 days (new node type + ToolExecutor batch verify)
**Risk**: Long-running analysis (~30s for medium Flutter projects).
**Risk**: Parallel verify races with output stage file writes.
**Mitigation**: Serialize: batch_verify → agent fix → batch_verify again. Do NOT run in parallel with output.

#### 3b. Stub detection in VerifierNode
**Mechanism**: Verifier node prompt includes heuristic stub detection:
check generated files for patterns like `// TODO`, `// stub`, empty class bodies,
`throw UnimplementedError`, `return null`. Flag files exceeding configurable
stub threshold as FAIL.

Already partially implemented — expose `stubDetection` toggle in BlockConfigPanel.

**Effort**: ~0.5 day (prompt refinement + frontend toggle)
**Risk**: False positives on legitimate TODO comments. Use threshold (≥5 stubs = FAIL).

#### 3c. Regression test suite via pipeline
**Mechanism**: After each pipeline run that produces code:
1. `git stash` (if dirty) in targetPath
2. Run LanguageTarget verifier (dart analyze / go vet / cargo check)
3. Assert existing tests still pass
4. Restore any stashed changes

If regression detected → FAIL stage, agent sees output and self-corrects
(max 3 retries, then permanent FAIL).

**Effort**: ~1.5 days (PipelineService post-stage hook)
**Risk**: Tests may require external services. Start with `--no-sandbox` for Flutter.
**Mitigation**: `maxSelfCorrectRetries: 3`, fallback to FAIL on exceed.

### Total effort: ~6 days
### Success criteria: A pipeline run that generates Flutter code passes
`dart analyze` at project level with 0 errors, with no stub files.

---

## 4. Observability

**Goal**: Make it possible to understand *why* an agent made a decision.

### Why
Debugging a bad agent turn requires grepping log files. Tool call history
is not persisted. Token usage is unavailable for local models.

### Breakdown

#### 4a. MDC tracing
**Mechanism**: Add `schemaId`, `runId`, `nodeId` to SLF4J MDC at entry points
(SchemaService, PipelineService, NodeRouter, ToolExecutor). Free structured
logs filterable by any dimension. Zero new tables or migrations.

**Effort**: ~0.5 day
**Risk**: None. Already partially done in some classes.

#### 4b. Tool call persistence
**Mechanism**: Each `toolExecutor.execute()` creates a `ToolCall` node linked
to `NodeExecution` with: toolId, args JSON (redacted), result preview (500 chars),
duration, timestamp.

**Effort**: ~3 days (new model + repository)
**Risk**: Write amplification (~50-200 calls/run). Cap args at 1KB, auto-purge 30 days.
**Risk**: Secrets in args. Redact `apiKey`, `password`, `token` before storage.

#### 4c. Token tracking for local models
**Mechanism**: For Ollama: parse stderr for `prompt eval count` and `eval count`.
For MLX/Bonsai: parse response metadata if available. Display in TimelineView
node detail rows.

**Effort**: ~1 day (OllamaProvider parsing)
**Risk**: MLX server doesn't expose token counts. Start with Ollama only.

### Total effort: ~4.5 days
### Success criteria: Given a runId, query Neo4j for all tool calls with args
and results, ordered by time.

---

## 5. Multi-Session Development (R&D)

> **This workstream is marked R&D.** Its value hypothesis is unproven —
> the spike may show it's not worth pursuing. If so, drop it and reallocate
> effort to WS1 (Production Hardening) or WS3 (Quality Gates).

**Goal**: Make Axolotl capable of building a real app over N sessions without
regression, duplication, or quality loss.

### Why
The Бережно experiment proved the concept but exposed three gaps:
- Each session starts blind (no memory of prior output)
- Re-runs overwrite/duplicate files
- No easy way to roll back a bad session

### Spike first (1 day)
**Implementation scope** (single file change):
- `ExecutionUtilityService.buildStagePrompt()`: add 20-line block to query
  `NodeExecutionRepository.findByRunId()` for last completed run, extract
  `outputSummary` from the agent-stage node, append as "## Prior Session Output"
  section. No new classes, no Neo4j schema changes, no frontend changes.

**Pass/fail criteria** (both must pass):
1. Agent produces ≥50% fewer stub files compared to baseline (one-shot pipeline)
2. No regression in `dart analyze` errors

**If spike fails** → **drop WS5 completely**. Reallocate to WS1 backlog.

### Full breakdown (if spike passes)

#### 5a. Cross-session context injection (~2 days)
Inject file tree (`directory_read`), last 3 `generatedFiles` arrays, git log
summary into system prompt. File tree is the canonical source of truth.

#### 5b. Git-backed session workspace (~1 day)
`git init` if not a repo. `git checkout -b session-N` on start.
`git add -A && git commit` on complete. Auto-delete branches >30 days.

#### 5c. Diff-aware agent (~1 day)
Inject `git diff main..session-N-1` summary (per-file, capped at 30 files/100 lines).

#### 5d. Session kill switch (~0.5 day)
Cancel mid-run reverts uncommitted changes via `git checkout`.

### Total effort: ~6.5 days (×1.5 human factor: ~10 days)
### Success criteria: 3 sequential sessions building the same app — each session
passes `dart analyze`, no file count regression, no manual edits needed.

---

## Priority Recommendation

| Rank | Workstream | Effort | Impact | Risk | When |
|------|-----------|--------|--------|------|------|
| 1. | **1. Production Hardening** | 9d | High (reliability) | Low | Now |
| 2. | **2. Infrastructure & CI** | 5d | Medium (foundation) | Low | After WS1 |
| 3. | **3. Quality Gates** | 6d | High (output quality) | Medium | After WS2 |
| 4. | **4. Observability** | 4.5d | Medium (debugging) | Low | After WS3 |
| — | **WS5 spike** | 1d | Validates WS5 | Low | Spike anytime |
| 5. | **5. Multi-Session (if spike passes)** | 10d | High (new capability) | Medium | After WS4 |

### Order of execution

1. **WS1 (Production Hardening)** — 9 days. Fix all known silent failures,
   validate schemas, harden WS, add edge case coverage.
2. **WS2 (Infrastructure & CI)** — 5 days. CI hardening, Neo4j lifecycle,
   integration tests. Keeps the project green while features are added.
3. **WS3 (Quality Gates)** — 6 days. Project-level verify, stub detection,
   regression checks.
4. **WS4 (Observability)** — 4.5 days. MDC tracing, tool call persistence,
   local model token tracking.
5. **WS5 spike (1 day)** — Run at any point after WS2. If passes → full WS5.
   If fails → drop. Reallocate to WS1 backlog items.

Reassess after each workstream. If a workstream overruns by >50%, cut the
lowest-ranked remaining item.

---

## What We Are NOT Doing (this cycle)

| Excluded | Rationale | Revisit when |
|----------|-----------|--------------|
| Multi-Session (if spike fails) | Value unproven; don't invest before evidence | Spike shows clear quality improvement |
| Mobile app for Axolotl itself (React Native / Flutter) | Premature — desktop web is the primary UI | User requests it |
| Multi-user / teams | No demand signal yet | Any team adopts Axolotl |
| Plugin system (third-party nodes/types) | Requires stable node API first | After WS1 + WS3 land |
| Stage groups in BlueprintView | Cosmetic, not reliability | After WS4 |
| Keyboard shortcuts | Nice-to-have, not reliability | After WS4 |
| Windows native packaging | macOS + web covers current users | User requests it |
| Full i18n (beyond EN/RU docs) | Low value per effort | After all WS1-4 |

**Tech debt that blocks nothing** (deferred indefinitely):
- `BlockConfigPanel.vue` reactive refs (stale data on block switch) — targeted watch already applied
- Dead code in `CustomLlmProvider.java` (commented fallback logic)
- Frontend test coverage on non-critical components

---

*Last updated: 2026-05-27*
