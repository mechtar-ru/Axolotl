package com.agent.orchestrator.service;

import com.agent.orchestrator.repository.ExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionLogCleanupServiceTest {

    @Mock ExecutionRepository executionRepository;
    @Captor ArgumentCaptor<Long> cutoffCaptor;

    ExecutionLogCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new ExecutionLogCleanupService(executionRepository);
    }

    @Test
    void cleanupOldRecords_deletesRecordsOlderThan14Days() {
        long beforeCall = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000;

        cleanupService.cleanupOldRecords();

        verify(executionRepository).deleteExecutionRecordsOlderThan(cutoffCaptor.capture());
        long cutoff = cutoffCaptor.getValue();

        // Cutoff should be approximately "now - 14 days" (within 5s tolerance)
        assertTrue(cutoff <= System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000 + 5000);
        assertTrue(cutoff >= beforeCall - 5000);
    }

    @Test
    void cleanupOldRecords_handlesRepositoryException() {
        doThrow(new RuntimeException("Neo4j error")).when(executionRepository)
            .deleteExecutionRecordsOlderThan(anyLong());

        assertDoesNotThrow(() -> cleanupService.cleanupOldRecords());
        verify(executionRepository).deleteExecutionRecordsOlderThan(anyLong());
    }
}
