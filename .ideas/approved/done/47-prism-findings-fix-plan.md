# PRISM Findings Fix Plan — Axolotl Backend Hardening (TRIMMED)

**Status:** Planned → Implementing  
**Priority:** High  
**Theme:** Reliability / Architecture  

---

## Stripped Findings (already done by Plans 43–49)

| Finding | Status | Implemented By |
|---------|--------|---------------|
| F1 — ExecutionUtilityService monolith | ✅ **Done** | Plan 49: ToolCallParser, NodeCommandExecutor, NodeSourceHandler, NodeFileWriter extracted |
| F5 — Per-node timeout | ✅ **Done** | Plan 49: `getTimeoutSeconds()` + `CompletableFuture.get()` in NodeRouter (default 300s) |
| F6 — State reconciliation | ✅ **Done** | Plan 49: `ExecutionStateReconciler` with `@PostConstruct` + `@Scheduled(fixedRate=300000)` |
| F11 — StudioView complexity | ✅ **Done** | Plan 46: useCanvasStore, usePipelineStore, useReviewStore, useSelectionStore, blockRegistry |

---

## Remaining Findings (to implement)

| # | Finding | Severity | Phase |
|---|---------|----------|-------|
| **F0** | Document operating envelope (ARCHITECTURE.md) | Medium | 0 |
| **F2** | NodeRouter registry pattern (switch → registry) | Low | 3 |
| **F3** | AgentController split (668 lines, all routes) | Medium | 3 |
| **F7** | WebSocket reconnect + state replay | High | 2 |
| **F8** | Circuit breaker for LLM providers | Medium | 1 |
| **F9** | Hop boundary validation (NodeOutputValidator) | Low | 4 |
| **F10** | API key model scoping | Low | 5 |
| **F12** | Feature flag system | Low | 5 |

**Note:** F4 (graph model duplication) de-prioritized — not causing active issues.

---

## Implementation Phases

### Phase 0 — Foundation (ARCHITECTURE.md)

**Files:**
- `backend/ARCHITECTURE.md` — new file

**Changes:**
1. Create `backend/ARCHITECTURE.md` documenting:
   - **Operating envelope:** single-user, single-schema execution at a time per process; no horizontal scaling
   - **Explicit non-goals:** multi-tenancy, queue-based execution, HA/failover, cross-process state sharing
   - **State model:** primary execution state is in-memory; Neo4j is cold storage for persistence and recovery
   - **Concurrency model:** virtual threads per node within one schema execution; no concurrent schema execution
   - **Timeout model:** per-node timeout (default 300s via `NodeData.config.timeoutSeconds`)

**Verification:** Reads correctly. `mvn compile -q` clean.

---

### Phase 1 — Circuit Breaker (F8)

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/CircuitBreakerWrapper.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/LlmService.java` — wrap provider calls
- `backend/src/main/java/com/agent/orchestrator/controller/SettingsController.java` — expose status

**Changes:**
1. Create lightweight `CircuitBreakerWrapper` with 3 states (CLOSED/OPEN/HALF_OPEN), per-provider tracking via `ConcurrentHashMap`
2. Config: failureThreshold=3, successThreshold=2, openDuration=30s
3. On OPEN, throw `CircuitBreakerOpenException` immediately
4. Wire into `LlmService.chat()`: wrap call with `circuitBreakerWrapper.call(providerName, () -> provider.chat(...))`
5. Expose state via existing `GET /api/settings/providers` (add `circuitState` field)

**Verification:** 3 unit tests (circuit opens, circuit closes, circuit stays open).

---

### Phase 2 — WebSocket Reconnect + State Replay (F7)

**Files:**
- `frontend/src/composables/useWebSocket.ts` — add reconnect + replay
- `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java` — add state endpoint
- `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java` — add replay message

**Changes:**
1. Backend: Add `GET /api/schemas/{id}/runs/{runId}/state` returning current execution snapshot (completedNodes, currentNodeId, stageStatus)
2. WebSocket: Add `replay` message type — on receipt, re-send `result` events for all completed nodes in the run
3. Frontend `useWebSocket`: Add `reconnect(runId)` method with auto-reconnect (1s/2s/4s backoff, max 30s), fetches state endpoint on reconnect, replays completed node results, re-subscribes if still running

**Verification:** Unit: composable reconnect fires state endpoint. Integration: disconnect → reconnect → state restored.

---

### Phase 3 — Structure (F2 + F3)

#### F3: AgentController Split

**Files:**
- `AgentController.java` — shrink to app-level routes
- `SchemaCrudController.java` — new (schema CRUD from AgentController)
- `ExecutionController.java` — new (execute/stop/runs/pipeline from AgentController)

**Changes:**
1. `SchemaCrudController` handles `GET/POST/PUT/DELETE /api/schemas`
2. `ExecutionController` handles `/api/schemas/{id}/execute`, `/stop`, `/runs`, `/pipeline/*`
3. `AgentController` keeps only `/api/app`, `/api/plan`, `/api/graph`, `/api/settings`
4. Target: each controller ≤250 lines, AgentController shrinks 668→~150

**Verification:** All backend tests pass. All API endpoints return same responses.

#### F2: NodeRouter Registry Pattern (lower priority)

**Files:**
- `NodeStrategyRegistry.java` — new (auto-collects `@Component` `NodeStrategy` beans)
- `NodeRouter.java` — replace `switch` with `registry.getStrategy(nodeType).execute(...)`

**Changes:**
1. Create `NodeStrategyRegistry` — `Map<String, NodeStrategy>` populated at startup by collecting all `@Component` `NodeStrategy` beans keyed by `supportedNodeType()`
2. Each `NodeStrategy` gets `String supportedNodeType()` method
3. NodeRouter: `registry.getStrategy(nodeType).execute(...)` instead of `switch(nodeType)`
4. NodeRouter constructor shrinks from 15+ deps to ~5

**Verification:** Unit: registry resolves correct strategy. All tests pass.

---

### Phase 4 — Hop Validation (F9)

**Files:**
- `backend/src/main/java/com/agent/orchestrator/validation/NodeOutputValidator.java` — new

**Changes:**
1. Create `NodeOutputValidator` — validates `outputSummary` structure per node type:
   - Agent: `response` string field
   - Review: `status`, `findings`, `summary`
   - Verifier: `status` (PASS/FAIL), `checks[]`
   - Output: `files[]` or `reportPath`
2. On validation failure → log warning + set `hasWarning` flag (not fail)
3. Behind feature flag (see Phase 5) — off by default

**Verification:** Unit: valid/invalid outputs per type.

---

### Phase 5 — Growth (F10 + F12)

#### F12: Feature Flags

**Files:**
- `backend/src/main/java/com/agent/orchestrator/config/FeatureFlags.java` — new record
- `application.yml` — add `axolotl.features.*`

**Changes:**
1. Create `FeatureFlags` record: `hopValidation` (default false), `circuitBreaker` (true), `controllerSplit` (false)
2. Wire into affected code paths via `@ConditionalOnProperty` or explicit boolean checks
3. Add `GET /api/features` endpoint returning current flag state

**Verification:** Unit: defaults match yml. Toggle enables/disables correctly.

#### F10: API Key Scoping (lowest priority)

1. Add optional `allowedModels` to Neo4j `ApiKey` node
2. On API key auth, intersect `allowedModels` with provider's `listModels()`
3. Empty = unrestricted (backwards-compatible)

---

## Batch Order

```
Phase 0 (ARCHITECTURE.md) → immediate, docs-only
        ↓
Phase 1 (circuit breaker) → standalone
        ↓
Phase 2 (WS reconnect) → standalone  
        ↓
Phase 3 (AgentController split + registry) → parallelizable internally
        ↓
Phase 4 (hop validation) → depends on feature flags
        ↓
Phase 5 (feature flags → API key scoping) → sequential
```

## Test Requirements

| Batch | Unit | Integration |
|-------|------|-------------|
| Phase 0 | 0 | 0 |
| Phase 1 (circuit breaker) | 3 | 0 |
| Phase 2 (WS replay) | 3 | 1 |
| Phase 3 (AgentController) | 0 | 0 |
| Phase 3 (registry) | 2 | 0 |
| Phase 4 (hop validation) | 3 | 0 |
| Phase 5 (feature flags) | 2 | 0 |
| Phase 5 (API key scoping) | 2 | 0 |
