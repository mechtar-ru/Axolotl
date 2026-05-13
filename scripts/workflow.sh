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

# --- Configuration ------------------------------------------------

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

# --- Schema Template ---------------------------------------------
# Minimal viable workflow: Source -> Agent -> Output

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
        "prompt": "Say: Workflow execution successful and nothing else"
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


# --- Helper Functions ---------------------------------------------

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
        echo "  [DRY-RUN] python3 $API_SCRIPT $*" >&2
        echo '{"dry_run": true}'
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
    echo "Partial execution -- check backend state for artifacts."
    echo "Schema may have been created (check with: python3 $API_SCRIPT GET /api/schemas)"
}
trap cleanup EXIT INT TERM


# --- Step 1: Health Check -----------------------------------------

step 1 "Health Check -- verify backend is reachable"

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


# --- Step 2: Login ------------------------------------------------

step 2 "Login -- authenticate and cache JWT token"

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


# --- Step 3: Create Plan ------------------------------------------

step 3 "Create Plan -- MCP add_task for workflow title"

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


# --- Step 4: Add Tasks --------------------------------------------

step 4 "Add Tasks -- create subtasks via MCP"

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
            task_text=$(echo "$TASK_RESULT" | python3 -c "
import sys
text = sys.stdin.read().strip()
print(text[:100] + '...' if len(text) > 100 else text)
" 2>/dev/null || echo "$TASK_RESULT")
            echo "  Added task: $title -> $task_text"
        fi
    done
fi


# --- Step 5: Create Schema ----------------------------------------

step 5 "Create Schema -- POST /api/schemas with workflow template"

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


# --- Step 6: Execute Schema ---------------------------------------

step 6 "Execute Schema -- POST /api/schemas/{id}/execute"

EXEC_RESULT=$(run_api POST "/api/schemas/$SCHEMA_ID/execute?mode=EXECUTE" "{}")
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


# --- Step 7: Poll for Completion ----------------------------------

step 7 "Poll for Completion -- polling /api/schemas/{id}/history"

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


# --- Step 8: Show Results -----------------------------------------

step 8 "Show Results -- execution summary"

if [ "$DRY_RUN" = true ]; then
    echo "  [DRY-RUN] Would display formatted execution summary"
else
    FINAL_HISTORY=$(run_api GET "/api/schemas/$SCHEMA_ID/history")

    echo ""
    echo "+----------------------------------------------------+"
    echo "|                 Execution Summary                    |"
    echo "+----------------------------------------------------+"

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
        print(f'  | {label:<14} {val_str}')

    outputs = r.get('outputs', r.get('nodeResults', r.get('results', [])))
    if outputs and isinstance(outputs, list) and len(outputs) > 0:
        print(f'  +----------------------------------------------------+')
        print(f'  | Output Details')
        for node in outputs[:5]:
            if isinstance(node, dict):
                nid = node.get('nodeId', node.get('id', '?'))
                result_text = str(node.get('result', node.get('output', node.get('text', ''))))[:150]
                print(f'  |   [{nid}] {result_text}')
    elif isinstance(outputs, dict):
        print(f'  +----------------------------------------------------+')
        print(f'  | Output Details')
        result_text = str(outputs.get('result', outputs.get('output', json.dumps(outputs))))[:200]
        print(f'  |   {result_text}')

    print(f'  +----------------------------------------------------+')

except Exception as e:
    print(f'  | Error parsing results: {e}')
    print(f'  +----------------------------------------------------+')
    print()
    print(json.dumps(data, indent=2)[:500] if 'data' in dir() else '')
" <<< "$FINAL_HISTORY" 2>/dev/null || {
    echo "  (raw history output:)"
    echo "$FINAL_HISTORY" | python3 -m json.tool 2>/dev/null || echo "$FINAL_HISTORY"
    echo "  +----------------------------------------------------+"
}
fi


# --- Done ---------------------------------------------------------

echo ""
echo "========================================="
echo "  Workflow Demo Complete"
echo "========================================="
echo "  Schema ID:  ${SCHEMA_ID:-N/A}"
echo "  Timestamp:  $TIMESTAMP"

# Remove trap on success
trap - EXIT INT TERM
exit 0
