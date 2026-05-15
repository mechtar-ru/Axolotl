package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.service.SchemaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionResilienceControllerTest {

    @Mock SchemaService schemaService;

    @InjectMocks
    AgentController controller;

    @Test
    void getExecutionRuns_delegatesToService() {
        String schemaId = "schema-1";
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId(schemaId);
        run.setStatus("completed");

        when(schemaService.findExecutionRuns(schemaId)).thenReturn(List.of(run));

        List<ExecutionRun> result = controller.getExecutionRuns(schemaId);
        assertEquals(1, result.size());
        assertEquals("completed", result.get(0).getStatus());
    }

    @Test
    void getPausedRun_returnsNoContentWhenNone() {
        when(schemaService.getPausedRun("schema-1")).thenReturn(null);

        ResponseEntity<ExecutionRun> response = controller.getPausedRun("schema-1");
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNull(response.getBody());
    }

    @Test
    void resumeExecution_delegatesToService() {
        String schemaId = "schema-1";
        Map<String, String> result = controller.resumeExecution(schemaId);
        assertEquals("resumed", result.get("status"));
        verify(schemaService).resumeExecution(schemaId);
    }
}
