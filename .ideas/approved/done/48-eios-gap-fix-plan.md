# EIOS Gap Fix Plan — Axolotl Code Generation Reliability

**Status:** Planned  
**Priority:** High  
**Theme:** Reliability / Provider Support / Multi-Session  
**Dependencies:** PRISM findings plan (47) complements Phase 1 here  
**Source:** `.ideas/approved/EIOS_GAP_ANALYSIS.md`

---

## Problem

The EIOS gap analysis identifies **25+ gaps** across 7 areas that prevent Axolotl from reliably generating a complete mobile app (Flutter) from a design spec. The core failure mode is: **the pipeline completes successfully but produces zero files on disk**, because the LLM doesn't call `file_write` or doesn't produce compilable output.

Gaps span: tool-calling reliability, pipeline resilience, iOS/Android build chains, cross-session iteration, provider header support, quality gates, and build configuration.

---

## Approach

Group gaps into **7 phases** ordered by user-visible impact:

| Phase | Theme | Gaps | Effort | Risk |
|-------|-------|------|--------|------|
| **1 — Tool Calling** | Make `file_write` reachable by more models | 1a, 5a | 1 day | Low |
| **2 — Verification** | Detect silent failures before pipeline claims success | 1b, 2d, 6a | 1.5 days | Low |
| **3 — Pipeline Resilience** | Survive partial failure; auto-retry | 2a, 1c, 5d | 3 days | Medium |
| **4 — Building** | Actually compile the generated code | 7a, 3d, 3f, 2c, 7b, 7c | 2 days | Low |
| **5 — Provider** | First-class OpenRouter support | 5b, 5c, 5d | 2 days | Medium |
| **6 — Multi-Session** | Iterate across runs without starting over | 4a, 4c, 4b | 5 days | High |
| **7 — Polish** | Nice-to-have improvements | 3e, 3b, 6b, 6c, 6d, 7d, 2b, 4d | 5 days | Low |

---

## Phase 1 — Tool Calling

**Goal:** Make `file_write` discoverable and callable by more LLMs.

### Batch 1.1 — file_write Aliases

**Files:** `ToolExecutor.java` (lines 64-66, 134)

**Current state:** `bash` has 3 aliases (`execute_command`, `run_command`, `exec_command`). `file_write` has zero.

**Changes:**
1. Add 3 registered tools after line 66:
   ```java
   registerTool(new Tool("write_file", "Write File", "Write content to a file (alias for file_write)", ...));
   registerTool(new Tool("create_file", "Create File", "Write content to a new file (alias for file_write)", ...));
   registerTool(new Tool("save_file", "Save File", "Save content to a file (alias for file_write)", ...));
   ```
2. Add 3 handler entries after line 134:
   ```java
   handlers.put("write_file", this::handleFileWrite);
   handlers.put("create_file", this::handleFileWrite);
   handlers.put("save_file", this::handleFileWrite);
   ```

**Verification:** `mvn compile -q` clean. Unit test: each alias routes to `handleFileWrite`.

---

### Batch 1.2 — OpenRouter Headers

**Files:** `CustomLlmProvider.java` (lines 120-160, `sendRawHttpRequest`)

**Current state:** `sendRawHttpRequest` builds `HttpRequest` with only auth header — no `HTTP-Referer` or `X-Title` headers. OpenRouter rejects requests without them.

**Changes:**
1. Add constants in `CustomLlmProvider`:
   ```java
   private static final String OPENROUTER_REFERER = "https://axolotl.app";
   private static final String OPENROUTER_TITLE = "Axolotl";
   ```
2. In `sendRawHttpRequest` (or `chat()` method), detect OpenRouter base URL pattern (`openrouter.ai` in the endpoint URL):
   ```java
   if (endpoint.getBaseUrl() != null && endpoint.getBaseUrl().contains("openrouter.ai")) {
       requestBuilder.header("HTTP-Referer", OPENROUTER_REFERER);
       requestBuilder.header("X-Title", OPENROUTER_TITLE);
   }
   ```
3. If using `OpenAiChatModel` (LangChain4j) for Bearer auth, add custom header via `OpenAiChatModel.builder().customHeaders(...)`.

**Verification:** Unit test: when baseUrl contains `openrouter.ai`, headers are present in request. `mvn compile -q` clean.

---

## Phase 2 — Verification

**Goal:** Never let a pipeline complete with "success" when the agent wrote zero files.

### Batch 2.1 — Zero-Tool-Call Detection

**Files:** `VerifierNodeStrategy.java`, `NodeExecution.java`, `AgentNodeStrategy.java`

**Current state:** Agent can complete with zero tool calls. No check in Verifier or Agent output.

**Changes:**
1. `NodeExecution` already has `toolCalls` (`List<String>`, nullable). Count tool calls at end of `AgentNodeStrategy.execute()`.
2. Add `toolCallCount` to agent `outputSummary`:
   ```java
   Map<String, Object> summary = new HashMap<>();
   summary.put("response", response);
   summary.put("toolCallCount", nodeExecution.getToolCalls() != null ? nodeExecution.getToolCalls().size() : 0);
   ```
3. In `VerifierNodeStrategy`, add a pre-defined check that fires when `toolCallCount == 0` AND expected output type is code generation:
   - Verdict: `{"status": "FAIL", "checks": [{"name": "file_write calls", "passed": false, "detail": "Agent made 0 file_write calls — no code generated"}], "summary": "..."}`
   - This check only applies when verifier config has `expectFiles: true` (default for verifier nodes created by QuickStart)

**Verification:** Unit test: agent with 0 tool calls + `expectFiles: true` → verifier returns FAIL.

---

### Batch 2.2 — "Expected Files" Count

**Files:** `NodeData.java`, `AgentNodeStrategy.java`, frontend `BlockConfigPanel.vue`

**Current state:** No way to tell the agent "you should create N files."

**Changes:**
1. Add optional `expectedFileCount` to `NodeData.config` (integer, null = no enforcement)
2. In `AgentNodeStrategy`, after execution, compare `writeFileCallCount` against `expectedFileCount`:
   - If `expectedFileCount` is set and actual < expected → add warning in output summary
   - Log: `"Agent created 2 file(s), expected 5"`
3. Frontend: add `expectedFileCount` input field in BlockConfigPanel for agent nodes (Advanced section, number input, min=1, placeholder="No limit")

**Verification:** Unit test: agent with `expectedFileCount=5` writes 2 files → output has warning. `vue-tsc --build` clean.

---

### Batch 2.3 — Stub Detection for Agent Nodes

**Files:** `AgentNodeStrategy.java`, `VerifierNodeStrategy.java`

**Current state:** Stub detection (`// TODO`, empty bodies, `return null`) exists in VerifierNodeStrategy but is not auto-enabled when created via QuickStart.

**Changes:**
1. Move stub detection logic from `VerifierNodeStrategy` into a shared `CodeQualityChecker` utility
2. In `VerifierNodeStrategy`, when the verifier node is a direct successor of an agent node (check edge direction), auto-enable stub detection by default
3. QuickStart dialog sets `config.stubDetectionEnabled: true` on verifier nodes it creates

**Verification:** Unit test: QuickStart-created schema → verifier node has `stubDetectionEnabled: true`.

---

## Phase 3 — Pipeline Resilience

**Goal:** Survive partial failures and automatically retry.

### Batch 3.1 — Model Fallback Chain

**Files:** `LlmService.java`, `NodeData.java`

**Current state:** If the selected model fails (429, 5xx, timeout), the pipeline fails. No fallback.

**Changes:**
1. Add optional `fallbackModels` to `NodeData.config` (list of model strings, null = no fallback)
2. In `LlmService.resolveProvider()`, wrap the provider call in a try/catch:
   ```java
   String[] models = {primaryModel, fallback1, fallback2, ...};
   for (String model : models) {
       try { return doChat(model, systemPrompt, userPrompt); }
       catch (Exception e) { log.warn("Model {} failed: {}; trying fallback", model, e.getMessage()); }
   }
   throw new LlmException("All models exhausted");
   ```
3. Log which model was used in output summary
4. Frontend: add `fallbackModels` multi-select in BlockConfigPanel for agent/verifier nodes (Advanced section)

**Verification:** Unit test: primary model throws 429, fallback succeeds → response from fallback. `mvn compile -q` clean.

---

### Batch 3.2 — Retry Failed Stages

**Files:** `PipelineService.java` (existing retry endpoint)

**Current state:** `POST /api/schemas/{id}/pipeline/retry` exists but requires manual invocation. Agent nodes don't auto-retry on transient failures.

**Changes:**
1. Add `autoRetryCount` to `NodeData.config` (integer, default 0, max 3)
2. In `NodeRouter.executeNode()`, if `autoRetryCount > 0` and node fails with a transient error (429, 502, 503, timeout), retry up to `autoRetryCount` times with 5s backoff
3. Log each retry attempt: `"Retry 1/3 for node X after Y error"`
4. Return final error if all retries exhausted

**Verification:** Unit test: node fails with 429 → retries N times → succeeds on retry N. Node fails with 400 → no retry (non-transient).

---

## Phase 4 — Building

**Goal:** Generate compilable, buildable output for Android and iOS.

### Batch 4.1 — ANDROID_HOME Detection

**Files:** `ToolExecutor.java` (`build_app` handler), `DepsInstallDialog.vue`

**Current state:** No detection of `ANDROID_HOME` in deps needed check. Android builds fail silently.

**Changes:**
1. In `ToolExecutor.buildApp()`, add check:
   ```java
   String androidHome = System.getenv("ANDROID_HOME");
   if (androidHome == null || androidHome.isBlank()) {
       warnings.add("ANDROID_HOME not set — Android builds will fail. Add 'export ANDROID_HOME=...' to ~/.zshrc");
   }
   ```
2. Add ANDROID_HOME to `deps_needed` calculations in frontend `DepsInstallDialog.vue`

**Verification:** Integration: `build_app` tool shows ANDROID_HOME warning when env var is unset.

---

### Batch 4.2 — Build Commands for iOS and AppBundle

**Files:** `ToolExecutor.java` (line 129 `build_app` tool), `ProjectType.java`

**Current state:** `build_app` only runs `flutter build apk --debug`.

**Changes:**
1. In `buildApp()` handler, add build commands for each platform:
   - Always: `flutter build apk --debug` (existing)
   - If ANDROID_HOME set: also `flutter build appbundle --debug`
   - If `xcode-select -p` succeeds: also `flutter build ios --no-codesign`
2. Each build step is independent — failure in one doesn't block others
3. Add `--release` variant configurable via `NodeData.config.buildMode` (debug/release, default debug)

**Verification:** Integration: `build_app` tool runs appropriate commands per environment.

---

## Phase 5 — Provider

**Goal:** First-class OpenRouter support.

### Batch 5.1 — Endpoint Health Check

**Files:** `SettingsController.java`, `CustomLlmProvider.java`, frontend `SettingsView.vue`

**Current state:** User configures custom endpoint; bad config (wrong URL, invalid key) only surfaces mid-execution.

**Changes:**
1. Add `POST /api/settings/providers/test` endpoint that accepts custom endpoint fields and does a lightweight test call:
   ```java
   // Send empty chat completion request, check HTTP 200 + valid JSON response
   ```
2. Frontend "Test" button in SettingsView calls this endpoint and shows success/failure inline

**Verification:** Integration: endpoint returns success for valid OpenRouter config, error for invalid key.

---

### Batch 5.2 — Rate-Limit Retry for Custom Providers

**Files:** `CustomLlmProvider.java`

**Current state:** Zen provider has rate-limit retry (429 → wait → retry). CustomLlmProvider does not.

**Changes:**
1. In `sendRawHttpRequest`, detect 429 status:
   ```java
   if (response.statusCode() == 429) {
       String retryAfter = response.headers().firstValue("Retry-After").orElse("30");
       int waitSeconds = Integer.parseInt(retryAfter);
       log.warn("Rate limited, waiting {}s before retry", waitSeconds);
       Thread.sleep(waitSeconds * 1000L);
       return sendRawHttpRequest(endpoint, systemPrompt, userPrompt, usage); // recursive retry
   }
   ```
2. Add max 3 retries with exponential backoff to prevent infinite loops

**Verification:** Unit test: mock returns 429 twice, succeeds on 3rd → response returned. Mock returns 429 4 times → error returned.

---

## Phase 6 — Multi-Session

**Goal:** Chain multiple pipeline runs into an iterative development session.

### Batch 6.1 — Cross-Session Context Injection

**Files:** `ProjectContextBuilder.java`, `AgentNodeStrategy.java`

**Current state:** `ProjectContextBuilder.buildContext()` loads file tree + session history (completed tasks). It does NOT load previous execution outputs.

**Changes:**
1. In `ProjectContextBuilder`, add method `buildSessionContext(String targetPath, String workspaceId, String schemaId)`:
   - Fetch last `N` completed `ExecutionRun` records from Neo4j (via `ExecutionRepository`)
   - For each run, include: run status, node outputs (aggregated), files written (from `generatedFiles` in output)
   - Format as: `--- Session 3 (completed) ---\nFiles created:\n  - src/main.dart\n  - pubspec.yaml\nOutput: Dashboard screen with navigation`
2. In `AgentNodeStrategy` system prompt injection, include `sessionContext` alongside file tree
3. Add max `sessionHistoryCount` to config (default 3, max 10)

**Verification:** Integration: run pipeline twice → second run's agent prompt includes summary of first run's output.

---

### Batch 6.2 — Session Plan

**Files:** `Plan.java`, `ProjectContextBuilder.java`, frontend `StudioView.vue`

**Current state:** No "what to do this session" mechanism — agent always gets the full design spec.

**Changes:**
1. Add `sessionGoal` field to `Plan` (stored in Neo4j, settable via API or frontend)
2. `ProjectContextBuilder.buildContext()` includes session goal prominently: `"## This Session Goal\n\nAdd the analytics screen with charts"`  
3. Frontend: add "Session Goal" textarea in StudioView (above Run button), saved to plan via `PUT /api/plan/session-goal`

**Verification:** E2E: set session goal → run → agent prompt contains the goal.

---

## Phase 7 — Polish

**Goal:** Address remaining nice-to-have items (lower priority).

| Batch | Gap | Change | Effort |
|-------|-----|--------|--------|
| 7.1 | 3b — CocoaPods | Add `sudo gem install cocoapods` to deps-needed check in DepsInstallDialog | 0.5 day |
| 7.2 | 2c, 3d — AAB + iOS builds | Add `flutter build appbundle` and `flutter build ios` to build check auto-run list | 1 day |
| 7.3 | 7b — iOS archive | Add `flutter build ipa` command (after iOS build succeeds) | 1 day |
| 7.4 | 3e — IPA generation | Wrapper script or tool for `.ipa` packaging | 1 day |
| 7.5 | 6b — Flutter test | Add `flutter test` to build check (opt-in, behind `runTests` config toggle) | 2 days |
| 7.6 | 7d — Flutter version | Check `flutter --version` against latest stable; warn if outdated | 0.5 day |
| 7.7 | 6c — Visual regression | Future concern — needs screenshot infra. Skip for now. | — |
| 7.8 | 6d — Code size lint | Add file > 500 lines check to CodeQualityChecker | 1 day |
| 7.9 | 2b — Continue from partial | Deferred — needs per-file checkpoint tracking (heavy infra) | — |
| 7.10 | 4d — Checkpoint/restore | Deferred — needs execution state serialization | — |

---

## Batch Dependencies

```
Phase 1 (Tool calling) — no deps
  ↓
Phase 2 (Verification) — no deps on Phase 1 (independent)
  ↓
Phase 3 (Resilience) — depends on Phase 1 (need tool calling working first)
  ↓
Phase 4 (Building) — independent of 1-3
  ↓
Phase 5 (Provider) — independent
  ↓
Phase 6 (Multi-Session) — depends on Phase 2 (need verification to know session succeeded)
  ↓
Phase 7 (Polish) — all independent, can be done in any order
```

Phases 1 and 2 can run in parallel. Phases 4 and 5 can run in parallel. Phase 3 depends on Phase 1.

---

## Verification

| Gate | What | When |
|------|------|------|
| `mvn compile -q` | Backend compiles | After each batch |
| `mvn test` | All backend tests pass (242+) | After each phase |
| `vue-tsc --build` | Frontend type-checks | After frontend batches |
| `npm run test:unit` | All frontend tests pass (173+) | After each phase with frontend changes |
| Manual: EIOS pipeline | Full end-to-end: spec → generated Flutter app | After Phase 4 |
| Manual: OpenRouter | Pipeline with OpenRouter model (Nemotron-3) | After Phase 5 |
| Manual: iterative | 2+ runs with session goal → incremental code | After Phase 6 |

---

## New Tests Required

| Batch | Unit Tests | Integration Tests | E2E Tests |
|-------|-----------|-------------------|-----------|
| 1.1 — file_write aliases | 1 (alias routing) | 0 | 0 |
| 1.2 — OpenRouter headers | 1 (header injection) | 0 | 0 |
| 2.1 — Zero-tool-call detection | 2 (agent output, verifier verdict) | 1 (full pipeline) | 0 |
| 2.2 — Expected file count | 2 (warning logic, frontend field) | 0 | 1 |
| 2.3 — Stub detection auto-enable | 1 (QuickStart verifier config) | 0 | 0 |
| 3.1 — Model fallback | 2 (fallback success, all exhausted) | 1 | 0 |
| 3.2 — Retry failed stages | 2 (transient retry, non-transient pass-through) | 1 | 0 |
| 4.1 — ANDROID_HOME detection | 1 (warning when unset) | 1 | 0 |
| 4.2 — Build commands | 2 (per-platform build) | 1 | 0 |
| 5.1 — Health check | 1 (endpoint test) | 1 | 0 |
| 5.2 — Rate-limit retry | 2 (retry succeeds, max retries) | 0 | 0 |
| 6.1 — Session context | 2 (context build, prompt injection) | 1 | 1 |
| 6.2 — Session plan | 1 (plan field storage) | 0 | 1 |

**Total:** ~22 unit tests, ~7 integration tests, ~3 E2E tests
