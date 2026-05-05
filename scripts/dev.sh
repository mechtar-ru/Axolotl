#!/usr/bin/env bash
# Axolotl dev helper — backend lifecycle, schema execution, log monitoring.
#
# Usage:
#   scripts/dev.sh start              # Compile + start backend (kills old one first)
#   scripts/dev.sh stop               # Kill backend
#   scripts/dev.sh restart            # Stop + start
#   scripts/dev.sh compile            # Compile only (no start)
#   scripts/dev.sh logs [N]           # Tail last N lines of backend log (default 50)
#   scripts/dev.sh status             # Show iteration progress from log
#   scripts/dev.sh execute [SCHEMA_ID] [MODE]  # Execute schema (default: EXECUTE)
#   scripts/dev.sh watch [SCHEMA_ID] [SECS]    # Execute + poll status every SECS (default 30)
#   scripts/dev.sh mempalace [VERSION]          # Update mempalace (default: latest npm, or GitHub tag)
#   scripts/dev.sh venv-setup                   # Create and activate Python venv for scripts/
#
# All commands auto-set Sentry skip flag.
# Backend log: /tmp/axolotl-backend.log

set -euo pipefail
cd "$(dirname "$0")/.."

PORT=8082
LOG=/tmp/axolotl-backend.log
BACKEND=backend

health() { curl -s "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q "UP" || curl -s "http://localhost:$PORT/api/agents" 2>/dev/null | grep -q "name"; }

kill_backend() {
  local pid
  pid=$(lsof -i ":$PORT" -t 2>/dev/null || true)
  if [ -n "$pid" ]; then
    kill -9 $pid 2>/dev/null || true
    sleep 1
    echo "Killed old backend (PID $pid)"
  fi
}

wait_for_health() {
  local i
  for i in $(seq 1 20); do
    sleep 5
    if [ -n "$(health)" ]; then
      echo "Backend ready on :$PORT"
      return 0
    fi
  done
  echo "ERROR: Backend failed to start. Tail $LOG"
  return 1
}

do_compile() {
  # Touch modified files to force recompile (Maven incremental checks timestamps)
  find "$BACKEND/src" -name "*.java" -newer "$BACKEND/target/classes" -exec touch {} \; 2>/dev/null || true
  mvn compiler:compile -f "$BACKEND/pom.xml" -q 2>&1 | grep -E "ERROR|BUILD" | head -5 || true
  echo "Compile done."
}

do_start() {
  kill_backend
  rm -f "$LOG"
  nohup mvn spring-boot:run -f "$BACKEND/pom.xml" \
    -Dmaven.test.skip=true -Dio.sentry.maven.skip=true \
    -Dsentry.skip=true -DSENTRY_AUTH_TOKEN=dummy \
    -q > "$LOG" 2>&1 &
  echo "Backend starting (PID $!)..."
  wait_for_health
}

do_stop() {
  kill_backend
  echo "Backend stopped."
}

do_logs() {
  local n="${1:-50}"
  tail -n "$n" "$LOG"
}

do_status() {
  grep -E "Итерация|Прогресс.*(COMPLETED|FAILED|BLOCKED)|timed out|streaming timeout|retry|Ошибка|Error:" "$LOG" 2>/dev/null | tail -30 || echo "No execution data in log."
}

do_execute() {
  local schema="${1:?Usage: execute SCHEMA_ID [MODE]}"
  local mode="${2:-EXECUTE}"
  python3 scripts/api.py POST "/api/schemas/$schema/execute" "{\"mode\":\"$mode\"}" -q
}

do_watch() {
  local schema="${1:?Usage: watch SCHEMA_ID [SECS]}"
  local interval="${2:-30}"
  echo "Executing $schema..."
  do_execute "$schema" "EXECUTE" || true
  echo "Polling every ${interval}s. Ctrl+C to stop."
  while true; do
    sleep "$interval"
    echo "--- $(date '+%H:%M:%S') ---"
    do_status
    # Stop if we see COMPLETED/FAILED/BLOCKED for out-1 or the system
    if grep -qE "Прогресс.*\[(system|out-1)\].*(COMPLETED|FAILED|BLOCKED)" "$LOG" 2>/dev/null; then
      echo "Execution finished."
      break
    fi
  done
}

do_venv_setup() {
  if [ -d ".venv" ]; then
    echo "venv exists at .venv/"
  else
    python3 -m venv .venv
    echo "Created .venv/"
  fi
  . .venv/bin/activate
  pip install -q -r scripts/requirements.txt 2>/dev/null || true
  echo "Python venv ready: $(python3 --version) at $(which python3)"
}

do_mempalace() {
  local version="${1:-}"
  local current
  current=$(python3 -m mempalace --version 2>/dev/null || echo "not installed")
  echo "Current mempalace: $current"

  # Ensure venv is active
  if [ -z "${VIRTUAL_ENV:-}" ] && [ -d ".venv" ]; then
    . .venv/bin/activate
  fi

  if [ -n "$version" ]; then
    echo "Installing mempalace==$version via pip..."
    pip install "mempalace==$version"
  else
    echo "Installing latest mempalace via pip..."
    pip install --upgrade mempalace
  fi

  local installed
  installed=$(python3 -m mempalace --version 2>/dev/null || echo "unknown")
  echo "Installed mempalace: $installed"
}

case "${1:-help}" in
  start)     do_start ;;
  stop)      do_stop ;;
  restart)   do_stop; do_start ;;
  compile)   do_compile ;;
  logs)      do_logs "${2:-50}" ;;
  status)    do_status ;;
  execute)   do_execute "${2:?Schema ID required}" "${3:-EXECUTE}" ;;
  watch)     do_watch "${2:?Schema ID required}" "${3:-30}" ;;
  venv-setup) do_venv_setup ;;
  mempalace) do_mempalace "${2:-}" ;;
  help|*)
    sed -n '2,15p' "$0" | sed 's/^# //' ;;
esac
