---
date: 2026-05-13
topic: "API Utility & Workflow Demo Scripts"
status: draft
---

# API Utility & Workflow Demo Scripts

## Problem Statement

Axolotl has a gap in its developer tooling:
- `scripts/api.py` is referenced in `AGENTS.md` as the recommended way to make API calls, but the file doesn't exist
- No script demonstrates the **full workflow cycle** (plan → create schema → execute → read results), making it hard for new developers/agents to understand the system end-to-end
- Developers resort to raw `curl` commands with manual JWT token handling for every API call

## Constraints

- **Python api.py**: must work with **only standard library** (urllib, json, os) — no pip dependencies for a utility script
- **Bash workflow.sh**: must use `curl` + `jq` — no other binary dependencies
- Both scripts must work **standalone** without the frontend
- Must handle JWT expiry gracefully (re-login on 401)
- Must not modify the backend codebase — scripts only

## Approach

### Script 1: `scripts/api.py` — Authenticated API Utility

**Purpose:** Single-command authenticated access to the full Axolotl API surface.

**Usage:**
```bash
# Login (caches token to ~/.axolotl/token)
python3 scripts/api.py login

# REST calls (auth header added automatically)
python3 scripts/api.py GET /api/schemas
python3 scripts/api.py GET "/api/plan?format=full"
python3 scripts/api.py POST /api/schemas '{"name":"test","nodes":[]}'
python3 scripts/api.py PUT "/api/plan/tasks/abc123/status" '{"status":"DONE"}'
python3 scripts/api.py DELETE "/api/plan/tasks/abc123"

# MCP calls (JSON-RPC 2.0 wrapper)
python3 scripts/api.py mcp add_task '{"title":"My Task","priority":"HIGH"}'
python3 scripts/api.py mcp read_plan '{"format":"status_summary"}'
```

**Key design decisions:**
- Token stored in `~/.axolotl/token` as JSON `{"token": "...", "expires_at": timestamp}`
- On each call, check `expires_at` — if expired or missing, auto re-login
- On 401 response, purge token and re-login once, then retry
- MCP mode wraps the call in JSON-RPC 2.0 envelope: `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<tool>","arguments":<args>}}`
- Output: pretty-printed JSON for REST, extract `.result.content[0].text` for MCP
- Error handling: non-zero exit code, error message to stderr

**Configuration via env vars:**
- `AXOLOTL_URL` — defaults to `http://localhost:8080`
- `AXOLOTL_USER` — defaults to `admin`
- `AXOLOTL_PASS` — defaults to `admin`

### Script 2: `scripts/workflow.sh` — Full Workflow Demo

**Purpose:** End-to-end demonstration of the Axolotl workflow cycle. Used for onboarding, testing, and CI pipelines.

**Usage:**
```bash
# Full demo
scripts/workflow.sh

# Skip plan creation (just schema + execute)
scripts/workflow.sh --schema-only

# Dry run (show what would be done, no API calls)
scripts/workflow.sh --dry-run
```

**Execution flow (8 steps):**

1. **Health check** — `GET /mcp` verifies backend is running (retry up to 5 times, 2s interval)
2. **Login** — `python3 scripts/api.py login` gets JWT token
3. **Create plan** — MCP `add_task` for the demo workflow title
4. **Add tasks** — MCP `add_tasks` with 3-4 subtasks for the demo
5. **Create schema** — `POST /api/schemas` with a minimal workflow (Source → Agent → Output), using a hardcoded JSON template with variable interpolation
6. **Execute schema** — `POST /api/schemas/{id}/execute?mode=EXECUTE`
7. **Poll for completion** — `GET /api/schemas/{id}/history` every 5 seconds, up to 30 attempts (150s timeout). Check `status` field for "completed"/"failed"/"cancelled"
8. **Show results** — Print execution summary: status, duration, total nodes, completed nodes, tokens used, cost

**Schema template** (inline in script, minimal viable workflow):
- Source node: static text input "Hello from workflow.sh!"
- Agent node: calls default LLM with prompt "Say 'Workflow execution successful' and nothing else"
- Output node: logs result to stdout
- Edges: Source → Agent → Output

**Error handling:**
- Each step checks exit code of previous command
- On failure, print ERROR message with context and exit
- `--dry-run` prints each step without executing
- `trap` cleanup on Ctrl+C or exit: print "Partial execution — check backend state"

## Architecture

### Data Flow

```
User/CI → workflow.sh → api.py (auth) → REST/MCP → Axolotl Backend
                         ↓
                    ~/.axolotl/token (cache)
```

### Token Lifecycle

1. `api.py login` → `POST /api/auth/login` → receive JWT → write to `~/.axolotl/token` with expiry
2. Subsequent calls → read cached token → check expiry → if valid, use; if expired, re-login
3. On 401 response → purge cache → re-login → retry once → if fails again, exit with error

## Components

### `scripts/api.py`

| Function | Purpose |
|----------|---------|
| `get_token()` | Read cached token, re-login if expired |
| `login()` | POST /api/auth/login, cache result |
| `rest_call(method, path, body)` | Make authenticated REST call |
| `mcp_call(tool, args)` | Wrap in JSON-RPC 2.0, POST /mcp |
| `main()` | CLI dispatcher |
| `print_json(data)` | Pretty-print JSON result |

### `scripts/workflow.sh`

| Section | Purpose |
|---------|---------|
| Config block | Env vars, defaults |
| Step 1: health_check | Verify backend is reachable |
| Step 2: login | Call api.py login |
| Step 3: create_plan | MCP add_task for workflow title |
| Step 4: add_tasks | MCP add_tasks for subtasks |
| Step 5: create_schema | POST /api/schemas with template |
| Step 6: execute | POST /api/schemas/{id}/execute |
| Step 7: poll | Loop GET /api/schemas/{id}/history |
| Step 8: show_results | Print execution summary |
| Cleanup trap | Handle interrupts |

## Data Flow Details

1. **workflow.sh** sets `AXOLOTL_URL`, calls `api.py login` → token cached
2. **Plan creation**: `api.py mcp add_task "..."` → JSON-RPC to `/mcp` → task created
3. **Schema creation**: `api.py POST /api/schemas {...}` → schema object returned with `id`
4. **Execution**: `api.py POST /api/schemas/{schemaId}/execute` → returns `{"status":"started","schemaId":"..."}`
5. **Polling**: `api.py GET /api/schemas/{schemaId}/history` → check `[0].status`
6. **Results**: Print execution record from history

## Error Handling Strategy

| Scenario | Handling |
|----------|----------|
| Backend down | workflow.sh retries health check 5x, then exits |
| Invalid credentials | api.py exits with "Login failed (HTTP 401)" |
| Token expired | api.py auto re-logs in, retries |
| Schema creation fails | workflow.sh prints backend error, exits |
| Execution never completes | workflow.sh poll times out after 150s, exits |
| Network error | api.py catches URLError, prints message, exits 1 |
| MCP error response | api.py extracts error from JSON-RPC response, prints it |

## Testing Strategy

- **Manual**: Run `scripts/workflow.sh` against running backend, verify all 8 steps complete
- **api.py unit tests**: (future) test `get_token()`, `login()`, `rest_call()` with mock HTTP
- **Edge cases**: Test token expiry by waiting > token lifetime, verify auto re-login
- **Failure modes**: Stop backend mid-workflow, verify error messages
- **Dry-run**: Verify `--dry-run` doesn't make any API calls

## Open Questions

1. Should `api.py` support piped input for large JSON payloads? (e.g., `echo '{"data":...}' | api.py POST /api/schemas`) — defer to implementation if useful
2. Should workflow.sh register cleanup tasks (e.g., delete test schema)? — no, test artifacts are useful for inspection
3. Should api.py support custom headers (e.g., for testing)? — no, YAGNI for now
