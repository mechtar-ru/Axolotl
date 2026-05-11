# Axolotl

Visual workflow builder for AI agent pipelines. Draw graphs of LLM calls, code analysis, file operations, and human-in-the-loop steps — execute them in parallel with real-time streaming.

## What it is

Axolotl is a Spring Boot + Vue 3 application where you:
1. **Draw a graph** of nodes (agents, sources, conditions, loops, outputs) on an infinite canvas
2. **Configure each node** — pick an LLM model, write prompts, bind tools, set conditions
3. **Execute** the graph — nodes run in parallel waves (topological sort), results stream via WebSocket
4. **Observe** execution in real-time — token-by-token LLM output, tool calls, iteration loops, per-node timing

It's not a no-code platform. It's a visual shell around LLM pipelines — you still write prompts, configure tools, and handle data flow explicitly.

## Codebase structure

```
backend/                          # Spring Boot 3.2, Java 21, Maven
  src/main/java/.../orchestrator/
    Application.java              # Entry point
    controller/                   # 16 REST controllers
    service/                      # 13 services (execution engine, tools, plans, skills, etc.)
    llm/                          # 8 LLM provider implementations
    graph/                        # Neo4j code graph system (loader, search, curation, hashing)
    model/                        # 19 domain classes (Node, Edge, WorkflowSchema, Task, etc.)
    mcp/                          # JSON-RPC 2.0 MCP server with 7 plan tools
    websocket/                    # WebSocket handler for real-time execution events
    config/                       # Security, JWT, Neo4j, WebSocket, CORS configuration
    repository/                   # SQLite repositories (Neo4j repositories live under graph/)
    client/                       # External service clients

frontend/                         # Vue 3, TypeScript, Vite
  src/
    components/
      nodes/                      # 16 VueFlow node components
      studio/                     # Main workspace (palette, canvas, config panel, timeline)
      blocks/                     # Agent pipeline blocks (Think, Act, Remember, Receive)
      live/                       # Runtime execution UIs (chat, doc analyzer, generic)
      ui/                         # Reusable UI primitives
    stores/                       # 5 Pinia stores (auth, schema, panel, settings, counter)
    router/                       # Vue Router — 5 routes
    composables/                  # WebSocket, execution state, Electron bridge, toasts
    services/                     # Axios API client with JWT interceptor

electron/                         # Electron desktop app
  main.ts                         # Spawns embedded Spring Boot JAR, system tray, auto-updater
  preload.ts                      # IPC bridge (notifications, file dialogs, window controls)

scripts/                          # 10 shell/Python scripts
  dev.sh                          # Dev lifecycle (start, stop, logs, execute)
  sync-to-test.sh                 # Copy main → test dirs for safe agent editing
  sync-from-test.sh               # Copy verified changes from test → main dirs
  update-graph.sh                 # Load codebase into Neo4j graph
  migrate-to-neo4j.py             # SQLite → Neo4j data migration
  setup-graph-hook.sh             # Git hook for auto-graph-update on commit

docs/                             # VitePress documentation (bilingual EN/RU)
templates/                        # 5 workflow JSON templates
e2e/                              # Playwright E2E test
kubernetes/axolotl/               # Helm chart
```

## Quick start

### Backend

```bash
cd backend
mvn spring-boot:run
# http://localhost:8080
```

### Frontend

```bash
cd frontend
npm install
npm run dev
# http://localhost:5173
```

### Docker

```bash
docker-compose up -d
# Starts: backend (:8080), frontend (:3000), PostgreSQL, Neo4j (:7474), MemPalace (:8765)
```

Requires Java 21+ and Node.js 18+. Ollama is optional (for local LLM inference).

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.2, Maven |
| Frontend | Vue 3 (Composition API), TypeScript, Vite 8 |
| Canvas | VueFlow (vue-flow-core 1.x) |
| State | Pinia 3, Vue Router 5 |
| LLM APIs | OpenAI, Anthropic, Ollama, DeepSeek, + custom OpenAI-compatible |
| Databases | SQLite (operational), Neo4j 5 (code graph, plan backup) |
| External | MemPalace memory service, PostgreSQL (Docker) |
| Execution | CompletableFuture parallel waves, WebSocket SSE streaming |
| Auth | JWT (HS256), Spring Security filter |
| Metrics | Micrometer + Prometheus |
| Desktop | Electron 41, electron-updater |
| CI/CD | GitHub Actions (compile, Docker buildx, VitePress deploy) |
| Deployment | Docker Compose, Kubernetes Helm chart |
| Testing | JUnit 5 + Mockito (backend), Vitest (frontend unit), Playwright (E2E) |

## What's in the backend

### 16 REST controllers

| Path | Controller | Purpose |
|------|-----------|---------|
| `/api/schemas` | AgentController | Schema CRUD, execution, history |
| `/api/app` | AppController | App CRUD (schemas with metadata) |
| `/api/auth` | AuthController | Login, register |
| `/api/crosscheck` | CrossCheckController | LLM-based agent output verification |
| `/api/settings/endpoints` | CustomEndpointController | Custom LLM endpoint CRUD |
| `/api/evidence` | EvidenceController | Test evidence from harness runs |
| `/api/harness` | HarnessController + EvolveController | Evolutionary harness loop for agent improvement |
| `/api/manifest` | ManifestController | YAML manifest for harness edits |
| `/api/plan` | PlanController | Plan/workspace/task CRUD, status transitions |
| `/api/plugins` | PluginController | Install/start/stop plugins (whitelist-gated) |
| `/api/remote` | RemoteApiController | API key management, remote execution, rate limiting |
| `/api/settings` | SettingsController | Provider API keys, base URLs (AES/GCM encrypted) |
| `/api/share` | ShareController | Read-only share links with expiry |
| `/api/skills` | SkillController | Skill CRUD, usage tracking |
| `/api/templates` | TemplateController | Workflow template listing |
| `/api/graph/*` | GraphController | Neo4j code graph load, search, curation, stats |

Plus: WebSocket at `ws://localhost:8080/ws/execution` and MCP JSON-RPC at `POST /mcp`.

### Execution engine (`SchemaService` + `NodeExecutor`)

Schemas are directed graphs of typed nodes. Execution proceeds in topological waves:

1. Kahn's algorithm computes independent levels
2. Nodes at the same level execute in parallel via `CompletableFuture`
3. Each node type has its own execution logic:
   - **AgentNode** — calls LLM with system prompt + collected upstream context + bound tools
   - **SourceNode** — injects static/input data
   - **ConditionNode** — evaluates expression → routes true/false branch
   - **LoopNode** — repeats subgraph until break condition
   - **HumanNode** — pauses for human approval via UI
   - **GuardrailNode** — validates data against rules
   - **SubagentNode** — invokes a nested schema (max 5 levels deep)
   - **MemoryNode** — queries MemPalace semantic memory
   - **OutputNode** — writes to log, file, or memory
   - **TransformNode** — JSON field extraction, regex, string operations
4. Results stream via WebSocket: `progress`, `log`, `error`, `result`, `complete`, `metrics`, `token`, `wave`, `nodeTime`, `toolCall`, `iteration`
5. Error threshold (3) triggers `BLOCKED` state for the schema

### 8 LLM providers

| Provider | Models | Streaming | Auth |
|----------|--------|-----------|------|
| Ollama | Any local model (gemma, llama, mistral, qwen) | NDJSON | None |
| OpenAI | GPT-4o, GPT-4o-mini, GPT-4-turbo, GPT-3.5-turbo | SSE | Bearer token |
| Anthropic | Claude Sonnet 4, Opus 4, Haiku 4 | SSE | x-api-key |
| DeepSeek | deepseek-chat, deepseek-reasoner | — | Bearer token |
| Zen (OpenCode) | 40+ models (big-pickle, Claude 4, Gemini 3, GPT-5, Qwen 3, etc.) | SSE | Bearer token |
| RLM | Delegates to gpt-4o / claude-sonnet / deepseek-chat via Python subprocess | — | None (local) |
| Custom | Any OpenAI-compatible endpoint | SSE | Bearer or X-API-Key |
| Spring AI | Ollama + OpenAI via Spring AI abstraction | Reactor Flux | Via auto-config |

Provider routing is model-name-based: `gpt-*` → OpenAI, `claude-*` → Anthropic, `deepseek-*` → DeepSeek, `llama`/`gemma`/`mistral` → Ollama, `big-pickle`/`qwen3.*` → Zen, `@cf/*` → custom. Fallback: Ollama.

### 15 built-in tools (for agent nodes)

`file_read`, `file_write`, `directory_read`, `grep`, `git`, `bash`, `memory_read`, `memory_write`, `memory_search`, `web_search`, `web_fetch`, `web_api`, `graph_query`, `mcp_execute`, `rlm_predict`

Tools run with per-node sandboxing (allowed paths, blocked commands). Dangerous commands (`rm -rf`, `format`, `mkfs`) are blocked by default.

### Neo4j code graph system

The `graph/` package provides codebase analysis:
- **CodebaseLoader** — parses Java source files, resolves AST dependencies
- **CodeEntityHasher** — computes stable 16-char hashes for hash-anchored edits
- **ParallelCodebaseImporter** — bulk imports code entities into Neo4j
- **AstPatternSearchService** — searches code by AST patterns
- **ContextCurationService** — token-bounded context assembly for LLM prompts (uses jtokkit BPE tokenizer)
- **BatchPlanner** — computes import waves by dependency tier
- **GraphMetricsService** — Prometheus metrics for graph operations
- Git hook auto-updates the graph after each commit

### MCP server (JSON-RPC 2.0)

7 tools at `POST /mcp`:
`read_plan`, `add_tasks`, `add_task`, `update_task_status`, `move_task`, `delete_task`, `update_task_priority`

### Plan/Workspace system

Tasks with statuses (TODO, IN_PROGRESS, DONE, BLOCKED, CANCELLED), priorities (HIGH/MEDIUM/LOW), acceptance criteria (validated on DONE transition), dependency checking, node-to-task linking, WebSocket broadcasts on changes. Neo4j-backed.

### Security

JWT auth (HS256), Spring Security filter chain, multi-tenancy (schemas isolated per user), encrypted provider settings (AES/GCM), API key rate limiting (60 req/min per key).

## What's in the frontend

### 5 routes

| Path | View | Auth |
|------|------|:----:|
| `/login` | LoginView | No |
| `/` | DashboardView (schema list) | Yes |
| `/app/:id` | StudioView (main editor) | Yes |
| `/settings` | SettingsView | Yes |
| `/about` | AboutView | No |

### Studio workspace

The main editor at `/app/:id` contains:
- **BlockPalette** — left sidebar with draggable node types
- **BlueprintView** — VueFlow canvas (infinite, zoom, pan)
- **BlockConfigPanel** — right sidebar for node configuration (prompts, model selection, tool binding)
- **LiveView** — execution monitoring (real-time logs, token stream, timing)
- **TimelineView** — execution history with per-node breakdown

### 16 VueFlow node types

Source, Agent, Output, Condition, Loop, Memory, Guardrail, Human, Fallback, Subagent, Group, Comment, Command, FileWrite, Transform, SchemaBuilder

### 5 Pinia stores

| Store | Purpose |
|-------|---------|
| schemaStore | Schema CRUD, current schema, canvas nodes/edges |
| authStore | JWT auth, login/logout, role check |
| panelStore | Block config panel visibility |
| settingsStore | Theme (light/dark/system) |
| counter | Vite scaffold vestige |

### Live execution UIs

3 runtime views: ChatAppUI (conversational), DocAnalyzerAppUI (document analysis), GenericAppUI (generic pipeline output).

## Desktop app (Electron)

The Electron app in `electron/` bundles the Spring Boot JAR as a child process. Features:
- System tray with show/hide, new workflow, quit
- Menu bar (File, Edit, View, Window, Help)
- Global shortcut Cmd/Ctrl+Shift+A
- Native notifications on execution complete/error
- Native file dialogs for open/save
- `electron-updater` for auto-updates from GitHub releases

## Development workflow

### Test-before-apply pattern (for AI agents)

```bash
scripts/sync-to-test.sh     # Copy main → test dirs (backend-next/, frontend-next/)
# ... agent edits in test dirs ...
scripts/sync-from-test.sh   # Copy verified changes back to main dirs
```

### Graph update

```bash
scripts/update-graph.sh                  # Load backend Java code into Neo4j
scripts/setup-graph-hook.sh             # Install git hook for auto-update
```

### Running tests

```bash
cd backend && mvn test
cd frontend && npm run test:unit
cd e2e && npx playwright test
```

## Templates

5 workflow templates in `templates/`:
- `ui-ux-review.json` — analyze frontend codebase for UI/UX issues
- `refactor-frontend.json` — multi-step refactor with worktree-based verification
- `refactor-frontend-simple.json` — simplified 3-node version
- `rllm-project-analysis.json` — project analysis with field-based routing
- `rlm-kimi-code-analysis.json` — deep codebase analysis via RLM + Kimi

Loadable from the Template Gallery (toolbar button) or via the templates API.

## Configuration

Key environment variables (`.env.example`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `AXOLOTL_DB_PATH` | `~/.axolotl/schema.db` | SQLite database path |
| `AXOLOTL_JWT_SECRET` | — | HS256 signing key (≥32 chars) |
| `VITE_API_URL` | `http://localhost:8080` | Backend API URL |
| `VITE_WS_URL` | `ws://localhost:8080` | WebSocket URL |
| `SPRING_PROFILE` | — | Set to `docker` for containerized runs |

Observability: Prometheus metrics at `/actuator/prometheus`, Swagger/OpenAPI at `/swagger.html`, structured JSON logging (Logstash).

## License

MIT
