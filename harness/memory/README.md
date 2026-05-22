# Memory

Persistent knowledge store for cross-session harness context.

## Schema

```yaml
# Each memory entry:
- category: TEST_RESULT
  content: "pipeline-review: PASS (3/3 assertions)"
  timestamp: 2026-05-20T10:00:00Z
  tags: [e2e, pipeline, review]

- category: INFRA
  content: "Backend on :8082, Neo4j on bolt://localhost:7687"
  timestamp: 2026-05-20T09:30:00Z
```

## Categories

- `INFRA` — environment state (ports, credentials)
- `TEST_RESULT` — last test outcome
- `WORKFLOW_RULE` — learned constraints
