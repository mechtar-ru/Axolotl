# API Utility & Workflow Demo Scripts

**Goal:** Create two scripts — `scripts/api.py` (Python authenticated API utility) and `scripts/workflow.sh` (bash full workflow demo) — to enable scripted/CI access to the Axolotl backend.

**Architecture:** api.py handles JWT auth with token caching in `~/.axolotl/token`, auto-renew on expiry, and transparent 401 retry. workflow.sh uses api.py as its auth layer and orchestrates the 8-step workflow cycle (health check → login → plan → schema → execute → poll → results), running as a self-contained demo.

**Design:** `thoughts/shared/designs/2026-05-13-api-workflow-scripts-design.md`

---

## Dependency Graph

```
Batch 1 (parallel — 1 implementer): 1.1 [scripts/api.py — standalone]
Batch 2 (parallel — 1 implementer): 2.1 [scripts/workflow.sh — calls api.py at runtime]
```

---

## Batch 1: Foundation (parallel — 1 implementer)

### Task 1.1: `scripts/api.py` — Authenticated API Utility
**File:** `scripts/api.py`
**Test:** none (manual/integration testing per design spec)
**Depends:** none

Design requires a stdlib-only Python API utility with automatic JWT handling. I'm implementing it with:
- Token cached as JSON in `~/.axolotl/token` with `{token, expires_at}` fields
- Auto re-login on expiry or 401 response (one retry)
- REST mode: GET/POST/PUT/DELETE/PATCH with JSON body support
- MCP mode: wraps in JSON-RPC 2.0 envelope, extracts result content
- Env vars: `AXOLOTL_URL` (default `http://localhost:8080`), `AXOLOTL_USER` (default `admin`), `AXOLOTL_PASS` (default `admin`)
- Pretty-printed JSON output, errors to stderr, exit code 1 on failure
- SSL verification disabled (for local dev with self-signed certs)

**Decision:** Using admin/admin defaults per design. Existing `update-graph.sh` uses tech/tech, but env var override handles that. Using urllib only (no requests dependency).

```python
#!/usr/bin/env python3
"""
Axolotl Authenticated API Utility

Single-command authenticated access to the full Axolotl API surface via
REST or MCP (JSON-RPC 2.0). Handles JWT token caching and auto-renewal.

Usage:
    python3 scripts/api.py login
    python3 scripts/api.py GET /api/schemas
    python3 scripts/api.py POST /api/schemas '{"name":"test","nodes":[]}'
    python3 scripts/api.py PUT /api/plan/tasks/abc/status '{"status":"DONE"}'
    python3 scripts/api.py DELETE /api/plan/tasks/abc
    python3 scripts/api.py mcp add_task '{"title":"My Task","priority":"HIGH"}'
    python3 scripts/api.py mcp read_plan '{"format":"status_summary"}'

Environment variables:
    AXOLOTL_URL   Backend URL (default: http://localhost:8080)
    AXOLOTL_USER  Username for login (default: admin)
    AXOLOTL_PASS  Password for login (default: admin)
"""

import os
import sys
import json
import time
import ssl
import urllib.request
import urllib.error

# ─── Configuration ───────────────────────────────────────────────────

AXOLOTL_URL = os.environ.get("AXOLOTL_URL", "http://localhost:8080")
AXOLOTL_USER = os.environ.get("AXOLOTL_USER", "admin")
AXOLOTL_PASS = os.environ.get("AXOLOTL_PASS", "admin")
TOKEN_DIR = os.path.expanduser("~/.axolotl")
TOKEN_FILE = os.path.join(TOKEN_DIR, "token")

# Default token expiry: 24 hours in seconds
DEFAULT_TOKEN_TTL = 86400


# ─── Token Management ────────────────────────────────────────────────

def _ensure_token_dir() -> None:
    """Create ~/.axolotl directory if it doesn't exist."""
    os.makedirs(TOKEN_DIR, exist_ok=True)


def _read_token() -> dict | None:
    """Read cached token file, return dict or None."""
    try:
        with open(TOKEN_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def _write_token(token: str, expires_at: int) -> None:
    """Write token with expiry to cache file."""
    _ensure_token_dir()
    with open(TOKEN_FILE, "w") as f:
        json.dump({"token": token, "expires_at": expires_at}, f)


def _purge_token() -> None:
    """Remove cached token file."""
    try:
        os.remove(TOKEN_FILE)
    except FileNotFoundError:
        pass


def get_token() -> str:
    """Read cached token, re-login if expired or missing, return token string."""
    token_data = _read_token()
    if token_data:
        expires_at = token_data.get("expires_at", 0)
        if expires_at > int(time.time()):
            return token_data["token"]
    # Token missing or expired — re-login
    return login()["token"]


def login() -> dict:
    """POST /api/auth/login, cache result, return response dict."""
    url = f"{AXOLOTL_URL}/api/auth/login"
    body = json.dumps({"username": AXOLOTL_USER, "password": AXOLOTL_PASS}).encode()
    data = _request(url, data=body, method="POST", auth=False)
    token = data.get("token", "")
    if not token:
        _die("Login response did not contain a token")
    expires_at = int(time.time()) + DEFAULT_TOKEN_TTL
    # Use server-provided expiry if available
    if "expiresAt" in data:
        try:
            expires_at = int(data["expiresAt"])
        except (ValueError, TypeError):
            pass
    _write_token(token, expires_at)
    return data


# ─── HTTP Requests ───────────────────────────────────────────────────

def _ssl_context() -> ssl.SSLContext:
    """Create permissive SSL context for local development."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _request(url: str, data: bytes | None = None,
             method: str = "GET", auth: bool = True,
             retried: bool = False) -> dict:
    """
    Make HTTP request with optional Bearer auth.
    On 401: purge token, re-login, retry once.
    Returns parsed JSON dict.
    """
    headers = {"Content-Type": "application/json"}

    if auth:
        token = get_token()
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req, context=_ssl_context()) as resp:
            body = resp.read().decode()
            if body:
                return json.loads(body)
            return {}
    except urllib.error.HTTPError as e:
        error_body = e.read().decode()
        if e.code == 401 and auth and not retried:
            # Token expired — purge, re-login, retry once
            _purge_token()
            # Clear old auth header before retry
            headers.pop("Authorization", None)
            new_token = get_token()
            headers["Authorization"] = f"Bearer {new_token}"
            retry_req = urllib.request.Request(
                url, data=data, headers=headers, method=method
            )
            try:
                with urllib.request.urlopen(retry_req, context=_ssl_context()) as resp:
                    body = resp.read().decode()
                    if body:
                        return json.loads(body)
                    return {}
            except urllib.error.HTTPError as e2:
                _die(
                    f"Request failed after re-login (HTTP {e2.code}): "
                    f"{e2.read().decode()}"
                )
        elif e.code == 401:
            _die(f"Login failed (HTTP 401): {error_body}")
        else:
            _format_http_error(e.code, error_body)
    except urllib.error.URLError as e:
        _die(f"Network error: {e.reason}")


def _format_http_error(code: int, body: str) -> None:
    """Print formatted HTTP error and exit."""
    try:
        err_json = json.loads(body)
        _die(f"Request failed (HTTP {code}):\n{json.dumps(err_json, indent=2)}")
    except json.JSONDecodeError:
        _die(f"Request failed (HTTP {code}): {body}")


# ─── API Call Modes ──────────────────────────────────────────────────

def rest_call(method: str, path: str, body: dict | None = None) -> dict:
    """Make authenticated REST call to the given API path."""
    url = f"{AXOLOTL_URL}{path}"
    data = json.dumps(body).encode() if body else None
    return _request(url, data=data, method=method)


def mcp_call(tool: str, args: dict | None = None) -> dict | str:
    """
    Wrap call in JSON-RPC 2.0 envelope and POST to /mcp.
    Returns the extracted text content if available, else the result object.
    """
    url = f"{AXOLOTL_URL}/mcp"
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {
            "name": tool,
            "arguments": args or {}
        }
    }
    data = json.dumps(payload).encode()
    result = _request(url, data=data, method="POST")

    # Handle JSON-RPC error response
    if "error" in result:
        _die(f"MCP error: {json.dumps(result['error'], indent=2)}")

    # Extract text content from MCP result
    if "result" in result:
        content = result["result"].get("content", [])
        if content and isinstance(content, list) and len(content) > 0:
            first = content[0]
            if isinstance(first, dict) and "text" in first:
                return first["text"]
            return first
        return result["result"]

    return result


# ─── Output Helpers ──────────────────────────────────────────────────

def print_json(data: object) -> None:
    """Pretty-print data as JSON to stdout."""
    if isinstance(data, str):
        # If it's a JSON string, parse then pretty-print
        try:
            parsed = json.loads(data)
            print(json.dumps(parsed, indent=2, ensure_ascii=False))
        except json.JSONDecodeError:
            print(data)
    else:
        print(json.dumps(data, indent=2, ensure_ascii=False))


def _die(message: str) -> None:
    """Print error message to stderr and exit with code 1."""
    print(message, file=sys.stderr)
    sys.exit(1)


def _print_usage() -> None:
    """Print usage information to stderr."""
    prog = os.path.basename(sys.argv[0])
    print(f"Usage: python3 {prog} <command> [args...]", file=sys.stderr)
    print(file=sys.stderr)
    print("Commands:", file=sys.stderr)
    print(f"  {prog} login", file=sys.stderr)
    print(f"  {prog} GET /api/path", file=sys.stderr)
    print(f"  {prog} POST /api/path '{{\"key\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} PUT /api/path '{{\"key\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} PATCH /api/path '{{\"key\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} DELETE /api/path", file=sys.stderr)
    print(f"  {prog} mcp <tool> '{{\"arg\": \"value\"}}'", file=sys.stderr)
    print(file=sys.stderr)
    print("Environment:", file=sys.stderr)
    print(f"  AXOLOTL_URL   (default: {AXOLOTL_URL})", file=sys.stderr)
    print(f"  AXOLOTL_USER  (default: {AXOLOTL_USER})", file=sys.stderr)
    print(f"  AXOLOTL_PASS  (default: {AXOLOTL_PASS})", file=sys.stderr)


# ─── CLI Dispatcher ──────────────────────────────────────────────────

def main() -> None:
    if len(sys.argv) < 2:
        _print_usage()
        sys.exit(1)

    command = sys.argv[1]

    if command == "login":
        data = login()
        print_json(data)
        return

    if command == "mcp":
        if len(sys.argv) < 3:
            _die("Usage: api.py mcp <tool> [args_json]")
        tool = sys.argv[2]
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        result = mcp_call(tool, args)
        print_json(result)
        return

    if command in ("GET", "POST", "PUT", "PATCH", "DELETE"):
        if len(sys.argv) < 3:
            _die(f"Usage: api.py {command} /api/path [body_json]")
        path = sys.argv[2]
        body = None
        if len(sys.argv) > 3 and command in ("POST", "PUT", "PATCH"):
            body = json.loads(sys.argv[3])
        result = rest_call(command, path, body)
        print_json(result)
        return

    _die(f"Unknown command: {command}\nRun 'python3 {os.path.basename(sys.argv[0])}' without arguments for usage.")


if __name__ == "__main__":
    main()
```

**Make executable:**
```bash
chmod +x scripts/api.py
```

**Smoke test:**
```bash
python3 scripts/api.py 2>&1 | head -5
# Should print usage info (without error exit) when no backend needed
python3 scripts/api.py login 2>&1
# Will show "Network error" if backend not running (expected without backend)
```

**Commit:** `:sparkles: feat(scripts): add api.py authenticated API utility`

---

## Batch 2: Workflow Demo (parallel — 1 implementer)

### Task 2.1: `scripts/workflow.sh` — Full Workflow Demo
**File:** `scripts/workflow.sh`
**Test:** none (manual/integration testing per design spec)
**Depends:** 1.1 (calls `scripts/api.py` at runtime)

Design requires an 8-step workflow demo. I'm implementing it with:
- Uses `api.py` for all authenticated API calls (login, REST, MCP)
- Retry-based health check (5 attempts, 2s interval) — uses direct curl to `/mcp` since health check happens before login
- Schema template: Source (static text) → Agent (LLM prompt) → Output (stdout), with variables interpolated
- Polling: GET `/api/schemas/{id}/history` every 5s, up to 30 attempts (150s timeout)
- Result display: formatted execution summary with status, duration, tokens, cost
- `--schema-only` flag: skips plan/task creation
- `--dry-run` flag: prints steps without executing
- `trap` cleanup: prints message on interrupt
- JSON parsing via `python3 -c` (not jq) for consistency with api.py dependency

```bash
#!/bin/bash
# Axolotl Full Workflow Demo
#
# End-to-end demonstration of the Axolotl workflow cycle:
#   Health Check → Login → Plan → Tasks → Schema → Execute → Poll → Results
#
# Usage:
#   scripts/workflow.sh              # Full demo
#   scripts/workflow.sh --schema-only  # Skip plan creation
#   scripts/workflow.sh --dry-run      # Dry run (no API calls)
#
# Dependencies:
#   - python3 (for api.py and JSON parsing)
#   - curl    (for health check before login)
#   - scripts/api.py  (for authenticated API calls)

set -euo pipefail

# ─── Configuration ───────────────────────────────────────────────────

# Resolve script directory for reliable api.py path
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_SCRIPT="$SCRIPT_DIR/api.py"
AXOLOTL_URL="${AXOLOTL_URL:-http://localhost:8080}"

# Flags
SCHEMA_ONLY=false
DRY_RUN=false

# Parse CLI arguments
for arg in "$@"; do
    case "$arg" in
        --schema-only) SCHEMA_ONLY=true ;;
        --dry-run)     DRY_RUN=true ;;
        *)
            echo "Unknown option: $arg" >&2
            echo "Usage: $0 [--schema-only] [--dry-run]" >&2
            exit 1
            ;;
    esac
done

# Timestamp for unique schema names
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# ─── Schema Template ─────────────────────────────────────────────────
# Minimal viable workflow: Source → Agent → Output

SCHEMA_TEMPLATE='{
  "name": "Workflow Demo",
  "nodes": [
    {
      "id": "source-1",
      "type": "source",
      "label": "Input",
      "config": {
        "inputType": "static",
        "content": "Hello from Axolotl workflow demo!"
      }
    },
    {
      "id": "agent-1",
      "type": "agent",
      "label": "Demo Agent",
      "config": {
        "agentType": "assistant",
        "systemPrompt": "You are a helpful assistant that responds concisely.",
        "prompt": "Say '\''Workflow execution successful'\'' and nothing else"
      }
    },
    {
      "id": "output-1",
      "type": "output",
      "label": "Output",
      "config": {
        "outputType": "stdout"
      }
    }
  ],
  "edges": [
    {"id": "e1", "source": "source-1", "target": "agent-1", "type": "data"},
    {"id": "e2", "source": "agent-1", "target": "output-1", "type": "data"}
  ]
}'


# ─── Helper Functions ────────────────────────────────────────────────

info()  { echo "  [INFO]  $*"; }
error() { echo "  [ERROR] $*" >&2; }
die()   { error "$*"; exit 1; }

step() {
    local num=$1; shift
    echo ""
    echo "=== Step $num: $* ==="
}

# Run api.py with dry-run awareness
run_api() {
    if [ "$DRY_RUN" = true ]; then
        echo "  [DRY-RUN] python3 $API_SCRIPT $*"
        return 0
    fi
    python3 "$API_SCRIPT" "$@"
}

# Curl call for health check (before login)
run_curl() {
    if [ "$DRY_RUN" = true ]; then
        echo "  [DRY-RUN] curl -s $*"
        return 0
    fi
    curl -s "$@"
}

# JSON pretty-print via python3
pretty_json() {
    echo "$1" | python3 -m json.tool 2>/dev/null || echo "$1"
}

# Extract a field from JSON using python3 (no jq dependency)
json_extract() {
    local json="$1"
    local key="$2"
    python3 -c "
import sys, json
try:
    data = json.loads(sys.stdin.read())
    print(data.get('$key', ''))
except Exception:
    pass
" <<< "$json" 2>/dev/null || echo ""
}

# Cleanup trap handler
cleanup() {
    echo ""
    echo "=== Interrupt ==="
    echo "Partial execution — check backend state for artifacts."
    echo "Schema may have been created (check with: python3 $API_SCRIPT GET /api/schemas)"
}
trap cleanup EXIT INT TERM


# ─── Step 1: Health Check ────────────────────────────────────────────

step 1 "Health Check — verify backend is reachable"

if [ "$DRY_RUN" = true ]; then
    echo "  [DRY-RUN] Would verify $AXOLOTL_URL/mcp responds"
    echo "  [DRY-RUN] Would retry up to 5 times with 2s interval"
else
    HEALTH_ATTEMPTS=5
    HEALTH_DELAY=2
    HEALTHY=false

    for i in $(seq 1 "$HEALTH_ATTEMPTS"); do
        http_code=$(run_curl -o /dev/null -w "%{http_code}" "$AXOLOTL_URL/mcp" 2>/dev/null || echo "000")
        # /mcp returns 405 (Method Not Allowed) for GET, which means it's alive
        if [ "$http_code" = "200" ] || [ "$http_code" = "404" ] || [ "$http_code" = "405" ]; then
            HEALTHY=true
            info "Backend is reachable at $AXOLOTL_URL (HTTP $http_code)"
            break
        fi
        info "Waiting for backend at $AXOLOTL_URL... (attempt $i/$HEALTH_ATTEMPTS, HTTP $http_code)"
        sleep "$HEALTH_DELAY"
    done

    if [ "$HEALTHY" != true ]; then
        die "Backend not reachable at $AXOLOTL_URL after $HEALTH_ATTEMPTS attempts"
    fi
fi


# ─── Step 2: Login ──────────────────────────────────────────────────

step 2 "Login — authenticate and cache JWT token"

LOGIN_RESULT=$(run_api login)
if [ "$DRY_RUN" = false ]; then
    echo "$LOGIN_RESULT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    token = d.get('token', '')
    print(f'  Token obtained: {token[:20]}...{token[-10:]}' if len(token) > 30 else '  Token obtained')
except Exception as e:
    print(f'  Login result: (could not parse: {e})')
" 2>/dev/null || { error "Login failed"; exit 1; }
fi


# ─── Step 3: Create Plan ─────────────────────────────────────────────

step 3 "Create Plan — MCP add_task for workflow title"

if [ "$SCHEMA_ONLY" = true ]; then
    info "Skipping plan creation (--schema-only)"
else
    PLAN_RESULT=$(run_api mcp add_task \
        "{\"title\":\"Workflow Demo $TIMESTAMP\",\"priority\":\"HIGH\",\"description\":\"Automated workflow demo executed by workflow.sh\"}")
    if [ "$DRY_RUN" = false ]; then
        echo "  $(echo "$PLAN_RESULT" | python3 -c "
import sys
text = sys.stdin.read().strip()
print(text[:120] + '...' if len(text) > 120 else text)
" 2>/dev/null || echo "$PLAN_RESULT")"
    fi
fi


# ─── Step 4: Add Tasks ──────────────────────────────────────────────

step 4 "Add Tasks — create subtasks via MCP"

if [ "$SCHEMA_ONLY" = true ]; then
    info "Skipping task creation (--schema-only)"
else
    # Add 3 subtasks for the demo
    TASKS=(
        "Prepare input data|HIGH|Create source node with test input for the workflow"
        "Execute workflow|HIGH|Run the agent workflow and capture results"
        "Verify output|MEDIUM|Check execution results for expected response"
    )

    for task_entry in "${TASKS[@]}"; do
        IFS='|' read -r title priority desc <<< "$task_entry"
        TASK_RESULT=$(run_api mcp add_task \
            "{\"title\":\"$title\",\"priority\":\"$priority\",\"description\":\"$desc\"}")
        if [ "$DRY_RUN" = false ]; then
            # Extract task ID or title from MCP text response
            task_text=$(echo "$TASK_RESULT" | python3 -c "
import sys
text = sys.stdin.read().strip()
print(text[:100] + '...' if len(text) > 100 else text)
" 2>/dev/null || echo "$TASK_RESULT")
            echo "  Added task: $title → $task_text"
        fi
    done
fi


# ─── Step 5: Create Schema ──────────────────────────────────────────

step 5 "Create Schema — POST /api/schemas with workflow template"

SCHEMA_BODY="${SCHEMA_TEMPLATE/\"Workflow Demo\"/\"Workflow Demo $TIMESTAMP\"}"
SCHEMA_RESULT=$(run_api POST /api/schemas "$SCHEMA_BODY")

if [ "$DRY_RUN" = false ]; then
    # Pretty-print the schema creation response
    echo "$SCHEMA_RESULT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    schema_id = data.get('id', 'unknown')
    schema_name = data.get('name', 'unnamed')
    print(f'  Created schema: \"{schema_name}\" (ID: {schema_id})')
    nodes = data.get('nodes', [])
    edges = data.get('edges', [])
    print(f'  Nodes: {len(nodes)}, Edges: {len(edges)}')
except Exception as e:
    print(f'  Schema response: (could not parse: {e})')
    print(json.dumps(data, indent=2) if 'data' in dir() else '')
" 2>/dev/null || echo "$SCHEMA_RESULT" | python3 -m json.tool 2>/dev/null || echo "$SCHEMA_RESULT"
fi

# Extract schema ID for subsequent steps
SCHEMA_ID=$(json_extract "$SCHEMA_RESULT" "id")
if [ -z "$SCHEMA_ID" ] && [ "$DRY_RUN" = false ]; then
    die "Could not extract schema ID from response"
fi

if [ "$DRY_RUN" = false ]; then
    info "Using schema ID: $SCHEMA_ID"
fi


# ─── Step 6: Execute Schema ─────────────────────────────────────────

step 6 "Execute Schema — POST /api/schemas/{id}/execute"

if [ "$DRY_RUN" = false ]; then
    EXEC_RESULT=$(run_api POST "/api/schemas/$SCHEMA_ID/execute?mode=EXECUTE" "{}")
    echo "$EXEC_RESULT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    status = data.get('status', 'started')
    print(f'  Execution status: {status}')
    if 'schemaId' in data:
        print(f'  Schema ID: {data[\"schemaId\"]}')
except Exception as e:
    print(f'  Execution response: (could not parse: {e})')
" 2>/dev/null || echo "$EXEC_RESULT" | python3 -m json.tool 2>/dev/null || echo "$EXEC_RESULT"
fi


# ─── Step 7: Poll for Completion ─────────────────────────────────────

step 7 "Poll for Completion — polling /api/schemas/{id}/history"

MAX_ATTEMPTS=30
POLL_DELAY=5
COMPLETED=false
FINAL_STATUS=""

if [ "$DRY_RUN" = true ]; then
    echo "  [DRY-RUN] Would poll GET /api/schemas/$SCHEMA_ID/history every ${POLL_DELAY}s"
    echo "  [DRY-RUN] Max wait: $((MAX_ATTEMPTS * POLL_DELAY))s"
else
    for i in $(seq 1 "$MAX_ATTEMPTS"); do
        sleep "$POLL_DELAY"

        HISTORY=$(run_api GET "/api/schemas/$SCHEMA_ID/history" 2>/dev/null || true)
        if [ -z "$HISTORY" ]; then
            info "No history yet (attempt $i/$MAX_ATTEMPTS)"
            continue
        fi

        STATUS=$(python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list) and len(data) > 0:
        rec = data[0]
    elif isinstance(data, dict):
        rec = data
    else:
        rec = {}
    print(rec.get('status', ''))
except Exception:
    print('')
" <<< "$HISTORY" 2>/dev/null)

        case "$STATUS" in
            completed)
                COMPLETED=true
                FINAL_STATUS="$STATUS"
                info "Execution completed!"
                break
                ;;
            failed|cancelled)
                FINAL_STATUS="$STATUS"
                error "Execution ended with status: $STATUS"
                echo "$HISTORY" | python3 -m json.tool 2>/dev/null || echo "$HISTORY"
                exit 1
                ;;
            "")
                info "No status yet (attempt $i/$MAX_ATTEMPTS)"
                ;;
            *)
                info "Status: $STATUS (attempt $i/$MAX_ATTEMPTS)"
                ;;
        esac
    done

    if [ "$COMPLETED" != true ]; then
        die "Execution did not complete within $((MAX_ATTEMPTS * POLL_DELAY)) seconds (last status: ${FINAL_STATUS:-unknown})"
    fi
fi


# ─── Step 8: Show Results ────────────────────────────────────────────

step 8 "Show Results — execution summary"

if [ "$DRY_RUN" = true ]; then
    echo "  [DRY-RUN] Would display formatted execution summary"
else
    FINAL_HISTORY=$(run_api GET "/api/schemas/$SCHEMA_ID/history")

    echo ""
    echo "┌────────────────────────────────────────────────────────────┐"
    echo "│                    Execution Summary                        │"
    echo "├────────────────────────────────────────────────────────────┤"

    python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list) and len(data) > 0:
        r = data[0]
    elif isinstance(data, dict):
        r = data
    else:
        r = {}

    # Standard fields
    fields = [
        ('Status',      r.get('status', 'N/A')),
        ('Schema ID',   r.get('schemaId', r.get('id', 'N/A'))),
        ('Duration',    f\"{r.get('duration', 'N/A')} s\"),
        ('Total Nodes', r.get('totalNodes', r.get('totalNodes', 'N/A'))),
        ('Completed',   r.get('completedNodes', r.get('completedCount', 'N/A'))),
        ('Tokens Used', r.get('tokensUsed', r.get('tokenCount', 'N/A'))),
        ('Cost',        r.get('cost', 'N/A')),
    ]

    for label, value in fields:
        val_str = str(value) if value is not None else 'N/A'
        print(f'  │ {label:<14} {val_str}')

    # Node results if available
    outputs = r.get('outputs', r.get('nodeResults', r.get('results', [])))
    if outputs and isinstance(outputs, list) and len(outputs) > 0:
        print(f'  ├────────────────────────────────────────────────────────────┤')
        print(f'  │ Output Details')
        for node in outputs[:5]:  # Limit to 5 nodes
            if isinstance(node, dict):
                nid = node.get('nodeId', node.get('id', '?'))
                result_text = str(node.get('result', node.get('output', node.get('text', ''))))[:150]
                print(f'  │   [{nid}] {result_text}')
    elif isinstance(outputs, dict):
        print(f'  ├────────────────────────────────────────────────────────────┤')
        print(f'  │ Output Details')
        result_text = str(outputs.get('result', outputs.get('output', json.dumps(outputs))))[:200]
        print(f'  │   {result_text}')

    print(f'  └────────────────────────────────────────────────────────────┘')

except Exception as e:
    print(f'  │ Error parsing results: {e}')
    print(f'  └────────────────────────────────────────────────────────────┘')
    print()
    print(json.dumps(data, indent=2)[:500] if 'data' in dir() else '')
" <<< "$FINAL_HISTORY" 2>/dev/null || {
    echo "  (raw history output:)"
    echo "$FINAL_HISTORY" | python3 -m json.tool 2>/dev/null || echo "$FINAL_HISTORY"
    echo "  └────────────────────────────────────────────────────────────┘"
}
fi


# ─── Done ────────────────────────────────────────────────────────────

echo ""
echo "========================================="
echo "  Workflow Demo Complete"
echo "========================================="
echo "  Schema ID:  ${SCHEMA_ID:-N/A}"
echo "  Timestamp:  $TIMESTAMP"

# Remove trap on success
trap - EXIT INT TERM
exit 0
```

**Make executable:**
```bash
chmod +x scripts/workflow.sh
```

**Smoke test:**
```bash
# Dry-run mode (no API calls needed)
scripts/workflow.sh --dry-run
# Should print all 8 steps with [DRY-RUN] prefixes

# Check that it exits cleanly on dry-run
scripts/workflow.sh --dry-run
echo "Exit code: $?"  # Should be 0
```

**Full test (requires running backend):**
```bash
# Start backend first: cd backend && mvn spring-boot:run -Dserver.port=8080
# Then run:
scripts/workflow.sh
```

**Commit:** `:sparkles: feat(scripts): add workflow.sh full workflow demo`

---

## Verification Summary

| Script | Smoke Test | Full Test |
|--------|-----------|-----------|
| `scripts/api.py` | `python3 scripts/api.py` prints usage | `python3 scripts/api.py login` with running backend |
| `scripts/workflow.sh` | `scripts/workflow.sh --dry-run` prints 8 steps | `scripts/workflow.sh` with running backend |

## Post-Implementation

1. **Update `AGENTS.md`** — the `scripts/api.py` references already exist in AGENTS.md (lines 32-33, 40, 66). No changes needed since the file path matches.
2. **No `.gitignore` changes** — `~/.axolotl/token` is in user home, not repo.
3. **`scripts/requirements.txt`** — No change needed since api.py uses only stdlib.
