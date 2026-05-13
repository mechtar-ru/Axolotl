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
