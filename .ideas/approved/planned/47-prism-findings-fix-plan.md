# PRISM Findings Fix Plan — Axolotl Backend Hardening

**Status:** Planned  
**Priority:** High  
**Theme:** Reliability / Architecture  
**Dependencies:** None (standalone fixes against current codebase)  
**Source:** PRISM multi-pass adversarial analysis (see `.hermes/plans/2024-06-09_211000-axolotl-prism-full.md`)

---

## Problem

A full multi-pass adversarial PRISM analysis of the Axolotl backend surfaced **12 findings** (F1–F12) plus a **deepest finding** about the implicit single-user synchronous execution contract. The findings span structural debt, safety gaps, resilience holes, and documentation deficits.

### Findings Summary

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| F1 | `ExecutionUtilityService` (1537 lines) | Monolith with 40+ imports; every new feature concentrates here | Medium |
| F2 | `NodeRouter` (486 lines, 14 deps) | Constructor bloat; adding a node type means growing this class or adding abstraction | Medium |
| F3 | `AgentController` | Catch-all controller; CRUD + execution + AI-gen routes in one class | Medium |
| F4 | Graph service boundary | `WorkflowSchema` / `GraphWorkflowSchema` model duplication; mapping layer is a drift source | Medium |
| F5 | No per-node timeout | LLM call, tool exec, file write can block indefinitely; one slow node takes down the entire workflow | **High** |
| F6 | No state reconciliation | In-memory state and Neo4j state can diverge; no startup recovery mechanism | **High** |
| F7 | WebSocket reconnect gap | Disconnect during execution → user loses visibility; no state replay on reconnect | **High** |
| F8 | No circuit breaker for LLM providers | Failed provider retries every request; no fail-fast or provider-status feedback | Medium |
| F9 | No output schema validation between hops | Router output feeds strategy feeds utility; no validation that intermediate data matches expected shape | Medium |
| F10 | API keys bypass user model settings | Key-based auth can access models user wouldn't normally have; no unified permission model | Low |
| F11 | Frontend `StudioView` complexity | Single view orchestrating 15+ sub-components with complex reactive state | Medium |
| F12 | No feature flags | Every deploy exposes all new features at once; no gradual rollout for node types or LLM providers | Low |

### Deepest Finding (Architectural)

The **implicit single-user synchronous execution contract** is Axolotl's defining architectural constraint. The entire design — in-memory state, no timeouts, no circuit breakers, no reconciliation, no horizontal scaling — is *correct* under the premise of one user, one workflow, at a time. But this premise is undocumented. A future developer adding multi-user support, async execution, or horizontal scaling will discover these constraints the hard way.

---

## Goal

Harden the Axolotl backend by:
1. **Documenting the operating envelope** — making the single-user contract explicit and adding a `NodeTimeoutSeconds` safety net
2. **Addressing 3 High-severity findings** (F5, F6, F7) that can cause silent data loss or indefinite hangs
3. **Addressing 6 Medium-severity findings** (F1, F2, F3, F4, F8, F9) that accumulate technical debt and regression risk
4. **Addressing 2 Low-severity findings** (F10, F12) that block future growth
5. **Addressing F11** (frontend `StudioView`) separately as a frontend-only workstream

---

## Approach

Fix findings in priority order grouped into 6 phases:

| Phase | Findings | Theme | Risk |
|-------|----------|-------|------|
| **0 — Foundation** | Deepest Finding + F5 anchor | Document contract; add NodeTimeoutSeconds config | Low — additive only |
| **1 — Safety** | F5 (per-node timeout), F8 (circuit breaker) | Prevent indefinite hangs; fail fast on provider failure | Medium — touches LLM execution path |
| **2 — State** | F6 (reconciliation), F7 (WebSocket reconnect) | Survive restarts; survive disconnects | High — needs careful design |
| **3 — Structure** | F1 (ExecutionUtilityService), F2 (NodeRouter), F3 (AgentController) | Extract, separate, simplify | Medium — mostly mechanical |
| **4 — Contracts** | F4 (model duplication), F9 (hop validation) | Unify models; add boundary validation | Low — additive |
| **5 — Growth** | F10 (API key scoping), F12 (feature flags) | Permission model; gradual rollout | Low — additive, no immediate prod impact |

### Design Principle: Additive Changes First

Every fix in this plan is **additive** — adding new files, classes, config fields, or wrappers without changing the existing execution flow. The only exception is F1/F2 extraction, which can be done incrementally by introducing wrappers before removing old code paths.

---

## Implementation Batches

### Phase 0 — Foundation (Deepest Finding + F5 anchor)

**Objective:** Make the single-user synchronous contract explicit and add the configuration knob for per-node timeouts.

#### Batch 0.1 — Document Operating Envelope

**Files:**
- `backend/ARCHITECTURE.md` — new file
- `backend/src/main/resources/application.yml` — add comment block
- `README.md` — reference architecture doc

**Changes:**
1. Create `backend/ARCHITECTURE.md` documenting:
   - **Operating envelope:** single-user, single-schema execution at a time per process; no horizontal scaling
   - **Explicit non-goals:** multi-tenancy, queue-based execution, HA/failover, cross-process state sharing
   - **State model:** primary execution state is in-memory; Neo4j is cold storage for persistence and recovery
   - **Concurrency model:** virtual threads per node within one schema execution; no concurrent schema execution
   - **Timeout model:** per-node timeout (configurable via `axolotl.execution.node-timeout-seconds`), default 300s
2. Add `axolotl.execution.node-timeout-seconds: 300` to `application.yml` with explanatory comment
3. Add reference to `ARCHITECTURE.md` in `README.md` architecture section
4. Add reference in `AGENTS.md` so AI agents know the operating bounds

**Verification:** `mvn compile -q` clean; docs render correctly.

---

#### Batch 0.2 — NodeTimeoutSeconds Config

**Files:**
- `backend/src/main/java/com/agent/orchestrator/config/AxolotlProperties.java` — new config class (if not exists) or add field to existing config
- `backend/src/main/java/com/agent/orchestrator/config/ExecutionConfig.java` — wire timeout into executor
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` — pass timeout to executeNode

**Changes:**
1. Add `execution.node-timeout-seconds` config property to `AxolotlProperties` or equivalent config holder
2. Create `ExecutionConfig` bean providing `ScheduledExecutorService` with configurable timeout
3. Modify `NodeRouter.executeNode()` to accept optional timeout parameter
4. In `PipelineService` / `SchemaService.executeSchema()`, pass timeout to each node execution
5. Wrap each `CompletableFuture` node execution with `orTimeout(nodeTimeoutSeconds, TimeUnit.SECONDS)` — on timeout, cancel the future, mark node as `failed` with error `"Node execution timed out after Ns"`

**Verification:**
- Unit test: timeout < actual LLM call → node marked as `failed` with timeout error
- Unit test: timeout > actual LLM call → node completes normally
- Integration test: schema executes with timeout config

---

### Phase 1 — Safety (F5 per-node timeout + F8 circuit breaker)

**Objective:** Prevent indefinite hangs and fail fast on provider failure.

#### Batch 1.1 — Per-Node Timeout Wiring

**Files:**
- `frontend/src/components/blocks/BlockConfigPanel.vue` — add timeout field
- `frontend/src/types/NodeData.ts` — add `timeoutSeconds` to config
- `backend/src/main/java/com/agent/orchestrator/model/NodeData.java` — add `timeoutSeconds` field

**Changes:**
1. Add optional `timeoutSeconds` to `NodeData.config` (overrides global default per node)
2. BlockConfigPanel shows timeout field in Advanced section (collapsed by default), number input with min=10, max=3600, placeholder="Global default (300s)"
3. Frontend saves `config.timeoutSeconds` into `NodeData.config`
4. Backend reads `nodeData.getConfig().get("timeoutSeconds")` — if present, overrides global default
5. `NodeRouter.executeNode()` resolves the effective timeout: `nodeData timeoutSeconds ?? global default`

**Verification:**
- Unit: node-level timeout overrides global default
- E2E: BlockConfigPanel shows/hides timeout field for agent/verifier nodes
- Integration: schema with one node at 5s timeout and slow LLM → node fails with timeout error

---

#### Batch 1.2 — LLM Provider Circuit Breaker

**Files:**
- `backend/pom.xml` — add `resilience4j-spring-boot3` or implement lightweight wrapper
- `backend/src/main/java/com/agent/orchestrator/service/LlmService.java` — wrap provider calls
- `backend/src/main/java/com/agent/orchestrator/config/ResilienceConfig.java` — circuit breaker config
- `backend/src/main/resources/application.yml` — circuit breaker defaults

**Changes:**
**Approach:** Use a lightweight `CircuitBreaker` wrapper (either resilience4j or a simple custom implementation — resilience4j adds ~3 deps, a custom one adds ~60 lines). Low complexity favors custom.

1. Create `CircuitBreakerWrapper` interface:
   ```java
   public interface CircuitBreakerWrapper {
       <T> T call(String providerName, Supplier<T> fn);
   }
   ```
2. Create `SimpleCircuitBreaker` implementation:
   - State: CLOSED / OPEN / HALF_OPEN
   - Config: failureThreshold (default 3), successThreshold (default 2), openDuration (default 30s)
   - Tracks failures per provider name (ConcurrentHashMap)
   - On OPEN, throws `CircuitBreakerOpenException` immediately without calling provider
   - On CLOSED, calls provider, records success/failure
   - On HALF_OPEN, allows 1 request, if it succeeds → CLOSED, if it fails → OPEN again
3. Wire into `LlmService`: before calling provider, wrap call with `circuitBreakerWrapper.call(providerName, () -> provider.chat(...))`
4. Expose circuit breaker state via `GET /api/settings/providers/status` — returns per-provider state (CLOSED/OPEN/HALF_OPEN)

**Verification:**
- Unit: 3 consecutive failures → circuit opens; subsequent calls throw `CircuitBreakerOpenException`
- Unit: after open duration, 1 success → circuit closes
- Unit: after open duration, 1 failure → circuit stays open

---

### Phase 2 — State (F6 reconciliation + F7 WebSocket reconnect)

**Objective:** Survive backend restarts and WebSocket disconnects without losing execution state.

#### Batch 2.1 — Startup State Reconciliation

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/ExecutionRepository.java` — add recovery queries
- `backend/src/main/java/com/agent/orchestrator/service/StartupRecoveryService.java` — new file
- `backend/src/main/java/com/agent/orchestrator/config/SchedulingConfig.java` — reuse existing scheduling

**Changes:**
1. Create `StartupRecoveryService` implementing `ApplicationListener<ApplicationReadyEvent>`
2. On startup:
   a. Query Neo4j for all `ExecutionRun` records with `status IN ('running', 'paused')`
   b. For each such run:
      - Mark any `NodeExecution` with status `'running'` as `'failed'` with error `"Execution interrupted by process restart"`
      - Mark the `ExecutionRun` as `'failed'` with error `"Process restarted during execution"`
   c. Log summary: `"Recovery: marked N stale runs as failed, M stale nodes as failed"`
3. Existing `ExecutionLogCleanupService` cleanup unaffected

**Verification:**
- Integration test: create `ExecutionRun` with status `'running'` in Neo4j, simulate app restart via `ApplicationReadyEvent` → run marked as `'failed'`
- Unit: `StartupRecoveryService` handles empty result set gracefully

---

#### Batch 2.2 — WebSocket Reconnect + State Replay

**Files:**
- `backend/src/main/java/com/agent/orchestrator/ws/ExecutionWebSocketHandler.java` — add session recovery
- `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java` — add replay endpoint
- `frontend/src/views/StudioView.vue` — reconnect logic
- `frontend/src/composables/useWebSocket.ts` — new composable for WS lifecycle

**Changes:**
**Backend:**
1. Add `GET /api/schemas/{id}/runs/{runId}/state` endpoint returning current execution state:
   ```json
   {
     "runId": "...",
     "status": "running",
     "completedNodes": [{"nodeId": "...", "status": "completed", "outputSummary": {...}}],
     "currentNodeId": "...",
     "stageStatus": {...}
   }
   ```
2. In `ExecutionWebSocketHandler`:
   - Add `replay` message type — when client sends `{"type": "replay", "runId": "..."}`, server re-sends all `result` and `progress` events for completed nodes
   - Track sessionId → runId mapping for cleanup

**Frontend:**
1. Create `useWebSocket` composable with:
   - Auto-reconnect with exponential backoff (1s, 2s, 4s, max 30s)
   - On reconnect, call `GET .../runs/{runId}/state` to get current execution state
   - Restore `store.executingNodes`, `store.nodeResults`, `store.runStatus` from state
   - If execution still running (`status === 'running'`), re-subscribe to WebSocket and send `replay` message
2. In `StudioView.vue`, replace inline WebSocket logic with `useWebSocket` composable
3. On `onActivated`, if `hasActiveRun === true`, initiate reconnect+replay flow automatically

**Verification:**
- Unit: `useWebSocket` composable reconnect fires state endpoint call
- Integration: disconnect WS during execution → reconnect → state restored
- E2E: Playwright test simulating WS disconnect (close page, reopen) → execution state visible

---

### Phase 3 — Structure (F1, F2, F3)

**Objective:** Reduce monolith sizes and constructor bloat.

#### Batch 3.1 — ExecutionUtilityService Extraction

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java` — existing
- `backend/src/main/java/com/agent/orchestrator/service/tool/ToolRunnerService.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/tool/FileWriterService.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/tool/LlmCallerService.java` — new (if not already extracted)

**Changes:**
1. Audit `ExecutionUtilityService`: identify cohesive method groups:
   - Tool execution methods → `ToolRunnerService`
   - File write/read methods → `FileWriterService`
   - LLM call helpers → `LlmCallerService` (or verify existing `LlmService` covers this)
2. Extract each group as a new Spring `@Service` class
3. `ExecutionUtilityService` retains only orchestration/coordination methods that compose the extracted services
4. Wire extracted services via constructor injection into strategies that need them (instead of going through `ExecutionUtilityService`)
5. Update test files to inject new services directly

**Target:** `ExecutionUtilityService` shrinks from ~1537 lines to ~300 lines (coordination only). Each extracted service ≤400 lines.

**Verification:**
- All 242+ backend tests pass with refactored injections
- No functional change — same behavior, same outputs
- New services have their own unit tests for extracted methods

---

#### Batch 3.2 — NodeRouter Registry Pattern

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` — existing
- `backend/src/main/java/com/agent/orchestrator/config/NodeStrategyRegistry.java` — new
- `backend/src/main/java/com/agent/orchestrator/service/strategy/NodeStrategy.java` — verify interface exists

**Changes:**
1. Create `NodeStrategyRegistry` — a `Map<String, NodeStrategy>` populated at startup by collecting all `@Component` `NodeStrategy` beans keyed by their supported `nodeType`
   ```java
   @Component
   public class NodeStrategyRegistry {
       private final Map<String, NodeStrategy> strategies;
       
       public NodeStrategyRegistry(List<NodeStrategy> strategyList) {
           this.strategies = strategyList.stream()
               .collect(Collectors.toMap(NodeStrategy::supportedNodeType, s -> s));
       }
       
       public NodeStrategy getStrategy(String nodeType) {
           NodeStrategy s = strategies.get(nodeType);
           if (s == null) throw new IllegalArgumentException("No strategy for node type: " + nodeType);
           return s;
       }
   }
   ```
2. Each `NodeStrategy` implementation declares `String supportedNodeType()` (or use `@NodeType("agent")` annotation)
3. `NodeRouter` replaces `if/else if/else` dispatch with `registry.getStrategy(nodeType).execute(...)`
4. `NodeRouter` constructor shrinks from 14 dependencies to ~3 (registry, execution utility, websocket)

**Verification:**
- Unit: registry resolves correct strategy per node type
- Unit: unknown node type throws `IllegalArgumentException`
- All backend tests pass with registry-based dispatch

---

#### Batch 3.3 — AgentController Split

**Files:**
- `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java` — existing
- `backend/src/main/java/com/agent/orchestrator/controller/SchemaController.java` — new
- `backend/src/main/java/com/agent/orchestrator/controller/ExecutionController.java` — new
- `backend/src/main/java/com/agent/orchestrator/controller/GenerationController.java` — new (if AI-gen routes remain)

**Changes:**
1. Identify route groups in `AgentController`:
   - Schema CRUD (`/api/schemas`) → `SchemaController`
   - Execution (`/api/schemas/{id}/execute`, `/api/schemas/{id}/stop`, `/api/schemas/{id}/runs`, `/api/schemas/{id}/pipeline/*`) → `ExecutionController`
   - AI generation (`/api/schemas/{id}/generate-nodes` — deprecated, but keep if active) → `GenerationController`
   - App management (`/api/app`) → keep in `AgentController` or move to `AppController`
2. Create new controllers with `@RequestMapping` at sub-path level
3. Keep `AgentController` only for app-level routes (`/api/app`, `/api/plan`, `/api/graph`)
4. Update test files to reference new controller classes

**Target:** Each controller ≤200 lines. `AgentController` shrinks from ~400+ to ~100 lines.

**Verification:**
- All 242+ backend tests pass with new controller routing
- Manual: all API endpoints return same responses as before
- Frontend unaffected (same URL paths, just routed to different classes)

---

### Phase 4 — Contracts (F4 model duplication + F9 hop validation)

#### Batch 4.1 — Model Unification

**Files:**
- `backend/src/main/java/com/agent/orchestrator/model/WorkflowSchema.java` — existing
- `backend/src/main/java/com/agent/orchestrator/graph/GraphWorkflowSchema.java` — existing
- `backend/src/main/java/com/agent/orchestrator/service/GraphService.java` — mapping layer

**Changes:**
1. Audit mapping between `WorkflowSchema` and `GraphWorkflowSchema` — identify drift-prone fields
2. Create shared base class `SchemaBase` with common fields (id, name, description, nodes, edges, userId, timestamps)
3. `WorkflowSchema extends SchemaBase`, `GraphWorkflowSchema extends SchemaBase`
4. Replace manual mapping with `ModelMapper` or constructor-based conversion in one place
5. Add test: `GraphService` mapping is idempotent (round-trip preserves data)

**Verification:**
- Unit: round-trip mapping preserves all fields
- All backend tests pass

---

#### Batch 4.2 — Hop Boundary Validation

**Files:**
- `backend/src/main/java/com/agent/orchestrator/service/NodeRouter.java` — add validation at node output
- `backend/src/main/java/com/agent/orchestrator/service/ExecutionUtilityService.java` — add validation at utility output
- `backend/src/main/java/com/agent/orchestrator/validation/NodeOutputValidator.java` — new

**Changes:**
1. Create `NodeOutputValidator` — validates that node execution result matches expected structure per node type:
   - Agent node → expects `outputSummary` with `response` string field
   - Review node → expects `outputSummary` with `status`, `findings`, `summary`
   - Verifier node → expects `outputSummary` with `status` (PASS/FAIL), `checks[]`
   - Output node → expects `outputSummary` with `files[]` or `reportPath`
2. On validation failure → log warning + set node status to `completed` with `hasWarning` flag (not failed — valid output is a hint, not a blocker)
3. All validation logic is behind a feature toggle (see Batch 5.2) — off by default until proven stable

**Verification:**
- Unit: validator accepts valid output for each node type
- Unit: validator rejects malformed output with correct warning
- Integration: schema executes with validator on → no regression in normal execution

---

### Phase 5 — Growth (F10 + F12)

#### Batch 5.1 — API Key Model Scoping (Low priority)

**Changes:**
1. Add optional `allowedModels` list to API key entity (Neo4j `ApiKey` node)
2. When request uses API key auth, intersect `allowedModels` with provider's `listModels()` — only intersection is visible
3. Empty `allowedModels` = unrestricted (backwards-compatible)

**Verification:**
- Unit: key with `allowedModels: ["big-pickle"]` can only use that model
- Unit: key with empty `allowedModels` is unrestricted

---

#### Batch 5.2 — Feature Flag System

**Files:**
- `backend/src/main/java/com/agent/orchestrator/config/FeatureFlags.java` — new
- `backend/src/main/resources/application.yml` — add `axolotl.features.*` flags

**Changes:**
1. Create `FeatureFlags` record/POJO:
   ```java
   @ConfigurationProperties("axolotl.features")
   public record FeatureFlags(
       boolean hopValidation,       // default: false
       boolean nodeTimeout,         // default: true (on by default — safety)
       boolean circuitBreaker,      // default: false
       boolean controllerSplit      // default: false
   ) {}
   ```
2. Wire into `@ConditionalOnProperty` or explicit boolean checks in affected code paths
3. Add `axolotl.features` section to `application.yml` with defaults
4. Create `FeatureFlagController` (or add to existing) with `GET /api/features` returning current flag state

**Verification:**
- Unit: feature flag defaults match `application.yml`
- Unit: feature toggles enable/disable the correct code path
- Integration: toggling flags doesn't break existing tests

---

## Frontend Workstream (F11 — StudioView)

F11 is scoped separately. The `StudioView.vue` complexity has been partially addressed by the Blueprint Panel Refactoring (plan 46) which extracted `useCanvasStore`, `usePipelineStore`, `useReviewStore`, `useSelectionStore`, and `blockRegistry`. Remaining work:

**Remaining F11 items:**
1. Extract WebSocket logic into `useWebSocket` composable (covered in Batch 2.2)
2. Replace inline execution flag management with `usePipelineStore`
3. Add `StudioView` state machine (idle → executing → paused → completed/failed) to replace boolean flags
4. Remove dead `isAlive` guards from execution callbacks (see earlier premortem findings)

**Plan:** Address these as follow-up when implementing Batch 2.2 (WebSocket composable naturally forces the extraction).

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| F1 extraction breaks existing strategy injections | Medium | High | Extract incrementally — add new services first, wire in parallel, remove old only after all tests pass |
| F2 registry pattern may hide missing strategies until runtime | Low | Medium | Add `@PostConstruct` validation that all expected node types have registered strategies |
| F7 WebSocket replay may be complex to implement correctly | Medium | High | Ship Batch 2.1 first (startup recovery) — that covers restart loss; Batch 2.2 is incremental improvement |
| F6 startup reconciliation may race with async execution | Low | High | Only run on `ApplicationReadyEvent` — by then all async executors should be done |
| F8 circuit breaker may cause user confusion when provider is "down" | Medium | Low | Expose circuit state in Settings UI; auto-close after success threshold |
| F5 per-node timeout may kill long-running nodes prematurely | Low | Medium | Default 300s is generous; users can override per-node; timeout extension via config |

---

## Verification

**Per-batch verification** is listed in each batch above. Global verification:

1. **Backend compilation:** `mvn compile -q` — zero errors after each batch
2. **Backend tests:** `mvn test` — all 242+ tests pass (no regressions)
3. **Frontend compilation:** `vue-tsc --build` — zero errors (frontend changes only in Batch 1.1, Batch 2.2)
4. **Frontend tests:** `npm run test:unit` — all 173+ tests pass
5. **Integration:** Manual schema execution with all node types — completes successfully
6. **Documentation:** `ARCHITECTURE.md` accurately describes operating envelope

---

## Batch Ordering and Dependencies

```
Phase 0.1 → 0.2         # Foundation — sequential (config needs arch doc)
       ↓
Phase 1.1 → 1.2         # Safety — parallelizable
       ↓
Phase 2.1 → 2.2         # State — sequential (replay depends on recovery)
       ↓
Phase 3.1, 3.2, 3.3     # Structure — parallelizable (different files)
       ↓
Phase 4.1, 4.2          # Contracts — parallelizable
       ↓
Phase 5.1, 5.2          # Growth — parallelizable (independent)
```

Phases 0–1 are independent and high-priority. Phases 2–5 can be reordered if constraints change.

---

## Test Requirements

New tests required per batch:

| Batch | New Unit Tests | New Integration Tests | New E2E Tests |
|-------|---------------|----------------------|---------------|
| 0.1 | 0 (docs only) | 0 | 0 |
| 0.2 | 2 (timeout behavior) | 1 (schema timeout) | 0 |
| 1.1 | 2 (per-node override) | 1 (per-node timeout) | 1 (config panel) |
| 1.2 | 3 (circuit states) | 1 (provider status API) | 0 |
| 2.1 | 1 (startup recovery) | 1 (stale run marking) | 0 |
| 2.2 | 3 (composable + state endpoint) | 1 (WS replay) | 1 (reconnect) |
| 3.1 | 3 (per extracted service) | 0 | 0 |
| 3.2 | 2 (registry resolution) | 0 | 0 |
| 3.3 | 0 | 0 | 0 |
| 4.1 | 1 (round-trip mapping) | 0 | 0 |
| 4.2 | 3 (per node type validator) | 0 | 0 |
| 5.1 | 2 (key scoping) | 0 | 0 |
| 5.2 | 1 (flag defaults) | 0 | 0 |

**Total:** ~22 new unit tests, ~5 integration tests, ~2 E2E tests.
