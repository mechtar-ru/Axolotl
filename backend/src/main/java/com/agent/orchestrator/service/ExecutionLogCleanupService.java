package com.agent.orchestrator.service;

import com.agent.orchestrator.repository.ExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Ежедневная очистка старых записей выполнения из Neo4j.
 * Запускается каждый день в 3:00 утра по крону.
 * Records older than 14 days are deleted.
 */
@Service
public class ExecutionLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLogCleanupService.class);
    private static final long FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000;

    private final ExecutionRepository executionRepository;

    public ExecutionLogCleanupService(ExecutionRepository executionRepository) {
        this.executionRepository = executionRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldRecords() {
        long cutoff = System.currentTimeMillis() - FOURTEEN_DAYS_MS;
        log.info("Запуск ежедневной очистки записей выполнения старше 14 дней (cutoff={})", cutoff);
        try {
            executionRepository.deleteExecutionRecordsOlderThan(cutoff);
            log.info("Ежедневная очистка записей выполнения завершена");
        } catch (Exception e) {
            log.error("Ошибка при ежедневной очистке записей выполнения: {}", e.getMessage());
        }
    }
}
