# CLAUDE.md

Guide for AI agents working in this repository.

## Project Overview

Axolotl is a visual AI-agent orchestration tool. Users design graph-based workflows on a VueFlow canvas, configure blocks (Receive, Agent, Review, Verify, Output), and execute them via Spring Boot backend with multi-stage pipeline support.

## Commands

### Backend (Spring Boot 3.3, Java 21)
```bash
cd backend && mvn spring-boot:run -Dserver.port=8082  # Run backend on :8082
cd backend && mvn compile
cd backend && mvn test
```

### Frontend (Vue 3 + Vite + TypeScript)
```bash
cd frontend && npm install
cd frontend && npm run dev                # Dev server on :5173
cd frontend && npm run build
cd frontend && npm run type-check         # vue-tsc
```

### Python Scripts
```bash
python3 scripts/api.py login
python3 scripts/api.py GET /api/schemas
python3 scripts/api.py pipeline-execute <schema-id>
```

## Architecture

### Backend
- **Entry**: `Application.java`
- **Controllers**: `AgentController.java` (schemas, pipeline, execution), `AuthController.java` (JWT), `SettingsController.java` (LLM providers), `PlanController.java`, `TemplateController.java`
- **Core services**: `SchemaService` (execution engine), `PipelineService` (multi-stage pipeline), `NodeRouter` (node type dispatch), `LlmService` (provider routing)
- **Pipeline**: `PipelineService` with topological sort, pause/resume on review, retry from failure, TDD expansion, cross-stage artifact passing
- **Node strategies**: `AgentNodeStrategy`, `ReviewNodeStrategy`, `VerifierNodeStrategy`, `SchemaBuilderNodeStrategy`
- **WebSocket**: `/ws/execution?schemaId=X` — events: progress, result, error, complete, paused, metrics, toolCall
- **Database**: Neo4j (primary), no SQLite/PostgreSQL
- **LLM providers**: OpenAI, Anthropic, DeepSeek, Zen, Ollama, CustomLlmProvider (all fetch models dynamically)

### Frontend
- **Framework**: Vue 3 Composition API + TypeScript + Pinia
- **Canvas**: `BlueprintView.vue` (VueFlow), `BlockConfigPanel.vue` (config sidebar)
- **Block palette**: ReceiveBlock, ThinkBlock, ReviewBlock, VerifyBlock, OutputBlock
- **Pipeline**: `PipelinePanel.vue` with stage controls, review approval dialog, resume banner
- **Stores**: `schemaStore.ts` (dirty-flag auto-save, pipeline state), `settingsStore.ts`

### Pipeline
- **Stages**: Receive → Review → Agent → Verify → Output (5-stage default)
- **TDD mode**: Expands to test→verify-test→impl→verify (8-stage)
- **Stage execution**: Topological sort, parallel within levels, pause on AWAITING_APPROVAL
- **State**: Neo4j-persisted `ExecutionRun.stageStatus/stageOutputs` (JSON strings)

### API Endpoints
- Schema CRUD: `GET/POST/PUT/DELETE /api/schemas[/{id}]`
- Pipeline: `POST /api/schemas/{id}/pipeline/{build,execute,retry,cancel,default}`, `GET .../status`
- Execution: `POST /api/schemas/{id}/execute`, `POST /api/schemas/{id}/stop`
- Review: `POST /api/execution/{id}/approve-review`, `POST /api/execution/{id}/reject`
- Auth: `POST /api/auth/login`, `POST /api/auth/register`
- Settings: `GET /api/settings/providers`, `PUT /api/settings/{provider}`, `GET /api/settings/{provider}/health`

## Conventions

- Code in English, commits in English with conventional-commits prefixes (`feat:`, `fix:`, `chore:`, `docs:`)
- Java: camelCase, SLF4J logging, constructor DI
- Vue: Composition API with `<script setup lang="ts">`
- DB: Neo4j 5.x, SDN 6
- Auth: JWT (Spring Security), credentials in `.env`

## Environment

- Backend port: **8082** (not 8080)
- Neo4j: `bolt://localhost:7687`, user `neo4j`, password `axolotl2026`
- Frontend: `VITE_API_URL=http://localhost:8082/api`, `VITE_WS_URL=ws://localhost:8082/ws/execution`
- JWT secret: `axolotl.jwt.secret` in application.yml

## Project Structure

```
backend/          # Spring Boot (Maven)
frontend/         # Vue 3 + Vite
harness/          # OpenCode integration (MCP servers, test tools, scripts)
scripts/          # Dev lifecycle, API client, migrations
templates/        # Workflow blueprints
docs/             # VitePress
```

## Key Files

- `PipelineService.java` — 1116 lines, core pipeline orchestration
- `SchemaService.java` — 1116 lines, workflow execution engine
- `NodeRouter.java` — 444 lines, node type routing
- `BlueprintView.vue` — VueFlow canvas component
- `PipelinePanel.vue` — pipeline controls sidebar
- `schemaStore.ts` — Pinia store with dirty-flag auto-save
