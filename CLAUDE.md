# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Axolotl is a visual AI-agent orchestration app. Users build node-based workflow graphs (source → agent → output) on a canvas, then execute them. Currently executes with simulated AI calls (OpenClawClient is a stub).

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
docker-compose up                         # Full stack: backend + frontend + postgres + nginx
```

## Architecture

### Backend (`backend/src/main/java/com/agent/orchestrator/`)
- **Entry point**: `Application.java` (Spring Boot app, package `com.agent.orchestrator`)
- **Controller**: `AgentController.java` — single REST controller at `/api` with CORS enabled. Handles schemas CRUD, execution, agent chat, mermaid export, and health check
- **Service layer**: `SchemaService.java` is the core — CRUD, topological sort for execution order (Kahn's algorithm), async workflow execution via `CompletableFuture`, cancellation support. `AgentService.java` manages agents. `OpenClawClient.java` is a stub for AI calls
- **WebSocket**: `ExecutionWebSocketHandler.java` at `/ws/execution?schemaId=X` — sends JSON messages of types: `progress`, `result`, `error`, `complete`, `metrics`, `log`
- **Data**: `SchemaRepository.java` uses SQLite (file `schema.db`). Models: `WorkflowSchema`, `Node` (with nested `Position`, `NodeData`, `NodeStatus`), `Edge`
- **Config**: `AgentConfig.java`, `AppConfig.java`, `WebSocketConfig.java`

### Frontend (`frontend/src/`)
- **Framework**: Vue 3 Composition API + TypeScript + Pinia stores
- **Canvas**: `WorkflowCanvas.vue` — the main component using Vue Flow (`@vue-flow/core`). Registers three node types (`source`, `agent`, `output`) and one edge type (`custom`)
- **Nodes**: `SourceNode.vue`, `AgentNode.vue`, `OutputNode.vue` in `components/nodes/`
- **State**: `schemaStore.ts` (Pinia) — loads/saves schemas via API, tracks current schema
- **API**: `api.ts` — axios client, base URL from `VITE_API_URL` env var (default `http://localhost:8080/api`)
- **WebSocket**: `composables/useWebSocket.ts` — connects to `/ws/execution?schemaId=X`, dispatches callbacks by message type
- **Types**: `types/index.ts` — `WorkflowSchema`, `FlowNode`, `FlowEdge`, `NodeData`, `Agent`
- **Execution panel**: `components/execution/ExecutionPanel.vue`

### API Endpoints
- `GET /api/schemas`, `POST /api/schemas`, `GET /api/schemas/{id}`, `PUT /api/schemas/{id}`, `DELETE /api/schemas/{id}`
- `POST /api/schemas/{id}/execute`, `POST /api/schemas/{id}/stop`
- `GET /api/schemas/{id}/export/mermaid`
- `GET /api/agents`, `POST /api/agents/{id}/chat`
- WebSocket: `/ws/execution?schemaId={id}`

## Conventions

- Language: code in English, commit messages in Russian with emoji prefixes (e.g., `UI upd + websockets`)
- Java: camelCase, Spring Boot conventions, `System.out.println` for logging (no SLF4J yet)
- Vue: Composition API with `<script setup lang="ts">`, Pinia stores
- Node types: `source`, `agent`, `output` (more planned: `condition`, `loop`, `memory`, etc.)
- DB: SQLite for dev (`schema.db`), PostgreSQL for Docker/prod

## Environment

Copy `.env.example` to `.env`. Key variables:
- `VITE_API_URL` — backend API URL (frontend)
- `VITE_WS_URL` — WebSocket URL (frontend)
- `SPRING_PROFILE` — Spring profile (`docker` for PostgreSQL)
- `POSTGRES_*` — PostgreSQL credentials

## Templates

Located in `/templates/`:
- `ui-ux-review.json` — Analyze frontend codebase and provide UI/UX recommendations
- `refactor-frontend.json` — Multi-step refactoring workflow
- `rlm-kimi-code-analysis.json` — Code analysis with RLM

## Graph API (Neo4j Integration)

When Neo4j is enabled (axolotl.graph.enabled=true):
- `POST /api/graph/load` — Load codebase into Neo4j
- `GET /api/graph/class/{hash}` — Get class by 16-char hash
- `POST /api/graph/curate` — Get token-bounded context for LLM
- `GET /api/graph/tiers` — Get import wave plan

### Agent Tools

New tools available in AgentNode:
- file_read, file_write, directory_read, grep, git, bash
- memory_read, memory_write, memory_search
- web_search, web_fetch, web_api
- graph_query (Neo4j), mcp_execute

Agent types: assistant, coder, researcher, reviewer, project-analyzer, graph-engineer, mcp-agent
