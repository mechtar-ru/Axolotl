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

    # Schema execution helpers
    python3 scripts/api.py execute <schema-id>              # Run schema
    python3 scripts/api.py wait <schema-id>                 # Poll until latest run completes
    python3 scripts/api.py results <schema-id> [run-id]     # Show node execution results
    python3 scripts/api.py nodes <schema-id>                # Show node statuses with models
    python3 scripts/api.py add-task "Title" "Description"   # Quick add plan task

    # Pipeline helpers
    python3 scripts/api.py pipeline-build <schema-id>       # Build pipeline nodes from stages
    python3 scripts/api.py pipeline-execute <schema-id>     # Execute pipeline
    python3 scripts/api.py pipeline-status <schema-id>      # Get pipeline status
    python3 scripts/api.py pipeline-retry <schema-id>       # Retry from last failure
    python3 scripts/api.py pipeline-cancel <schema-id>      # Cancel running pipeline
    python3 scripts/api.py pipeline-default <schema-id>     # Create default 5-stage pipeline

    # JSON body supports @file.json syntax (read from file)
    # Example: python3 scripts/api.py POST /api/schemas @schema.json

Environment variables:
    AXOLOTL_URL   Backend URL (default: http://localhost:8082)
    AXOLOTL_USER  Username for login (default: tech)
    AXOLOTL_PASS  Password for login (default: tech)
"""

import os
import sys
import json
import time
import ssl
import urllib.request
import urllib.error

# ─── Configuration ───────────────────────────────────────────────────

AXOLOTL_URL = os.environ.get("AXOLOTL_URL", "http://localhost:8082")
AXOLOTL_USER = os.environ.get("AXOLOTL_USER", "tech")
AXOLOTL_PASS = os.environ.get("AXOLOTL_PASS", "tech")
TOKEN_DIR = os.path.expanduser("~/.axolotl")
TOKEN_FILE = os.path.join(TOKEN_DIR, "token")

# Default token expiry: 24 hours in seconds
DEFAULT_TOKEN_TTL = 86400

# HTTP request timeout in seconds
REQUEST_TIMEOUT = 30


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


def login(force: bool = False) -> dict:
    """POST /api/auth/login, cache result, return response dict.
    If force=False and a valid cached token exists, returns the cached data."""
    if not force:
        cached = _read_token()
        if cached and cached.get("expires_at", 0) > int(time.time()):
            return {"token": cached["token"], "cached": True}
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
    """Create SSL context. Permissive only for localhost URLs."""
    ctx = ssl.create_default_context()
    if "localhost" in AXOLOTL_URL or "127.0.0.1" in AXOLOTL_URL:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _request(url: str, data: bytes | None = None,
             method: str = "GET", auth: bool = True,
             retried: bool = False, timeout: int = REQUEST_TIMEOUT) -> dict:
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
        with urllib.request.urlopen(req, context=_ssl_context(), timeout=timeout) as resp:
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
                with urllib.request.urlopen(retry_req, context=_ssl_context(), timeout=timeout) as resp:
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
    except TimeoutError:
        _die(f"Request timed out after {timeout}s: {url}")


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
    Returns the extracted text content if available, else full result dict.
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

    # Extract content from MCP result
    if "result" in result:
        content = result["result"].get("content", [])
        if not content:
            return result["result"]
        # Collect all text items
        texts = []
        for item in content if isinstance(content, list) else [content]:
            if isinstance(item, dict):
                if "text" in item:
                    texts.append(item["text"])
                else:
                    texts.append(json.dumps(item, indent=2))
            else:
                texts.append(str(item))
        return "\n".join(texts) if texts else result["result"]

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


def _parse_json_arg(raw: str) -> dict:
    """Parse a JSON string argument, supporting @file syntax."""
    if raw.startswith("@"):
        path = raw[1:]
        try:
            with open(path) as f:
                return json.load(f)
        except FileNotFoundError:
            _die(f"File not found: {path}")
        except json.JSONDecodeError as e:
            _die(f"Invalid JSON in {path}: {e}")
    try:
        return json.loads(raw)
    except json.JSONDecodeError as e:
        _die(f"Invalid JSON argument: {e}\nUse @file.json for file-based JSON input.")


def _print_usage() -> None:
    """Print usage information to stderr."""
    prog = os.path.basename(sys.argv[0])
    print(f"Usage: python3 {prog} <command> [args...]", file=sys.stderr)
    print(file=sys.stderr)
    print("Commands:", file=sys.stderr)
    print(f"  {prog} login", file=sys.stderr)
    print(f"  {prog} execute <schema-id>", file=sys.stderr)
    print(f"  {prog} wait <schema-id>", file=sys.stderr)
    print(f"  {prog} results <schema-id> [run-id]", file=sys.stderr)
    print(f"  {prog} nodes <schema-id>", file=sys.stderr)
    print(f"  {prog} add-task \"Title\" [\"Description\"]", file=sys.stderr)
    print(f"  {prog} GET /api/path", file=sys.stderr)
    print(f"  {prog} POST /api/path '{{\"key\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} POST /api/path @body.json", file=sys.stderr)
    print(f"  {prog} PUT /api/path '{{\"key\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} PATCH /api/path '{{\"key\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} DELETE /api/path", file=sys.stderr)
    print(f"  {prog} mcp <tool> '{{\"arg\": \"value\"}}'", file=sys.stderr)
    print(f"  {prog} mcp <tool> @args.json", file=sys.stderr)
    print(file=sys.stderr)
    print("Environment:", file=sys.stderr)
    print(f"  AXOLOTL_URL   (default: {AXOLOTL_URL})", file=sys.stderr)
    print(f"  AXOLOTL_USER  (default: {AXOLOTL_USER})", file=sys.stderr)
    print(f"  AXOLOTL_PASS  (default: {AXOLOTL_PASS})", file=sys.stderr)


# ─── Schema Execution Helpers ────────────────────────────────────────

def _latest_run(schema_id: str) -> dict | None:
    """Fetch runs for a schema, return the most recent one by startedAt."""
    runs = rest_call("GET", f"/api/schemas/{schema_id}/runs")
    if not runs:
        return None
    return max(runs, key=lambda r: r.get("startedAt", ""))


def _node_results(schema_id: str, run_id: str | None = None) -> list:
    """Fetch node execution records for a schema run."""
    if not run_id:
        run = _latest_run(schema_id)
        if not run:
            return []
        run_id = run["id"]
    nodes = rest_call("GET", f"/api/schemas/{schema_id}/runs/{run_id}/nodes")
    return nodes if nodes else []


# ─── CLI Dispatcher ──────────────────────────────────────────────────

def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "-help"):
        _print_usage()
        sys.exit(0 if len(sys.argv) >= 2 and sys.argv[1] in ("-h", "--help", "-help") else 1)

    command = sys.argv[1]

    if command == "login":
        force = len(sys.argv) > 2 and sys.argv[2] == "--force"
        data = login(force=force)
        print_json(data)
        return

    if command == "mcp":
        if len(sys.argv) < 3:
            _die("Usage: api.py mcp <tool> [args_json]")
        tool = sys.argv[2]
        args = _parse_json_arg(sys.argv[3]) if len(sys.argv) > 3 else {}
        result = mcp_call(tool, args)
        print_json(result)
        return

    if command == "execute":
        if len(sys.argv) < 3:
            _die("Usage: api.py execute <schema-id>")
        schema_id = sys.argv[2]
        result = rest_call("POST", f"/api/schemas/{schema_id}/execute")
        print_json(result)
        return

    if command == "wait":
        if len(sys.argv) < 3:
            _die("Usage: api.py wait <schema-id>")
        schema_id = sys.argv[2]
        print("Waiting for execution to complete...", file=sys.stderr)
        no_run_retries = 6  # ~30s grace window for async execution to start
        for _ in range(120):
            run = _latest_run(schema_id)
            if not run:
                if no_run_retries > 0:
                    no_run_retries -= 1
                    print(".", end="", flush=True, file=sys.stderr)
                    time.sleep(5)
                    continue
                print("\nNo runs found for this schema (execution may not have started).", file=sys.stderr)
                return
            status = run.get("status", "")
            if status in ("completed", "failed", "paused"):
                print(file=sys.stderr)
                print_json(run)
                return
            print(".", end="", flush=True, file=sys.stderr)
            time.sleep(5)
        print("\nTimed out waiting for execution.", file=sys.stderr)
        return

    if command == "results":
        if len(sys.argv) < 3:
            _die("Usage: api.py results <schema-id> [run-id]")
        schema_id = sys.argv[2]
        run_id = sys.argv[3] if len(sys.argv) > 3 else None
        nodes = _node_results(schema_id, run_id)
        if not nodes:
            print("No node results found.", file=sys.stderr)
            return
        for n in nodes:
            nid = n.get("nodeId", "?")
            ntype = n.get("nodeType", "?")
            status = n.get("status", "?")
            tokens = n.get("tokensUsed", 0)
            error = n.get("error", "")
            out = n.get("outputSummary", "")
            print(f"── {nid:30s} {ntype:12s} status={status:10s} tokens={tokens}")
            if error:
                truncated = len(error) > 200
                print(f"   ERROR: {error[:200]}{'…[truncated]' if truncated else ''}")
            if out:
                truncated = len(out) > 150
                preview = out[:150].replace("\n", "\\n")
                print(f"   output: {preview}{'…[truncated]' if truncated else ''}")
        return

    if command == "nodes":
        if len(sys.argv) < 3:
            _die("Usage: api.py nodes <schema-id>")
        schema_id = sys.argv[2]
        schema = rest_call("GET", f"/api/schemas/{schema_id}")
        for n in schema.get("nodes", []):
            d = n.get("data", {})
            model = d.get("model", "-")
            tools = d.get("enabledTools", []) or []
            tools_str = ",".join(tools) if tools else "-"
            print(f"  {n['id']:30s} [{n['type']:12s}] model={model:20s} tools={tools_str}")
        return

    if command == "add-task":
        if len(sys.argv) < 3:
            _die("Usage: api.py add-task \"Title\" [\"Description\"]")
        title = sys.argv[2]
        description = sys.argv[3] if len(sys.argv) > 3 else ""
        result = mcp_call("add_task", {"title": title, "description": description})
        print_json(result)
        return

    if command == "pipeline-build":
        if len(sys.argv) < 3:
            _die("Usage: api.py pipeline-build <schema-id>")
        schema_id = sys.argv[2]
        result = rest_call("POST", f"/api/schemas/{schema_id}/pipeline/build")
        print_json(result)
        return

    if command == "pipeline-execute":
        if len(sys.argv) < 3:
            _die("Usage: api.py pipeline-execute <schema-id>")
        schema_id = sys.argv[2]
        result = rest_call("POST", f"/api/schemas/{schema_id}/pipeline/execute")
        print_json(result)
        return

    if command == "pipeline-status":
        if len(sys.argv) < 3:
            _die("Usage: api.py pipeline-status <schema-id>")
        schema_id = sys.argv[2]
        result = rest_call("GET", f"/api/schemas/{schema_id}/pipeline/status")
        print_json(result)
        return

    if command == "pipeline-retry":
        if len(sys.argv) < 3:
            _die("Usage: api.py pipeline-retry <schema-id>")
        schema_id = sys.argv[2]
        result = rest_call("POST", f"/api/schemas/{schema_id}/pipeline/retry")
        print_json(result)
        return

    if command == "pipeline-cancel":
        if len(sys.argv) < 3:
            _die("Usage: api.py pipeline-cancel <schema-id>")
        schema_id = sys.argv[2]
        result = rest_call("POST", f"/api/schemas/{schema_id}/pipeline/cancel")
        print_json(result)
        return

    if command == "pipeline-default":
        if len(sys.argv) < 3:
            _die("Usage: api.py pipeline-default <schema-id> [appType] [description] [tddEnabled]")
        schema_id = sys.argv[2]
        app_type = sys.argv[3] if len(sys.argv) > 3 else "application"
        description = sys.argv[4] if len(sys.argv) > 4 else ""
        tdd = sys.argv[5].lower() == "true" if len(sys.argv) > 5 else False
        result = rest_call("POST", f"/api/schemas/{schema_id}/pipeline/default",
                           {"appType": app_type, "description": description, "tddEnabled": tdd})
        print_json(result)
        return

    if command in ("GET", "POST", "PUT", "PATCH", "DELETE"):
        if len(sys.argv) < 3:
            _die(f"Usage: api.py {command} /api/path [body_json]")
        path = sys.argv[2]
        body = None
        if len(sys.argv) > 3 and command in ("POST", "PUT", "PATCH"):
            body = _parse_json_arg(sys.argv[3])
        result = rest_call(command, path, body)
        print_json(result)
        return

    _die(f"Unknown command: {command}\nRun 'python3 {os.path.basename(sys.argv[0])}' without arguments for usage.")


if __name__ == "__main__":
    main()
