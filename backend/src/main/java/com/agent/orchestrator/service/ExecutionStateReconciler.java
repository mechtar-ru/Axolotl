package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class ExecutionStateReconciler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateReconciler.class);

    private static final long RECONCILE_GRACE_PERIOD_SECONDS = 600;

    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final ExecutionWebSocketHandler webSocketHandler;

    public ExecutionStateReconciler(ExecutionRepository executionRepository,
                                    ExecutionStateManager stateManager,
                                    ExecutionWebSocketHandler webSocketHandler) {
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.webSocketHandler = webSocketHandler;
    }

    @PostConstruct
    public void init() {
        log.info("ExecutionStateReconciler initialized");
        reconcileOrphanedRuns();
    }

    @Scheduled(fixedRateString = "${axolotl.reconciler.interval:300000}")
    public void scheduledReconcile() {
        reconcileOrphanedRuns();
    }

    public void reconcileOrphanedRuns() {
        try {
            List<ExecutionRun> runningRuns = executionRepository.findByStatus("running");
            if (runningRuns == null || runningRuns.isEmpty()) {
                return;
            }

            Instant cutoff = Instant.now().minus(RECONCILE_GRACE_PERIOD_SECONDS, ChronoUnit.SECONDS);

            for (ExecutionRun run : runningRuns) {
                String schemaId = run.getSchemaId();
                if (schemaId != null && stateManager.getCurrentRunId(schemaId) != null) {
                    continue;
                }

                Instant startedAt = run.getStartedAt();
                if (startedAt == null) {
                    startedAt = Instant.now();
                }

                if (startedAt.isAfter(cutoff)) {
                    continue;
                }

                log.warn("Reconciling orphaned execution run: {} for schema {}, started at {}",
                        run.getId(), run.getSchemaId(), run.getStartedAt());

                executionRepository.updateRunStatus(run.getId(), "RECONCILED_FAILED",
                        "Execution was orphaned by server restart");

                if (schemaId != null && webSocketHandler != null) {
                    webSocketHandler.sendError(schemaId, "system",
                            "Execution reconciled: server restart detected. Previous run marked as failed.",
                            ExecutionWebSocketHandler.ErrorCategory.INTERNAL_ERROR);

                    webSocketHandler.sendLog(schemaId, "error",
                            "Execution run " + run.getId() + " was orphaned by restart and has been reconciled as failed.",
                            null,
                            ExecutionWebSocketHandler.ErrorCategory.INTERNAL_ERROR);

                    webSocketHandler.sendComplete(schemaId, 0, 0);
                }

                try {
                    List<NodeExecution> nodes = executionRepository.getNodeExecutionsByRun(run.getId());
                    if (nodes != null) {
                        for (NodeExecution nodeExec : nodes) {
                            if ("running".equals(nodeExec.getStatus()) || "pending".equals(nodeExec.getStatus())) {
                                executionRepository.updateNodeExecution(
                                        nodeExec.getId(), "RECONCILED_FAILED", null,
                                        nodeExec.getTokensUsed(), 0L, 0,
                                        "Reconciled: server restart occurred during execution");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to reconcile node executions for run {}: {}", run.getId(), e.getMessage(), e);
                }
            }

            if (!runningRuns.isEmpty()) {
                log.info("Reconciled {} orphaned execution run(s)", runningRuns.size());
            }

        } catch (Exception e) {
            log.error("Error during execution state reconciliation: {}", e.getMessage(), e);
        }
    }
}
