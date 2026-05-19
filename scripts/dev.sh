#!/bin/bash

case "$1" in
  start)
    pkill -f "spring-boot:run" 2>/dev/null
    sleep 1
    cd backend && mvn spring-boot:run -Dserver.port=8082
    ;;
  stop)
    pkill -f "spring-boot:run" 2>/dev/null
    ;;
  logs)
    N=${2:-50}
    find backend -name "*.log" -type f 2>/dev/null | head -1 | xargs tail -n "$N" 2>/dev/null || echo "No logs found"
    ;;
  execute)
    if [ -z "$2" ]; then
      echo "Usage: $0 execute <schema_id>"
      exit 1
    fi
    source scripts/token.sh 2>/dev/null
    curl -s -X POST http://localhost:8082/api/schema/execute/$2 -H "$CURL_HEADER" -H "Content-Type: application/json"
    ;;
  *)
    echo "Usage: $0 {start|stop|logs [N]|execute <id>}"
    exit 1
    ;;
esac