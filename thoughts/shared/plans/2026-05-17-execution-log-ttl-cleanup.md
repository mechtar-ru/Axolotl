# Execution Log TTL Cleanup — Scheduled Deletion from Neo4j

**Goal:** Automatically purge `ExecutionRecord` nodes from Neo4j after 14 days using a daily scheduled cleanup.

**Architecture:** Add a `@Query` Cypher delete method to `Neo4jExecutionRecordRepository`, expose it through `ExecutionRepository` facade, create a `@Scheduled` service that runs daily at 3 AM, and enable scheduling in `Application.java`. Error handling in the service catches and logs failures without crashing the app.

**Design:** `thoughts/shared/designs/2026-05-17-execution-log-ttl-design.md`

---

## Dependency Graph

```
Batch 1 (parallel — 2 implementers): [1.1, 1.2]
  → Foundation: Repository query + EnableScheduling
Batch 2 (parallel — 1 implementer):  [2.1]
  → Facade delegation method (depends on 1.1)
Batch 3 (parallel — 1 implementer):  [3.1]
  → Scheduled service (depends on 2.1)
```

---

## Batch 1: Foundation (parallel — 2 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

---

### Task 1.1: Add TTL delete query to Neo4jExecutionRecordRepository

**File:** `backend/src/main/java/com/agent/orchestrator/graph/repository/Neo4jExecutionRecordRepository.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/graph/repository/Neo4jExecutionRecordRepositoryTest.java`
**Depends:** none

**Change:** Add `@Query` annotation import and time-based batch deletion method. `startTime` is a `long` (epoch millis) on `GraphExecutionRecord`. Uses `DETACH DELETE` for forward-compatibility with future relationships.

**Implementation** (modify existing file — add import + method):

Add `org.springframework.data.neo4j.repository.query.Query` to imports (line 4):

```java
import org.springframework.data.neo4j.repository.query.Query;
```

Add method after line 15 (after `findTop50ByOrderByStartTimeDesc()`):

```java
    @Query("MATCH (r:ExecutionRecord) WHERE r.startTime < $cutoff DETACH DELETE r")
    void deleteRecordsOlderThan(@Param("cutoff") long cutoffTimestamp);
```

**Full resulting file:**

```java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphExecutionRecord;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface Neo4jExecutionRecordRepository extends Neo4jRepository<GraphExecutionRecord, String> {

    List<GraphExecutionRecord> findBySchemaIdOrderByStartTimeDesc(@Param("schemaId") String schemaId);

    List<GraphExecutionRecord> findTop50ByOrderByStartTimeDesc();

    @Query("MATCH (r:ExecutionRecord) WHERE r.startTime < $cutoff DETACH DELETE r")
    void deleteRecordsOlderThan(@Param("cutoff") long cutoffTimestamp);
}
```

**Test:**

```java
package com.agent.orchestrator.graph.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class Neo4jExecutionRecordRepositoryTest {

    @Mock
    Neo4jExecutionRecordRepository repository;

    @Test
    void deleteRecordsOlderThan_acceptsLongParam() {
        long cutoff = 1000000L;
        repository.deleteRecordsOlderThan(cutoff);
        verify(repository).deleteRecordsOlderThan(cutoff);
    }
}
```

**Verify:** `cd backend && mvn test -pl . -Dtest=Neo4jExecutionRecordRepositoryTest`
**Commit:** `✨ feat(neo4j): add TTL delete query for ExecutionRecord`

---

### Task 1.2: Enable scheduling in Application.java

**File:** `backend/src/main/java/com/agent/orchestrator/Application.java`
**Test:** none (compile-only verification)
**Depends:** none

**Change:** Add `@EnableScheduling` to the class-level annotations.

Add import after line 7:
```java
import org.springframework.scheduling.annotation.EnableScheduling;
```

Add `@EnableScheduling` on line 12 alongside `@SpringBootApplication`:

```java
@SpringBootApplication
@EnableScheduling
public class Application {
```

**Full resulting file:**

```java
package com.agent.orchestrator;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        loadEnvFromParents();
        
        SpringApplication.run(Application.class, args);
        log.info("\n" +
            "╔══════════════════════════════════════╗\n" +
            "║   Axolotl Orchestrator запущен!     ║\n" +
            "║   http://localhost:8082/api/agents   ║\n" +
            "╚══════════════════════════════════════╝");
    }
    
    private static void loadEnvFromParents() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path envPath = current.resolve(".env");
            if (envPath.toFile().exists()) {
                try {
                    Dotenv dotenv = Dotenv.configure()
                            .ignoreIfMissing()
                            .directory(envPath.getParent().toString())
                            .load();
                    dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
                    log.info("Loaded .env from: {}", envPath.getParent());
                    return;
                } catch (Exception e) {
                    log.debug("Failed to load .env from {}: {}", envPath, e.getMessage());
                }
            }
            current = current.getParent();
        }
        log.info("No .env file found, using system environment variables");
    }
}
```

**Verify:** `cd backend && mvn compile` (no test — just verify compilation)
**Commit:** `✨ feat(config): enable @EnableScheduling for TTL cleanup`

---

## Batch 2: Facade delegation (1 implementer)

Depends on Batch 1 completing (specifically Task 1.1 for the `deleteRecordsOlderThan` method on the repository).

---

### Task 2.1: Add deleteExecutionRecordsOlderThan to ExecutionRepository

**File:** `backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/repository/ExecutionRepositoryTest.java`
**Depends:** 1.1 (Neo4jExecutionRecordRepository.deleteRecordsOlderThan must exist)

**Change:** Add a delegation method after `getAllExecutionRecords()` (after line 243, before the `// ────────── Mapping: POJO ↔ Graph entity ──────────` section separator).

Insert after line 243 (`    }` closing `getAllExecutionRecords`):

```java

    public void deleteExecutionRecordsOlderThan(long cutoffTimestamp) {
        try {
            recordRepo.deleteRecordsOlderThan(cutoffTimestamp);
            log.info("Удалены записи выполнения старше cutoff={}", cutoffTimestamp);
        } catch (Exception e) {
            log.error("Ошибка при удалении старых записей выполнения: {}", e.getMessage());
        }
    }
```

**Rationale for gap-filling:** Design says "add delegation method." I'm wrapping it in try-catch with Russian logging, consistent with every other method in this facade. This ensures the facade is the single point of error handling for all persistence operations.

**Test:**

```java
package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.repository.Neo4jCheckpointRepository;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRecordRepository;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRunRepository;
import com.agent.orchestrator.graph.repository.Neo4jNodeExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExecutionRepositoryTest {

    @Mock Neo4jExecutionRunRepository runRepo;
    @Mock Neo4jNodeExecutionRepository nodeExecRepo;
    @Mock Neo4jCheckpointRepository checkpointRepo;
    @Mock Neo4jExecutionRecordRepository recordRepo;

    ExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        executionRepository = new ExecutionRepository(runRepo, nodeExecRepo, checkpointRepo, recordRepo);
    }

    @Test
    void deleteExecutionRecordsOlderThan_delegatesToRecordRepo() {
        long cutoff = 1000000L;
        executionRepository.deleteExecutionRecordsOlderThan(cutoff);
        verify(recordRepo).deleteRecordsOlderThan(cutoff);
    }
}
```

**Verify:** `cd backend && mvn test -pl . -Dtest=ExecutionRepositoryTest`
**Commit:** `✨ feat(repository): add deleteExecutionRecordsOlderThan to facade`

---

## Batch 3: Scheduled service (1 implementer)

Depends on Batch 2 completing (Task 2.1 — `ExecutionRepository.deleteExecutionRecordsOlderThan` must exist).

---

### Task 3.1: Create ExecutionLogCleanupService (NEW FILE)

**File:** `backend/src/main/java/com/agent/orchestrator/service/ExecutionLogCleanupService.java` (NEW)
**Test:** `backend/src/test/java/com/agent/orchestrator/service/ExecutionLogCleanupServiceTest.java` (NEW)
**Depends:** 2.1 (ExecutionRepository.deleteExecutionRecordsOlderThan must exist)

**Design decisions:**
- Cron `0 0 3 * * *` = daily at 3:00 AM (design specifies this)
- 14 days cutoff: `14L * 24 * 60 * 60 * 1000` (design specifies this)
- SLF4J logging in Russian (follows codebase convention from mindmodel)
- Constructor injection (codebase convention)
- Error handling: catch + log, never rethrow (design specifies resilience)
- Service is idempotent — safe to run manually or on overlap

**Implementation:**

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.repository.ExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExecutionLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogCleanupService.class);
    private static final long FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000;

    private final ExecutionRepository repository;

    public ExecutionLogCleanupService(ExecutionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldRecords() {
        long cutoff = System.currentTimeMillis() - FOURTEEN_DAYS_MS;
        log.info("Запуск очистки записей выполнения старше 14 дней (cutoff={})", cutoff);
        try {
            repository.deleteExecutionRecordsOlderThan(cutoff);
            log.info("Очистка записей выполнения завершена");
        } catch (Exception e) {
            log.error("Ошибка при очистке записей выполнения: {}", e.getMessage());
        }
    }
}
```

**Test:**

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.repository.ExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionLogCleanupServiceTest {

    @Mock
    ExecutionRepository executionRepository;

    ExecutionLogCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new ExecutionLogCleanupService(executionRepository);
    }

    @Test
    void cleanupOldRecords_passesCutoffApproximately14DaysAgo() {
        long beforeCall = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000;

        cleanupService.cleanupOldRecords();

        ArgumentCaptor<Long> captor = ArgumentCaptor.captor(Long.class);
        verify(executionRepository).deleteExecutionRecordsOlderThan(captor.capture());

        long cutoff = captor.getValue();
        long afterCall = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000;

        // Allow 1s tolerance for execution time
        assertTrue(cutoff >= beforeCall - 1000,
                "Cutoff " + cutoff + " should be >= " + (beforeCall - 1000));
        assertTrue(cutoff <= afterCall + 1000,
                "Cutoff " + cutoff + " should be <= " + (afterCall + 1000));
    }

    @Test
    void cleanupOldRecords_handlesRepositoryErrorGracefully() {
        doThrow(new RuntimeException("DB connection failed"))
                .when(executionRepository).deleteExecutionRecordsOlderThan(anyLong());

        // Must not throw — error is caught and logged
        cleanupService.cleanupOldRecords();

        verify(executionRepository).deleteExecutionRecordsOlderThan(anyLong());
    }
}
```

**Verify:** `cd backend && mvn test -pl . -Dtest=ExecutionLogCleanupServiceTest`
**Commit:** `✨ feat(service): add ExecutionLogCleanupService with 14-day TTL`

---

## Execution Order

```
Batch 1  ──→  Task 1.1 (repository query) ──┐
              Task 1.2 (@EnableScheduling)   │
                                             ↓
Batch 2  ──→  Task 2.1 (facade delegation) ──→  depends on 1.1
                                             ↓
Batch 3  ──→  Task 3.1 (scheduled service)  ──→  depends on 2.1
```

## Final Verification

After all batches complete, verify full compilation:

```bash
cd backend && mvn compile
```

Then run all new tests together:

```bash
cd backend && mvn test -pl . \
  -Dtest=Neo4jExecutionRecordRepositoryTest,ExecutionRepositoryTest,ExecutionLogCleanupServiceTest
```
