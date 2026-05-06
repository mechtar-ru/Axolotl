# AGENTS.md

Quick reference for AI agents working in this repository.

## Key Commands

### Backend Dev (prefer this over manual)
```bash
scripts/dev.sh start        # Compile + start backend (kills old)
scripts/dev.sh stop         # Kill backend
scripts/dev.sh logs [N]     # Tail last N lines
scripts/dev.sh execute ID   # Execute schema
scripts/update-graph.sh [path]  # Load/update codebase in Neo4j graph
scripts/setup-graph-hook.sh   # Install git hook to auto-update graph on commit
```

### Manual Backend
```bash
cd backend && mvn spring-boot:run  # Port :8080 (or 8082)
cd backend && mvn compile
```

### Frontend
```bash
cd frontend && npm run dev          # Port :5173
cd frontend && npm run type-check   # vue-tsc
```

### Python Scripts (always use venv)
```bash
source .venv/bin/activate
python3 scripts/api.py GET /api/plan
```

## Critical Patterns

### Authentication
- Use `tech/tech` for all harness/automation API calls
- Get token: `POST /api/auth/login` then use `Authorization: Bearer <token>`
- Use `scripts/api.py` - handles auth automatically

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

### WebSocket
- Connect to `ws://localhost:8080/ws/execution?schemaId={id}`
- Events: `progress`, `log`, `result`, `error`, `complete`, `metrics`

## Architecture

### Backend (Spring Boot, Java 21)
- Entry: `backend/src/main/java/com/agent/orchestrator/Application.java`
- Core: `SchemaService.java` - topological sort, async execution
- WebSocket: `ExecutionWebSocketHandler.java`

### Frontend (Vue 3 + TypeScript)
- Canvas: `WorkflowCanvas.vue` (Vue Flow)
- Nodes: `AgentNode.vue`, `SourceNode.vue`, `OutputNode.vue`
- State: Pinia store `schemaStore.ts`

## Conventions

- Code in English, commits in Russian with emoji prefix
- Java: camelCase, `System.out.println` logging (no SLF4J)
- Vue: Composition API with `<script setup lang="ts">`
- DB: SQLite (`schema.db`) dev, PostgreSQL in Docker
- `.env` needs: `VITE_API_URL`, `VITE_WS_URL`, `JWT_SECRET`

## Neo4j Graph Storage

See `docs/NEO4J_MIGRATION.md` for Neo4j integration specification.

### Graph Update Helper

After commits or code changes, update the Neo4j graph:
```bash
scripts/update-graph.sh [path]     # Load/update codebase (default: backend/src/main/java)
scripts/setup-graph-hook.sh        # Install git hook for auto-update on commit
```

The git hook runs `update-graph.sh` in background after each `git commit`.

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

## Testing

```bash
cd backend && mvn test
cd frontend && npm run test:unit
```

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
cd backend-next && mvn spring-boot:run -Dserver.port=8083 -Daxolotl.db.path=/Users/Shared/Axolotl/test-data.db

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
- Test DB: `/Users/Shared/Axolotl/test-data.db`