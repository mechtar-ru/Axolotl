# Axolotl Roadmap — Next Directions

> Approved directions for Axolotl development. Each section is a concrete,
> orderable workstream with rationale, milestones, and risk notes.

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
**Risk**: Prompt length growth — cap at 5 most recent runs, prune large file trees.

#### 1b. Git-backed session workspace
**Mechanism**: On first session, `git init` in targetPath if not already a repo.
On each session start: `git checkout -b session-N`.
On session complete: `git add -A && git commit -m "Session N: ..."`.
Session plan writes commit messages with file list.

**Effort**: ~1 day (ToolExecutor + schemaStore)
**Risk**: User might have their own git. Check for existing git first, use `git stash` if dirty.

#### 1c. Diff-aware agent
**Mechanism**: When agent starts a session, inject `git diff main..session-N-1`
into the system prompt (as "files modified in prior sessions"). This lets the
agent know what exists and what was recently changed.

**Effort**: ~1 day
**Dependency**: 1b must be done first

### Total effort: ~4 days
### Success criteria: 3 sequential sessions building the same app without file
conflicts or quality regression.

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
**Risk**: Long-running analysis (~30s for medium Flutter projects). Mitigate:
run in background parallel to output stage.

#### 3b. Neo4j-powered premortem
**Mechanism**: Instead of per-file premortem, query Neo4j code graph for
cross-file dependencies. If agent modifies `database_service.dart`, the
premortem checks all files importing it.

**Effort**: ~3 days (Neo4j graph queries in premortem prompt)
**Dependency**: Code graph must be up-to-date for targetPath files.

#### 3c. Regression test suite
**Mechanism**: After each session, `git stash` (if dirty), run `dart test`,
restore. Fail the execution if tests break. Agent sees test output and
self-corrects.

**Effort**: ~1.5 days (PipelineService post-stage hook)
**Risk**: Tests might require external services (DB, API). Start with
`--no-sandbox` for Flutter tests.

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
**Risk**: Polling during execution. Mitigate: stop polling on component unmount.

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
**Risk**: None.

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
| 1 | 1. Multi-Session | 4d | High | Medium |
| 2 | 4. UI/UX Polish | 2.5d | Medium | Low |
| 3 | 5. Infrastructure | 3d | Low (but urgent) | Low |
| 4 | 3. Quality Gates | 6.5d | High | Medium |
| 5 | 2. Observability | 5.5d | Medium | Low |

### Urgent note
CI actions deprecation (#5a) has a hard deadline of **June 2, 2026** — 7 days
from now. Recommend doing #5a first, then #1 (Multi-Session).

---

*Last updated: 2026-05-26*
