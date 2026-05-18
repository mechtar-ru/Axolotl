#!/bin/bash
# Kill any existing conflicting sessions
tmux kill-session -t opencode-real 2>/dev/null

# Start fresh tmux session - it will load .zshrc which is fine
tmux new-session -d -s opencode-real -c /Users/evgenijtihomirov/git/Axolotl/Axolotl

# Wait for shell to initialize
sleep 1

# Run opencode - it should work fine now
tmux send-keys -t opencode-real "opencode" Enter

# Wait for opencode to start
sleep 3

# Verify it started
tmux capture-pane -t opencode-real -p | head -20

echo ""
echo "=== If you see the opencode UI above, run this to attach ==="
echo "tmux attach -t opencode-real"