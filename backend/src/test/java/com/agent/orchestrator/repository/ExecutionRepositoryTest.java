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
