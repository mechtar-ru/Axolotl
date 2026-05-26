# Axolotl Roadmap — Next Directions

> Approved directions for Axolotl development. Each section is a concrete,
> orderable workstream with rationale, milestones, and risk notes.
>
> **Reassess after each workstream** — if a workstream overruns by >50%, cut
> the lowest-ranked remaining item. Premortem again after any major update.

---

## 1. Multi-Session App Development

**Goal**: Make Axolotl capable of building a real app over N sessions without
regression, duplication, or quality loss.

### Why
The Бережно experiment proved the concept but exposed three gaps:
- Each session starts blind (no memory of prior sessions' output)
- Re-runs overwrite/duplicate files
- No easy way to roll back a bad session

### Breakdown

#### 1a. Cross-session context injection
**Mechanism**: Before each agent stage, inject into systemPrompt:
- `directory_read` output of targetPath (current file tree)
- Last 3 `outputSummary` JSON blobs from prior completed runs
- Git log summary (`git log --oneline -5` in targetPath if `.git` exists)

**Effort**: ~2 days (PipelineService + ExecutionUtilityService)
**Risk**: Prompt length growth — 5 runs × raw outputSummary can exceed 32K context.
**Mitigation**: Inject only `generatedFiles` JSON, not raw outputSummary. Cap at 3KB total.

#### 1b. Git-backed session workspace
**Mechanism**: On first session, `git init` in targetPath if not already a repo.
On each session start: `git checkout -b session-N`.
On session complete: `git add -A && git commit -m "Session N: ..."`.
Session plan writes commit messages with file list.

**Effort**: ~1 day (ToolExecutor + schemaStore)
**Risk**: User might have their own git. Check for existing git first, use `git stash` if dirty.
**Risk**: Branch explosion — `session-N` accumulates indefinitely.
**Mitigation**: Auto-delete branches >30 days old after commit. Or use `git worktree` instead.

#### 1c. Diff-aware agent
**Mechanism**: When agent starts a session, inject `git diff main..session-N-1`
into the system prompt (as "files modified in prior sessions"). This lets the
agent know what exists and what was recently changed.

**Effort**: ~1 day
**Risk**: Git diff produces unstructured raw text — a 2000-line diff overwhelms context.
**Mitigation**: Summarize diff per-file (summary, ±lines), cap at 30 files / 100 lines.
**Dependency**: 1b must be done first

### Total effort: ~4 days (×1.5 human factor: ~6 days)
### Success criteria: 3 sequential sessions building the same app — each session
passes `dart analyze`, no file count regression across sessions, no manual edits needed.

---

## 2. Observability & Debugging

**Goal**: Make it possible to understand *why* an agent made a decision, not just
*what* it did.

### Why
Current observability is limited to `outputSummary` (result text) and bare logs.
Debugging a bad agent turn requires grepping log files.

### Breakdown

#### 2a. Tool call persistence in Neo4j
**Mechanism**: Each `toolExecutor.execute()` creates a `ToolCall` node linked to
`NodeExecution` with: toolId, args JSON, result preview (first 500 chars),
duration, timestamp. NodeExecution has a `toolCalls` relationship.

**Effort**: ~3 days (new model + repository + migration)
**Risk**: Write amplification — each session generates ~50-200 tool calls.
Mitigate: cap stored args at 1KB, auto-purge after 30 days.
**Risk**: Tool args may contain secrets (API keys passed to bash).
**Mitigation**: Add redaction for known patterns (`apiKey`, `password`, `token`) before persistence.

#### 2b. Real-time token tracking for local models
**Mechanism**: For Ollama: parse stderr output for `prompt eval count` and
`eval count` lines. For MLX/Bonsai: parse response metadata (if available).
Display in TimelineView node detail rows.

**Effort**: ~2 days (OllamaProvider LLM integration)
**Risk**: MLX server doesn't expose token counts in API response — would need
proxy middleware. Start with Ollama only.

#### 2c. MDC tracing
**Mechanism**: Add `schemaId`, `runId`, `nodeId` to SLF4J MDC at entry points
(SchemaService, PipelineService, NodeRouter, ToolExecutor). This gives free
structured logs that can be filtered by any dimension.

**Effort**: ~0.5 day
**Risk**: None. Well-understood pattern, already partially done in some classes.

### Total effort: ~5.5 days
### Success criteria: Given a runId, query Neo4j for all tool calls with args and
results, ordered by time.

---

## 3. Quality Gates

**Goal**: Ensure generated code compiles, passes static analysis, and doesn't
break existing tests.

### Why
Post-write syntax validation (added in this cycle) catches individual file errors.
But cross-file issues (import resolution, type mismatch across files) need
project-level verification.

### Breakdown

#### 3a. Project-level verify stage
**Mechanism**: New `batch_verify` node type. After all agent nodes complete,
runs `dart analyze` (or equivalent) on the entire project. Results feed into
a new agent turn to fix all issues in one batch.

**Effort**: ~2 days (new node type + ToolExecutor batch verify)
**Risk**: Long-running analysis (~30s for medium Flutter projects).
**Risk**: Parallel verify races with output stage file writes — output modifies files while batch_verify runs.
**Mitigation**: Serialize: batch_verify → agent fix → batch_verify again. Do NOT run in parallel with output.

#### 3b. Neo4j-powered premortem
**Mechanism**: Instead of per-file premortem, query Neo4j code graph for
cross-file dependencies. If agent modifies `database_service.dart`, the
premortem checks all files importing it.

**Effort**: ~3 days (Neo4j graph queries in premortem prompt)
**Dependency**: Code graph must be up-to-date for targetPath files (currently only scans `backend/src/main/java/`).
**Prerequisite**: Extend `update-graph.sh` to scan targetPath project dirs + config toggle. ~1 day unbudgeted.

#### 3c. Regression test suite
**Mechanism**: After each session, `git stash` (if dirty), run `dart test`,
restore. Fail the execution if tests break. Agent sees test output and
self-corrects.

**Effort**: ~1.5 days (PipelineService post-stage hook)
**Risk**: Tests might require external services (DB, API). Start with
`--no-sandbox` for Flutter tests.
**Risk**: Agent-in-the-loop self-correction is unreliable — models hallucinate fixes for test errors.
**Mitigation**: Add `maxSelfCorrectRetries: 3`, fallback to StageResult.FAILED on exceed.

### Total effort: ~6.5 days
### Success criteria: A pipeline run that generates Flutter code passes
`dart analyze` at project level with 0 errors.

---

## 4. UI/UX Polish

**Goal**: Reduce friction in daily Axolotl use.

### Why
The UI premortems identified ~40 risks. Most are minor but cumulative friction.

### Breakdown

#### 4a. BlockConfigPanel refs reset
**Mechanism**: On block switch, all 40+ refs re-initialize from fresh config
data. Currently stale refs persist. Add `watch(blockId, { immediate: true })`
that calls `resetRefs()` before loading new block config.

**Effort**: ~0.5 day
**Risk**: Already documented as "Critical" in premortem. Low complexity.

#### 4b. Pipeline progress in StudioTopBar
**Mechanism**: Mini progress bar with stage names and status colors next to the
Execute button. Polls `GET /api/schemas/{id}/pipeline/status` every 2s while
`isRunning`.

**Effort**: ~1 day (new component + polling)
**Risk**: Polling continues forever if WebSocket disconnects but `isRunning` stays true.
**Mitigation**: Add polling timeout (60s max), tie to WebSocket `onDisconnect` callback, stop on component unmount.

#### 4c. Keyboard shortcuts
**Mechanism**: Provide/inject map at StudioView level:
- `Cmd+Enter` → Execute
- `Cmd+S` → Trigger save (already auto-saves, but visual feedback)
- `Cmd+Shift+D` → Toggle timeline/blueprint mode

**Effort**: ~0.5 day
**Risk**: Conflicts with browser defaults. Use `useMagicKeys` from VueUse.

#### 4d. Бережно template
**Mechanism**: Add `presets/bereghno.json` as a Quick Start preset with:
Receive (file → 003.md) → Review (manual) → Agent (Flutter) → Verify → Output.
Default model: @cf/bonsai. Target: emotion tracker app.
(Preset path: `frontend/public/presets/bereghno.json`)

**Effort**: ~0.5 day
**Risk**: None.

### Total effort: ~2.5 days
### Success criteria: A new user can create a Бережно app in 3 clicks, execute
with Cmd+Enter, and follow progress in the top bar.

---

## 5. Infrastructure & CI

**Goal**: Keep the project maintainable and green.

### Why
GitHub Actions Node.js 20 deprecation (June 2, 2026) will break CI silently.
Neo4j data grows unbounded. Test coverage is low.

### Breakdown

#### 5a. CI action updates
**Mechanism**: Bump all actions to v5. Add `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`
env to proactively test.

**Effort**: ~0.5 day
**Risk**: None. Drop-in replacements.

#### 5b. Neo4j TTL indexes
**Mechanism**: Set TTL on `NodeExecution` nodes (auto-delete after 90 days).
Add Cypher `CREATE INDEX node_execution_created_at IF NOT EXISTS FOR ...`.
Also add TTL to `ExecutionRun` (after 180 days).

**Effort**: ~0.5 day (application.yml + Neo4j config)
**Risk**: TTL requires Neo4j Enterprise — local dev with Community silently ignores it.
**Mitigation**: Add log warning when TTL index creation fails + app-level fallback cleanup for Community.

#### 5c. Integration test for PipelineService
**Mechanism**: Spin up test Neo4j via Testcontainers, create real schema + nodes,
run `executePipeline()` and assert stage results. Currently PipelineServiceTest
uses only Mockito, missing real Neo4j interactions.

**Effort**: ~2 days
**Risk**: Testcontainers needs Docker. Bundle with `mvn test -Pintegration`.

### Total effort: ~3 days
### Success criteria: `mvn test -Pintegration` passes with real Neo4j,
CI runs with Node.js 24.

---

## Priority Recommendation

| Rank | Workstream | Effort | Impact | Risk |
|------|-----------|--------|--------|------|
| — | **5a (CI actions)** | 0.5d | Low (deadline) | Low |
| 1 | 1. Multi-Session | 6d (×1.5 human) | High | Medium |
| 2 | 4. UI/UX Polish | 3.5d (×1.5) | Medium | Low |
| 3 | 3. Quality Gates | 10d (×1.5) | High | Medium |
| 4 | 2. Observability | 8d (×1.5) | Medium | Low |
| 5 | 5b-c (Infra rest) | 3.5d (×1.5) | Low | Low |

### Order of execution

1. **#5a (DO FIRST)** — CI actions v5 before June 2 deadline
2. **Reassess roadmap** — priorities may shift after CI fix lands
3. **#1 Multi-Session** — core differentiator, highest user-facing impact
4. **#4 UI/UX** — quick wins, low risk
5. **#3 Quality Gates** — high impact but gated on #1 learnings
6. **#2 Observability** — nice-to-have, defer if budget tight
7. **#5b-c** — after major workstreams land

Reassess roadmap after each workstream. If a workstream overruns by >50%, cut the
lowest-ranked remaining item.

---

*Last updated: 2026-05-26*
