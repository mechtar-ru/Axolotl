package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.model.GraphExecutionRun;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * On application startup, reconciles stale execution state:
 * marks any {@code ExecutionRun} with status {@code 'running'} or {@code 'paused'}
 * as {@code 'failed'} with an explanatory error, and cascades to all
 * {@code NodeExecution} records that are still {@code 'running'}.
 *
 * This prevents orphaned execution state after a JVM restart.
 * The typical flow is: user runs schema → process dies → restart →
 * StartupRecoveryService marks stale runs as failed and logs the count.
 */
@Service
public class StartupRecoveryService implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryService.class);

    private static final String RECOVERY_ERROR = "Execution interrupted by process restart";

    private final Neo4jExecutionRunRepository runRepository;

    public StartupRecoveryService(Neo4jExecutionRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        recover();
    }

    void recover() {
        List<GraphExecutionRun> staleRuns;
        try {
            staleRuns = runRepository.findStaleRuns();
        } catch (Exception e) {
            log.error("Startup recovery: failed to query stale runs: {}", e.getMessage(), e);
            return;
        }

        if (staleRuns == null || staleRuns.isEmpty()) {
            log.info("Startup recovery: no stale runs found");
            return;
        }

        int runCount = 0;
        int nodeCount = 0;

        for (GraphExecutionRun run : staleRuns) {
            String runId = run.getId();
            try {
                // Mark all running node executions as failed
                try {
                    runRepository.failRunningNodeExecutions(runId, RECOVERY_ERROR);
                } catch (Exception e) {
                    log.warn("Startup recovery: failed to fail node executions for run {}: {}", runId, e.getMessage());
                }

                // Mark the run itself as failed
                runRepository.forceUpdateRunStatus(runId, "failed", RECOVERY_ERROR);
                runCount++;
                log.info("Startup recovery: marked stale run {} (status={}) as failed", runId, run.getStatus());
            } catch (Exception e) {
                log.error("Startup recovery: failed to recover run {}: {}", runId, e.getMessage(), e);
            }
        }

        log.info("Startup recovery: marked {} stale runs, {} node executions as failed", runCount, nodeCount);
    }
}
