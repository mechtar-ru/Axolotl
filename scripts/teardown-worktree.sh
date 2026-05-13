#!/bin/bash
set -e

WORKTREE_PATH="$(git rev-parse --show-toplevel)/../Axolotl-worktree"

if [ ! -d "$WORKTREE_PATH" ]; then
  echo "No worktree found at: $WORKTREE_PATH"
  exit 0
fi

echo "Removing worktree at: $WORKTREE_PATH"
git worktree remove "$WORKTREE_PATH" --force

echo "Worktree removed successfully."
