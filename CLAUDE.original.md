# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Behavioral Guidelines

Tradeoff: These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding
Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First
Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes
Touch only what you must. Clean up only your own mess.

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.
- The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution
Define success criteria. Loop until verified.

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

These guidelines are working if: fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

## Project Overview

Axolotl is a visual AI-agent orchestration app. Users build node-based workflow graphs (source → agent → output) on a canvas and execute them. Supports real LLM calls via Ollama and Spring AI, tool-calling agents with file/command nodes, task management via Plan API, and MemPalace integration for memory.

## Commands

### Backend (Spring Boot, Java 21)
```bash
cd backend && mvn spring-boot:run        # Run backend on :8080
cd backend && mvn compile                 # Compile only
cd backend && mvn test                    # Run tests (none yet)
```

### Frontend (Vue 3 + Vite + TypeScript)
```bash
cd frontend && npm install                # Install deps
cd frontend && npm run dev                # Dev server on :5173
cd frontend && npm run build              # Production build
cd frontend && npm run test:unit          # Run vitest
cd frontend && npm run type-check         # vue-tsc type checking
```

### Docker
```bash
docker-compose up                         # Full stack: backend + frontend + nginx + neo4j
```

## API Authentication

Axolotl uses **JWT Bearer tokens** for UI/harness access and **API Keys** for remote programmatic access.

**Note:** `.env` contains `JWT_SECRET` (signing key) and `ZEN_API_KEY` but **no JWT tokens** — tokens are obtained at runtime via `/api/auth/login`.

### Quick Auth for Harnesses (opencode, etc.)

**Always use `tech/tech` credentials for all harness/automation interactions.**

**Minimal API calls (use `scripts/api.py`):**
```bash
# Any API call — handles auth automatically (uses tech/tech)
python3 scripts/api.py GET  /api/plan
python3 scripts/api.py POST /api/plan/tasks '{"title":"Task","priority":"HIGH"}'
python3 scripts/api.py PUT /api/plan/tasks/ID/status '{"status":"DONE"}'
```
Script location: `/scripts/api.py` (root of Axolotl project).

**Manual curl (if needed):**
```bash
TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tech","password":"tech"}' | jq -r '.token')
curl -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/plan
```

### Available Credentials
| User | Password | Role | Usage |
|------|----------|------|-------|
| `tech` | `tech` | tech | **Harnesses/automation (use this)** |
| `admin` | `admin` | admin | Manual/admin tasks only |

### JWT Details
- **Algorithm**: HS256
- **Expiration**: 24 hours
- **Obtain**: `POST /api/auth/login`
- **Use**: `Authorization: Bearer <token>`

### API Key (Remote Access)
```bash
# Create key (requires JWT auth)
curl -X POST http://localhost:8082/api/remote/keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"My Key","scopes":["workflows:read","workflows:execute"]}'

# Use key
curl -H "X-API-Key: axk_..." http://localhost:8082/api/remote/workflows
```

## Working with Plans

**CRITICAL: Always use the Plan API (`/api/plan`) to manage tasks — never directly edit `plan.json`.**

**Before adding tasks: Always check if they already exist in the API.**
```bash
# Check existing tasks before adding
python3 scripts/api.py GET "/api/plan?format=full" | grep "title"
```

**Never add tasks that already exist.** If a task exists in the API (check by title or order), do not create duplicates.

### Plan API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/plan?workspaceId=default` | Get full plan |
| `POST` | `/api/plan/tasks` | Add single task |
| `POST` | `/api/plan/tasks/batch` | Add multiple tasks |
| `PUT` | `/api/plan/tasks/{id}/status` | Update task status |
| `PUT` | `/api/plan/tasks/{id}/priority` | Update priority |
| `PUT` | `/api/plan/tasks/{id}/move` | Move task position |
| `DELETE` | `/api/plan/tasks/{id}` | Delete task |

### Example: Adding Tasks via API (Harness Pattern)
```bash
# Simplest way — uses tech credentials automatically
python3 scripts/api.py POST /api/plan/tasks '{"title":"New task","priority":"HIGH","position":{"type":"end"}}'

# Add multiple tasks
python3 scripts/api.py POST /api/plan/tasks/batch '{"tasks":[{"title":"Task 1"},{"title":"Task 2"}]}'

# Update status
python3 scripts/api.py PUT /api/plan/tasks/ID/status '{"status":"DONE"}'
```

**Note**: `plan.json` is the persistence file but the API is the interface. Direct edits to `plan.json` will be overwritten by the API.

## Architecture

### Backend (`backend/src/main/java/com/agent/orchestrator/`)
- **Entry point**: `Application.java` (Spring Boot app, package `com.agent.orchestrator`)
- **Controllers**: `AgentController.java` (schemas, execution, agents, memory, LLM), `PlanController.java` (task management), `RemoteApiController.java` (API Keys, remote workflow execution), `TemplateController.java`, `AuthController.java`
- **Service layer**: `SchemaService.java` is the core — CRUD, topological sort (Kahn's algorithm), async execution via `CompletableFuture`, cancellation. `AgentService.java` manages agents. `LlmService.java` handles real LLM calls (Ollama, Spring AI). `PlanService.java` manages tasks via Plan API
- **WebSocket**: `ExecutionWebSocketHandler.java` at `/ws/execution?schemaId=X` — sends JSON messages: `progress`, `result`, `error`, `complete`, `metrics`, `log`
- **Tools**: `ToolExecutor.java` with built-in tools (file_read, file_write, bash), `Tool.java` interface, `ToolPermission.java`
- **Data**: `Neo4jSchemaRepository` (Neo4j), `PlanRepository`, `ApiKeyRepository`, `UserRepository`. Models: `WorkflowSchema`, `Node`, `Plan`, `Task`, `ApiKey`, `AppUser`
- **Config**: `AgentConfig.java`, `AppConfig.java`, `SecurityConfig.java` (JWT auth), `JwtUtil.java`, `WebSocketConfig.java`

### Frontend (`frontend/src/`)
- **Framework**: Vue 3 Composition API + TypeScript + Pinia stores
- **Canvas**: `WorkflowCanvas.vue` — main component using Vue Flow (`@vue-flow/core`). Registers node types: `source`, `agent`, `output`, `filewrite`, `command`, `schemabuilder`
- **Nodes**: `SourceNode.vue`, `AgentNode.vue`, `OutputNode.vue`, `FileWriteNode.vue`, `CommandNode.vue` in `components/nodes/`
- **State**: `schemaStore.ts` (Pinia) — loads/saves schemas via API, tracks current schema
- **API**: `api.ts` — axios client, base URL from `VITE_API_URL` env var
- **WebSocket**: `composables/useWebSocket.ts` — connects to `/ws/execution?schemaId=X`
- **Types**: `types/index.ts` — `WorkflowSchema`, `FlowNode`, `FlowEdge`, `NodeData`, `Agent`
- **Execution panel**: `components/execution/ExecutionPanel.vue` (with Trajectory tab)
- **Plan panel**: `components/plan/PlanPanel.vue` — task management UI with workspaces
- **Tools**: AgentNode includes tool selector and permissions panel

### API Endpoints

**Core (`/api`):**
- `GET /api/schemas`, `POST /api/schemas`, `GET /api/schemas/{id}`, `PUT /api/schemas/{id}`, `DELETE /api/schemas/{id}`
- `POST /api/schemas/{id}/execute`, `POST /api/schemas/{id}/stop`
- `GET /api/schemas/{id}/export/mermaid`, `GET /api/schemas/{id}/export/python`
- `GET /api/agents`, `POST /api/agents/{id}/chat`
- `GET /api/health`, `POST /api/llm/test`
- `GET /api/memory/search`, `GET /api/memory/taxonomy`, `POST /api/memory/add`
- WebSocket: `/ws/execution?schemaId={id}`

**Plan (`/api/plan`) — use this for all task management:**
- `GET /api/plan?workspaceId=default&format=full`
- `POST /api/plan/tasks`, `POST /api/plan/tasks/batch`
- `PUT /api/plan/tasks/{id}/status`, `PUT /api/plan/tasks/{id}/priority`, `PUT /api/plan/tasks/{id}/move`
- `PUT /api/plan/tasks/{id}/criteria`, `PUT /api/plan/tasks/{id}/link`
- `DELETE /api/plan/tasks/{id}?cascade=true`
- `GET /api/plan/workspaces`, `POST /api/plan/workspaces`
- `GET /api/plan/{planId}/children`, `POST /api/plan/{planId}/subplan`

**Remote API (`/api/remote`) — API Key auth:**
- `POST /api/remote/keys`, `GET /api/remote/keys`, `DELETE /api/remote/keys/{id}`
- `POST /api/remote/workflows/{id}/run`, `GET /api/remote/workflows/{id}/status`

**Templates (`/api/templates`):**
- `GET /api/templates`, `GET /api/templates/{id}`

## Conventions

- Language: code in English, commit messages in Russian with emoji prefixes (e.g., `UI upd + websockets`)
- Java: camelCase, Spring Boot conventions, `System.out.println` for logging (no SLF4J yet)
- Vue: Composition API with `<script setup lang="ts">`, Pinia stores
- Node types: `source`, `agent`, `output` (more planned: `condition`, `loop`, `memory`, etc.)
- DB: Neo4j

## Environment

Copy `.env.example` to `.env`. Key variables:
- `VITE_API_URL` — backend API URL (frontend)
- `VITE_WS_URL` — WebSocket URL (frontend)
- `SPRING_PROFILE` — Spring profile (`docker`)
