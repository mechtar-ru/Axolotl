#!/bin/bash
# Update Neo4j graph with current codebase
# Usage: ./scripts/update-graph.sh [path-to-codebase]
# Example: ./scripts/update-graph.sh backend/src/main/java

set -e

CODEBASE_PATH="${1:-backend/src/main/java}"
# Convert to absolute path if relative
if [[ ! "$CODEBASE_PATH" = /* ]]; then
  CODEBASE_PATH="$(cd "$(dirname "$0")/.." && pwd)/$CODEBASE_PATH"
fi
API_URL="${API_URL:-http://localhost:8082}"

echo "Loading codebase from: $CODEBASE_PATH"

# Get auth token
TOKEN=$(curl -s -X POST "$API_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"tech","password":"tech"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])' 2>/dev/null)

if [ -z "$TOKEN" ]; then
  echo "Failed to get auth token. Is backend running on $API_URL?"
  exit 1
fi

# Load codebase into Neo4j
echo "Updating Neo4j graph..."
RESPONSE=$(curl -s -X POST "$API_URL/api/graph/load" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"path\":\"$CODEBASE_PATH\"}")

echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
echo ""
echo "Graph update complete. Use: curl $API_URL/api/graph/stats for statistics."
