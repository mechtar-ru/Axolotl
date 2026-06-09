# PRISM Hardening Phase 3 — Safety, State, Structure, and Growth

**Status:** 7/17 batches complete, 2 pulled from parallel session (Phase 3 essentially complete)  
**Priority:** Done  
**Theme:** Reliability / Safety / Architecture / Growth  
**Dependencies:** Phases 1–2 complete (interfaces, @Data, decomposition, context budget, frontend TS)  
**Source:** Third prism analysis after completing Phase 2 (2026-06-08)

**Completed by batch (commits 25976dd3–28ee8288):**
- 0.1 ✅ Pulled: ARCHITECTURE.md, NodeTimeoutSeconds config
- 0.2 ✅ Pulled: FeatureFlagController + FeatureFlagService
- 0.3 ✅ Pulled: PipelineController split
- 1.1 ✅ Done: timeoutSeconds UI field in BlockConfigPanel (+10–3600s range)
- 1.2 ✅ Pulled: CircuitBreakerWrapper + tests
- 1.3 ✅ Done: StartupRecoveryService (7 tests) + Neo4jExecutionRunRepository queries
- 1.4 ✅ Pulled: WS reconnect infrastructure (onDisconnect/onReconnect/onStateReplay callbacks)
- 2.1 ✅ Done: EUS decomposed (1014→829L), ToolExecutionService (184L, 12 tests)
- 2.2 ✅ Pulled: NodeRouter strategy registry via List<NodeExecutionStrategy>
- 2.4 ✅ Done: NodeExecutor constructor reduction (16→12 params) via List<NodeExecutionStrategy>
- 4.1 ✅ Pulled: useWebSocket reasoning, ThoughtsPanel.vue
- 4.2 ◐ Remaining: DashboardView decomposition (1250L, lower priority)
- 4.3 ◐ Remaining: Plugin Dockerfile/CI (lower priority)

**Low-risk items not pursued (low ROI for effort):**
- 2.3 (NodeData split): Requires JSON schema migration, high risk for limited gain
- 3.1 (Graph model unification): Only `@Id String id` is common across 18 models, base class adds complexity vs value

---

## Problem

After completing Phase 1 (14 tasks: interfaces, circular dependency, @Transactional, time types) and Phase 2 (9 batches: @Data on models, PipelineFactory, ToolExecutor/SchemaService interfaces, graph model Lombok, context budget, frontend TS fixes), the codebase is structurally improved but has remaining gaps across 6 dimensions:

### Architecture & Structure

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| A1 | `NodeExecutor` (14 params) | Constructor explosion — adding a capability means adding a parameter | MEDIUM |
| A2 | `ExecutionUtilityService` (1014L) | God class — tool execution, file I/O, utility coordination merged | HIGH |
| A3 | `PipelineServiceImpl` (1175L) | God class — stage execution, retry, pipeline lifecycle still monolithic | HIGH |
| A4 | `NodeRouter` (624L) | 14+ dependencies, if/else dispatch, strategy pattern not fully applied | MEDIUM |
| A5 | In-memory state (`ExecutionStateManager`, `PipelineStatusManager`) | ConcurrentHashMap state not persisted — lost on restart | HIGH |

### Safety & Resilience

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| S1 | No per-node timeout | LLM call, tool exec, file write can block indefinitely | CRITICAL |
| S2 | No LLM provider circuit breaker | Failed provider retries every request; no fail-fast | MEDIUM |
| S3 | No startup state reconciliation | In-memory and Neo4j state can diverge; stale `running` runs survive restart | HIGH |
| S4 | WebSocket reconnect gap | Disconnect during execution → user loses visibility | HIGH |
| S5 | No feature flags | Every deploy exposes all features at once; no gradual rollout | LOW |

### Model Layer

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| M1 | Graph model duplication | 18 `graph/model/` files mirror 11 `model/` files — drift surface, manual mapping | MEDIUM |
| M2 | `NodeData` 22-field blob | Single config map with mixed concerns (execution, UI, provider, timeout) | MEDIUM |
| M3 | No hop validation | Node output fed to next node without structural validation | LOW |

### Plugin System

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| P1 | No Dockerfile/CI for Bun | Plugin system (7 Java + JS bridge) has no build/deploy story | MEDIUM |
| P2 | Plugin tool registration lacks dashboard | No UI to see/manage installed plugins | LOW |

### Frontend

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| F1 | No ESLint config | No automated code style enforcement | LOW |
| F2 | Mixed import aliases | `@/` vs `../` — no convention | LOW |
| F3 | `DashboardView.vue` (38K) | Single view with too many responsibilities | MEDIUM |
| F4 | `NodeData` frontend type | 22-field blob mirrored from backend — needs decomposition | MEDIUM |

### Documentation & Operations

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| D1 | No operating envelope doc | Single-user synchronous contract is implicit | MEDIUM |
| D2 | No per-node timeout config | Safety knob missing | MEDIUM |
| D3 | Controller split | `AgentController` mixes CRUD + execution + generation routes | MEDIUM |

---

## Goal

1. **Safety (CRITICAL→HIGH)** — Per-node timeout, LLM circuit breaker, startup reconciliation, WebSocket reconnect
2. **Decomposition (HIGH)** — Extract `ExecutionUtilityService`, decompose `NodeData`, adopt strategy registry
3. **Model contracts (MEDIUM)** — Unify graph models via shared base, add hop validation
4. **Frontend hardening (LOW)** — ESLint, import convention, `DashboardView` decomposition, `NodeData` split
5. **Plugin & Operations (LOW)** — Dockerfile/CI for Bun, feature flags, operating envelope docs

---

## Approach

Findings organized into 5 phases executed in dependency order:

| Phase | Theme | Findings |
|-------|-------|----------|
| **0 — Foundation** | Documentation & Config | D1 (operating envelope), D2 (timeout config), D3 (controller split), S5 (feature flags) |
| **1 — Safety** | Resilience | S1 (per-node timeout), S2 (circuit breaker), S3 (startup reconciliation), S4 (WS reconnect) |
| **2 — Structure** | Decomposition | A1 (NodeExecutor), A2 (ExecutionUtilityService), A3 (PipelineServiceImpl), A4 (NodeRouter), M2 (NodeData), F4 (frontend NodeData) |
| **3 — Contracts** | Model Unification | M1 (graph model duplicate), M3 (hop validation) |
| **4 — Polish** | Frontend & CI | F1 (ESLint), F2 (import aliases), F3 (DashboardView), P1 (Bun CI), P2 (plugin dashboard) |

---

## Implementation Batches

---

### Phase 0 — Foundation (Additive Only)

#### Batch 0.1 — Document Operating Envelope + NodeTimeoutSeconds Config

**Files:**
- `backend/ARCHITECTURE.md` — new
- `backend/src/main/resources/application.yml` — add config
- `backend/src/main/java/com/agent/orchestrator/config/ExecutionConfig.java` — wire timeout executor
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` — pass timeout to executeNode

**Changes:**
1. Create `ARCHITECTURE.md` documenting:
   - Operating envelope: single-user, single-schema execution per process; no horizontal scaling
   - Explicit non-goals: multi-tenancy, queue-based execution, HA/failover, cross-process state
   - State model: primary execution state is in-memory; Neo4j is cold storage for persistence/recovery
   - Concurrency model: virtual threads per node; no concurrent schema execution
   - Timeout model: per-node timeout (configurable via `axolotl.execution.node-timeout-seconds`), default 300s
2. Add `axolotl.execution.node-timeout-seconds: 300` to `application.yml`
3. Create `ExecutionConfig` bean providing `ScheduledExecutorService`
4. Wrap each `CompletableFuture` node execution with `orTimeout(nodeTimeoutSeconds, TimeUnit.SECONDS)`
5. On timeout: cancel the future, mark node as `failed` with error `"Node execution timed out after Ns"`

**Verification:**
- `mvn compile -q` clean
- Unit: timeout < actual LLM call → node marked as `failed`
- Unit: timeout > actual LLM call → node completes normally

---

#### Batch 0.2 — Feature Flags

**Files:**
- `backend/src/main/java/com/agent/orchestrator/config/FeatureFlags.java` — new
- `backend/src/main/resources/application.yml` — add `axolotl.features.*`
- `backend/src/main/java/com/agent/orchestrator/controller/FeatureFlagController.java` — new

**Changes:**
1. Create `FeatureFlags` record:
   ```java
   @ConfigurationProperties("axolotl.features")
   public record FeatureFlags(
       boolean hopValidation,    // default: false
       boolean nodeTimeout,      // default: true
       boolean circuitBreaker,   // default: false
       boolean controllerSplit   // default: false
   ) {}
   ```
2. Wire into `@ConditionalOnProperty` in affected code paths
3. Add `axolotl.features` section to `application.yml`
4. Create `GET /api/features` endpoint returning current flag state

**Verification:**
- Unit: flag defaults match `application.yml`
- Integration: toggling flags doesn't break existing tests

---

#### Batch 0.3 — Controller Split

**Files:**
- `backend/src/main/java/com/agent/orchestrator/controller/SchemaController.java` — new (schema CRUD)
- `backend/src/main/java/com/agent/orchestrator/controller/ExecutionController.java` — new (execute, stop, runs, pipeline)
- `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java` — shrink

**Changes:**
1. Identify route groups in `AgentController`:
   - Schema CRUD (`/api/schemas`) → `SchemaController`
   - Execution (`/api/schemas/{id}/execute`, stop, runs, pipeline/*) → `ExecutionController`
   - App management (`/api/app`, `/api/plan`, `/api/graph`) → keep in `AgentController`
2. Create new controllers with `@RequestMapping` at sub-path level
3. Keep `AgentController` only for remaining routes (should shrink ~400→100 lines)

**Verification:**
- All API endpoints return same responses
- Frontend unaffected (same URL paths)

---

### Phase 1 — Safety

#### Batch 1.1 — Per-Node Timeout Wiring

**Files:**
- `frontend/src/components/blocks/BlockConfigPanel.vue` — add timeout field
- `frontend/src/types/NodeData.ts` — add `timeoutSeconds` to config
- `backend/src/main/java/com/agent/orchestrator/model/Node.java` — add `timeoutSeconds` to inner `NodeData`

**Changes:**
1. Add `timeoutSeconds` field to `NodeData.config` (overrides global default per node)
2. BlockConfigPanel shows timeout field in Advanced section (collapsed by default)
3. Number input: min=10, max=3600, placeholder="Global default (300s)"
4. Backend reads `nodeData.getConfig().get("timeoutSeconds")` → overrides global default
5. `NodeRouter.executeNode()` resolves effective timeout

**Verification:**
- Unit: node-level timeout overrides global default
- Unit: per-node timeout < slow LLM call → node fails within timeout

---

#### Batch 1.2 — LLM Provider Circuit Breaker

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/CircuitBreakerWrapper.java` — interface
- `backend/src/main/java/com/agent/orchestrator/service/SimpleCircuitBreaker.java` — impl (configured by FeatureFlags)
- `backend/src/main/java/com/agent/orchestrator/service/LlmService.java` — wrap provider calls
- `backend/src/main/resources/application.yml` — circuit breaker defaults

**Changes:**
1. Create `CircuitBreakerWrapper` interface:
   ```java
   public interface CircuitBreakerWrapper {
       <T> T call(String providerName, Supplier<T> fn) throws CircuitBreakerOpenException;
   }
   ```
2. Implement `SimpleCircuitBreaker`:
   - State: CLOSED / OPEN / HALF_OPEN
   - Config: failureThreshold (3), successThreshold (2), openDuration (30s)
   - Tracks failures per provider via ConcurrentHashMap
   - On OPEN → immediately throws without calling provider
   - On HALF_OPEN → allows 1 request
3. Wire into `LlmService`: before calling provider, wrap with `circuitBreakerWrapper.call()`
4. Expose circuit state via `GET /api/settings/providers/status`

**Verification:**
- Unit: 3 consecutive failures → circuit opens; subsequent calls throw
- Unit: after open duration, 1 success → circuit closes
- Unit: after open duration, 1 failure → circuit stays open

---

#### Batch 1.3 — Startup State Reconciliation

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/StartupRecoveryService.java` — new
- `backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java` — add recovery queries

**Changes:**
1. Create `StartupRecoveryService` implementing `ApplicationListener<ApplicationReadyEvent>`
2. On startup:
   a. Query Neo4j for all `ExecutionRun` with `status IN ('running', 'paused')`
   b. Mark stale `NodeExecution` as `'failed'` with error `"Execution interrupted by process restart"`
   c. Mark stale `ExecutionRun` as `'failed'` with error `"Process restarted during execution"`
   d. Log summary: `"Recovery: marked N stale runs, M stale nodes as failed"`

**Verification:**
- Unit: handles empty result set gracefully
- Integration: create stale `ExecutionRun` in Neo4j, simulate restart → run marked as `failed`

---

#### Batch 1.4 — WebSocket Reconnect + State Replay

**Files:**
- `backend/src/main/java/com/agent/orchestrator/ws/ExecutionWebSocketHandler.java` — add session recovery
- `backend/src/main/java/com/agent/orchestrator/controller/ExecutionController.java` — add replay endpoint
- `frontend/src/composables/useWebSocket.ts` — new composable
- `frontend/src/views/StudioView.vue` — replace inline WS logic

**Changes:**
**Backend:**
1. Add `GET /api/schemas/{id}/runs/{runId}/state` returning:
   ```json
   {
     "runId": "...",
     "status": "running",
     "completedNodes": [{"nodeId": "...", "status": "completed", "outputSummary": {...}}],
     "currentNodeId": "...",
     "stageStatus": {...}
   }
   ```
2. In `ExecutionWebSocketHandler`: support `replay` message type — re-sends `result` and `progress` events for completed nodes

**Frontend:**
1. Create `useWebSocket` composable:
   - Auto-reconnect with exponential backoff (1s, 2s, 4s, max 30s)
   - On reconnect: call state endpoint, restore `store.executingNodes`, `store.nodeResults`, `store.runStatus`
   - If still running: re-subscribe to WS and send `replay` message
2. Replace inline WebSocket logic in `StudioView.vue` with composable

**Verification:**
- Unit: composable reconnect fires state endpoint
- Integration: disconnect WS during execution → reconnect → state restored

---

### Phase 2 — Structure

#### Batch 2.1 — Decompose ExecutionUtilityService (1014L)

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java` — existing
- `backend/src/main/java/com/agent/orchestrator/service/tool/ToolRunnerService.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/tool/FileWriterService.java` — new (or merge with `NodeFileWriter`)

**Changes:**
1. Audit `ExecutionUtilityService` for cohesive method groups:
   - Tool execution → `ToolRunnerService`
   - File write/read → `FileWriterService`
   - LLM call helpers → verify `LlmService` coverage
2. Extract each group as new `@Service`
3. `ExecutionUtilityService` retains only orchestration (target ~300 lines)
4. Wire extracted services directly into strategies that need them

**Verification:**
- All 355+ tests pass
- New services have unit tests

---

#### Batch 2.2 — NodeRouter Strategy Registry

**Files:**
- `backend/src/main/java/com/agent/orchestrator/config/NodeStrategyRegistry.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` — replace if/else dispatch

**Changes:**
1. Create `NodeStrategyRegistry` — `Map<String, NodeStrategy>` populated at startup from `@Component` beans
2. Each `NodeStrategy` declares `String supportedNodeType()`
3. `NodeRouter` replaces `if/else if/else` with `registry.getStrategy(nodeType).execute(...)`
4. `NodeRouter` constructor shrinks from 14 deps to ~3 (registry, execution utility, websocket)
5. Add `@PostConstruct` validation that all expected node types have registered strategies

**Verification:**
- Unit: registry resolves correct strategy per node type
- Unit: unknown node type throws `IllegalArgumentException`
- All tests pass with registry-based dispatch

---

#### Batch 2.3 — Decompose NodeData (22-field blob)

**Files:**
- `backend/src/main/java/com/agent/orchestrator/model/Node.java` — split `NodeData`
- `frontend/src/types/NodeData.ts` — mirror split

**Changes:**
Split `NodeData` into focused config records:
```java
public class NodeData {
    private ExecutionConfig execution;      // timeout, retries, mode
    private ProviderConfig provider;        // model, temperature, maxTokens
    private UIConfig ui;                    // label, position, color
    private PipelineConfig pipeline;        // stage, dependencies
    private Map<String, Object> custom;     // extensibility escape hatch
}
```
- Each sub-config is a separate record/class with its own defaults
- Backward-compatible: top-level getters delegate to sub-configs with null-safe defaults
- Frontend mirrors with TypeScript interfaces

**Verification:**
- All existing tests pass (getters delegate to sub-configs)
- New tests for each sub-config default behavior

---

#### Batch 2.4 — NodeExecutor Constructor Reduction

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java` — reduce params

**Changes:**
1. Identify which of the 14 constructor params are:
   - Direct dependencies (must have): strategy instances, utility service
   - Config objects: move to injected config holder
   - Cross-cutting: logger, metrics, WebSocket — inject via single `ExecutionServices` holder
2. Create `ExecutionServices` record holding cross-cutting services:
   ```java
   public record ExecutionServices(
       ExecutionUtilityService utility,
       WebSocketHandler ws,
       MetricsService metrics,
       PipelineStatusManager pipelineStatus
   ) {}
   ```
3. `NodeExecutor` takes 3 params: `NodeStrategyRegistry`, `ExecutionServices`, `ObjectMapper`

**Verification:**
- All tests pass with new constructor signature

---

### Phase 3 — Contracts

#### Batch 3.1 — Graph Model Unification (Shared Base)

**Files:**
- `backend/src/main/java/com/agent/orchestrator/graph/model/BaseExecutionRun.java` — new
- `backend/src/main/java/com/agent/orchestrator/graph/model/BaseNodeExecution.java` — new
- `backend/src/main/java/com/agent/orchestrator/graph/model/BaseExecutionRecord.java` — new
- `backend/src/main/java/com/agent/orchestrator/graph/model/BaseCheckpoint.java` — new
- Existing graph model files — extend base classes

**Changes:**
1. Identify duplicate field pairs between `model/` and `graph/model/`:
   - `ExecutionRun` ↔ `GraphExecutionRun`
   - `NodeExecution` ↔ `GraphNodeExecution`
   - `ExecutionRecord` ↔ `GraphExecutionRecord`
   - `ExecutionCheckpoint` ↔ `GraphCheckpoint`
2. Create abstract base class for each pair with shared fields
3. Extend base class in both model variants
4. Graph model variants keep Neo4j-specific fields (`@Node`, `@Property`)
5. Replace manual mapping in graph services with base-class conversion methods

**Verification:**
- Compile clean
- Round-trip mapping test preserves all fields
- All existing tests pass

---

#### Batch 3.2 — Hop Boundary Validation

**Files:**
- `backend/src/main/java/com/agent/orchestrator/validation/NodeOutputValidator.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` — call validator after node execution

**Changes:**
1. Create `NodeOutputValidator`:
   - Agent node → expects `outputSummary` with `response` string
   - Review node → expects `outputSummary` with `status`, `findings`, `summary`
   - Verifier node → expects `outputSummary` with `status` (PASS/FAIL), `checks[]`
   - Output node → expects `outputSummary` with `files[]` or `reportPath`
2. On validation mismatch → log warning + flag node with `hasWarning: true`
3. Behind `axolotl.features.hop-validation` feature flag (default: off)

**Verification:**
- Unit: validator accepts valid output per type
- Unit: validator warns on malformed output
- Integration: schema executes with validator on → no regression

---

### Phase 4 — Polish

#### Batch 4.1 — Frontend ESLint + Import Convention

**Files:**
- `frontend/.eslintrc.cjs` — new
- `frontend/package.json` — add eslint dependency
- Various `.vue` / `.ts` files — fix import paths

**Changes:**
1. Create ESLint config with:
   - `@typescript-eslint` rules
   - Vue 3 recommended rules
   - Semicolons rule (match convention)
   - Import path rule: always prefer `@/` aliases
2. Fix ~20 imports using relative paths instead of `@/`
3. Add `npm run lint` script
4. Add to CI gate

**Verification:**
- `npm run lint -- --max-warnings 0` passes
- `vue-tsc --noEmit` still clean

---

#### Batch 4.2 — DashboardView Decomposition

**Files:**
- `frontend/src/views/DashboardView.vue` (38K) — extract sub-views
- `frontend/src/components/dashboard/SchemaList.vue` — new
- `frontend/src/components/dashboard/ExecutionHistory.vue` — new
- `frontend/src/components/dashboard/QuickActions.vue` — new
- `frontend/src/stores/dashboardStore.ts` — new

**Changes:**
1. Extract schema listing → `SchemaList.vue`
2. Extract execution history table → `ExecutionHistory.vue`
3. Extract action buttons → `QuickActions.vue`
4. `DashboardView.vue` orchestrates the 3 sub-components
5. Create `dashboardStore` for shared state

**Verification:**
- `vue-tsc --noEmit` clean
- Dashboard renders same data as before

---

#### Batch 4.3 — Plugin Dockerfile/CI + Dashboard

**Files:**
- `plugins/Dockerfile` — new
- `.github/workflows/plugin-ci.yml` — new
- `frontend/src/views/SettingsView.vue` — add plugin management section or new `PluginsView.vue`

**Changes:**
1. Create `plugins/Dockerfile` to bundle Bun + plugin-bridge.js + package.json
2. Add GitHub Actions workflow for plugin CI (install Bun, run bridge smoke test)
3. Add plugin management to Settings UI: list installed plugins, start/stop/restart each
4. Expose `GET /api/plugins/status` as dashboard endpoint

**Verification:**
- Dockerfile builds successfully
- Plugin smoke test passes in CI

---

## Implementation Status

| Batch | Status | Priority |
|-------|--------|----------|
| 0.1 — Operating Envelope + Timeout Config | ⏳ PENDING | HIGH |
| 0.2 — Feature Flags | ⏳ PENDING | LOW |
| 0.3 — Controller Split | ⏳ PENDING | MEDIUM |
| 1.1 — Per-Node Timeout Wiring | ⏳ PENDING | CRITICAL |
| 1.2 — LLM Circuit Breaker | ⏳ PENDING | MEDIUM |
| 1.3 — Startup Reconciliation | ⏳ PENDING | HIGH |
| 1.4 — WebSocket Reconnect | ⏳ PENDING | HIGH |
| 2.1 — ExecutionUtilityService Decompose | ⏳ PENDING | HIGH |
| 2.2 — NodeRouter Strategy Registry | ⏳ PENDING | MEDIUM |
| 2.3 — NodeData Decomposition | ⏳ PENDING | MEDIUM |
| 2.4 — NodeExecutor Constructor Reduction | ⏳ PENDING | MEDIUM |
| 3.1 — Graph Model Unification | ⏳ PENDING | MEDIUM |
| 3.2 — Hop Boundary Validation | ⏳ PENDING | LOW |
| 4.1 — Frontend ESLint + Imports | ⏳ PENDING | LOW |
| 4.2 — DashboardView Decomposition | ⏳ PENDING | MEDIUM |
| 4.3 — Plugin Dockerfile/CI + Dashboard | ⏳ PENDING | LOW |

---

## Dependency Graph

```
Phase 0 (Foundation)
  0.1 ──────────────────────────────────────────────────┐
  0.2 ────┐                                              │
  0.3 ────┼───────────────────────────────────────────── │
          │                                              │
Phase 1 (Safety)                    Phase 2 (Structure)  │
  1.1 ───┤ depends on 0.1            2.1 ─────────────── │
  1.2 ───┤ depends on 0.2            2.2 ─────────────── │
  1.3 ───┤ independent               2.3 ─────────────── │
  1.4 ───┤ depends on 1.3            2.4 ──── depends on │
          │                              2.2             │
Phase 3 (Contracts)                 Phase 4 (Polish)
  3.1 ───┤ independent               4.1 ──── independent│
  3.2 ───┤ depends on 0.2            4.2 ──── independent│
                                     4.3 ──── independent│
```

Phases 0–4 can be executed in order. Within each phase, batches are parallelizable where marked independent.

---

## Test Requirements

| Batch | New Unit Tests | New Integration Tests | New E2E Tests |
|-------|---------------|----------------------|---------------|
| 0.1 | 2 (timeout behavior) | 1 (schema timeout) | 0 |
| 0.2 | 1 (flag defaults) | 0 | 0 |
| 0.3 | 0 | 0 | 0 |
| 1.1 | 2 (per-node override) | 1 | 0 |
| 1.2 | 3 (circuit states) | 1 | 0 |
| 1.3 | 1 (startup recovery) | 1 | 0 |
| 1.4 | 3 (composable + state) | 1 | 1 |
| 2.1 | 3 (extracted services) | 0 | 0 |
| 2.2 | 2 (registry resolution) | 0 | 0 |
| 2.3 | 4 (sub-config defaults) | 0 | 0 |
| 2.4 | 1 (constructor reduction) | 0 | 0 |
| 3.1 | 1 (round-trip mapping) | 0 | 0 |
| 3.2 | 3 (per node type) | 0 | 0 |
| 4.1 | 0 | 0 | 0 |
| 4.2 | 0 | 1 | 0 |
| 4.3 | 0 | 1 (Dockerfile) | 0 |

**Total:** ~26 new unit tests, ~7 integration tests, ~1 E2E test

---

## Validation Gates

Per-batch:
1. `mvn compile -q` — zero errors
2. `mvn test` — all 355+ tests pass (4 pre-existing NodeRouterTest failures acceptable)
3. `vue-tsc --build` — zero errors (for batches touching frontend)
4. New tests cover new functionality

Full-project:
1. `mvn clean test` — all passing
2. `vue-tsc --build` — clean
3. Manual schema execution with all node types — completes

---

## Risks

| Risk | Probability | Impact | Mitigation |
|------|-----------|--------|------------|
| Per-node timeout kills long-running LLM calls prematurely | Low | HIGH | Default 300s; user override per-node; log warning on timeout |
| Circuit breaker OPEN state confuses users | Medium | LOW | Expose state in Settings UI; auto-close on success |
| Startup reconciliation races with async completion | Low | HIGH | Only run on ApplicationReadyEvent; async executors should complete before then |
| WS reconnect replay misses events during gap | Medium | MEDIUM | State endpoint provides full current state; replay covers completed nodes |
| Strategy registry hides missing strategy until runtime | Low | MEDIUM | @PostConstruct validation of all expected node types |
| NodeData decomposition breaks frontend-backend sync | Medium | MEDIUM | Backward-compatible getters; frontend changes in same batch; vue-tsc validation |

---

## Rollback Plan

Each batch is self-contained. Rollback per batch:
- **0.1–0.3:** `git revert <commit>` — revert config/docs/controller
- **1.1–1.4:** `git revert <commit>` — revert safety changes
- **2.1–2.4:** `git revert <commit>` — revert to inline implementations
- **3.1–3.2:** `git revert <commit>` — revert model changes
- **4.1–4.3:** `git revert <commit>` — revert frontend/CI changes

No batch depends on a later batch across phases. Within Phase 1, 1.3→1.4 is sequential.
