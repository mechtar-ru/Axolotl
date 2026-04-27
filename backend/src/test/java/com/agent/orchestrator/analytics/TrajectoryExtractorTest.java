package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class TrajectoryExtractorTest {

    @Test
    void shouldExtractTrajectoryFromSuccessfulExecution() {
        TrajectoryExtractor extractor = new TrajectoryExtractor();
        WorkflowSchema schema = createTestSchema();
        List<TrajectoryExtractor.ExecutionRecord> records = Arrays.asList(
            new TrajectoryExtractor.ExecutionRecord("node1", Map.of("data", "input1"),
                Map.of("result", "output1"), 1000L, true),
            new TrajectoryExtractor.ExecutionRecord("node2", Map.of("data", "input2"),
                Map.of("result", "output2"), 2000L, true)
        );

        TrajectoryExtractor.Trajectory trajectory = extractor.extractTrajectory(schema, records);

        assertNotNull(trajectory);
        assertTrue(trajectory.isSuccessful());
        assertEquals(2, trajectory.getSteps().size());
        assertEquals("source", trajectory.getSteps().get(0).getNodeType());
    }

    @Test
    void shouldReturnNullForFailedExecution() {
        TrajectoryExtractor extractor = new TrajectoryExtractor();
        WorkflowSchema schema = createTestSchema();
        List<TrajectoryExtractor.ExecutionRecord> records = Arrays.asList(
            new TrajectoryExtractor.ExecutionRecord("node1", Map.of("data", "input1"),
                Map.of("result", "output1"), 1000L, true),
            new TrajectoryExtractor.ExecutionRecord("node2", Map.of("data", "input2"),
                null, 2000L, false)
        );

        TrajectoryExtractor.Trajectory trajectory = extractor.extractTrajectory(schema, records);

        assertNull(trajectory);
    }

    @Test
    void shouldGenerateTrainingExample() {
        TrajectoryExtractor extractor = new TrajectoryExtractor();
        WorkflowSchema schema = createTestSchema();
        List<TrajectoryExtractor.ExecutionRecord> records = Arrays.asList(
            new TrajectoryExtractor.ExecutionRecord("node1", Map.of("prompt", "test"),
                Map.of("response", "result"), 1000L, true)
        );

        TrajectoryExtractor.Trajectory trajectory = extractor.extractTrajectory(schema, records);
        Map<String, String> example = trajectory.toTrainingExample();

        assertNotNull(example.get("prompt"));
        assertNotNull(example.get("completion"));
    }

    @Test
    void shouldReturnOnlySuccessfulTrajectories() {
        TrajectoryExtractor extractor = new TrajectoryExtractor();
        WorkflowSchema schema = createTestSchema();

        // Add successful trajectory
        List<TrajectoryExtractor.ExecutionRecord> successRecords = Arrays.asList(
            new TrajectoryExtractor.ExecutionRecord("node1", Map.of("data", "input"),
                Map.of("result", "output"), 1000L, true)
        );
        extractor.extractTrajectory(schema, successRecords);

        List<TrajectoryExtractor.Trajectory> successful = extractor.getSuccessfulTrajectories();
        assertEquals(1, successful.size());
    }

    @Test
    void shouldSortStepsByTimestamp() {
        TrajectoryExtractor extractor = new TrajectoryExtractor();
        WorkflowSchema schema = createTestSchema();
        List<TrajectoryExtractor.ExecutionRecord> records = Arrays.asList(
            new TrajectoryExtractor.ExecutionRecord("node2", Map.of("data", "input2"),
                Map.of("result", "output2"), 2000L, true),
            new TrajectoryExtractor.ExecutionRecord("node1", Map.of("data", "input1"),
                Map.of("result", "output1"), 1000L, true)
        );

        TrajectoryExtractor.Trajectory trajectory = extractor.extractTrajectory(schema, records);

        assertEquals("node1", trajectory.getSteps().get(0).getNodeId());
        assertEquals("node2", trajectory.getSteps().get(1).getNodeId());
    }

    private WorkflowSchema createTestSchema() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("schema1");
        schema.setName("Test Schema");

        Node node1 = new Node();
        node1.setId("node1");
        node1.setType("source");

        Node node2 = new Node();
        node2.setId("node2");
        node2.setType("agent");

        schema.setNodes(Arrays.asList(node1, node2));
        return schema;
    }
}
