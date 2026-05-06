#!/bin/bash
# Sync test dirs to main dirs - after agent verifies changes
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAIN_BACKEND="$SCRIPT_DIR/../backend"
MAIN_FRONTEND="$SCRIPT_DIR/../frontend"
TEST_BACKEND="$SCRIPT_DIR/../backend-next"
TEST_FRONTEND="$SCRIPT_DIR/../frontend-next"

echo "Syncing test → main dirs..."

# Sync backend (update newer + delete non-existent)
rsync -av --delete \
    "$TEST_BACKEND/src/" "$MAIN_BACKEND/src/" \
    --exclude='target/' \
    --exclude='.mvn/' \
    --exclude='*.log'

# Sync frontend (update newer + delete non-existent)
rsync -av --delete \
    "$TEST_FRONTEND/src/" "$MAIN_FRONTEND/src/" \
    --exclude='node_modules/' \
    --exclude='dist/' \
    --exclude='dist-electron/'

echo "Done. Main dirs updated from test."
echo "Restart Axolotl to use verified changes."