# AGENTS.md

Quick reference for AI agents working in this repository.

## Key Commands

### Backend Dev (prefer this over manual)
```bash
scripts/dev.sh start          # Compile + start backend in background
scripts/dev.sh start-fg       # Compile + start backend in foreground
scripts/dev.sh stop           # Kill backend
scripts/dev.sh logs [N]       # Tail last N lines from /tmp/axolotl-backend.log
scripts/dev.sh execute ID     # Execute schema (POST /api/schemas/{id}/execute)
scripts/dev.sh frontend       # Start Vite frontend in background
scripts/update-graph.sh [path]  # Load/update codebase in Neo4j graph
scripts/setup-graph-hook.sh   # Install git hook to auto-update graph on commit
```

### Manual Backend
```bash
cd backend && mvn spring-boot:run -Dserver.port=8082  # Port :8082 only
cd backend && mvn compile
```

### Frontend
```bash
cd frontend && npm run dev          # Port :5173
cd frontend && npm run type-check   # vue-tsc
```

### Python Scripts (always use venv)
```bash
# First time setup:
python3 -m venv .venv               # Create virtual environment (one-time)
source .venv/bin/activate            # Activate it

# Available commands:
python3 scripts/api.py login                              # Auth + cache token
python3 scripts/api.py GET /api/plan                      # Generic REST call
python3 scripts/api.py POST /api/schemas @body.json       # @file syntax for JSON bodies
python3 scripts/api.py execute <schema-id>                # Execute a schema
python3 scripts/api.py wait <schema-id>                   # Poll until execution completes
python3 scripts/api.py results <schema-id>                # Show node execution results (outputSummary)
python3 scripts/api.py nodes <schema-id>                  # Show node configs (type, model, tools)
python3 scripts/api.py add-task "Title" "Desc"            # Quick add plan task via MCP
python3 scripts/api.py mcp <tool> '{"arg": "value"}'     # Direct MCP tool call
```

## Critical Patterns

### Authentication
- Backend runs on port **8082** (not 8080)
- Use `tech/tech` for all harness/automation API calls
- **For curl**: `source scripts/token.sh` — exports `$TOKEN` and `$CURL_HEADER`
  ```bash
  source scripts/token.sh                         # caches in ~/.axolotl/token
  curl -s -H "$CURL_HEADER" http://localhost:8082/api/schemas
  curl -s -H "$CURL_HEADER" http://localhost:8082/api/app/ea42107f-c3ac-4080-8fb7-645f5a62080e | python3 -m json.tool
  ```
- **For Python**: `scripts/api.py` — handles auth automatically
  ```bash
  source .venv/bin/activate
  python3 scripts/api.py GET /api/schemas
  ```

### WebSocket
- Connect to `ws://localhost:8082/ws/execution?schemaId={id}`
- Events: `progress`, `log`, `result`, `error`, `complete`, `metrics`, `paused`

### Task Management
- **NEVER edit plan.json directly** - use Plan API or MCP
- Check existing tasks: `GET /api/plan?format=full`
- Endpoints: `POST /api/plan/tasks`, `PUT /api/plan/tasks/{id}/status`

### Plan MCP Commands (easier than REST)
```bash
# Add task via MCP (recommended)
curl -s -X POST http://localhost:8082/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"add_task","arguments":{"title":"Task title","priority":"HIGH","description":"Optional description"}}}'

# Read plan
curl -s -X POST http://localhost:8082/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_plan","arguments":{"format":"status_summary"}}}'

# Update task status
curl -s -X POST http://localhost:8082/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"update_task_status","arguments":{"task_id":"ID","status":"DONE"}}}'

# Quick add (alias for scripts)
source .venv/bin/activate && python3 scripts/api.py add-task "Title" "description"
```

### Quick Start — Fixed Pipeline

Quick Start creates schemas with a **fixed pipeline template**: Receive → Review → Agent (think with tools) → Verify → Output.

- Presets describe **only the application** (features, UX, behavior), not pipeline instructions
- Quick Start input resolves to the Receive node as source content (`sourceType: "text"`)
- When the input describes an application, that description is also passed to the Verify node as verification criteria
- **`POST /api/schemas/{id}/generate-nodes` is deprecated** — returns an error. Quick Start no longer uses LLM-driven node generation
- The pipeline template is applied at schema creation time; stages can be configured via the PipelinePanel in Studio

### Database Architecture
- **Neo4j** — primary storage for schemas, plans, execution history, code graph, auth

## Architecture

### Backend (Spring Boot, Java 21)
- Entry: `backend/src/main/java/com/agent/orchestrator/Application.java`
- Core services: `SchemaService` (execution orchestration), `PipelineService` (multi-stage pipeline), `NodeRouter` (node type dispatch), `LlmService` (provider routing)
- WebSocket: `ExecutionWebSocketHandler.java`

### Pipeline System

Multi-stage pipeline execution for complex app generation:
- **Pipeline** contains named stages with dependencies (topological sort)
- **Stage types**: agent (code generation), review (plan review), verifier (checks), output (report)
- **TDD mode**: when `pipeline.tddEnabled=true`, each branch expands to 4 stages: test → verify-test → impl → verify
- Stage status and outputs persist to Neo4j (`ExecutionRun.stageStatus`, `ExecutionRun.stageOutputs`)
- **Cross-stage artifact passing**: `Stage.inputMapping` with dot-notation field extraction from upstream stage outputs
- **Retry from failure**: `POST /api/schemas/{id}/pipeline/retry` creates a child `ExecutionRun` with `resumesFrom`, resets failed+dependent stages, re-executes only those
- PipelinePanel in Studio sidebar shows per-stage status with Build/Execute/Cancel/Retry buttons

### Node Types

| Type | Label | What It Does |
|------|-------|--------------|
| `source` | Receive | Input: text pasted directly, file reference (relative to targetPath), URL fetch, or project dir listing |
| `agent` | Agent | Tool-enabled LLM with system prompt, model selection, write/read/bash/grep/web tools |
| `review` | Review | Two-phase plan generation + analysis. Three checks: premortem, prism, postmortem. Three iteration modes: Manual (human gates every iteration), Auto (configurable max N, fails on exceed), Hybrid (auto N, then manual). Uses `ReviewApprovalDialog` with Accept/Edit/Suggest & Regenerate/Reject |
| `verifier` | Verifier | Runs checks against generated code. Structured JSON verdict `{"status":"PASS"|"FAIL","checks":[...],"summary":"..."}`. Optional auto-rewrite loop up to `maxRewriteRetries` (default 3) |
| `output` | Output | Collects execution results. Modes: stdout, log, summary_report (writes `pipeline-report.md` to targetPath) |

### Frontend (Vue 3 + TypeScript)
- Canvas: `BlueprintView.vue` (Vue Flow), `SchemaPropertiesPanel.vue`
- Nodes: custom render components per type (ReceiveBlock, ThinkBlock, ReviewBlock, VerifyBlock, OutputBlock)
- State: Pinia stores `schemaStore.ts`, `settingsStore.ts`
- Studio: keeps-alive with `onActivated`/`onDeactivated` WebSocket lifecycle; dirty-flag auto-save on blueprint edits

## Conventions

- Code in English, commits in English with conventional-commits prefixes (`feat:`, `fix:`, `chore:`, `docs:`, etc.)
- Java: camelCase, SLF4J logging with `LoggerFactory.getLogger(...)`
- Vue: Composition API with `<script setup lang="ts">`
- DB: Neo4j
- `.env` needs: `VITE_API_URL=http://localhost:8082/api`, `VITE_WS_URL=ws://localhost:8082/ws/execution`, `JWT_SECRET`

## Execution Result Persistence

Execution results (node outputs, review findings, errors) are persisted to Neo4j for durability across page reloads.

### How It Works

1. `NodeRouter.executeNode()` creates a `NodeExecution` record per node at execution start (status: `"running"`)
2. After the node completes, `updateNodeExecution()` persists the result as `outputSummary` and sets status to `"completed"` (or `"failed"` on error)
3. Each `NodeExecution` is linked to an `ExecutionRun` which belongs to a schema

### Key Models

| Model | Neo4j Label | DB Fields | Description |
|-------|-------------|-----------|-------------|
| `ExecutionRun` | `ExecutionRun` | id, schemaId, status, mode, totalTokens, estimatedCost, error, timestamps, stageStatus, stageOutputs, resumesFrom | One per schema execution |
| `NodeExecution` | `NodeExecution` | id, runId, nodeId, nodeType, status, tokensUsed, durationMs, toolCalls, error, **outputSummary**, inputSummary, filesWritten, configHash, timestamps | One per node per run |

### For AI Agents

- **Reading persisted results**: `GET /api/schemas/{id}/runs/{runId}/nodes` returns all `NodeExecution` records for a run, each with `outputSummary` containing the node result JSON
- **Latest run**: `GET /api/schemas/{id}/runs/latest` returns the most recent `ExecutionRun` (use its `id` to fetch nodes)
- **Review node results**: The full review output (status, findings, summary, plan, mode, finalResult) is stored in `outputSummary` as JSON — survives page reloads
- **Error state**: Failed nodes have status `"failed"` and their error message in the `error` field
- **Pipeline stage status**: `ExecutionRun.stageStatus` is a `Map<String,String>` with stage-level status (`pending`/`running`/`completed`/`failed`/`paused`)

## Neo4j Graph Storage

See `docs/NEO4J_MIGRATION.md` for Neo4j integration specification.

### Graph Update Helper

After commits or code changes, update the Neo4j graph:
```bash
scripts/update-graph.sh [path]     # Load/update codebase (default: backend/src/main/java)
scripts/setup-graph-hook.sh        # Install git hook for auto-update on commit
```

The git hook runs `update-graph.sh` in background after each `git commit`.

### Graph Query Tool (for Agent Nodes)

Agent nodes can now query Neo4j directly using `graph_query` tool:
```json
{
  "query": "MATCH (c:Class) RETURN c.name LIMIT 10",
  "type": "search"
}
```

### Graph API (Dirac-inspired Features)

#### Endpoints
- `POST /api/graph/load` — Load codebase into Neo4j
- `GET /api/graph/class/{hash}` — Get class by 16-char hash
- `POST /api/graph/hash/class` — Compute stable hash for class
- `POST /api/graph/search/ast` — Search by AST patterns
- `POST /api/graph/curate` — Get token-bounded context for LLM
- `GET /api/graph/tiers` — Get import wave plan
- `GET /api/graph/stats` — Graph statistics

#### Hash-Anchored Edits
Agents reference code by stable hash (16 hex chars):
```java
// Hash computed from class signature, stable on internal refactoring
String hash = "038f2e49841afecb";
// Use in prompts: "Update class with hash 038f2e49841afecb"
```

#### Context Curation
```bash
curl -X POST http://localhost:8082/api/graph/curate \
  -H "Content-Type: application/json" \
  -d '{"query": "authentication middleware", "tokenBudget": 2000}'
```

#### Configuration
```yaml
axolotl:
  graph:
    enabled: true
    token-budget: 2000
    batch-parallelism: 8
    batch-size: 20
```

## External App Development (Multi-Session)

Схемы Axolotl могут генерировать внешние приложения — не только возвращать текст в UI, но и писать файлы в целевую директорию. Каждый запуск схемы = одна сессия разработки. Несколько сессий последовательно собирают приложение.

### Целевая директория

Результаты всех сессий для одного приложения пишутся в:

```
{project-root}/{schema-name}/
```

Где `{project-root}` — корень проекта (Axolotl), а `{schema-name}` — имя схемы (WorkflowSchema.name). Путь доступен как `targetPath` в свойствах схемы.

### Правила для агентов

1. **Результат схемы — файлы на диске, не строка в лог.** Пиши через `file_write` tool. OutputNode с `outputType: "log"` используй только для отчётов/логов.

2. **Не выходи за пределы targetPath.** `file_write` за границы директории проекта блокируется. Если нужно записать куда-то ещё — настрой `allowedPaths` в конфиге узла явно.

3. **Перед началом работы — прочитай существующие файлы.** Используй `file_read` и `directory_read`, чтобы понять, что уже создано в предыдущих сессиях. Не пересоздавай то, что уже есть.

4. **Идемпотентность.** Повторный запуск схемы не должен ломать проект:
   - Существующие файлы — либо пропускай, либо обновляй с проверкой
   - Новые файлы — создавай
   - Не перезаписывай файлы других сессий без необходимости

5. **Описывай созданные файлы.** В конце работы верни JSON со списком созданных/изменённых файлов и кратким описанием каждого. Это попадёт в Plan и будет видно в следующих сессиях:
   ```json
   {
     "generatedFiles": [
       {"path": "src/components/Game.vue", "description": "Main game component with 10 levels"},
       {"path": "src/App.vue", "description": "Updated App.vue with Game route"}
     ]
   }
   ```

6. **Читай контекст перед началом.** В systemPrompt автоматически добавляется дерево файлов проекта и история завершённых сессий. Используй это, чтобы понимать текущее состояние.

### Дизайн многосессионного процесса

Каждое приложение = отдельный workspace в Axolotl. Plan в этом workspace отслеживает прогресс.

```
Session 1: "Scaffold project"
  → создаёшь package.json, index.html, vite.config
  → Plan: [DONE] scaffold

Session 2: "Create Game Engine"  
  → agent видит существующие файлы
  → создаёшь Game.vue, main.ts
  → Plan: [DONE] game engine

Session 3: "Add Level Editor"
  → agent видит Game.vue, добавляет редактор
  → Plan: [DONE] level editor
```

Plan-задачи создаются автоматически при запуске схемы. Не нужно создавать их вручную.

### Обработка конфликтов

Если при создании схемы директория проекта уже существует — система предложит выбор:
- **CONTINUE** — дописывать в существующую директорию (используй для новой сессии)
- **OVERWRITE** — очистить директорию и начать заново (используй для перезапуска)
- **CHANGE_PATH** — указать другой путь

### Agent Workflow: Test Dirs for Implementing Changes

Use test directories (`backend-next/`, `frontend-next/`) to implement and verify changes before updating main code.

#### Workflow

1. Agent works in test dirs, makes code changes
2. Starts test instance from test dirs
3. Tests via test API (localhost:8083)
4. On success, sync to main dirs

#### Commands

```bash
# Sync main → test dirs (before starting work)
scripts/sync-to-test.sh

# Start test backend from test dirs
cd backend-next && mvn spring-boot:run -Dserver.port=8083

# Start test frontend from test dirs
cd frontend-next && npm run dev -- --port 5174

# Test via API
curl localhost:8083/api/...
curl localhost:8082/api/...  # main for comparison

# Stop test instance
pkill -f "spring-boot:run"      # backend
pkill -f "vite.*5174"        # frontend

# Sync verified changes to main dirs (after successful test)
scripts/sync-from-test.sh

# Restart Axolotl to use verified changes
```

#### Key Files

- Test backend: `backend-next/src/main/java/com/agent/orchestrator/`
- Test frontend: `frontend-next/src/`
