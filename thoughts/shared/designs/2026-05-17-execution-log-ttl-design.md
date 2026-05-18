date: 2026-05-17
topic: "Execution Log TTL Cleanup — Scheduled Deletion from Neo4j"
status: validated

## Problem Statement

Execution logs (`ExecutionRecord` nodes) persist to Neo4j indefinitely with no cleanup. Each schema execution creates a record — over time, these accumulate and bloat the database. The in-memory `executionHistory` list caps at 100 entries, but the Neo4j store has no limit.

## Constraints

- Must use existing `Neo4jExecutionRecordRepository` (not `Neo4jSchemaRepository` as older design suggested — the architecture has evolved)
- Records older than 14 days should be automatically purged
- Cleanup must be resilient — errors must not crash the application
- All changes are additive — no existing code deleted

## Architecture

Four small changes across 4 files:

### 1. Neo4jExecutionRecordRepository — add TTL delete query

Add a `@Query` method for time-based batch deletion:
```java
@Query("MATCH (r:ExecutionRecord) WHERE r.startTime < $cutoff DETACH DELETE r")
void deleteRecordsOlderThan(@Param("cutoff") long cutoffTimestamp);
```

Uses `DETACH DELETE` to remove the node and any relationships (forwards-compatible if we later add relationships to records).

### 2. ExecutionRepository facade — expose deletion

Add delegation method:
```java
public void deleteExecutionRecordsOlderThan(long cutoffTimestamp) {
    recordRepo.deleteRecordsOlderThan(cutoffTimestamp);
}
```

### 3. ExecutionLogCleanupService — scheduled cleanup

New `@Service` with:
- `@Scheduled(cron = "0 0 3 * * *")` — runs daily at 3:00 AM
- Computes cutoff: `System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000`
- Logs count and handles errors gracefully
- Single injected dependency: `ExecutionRepository`

**Rejected alternatives:**
- Neo4j's `ttl` config property → only works for specific Enterprise Edition features
- `@EventListener(ApplicationReadyEvent.class)` → would fire on every restart, not periodic

### 4. Application.java — enable scheduling

Add `@EnableScheduling` alongside `@SpringBootApplication`.

## Data Flow

```
⏰ 3:00 AM daily
  → ExecutionLogCleanupService.cleanupOldRecords()
    → ExecutionRepository.deleteExecutionRecordsOlderThan(cutoff)
      → Neo4jExecutionRecordRepository.deleteRecordsOlderThan(cutoff)
        → MATCH (r:ExecutionRecord) WHERE r.startTime < $cutoff DETACH DELETE r
```

## Error Handling

- Repository query failures: caught in the service, logged, not rethrown
- No retry — next daily run will catch remaining records
- Service is idempotent — safe to run manually or overlap (rare, clock-change scenario)

## Testing

- **ExecutionLogCleanupServiceTest**: Mock `ExecutionRepository`, verify cutoff is within ~now-14days, verify error handling
- Compilation check: `mvn compile`
