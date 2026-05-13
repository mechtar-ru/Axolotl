#!/bin/bash
set -e

WORKTREE_PATH="$(git rev-parse --show-toplevel)/../Axolotl-worktree"

if [ -d "$WORKTREE_PATH" ]; then
  echo "Worktree already exists at: $WORKTREE_PATH"
  echo "$WORKTREE_PATH"
  exit 0
fi

echo "Creating worktree at: $WORKTREE_PATH"
git worktree add "$WORKTREE_PATH" HEAD

echo "Worktree created successfully."
echo "Path: $WORKTREE_PATH"
echo "$WORKTREE_PATH"
