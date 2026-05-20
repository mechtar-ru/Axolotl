#!/bin/bash
# Axolotl Full Workflow Demo
#
# End-to-end demonstration of the Axolotl workflow cycle:
#   Health Check -> Login -> Plan -> Tasks -> Schema -> Execute -> Poll -> Results
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

# ─── Configuration ───────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_SCRIPT="$SCRIPT_DIR/api.py"
AXOLOTL_URL="${AXOLOTL_URL:-http://localhost:8082}"

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

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
SCHEMA_ID=""

# ─── Schema Template ─────────────────────────────────────────────
# Matches current NodeData schema:
#   nodes[].data.model, data.systemPrompt, data.enabledTools
#   source node: config.sourceType
#   agent node:  config.tools, config.outputMode
#   output node: config.outputType

SCHEMA_TEMPLATE='{
  "name": "Workflow Demo",
  "nodes": [
    {
      "id": "source-1",
      "type": "source",
      "data": {
        "label": "Input",
        "model": "",
        "config": {
          "sourceType": "text",
          "content": "Hello from Axolotl workflow demo!"
        }
      }
    },
    {
      "id": "agent-1",
      "type": "agent",
      "data": {
        "label": "Demo Agent",
        "model": "big-pickle",
        "systemPrompt": "You are a helpful assistant that responds concisely.",
        "config": {
          "prompt": "Say: Workflow execution successful and nothing else",
          "tools": []
        }
      }
    },
    {
      "id": "output-1",
      "type": "output",
      "data": {
        "label": "Output",
        "config": {
          "outputType": "stdout"
        }
      }
    }
  ],
  "edges": [
    {"id": "e1", "source": "source-1", "target": "agent-1"},
    {"id": "e2", "source": "agent-1", "target": "output-1"}
  ]
}'

# ─── Cleanup ─────────────────────────────────────────────────────

SCHEMAS_CREATED=()

cleanup() {
    local exit_code=$?
    echo ""
    if [ ${#SCHEMAS_CREATED[@]} -gt 0 ] && [ "$DRY_RUN" = false ]; then
        echo "=== Cleanup ==="
        for sid in "${SCHEMAS_CREATED[@]}"; do
            if [ -n "$sid" ]; then
                echo "  Deleting schema $sid..."
                python3 "$API_SCRIPT" DELETE "/api/schemas/$sid" 2>/dev/null || true
            fi
        done
    fi
    if [ $exit_code -ne 0 ]; then
        echo ""
        echo "=== Interrupt ==="
        echo "Partial execution — check backend state for artifacts."
    fi
}
trap cleanup EXIT INT TERM

# ─── Helper Functions ────────────────────────────────────────────

info()  { echo "  [INFO]  $*"; }
error() { echo "  [ERROR] $*" >&2; }
die()   { error "$*"; exit 1; }

step() {
    local num=$1; shift
    echo ""
    echo "=== Step $num: $* ==="
}

run_api() {
    if [ "$DRY_RUN" = true ]; then
        echo "  [DRY-RUN] python3 $API_SCRIPT $*" >&2
        echo '{"dry_run": true}'
        return 0
    fi
    python3 "$API_SCRIPT" "$@"
}

run_curl() {
    if [ "$DRY_RUN" = true ]; then
        echo "  [DRY-RUN] curl -s $*"
        return 0
    fi
    curl -s "$@"
}

pretty_json() {
    echo "$1" | python3 -m json.tool 2>/dev/null || echo "$1"
}

json_extract() {
    local json="$1"
    local key="$2"
    python3 -c "
import sys, json
try:
    data = json.loads('''$json''')
    print(data.get('$key', ''))
except Exception:
    pass
"
}


# ─── Step 1: Health Check ────────────────────────────────────────

step 1 "Health Check — verify backend is reachable"

HEALTH_ATTEMPTS=5
HEALTH_DELAY=2
HEALTHY=false

for i in $(seq 1 "$HEALTH_ATTEMPTS"); do
    http_code=$(run_curl -o /dev/null -w "%{http_code}" "$AXOLOTL_URL/api/schemas" 2>/dev/null || echo "000")
    if [ "$http_code" = "200" ] || [ "$http_code" = "401" ]; then
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


# ─── Step 2: Login ───────────────────────────────────────────────

step 2 "Login — authenticate and cache JWT token"

LOGIN_RESULT=$(run_api login)
if [ "$DRY_RUN" = false ]; then
    echo "$LOGIN_RESULT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    token = d.get('token', '')
    if len(token) > 30:
        print(f'  Token obtained: {token[:20]}...{token[-10:]}')
    else:
        print(f'  Token obtained')
except Exception as e:
    print(f'  Login result: (could not parse: {e})')
" 2>/dev/null || { error "Login failed"; exit 1; }
fi


# ─── Step 3: Create Plan ─────────────────────────────────────────

step 3 "Create Plan — MCP add_task for workflow title"

if [ "$SCHEMA_ONLY" = true ]; then
    info "Skipping plan creation (--schema-only)"
else
    PLAN_RESULT=$(run_api mcp add_task \
        "{\"title\":\"Workflow Demo $TIMESTAMP\",\"priority\":\"HIGH\",\"description\":\"Automated workflow demo executed by workflow.sh\"}")
    if [ "$DRY_RUN" = false ]; then
        echo "$PLAN_RESULT" | python3 -c "
import sys
text = sys.stdin.read().strip()
print(text[:120] + '...' if len(text) > 120 else text)
" 2>/dev/null || echo "  $(echo "$PLAN_RESULT" | head -c 120)"
    fi
fi


# ─── Step 4: Add Tasks ───────────────────────────────────────────

step 4 "Add Tasks — create subtasks via MCP"

if [ "$SCHEMA_ONLY" = true ]; then
    info "Skipping task creation (--schema-only)"
else
    TASK_TITLES=(
        "Prepare input data"
        "Execute workflow"
        "Verify output"
    )
    TASK_PRIORITIES=("HIGH" "HIGH" "MEDIUM")
    TASK_DESCS=(
        "Create source node with test input for the workflow"
        "Run the agent workflow and capture results"
        "Check execution results for expected response"
    )

    for idx in "${!TASK_TITLES[@]}"; do
        title="${TASK_TITLES[$idx]}"
        priority="${TASK_PRIORITIES[$idx]}"
        desc="${TASK_DESCS[$idx]}"
        TASK_RESULT=$(run_api mcp add_task \
            "{\"title\":\"$title\",\"priority\":\"$priority\",\"description\":\"$desc\"}")
        if [ "$DRY_RUN" = false ]; then
            task_text=$(echo "$TASK_RESULT" | python3 -c "
import sys
text = sys.stdin.read().strip()
print(text[:100] + '...' if len(text) > 100 else text)
" 2>/dev/null || echo "$TASK_RESULT")
            echo "  Added task: $title → $task_text"
        fi
    done
fi


# ─── Step 5: Create Schema ───────────────────────────────────────

step 5 "Create Schema — POST /api/schemas with workflow template"

SCHEMA_BODY="${SCHEMA_TEMPLATE/\"Workflow Demo\"/\"Workflow Demo $TIMESTAMP\"}"
SCHEMA_RESULT=$(run_api POST /api/schemas "$SCHEMA_BODY")

if [ "$DRY_RUN" = false ]; then
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
" 2>/dev/null || echo "$SCHEMA_RESULT" | python3 -m json.tool 2>/dev/null || echo "$SCHEMA_RESULT"
fi

SCHEMA_ID=$(json_extract "$SCHEMA_RESULT" "id")
if [ -z "$SCHEMA_ID" ] && [ "$DRY_RUN" = false ]; then
    die "Could not extract schema ID from response"
fi
SCHEMAS_CREATED+=("$SCHEMA_ID")

if [ "$DRY_RUN" = false ]; then
    info "Using schema ID: $SCHEMA_ID"
fi


# ─── Step 6: Execute Schema ──────────────────────────────────────

step 6 "Execute Schema — POST /api/schemas/{id}/execute"

EXEC_RESULT=$(run_api POST "/api/schemas/$SCHEMA_ID/execute" "{}")
if [ "$DRY_RUN" = false ]; then
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


# ─── Step 7: Poll for Completion ─────────────────────────────────

step 7 "Poll for Completion — polling /api/schemas/{id}/runs"

MAX_ATTEMPTS=30
POLL_DELAY=5
COMPLETED=false
FINAL_STATUS=""
RUN_ID=""

if [ "$DRY_RUN" = true ]; then
    echo "  [DRY-RUN] Would poll GET /api/schemas/$SCHEMA_ID/runs every ${POLL_DELAY}s"
    echo "  [DRY-RUN] Max wait: $((MAX_ATTEMPTS * POLL_DELAY))s"
else
    for i in $(seq 1 "$MAX_ATTEMPTS"); do
        sleep "$POLL_DELAY"

        RUNS=$(run_api GET "/api/schemas/$SCHEMA_ID/runs" 2>/dev/null || true)
        if [ -z "$RUNS" ] || [ "$RUNS" = "[]" ]; then
            info "No runs yet (attempt $i/$MAX_ATTEMPTS)"
            continue
        fi

        # Get the most recent run
        RUN_ID=$(python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list) and len(data) > 0:
        # Sort by startedAt descending, take first
        sorted_runs = sorted(data, key=lambda r: r.get('startedAt', ''), reverse=True)
        print(sorted_runs[0].get('id', ''))
    else:
        print('')
except Exception:
    print('')
" <<< "$RUNS" 2>/dev/null)

        if [ -z "$RUN_ID" ]; then
            info "No run ID found (attempt $i/$MAX_ATTEMPTS)"
            continue
        fi

        STATUS=$(python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list) and len(data) > 0:
        sorted_runs = sorted(data, key=lambda r: r.get('startedAt', ''), reverse=True)
        r = sorted_runs[0]
    else:
        r = data
    print(r.get('status', ''))
except Exception:
    print('')
" <<< "$RUNS" 2>/dev/null)

        case "$STATUS" in
            completed)
                COMPLETED=true
                FINAL_STATUS="$STATUS"
                info "Execution completed! (run: $RUN_ID)"
                break
                ;;
            failed|cancelled)
                FINAL_STATUS="$STATUS"
                error "Execution ended with status: $STATUS (run: $RUN_ID)"
                # Show error details
                RUN_ERROR=$(python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list) and len(data) > 0:
        sorted_runs = sorted(data, key=lambda r: r.get('startedAt', ''), reverse=True)
        r = sorted_runs[0]
        print(r.get('error', '')[:300])
    else:
        print('')
except Exception:
    print('')
" <<< "$RUNS" 2>/dev/null)
                [ -n "$RUN_ERROR" ] && error "Error: $RUN_ERROR"
                exit 1
                ;;
            paused)
                info "Execution paused (awaiting human approval, run: $RUN_ID)"
                FINAL_STATUS="$STATUS"
                break
                ;;
            "")
                info "No status yet (attempt $i/$MAX_ATTEMPTS)"
                ;;
            *)
                info "Status: $STATUS (attempt $i/$MAX_ATTEMPTS, run: $RUN_ID)"
                ;;
        esac
    done

    if [ "$COMPLETED" != true ] && [ "$FINAL_STATUS" != "paused" ]; then
        die "Execution did not complete within $((MAX_ATTEMPTS * POLL_DELAY)) seconds (last status: ${FINAL_STATUS:-unknown})"
    fi
fi


# ─── Step 8: Show Results ────────────────────────────────────────

step 8 "Show Results — node execution results"

if [ "$DRY_RUN" = true ]; then
    echo "  [DRY-RUN] Would display node execution results"
else
    NODES=$(run_api GET "/api/schemas/$SCHEMA_ID/runs/$RUN_ID/nodes" 2>/dev/null || echo "[]")

    echo ""
    echo "+----------------------------------------------------------+"
    echo "|                 Execution Summary                         |"
    echo "+----------------------------------------------------------+"

    python3 -c "
import sys, json
try:
    nodes = json.load(sys.stdin) if sys.stdin.read(0) is None else []
except:
    nodes = []
" 2>/dev/null || true

    python3 -c "
import sys, json
try:
    data = json.loads('''$NODES''')
    if isinstance(data, list) and len(data) > 0:
        print(f'  |  {len(data)} node(s) executed')
        print(f'  +----------------------------------------------------------+')
        for n in data:
            nid = n.get('nodeId', '?')
            ntype = n.get('nodeType', '?')
            status = n.get('status', '?')
            tokens = n.get('tokensUsed', 0)
            duration = n.get('durationMs', 0)
            error = n.get('error', '')
            out = n.get('outputSummary', '')
            print(f'  |  Node:  {nid}')
            print(f'  |  Type:  {ntype}')
            print(f'  |  Status: {status}  |  Tokens: {tokens}  |  Duration: {duration}ms')
            if error:
                print(f'  |  ERROR: {error[:200]}')
            if out:
                preview = str(out)[:150].replace(chr(10), ' ')
                print(f'  |  Output: {preview}')
            print(f'  +----------------------------------------------------------+')
    else:
        print(f'  |  No node results found for run $RUN_ID')
        print(f'  +----------------------------------------------------------+')
except Exception as e:
    print(f'  | Error parsing results: {e}')
    print(f'  +----------------------------------------------------------+')
"

fi


# ─── Done ─────────────────────────────────────────────────────────

echo ""
echo "========================================="
echo "  Workflow Demo Complete"
echo "========================================="
echo "  Schema ID:  ${SCHEMA_ID:-N/A}"
echo "  Run ID:     ${RUN_ID:-N/A}"
echo "  Timestamp:  $TIMESTAMP"

# Remove trap on success
trap - EXIT INT TERM
exit 0
