# Test Plan: Axolotl v0.4.0

## Scope

All features landing in v0.4.0, ordered by risk. Each test case is a concrete manual check. Backend unit tests = 231 (baseline — run `mvn test` before any manual testing).

## Quick Smoke (5 min)

Run these first. If any fails, block the release.

```
[SMOKE-01] Backend starts: ./scripts/dev.sh start → curl -s localhost:8082/api/schemas | python3 -m json.tool
  Expect: 200 + JSON array (may be empty)

[SMOKE-02] Frontend loads: cd frontend && npm run dev → open localhost:5173
  Expect: Login page renders, no console errors

[SMOKE-03] Auth works: source scripts/token.sh → curl -s -H "$CURL_HEADER" localhost:8082/api/schemas
  Expect: 200 + JSON array

[SMOKE-04] Backend tests pass: cd backend && mvn test
  Expect: SUCCESS, 0 failures

[SMOKE-05] Frontend type-checks: cd frontend && npx vue-tsc --noEmit
  Expect: 0 errors
```

---

## 1. Schema Import/Export

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 1.1 | Export schema | 1. Open schema in Studio 2. Click Export in StudioTopBar | `.json` file downloaded with schema name, contains all nodes+edges+pipeline (no IDs/timestamps) |
| 1.2 | Import schema | 1. Open Dashboard 2. Click Import 3. Select exported `.json` 4. Confirm | New schema card appears; open shows exact same nodes/edges/pipeline as exported |
| 1.3 | Import validates JSON | 1. Upload invalid JSON (`{bad`) | Error toast: "Failed to parse schema JSON" |
| 1.4 | Import rejects missing required fields | 1. Upload `{}` | Error: "Missing required field: name" |

## 2. Blueprint Undo/Redo

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 2.1 | Undo node add | 1. Drag new Agent node onto canvas 2. Press Ctrl+Z | Node disappears |
| 2.2 | Redo node add | 3. After undo, press Ctrl+Shift+Z | Node reappears |
| 2.3 | Undo node delete | 1. Select a node, press Delete 2. Ctrl+Z | Node returns at same position |
| 2.4 | Undo edge create | 1. Connect two nodes with edge 2. Ctrl+Z | Edge removed |
| 2.5 | Undo/redo buttons in toolbar | 1. Add then remove node 2. Click Undo button → Redo button | Node restored, then removed |
| 2.6 | Stack depth | 1. Add 10 nodes sequentially 2. Ctrl+Z 10x | All 10 undone, back to original state |

## 3. Agent Personas

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 3.1 | Persona selector visible | 1. Select Agent node 2. Open BlockConfigPanel | "Persona" dropdown visible above System Prompt |
| 3.2 | Personas listed | Click dropdown | 5 options: Architect, Hacker, Teacher, Minimalist, TDD |
| 3.3 | Persona replaces system prompt | 1. Type custom prompt 2. Select "Architect" persona | System prompt replaced with Architect's preset (spec-first) |
| 3.4 | Custom prompt after persona | 1. Select "Hacker" 2. Edit system prompt | Edit preserved, persona remains selected (visual indicator) |
| 3.5 | Pipeline respects persona | 1. Create pipeline with Agent node using "Teacher" 2. Execute | Agent output contains explanatory comments (Teacher trait) |

## 4. Non-Flutter Targets (ProjectType)

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 4.1 | Project type dropdown | 1. Open SchemaPropertiesPanel | "Project Type" select with 5 options |
| 4.2 | Python target validate | 1. Set type=Python 2. Agent writes `foo.py` 3. Build check runs | `python3 -m py_compile foo.py` runs |
| 4.3 | Web target validate | 1. Set type=Web 2. Agent writes `foo.tsx` | `npx tsc --noEmit` runs |
| 4.4 | Go target validate | 1. Set type=Go 2. Agent writes `main.go` | `go vet ./...` runs |
| 4.5 | Rust target validate | 1. Set type=Rust 2. Agent writes `lib.rs` | `cargo check` runs |
| 4.6 | Build command per type | 1. Set any non-Flutter type 2. Execute pipeline with build_app tool | Build command matches type (e.g. `npm run build` for Web) |
| 4.7 | Default is Flutter | 1. Create new schema | projectType = FLUTTER |

## 5. Draft Pipeline

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 5.1 | Draft gate appears | 1. Open schema with draft-stage in pipeline 2. Execute pipeline | Pipeline pauses at draft gate with DraftReviewDialog |
| 5.2 | Draft gate shows artifacts | 1. Execute until draft pause | Artifact cards visible, collapsible, show generated code |
| 5.3 | Approve draft | 1. In DraftReviewDialog click Accept | Pipeline resumes, next stage starts |
| 5.4 | Reject draft | 1. Click Reject with feedback | Pipeline status = paused, user can modify and re-run |

## 6. LLM Thoughts & Reasoning

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 6.1 | Reasoning captured | 1. Execute pipeline with Agent node 2. After completion check Timeline | Each Agent node shows `reasoning` field in expanded node details |
| 6.2 | Reasoning persisted | 1. Reload Studio | Reasoning still visible in Timeline (from Neo4j) |
| 6.3 | Reasoning empty for non-reasoning models | 1. Use basic model without reasoning output | reasoning field = absent/null (no crash) |

## 7. Run History Timeline

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 7.1 | Timeline shows runs | 1. Open Timeline tab in Studio | List of execution runs, newest first, with status-colored dots |
| 7.2 | Run card info | Inspect a card | Shows: status dot, mode tag (PIPELINE/EXECUTE), relative time, duration, token count |
| 7.3 | Expand node list | Click a run card | Nodes expand below with status per node |
| 7.4 | Output preview | Click a completed node | Output shown with 200-char truncation + "Show more" link |
| 7.5 | Show more dialog | Click "Show more" | Modal with full output, monospace, scrollable, Copy button |
| 7.6 | Running run auto-expands | 1. Start pipeline 2. Open Timeline | Latest running/completed run auto-expands |
| 7.7 | Stale run release | 1. If run stuck in 'resuming' 2. Click "Release Stale Runs" | Run status reset to "paused", can be resumed |
| 7.8 | Delete run | 1. Click delete on a run 2. Confirm in 3s window | Run removed from list and Neo4j |
| 7.9 | Live Events bar | 1. Execute pipeline 2. Open Timeline | Live Events bar shows recent events (progress, log, etc.) |
| 7.10 | Cleanup-runs endpoint | 1. Cause a stuck 'resuming' run 2. Call POST /api/schemas/{id}/cleanup-runs | Stuck runs released, 200 OK |

## 8. Diff Review

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 8.1 | Diff created on existing file edit | 1. Schema with existing file in targetPath 2. Set `requireDiffReview=true` in stage 3. Execute pipeline | `.bak` backup created, pipeline pauses at `AWAITING_DIFF_APPROVAL` |
| 8.2 | DiffReviewDialog shows unified diff | Pipeline paused | Dialog opens with unified diff per file, shows +/− lines |
| 8.3 | Accept diff | Click Accept for a file | `.bak` removed, pipeline continues |
| 8.4 | Reject diff | Click Reject for a file | Original file restored from `.bak`, pipeline continues |
| 8.5 | RequireDiffReview toggle saves | 1. Toggle ON in BlockConfigPanel 2. Save schema 3. Reload | Toggle still ON |
| 8.6 | Not required for new files | 1. Agent creates new file (not in targetPath) 2. `requireDiffReview=true` | No pause, pipeline continues (no `.bak` needed) |

## 9. Missing Dependencies Install

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 9.1 | build_app reports missing deps | 1. Execute pipeline with Agent + build_app tool 2. Flutter SDK not in PATH | `deps_needed` WebSocket event fires with list of missing tools |
| 9.2 | DepsInstallDialog appears | 1. Trigger deps_needed | Dialog shows per-dependency install ⟳ → ✅/❌ status |
| 9.3 | Install-deps endpoint | 1. POST /api/execution/{id}/install-deps with dep name | Brew install runs, status updates via WS |
| 9.4 | User continues after install | 1. After install completes 2. User closes dialog | Pipeline retries build_app |
| 9.5 | User skips install | 1. Dismiss dialog without installing | Pipeline status = paused, user can edit and retry |

## 10. Parallel Stage Execution

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 10.1 | Independent stages run concurrently | 1. Create pipeline with 2 independent Agent stages in same level 2. Execute | Both stages start at approximately the same timestamp |
| 10.2 | Sequential stages respect order | 1. Stage B depends on Stage A 2. Execute | Stage A completes before Stage B starts |
| 10.3 | Failure in parallel stage stops level | 1. Stage A fails in level with A+B 2. Execute | Stage B does not start (level failed) |
| 10.4 | No deadlock on pause | 1. Stage A pauses for review, Stage B is also in level 2. Execute | Both stop, pipeline status = paused |

## 11. Production Hardening (WS1)

### 11.1 Schema Validation

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 11.1a | Validate invalid schema | 1. Create schema with no name 2. GET /api/schemas/{id}/validate | ValidationResult with errors, `valid=false` |
| 11.1b | Execution blocked on errors | 1. Schema has validation errors 2. Try to execute | 400 error: "Schema has validation errors" |
| 11.1c | Validate passes | 1. Open valid schema 2. GET /api/schemas/{id}/validate | `valid=true`, no errors |
| 11.1d | Warnings don't block | 1. Schema with missing edge (warning) 2. Execute | Warning listed, execution allowed |

### 11.2 Concurrent Execution Guard

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 11.2a | Double execute blocked | 1. Start pipeline on schema 2. Click execute again | 409: "Schema is already executing" |
| 11.2b | Execute after completion allowed | 1. Wait for pipeline to complete 2. Click execute again | Pipeline starts normally |

### 11.3 Input Validation

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 11.3a | Empty schema name rejected | 1. POST /api/schemas with name="" | 400 error |
| 11.3b | Null name rejected | 1. PATCH /api/schemas/{id} with `{"name": null}` | 400 error |
| 11.3c | Import invalid payload | 1. POST /api/schemas/import with `{"name": ""}` | 400 error |

### 11.4 WebSocket Resilience

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 11.4a | WS reconnect on drop | 1. Start pipeline 2. Kill backend process 3. Restart backend | WS reconnects within 16s (exponential backoff), pipeline state restored |
| 11.4b | Heartbeat keeps connection alive | 1. Idle for 60s | No disconnection (heartbeat every 30s) |
| 11.4c | isRunning resets on disconnect | 1. Kill backend during execution 2. WS disconnects | Frontend `isRunning` = false, UI returns to editable state |

### 11.5 Edge Case: Empty Schema Execution

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 11.5a | Execute schema with 0 nodes | 1. Create empty schema 2. Execute | Error: "No nodes to execute" |
| 11.5b | Resume without paused run | 1. POST /api/schemas/{id}/resume on completed run | WS error: "No paused execution found" |

## 12. Neo4j TTL Cleanup (WS2)

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 12.1 | Cleanup removes old runs | 1. Manually set ExecutionRun.startedAt to 31 days ago 2. Trigger cleanup | Old run deleted from Neo4j |
| 12.2 | Cleanup keeps recent runs | 1. Run with startedAt = today | Not deleted |
| 12.3 | Scheduled execution | 1. Wait for 3 AM or trigger @Scheduled | Runs cleaned, log confirms |

## 13. Stub Detection (WS3b)

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 13.1 | Stub toggle visible | 1. Select Verify node 2. Open BlockConfigPanel | "Detect stubs" toggle visible (default=off) |
| 13.2 | Stub detected in code | 1. Agent outputs `// TODO: implement` 2. `detectStubs=true` | Verifier verdict: FAIL with "stub detected" in checks |
| 13.3 | Empty body detected | 1. Agent outputs `void foo() {}` | FAIL: "Empty function body detected" |
| 13.4 | null return detected | 1. Agent outputs `return null;` | FAIL: "Null return value" |
| 13.5 | Stub detection off = no effect | 1. Agent outputs same stub code, toggle OFF | Verifier verdict unaffected by stub patterns |

## 14. Token Tracking & Tool Call History (WS4)

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 14.1 | Tokens persisted | 1. Execute pipeline 2. Check Timeline > node details | `tokensUsed` > 0 (non-zero if provider reports tokens) |
| 14.2 | Tokens survive reload | 1. Refresh Studio | Tokens still visible in Timeline |
| 14.3 | Tool call count | 1. Agent uses file_write + bash 2. Check Timeline node details | `toolCalls` count matches actual tool invocations |
| 14.4 | Tool calls in WS events | 1. Monitor WS during pipeline | `tool_call` events with toolId, duration, success/fail |

## 15. MDC Tracing (WS4a)

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 15.1 | traceId in logs | 1. Execute any API call 2. Check backend log | Log lines contain `[XXXXXXXX]` (8 hex chars traceId) |
| 15.2 | traceId per request | 1. Make 2 concurrent API calls | Each log line has different traceId |

## 16. Post-Write Syntax Validation

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 16.1 | Dart syntax error surfaced | 1. Agent writes `.dart` with missing semicolon 2. file_write completes | Tool output includes `dart analyze` error message |
| 16.2 | Valid Dart passes | 1. Agent writes valid `.dart` 2. file_write completes | No validation error in tool output |
| 16.3 | Python syntax check | 1. Agent writes `.py` with syntax error | `python3 -m py_compile` error in tool output |
| 16.4 | Non-validated extension | 1. Agent writes `.json` with malformed content | No validation (no handler for `.json`) |

## 17. Security Hardening

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 17.1 | Bash `$()` blocked | 1. Agent sends: `bash` with `echo $(whoami)` | Error: "substitution not allowed" |
| 17.2 | Bash backtick blocked | 1. Agent sends command with `` ` `` | Error: "substitution not allowed" |
| 17.3 | Pipe to unallowed command blocked | 1. Agent sends: `ls | bash` | Error: "Pipe chain blocked: 'bash' not in allowed set" |
| 17.4 | Allowed pipe works | 1. Agent sends: `ls | grep test` | Works normally (grep is allowed) |
| 17.5 | Path traversal blocked | 1. Agent writes: `../../etc/passwd` | `SecurityException`: path outside targetPath |
| 17.6 | Normalized path check | 1. Agent writes: `valid_dir/../outside` | Blocked (normalized to canonical path before check) |

## 18. LangChain4j Provider Migration

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 18.1 | All providers work after migration | 1. Test each provider via Settings → Test button | Each returns "Connected" for valid config |
| 18.2 | Chat completion | 1. Execute pipeline with each provider | Agent generates response using the provider |
| 18.3 | Streaming chat | 1. Execute pipeline with streaming model | Tokens stream to WebSocket incrementally |
| 18.4 | Token usage reported | 1. Check after any provider call | `LlmUsage.inputTokens + outputTokens = totalTokens` |

## 19. UI/UX Regression Checks

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 19.1 | BaseButton variants | 1. Inspect all buttons in app | All use `BaseButton.vue` with `variant` prop; no raw `<button>` elements with custom styles |
| 19.2 | Toast errors fire | 1. Trigger a backend error | Toast appears with error message (not silent console.log) |
| 19.3 | Empty state CTAs | 1. Dashboard with 0 schemas 2. Timeline with 0 runs 3. PipelinePanel with 0 stages | Each shows empty state with action button |
| 19.4 | Focus trap in modals | 1. Tab through ReviewApprovalDialog | Focus cycles within modal; cannot tab to page behind |
| 19.5 | Projects folder prompt | 1. Clear projectsFolder setting 2. Login | Dialog appears on Dashboard mount |
| 19.6 | Folder picker opens | 1. Click folder icon in SchemaPropertiesPanel | Native directory picker opens (webkitdirectory) |
| 19.7 | CSS tokens applied | 1. Inspect any component | Uses `var(--bg-*)`, `var(--accent-*)`, `var(--space-*)`, not hardcoded `#xxx` |

## 20. C4 Architecture Docs

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 20.1 | All diagrams render | 1. Open docs/architecture/ 2. Preview each `.md` | 6 Mermaid C4 diagrams render without errors |
| 20.2 | Navigation works | 1. Open VitePress build | Architecture > C4 Diagrams section has 6 links, all 200 |

## 21. CI & Build

| ID | Test | Steps | Expected |
|----|------|-------|----------|
| 21.1 | CI passes on release branch | 1. Push release/0.4.0 2. Check GitHub Actions | build-backend ✅, build-frontend ✅ |
| 21.2 | Neo4j service in CI | 1. Check CI log for backend test run | Neo4j 5-enterprise container started before tests |
| 21.3 | npm cache hit | 1. Run CI twice 2. Second run | `actions/cache` → "Cache hit", install time < 5s |

---

## Test Environment Requirements

| Item | Details |
|------|---------|
| Backend | Running on :8082, Neo4j at bolt://localhost:7687 |
| Frontend | Dev server at :5173 |
| Auth token | `source scripts/token.sh` |
| Flutter SDK | For build_app tests (optional — skip 9.1-9.5 if absent) |
| Ollama | For provider tests (fall back to Zen API if unavailable) |
| Test schema | `scripts/api.py` + arbitrary UUID for pipeline execution |

## Blocking Criteria

All SMOKE tests pass. Zero CRITICAL/HIGH test failures. Known failures documented with severity and owner.

## Priorities

| Priority | Tests | Run at |
|----------|-------|--------|
| P0 — Smoke | SMOKE-01..05 + 21.1 (CI) | Every push |
| P1 — Core features | 1.1-1.4, 2.1-2.3, 7.1-7.3, 10.1-10.2, 11.1a-11.1c, 11.2a-11.2b | Before release |
| P2 — Edge cases | 11.4a-11.5b, 17.1-17.6 | Before release |
| P3 — Full coverage | All | One full pass before release candidate |
