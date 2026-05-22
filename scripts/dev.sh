#!/bin/bash
# Axolotl Development Script
#
# Usage:
#   scripts/dev.sh start          # Compile + start backend in background
#   scripts/dev.sh start-fg       # Compile + start backend in foreground
#   scripts/dev.sh stop           # Kill backend process
#   scripts/dev.sh logs [N]       # Tail last N lines
#   scripts/dev.sh execute ID     # Execute schema via API
#   scripts/dev.sh frontend       # Start frontend dev server in background
#
# ────────────────────────────────────────────────────────────────────────

set -euo pipefail

case "$1" in
  start)
    pkill -f "spring-boot:run" 2>/dev/null || true
    sleep 1
    echo "Starting backend on :8082..."
    cd "$(dirname "$0")/../backend" && nohup mvn spring-boot:run -Dserver.port=8082 > /tmp/axolotl-backend.log 2>&1 &
    echo "PID: $!"
    echo "Logs: tail -f /tmp/axolotl-backend.log"
    ;;
  start-fg)
    pkill -f "spring-boot:run" 2>/dev/null || true
    sleep 1
    cd "$(dirname "$0")/../backend" && mvn spring-boot:run -Dserver.port=8082
    ;;
  stop)
    pkill -f "spring-boot:run" 2>/dev/null && echo "Backend stopped" || echo "No backend process found"
    ;;
  logs)
    N=${2:-50}
    log_file="/tmp/axolotl-backend.log"
    if [[ -f "$log_file" ]]; then
      tail -n "$N" "$log_file"
    else
      echo "No log file found at $log_file"
      echo "Start backend first with: $0 start"
    fi
    ;;
  execute)
    if [ -z "${2:-}" ]; then
      echo "Usage: $0 execute <schema_id>"
      exit 1
    fi
    source "$(dirname "$0")/token.sh" 2>/dev/null || true
    curl -s -X POST "http://localhost:8082/api/schemas/$2/execute" \
      -H "$CURL_HEADER" \
      -H "Content-Type: application/json" | python3 -m json.tool
    ;;
  frontend)
    echo "Starting frontend on :5173..."
    cd "$(dirname "$0")/../frontend" && nohup npx vite --port 5173 > /tmp/axolotl-frontend.log 2>&1 &
    echo "PID: $!"
    echo "Logs: tail -f /tmp/axolotl-frontend.log"
    ;;
  *)
    echo "Usage: $(basename "$0") {start|start-fg|stop|logs [N]|execute <id>|frontend}"
    exit 1
    ;;
esac
