package com.agent.orchestrator.service;

import com.agent.orchestrator.repository.ExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Ежедневная очистка старых записей выполнения из Neo4j.
 * Запускается каждый день в 3:00 утра по крону.
 * ExecutionRecord entries older than 14 days are deleted.
 * ExecutionRun + NodeExecution nodes older than 30 days are deleted.
 */
@Service
public class ExecutionLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogCleanupService.class);
    private static final long FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000;
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;

    private final ExecutionRepository executionRepository;

    public ExecutionLogCleanupService(ExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldRecords() {
        long now = System.currentTimeMillis();
        cleanupRecords(now - FOURTEEN_DAYS_MS);
        cleanupRuns(now - THIRTY_DAYS_MS);
    }

    private void cleanupRecords(long cutoff) {
        log.info("Запуск очистки записей выполнения старше 14 дней");
        try {
            executionRepository.deleteExecutionRecordsOlderThan(cutoff);
            log.info("Очистка записей выполнения завершена");
        } catch (Exception e) {
            log.error("Ошибка при очистке записей выполнения: {}", e.getMessage());
        }
    }

    private void cleanupRuns(long cutoff) {
        log.info("Запуск очистки ExecutionRun/NodeExecution старше 30 дней");
        try {
            executionRepository.deleteRunsOlderThan(cutoff);
            log.info("Очистка ExecutionRun/NodeExecution завершена");
        } catch (Exception e) {
            log.error("Ошибка при очистке ExecutionRun/NodeExecution: {}", e.getMessage());
        }
    }
}
