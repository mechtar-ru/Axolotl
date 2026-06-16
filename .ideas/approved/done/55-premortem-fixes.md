# Plan 55: Premortem Fixes — Systemic Risk Mitigation

## Goal
Close all 10 findings from the 2026-06-17 premortem (2 CRITICAL, 4 HIGH, 4 MEDIUM).

## Strategy
4 phases ordered by ROI: thread/transaction safety → null/timeout robustness → persistence/config → frontend testing.

---

## Phase 1 — Critical Safety (3 batches, ~30 min)

### 1.1 `future.cancel(true)` on timeout (C1)
**Files**: `NodeRouter.java` lines 220-240
**Problem**: `CompletableFuture.get(timeoutSecs, ...)` → TimeoutException → `return` — but supplyAsync lambda still runs in ForkJoinPool.commonPool(). Thread leaked until strategy actually finishes.
**Fix**:
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> { ... });
try {
    result = future.get(timeoutSecs, TimeUnit.SECONDS);
} catch (TimeoutException te) {
    future.cancel(true);  // <-- interrupt the hanging thread
    // ... existing error handling ...
}
```
**Test**: Add `NodeRouterExecutionTimeoutTest` — call executeNode with a strategy that hangs >timeout, verify thread returns before strategy completes.
**Risk**: `cancel(true)` calls `Thread.interrupt()` — strategies must handle `InterruptedException` gracefully. Check all strategy dispatch points (agent loop, LLM HTTP calls, bash process).
**Rollback**: Revert single line addition.

### 1.2 Remove class-level @Transactional (C2)
**Files**: `SchemaExecutionService.java` line 25
**Problem**: `@Transactional` on class — ALL public methods wrapped in Neo4j transactions. `executeSchema()` (10+ min) will hit Neo4j default 60s transaction timeout.
**Fix**: Remove `@Transactional` from class. Add `@Transactional(readOnly = true)` to fast query methods only:
```java
@Transactional(readOnly = true)
public List<ExecutionRun> findExecutionRuns(String schemaId) { ... }

@Transactional(readOnly = true)  
public ExecutionRun getPausedRun(String schemaId) { ... }

@Transactional(readOnly = true)
public List<ExecutionRecord> getExecutionHistory(String schemaId) { ... }

@Transactional(readOnly = true)
public List<ExecutionRecord> getAllExecutionHistory() { ... }
```
Execution methods (`executeSchema`, `resumeExecution`, `handleReviewFeedback`, etc.) remain non-transactional — they write to DB explicitly via executionRepository, not through Spring Data Neo4j implicit transactions.
**Test**: `SchemaExecutionServiceTest` — verify fast query methods run within transaction, execution methods don't. No behavioral change.
**Risk**: Fast query methods that write (if any) would lose rollback protection. Verify each annotated method is genuinely read-only.
**Rollback**: Restore class-level `@Transactional`.

### 1.3 Zombie Bun process cleanup (H3)
**Files**: `PluginLifecycleManager.java`
**Problem**: JVM crash leaves Bun subprocess running. On restart, zombie accumulates with each deployment/crash cycle (~50-120MB RSS each).
**Fix**: In `PluginLifecycleManager.afterPropertiesSet()` (or `@PostConstruct`):
```java
@PostConstruct
public void cleanupOrphans() {
    try {
        Runtime.getRuntime().exec("pkill -f plugin-bridge.js").waitFor(5, TimeUnit.SECONDS);
    } catch (Exception e) {
        log.warn("Failed to clean up orphaned plugin processes: {}", e.getMessage());
    }
}
```
Also register JVM shutdown hook to kill subprocesses:
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    bridges.forEach(PluginBridge::stop);
}));
```
**Test**: `PluginLifecycleManagerTest` — verify pkill called on init, shutdown hook registered. Hard to test end-to-end without Bun, but mock ProcessBuilder.
**Risk**: `pkill -f` matches processes with "plugin-bridge.js" in command line. If user has unrelated processes with that string (unlikely), they'd be killed.
**Rollback**: Remove cleanup code from init.

---

## Phase 2 — Robustness (3 batches, ~45 min)

### 2.1 Null tool_call_id guard (H1)
**Files**: `ToolExecutorImpl.java` — tool execution loop
**Problem**: When LLM returns `tool_calls` with `id=null` (common with weak models), the iteration NPEs and the entire agent output is silently lost.
**Fix**: Add null-guard at the outermost tool execution loop in the dispatch method:
```java
for (ToolCall call : toolCalls) {
    if (call.id() == null || call.id().isBlank()) {
        log.warn("Skipping tool call with null/empty id: function={}", call.function().name());
        webSocketHandler?.sendLog(schemaId, "warning", "Tool call missing id, skipping", nodeId);
        continue;
    }
    // ... existing execution ...
}
```
Search for the iteration pattern — it's in `AgentNodeStrategy` around the tool execution loop or in `ToolExecutorImpl.execute()`.
**Test**: Add test case where LLM returns `tool_calls` with `null id` — verify skipped, not crashed.
**Risk**: Silent skip could hide real problems. Log at WARN level and include the function name so debugging is possible.
**Rollback**: Remove the guard.

### 2.2 OpenRouter hard timeout for all paths (H2)
**Files**: `CustomLlmProvider.java`, also check `OllamaProvider.java`/`OpenAiProvider.java` for provider-agnostic HTTP calls
**Problem**: OpenRouter free-tier proxy holds HTTP connections open >120s. With 3 retries × 120s, a single planner call blocks a ForkJoinPool thread for 6+ minutes. Already partially fixed for planner (60s timeout), but tool agent path still has no hard timeout.
**Fix**: In `CustomLlmProvider.sendRawHttpRequestWithRetry()`:
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> { ... });
try {
    return future.get(60, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    future.cancel(true);
    throw new LlmException("Provider request timed out after 60s");
}
```
Or, simpler: use `HttpClient.newHttpClient()` with `HttpClient.Builder.connectTimeout()` and `java.net.http.HttpRequest.timeout()` (Java 11+) instead of the current `URLConnection` / raw socket approach.
**Check**: Does `CustomLlmProvider` use `java.net.http.HttpClient` or `URLConnection`/`HttpURLConnection`? If `HttpURLConnection`, set `setConnectTimeout(5000)` and `setReadTimeout(60000)` on every connection.
**Test**: `CustomLlmProviderTest` — mock HTTP endpoint that hangs >60s, verify timeout exception.
**Risk**: 60s may be too short for long-running code generation. Consider making configurable: `axolotl.llm.openrouter.timeout: 120`.
**Rollback**: Revert timeout changes.

### 2.3 Neo4j connection pool config (H4)
**Files**: `application.yml`, `Neo4jConfig.java`
**Problem**: No explicit connection pool config. Spring Data Neo4j defaults may be insufficient for concurrent operations.
**Fix**: Add to `application.yml`:
```yaml
spring:
  neo4j:
    pool:
      max-active: 16
      max-idle: 4
      max-lifetime: 300000  # 5 min
      connection-timeout: 5000  # 5s
```
**Test**: No functional test needed. Verify connection pool metrics show correct values.
**Risk**: Low. Defaults are reasonable but explicit config prevents surprises.
**Rollback**: Remove config.

---

## Phase 3 — Persistence & Config (2 batches, ~30 min)

### 3.1 Persist generatedFiles to Neo4j (M1)
**Files**: `ExecutionStateManager.java`, `PipelineStageExecutionService.java`, `ProjectContextBuilder.java`
**Problem**: `generatedFilesRegistry` is a `ConcurrentHashMap` in memory — lost on JVM restart. `GraphExecutionRun.setGeneratedFiles()` is never called. Multi-session loses file tracking after restart.
**Fix**: In `PipelineStageExecutionService.onComplete()`:
```java
// After pipeline completes, persist generated files
List<String> generatedFiles = new ArrayList<>();
Object registry = stateManager.getGeneratedFilesRegistry().get("schema:" + schemaId);
if (registry != null) {
    generatedFiles = extractFileList(registry);
}
if (!generatedFiles.isEmpty()) {
    ExecutionRun run = executionRepository.getRunById(runId);
    if (run instanceof GraphExecutionRun) {
        String json = objectMapper.writeValueAsString(generatedFiles);
        ((GraphExecutionRun) run).setGeneratedFilesJson(json);
        // Need a repository method that saves generatedFilesJson
        updateRunGeneratedFiles(runId, json);
    }
}
```
Add `updateRunGeneratedFiles(String runId, String json)` to `ExecutionRepository`.
In `ProjectContextBuilder`, read from `GraphExecutionRun.getGeneratedFilesJson()` (deserialize to list) when `getGeneratedFiles()` returns null (which it will after restart).
**Test**: `PipelineStageExecutionServiceTest` — after pipeline completes, verify generatedFilesJson is set on the run. Restart test: verify after re-initialization, `ProjectContextBuilder` reads files from Neo4j.
**Risk**: JSON serialization of file list to a String field is hacky but pragmatic. If schema evolves, need migration.
**Rollback**: Remove the persistence call.

### 3.2 Externalize allowed commands to config (M2)
**Files**: `ToolHandlerService.java`, `application.yml`
**Problem**: Bash tool command whitelist is hardcoded in Java. Adding a new tool requires recompilation.
**Fix**: In `application.yml`:
```yaml
axolotl:
  tools:
    allowed-commands:
      - flutter
      - dart
      - pub
      - npm
      - npx
      - node
      - python3
      - pip3
      - git
      - cargo
      - go
      - pnpm
      - yarn
      - pkill
      - ls
      - cat
      - echo
      - mkdir
      - cp
      - mv
      - rm
```
In `ToolHandlerService.java`, change `DEFAULT_ALLOWED_COMMANDS` to read from `@Value("${axolotl.tools.allowed-commands}")` with a fallback to the current hardcoded list. Use `@ConfigurationProperties` or `@Value("#{${axolotl.tools.allowed-commands}}")`.
**Test**: `ToolHandlerServiceTest` — verify allowed commands loaded from config, custom list overrides default.
**Risk**: Misconfiguration could break all bash tool calls. Keep the Java default as fallback.
**Rollback**: Remove config, code falls back to hardcoded list.

---

## Phase 4 — Enhancement (2 batches, ~45 min)

### 4.1 Batch WebSocket token messages (M3)
**Files**: `ExecutionWebSocketHandler.java`
**Problem**: Agent streaming sends individual `sendToken()` per token. On long outputs, outbound message buffer grows unbounded. No backpressure.
**Fix**: Add batching in `sendToken()`:
```java
private final Map<String, StringBuilder> tokenBuffers = new ConcurrentHashMap<>();
private final ScheduledExecutorService tokenFlusher = Executors.newSingleThreadScheduledExecutor();

public void sendToken(String schemaId, String nodeId, String token) {
    String key = schemaId + ":" + nodeId;
    tokenBuffers.computeIfAbsent(key, k -> new StringBuilder()).append(token);
    // Flush every 100ms or every 200 chars
}

// Scheduled at 100ms interval
private void flushTokens() {
    tokenBuffers.forEach((key, buf) -> {
        if (buf.length() > 0) {
            String content = buf.toString();
            buf.setLength(0);
            // Actually send the batch
            sendMessage(key.split(":")[0], new TextMessage(...));
        }
    });
}
```
**Test**: `ExecutionWebSocketHandlerTest` — verify tokens are batched (N individual sends produce M batch sends, M < N).
**Risk**: Slightly increased latency (up to 100ms) per token display. Acceptable for LLM streaming.
**Rollback**: Revert to per-token sending.

### 4.2 Vitest for frontend composables (M4)
**Files**: `frontend/src/composables/useExecution.ts`, `frontend/src/composables/useReview.ts`, `vitest.config.ts` (create)
**Problem**: 0 frontend tests. StudioView (889L), QuickStartDialog (811L), TimelineView (880L) have no safety net.
**Fix**: This is exploratory — scope limited to composables which are easy to unit test:
1. Create `vitest.config.ts` (if not exist) with `jsdom` environment
2. Add `npm install -D vitest @vue/test-utils happy-dom`
3. Add test script to `package.json`: `"test": "vitest run"`
4. Write tests for `useExecution` composable: verify state transitions (IDLE → RUNNING → COMPLETED), verify cleanup on unmount
5. Write tests for `useReview` composable: verify approval/rejection state
**Test**: N/A (this adds tests). Verify `npm run test` passes.
**Risk**: Low. Composables are pure functions with no DOM dependency.
**Rollback**: Remove vitest config and tests.

---

## Dependency Graph

```
Phase 1 (30 min)
  1.1 ──→ 1.2 ──→ 1.3
              │
Phase 2 (45 min)  │
  2.1 ──→ 2.2 ──→ │ ──→ 2.3
                    │
Phase 3 (30 min)    │
  3.1 ←─────────────┘
  3.2
                    │
Phase 4 (45 min)    │
  4.1 ──→ 4.2 ──────┘
```

Phases 2.2 and 1.1 are related (both `future.cancel(true)` pattern) — do them close together.

Phase 3.1 depends on Phase 1.2 being done (SchemaExecutionService transaction fix ensures pipeline completion doesn't roll back).

All other batches are independent.

---

## Test Requirements

| Batch | Test file | New tests | Existing tests that must pass |
|-------|-----------|-----------|-------------------------------|
| 1.1 | `NodeRouterTest.java` (extend) | 2: timeout cancel, interrupt propagation | NodeRouterTest (4), full suite |
| 1.2 | `SchemaExecutionServiceTest.java` | 3: @Transactional on reads, none on writes, execution methods | all 391 |
| 1.3 | `PluginLifecycleManagerTest.java` | 2: pkill on init, shutdown hook | PluginLifecycleManagerTest (8) |
| 2.1 | `ToolExecutorImplTest.java` | 2: null tool_id skip, blank tool_id skip | ToolExecutorTest (4) |
| 2.2 | `CustomLlmProviderTest.java` | 2: 60s timeout, configurable timeout | — |
| 2.3 | N/A (config only) | — | all 391 |
| 3.1 | `PipelineStageExecutionServiceTest.java` | 2: generatedFiles persistence, restart read | all 391 |
| 3.2 | `ToolHandlerServiceTest.java` | 2: config override, fallback to defaults | — |
| 4.1 | `ExecutionWebSocketHandlerTest.java` | 2: batching, flush timing | — |
| 4.2 | N/A (frontend vitest) | 4: useExecution, useReview | `npm run type-check` |

**Total new tests**: ~21
**Target test count**: ~412

---

## Risk Matrix

| Batch | Risk | Mitigation |
|-------|------|------------|
| 1.1 | `cancel(true)` may leave resources dirty | Add `try/finally` in strategy dispatch, use `Thread.interrupted()` checks |
| 1.2 | `readOnly=true` query might write accidentally | Audit all annotated methods before committing |
| 2.2 | 60s too short for code gen | Make timeout configurable with 60s default |
| 3.1 | JSON serialization schema drift | Use List<String> + ObjectMapper, not raw JSON string |
| 4.2 | Vitest setup conflicts with existing Vue 3 tooling | Use same tsconfig, pin vitest version |

---

## Rollback Plan

If a batch breaks CI:
```bash
git revert <commit-hash> --no-edit
git push
```

If Phase 1 breaks production (unlikely — defensive changes only):
```bash
git revert HEAD~3..HEAD
```

---

## Success Criteria

- [ ] 1.1: timeout produces `future.cancel(true)`, no thread leak
- [ ] 1.2: `executeSchema()` runs without Neo4j transaction wrapper
- [ ] 1.3: zombie Bun pkill on startup, shutdown hook registered
- [ ] 2.1: null tool_call.id → WARN + skip, not NPE
- [ ] 2.2: OpenRouter HTTP calls timeout at configurable limit
- [ ] 2.3: Neo4j pool configured in application.yml
- [ ] 3.1: generatedFiles persisted to Neo4j, survives restart
- [ ] 3.2: allowed commands externalized to config
- [ ] 4.1: WebSocket tokens batched, memory bounded
- [ ] 4.2: Vitest runs, 4+ composable tests
- [ ] Full test suite: 391+ pass, 0 failures (411+ target)
- [ ] Frontend: `vue-tsc --noEmit` clean
