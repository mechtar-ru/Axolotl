# Axolotl — Architecture & Design

> Visual AI-agent workflow orchestration platform — "Draw logic, don't write it"

---

## Tech Stack

### Backend

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Runtime |
| Spring Boot | 3.2.0 | Application framework |
| Spring AI | 1.1.4 | LLM abstraction layer |
| Maven | - | Build |
| Neo4j | 5.x | Primary database (schemas, plans, execution, code graph) |
| Neo4j | 5.x | All operational data, code graph, auth |
| WebSocket | Spring | Real-time execution streaming |
| Micrometer + Prometheus | - | Metrics |
| springdoc OpenAPI | 3.0 | API docs |
| Logstash (logback) | - | Structured JSON logging |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| Vue | 3.5 | UI framework (Composition API) |
| TypeScript | 5.x | Type safety |
| Vite | 5.x | Build tool |
| VueFlow | 1.x | Visual node editor canvas |
| Pinia | 3.x | State management |
| Axios | 1.x | HTTP client |
| Vue Router | 5.x | Client-side routing |
| Lucide Vue | 1.x | Icons |
| VueUse | 14.x | Composition utilities |
| Playwright | 1.59 | E2E tests |
| Vitest | - | Unit tests |
| Electron | 34.x | Desktop wrapper |

---

## Monorepo Layout

```
Axolotl/
├── backend/                    # Spring Boot 3.2, Java 21
│   └── src/main/java/com/agent/orchestrator/
│       ├── Application.java    # Entry point, .env loader
│       ├── config/             # Security, JWT, WebSocket, DB, OpenAPI
│       ├── controller/         # REST endpoints (17 controllers)
│       ├── service/            # Business logic (15 services)
│       ├── llm/                # LLM provider implementations
│       ├── graph/              # Neo4j integration (full sub-project)
│       │   ├── api/            # Graph REST endpoints
│       │   ├── config/         # Neo4j config, indexes
│       │   ├── model/          # Neo4j node entities
│       │   ├── repository/     # Neo4j repositories
│       │   ├── service/        # Graph query/curation logic
│       │   ├── loader/         # Codebase import pipelines
│       │   └── mcp/            # Graph MCP tools
│       ├── mcp/                # MCP (Model Context Protocol) server
│       ├── model/              # Domain model (Node, Edge, Schema, Plan, etc.)
│       ├── repository/         # Neo4j repositories
│       └── websocket/          # WebSocket handler
├── frontend/                   # Vue 3 + TypeScript + Vite
│   └── src/
│       ├── components/
│       │   ├── canvas/         # WorkflowCanvas, NodeContextMenu
│       │   ├── nodes/          # 18 node type components
│       │   ├── execution/      # ExecutionPanel, ExecutionHistory
│       │   ├── panels/         # RightPanel (properties)
│       │   ├── memory/         # MemoryGraphView
│       │   ├── editor/         # PromptEditorModal
│       │   ├── plan/           # PlanPanel
│       │   └── ui/             # Shared UI components
│       ├── stores/             # Pinia stores (auth, schema, panel, counter)
│       ├── services/           # API client wrappers
│       ├── composables/        # useWebSocket, useToast, useExecutionState
│       ├── router/             # Vue Router config
│       ├── views/              # Page-level components
│       └── types/              # TypeScript interfaces
├── electron/                   # Electron desktop app
├── kubernetes/axolotl/        # Helm chart
├── e2e/                        # Playwright end-to-end tests
└── .github/workflows/          # CI/CD (compile, build, Docker publish)
```

---

## Backend Architecture

### Package Structure

The backend follows a **layered architecture with domain packages**:

```
controller/  →  service/  →  repository/ (Neo4j)
                    ↓
               llm/ (providers)
               graph/ (Neo4j access)
               mcp/ (MCP protocol)
               websocket/ (streaming)
```

### Key Services

| Service | Responsibility | Lines |
|---|---|---|
| **SchemaService** | CRUD workflows, topological sort, async execution orchestration | 806 |
| **NodeExecutor** | Executes individual nodes: LLM calls, tools, conditions, loops, subagents | 1616 |
| **PlanService** | Todo/plan management, MCP-driven task operations | 485 |
| **AgentService** | Agent lifecycle (local/remote), message routing via OpenClaw | 100 |
| **LlmService** | Routes LLM requests to the correct provider implementation | - |
| **ToolExecutor** | Executes built-in tool calls (file ops, bash, web, git, etc.) | - |
| **CrossCheckService** | Cross-check/review orchestration | - |
| **TransformService** | Data transforms between services | - |
| **SkillService** | Skill auto-generation and tracking | - |
| **PluginService** | Plugin management | - |

### LLM Provider Layer (`llm/`)

Providers implement a common interface (`LlmProvider`) for unified streaming:

| Provider | Type | Streaming |
|---|---|---|
| **OllamaProvider** | Local (Ollama API) | NDJSON |
| **OpenAiProvider** | OpenAI GPT-4o/mini | SSE |
| **AnthropicProvider** | Claude Sonnet/Opus/Haiku | SSE |
| **DeepSeekProvider** | DeepSeek API | NDJSON |
| **CustomLlmProvider** | Any OpenAI-compatible endpoint | Configurable |
| **RlmProvider** | RLM (Reinforcement Learning Model) | - |
| **SpringAiLlmProvider** | Spring AI abstraction wrapper | - |
| **OpenencodeZenProvider** | OpenCode Zen model | - |

### Neo4j Integration (`graph/`)

A near-independent sub-project within the backend:

- **GraphController** — REST endpoints for code graph queries, context curation, stats
- **Neo4jConfig** — connection pool, Bolt driver configuration
- **CodebaseLoader/ParallelCodebaseImporter** — walks source trees, parses AST, imports classes/methods/fields into Neo4j
- **GraphMemoryService** — query construction, token-bounded context curation for LLM prompts
- **UnifiedMemoryService** — bridges Neo4j and MemPalace
- **MCP tools** — `graph_query` tool for agent nodes
- **Hash-anchored edits** — classes referenced by stable 16-char signature hash

### MCP Server (`mcp/`)

JSON-RPC 2.0 at `/mcp` with 7 tools:
- `add_task`, `read_plan`, `update_task_status`, `read_plan_tree`, `move_task_position`, `delete_task`, `batch_add_tasks`

### WebSocket (`websocket/`)

- **ExecutionWebSocketHandler** — shared by all users
- Event types: `progress`, `log`, `result`, `error`, `complete`, `metrics`, `token`, `wave`, `nodeTime`, `toolCall`, `iteration`

### Model Layer (`model/`)

Domain classes shared across services:

| Model | Fields |
|---|---|
| `WorkflowSchema` | id, name, description, nodes[], edges[], metadata |
| `Node` | id, type, label, config, position, positionAbsolute |
| `Edge` | id, source, target, type, label |
| `Task` | id, title, description, status, priority, dependencies |
| `Plan` | tasks[], metadata |
| `ExecutionRecord` | id, schemaId, mode, status, startedAt, completedAt |
| `ExecutionMode` | enum: EXECUTE, ANALYZE, DRY_RUN |
| `AppUser` | id, username, passwordHash, role |
| `CustomLlmEndpoint` | id, name, url, apiKey, models |

### Security Model

**Two-layer defense:**

1. **JwtAuthFilter** (`OncePerRequestFilter`) — skips (via `shouldNotFilter()`) for public paths, validates JWT for everything else
2. **SecurityConfig** — `authorizeHttpRequests()` permits specific patterns, requires auth for everything else

**Public endpoints (no JWT needed):**
- `/api/auth/**` — login/register
- `/api/schemas`, `/api/schemas/**` — schema CRUD
- `/api/health`
- `/api/memory/**`
- `/api/graph/**`
- `/api/settings/**`
- `/api/agents`, `/api/agents/**`
- `/api/templates`, `/api/templates/**`
- `/api/history`, `/api/history/**`
- `/api/plan`, `/api/plan/**`
- `/api/plugins`, `/api/plugins/**`
- `/api/share/t/**`
- `/api/fetch-url`
- `/ws/**` — WebSocket
- `/mcp` — MCP server
- `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`

**Auth flow:** Token issued at `/api/auth/login` → stored in Pinia `authStore` → sent as `Authorization: Bearer <token>` header.

**Other security:**
- BCrypt password hashing
- CORS wide-open (all origins)
- CSRF disabled (stateless API)
- API key rate limiting (60 req/min) for Remote API
- Dangerous command blocking in bash tool

---

## Frontend Architecture

### Component Tree

```
App.vue
├── ToastContainer (global notifications)
├── SchemaBuilderModal (AI prompt-to-schema)
├── RouterView
│   ├── LoginView
│   ├── HomeView (main workspace)
│   │   ├── WorkflowCanvas (VueFlow)
│   │   │   ├── AgentNode, SourceNode, OutputNode, etc.
│   │   │   ├── NodeContextMenu
│   │   │   └── GroupNode
│   │   ├── RightPanel (node properties)
│   │   ├── ExecutionPanel (bottom drawer)
│   │   │   └── ExecutionHistory
│   │   ├── MemoryGraphView (sidebar)
│   │   └── PlanPanel (todo list)
│   ├── SettingsView
│   └── AboutView
```

### State Management (Pinia Stores)

| Store | Responsibility |
|---|---|
| `schemaStore` | Current workflow schema, nodes, edges, undo/redo stack, selection state |
| `authStore` | JWT token, user info, login/logout |
| `panelStore` | UI panel visibility, active panels |

### Data Flow Patterns

**Schema CRUD:**
```
Canvas edits → schemaStore mutations → auto-save debounce → PUT /api/schemas/:id
Page load → GET /api/schemas/:id → schemaStore hydration → VueFlow render
```

**Execution:**
```
Run button → POST /api/schemas/:id/execute
  → Backend: topo sort → parallel level execution → WebSocket events
  → Frontend: useWebSocket composable → schemaStore mutations (node states) → canvas animation
```

**LLM streaming:**
```
Agent node execution → LlmService → Provider (SSE/NDJSON)
  → WebSocket "token" events → ExecutionPanel (character-by-character)
```

### Key Node Types (18 total)

Source, Agent, Output, Condition, Loop, Memory, Guardrail, Human, Fallback, Subagent, Group, Transform, FileWrite, Command, SchemaBuilder, Comment, MemoryResultCard, CustomCommand

---

## Database Design

### Dual-DB Architecture

**Neo4j (primary operational store):**
- `WorkflowSchema` nodes — full schema with nodes/edges
- `Plan` nodes — tasks, statuses, priorities
- `ExecutionRecord` nodes — execution history
- `Task` nodes — individual plan tasks
- `CodeClass`/`CodeMethod`/`CodeField`/`CodePackage` — code graph (immutable copies)
- `Decision` — design decisions
- `LlmEndpoint` — provider config backups
- `ProviderConfig` — settings backups

**Key decision:** Neo4j is the single data store for all data. The `Application.java` entry point sets `spring.ai` system properties from `.env` before Spring Boot initializes.

---

## Key Data Flows

### 1. Schema Creation & Editing

```
User drags nodes on canvas
  → VueFlow @nodesChange/@edgesChange events
  → schemaStore mutations (with undo/redo history via pushState/popState)
  → Debounced auto-save: PUT /api/schemas/:id
  → SchemaService.save() → Neo4jSchemaRepository.save()
```

**AI schema generation:**
```
User types "build a sentiment analysis pipeline"
  → POST /api/schemas/generate-from-prompt
  → SchemaService → LlmService → LLM returns structured JSON
  → Parse into WorkflowSchema with nodes + edges
  → Save to Neo4j, return to frontend
  → Hydrate canvas
```

### 2. Workflow Execution

```
User clicks "Run"
  → POST /api/schemas/:id/execute[?mode=EXECUTE|ANALYZE|DRY_RUN]
  → SchemaService.execute():
      1. Load schema from Neo4j
      2. Topological sort (Kahn's algorithm → levels)
      3. Level-by-level parallel execution:
         For each level:
           - Spawn CompletableFuture per node
           - NodeExecutor.execute(node, context):
             a. Collect upstream context (variable interpolation: {{input}}, {{prev_result}})
             b. Route to handler by node type:
                - Source → inject input data
                - Agent → LlmService → provider → WebSocket token stream
                - Condition → evaluate expression → branch
                - Loop → iterate until condition
                - Memory → MemPalaceClient search/save
                - Subagent → recursive execution (max 5 depth)
                - Guardrail → validate/transform data
                - Human → wait for external confirmation
                - Fallback → retry with configurable count
             c. Send WebSocket events (progress, log, token, nodeTime, wave)
           - Convergence monitor: error counter ≥ 3 → BLOCKED
           - Collect level results as context for next level
      4. Save ExecutionRecord
      5. WebSocket "complete" event
```

### 3. WebSocket Streaming

```
SchemaService executes → calls sendEvent(type, payload):
  → ExecutionWebSocketHandler.broadcast():
     → session.sendMessage(new TextMessage(json))
  → Frontend useWebSocket composable:
     → Route by event type:
        - progress → update node border pulse
        - log → append to ExecutionPanel
        - token → stream into output display
        - error → mark node red, shake animation
        - complete → show results, green glow
        - metrics → display timing/usage stats
```

### 4. Plan/MCP Flow

```
External tool or user calls MCP:
  → POST /mcp (JSON-RPC 2.0 request)
  → PlanMcpServer routes to PlanTools
  → PlanService CRUD → Neo4jPlanRepository
  → Response via JSON-RPC response
```

### 5. Code Graph Import

```
POST /api/graph/load?path=backend/src
  → CodebaseLoader walks files
  → ParallelCodebaseImporter parses AST
  → Creates CodePackage → CodeClass → CodeMethod/CodeField nodes in Neo4j
  → Hash-anchored (16-char stable hash per class)
  → Agents query via graph_query tool:
     "Find class with hash 038f2e49841afecb"
```

---

## Design Patterns

| Pattern | Where | Why |
|---|---|---|
| **Layered Architecture** | Backend packages (`controller → service → repository`) | Separation of concerns, testability |
| **Strategy Pattern** | LLM providers (`LlmProvider` interface, 8 implementations) | Swap models without changing orchestration |
| **Observer/Event** | WebSocket events (`progress/log/token/error`) | Real-time UI updates without polling |
| **Kahn's Algorithm** | `SchemaService` topological sort | Correct execution order for DAG workflows |
| **CompletableFuture** | Parallel level execution | Independent nodes run concurrently |
| **Circuit Breaker** | Convergence monitor (error threshold → BLOCKED) | Prevent cascading failures |
| **MCP (Model Context Protocol)** | JSON-RPC 2.0 tool server | Standardized agent-tool interface |
| **Hash-Anchored References** | Neo4j code graph (16-char hashes) | Stable references despite refactoring |
| **Repository Pattern** | `Neo4j*Repository` | Abstract data access from business logic |
| **Builder/Factory** | Schema generation from prompt | Parse LLM output into structured model |
| **Composition API** | All Vue components | Reusable logic via composables |
| **Pinia Stores** | Centralized state | Predictable state mutations, devtools |

---

## Observability

- **Metrics**: `/actuator/prometheus` via Micrometer
- **API Docs**: `/swagger.html` via springdoc OpenAPI 3.0
- **Logging**: Structured JSON via Logstash encoder
- **Execution Records**: Persisted in Neo4j for historical analysis

---

## Desktop App (Electron)

- **Window**: 1400×900, min 1024×700
- **System tray**: show/hide, new workflow, quit
- **Global shortcut**: Cmd/Ctrl+Shift+A toggle
- **Native notifications**: execution complete, errors
- **File dialogs**: native open/save for workflows
- **Auto-update**: via `electron-updater` from GitHub releases
- **Packaging**: `electron-builder`, includes bundled JRE

---

## Deployment

- **Docker Compose**: full stack (backend + frontend + Neo4j)
- **Kubernetes**: Helm chart at `kubernetes/axolotl/`
- **CI/CD**: GitHub Actions — compile → test → build → Docker → GHCR
- **Database**: Neo4j
