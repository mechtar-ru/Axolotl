# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Axolotl is a visual AI-agent orchestration app. Users build node-based workflow graphs on a canvas using Vue Flow, then execute them via Spring Boot backend with wave-based parallel execution (Kahn's algorithm). Supports 14 node types and 7+ LLM providers.

## Commands

### Backend (Spring Boot 3.2, Java 21)
```bash
cd backend && mvn spring-boot:run        # Run backend on :8080
cd backend && mvn compile                 # Compile only
cd backend && mvn test                    # Run tests
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

## Architecture

### Backend (`backend/src/main/java/com/agent/orchestrator/`)
- **Entry point**: `Application.java` (Spring Boot app, package `com.agent.orchestrator`)
- **Controllers**: `AgentController.java` (schemas, execution, agents, mermaid export), `AuthController.java` (JWT auth), `SettingsController.java` (LLM provider settings), `PlanController.java`, `TemplateController.java`, `PluginController.java`, `ShareController.java`, `CustomEndpointController.java`, others
- **Service layer**: `SchemaService.java` — core workflow engine: CRUD, topological sort (Kahn's), wave-based parallel execution via `CompletableFuture`, cancellation, 14 node types. `LlmService.java` — multi-provider LLM abstraction (Ollama, OpenAI, DeepSeek, Anthropic, Spring AI, custom endpoints, RLM). `AgentService.java`, `PlanService.java`, `SettingsService.java`, `ToolExecutor.java`, `TransformService.java`, `MetricsService.java`
- **WebSocket**: `ExecutionWebSocketHandler.java` at `/ws/execution?schemaId=X` — streaming execution updates: `progress`, `result`, `error`, `complete`, `metrics`, `log`, `token`, `wave`, `toolCall`, `iteration`, `trajectoryComplete`
- **Data**: `Neo4jSchemaRepository.java` uses Neo4j (primary). Models: `WorkflowSchema`, `Node` (with `NodeData`, `NodeStatus`), `Edge`, `ExecutionRecord`, `Plan`, `Task`, `Skill`
- **LLM Providers**: `OllamaProvider`, `OpenAiProvider`, `AnthropicProvider`, `DeepSeekProvider`, `SpringAiLlmProvider`, `RlmProvider`, `OpencodeZenProvider`, `CustomLlmProvider`
- **Config**: `AgentConfig.java`, `AppConfig.java`, `WebSocketConfig.java`, `JwtUtil.java`, `DbConfig.java`, `SpringAiConfig.java`

### Frontend (`frontend/src/`)
- **Framework**: Vue 3 Composition API + TypeScript + Pinia stores
- **Canvas**: `WorkflowCanvas.vue` — main component using Vue Flow (`@vue-flow/core`). Registers 14 node types and custom edge type. Supports undo/redo, keyboard shortcuts, search, node grouping, PNG export
- **Node Types**: `SourceNode`, `AgentNode`, `OutputNode`, `ConditionNode`, `TransformNode`, `LoopNode`, `GroupNode`, `CommentNode`, `MemoryNode`, `GuardrailNode`, `HumanNode`, `FallbackNode`, `SubagentNode`, `SchemaBuilderNode` in `components/nodes/`
- **State**: `schemaStore.ts` (Pinia) — loads/saves schemas via API
- **API**: `api.ts` — axios client with JWT auth interceptor, base URL from `VITE_API_URL`
- **WebSocket**: `composables/useWebSocket.ts` — connects to `/ws/execution?schemaId=X`, supports `connectAsync()` for promise-based connection
- **Types**: `types/index.ts` — `WorkflowSchema`, `FlowNode`, `FlowEdge`, `NodeData`, `Agent`, `ExecutionMode`
- **UI Components**: `ExecutionPanel.vue`, `ExecutionHistory.vue`, `PromptEditorModal.vue`, `MemoryGraphView.vue`, `TemplateGallery.vue`, `NodeContextMenu.vue`, `CommandPalette.vue`, `OnboardingModal.vue`, `AppModal.vue`

### Node Types (Backend Execution)
- `source` — data input (text, URL, memory, project)
- `agent` — LLM call with optional tool use (file_read, file_write, grep, bash, etc.)
- `output` — write to file, memory, or log
- `condition` — JS expression evaluation via GraalVM (sandboxed)
- `transform` — data extraction with route-based branching
- `loop` — iterative execution with condition check
- `guardrail` — LLM-based validation/transformation
- `human` — approval gate (auto-approves in current impl)
- `fallback` — executes only if predecessor failed
- `subagent` — delegates to another schema
- `schemabuilder` — generates new workflow from LLM response
- `memory` — MemPalace search
- `command` — shell command execution (sandboxed with blocklist)
- `filewrite` — file write (path-traversal protected)

### API Endpoints
- `GET /api/schemas`, `POST /api/schemas`, `GET /api/schemas/{id}`, `PUT /api/schemas/{id}`, `DELETE /api/schemas/{id}`
- `POST /api/schemas/{id}/execute`, `POST /api/schemas/{id}/stop`
- `GET /api/schemas/{id}/export/mermaid`, `GET /api/schemas/{id}/export/python`
- `GET /api/agents`, `POST /api/agents/{id}/chat`
- `GET /api/settings/providers`, `PUT /api/settings/{provider}`, `GET /api/settings/{provider}/health`
- `POST /api/auth/login`, `POST /api/auth/register`
- WebSocket: `/ws/execution?schemaId={id}`

## Conventions

- Language: code in English, commit messages in Russian with emoji prefixes
- Java: camelCase, Spring Boot conventions, SLF4J logging
- Vue: Composition API with `<script setup lang="ts">`, Pinia stores
- DB: Neo4j (via `Neo4jSchemaRepository`)
- Auth: JWT (Spring Security), configurable secret via `axolotl.jwt.secret`

## Environment

Copy `.env.example` to `.env`. Key variables:
- `VITE_API_URL` — backend API URL (frontend, build-time)
- `VITE_WS_URL` — WebSocket URL (frontend, build-time)
- `SPRING_PROFILE` — Spring profile (`docker`)
- `axolotl.jwt.secret` — JWT signing key (auto-generated if not set)
- `axolotl.sandbox.allowedWriteDirs` — allowed directories for file write node

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

Tools available in AgentNode with tool-enabled mode:
- file_read, file_write, directory_read, grep, git, bash
- memory_read, memory_write, memory_search
- web_search, web_fetch, web_api
- graph_query (Neo4j), mcp_execute

Agent types: assistant, coder, researcher, reviewer, project-analyzer, graph-engineer, mcp-agent
