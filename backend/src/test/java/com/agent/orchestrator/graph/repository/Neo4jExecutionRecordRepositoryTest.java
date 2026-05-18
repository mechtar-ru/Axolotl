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
