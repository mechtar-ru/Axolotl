#!/bin/bash
# Sync main dirs to test dirs - for agent to work on changes
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAIN_BACKEND="$SCRIPT_DIR/../backend"
MAIN_FRONTEND="$SCRIPT_DIR/../frontend"
TEST_BACKEND="$SCRIPT_DIR/../backend-next"
TEST_FRONTEND="$SCRIPT_DIR/../frontend-next"

echo "Syncing main → test dirs..."

# Sync backend (update newer + delete non-existent)
rsync -av --delete \
    "$MAIN_BACKEND/src/" "$TEST_BACKEND/src/" \
    --exclude='target/' \
    --exclude='.mvn/' \
    --exclude='*.log'

# Sync frontend (update newer + delete non-existent)  
rsync -av --delete \
    "$MAIN_FRONTEND/src/" "$TEST_FRONTEND/src/" \
    --exclude='node_modules/' \
    --exclude='dist/' \
    --exclude='dist-electron/' \
    --exclude='.env'

# Copy pom.xml and configs
rsync -av "$MAIN_BACKEND/pom.xml" "$TEST_BACKEND/pom.xml"
rsync -av "$MAIN_BACKEND/.mvn/" "$TEST_BACKEND/.mvn/" 2>/dev/null || true
rsync -av "$MAIN_BACKEND/src/" "$TEST_BACKEND/src/"

rsync -av --delete \
    "$MAIN_FRONTEND/src/" "$TEST_FRONTEND/src/" \
    --exclude='node_modules/' \
    --exclude='dist/'

cp "$MAIN_FRONTEND/package.json" "$TEST_FRONTEND/package.json" 2>/dev/null || true
cp "$MAIN_FRONTEND/vite.config.ts" "$TEST_FRONTEND/vite.config.ts" 2>/dev/null || true
cp "$MAIN_FRONTEND/tsconfig.json" "$TEST_FRONTEND/tsconfig.json" 2>/dev/null || true
cp "$MAIN_FRONTEND/tailwind.config.js" "$TEST_FRONTEND/tailwind.config.js" 2>/dev/null || true
cp "$MAIN_FRONTEND/postcss.config.js" "$TEST_FRONTEND/postcss.config.js" 2>/dev/null || true
cp -r "$MAIN_FRONTEND/public" "$TEST_FRONTEND/public" 2>/dev/null || true
cp -r "$MAIN_FRONTEND/index.html" "$TEST_FRONTEND/index.html" 2>/dev/null || true

echo "Done. Test dirs synced from main."
echo "Run: cd backend-next && mvn spring-boot:run -Dserver.port=8083"
echo "Frontend on: cd frontend-next && npm run dev -- --port 5174"