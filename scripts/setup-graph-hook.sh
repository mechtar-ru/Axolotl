#!/bin/bash
# Install git post-commit hook to auto-update Neo4j graph
# Run once: ./scripts/setup-graph-hook.sh

HOOK_FILE=".git/hooks/post-commit"

cat > "$HOOK_FILE" << 'EOF'
#!/bin/bash
# Auto-update Neo4j graph after each commit
script_dir="$(cd "$(dirname "$0")/../../scripts" && pwd)"
if [ -f "$script_dir/update-graph.sh" ]; then
  bash "$script_dir/update-graph.sh" > /tmp/graph-update.log 2>&1 &
fi
EOF

chmod +x "$HOOK_FILE"
echo "Git post-commit hook installed: $HOOK_FILE"
echo "Graph will auto-update after each commit (runs in background)."
