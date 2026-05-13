---
session: ses_1e1e
updated: 2026-05-12T21:32:49.443Z
---

Here's the structured summary of everything we've discovered:

# Session Summary

## Goal
Map all available MCP tools and REST API endpoints in Axolotl to understand the full API surface for tool building and workflow automation.

## Constraints & Preferences
- Tools are defined in `PlanTools.java` (7 tools) and `GraphMcpTools.java` (3 tools) — no other MCP tool classes exist
- All REST controllers follow `@RestController` + `@RequestMapping("/api/{domain}")` pattern
- MCP endpoint is at `POST /mcp` with JSON-RPC 2.0; health check at `GET /mcp`
- No complete "create plan → create schema → execute" workflow scripts exist in `scripts/`

## Progress
### Done
- [x] Found and read **`PlanMcpServer.java`** — the main MCP JSON-RPC server at `POST /mcp` (Spring Boot port 8080). Delegates tool execution to `PlanTools`.
- [x] Found and read **`PlanTools.java`** — defines **7 MCP tools** for plan management.
- [x] Found and read **`GraphMcpTools.java`** — defines **3 MCP tools** for code graph operations.
- [x] Read **`McpIntegrationTest.java`** — integration test covering all plan MCP tools.
- [x] Read all **16 REST controllers** under `backend/src/main/java/com/agent/orchestrator/controller/`.
- [x] Read **`GraphController.java`** under `graph/api/` — the internal REST API for code graph.
- [x] Read **`SchemaControllerIntegrationTest.java`** — shows full REST API usage for schema CRUD + execution.
- [x] Read **`scripts/dev.sh`** — thin script with `start`, `stop`, `logs`, `execute <schema_id>`.

### In Progress
- [ ] No existing complete workflow scripts (create plan → create schema → execute) found

### Blocked
- (none)

## Key Decisions
- **Two separate MCP tool sources**: `PlanTools` (plan management) registered in `PlanMcpServer`, and `GraphMcpTools` (code graph) available as a `@Component`. Plan-only tools wired into JSON-RPC endpoint; Graph tools would need separate wiring.
- **Schema = workflow definition**: `WorkflowSchema` is the database entity for visual workflow graphs. No direct schema management MCP tool exists — schema CRUD is only via REST.
- **No api.py script found**: The AGENTS.md reference to `scripts/api.py` is stale/absent. `scripts/` contains only `migrate-schemas.py`, `migrate-to-neo4j.py`, and `dev.sh`.

## Next Steps
1. Decide which MCP tools to expose to Claude — all 10 existing, or a subset
2. If workflow execution from MCP is needed, create new tool(s) in `PlanTools` or a new `SchemaMcpTools` class
3. Add MCP tools for schema CRUD if the plan includes "create schema via Claude"
4. Write a demo/example script showing the full workflow loop

## Critical Context

### MCP Tools — `PlanTools` (7 tools at `POST /mcp`)
Registered in `PlanMcpServer` via `planTools.getToolSpecs()`. All use JSON-RPC 2.0 over HTTP.

| Tool | Parameters | Description |
|------|-----------|-------------|
| `read_plan` | `workspace_id` (string, opt), `format` (enum: full/tasks_only/status_summary) | Returns plan as JSON |
| `add_tasks` | `workspace_id` (string, opt), `tasks` (array of `{title, description, status?, priority?, dependencies?, metadata?}`) | Batch add multiple tasks |
| `update_task_status` | `task_id` (string), `status` (string) | Update single task status |
| `move_task` | `task_id` (string), `new_index` (number) | Reorder task in plan |
| `add_task` | `workspace_id` (string, opt), `title` (string), `description` (string), `status` (string, opt) | Add single task |
| `delete_task` | `task_id` (string) | Remove task |
| `update_task_priority` | `task_id` (string), `priority` (string: low/medium/high/critical) | Change priority |

### MCP Tools — `GraphMcpTools` (3 tools, `@Component` not wired to JSON-RPC endpoint)
These exist as a `@Component` but are **NOT registered** in `PlanMcpServer` — they'd need separate wiring to be exposed at the MCP endpoint.

| Tool | Parameters | Description |
|------|-----------|-------------|
| `load_codebase` | `path` (string) | Load codebase at path into graph |
| `get_class_by_hash` | `hash` (string) | Look up class by hash or name |
| `compute_hash` | `packageName` (string), `className` (string), `content` (string) | Compute hash for code entity |

### REST API Endpoints — Complete Map

| Prefix | Class | Key Endpoints |
|--------|-------|---------------|
| `POST /mcp` | `PlanMcpServer` | JSON-RPC 2.0 endpoint for plan MCP tools |
| `GET /mcp` | `PlanMcpServer` | Health check returning tool list |
| `GET/POST /api/agent` | `AgentController` | List agents, chat |
| `GET/POST /api/app` | `AppController` | List/create apps (wraps schema) |
| `POST /api/auth/login` | `AuthController` | JWT login (admin:admin default) |
| `POST /api/crosscheck` | `CrossCheckController` | Verify agent output |
| `GET /api/evidence` | `EvidenceController` | List evidence for test runs |
| `GET /api/harness/*` | `HarnessController` | Harness components CRUD |
| `POST /api/harness/evolve` | `EvolveController` | Start evolution loop |
| `GET/POST /api/manifest` | `ManifestController` | Manifest YAML CRUD |
| `GET/POST /api/plan/*` | `PlanController` | Plan CRUD, tasks, statuses |
| `POST /api/plan/tasks` | `PlanController` | Add task |
| `POST /api/plan/tasks/batch` | `PlanController` | Batch add tasks |
| `PUT /api/plan/tasks/{id}/status` | `PlanController` | Update task status |
| `PUT /api/plan/tasks/{id}/priority` | `PlanController` | Update task priority |
| `DELETE /api/plan/tasks/{id}` | `PlanController` | Delete task |
| `PUT /api/plan/tasks/{id}/move` | `PlanController` | Reorder task |
| `POST /api/plugins/install` | `PluginController` | Install plugin |
| `POST /api/remote/execute` | `RemoteApiController` | Execute schema via API key |
| `GET/PUT /api/settings` | `SettingsController` | Provider settings |
| `GET/POST /api/settings/endpoints` | `CustomEndpointController` | Custom LLM endpoints |
| `GET/POST/PUT/DELETE /api/skills` | `SkillController` | Skills CRUD |
| `POST /api/share/schemas/{id}` | `ShareController` | Create share link |
| `GET /api/templates` | `TemplateController` | Workflow templates |
| `POST /api/graph/load` | `GraphController` | Load codebase into graph |
| `GET /api/graph/class/{hash}` | `GraphController` | Get class by hash |
| `GET /api/graph/search` | `GraphController` | AST search |
| `GET /api/graph/metrics` | `GraphController` | Graph metrics |
| `GET /api/schema` | _(SchemaController? Not found as separate file — likely in AgentController or via `schemaService`)_ | Schemas CRUD |

### Schema REST Endpoints (from `AgentController` and `AppController`)
- `GET /api/agent/schemas` — list all schemas
- `POST /api/agent/schemas` — create schema (body: `{name, description, appType?, workspaceId?}`)
- `GET /api/agent/schemas/{id}` — get single schema
- `PUT /api/agent/schemas/{id}` — update schema
- `DELETE /api/agent/schemas/{id}` — delete schema
- `POST /api/agent/schemas/{id}/execute` — execute schema (returns execution record)
- `GET /api/app` — list schemas as app models
- `POST /api/app` — create app from schema

### Schema ↔ MCP Relationship
- **No MCP tool exists for schema management** — schemas are only accessible via REST
- `WorkflowSchema` is the database entity containing graph nodes, edges, and execution config
- `SchemaService` handles CRUD with MongoDB persistence
- `ExecutionRecord` tracks execution state with mode, status, output, timestamps

### Test Coverage
- `McpIntegrationTest.java` — tests all 7 plan MCP tools end-to-end via JSON-RPC
- `SchemaControllerIntegrationTest.java` — tests schema CRUD and execution via REST

### Existing Scripts
- `scripts/dev.sh` — `start`, `stop`, `logs N`, `execute <schema_id>`
- `scripts/migrate-schemas.py` — schema migration utility
- `scripts/migrate-to-neo4j.py` — Neo4j migration
- **No api.py exists** — the referenced `scripts/api.py` from AGENTS.md is missing

## File Operations
### Read
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/AgentController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/AppController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/AuthController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/CrossCheckController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/CustomEndpointController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/EvidenceController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/EvolveController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/HarnessController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/ManifestController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/PlanController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/PluginController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/RemoteApiController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/SettingsController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/ShareController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/SkillController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/controller/TemplateController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/graph/api/GraphController.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/graph/mcp/GraphMcpTools.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/mcp/PlanMcpServer.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/mcp/PlanTools.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/test/java/com/agent/orchestrator/controller/SchemaControllerIntegrationTest.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/test/java/com/agent/orchestrator/mcp/McpIntegrationTest.java`
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/scripts/dev.sh`

### Modified
- (none)
