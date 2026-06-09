# Axolotl Backend Architecture

## Operating Envelope

### Core Assumptions
Axolotl is designed for **single-user, single-schema execution** at a time per process:

- One schema executes at a time (no concurrent schema runs)
- One node completes before the next wave of parallel nodes begins
- All execution state lives in-memory during a run; Neo4j is cold storage
- No queue-based execution, no horizontal scaling, no multi-tenancy

### Explicit Non-Goals
The following are NOT targets for the current architecture:
- **Multi-tenancy/HA** — no load balancing, failover, or cluster coordination
- **Distributed execution** — no cross-process state sharing or remote workers
- **Queue-based async** — no message queues for deferred execution
- **Horizontal scaling** — one JVM, one execution at a time

### Why This Works
Axolotl is a local-first development tool, not a production job orchestrator. The single-user contract enables:
- Simple in-memory state management (no distributed locks or consensus)
- Direct Neo4j reads/writes with no transaction coordination
- Virtual threads for per-node parallelism without thread-pool sizing complexity
- Immediate feedback via WebSocket (no polling or callback routing)

### When This Will Break
Adding any of the following requires rethinking the entire execution model:
1. **Multi-user schemas** — in-memory state per schema becomes unbounded
2. **Concurrent schema execution** — `PipelineStatusManager` maps are keyed by schemaId and assume single access
3. **Async/queue-based execution** — `ExecutionWebSocketHandler` assumes connected client for streaming
4. **Horizontal scaling** — in-memory `ExecutionStateManager` and `PipelineStatusManager` are per-process

---

## System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Frontend (Vue 3)                     │
│  Dashboard → Studio (VueFlow canvas) → Settings         │
│  Pinia stores: schemaStore, canvasStore, pipelineStore   │
│  WebSocket → live execution streaming                   │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP REST + WebSocket
                       ▼
┌─────────────────────────────────────────────────────────┐
│               AgentController / REST Layer               │
│  Schema CRUD, Execution, Pipeline, App, Graph, Settings  │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│                 PipelineService                          │
│  Topological sort, stage execution, pause/resume/retry   │
│  TDD mode expansion, cross-stage artifact passing        │
│  Delegates to SchemaService.executeSchema()              │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│  SchemaService + NodeRouter                              │
│  Topological sort → execute wave per wave                │
│  NodeRouter dispatches to per-type strategies:           │
│    AgentNodeStrategy (LLM + tools)                       │
│    VerifierNodeStrategy (checks + verdict)                │
│    ReviewNodeStrategy (plan + human approval)             │
│    DraftNodeStrategy (spec/plan/ui/backend drafts)        │
│    SchemaBuilderNodeStrategy (meta schema generation)     │
└──────────────────────┬──────────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
   ┌────────────┐ ┌──────────┐ ┌──────────┐
   │ LlmService │ │ ToolExec │ │ Execution│
   │ Provider   │ │  file_   │ │ WebSocket│
   │ routing    │ │  write,  │ │ Handler  │
   │ 7 providers│ │  bash... │ │ streaming│
   └────────────┘ └──────────┘ └──────────┘
```

---

## Key Components

### PipelineService
- Orchestrates multi-stage pipeline execution
- Stages in topological order, branches within a stage run in parallel
- Supports pause/resume, retry from failure, TDD mode expansion
- Cross-stage artifact passing via `Stage.inputMapping` (dot-notation JSON field extraction)
- Delegates actual node execution to `SchemaService.executeSchema()`

### SchemaService
- Topological sort of canvas nodes
- Executes nodes wave-by-wave (parallel within wave)
- `ExecutionStateManager` tracks per-schema state
- Sanitizes schemas before returning (removes null-ID nodes/edges)

### NodeRouter
- Routes node execution by node type → strategy
- Supports `agent`, `output`, `command`, `filewrite`, `source`, `condition`, `transform`, `loop`, `review`, `verifier`, `draft`, `schema_builder`, `memory`, `guardrail` node types
- Wraps execution with per-node timeout (`NodeData.config.timeoutSeconds`, default 300s)
- Retries on transient errors up to `autoRetryCount`
- Persists results to Neo4j via `ExecutionRepository`

### Execution State Model
- **In-memory (primary):** `ExecutionStateManager` — node results, condition results, cancellations
- **Neo4j (cold storage):** `ExecutionRun`, `NodeExecution` — persisted after each node completes
- **Reconciliation:** `ExecutionStateReconciler` marks orphaned `running` runs as `RECONCILED_FAILED` on startup and every 5 minutes

### LLM Provider Layer
- `LlmService` routes model names to 7 providers: OpenAI, Anthropic, DeepSeek, Zen, Ollama, Custom, Rlm
- All providers use LangChain4j `ChatLanguageModel` internally
- Model lists fetched dynamically from each provider API, persisted to Neo4j
- Fallback chain: if primary model fails, try `fallbackModels` in sequence

### WebSocket
- `ExecutionWebSocketHandler` streams progress, logs, results, errors, reasoning, metrics
- Events: `progress`, `log`, `result`, `error`, `complete`, `metrics`, `paused`, `reasoning`, `state_replay`
- Connect at `ws://localhost:8082/ws/execution?schemaId={id}`
- **Reconnect support:** events are buffered per `schemaId` when no WS session is connected; on reconnect, a `state_replay` envelope followed by buffered events are sent to restore state

### Feature Flags
- `FeatureFlagService` reads `axolotl.features.*` from `application.yml`, exposed via `GET /api/features`
- Flags: `webhook`, `graph-query`, `tdd-pipeline`, `orchestration`, `experimental.draft-node`
- Each flag has an `AXOLOTL_FEATURE_*` env var override
- Runtime overrides via `PUT /api/features/{name}` with `{"enabled": true/false}`
- Used to gate experimental capabilities without code changes

### Node Output Validation
- `NodeOutputValidator` checks each node's output after strategy execution
- Per-type structural validation (null result, blank content, error prefix)
- Validation issues logged as warnings and sent via WebSocket log events
- Non-blocking — does not fail the node, but downstream consumers can inspect the validation result

### PipelineController
- Extracted pipeline endpoints from `AgentController` into dedicated `PipelineController`
- Endpoints: `POST /pipeline/build`, `POST /pipeline/execute`, `POST /pipeline/retry`, `POST /pipeline/cancel`, `GET /pipeline/status`
- Provides `lastRunStatus`/`lastRunError` at `/pipeline/status` for polling`

### Persistence (Neo4j)
- Schema definitions (`WorkflowSchema`)
- Execution runs + node results (`ExecutionRun`, `NodeExecution`)
- Provider settings + disabled models (`GraphProviderSetting`)
- Auth users
- Codebase graph (AST analysis)

---

## Concurrency Model

| Scope | Mechanism | Notes |
|-------|-----------|-------|
| Per-node | Virtual thread via `CompletableFuture.supplyAsync()` | Nodes within same wave run parallel |
| Per-schema | Single execution at a time | `PipelineStatusManager` assumes schemaId uniqueness |
| Across schemas | Not supported | In-memory maps would collide |
| LLM calls | Blocking within virtual thread | Provider-specific retry logic |
| File writes | Direct `Files.write()` | Sandbox validation via `validateSandboxPath()` |

---

## Timeout Model

| Timeout | Default | Source | Effect |
|---------|---------|--------|--------|
| Per-node | 300s | `NodeData.config.timeoutSeconds` or node-level override | Node marked FAILED with timeout error |
| WebSocket | N/A | Persistent connection | Client must reconnect |
| LLM provider | Provider default | LangChain4j `ChatLanguageModel` | Propagates as node error |
| HTTP | 30s | `scripts/api.py` | Python API client |

---

## Error Handling

- **Node errors** → caught by NodeRouter, persisted to Neo4j, sent via WebSocket `error` event
- **Provider errors** → retried up to `autoRetryCount` (if transient: 429/502/503/timeout), fallback models tried after retries exhausted
- **Pipeline errors** → caught by PipelineService, stage marked FAILED, dependent stages BLOCKED
- **Circuit breaker** → after 3 consecutive failures, provider calls fail-fast for 30s (Phase 1 feature)
- **State reconciliation** → orphaned `running` runs marked `RECONCILED_FAILED` on startup and every 5min

---

## Testing Strategy

| Layer | Tool | Command |
|-------|------|---------|
| Backend unit | JUnit 5 + Mockito | `cd backend && mvn test` |
| Backend integration | Spring Boot Test | `mvn test` (with embedded Neo4j) |
| Frontend unit | Vitest + vue-test-utils | `cd frontend && npm run test:unit` |
| Frontend type | vue-tsc | `cd frontend && npx vue-tsc --noEmit` |
| E2E | Playwright | `cd frontend && npm run test:e2e` |
